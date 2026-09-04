package net.ripster.mobile.core.settings

/**
 * Чистка и проверка ВСТАВЛЕННОГО секрета — одна на все поля учёток.
 *
 * Токены приходят человеку из мессенджера, и вставляется вместе с ними мусор:
 * подпись «Token ➠ …», кавычки, `arl=` спереди, перенос строки в конце, а на
 * длинных значениях вставка вообще может прийти обрезанной. Раньше поле
 * сохраняло что дали, «Учётные записи» рисовали зелёное «Подключён», а сервис
 * падал 401 — и причину искали в токене, хотя он был хороший (ровно так
 * потерялись Tidal-токен и Deezer-ARL 03.09.2026).
 *
 * Поэтому: [clean] снимает обёртку и все пробелы (в секретах их не бывает),
 * а [validate] отказывается сохранять заведомо битое и говорит, что не так.
 */
object CredentialInput {

    /** Убрать подпись/кавычки/пробелы вокруг вставленного значения. */
    fun clean(raw: String): String {
        var s = raw.trim()
        // «Token ➠ xxx», «ARL: xxx», «arl=xxx» — берём хвост после разделителя,
        // но только если хвост похож на секрет (длинный и без пробелов).
        for (sep in listOf("➠", "=", ":")) {
            val i = s.lastIndexOf(sep)
            if (i in 0 until s.length - 1) {
                val tail = s.substring(i + 1).trim()
                // У JWT внутри есть точки, но не двоеточия; url в значении не ждём.
                if (tail.length >= 20 && !tail.contains(' ') && !tail.startsWith("//")) s = tail
            }
        }
        s = s.trim().trim('"', '\'', '«', '»', '`')
        // В секретах не бывает пробелов и переносов — вычищаем любые.
        return s.filterNot { it.isWhitespace() }
    }

    /**
     * Deezer выдаёт ARL ровно такой длины. Проверять «не короче» мало: 04.09.2026
     * в поле на телефоне лежала склейка тестовой заглушки `deadbeef123` с
     * настоящим токеном — 203 символа, все шестнадцатеричные, порог «≥128»
     * пропускал её насквозь. В учётках горело «Подключён», Deezer сессию не
     * открывал, и человека отправляли переклеивать токен, с которым всё было в
     * порядке. Слишком длинное — такая же порча, как слишком короткое.
     */
    const val DEEZER_ARL_LEN = 192

    /** Что не так со значением, или null если годится. */
    fun problem(key: CredentialStore.Key, value: String): Problem? {
        val v = value.trim()
        if (v.isEmpty()) return null            // пустое = очистка поля, это законно
        return when (key) {
            CredentialStore.Key.DEEZER_ARL ->
                if (v.length != DEEZER_ARL_LEN || !v.all { it.isHex() }) Problem.ARL else null
            CredentialStore.Key.TIDAL_OAUTH ->
                if (!v.startsWith("{") && !looksLikeJwt(v)) Problem.TIDAL else null
            CredentialStore.Key.QOBUZ_TOKEN ->
                if (v.length < 40) Problem.SHORT else null
            CredentialStore.Key.YANDEX_OAUTH ->
                if (v.length < 30) Problem.SHORT else null
            CredentialStore.Key.SPOTIFY_SP_DC ->
                if (v.length < 60) Problem.SHORT else null
            CredentialStore.Key.QOBUZ_APP_ID ->
                if (!v.matches(Regex("""\d{9}"""))) Problem.APP_ID else null
            CredentialStore.Key.QOBUZ_SECRET ->
                if (!v.matches(Regex("""[0-9a-f]{32}"""))) Problem.SECRET else null
            else -> null
        }
    }

    enum class Problem { ARL, TIDAL, SHORT, APP_ID, SECRET }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * JWT проверяем ПО СУЩЕСТВУ, а не по «есть две точки и длина большая»:
     * обрезанный токен обычно сохраняет и то, и другое (реальный случай — 165
     * символов, две точки, и всё равно мусор). Достоверный признак — средняя
     * часть раскодируется из base64url в JSON-объект.
     */
    private fun looksLikeJwt(v: String): Boolean {
        val parts = v.split('.')
        if (parts.size != 3 || parts.any { it.isBlank() }) return false
        val payload = runCatching {
            val p = parts[1]
            val padded = p.padEnd((p.length + 3) / 4 * 4, '=')
            String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull() ?: return false
        return payload.trimStart().startsWith("{") && payload.trimEnd().endsWith("}")
    }
}
