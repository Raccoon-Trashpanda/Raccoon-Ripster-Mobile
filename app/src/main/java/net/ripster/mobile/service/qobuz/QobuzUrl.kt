package net.ripster.mobile.service.qobuz

/**
 * Разбор ссылки Qobuz: что это и какой у него идентификатор.
 *
 * Вынесено из QobuzClient.resolve отдельно, чтобы это можно было ПРОВЕРИТЬ.
 * Ошибка здесь тихая: клиент просто «ничего не находит», и по такому симптому
 * причину не увидеть — она пряталась до 06.09.2026.
 */
object QobuzUrl {

    data class Ref(val kind: String, val id: String)

    /**
     * `null` — это не ссылка Qobuz на альбом или трек.
     *
     * Идентификатор — ПОСЛЕДНИЙ сегмент пути. Раньше стояло
     * `(album|track)/([a-z0-9]+)`, но у Qobuz после вида идёт слаг названия:
     *
     *   /fr-fr/album/a-date-with-depeche-mode-nouvelle-vague/bfw3j2gdkcmtt
     *
     * и `[a-z0-9]+` останавливался на первом дефисе — в идентификатор уходила
     * буква «a». Клиент спрашивал альбом «a», получал пусто, человек читал
     * «ничего не найдено».
     *
     * Слаг необязателен: у короткой формы open.qobuz.com/album/<id> его нет.
     */
    fun parse(url: String?): Ref? {
        val u = url.orEmpty()
        if ("qobuz.com" !in u.lowercase()) return null
        val kind = when {
            Regex("""/album/""").containsMatchIn(u) -> "album"
            Regex("""/track/""").containsMatchIn(u) -> "track"
            else -> return null
        }
        val id = u.substringBefore('?').substringBefore('#')
            .trimEnd('/').substringAfterLast('/')
        // Идентификатор у Qobuz буквенно-цифровой: у альбома вроде
        // «bfw3j2gdkcmtt», у трека числовой. Всё остальное — не идентификатор,
        // и лучше честно вернуть null, чем спросить сервис про мусор.
        if (id.isBlank() || !id.all { it.isLetterOrDigit() }) return null
        return Ref(kind, id)
    }
}
