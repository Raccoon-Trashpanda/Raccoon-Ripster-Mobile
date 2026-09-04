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
    return when {
        "__qobuz_stale_appid__" in m -> tr("search.qobuz_stale_appid", lang)
        "__qobuz_bad_token__" in m -> tr("search.qobuz_bad_token", lang)
        m == "__timeout__" || e is java.net.SocketTimeoutException ||
            "timeout" in m || "timed out" in m -> tr("search.svc_timeout", lang)
        e is java.net.UnknownHostException || e is java.net.ConnectException ||
            e is java.net.SocketException || "connection abort" in m ||
            "connection reset" in m || "unreachable" in m ||
            "failed to connect" in m -> tr("search.svc_neterr", lang)
        // Вызывающий уже подписывает «<Сервис>: » — снимаем такой же префикс из
        // текста самого движка, иначе он удваивается.
        else -> (e.message ?: e.javaClass.simpleName)
            .replace(Regex("^(Qobuz|Tidal|Deezer|Yandex|Beatport|SoundCloud|Spotify|Apple|BBC):\\s*"), "")
    }
}

/** То же для строки, а не исключения: очередь загрузок хранит причину текстом. */
fun errorText(raw: String?, lang: AppLang): String {
    if (raw.isNullOrBlank()) return tr("err.unknown", lang)
    return engineErrorText(raw, lang) ?: raw
}
