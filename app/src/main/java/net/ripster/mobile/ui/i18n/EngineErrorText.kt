package net.ripster.mobile.ui.i18n

/**
 * Разбор маркера [net.ripster.mobile.core.errors.EngineErrors] в текст на языке
 * пользователя.
 *
 * Возвращает `null`, если это не наш маркер, — вызывающий тогда показывает
 * исходное сообщение как раньше. Так экраны переводятся по одному, а
 * непокрытая ошибка не превращается в пустоту: увидеть техническую строку
 * лучше, чем не увидеть ничего.
 */
fun engineErrorText(raw: String?, lang: AppLang): String? {
    val s = raw?.trim().orEmpty()
    if (!s.startsWith("__e.")) return null
    val end = s.indexOf("__", startIndex = 4)
    if (end <= 4) return null
    val key = s.substring(4, end)
    val detail = s.substring(end + 2).trim()
    val text = tr("err.$key", lang)
    // Перевода нет — tr() вернул сам ключ; показывать «err.geo_uk» человеку
    // незачем, пусть лучше отработает ветка с исходным сообщением.
    if (text == "err.$key") return null
    return if (detail.isBlank()) text else "$text ($detail)"
}
