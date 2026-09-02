package net.ripster.mobile.core.radar

import kotlinx.coroutines.flow.Flow
import net.ripster.mobile.core.db.RipsterDb
import net.ripster.mobile.core.db.WatchEntity
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.service.ServiceRegistry

/**
 * Радар БЕЗ ПК: телефон сам ведёт список отслеживаемых артистов и периодически
 * (см. [RadarWorker]) спрашивает у нативного `ServiceClient.getArtist()` их
 * самый свежий СОБСТВЕННЫЙ релиз. Появился новый — помечает `unseen` и шлёт
 * уведомление. Когда телефон в паре с ПК, эта лента показывается ВМЕСТЕ с
 * радаром ПК (дедуп по имени артиста).
 */
class LocalRadar(private val db: RipsterDb) {

    fun feed(): Flow<List<WatchEntity>> = db.watch().observeAll()
    fun unseenCount(): Flow<Int> = db.watch().unseenCount()

    private fun key(serviceId: String, kind: String, ident: String) = "$serviceId:$kind:$ident"

    suspend fun isFollowed(serviceId: String, artistId: String): Boolean =
        artistId.isNotBlank() && db.watch().countFor(serviceId, artistId) > 0

    suspend fun follow(
        serviceId: String,
        artistId: String,
        name: String,
        coverUrl: String?,
        kind: String = "artist",
    ) {
        val k = key(serviceId, kind, artistId.ifBlank { name.lowercase() })
        if (db.watch().get(k) != null) return
        db.watch().upsert(
            WatchEntity(
                key = k, kind = kind, serviceId = serviceId, artistId = artistId,
                name = name, coverUrl = coverUrl, addedAt = System.currentTimeMillis(),
            ),
        )
        // сразу снимаем базовую точку (без уведомления) — чтобы старые релизы не
        // прилетели как «новинка» при первой же проверке
        runCatching { checkOne(db.watch().get(k) ?: return, notifyBaseline = false) }
    }

    suspend fun unfollow(key: String) = db.watch().delete(key)
    suspend fun markSeen(key: String) = db.watch().markSeen(key)
    suspend fun markAllSeen() = db.watch().markAllSeen()

    /** Проверить всех. @return сколько артистов с новинкой. */
    suspend fun refresh(): Int {
        var fresh = 0
        for (w in db.watch().all()) {
            if (checkOne(w, notifyBaseline = true)) fresh++
        }
        return fresh
    }

    /** @return true, если у этого артиста появился НОВЫЙ релиз в эту проверку. */
    private suspend fun checkOne(w: WatchEntity, notifyBaseline: Boolean): Boolean {
        if (w.artistId.isBlank()) return false
        val svc = Service.entries.firstOrNull { it.id == w.serviceId } ?: return false
        val page = runCatching { ServiceRegistry.get(svc)?.getArtist(w.artistId) }.getOrNull()
            ?: run { db.watch().touch(w.key, System.currentTimeMillis()); return false }
        // только СВОИ релизы артиста (не «с этим артистом»)
        val own = page.releases.filter { it.type != "compilation" && it.id.isNotBlank() }
        val newest = own.maxByOrNull { it.date }
            ?: own.firstOrNull()
            ?: run { db.watch().touch(w.key, System.currentTimeMillis()); return false }
        val now = System.currentTimeMillis()

        if (w.latestReleaseId.isBlank()) {
            // первая проверка — просто базлайн
            db.watch().setLatest(
                w.key, newest.id, newest.title, newest.url, newest.coverUrl, newest.date,
                unseen = false, ts = now,
            )
            return false
        }
        if (newest.id != w.latestReleaseId && newest.date >= w.latestDate) {
            db.watch().setLatest(
                w.key, newest.id, newest.title, newest.url, newest.coverUrl, newest.date,
                unseen = notifyBaseline, ts = now,
            )
            return notifyBaseline
        }
        db.watch().touch(w.key, now)
        return false
    }
}
