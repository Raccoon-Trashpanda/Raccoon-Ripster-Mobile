package net.ripster.mobile

import net.ripster.mobile.core.errors.attempt

import android.app.Application
import android.content.Context
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.ripster.mobile.core.db.RipsterDb
import net.ripster.mobile.core.download.DownloadQueue
import net.ripster.mobile.core.download.DownloadWorker
import net.ripster.mobile.core.pair.PcBridge
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.core.settings.AppSettings
import net.ripster.mobile.core.settings.CredentialStore
import net.ripster.mobile.core.storage.SafStorage
import net.ripster.mobile.player.PlayerController
import net.ripster.mobile.service.apple.AppleProxyClient
import net.ripster.mobile.service.bbc.BbcClient
import net.ripster.mobile.service.deezer.DeezerClient
import net.ripster.mobile.service.jiosaavn.JioSaavnClient
import net.ripster.mobile.service.qobuz.QobuzClient
import net.ripster.mobile.service.soundcloud.SoundCloudClient
import net.ripster.mobile.service.spotify.SpotifyConvertClient
import net.ripster.mobile.service.tidal.TidalClient
import net.ripster.mobile.service.yandex.YandexMusicClient

/**
 * Точка сборки: строит сторы/БД и регистрирует клиентов сервисов при старте —
 * аналог того, как десктоп при импорте `ripster.engines` наполняет `REGISTRY`.
 *
 * DI-фреймворк пока не заводим: одно место сборки, доступ через
 * `RipsterApp.from(context)`.
 */
class RipsterApp : Application() {

    lateinit var credentials: CredentialStore
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var db: RipsterDb
        private set
    lateinit var downloads: DownloadQueue
        private set
    lateinit var storage: SafStorage
        private set
    lateinit var player: PlayerController
        private set
    lateinit var pcBridge: PcBridge
        private set
    lateinit var localRadar: net.ripster.mobile.core.radar.LocalRadar
        private set

    @Volatile
    private var lastActivityPush = 0L

    override fun onCreate() {
        super.onCreate()
        credentials = CredentialStore(this)
        settings = AppSettings.of(this)
        pcBridge = PcBridge(this)
        storage = SafStorage(this)
        player = PlayerController(this)
        player.nativeEnabled = settings.state.value.nativeEngine
        MainScope().launch {
            settings.state.collect { player.nativeEnabled = it.nativeEngine }
        }
        db = RipsterDb.build(this)
        // Плеер умеет восстановить прошлую очередь — даём ему доступ к библиотеке.
        player.bindLibrary { ids ->
            val rows = db.library().byIds(ids)
            ids.mapNotNull { id -> rows.firstOrNull { it.id == id } }   // сохранить порядок
        }
        // Файл пропал (удалён/перемещён) → плеер зовёт это, чтобы вычистить
        // мёртвую библиотечную запись и не спотыкаться об неё снова.
        player.bindDeadPath { path -> attempt { db.library().forgetPath(path) } }
        // История прослушивания — «память» того, что игралось, даже вне библиотеки.
        // Вкус слушателя для станций — те же 200 последних прослушиваний.
        player.bindTaste { db.plays().recent(200).map { it.artist } }
        // Продолжение эфира спрашивает ТО ЖЕ качество, что и обычное
        // воспроизведение: см. bindQuality.
        player.bindQuality { settings.state.value.qualityFor(onWifi = true) }

        player.bindPlayLog { row ->
            attempt { db.plays().add(row) }
            // отдать ПК свежую активность, но не чаще раза в минуту
            val now = System.currentTimeMillis()
            if (pcBridge.paired && now - lastActivityPush > 60_000) {
                lastActivityPush = now
                pushActivityToPc()
            }
        }
        downloads = DownloadQueue(
            context = this,
            dao = db.downloads(),
            wifiOnlyProvider = { settings.state.value.wifiOnly },
        )
        DownloadWorker.ensureChannel(this)

        // Свести очередь загрузок с действительностью: строки, оставшиеся
        // «качается» после гибели процесса, некому продолжать — см.
        // DownloadQueue.reconcileOnStart.
        MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            attempt { downloads.reconcileOnStart() }
        }

        // Разовая уборка библиотеки при запуске: убрать записи о файлах,
        // которых больше нет, и склеить дубли на один путь. Дубли копились,
        // пока ключом записи был id задачи загрузки; ссылки в кэш ОС чистит
        // сама, и такие строки молча переставали играть.
        MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            attempt {
                val dao = db.library()
                val rows = dao.observeAll().first()
                val plan = net.ripster.mobile.core.library.LibraryUpkeep.plan(rows) { path ->
                    when {
                        path.startsWith("/") -> java.io.File(path).exists()
                        path.startsWith("content://") -> runCatching {
                            androidx.documentfile.provider.DocumentFile
                                .fromSingleUri(this@RipsterApp, android.net.Uri.parse(path))?.exists()
                        }.getOrNull()
                        // Незнакомый вид адреса — не наше дело, а не приговор.
                        else -> null
                    }
                }
                if (plan.forget.isNotEmpty()) {
                    plan.forget.forEach { dao.forgetPath(it) }
                    plan.keep.forEach { dao.upsert(it) }
                    android.util.Log.i(
                        "RipsterLibrary",
                        "upkeep: ${plan.ghosts} gone, ${plan.duplicates} duplicate groups, " +
                            "${plan.keep.size} re-keyed",
                    )
                }
            }
        }

        // Дирижёр жанров: один на приложение, помнит выученное между
        // запусками. Пишем не чаще раза в минуту — учится он на том, что и
        // так качается, и дёргать диск на каждый трек незачем.
        run {
            val prefs = getSharedPreferences("genre_brain", MODE_PRIVATE)
            net.ripster.mobile.core.service.StationBuilder.genres
                .restoreFrom(prefs.getString("learned", null))
            MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(60_000)
                    val g = net.ripster.mobile.core.service.StationBuilder.genres
                    runCatching {
                        prefs.edit().putString("learned", g.serialize()).apply()
                    }
                }
            }
        }

        localRadar = net.ripster.mobile.core.radar.LocalRadar(db)
        net.ripster.mobile.core.radar.RadarWorker.schedule(this)

        registerClients()

        // Если телефон в паре с ПК — тихо подтянуть свежие учётки при каждом
        // старте: добавил токен на ПК (Яндекс, Qobuz, …) — на телефоне он есть
        // со следующего запуска, без ручного «Забрать учётки с ПК».
        if (pcBridge.paired) {
            MainScope().launch {
                attempt { pcBridge.syncCredentials(credentials) }
                    .getOrNull()?.getOrNull()?.let { n -> if (n > 0) registerClients() }
                runCatching { pushActivityToPc() }
            }
        }
    }

    /**
     * Отдать ПК-версии, что телефон скачал и что слушал (история/аналитика на
     * ПК видит активность телефона). Best-effort: шлём срез последних записей,
     * ПК сам дедупит. Вызывается на старте и после новых прослушиваний.
     */
    fun pushActivityToPc() {
        if (!pcBridge.paired) return
        MainScope().launch {
            attempt {
                val plays = db.plays().recent(100)
                val dls = db.downloads().recent(100).filter { it.state == "DONE" || it.state == "FAILED" }
                fun j(s: String?) = pcBridge.jsonEscape(s ?: "")
                fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime().toString().take(19)
                val playsJson = plays.joinToString(",", "[", "]") { p ->
                    """{"title":${j(p.title)},"artist":${j(p.artist)},"album":${j(p.album)},""" +
                        """"service":${j(p.serviceId)},"genre":${j(p.genre)},"at":${j(iso(p.playedAt))}}"""
                }
                val dlsJson = dls.joinToString(",", "[", "]") { d ->
                    """{"title":${j(d.title)},"artist":${j(d.artist)},"album":"",""" +
                        """"service":${j(d.serviceId)},"format":${j(d.qualityId)},""" +
                        """"ok":${d.state == "DONE"},"at":${j(iso(d.createdAt))}}"""
                }
                if (plays.isNotEmpty() || dls.isNotEmpty()) {
                    pcBridge.pushActivity(playsJson, dlsJson)
                }
            }
        }
    }

    /**
     * (Пере)регистрация клиентов. Вызывается при старте и после изменения
     * учёток — клиент SoundCloud берёт OAuth-токен из стора в момент создания.
     */
    fun registerClients() {
        ServiceRegistry.register(
            SoundCloudClient(
                oauthToken = credentials.get(CredentialStore.Key.SOUNDCLOUD_OAUTH),
                cacheDir = cacheDir,
            ),
        )
        // BBC — аккаунт не нужен (nondrm-download), но по ссылке, не поиском.
        ServiceRegistry.register(BbcClient(cacheDir = cacheDir))
        // JioSaavn — без логина и без ПК: поиск и ссылка, звук — локальной
        // расшифровкой прямой ссылки на незащищённый CDN. Регистрируем всегда.
        ServiceRegistry.register(JioSaavnClient(cacheDir = cacheDir))
        // Spotify — только конверсия (ISRC → Deezer/Qobuz). Регистрируем всегда;
        // isConfigured() сам вернёт false, если качать некуда.
        ServiceRegistry.register(SpotifyConvertClient())
        // Apple Music — только через сопряжение с ПК. isConfigured() = ПК спарен
        // и умеет apple_music.
        ServiceRegistry.register(AppleProxyClient(pc = pcBridge, cacheDir = cacheDir))
        credentials.get(CredentialStore.Key.DEEZER_ARL)?.let { arl ->
            ServiceRegistry.register(DeezerClient(arl = arl, cacheDir = cacheDir))
        }

        credentials.get(CredentialStore.Key.TIDAL_OAUTH)?.let { td ->
            ServiceRegistry.register(TidalClient(storedJson = td, cacheDir = cacheDir))
        }
        credentials.get(CredentialStore.Key.YANDEX_OAUTH)?.let { yt ->
            ServiceRegistry.register(YandexMusicClient(oauthToken = yt, cacheDir = cacheDir))
        }
        val bpUser = credentials.get(CredentialStore.Key.BEATPORT_USERNAME)
        val bpPass = credentials.get(CredentialStore.Key.BEATPORT_PASSWORD)
        if (!bpUser.isNullOrBlank() && !bpPass.isNullOrBlank()) {
            ServiceRegistry.register(
                net.ripster.mobile.service.beatport.BeatportClient(
                    username = bpUser, password = bpPass, cacheDir = cacheDir,
                ),
            )
        }

        val qbEmail = credentials.get(CredentialStore.Key.QOBUZ_EMAIL)
        val qbToken = credentials.get(CredentialStore.Key.QOBUZ_TOKEN)
        val qbAppId = credentials.get(CredentialStore.Key.QOBUZ_APP_ID)
        // Какой токен реально берёт клиент. Секрета тут нет: длина и первые
        // четыре символа. Без этого «токен недействителен» неотличимо от
        // «взяли не тот токен», и 06.09.2026 я на этом встал — на телефоне
        // сохранён живой токен, а загрузка всё равно ругается.
        android.util.Log.i(
            "RipsterQobuz",
            "register: token len=${qbToken?.length ?: 0} head=${qbToken?.take(4).orEmpty()} " +
                "appId=${qbAppId.orEmpty().take(4)} " +
                // Секрет тоже нужен головой: «токен верный, ключи верные, а
                // подпись не сходится» неотличимо от «взяли ЧУЖОЙ секрет»,
                // пока не видно, какой именно взят. Четыре символа — не секрет.
                "secret=${credentials.get(CredentialStore.Key.QOBUZ_SECRET).orEmpty().take(4)} " +
                "email=${!qbEmail.isNullOrBlank()}",
        )
        // Регистрируем Qobuz, если есть ЛЮБОЙ путь входа: email+пароль, готовый
        // токен, ИЛИ ручные app_id+secret (тогда qualities()/isConfigured сами
        // разберутся). Раньше при вводе только app_id движок не появлялся вовсе.
        if (!qbEmail.isNullOrBlank() || !qbToken.isNullOrBlank() || !qbAppId.isNullOrBlank()) {
            ServiceRegistry.register(
                QobuzClient(
                    email = qbEmail,
                    password = credentials.get(CredentialStore.Key.QOBUZ_PASSWORD),
                    token = qbToken,
                    appId = credentials.get(CredentialStore.Key.QOBUZ_APP_ID),
                    secret = credentials.get(CredentialStore.Key.QOBUZ_SECRET),
                    cacheDir = cacheDir,
                    // Пригодность токена решает, можно ли синку с ПК перекрыть
                    // ручной ввод: набранное руками держится, ПОКА ОНО РАБОТАЕТ.
                    onTokenVerdict = { ok ->
                        if (ok) credentials.markWorking(CredentialStore.Key.QOBUZ_TOKEN)
                        else credentials.markRejected(CredentialStore.Key.QOBUZ_TOKEN)
                    },
                ),
            )
        }
        // Разбудить экран поиска — он переспросит configured() без перезапуска.
        ServiceRegistry.bumpGeneration()

        // Прогреть авторизацию клиентов в фоне: первый поиск не должен платить
        // за логин/скрейп внутри своего таймаута (жалоба: «Qobuz не ответил за
        // 15 с» + «software caused connection abort» на холодном старте).
        MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            ServiceRegistry.all().forEach { c -> attempt { c.warmUp() } }
        }
    }

    companion object {
        fun from(context: Context): RipsterApp =
            context.applicationContext as RipsterApp
    }
}
