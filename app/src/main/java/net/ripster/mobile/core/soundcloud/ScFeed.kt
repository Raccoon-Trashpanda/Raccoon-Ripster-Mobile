package net.ripster.mobile.core.soundcloud

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.service.ServiceClient
import net.ripster.mobile.core.service.ServiceRegistry

/**
 * Новое у тех, на кого подписан в SoundCloud — самодостаточно, без ПК.
 *
 * Подписки живут в том же локальном радаре, что и артисты остальных сервисов
 * ([net.ripster.mobile.core.radar.LocalRadar]): на странице аккаунта SC жмёшь
 * «Следить», и он попадает сюда. На SoundCloud «релиз» — это залитый трек или
 * микс, поэтому ленту собираем из последних загрузок каждого аккаунта.
 *
 * Свежесть считаем по дате трека, а не по порядку выдачи: аккаунты опрашиваются
 * параллельно, и без сортировки лента зависела бы от того, кто ответил первым.
 * Залипший аккаунт не задерживает секцию — у каждого свой потолок.
 */
object ScFeed {

    /**
     * @param userIds id аккаунтов SoundCloud, за которыми следят.
     * @param perUser сколько последних загрузок брать у каждого.
     * @param limit   потолок ленты после слияния.
     */
    suspend fun latest(
        userIds: List<String>,
        perUser: Int = 5,
        limit: Int = 24,
    ): List<Track> {
        if (userIds.isEmpty()) return emptyList()
        val client = ServiceRegistry.get(Service.SOUNDCLOUD) ?: return emptyList()
        return coroutineScope {
            userIds.distinct().map { id ->
                async {
                    withTimeoutOrNull(12_000) {
                        runCatching { tracksOf(client, id, perUser) }.getOrNull()
                    }.orEmpty()
                }
            }.awaitAll()
                .flatten()
                .distinctBy { it.id }
                .sortedByDescending { it.year ?: 0 }
                .take(limit)
        }
    }

    /**
     * Последние загрузки аккаунта. Идём через публичный [ServiceClient.getArtist]
     * — он уже отдаёт «релизы» аккаунта SC с датой и обложкой, и это избавляет
     * от второй копии разбора DTO здесь.
     */
    private suspend fun tracksOf(client: ServiceClient, userId: String, take: Int): List<Track> {
        val page = client.getArtist(userId) ?: return emptyList()
        return page.releases
            .sortedByDescending { it.date.ifBlank { it.year } }
            .take(take)
            .map { r ->
                Track(
                    id = r.id,
                    title = r.title,
                    artist = page.name,
                    service = Service.SOUNDCLOUD,
                    artworkUrl = r.coverUrl,
                    year = r.date.take(4).toIntOrNull() ?: r.year.toIntOrNull(),
                    raw = mapOf("permalink" to r.url, "artId" to userId),
                )
            }
            .filter { it.raw["permalink"]?.isNotBlank() == true }
    }
}
