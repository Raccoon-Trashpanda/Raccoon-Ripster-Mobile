package net.ripster.mobile.core.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Однонаправленная «шина» для перехода в Поиск с заранее заданным запросом:
 * тап по артисту/лейблу на карточке релиза → `SearchBus.request("Markus Schulz")`
 * → навигация на вкладку Поиск → `SearchScreen` подхватывает запрос, выполняет
 * его и обнуляет шину. Аналог `openArtistPage`/`openLabelPage` с ПК, только
 * без отдельной страницы артиста — просто предзаполненный поиск.
 */
object SearchBus {
    val query = MutableStateFlow<String?>(null)

    fun request(q: String) {
        val t = q.trim()
        if (t.isNotEmpty()) query.value = t
    }

    fun consume(): String? {
        val q = query.value
        if (q != null) query.value = null
        return q
    }
}
