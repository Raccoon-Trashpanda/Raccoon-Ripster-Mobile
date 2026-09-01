package net.ripster.mobile.cast

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.service.ServiceRegistry

/**
 * Каст трека на Яндекс Станцию. Glagol умеет запускать только контент
 * Яндекс.Музыки по id, поэтому:
 *   - трек уже из Яндекс.Музыки → берём `ymId` из `raw`;
 *   - иначе → ищем тот же трек в Яндекс.Музыке (по «артист название»,
 *     сверяем длительность) и берём его id.
 *
 * Если Яндекс.Музыка не настроена или совпадение не найдено — null,
 * и вызывающий показывает честное «нет на Яндекс.Музыке».
 */
object YandexCast {

    suspend fun resolveYmId(track: Track): String? {
        if (track.service == Service.YANDEX) return track.raw["ymId"]?.takeIf { it.isNotBlank() }

        val ym = ServiceRegistry.get(Service.YANDEX) ?: return null
        val q = "${track.artist} ${track.title}".trim()
        val hits = runCatching { ym.search(q).tracks }.getOrDefault(emptyList())
        if (hits.isEmpty()) return null

        // Лучшее совпадение: близкая длительность (±3 c), иначе первый.
        val target = track.durationMs
        val best = if (target != null) {
            hits.minByOrNull { kotlin.math.abs((it.durationMs ?: 0L) - target) }
                ?.takeIf { kotlin.math.abs((it.durationMs ?: 0L) - target) <= 3000 }
        } else null
        return (best ?: hits.first()).raw["ymId"]?.takeIf { it.isNotBlank() }
    }
}
