package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Album
import net.ripster.mobile.core.model.MediaSelection
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import java.text.Normalizer

/**
 * Ранжирование и дедуп поисковой выдачи.
 *
 * Раньше `SearchScreen` просто склеивал списки: `deezer.tracks + qobuz.tracks +
 * …`. Итог — точное совпадение из Tidal тонуло под двумя десятками рыхлых
 * результатов Deezer, а один и тот же трек показывался по разу на каждый
 * сервис. Это и есть «поиск не работает».
 *
 * Здесь — детерминированный ранкер (никаких моделей): текстовое совпадение →
 * популярность (где сервис её отдал) → аффинити к библиотеке и истории →
 * дедуп по ISRC/UPC. Чистые функции, тестируемо, стабильная сортировка.
 */
object SearchRanker {

    data class Ctx(
        /** norm(artist) артистов из библиотеки. */
        val libArtists: Set<String> = emptySet(),
        /** norm(artist)|norm(album) альбомов из библиотеки. */
        val libAlbums: Set<String> = emptySet(),
        /** norm(artist) из недавних прослушиваний. */
        val histArtists: Set<String> = emptySet(),
    ) {
        companion object { val EMPTY = Ctx() }
    }

    // ── нормализация ────────────────────────────────────────────────────────
    private val diacritics = "\\p{Mn}+".toRegex()
    private val nonAlnum = "[^\\p{L}\\p{Nd}]+".toRegex()
    private val multiSpace = "\\s+".toRegex()

    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        return diacritics.replace(d, "")
            .lowercase()
            .replace(nonAlnum, " ")
            .replace(multiSpace, " ")
            .trim()
    }

    private fun tokens(s: String): List<String> =
        norm(s).split(" ").filter { it.isNotBlank() }

    /** Убрать хвостовые «(feat. …)», «(with …)», «(prod. …)» — но НЕ remix/edit/live. */
    private val featTail =
        "[\\(\\[]\\s*(feat|ft|featuring|with|prod|produced by)\\b[^\\)\\]]*[\\)\\]]".toRegex(RegexOption.IGNORE_CASE)

    private fun coreTitle(title: String): String = norm(featTail.replace(title, " "))

    // маркеры «версии», которых пользователь обычно НЕ просил
    private val versionWords = listOf(
        "live", "remaster", "remastered", "remix", "instrumental", "acoustic",
        "sped up", "slowed", "nightcore", "8 bit", "8bit", "karaoke", "demo",
        "radio edit", "extended", "reprise", "commentary",
    )
    private val junkWords = listOf(
        "karaoke", "tribute", "made famous by", "originally performed",
        "in the style of", "as made famous", "backing track", "cover version",
    )

    // предпочтение сервиса при прочих равных (lossless-способность + надёжность)
    private fun serviceBonus(s: Service): Double = when (s) {
        Service.QOBUZ, Service.TIDAL, Service.BEATPORT -> 6.0
        Service.APPLE, Service.DEEZER -> 4.0
        Service.YANDEX -> 3.0
        Service.SOUNDCLOUD -> 1.0
        Service.SPOTIFY, Service.BBC -> 0.0
    }

    // ── публичный вход ─────────────────────────────────────────────────────
    fun rank(query: String, sel: MediaSelection, ctx: Ctx = Ctx.EMPTY): MediaSelection =
        sel.copy(
            tracks = rankTracks(query, sel.tracks, ctx),
            albums = rankAlbums(query, sel.albums, ctx),
        )

    fun rankTracks(query: String, tracks: List<Track>, ctx: Ctx): List<Track> {
        if (tracks.size <= 1) return tracks
        val q = norm(query)
        val qTok = tokens(query)
        val (qArtist, qTitle) = splitArtistTitle(query)

        val scored = tracks.map { t -> t to scoreTrack(q, qTok, qArtist, qTitle, t, ctx) }

        // дедуп: ISRC → иначе norm(artist)|coreTitle|бакет длительности (4с)
        val best = LinkedHashMap<String, Pair<Track, Double>>()
        for ((t, sc) in scored.sortedByDescending { it.second }) {
            val isrc = t.isrc?.trim()?.uppercase()?.takeIf { it.length >= 10 }
            val key = isrc ?: buildString {
                append(norm(t.artist)); append('|')
                append(coreTitle(t.title)); append('|')
                append(t.durationMs?.let { it / 4000 } ?: -1L)
            }
            val cur = best[key]
            if (cur == null || sc > cur.second) best[key] = t to sc
        }
        return best.values.sortedByDescending { it.second }.map { it.first }
    }

    fun rankAlbums(query: String, albums: List<Album>, ctx: Ctx): List<Album> {
        if (albums.size <= 1) return albums
        val q = norm(query)
        val qTok = tokens(query)
        val (qArtist, qTitle) = splitArtistTitle(query)

        val scored = albums.map { a -> a to scoreAlbum(q, qTok, qArtist, qTitle, a, ctx) }

        val best = LinkedHashMap<String, Pair<Album, Double>>()
        for ((a, sc) in scored.sortedByDescending { it.second }) {
            val upc = a.upc?.trim()?.takeIf { it.length >= 8 }
            val key = upc ?: (norm(a.artist) + "|" + norm(a.title))
            val cur = best[key]
            if (cur == null || sc > cur.second) best[key] = a to sc
        }
        return best.values.sortedByDescending { it.second }.map { it.first }
    }

    // ── скоринг ────────────────────────────────────────────────────────────
    private fun splitArtistTitle(query: String): Pair<String, String> {
        val parts = query.split(" - ", " – ", " — ", " -", "- ").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size == 2) norm(parts[0]) to norm(parts[1]) else "" to ""
    }

    private fun textScore(
        q: String, qTok: List<String>, qArtist: String, qTitle: String,
        title: String, artist: String,
    ): Double {
        val nt = norm(title)
        val na = norm(artist)
        val hay = "$na $nt"
        var s = 0.0

        val wantedTitle = qTitle.ifEmpty { q }
        when {
            nt == wantedTitle || nt == q -> s += 100.0
            nt.startsWith("$wantedTitle ") || nt.startsWith("$q ") -> s += 62.0
            wantedTitle.isNotEmpty() && nt.contains(wantedTitle) -> s += 34.0
        }
        if (qArtist.isNotEmpty()) {
            if (na == qArtist) s += 52.0
            else if (na.contains(qArtist) || qArtist.split(" ").all { na.contains(it) }) s += 22.0
        }
        // покрытие токенов запроса по «артист + название»
        if (qTok.isNotEmpty()) {
            val hit = qTok.count { tk -> Regex("(?<![\\p{L}\\p{Nd}])" + Regex.escape(tk)).containsMatchIn(hay) }
            s += 34.0 * hit / qTok.size
            if (hit == qTok.size) s += 10.0
        }
        if (q.isNotEmpty() && hay.contains(q)) s += 20.0
        return s
    }

    private fun versionPenalty(q: String, title: String): Double {
        val nt = norm(title)
        var p = 0.0
        for (w in junkWords) if (nt.contains(norm(w)) && !q.contains(norm(w))) p -= 90.0
        var vhits = 0
        for (w in versionWords) if (nt.contains(norm(w)) && !q.contains(norm(w))) vhits++
        p -= (vhits.coerceAtMost(3) * 14.0)
        return p
    }

    private fun popularityBoost(raw: Map<String, String>): Double {
        // Spotify popularity 0..100
        raw["popularity"]?.toIntOrNull()?.let { return 18.0 * (it / 100.0) }
        // Deezer rank ~0..1_000_000
        raw["rank"]?.toLongOrNull()?.let {
            if (it <= 0) return 0.0
            val n = (Math.log10(it.toDouble()) / 6.0).coerceIn(0.0, 1.0)
            return 16.0 * n
        }
        raw["fans"]?.toLongOrNull()?.let {
            if (it <= 0) return 0.0
            return 10.0 * (Math.log10(it.toDouble()) / 6.0).coerceIn(0.0, 1.0)
        }
        return 0.0
    }

    private fun scoreTrack(
        q: String, qTok: List<String>, qArtist: String, qTitle: String,
        t: Track, ctx: Ctx,
    ): Double {
        var s = textScore(q, qTok, qArtist, qTitle, t.title, t.artist)
        s += versionPenalty(q, t.title)
        s += popularityBoost(t.raw)
        val na = norm(t.artist)
        if (na in ctx.libArtists) s += 25.0
        else if (na in ctx.histArtists) s += 15.0
        t.albumTitle?.let { if ("$na|${norm(it)}" in ctx.libAlbums) s += 10.0 }
        s += serviceBonus(t.service)
        // очень короткие (<45с) — обычно интро/скиты, промахи по названию
        t.durationMs?.let { if (it in 1 until 45_000) s -= 12.0 }
        return s
    }

    private fun scoreAlbum(
        q: String, qTok: List<String>, qArtist: String, qTitle: String,
        a: Album, ctx: Ctx,
    ): Double {
        var s = textScore(q, qTok, qArtist, qTitle, a.title, a.artist)
        s += versionPenalty(q, a.title)
        val na = norm(a.artist)
        if (na in ctx.libArtists) s += 22.0
        else if (na in ctx.histArtists) s += 12.0
        if ("$na|${norm(a.title)}" in ctx.libAlbums) s += 14.0
        s += serviceBonus(a.service)
        // «сборники» на 1 трек и «дискографии» на 300 — оба подозрительны как ответ
        a.trackCount?.let { if (it == 0 || it > 60) s -= 8.0 }
        a.year?.let { if (it in 1950..2100) s += ((it - 1990).coerceIn(-20, 20)) * 0.15 }
        return s
    }
}
