package net.ripster.mobile.ui.i18n

/**
 * Единственное место, где исключение превращается в текст для человека.
 *
 * Жалоба владельца 04.09.2026: «ошибки должны быть на языке, выбранном в
 * программе; практика показала, что там русский, даже если выбран английский».
 * Причина оказалась не в словаре, а в маршруте: движки живут в core-слое, языка
 * не знают и бросали готовую русскую строку, а экраны печатали
 * `exception.message` КАК ЕСТЬ — `RadarScreen`, `SettingsHost`,
 * `OnboardingScreen`. Перевод при этом существовал и просто не участвовал.
 *
 * Правильный порядок разбора, сверху вниз:
 *  1. маркер движка (`__e.<ключ>__`) — переводится по таблице;
 *  2. известные технические маркеры Qobuz;
 *  3. класс сетевой аварии — таймаут и обрыв связи человеку одинаково
 *     непонятны в оригинале («software caused connection abort»);
 *  4. и лишь в конце — сырой текст, у которого срезается дублирующий префикс
 *     сервиса.
 *
 * Четвёртая ветка — не запасной путь «на всякий случай», а признак того, что
 * движок ещё не переведён на маркеры. Видишь на экране русский при английском
 * интерфейсе — значит строка пришла оттуда.
 */
fun errorText(e: Throwable?, lang: AppLang): String {
    if (e == null) return tr("err.unknown", lang)
    engineErrorText(e.message, lang)?.let { return it }
    val m = (e.message ?: e.javaClass.simpleName).lowercase()
    markerText(m, lang)?.let { return it }
    return when {
        e is java.net.SocketTimeoutException -> tr("search.svc_timeout", lang)
        e is java.net.UnknownHostException || e is java.net.ConnectException ||
            e is java.net.SocketException -> tr("search.svc_neterr", lang)
        // Вызывающий уже подписывает «<Сервис>: » — снимаем такой же префикс из
        // текста самого движка, иначе он удваивается.
        else -> (e.message ?: e.javaClass.simpleName)
            .replace(Regex("^(Qobuz|Tidal|Deezer|Yandex|Beatport|SoundCloud|Spotify|Apple|BBC):\\s*"), "")
    }
}

/**
 * То же для строки, а не исключения: очередь загрузок хранит причину текстом.
 *
 * Раньше здесь разбирались ТОЛЬКО маркеры движков, а служебные — нет, и на
 * экран загрузок утекало «__qobuz_bad_token__» как есть (A31, 06.09.2026).
 * Человеку показывали внутреннее имя ошибки вместо причины: это не сообщение,
 * это сор из избы.
 *
 * Разбор теперь ОДИН на обе версии — иначе они снова разойдутся.
 */
fun errorText(raw: String?, lang: AppLang): String {
    if (raw.isNullOrBlank()) return tr("err.unknown", lang)
    engineErrorText(raw, lang)?.let { return it }
    markerText(raw, lang)?.let { return it }
    return raw.replace(
        Regex("^(Qobuz|Tidal|Deezer|Yandex|Beatport|SoundCloud|Spotify|Apple|BBC):\\s*"), "",
    )
}

/**
 * Служебные маркеры — в человеческие слова.
 *
 * Общий разбор для строки и исключения. Пустой ответ значит «этот текст не
 * маркер», а не «ошибки нет».
 */
private fun markerText(raw: String, lang: AppLang): String? {
    val m = raw.lowercase()
    return when {
        "__qobuz_stale_appid__" in m -> tr("search.qobuz_stale_appid", lang)
        "__qobuz_bad_token__" in m -> tr("search.qobuz_bad_token", lang)
        net.ripster.mobile.core.errors.EngineErrors.QOBUZ_PURCHASE_ONLY in m -> tr("err.qobuz_purchase_only", lang)
        net.ripster.mobile.core.errors.EngineErrors.QOBUZ_RIGHTS_BLOCKED in m -> tr("err.qobuz_rights_blocked", lang)
        net.ripster.mobile.core.errors.EngineErrors.QOBUZ_RESTRICTED in m ->
            tr("err.qobuz_restricted", lang) + ": " + m.substringAfter(net.ripster.mobile.core.errors.EngineErrors.QOBUZ_RESTRICTED).trim()
        m == "__timeout__" || "timed out" in m || "timeout" in m -> tr("search.svc_timeout", lang)
        "unknownhost" in m || "connection abort" in m || "connection reset" in m ||
            "unreachable" in m || "failed to connect" in m -> tr("search.svc_neterr", lang)
        else -> null
    }
}
