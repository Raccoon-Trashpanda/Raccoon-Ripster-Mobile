package net.ripster.mobile.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Шифрованный стор секретов сервисов: ARL, OAuth-токены, пароли, app_id.
 *
 * Две дороги наполнения (см. `ARCH_2026-08-29_pc_phone_pairing.md`):
 *   1. ручной ввод в Настройках на телефоне;
 *   2. синк с ПК по сопряжению (`/api/pair/credentials` + WS
 *      `credentials_update`).
 * Правило слияния — «свежее выигрывает» по [updatedAt]; синк с ПК не
 * затирает более новый ручной ввод без спроса.
 *
 * Ключи — плоские строки `service.field`. Значения в открытую БД/логи не
 * попадают: под капотом `EncryptedSharedPreferences` (AES-256, ключ в
 * Android Keystore).
 */
class CredentialStore(context: Context) {

    /** true — секреты лежат в EncryptedSharedPreferences; false — сработал
     *  простой fallback (Keystore на устройстве неисправен). Для диагностики. */
    var usingEncryption: Boolean = false
        private set

    private val prefs: SharedPreferences = openStore(context)

    /**
     * Открыть стор так, чтобы токены НЕ терялись при ребуте.
     *
     * `EncryptedSharedPreferences.create()` бросает (или молча отдаёт пустоту),
     * когда мастер-ключ в Android Keystore после перезагрузки недоступен или
     * протух, или Tink-keyset в файле побит (`security-crypto:1.1.0-alpha06`
     * этим известен). Владелец: «каждый ребут слетают токены». Лечим:
     *   1. Пробуем зашифрованный + roundtrip-проверка чтения/записи.
     *   2. Не вышло → сносим битый файл, пробуем один раз заново.
     *   3. Всё равно нет → ПРОСТОЙ SharedPreferences под тем же именем. Лучше
     *      персистентный незашифрованный стор (файл всё равно в приватной
     *      песочнице приложения), чем «зашифрованный», который обнуляется
     *      каждую перезагрузку.
     */
    private fun openStore(context: Context): SharedPreferences {
        val name = "ripster_credentials"
        fun tryEncrypted(): SharedPreferences {
            val master = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val sp = EncryptedSharedPreferences.create(
                context, name, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            // roundtrip: если keyset побит, упадёт именно здесь, а не при первом
            // реальном чтении токена (когда уже поздно и сервис «отвалился»).
            val probe = "__probe__"
            sp.edit().putString(probe, "1").commit()
            check(sp.getString(probe, null) == "1") { "encrypted prefs roundtrip failed" }
            sp.edit().remove(probe).commit()
            return sp
        }
        return runCatching { tryEncrypted() }.getOrElse {
            runCatching {
                context.deleteSharedPreferences(name)
                tryEncrypted()
            }.getOrElse {
                // Последний рубеж — простой стор. Помечаем.
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
            }
        }.also { usingEncryption = it.javaClass.simpleName.contains("Encrypted", ignoreCase = true) }
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        // commit(), не apply(): сопряжение/ввод токена должны пережить
        // немедленное убийство процесса (свернул → система прибила).
        prefs.edit().apply(block).commit()
    }

    /** Известные поля. Список закрытый: клиент просит по имени из [Key]. */
    enum class Key(val id: String) {
        DEEZER_ARL("deezer.arl"),
        QOBUZ_APP_ID("qobuz.app_id"),
        QOBUZ_SECRET("qobuz.secret"),
        QOBUZ_EMAIL("qobuz.email"),
        QOBUZ_PASSWORD("qobuz.password"),
        QOBUZ_TOKEN("qobuz.token"),
        TIDAL_OAUTH("tidal.oauth"),
        SOUNDCLOUD_OAUTH("soundcloud.oauth"),
        SPOTIFY_SP_DC("spotify.sp_dc"),
        YANDEX_OAUTH("yandex.oauth"),
        BEATPORT_USERNAME("beatport.username"),
        BEATPORT_PASSWORD("beatport.password"),
    }

    fun get(key: Key): String? = prefs.getString(key.id, null)?.ifBlank { null }

    fun updatedAt(key: Key): Long = prefs.getLong("${key.id}\$ts", 0L)

    /** Записать значение (пустое → удалить). [source] помечает происхождение для отладки. */
    fun set(key: Key, value: String?, source: Source = Source.MANUAL) {
        edit {
            if (value.isNullOrBlank()) {
                remove(key.id); remove("${key.id}\$ts"); remove("${key.id}\$src")
            } else {
                putString(key.id, value.trim())
                putLong("${key.id}\$ts", System.currentTimeMillis())
                putString("${key.id}\$src", source.name)
                // Приговор был вынесен ПРЕЖНЕМУ значению. Новое ещё не пробовали.
                remove("${key.id}\$dead")
            }
        }
    }

    /**
     * Слияние значения, пришедшего с ПК. ПК — источник истины по токенам
     * сервисов: берём его значение всякий раз, когда СЛЕПОК ПК новее того,
     * что мы уже приняли (`$pcts` = mtime конфига ПК на момент прошлого синка).
     *
     * Старая версия сравнивала `updatedAt(key)` (локальное время записи, у
     * ручной правки — «сейчас», ~1.7e12) с `pcUpdatedAt` (mtime конфига,
     * тоже ~1.7e12) — из-за чего ротация Tidal/Qobuz-токена на ПК часто НЕ
     * доезжала до телефона, и сервис «отваливался». Теперь отметки разведены.
     *
     * Возвращает true, если значение реально изменилось.
     */
    fun mergeFromPc(key: Key, value: String?, pcUpdatedAt: Long, force: Boolean = false): Boolean {
        if (value.isNullOrBlank()) return false
        // «Забрать учётки с ПК» ([force]) обязано ЗАБРАТЬ. Отметка слепка ПК —
        // оптимизация автосинка (не дёргать стор, когда на ПК ничего не
        // менялось); для нажатой кнопки это была тихая заглушка: конфиг ПК с
        // прошлого синка не трогали — и кнопка не делала ничего, молча.
        val acceptedPcTs = prefs.getLong("${key.id}\$pcts", 0L)
        if (!force && get(key) != null && pcUpdatedAt <= acceptedPcTs) return false

        // НАБРАННОЕ РУКАМИ АВТОСИНК НЕ ТРОГАЕТ.
        //
        // 06.09.2026: на телефоне сохранён живой токен Qobuz, экран его
        // показывает — а клиент получает СТАРЫЙ, с ПК. Синк перезаписывал
        // ручное значение при каждом запуске. Человек вводит токен, видит его в
        // поле, и приложение молча им не пользуется: контрол показывает не то,
        // чем живёт.
        //
        // Сравнивать отметки времени бесполезно: конфиг на ПК переписывается
        // часто, и его mtime почти всегда свежее ручной правки — я попробовал,
        // не сработало. Правило простое и предсказуемое: сказал человек руками
        // — его слово держится, пока он сам не нажмёт «Забрать учётки с ПК».
        // Тогда приходит [force] = true, и ПК снова главный.
        //
        // Уточнение владельца 06.09.2026: держится РУЧНОЙ И ВАЛИДНЫЙ. Если
        // сервис уже отверг набранное руками (`markRejected`), держать его
        // не за что — это не выбор человека, это мёртвая строка, и синк с ПК
        // имеет право её заменить. «Не знаю» приговором не считается: пометку
        // ставит только явный отказ сервиса.
        val manual = prefs.getString("${key.id}\$src", null) == Source.MANUAL.name
        if (manual && !force && get(key) != null && !isRejected(key)) return false
        val changed = get(key) != value.trim()
        edit {
            putString(key.id, value.trim())
            putLong("${key.id}\$ts", System.currentTimeMillis())
            putLong("${key.id}\$pcts", pcUpdatedAt)
            putString("${key.id}\$src", Source.PC_SYNC.name)
            remove("${key.id}\$dead")
        }
        return changed
    }

    /**
     * Сервис ОТВЕРГ это значение (401 «токен недействителен», не сетевой сбой).
     *
     * Приговор привязан к КОНКРЕТНОМУ значению: [set] и [mergeFromPc] его
     * снимают, потому что относился он к предыдущей строке, а не к полю.
     * Сеть — не приговор: сюда попадает только явный отказ сервиса, иначе
     * упавший Wi-Fi объявлял бы живой токен мёртвым.
     */
    fun markRejected(key: Key) {
        if (get(key) == null) return
        edit { putBoolean("${key.id}\$dead", true) }
    }

    /** Значение реально сработало — снимаем прежний приговор, если он был. */
    fun markWorking(key: Key) {
        if (!isRejected(key)) return
        edit { remove("${key.id}\$dead") }
    }

    fun isRejected(key: Key): Boolean = prefs.getBoolean("${key.id}\$dead", false)

    fun clearAll() {
        edit { clear() }
    }

    enum class Source { MANUAL, PC_SYNC }
}
