package net.ripster.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.audio.parseCue
import net.ripster.mobile.ui.components.*
import net.ripster.mobile.ui.screens.NowPlayingScreen
import net.ripster.mobile.ui.screens.NowPlayingState
import net.ripster.mobile.ui.screens.LibraryScreen
import net.ripster.mobile.ui.screens.LibraryItem
import net.ripster.mobile.ui.screens.DownloadsQueueScreen
import net.ripster.mobile.ui.screens.DownloadTask
import net.ripster.mobile.ui.screens.DownloadTaskStatus
import net.ripster.mobile.ui.screens.SettingsScreen
import net.ripster.mobile.ui.screens.DownloadQuality
import net.ripster.mobile.ui.screens.StorageInfo
import net.ripster.mobile.ui.navigation.BottomNav
import net.ripster.mobile.ui.navigation.RipsterDestination
import net.ripster.mobile.ui.screens.PairingScreen
import net.ripster.mobile.ui.screens.PairingAttemptState
import net.ripster.mobile.ui.screens.SoundCloudLiveScreen
import net.ripster.mobile.ui.screens.ServiceAccountsScreen
import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Демо-экран дизайн-системы.
 *
 * Он существует по одной причине: 2200 строк компонентов лежали в проекте и НЕ
 * ВЫЗЫВАЛИСЬ ниоткуда, поэтому владелец на экране видел ровно то же, что и до
 * их появления — шесть строк цветных квадратов. Собранный, но невидимый код
 * неотличим от отсутствующего: это тот же класс, что «посчитано и не показано».
 *
 * Поэтому здесь каждый компонент ВЫЗВАН, и вызван на живых переключателях темы
 * и плотности — то есть экран показывает не картинку компонента, а его
 * поведение. Это по-прежнему витрина, а не интерфейс приложения: сетевого слоя
 * нет, данные подставлены.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Прозрачные системные бары + инсеты в Compose. Без этого контент рисуется
        // от края до края «на глаз»: на телефонах с другой высотой статус-бара,
        // вырезом или жестовой навигацией верхняя панель и нижняя навигация
        // залезают под бары и обрезаются — «разрешение не подгоняется».
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { RipsterRoot() }
    }
}

/**
 * Корень настоящего приложения: тема/плотность/язык из [net.ripster.mobile.core.settings.AppSettings]
 * (не локальный стейт витрины), внутри — [net.ripster.mobile.ui.AppShell].
 * Старая витрина компонентов осталась в [Demo] этого файла — вызывается
 * вручную при отладке дизайн-системы, из UI больше не открывается.
 */
@Composable
private fun RipsterRoot() {
    val app = net.ripster.mobile.RipsterApp.from(androidx.compose.ui.platform.LocalContext.current)
    val s by app.settings.state.collectAsState()

    val theme = runCatching { RipsterThemeName.valueOf(s.theme) }.getOrDefault(RipsterThemeName.Dark)
    val density = runCatching { RipsterDensity.valueOf(s.density) }.getOrDefault(RipsterDensity.Normal)

    // Реальный масштаб шрифта из «плотности» — глобально, без смены dp
    // (мишени касания не едут). Именно это делает выбор размера ощутимым.
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = remember(baseDensity, s.fontScale) {
        androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * s.fontScale)
    }

    RipsterTheme(theme = theme, density = density) {
        CompositionLocalProvider(
            LocalAppLang provides AppLang.byTag(s.uiLang),
            androidx.compose.ui.platform.LocalDensity provides scaledDensity,
        ) {
            Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                // «Только что прошли первый запуск» — отдельный флаг, чтобы после
                // записи onboardingDone=true не потерять переход в раздел учёток.
                var justOnboarded by remember { mutableStateOf(false) }
                if (!s.onboardingDone && !justOnboarded) {
                    net.ripster.mobile.ui.screens.OnboardingScreen(
                        onFinish = {
                            app.settings.update { it.copy(onboardingDone = true) }
                            justOnboarded = true
                        },
                    )
                } else {
                    net.ripster.mobile.ui.AppShell(startInAccountsSettings = justOnboarded)
                }

                var showSplash by remember { mutableStateOf(true) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1700); showSplash = false
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showSplash && s.onboardingDone,
                    enter = androidx.compose.animation.EnterTransition.None,
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(400)),
                ) { net.ripster.mobile.ui.BootSplash() }
            }
        }
    }
}

private const val SAMPLE_CUE = """
PERFORMER "Marsh"
TITLE "Endless Mix"
FILE "mix.flac" WAVE
  TRACK 01 AUDIO
    TITLE "Intro"
    INDEX 01 00:00:00
  TRACK 02 AUDIO
    TITLE "Time"
    PERFORMER "Volen Sentir"
    INDEX 00 05:28:00
    INDEX 01 05:30:37
  TRACK 03 AUDIO
    TITLE "Outro"
    INDEX 01 12:00:74
"""

@Composable
private fun Demo() {
    var theme by remember { mutableStateOf(RipsterThemeName.Dark) }
    var density by remember { mutableStateOf(RipsterDensity.Normal) }
    // 29.08.2026: первый СОБРАННЫЙ экран, не витрина компонентов по одному.
    // Переключатель оставлен, а не вырезана старая витрина, — она всё ещё
    // нужна: проверять компонент в изоляции (все состояния SeekStrip разом,
    // например) быстрее на витрине, чем разбирать готовый экран по кусочку.
    // 29.08.2026: добавлены демо-режимы для Библиотеки/Загрузок/Настроек и
    // сборного режима "Приложение" (BottomNav + MiniPlayer поверх реального
    // содержимого) — те же экраны, что запросил владелец в этом заходе
    // ("достроить остальные экраны плеера"). 0=витрина,1=Now Playing,
    // 2=Библиотека,3=Загрузки,4=Настройки,5=Приложение (сборка всех вместе).
    var screen by remember { mutableStateOf(0) }

    RipsterTheme(theme = theme, density = density) {
        // Язык витрины. Набор — ровно как в десктопе (ru/en/hi/ja/zh, см.
        // ui/i18n/Strings.kt). Пока фиксирован RU под остальной демо-текст;
        // переключатель придёт с настоящим экраном настроек.
        CompositionLocalProvider(LocalAppLang provides AppLang.RU) {
        val c = RipsterTheme.colors
        Column(Modifier.fillMaxSize().background(c.surface_canvas)) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chips(
                    listOf(
                        "Витрина", "Now Playing", "Библиотека", "Загрузки", "Настройки",
                        "Приложение", "Сопряжение", tr("chip.sc", LocalAppLang.current),
                        tr("chip.accounts", LocalAppLang.current),
                    ),
                    screen,
                ) { screen = it }
            }
            when (screen) {
                1 -> NowPlayingDemo(theme, density)
                2 -> LibraryDemo(onOpenSettings = { screen = 4 })
                3 -> DownloadsDemo()
                4 -> SettingsDemo()
                5 -> AppDemo()
                6 -> PairingDemo()
                7 -> SoundCloudLiveScreen()
                8 -> ServiceAccountsScreen()
                else -> ComponentShowcase(
                    theme = theme, density = density,
                    onThemeChange = { theme = it }, onDensityChange = { density = it },
                )
            }
        }
        }
    }
}

@Composable
private fun NowPlayingDemo(theme: RipsterThemeName, density: RipsterDensity) {
    var positionMs by remember { mutableStateOf(96_000L) }
    var isPlaying by remember { mutableStateOf(true) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    val durationMs = 254_000L

    // Тиканье позиции — ровно чтобы экран был ЖИВЫМ на скриншоте/записи, а не
    // статичным кадром: play/pause и перемотка проверяются тем же взглядом,
    // которым владелец проверяет реальный плеер.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(500)
            positionMs = (positionMs + 500).coerceAtMost(durationMs)
            if (positionMs >= durationMs) isPlaying = false
        }
    }

    NowPlayingScreen(
        state = NowPlayingState(
            title = "Bigger Than All Of Us (Above & Beyond Club Mix)",
            artist = "Justine Suissa & Above & Beyond",
            album = "Anjunabeats Volume 15",
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            shuffle = shuffle,
            repeat = repeat,
            quality = QualityBadgeState.Match,
            format = "FLAC · 24-bit/96kHz",
        ),
        onSeek = { positionMs = it },
        onScrubPreview = {},
        onPlayPause = { isPlaying = !isPlaying },
        onNext = { positionMs = 0L },
        onPrevious = { positionMs = 0L },
        onToggleShuffle = { shuffle = !shuffle },
        onToggleRepeat = { repeat = !repeat },
        onDownloadAlbum = {},
    )
}

@Composable
private fun LibraryDemo(onOpenSettings: () -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    val allItems = remember {
        listOf(
            LibraryItem("1", "Anjunabeats Volume 15", "Above & Beyond", "FLAC \u00b7 24-bit/96kHz", 20),
            LibraryItem("2", "Discovery", "Daft Punk", "FLAC \u00b7 16-bit/44.1kHz", 14),
            LibraryItem("3", "In Rainbows", "Radiohead", "FLAC \u00b7 24-bit/48kHz", 10),
            LibraryItem("4", "Music Has The Right To Children", "Boards of Canada", "FLAC \u00b7 16-bit/44.1kHz", 17),
        )
    }
    val filtered = if (query.isBlank()) allItems else allItems.filter {
        it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
    }
    LibraryScreen(
        items = filtered,
        searchQuery = query,
        onSearchQueryChange = { query = it },
        onItemClick = {},
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun DownloadsDemo() {
    val tasks = remember {
        listOf(
            DownloadTask("a", "Bigger Than All Of Us", "Above & Beyond", DownloadTaskStatus.Done),
            DownloadTask("b", "Random Access Memories", "Daft Punk", DownloadTaskStatus.Downloading, progress = 0.62f),
            DownloadTask("c", "Amnesiac", "Radiohead", DownloadTaskStatus.Downloading, progress = null),
            DownloadTask("d", "Geogaddi", "Boards of Canada", DownloadTaskStatus.Queued),
            DownloadTask(
                "e", "Kid A", "Radiohead", DownloadTaskStatus.Failed,
                errorReason = "\u0441\u0435\u0440\u0432\u0438\u0441 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u0435\u043d, \u043f\u043e\u043f\u0440\u043e\u0431\u0443\u0439\u0442\u0435 \u043f\u043e\u0437\u0436\u0435",
            ),
        )
    }
    DownloadsQueueScreen(tasks = tasks, onRetry = {}, onCancel = {})
}

@Composable
private fun SettingsDemo() {
    var quality by remember { mutableStateOf(DownloadQuality.Standard) }
    SettingsScreen(
        selectedQuality = quality,
        onQualityChange = { quality = it },
        storage = StorageInfo(
            downloadsPath = "/storage/emulated/0/Music/Ripster",
            usedBytes = 4_200_000_000L,
            availableBytes = 26_800_000_000L,
        ),
        onOpenDownloadsFolder = {},
        onClearCache = {},
    )
}

/**
 * "\u041f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435" \u2014 \u0441\u0431\u043e\u0440\u043a\u0430 BottomNav + MiniPlayer \u043d\u0430\u0434 \u0440\u0435\u0430\u043b\u044c\u043d\u044b\u043c \u044d\u043a\u0440\u0430\u043d\u043e\u043c,
 * \u0430 \u043d\u0435 \u043e\u0442\u0434\u0435\u043b\u044c\u043d\u0430\u044f \u0432\u0438\u0442\u0440\u0438\u043d\u0430 \u043a\u0430\u0436\u0434\u043e\u0433\u043e \u043a\u043e\u043c\u043f\u043e\u043d\u0435\u043d\u0442\u0430 \u043f\u043e \u043e\u0442\u0434\u0435\u043b\u044c\u043d\u043e\u0441\u0442\u0438: \u0442\u043e\u043b\u044c\u043a\u043e \u0442\u0430\u043a \u0432\u0438\u0434\u043d\u043e, \u0434\u0435\u0440\u0436\u0438\u0442
 * \u043b\u0438 \u043c\u0438\u043d\u0438-\u043f\u043b\u0435\u0435\u0440 \u0441\u0432\u043e\u0451 \u043c\u0435\u0441\u0442\u043e \u043d\u0430\u0434 \u043d\u0430\u0432\u0438\u0433\u0430\u0446\u0438\u0435\u0439 \u043f\u0440\u0438 \u0440\u0435\u0430\u043b\u044c\u043d\u043e\u0439 \u043f\u0440\u043e\u043a\u0440\u0443\u0442\u043a\u0435 \u0441\u043f\u0438\u0441\u043a\u0430.
 * Radar \u2014 \u0447\u0435\u0441\u0442\u043d\u0430\u044f \u0437\u0430\u0433\u043b\u0443\u0448\u043a\u0430 \u0432\u043c\u0435\u0441\u0442\u043e \u044d\u043a\u0440\u0430\u043d\u0430: \u0440\u0430\u0434\u0430\u0440 \u043d\u0430 \u043c\u043e\u0431\u0438\u043b\u044c\u043d\u043e\u0439 \u0441\u0442\u043e\u0440\u043e\u043d\u0435 \u0432 \u044d\u0442\u043e\u043c \u0437\u0430\u0445\u043e\u0434\u0435 \u043d\u0435 \u043f\u0440\u043e\u0435\u043a\u0442\u0438\u0440\u043e\u0432\u0430\u043b\u0441\u044f.
 */
@Composable
private fun AppDemo() {
    var dest by remember { mutableStateOf(RipsterDestination.Player) }
    var positionMs by remember { mutableStateOf(96_000L) }
    var isPlaying by remember { mutableStateOf(true) }
    val durationMs = 254_000L

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(500)
            positionMs = (positionMs + 500).coerceAtMost(durationMs)
            if (positionMs >= durationMs) isPlaying = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (dest) {
                RipsterDestination.Player -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Label(
                        "\u042d\u043a\u0440\u0430\u043d \u043f\u043b\u0435\u0435\u0440\u0430 \u043e\u0442\u043a\u0440\u044b\u0432\u0430\u0435\u0442\u0441\u044f \u043f\u043e \u0442\u0430\u043f\u0443 \u043d\u0430 MiniPlayer \u043d\u0438\u0436\u0435",
                        RipsterTheme.colors.text_tertiary, 12.sp,
                    )
                }
                RipsterDestination.Radar -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Label(
                        "\u0420\u0430\u0434\u0430\u0440 \u2014 \u043d\u0435 \u0432 \u043e\u0431\u044a\u0451\u043c\u0435 \u044d\u0442\u043e\u0433\u043e \u0437\u0430\u0445\u043e\u0434\u0430, \u0437\u0430\u0433\u043b\u0443\u0448\u043a\u0430 \u043d\u0430\u043c\u0435\u0440\u0435\u043d\u043d\u0430",
                        RipsterTheme.colors.text_tertiary, 12.sp,
                    )
                }
                RipsterDestination.Library -> LibraryDemo()
                RipsterDestination.Downloads -> DownloadsDemo()
                RipsterDestination.Tools, RipsterDestination.Home, RipsterDestination.Search ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Label("demo stub", RipsterTheme.colors.text_tertiary, 12.sp)
                    }
            }
        }
        MiniPlayer(
            state = MiniPlayerState(
                title = "Bigger Than All Of Us (Above & Beyond Club Mix)",
                artist = "Justine Suissa & Above & Beyond",
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
            ),
            onPlayPause = { isPlaying = !isPlaying },
            onPrev = {},
            onNext = {},
            onClose = {},
            onExpand = {},
        )
        BottomNav(current = dest, onSelect = { dest = it })
    }
}

/**
 * Демо-режим экрана сопряжения. НЕТ реального сетевого
 * рукопожатия (протокол из ARCH_2026-08-29_pc_phone_pairing.md ещё не
 * реализован, только спроектирован) — честная имитация задержкой и
 * жёстко заданным кодом-паролем успеха ("000000" — успех, любой
 * другой ввод — честный отказ с причиной, не выдуманный успех наоборот).
 * Нужно только чтобы экран было чем проверить визуально до того, как
 * появится реальный handshake с /api/pair.
 */
@Composable
private fun PairingDemo() {
    var code by remember { mutableStateOf("") }
    var attemptState by remember { mutableStateOf(PairingAttemptState.Idle) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    PairingScreen(
        code = code,
        onCodeChange = {
            code = it
            if (attemptState == PairingAttemptState.Error) attemptState = PairingAttemptState.Idle
        },
        attemptState = attemptState,
        errorMessage = errorMessage,
        onSubmit = {
            if (code.length == 6 && attemptState != PairingAttemptState.Connecting) {
                attemptState = PairingAttemptState.Connecting
                scope.launch {
                    delay(1400)
                    if (code == "000000") {
                        attemptState = PairingAttemptState.Success
                    } else {
                        errorMessage = "Код не найден или истёк — проверьте экран ПК"
                        attemptState = PairingAttemptState.Error
                    }
                }
            }
        },
    )
}

@Composable
private fun ComponentShowcase(
    theme: RipsterThemeName,
    density: RipsterDensity,
    onThemeChange: (RipsterThemeName) -> Unit,
    onDensityChange: (RipsterDensity) -> Unit,
) {
val c = RipsterTheme.colors
Column(
    Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 18.dp)
) {
    Label("Ripster mobile — design system", c.text_primary, 19.sp, FontWeight.Bold)
    Gap(14)

    // ── переключатели: тема и плотность влияют на ВСЁ ниже ───────────
    Label("тема", c.text_tertiary, 11.sp)
    Gap(4)
    Chips(RipsterThemeName.entries.map { it.name }, theme.ordinal) {
        onThemeChange(RipsterThemeName.entries[it])
    }
    Gap(10)
    Label("плотность", c.text_tertiary, 11.sp)
    Gap(4)
    Chips(RipsterDensity.entries.map { it.name }, density.ordinal) {
        onDensityChange(RipsterDensity.entries[it])
    }

    Section("Полоса перемотки — горизонтальная")
    var posH by remember { mutableStateOf(96_000L) }
    SeekStrip(
        positionMs = posH,
        durationMs = 720_987L,
        bufferedMs = posH + 180_000L,
        onSeek = { posH = it },
        state = SeekPlaybackState.Playing,
        modifier = Modifier.fillMaxWidth(),
    )
    Gap(6)
    Label("позиция ${posH / 1000} с из 720 — тяни", RipsterTheme.colors.text_tertiary, 11.sp)

    Section("Она же в остальных состояниях")
    listOf(
        SeekPlaybackState.Paused to "Paused",
        SeekPlaybackState.Buffering to "Buffering",
        SeekPlaybackState.Offline to "Offline",
        SeekPlaybackState.Error to "Error",
    ).forEach { (st, name) ->
        Label(name, RipsterTheme.colors.text_tertiary, 10.sp)
        SeekStrip(
            positionMs = 240_000L, durationMs = 720_987L,
            onSeek = {}, state = st, modifier = Modifier.fillMaxWidth(),
        )
        Gap(8)
    }

    Section("Кнопки — четыре уровня, не четыре цвета")
    var clicks by remember { mutableStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RipsterButton("Скачать", { clicks++ }, level = ButtonLevel.Primary)
        RipsterButton("В очередь", { clicks++ }, level = ButtonLevel.Standard)
    }
    Gap(8)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RipsterButton("Позже", { clicks++ }, level = ButtonLevel.Quiet)
        RipsterButton("Удалить", { clicks++ }, level = ButtonLevel.Danger)
    }
    Gap(6)
    Label("нажатий: $clicks", RipsterTheme.colors.text_tertiary, 11.sp)

    Section("Бейджи качества — честностный контракт")
    QualityBadge(QualityBadgeState.NotMeasured)
    Gap(6)
    QualityBadge(QualityBadgeState.Measuring(0.42f))
    Gap(6)
    QualityBadge(QualityBadgeState.Measuring(null))
    Gap(6)
    QualityBadge(QualityBadgeState.Match)
    Gap(6)
    QualityBadge(QualityBadgeState.Mismatch(promised = "FLAC 24/96", actual = "44.1 kHz"))
    Gap(6)
    QualityBadge(QualityBadgeState.Fake("MP3 в контейнере FLAC"))

    Section("CUE — тот же парсер, что на десктопе")
    val cue = parseCue(SAMPLE_CUE)
    Label("${cue.album} — ${cue.albumArtist}", RipsterTheme.colors.text_primary,
          13.sp, FontWeight.Bold)
    cue.tracks.forEach { t ->
        Label("  ${t.num}. ${t.title} — ${t.artist}   ${"%.3f".format(t.start)} s",
              RipsterTheme.colors.text_secondary, 12.sp)
    }

    Gap(28)
}
}

// ── мелкая обвязка витрины. Намеренно НЕ компоненты дизайн-системы: это леса,
//    а не здание, и путать их с настоящими Surfaces/Buttons нельзя.
@Composable
private fun Label(t: String, color: androidx.compose.ui.graphics.Color,
                  size: androidx.compose.ui.unit.TextUnit,
                  weight: FontWeight = FontWeight.Normal) =
    BasicText(t, style = TextStyle(color = color, fontSize = size, fontWeight = weight))

@Composable private fun Gap(dp: Int) = Spacer(Modifier.height(dp.dp))

@Composable
private fun Section(title: String) {
    Gap(22)
    Label(title, RipsterTheme.colors.text_secondary, 13.sp, FontWeight.Bold)
    Gap(8)
}

@Composable
private fun Chips(items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val c = RipsterTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, name ->
            val on = i == selected
            Box(
                Modifier
                    .background(if (on) c.surface_active else c.surface_raised,
                                RoundedCornerShape(7.dp))
                    .border(1.dp, if (on) c.border_strong else c.border_subtle,
                            RoundedCornerShape(7.dp))
                    .clickable { onPick(i) }
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            ) {
                Label(name, if (on) c.text_primary else c.text_tertiary, 11.sp)
            }
        }
    }
}
