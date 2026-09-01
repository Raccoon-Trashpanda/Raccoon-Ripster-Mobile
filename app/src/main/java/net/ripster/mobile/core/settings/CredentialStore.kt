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

    private val prefs: SharedPreferences = run {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ripster_credentials",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
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
    }

    fun get(key: Key): String? = prefs.getString(key.id, null)?.ifBlank { null }

    fun updatedAt(key: Key): Long = prefs.getLong("${key.id}\$ts", 0L)

    /** Записать значение (пустое → удалить). [source] помечает происхождение для отладки. */
    fun set(key: Key, value: String?, source: Source = Source.MANUAL) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) {
                remove(key.id); remove("${key.id}\$ts"); remove("${key.id}\$src")
            } else {
                putString(key.id, value.trim())
                putLong("${key.id}\$ts", System.currentTimeMillis())
                putString("${key.id}\$src", source.name)
            }
        }.apply()
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
    fun mergeFromPc(key: Key, value: String?, pcUpdatedAt: Long): Boolean {
        if (value.isNullOrBlank()) return false
        val acceptedPcTs = prefs.getLong("${key.id}\$pcts", 0L)
        if (get(key) != null && pcUpdatedAt <= acceptedPcTs) return false
        val changed = get(key) != value.trim()
        prefs.edit()
            .putString(key.id, value.trim())
            .putLong("${key.id}\$ts", System.currentTimeMillis())
            .putLong("${key.id}\$pcts", pcUpdatedAt)
            .putString("${key.id}\$src", Source.PC_SYNC.name)
            .apply()
        return changed
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    enum class Source { MANUAL, PC_SYNC }
}
