package net.ripster.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.model.MediaSelection
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.core.service.StreamResolver
import net.ripster.mobile.ui.components.Cover
import net.ripster.mobile.ui.components.pressable
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Детальный экран альбома/релиза — модальный push поверх вкладок (без нижней
 * навигации). Дизайн: ripster-neon-skin_2 / Album.dc.html.
 *   назад · обложка 280 · тайтл/артист по центру · мета-строка (год · N трек. ·
 *   длительность) · бейджи формата · треклист (номер · название · время),
 *   группировка по дискам · «Скачать альбом» + ↓ у каждого трека.
 * Треклист берётся через `ServiceClient.resolve(url)`.
 */
@Composable
fun AlbumScreen(
    url: String,
    service: String,
    fallbackTitle: String,
    fallbackArtist: String,
    fallbackCover: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenArtist: (String, String, String) -> Unit = { _, _, _ -> },
) {
    val ctx = LocalContext.current
    val app = RipsterApp.from(ctx)
    val lang = LocalAppLang.current
    val c = RipsterTheme.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val queued = remember { mutableStateMapOf<String, Boolean>() }

    BackHandler { onBack() }

    // `resolve()` ходит в сеть и может залипнуть (протухший вход, стоящий
    // сокет). Без потолка экран оставался в «Анализирую…» НАВСЕГДА — владелец
    // ловил это по 5 минут (03.09.2026). 25 с и честная пустая карточка.
    var resolveFailed by remember(url) { mutableStateOf(false) }
    val sel by produceState<MediaSelection?>(initialValue = null, url) {
        val svc = Service.entries.firstOrNull { it.id == service || it.label.equals(service, true) }
        if (svc == null) { resolveFailed = true; value = null; return@produceState }
        val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(25_000) {
                runCatching { ServiceRegistry.get(svc)?.resolve(url) }.getOrNull()
            }
        }
        resolveFailed = got == null
        value = got
    }

    val album = sel?.albums?.firstOrNull()
    val tracks = sel?.tracks.orEmpty()

    var playMsg by remember { mutableStateOf<String?>(null) }

    // Слушать альбом потоком с указанного трека: первые 4 резолвим сразу,
    // остальное дописываем в очередь фоном. Если прямой поток не вышел —
    // пробуем «простые» сервисы (Deezer/Qobuz) по «артист трек», как на ПК.
    // Скачивание тут НЕ инициируем (это отдельная кнопка ↓).
    fun playFrom(start: Track) {
        scope.launch {
            playMsg = null
            val q = app.settings.state.value.qualityFor(onWifi = true)
            val ordered = tracks.dropWhile { it.id != start.id }.ifEmpty { tracks }
            var head = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                StreamResolver.toStreamItems(ordered.take(4), q, limit = 4)
            }.orEmpty()
            var tail = ordered.drop(4)
            if (head.isEmpty()) {
                // конверсия: ищем этот трек в стримируемых сервисах
                val q2 = "${start.artist} ${start.title}".trim()
                val alt = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    listOf(Service.DEEZER, Service.QOBUZ, Service.TIDAL, Service.SOUNDCLOUD)
                        .mapNotNull { ServiceRegistry.get(it) }
                        .firstNotNullOfOrNull { c -> runCatching { c.search(q2).tracks.take(6) }.getOrNull()?.takeIf { it.isNotEmpty() } }
                }.orEmpty()
                head = StreamResolver.toStreamItems(alt.take(4), q, limit = 4)
                tail = alt.drop(4)
            }
            if (head.isEmpty()) {
                playMsg = tr("album.no_stream", lang)
                return@launch
            }
            app.player.playStream(head)
            onOpenPlayer()
            if (tail.isNotEmpty()) {
                app.player.appendStream(StreamResolver.toStreamItems(tail, q, limit = 40))
            }
        }
    }

    // Ссылка привела к ОДНОМУ треку (частая история для DJ-миксов/сборников/
    // синглов, где артист выложил один сегмент) — это не альбом, показываем как
    // трек. `album.title`, совпадающий с именем артиста из радара, бесполезен.
    val singleTrack = tracks.size == 1 && sel?.albums.orEmpty().size <= 1
    val usefulAlbumTitle = album?.title?.takeIf { it.isNotBlank() && !it.equals(fallbackTitle, true) }
    val title = usefulAlbumTitle
        ?: if (singleTrack) tracks[0].title else (sel?.containerTitle ?: fallbackTitle)
    val artist = album?.artist ?: tracks.firstOrNull()?.artist ?: fallbackArtist
    val cover = album?.artworkUrl ?: tracks.firstOrNull()?.artworkUrl ?: fallbackCover
    val totalMs = tracks.mapNotNull { it.durationMs }.sum()

    Box(Modifier.fillMaxSize().background(c.surface_canvas)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item("top") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).border(1.dp, c.border_subtle, CircleShape)
                            .background(c.surface_raised).pressable { onBack() },
                        contentAlignment = Alignment.Center,
                    ) { BasicText("‹", style = TextStyle(color = c.text_secondary, fontSize = 22.sp)) }
                }
            }

            item("head") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(260.dp).clip(RoundedCornerShape(20.dp)).background(c.surface_active)) {
                        Cover(url = cover, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(20.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    BasicText(
                        title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = c.text_primary, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    )
                    Spacer(Modifier.height(4.dp))
                    // артист(ы) кликабельны → страница артиста с дискографией
                    val primaryArtist = artist.substringBefore(",").trim().ifBlank { artist }
                    BasicText(
                        artist,
                        modifier = Modifier.pressable {
                            if (primaryArtist.isNotBlank()) onOpenArtist(primaryArtist, service, "")
                        },
                        style = TextStyle(color = c.accent_text, fontSize = 14.sp),
                    )
                    Spacer(Modifier.height(8.dp))
                    val meta = buildList {
                        (album?.year ?: tracks.firstOrNull()?.year)?.let { add(it.toString()) }
                        val n = album?.trackCount ?: tracks.size
                        if (n > 0) add("$n " + tr("search.tracks_short", lang))
                        if (totalMs > 0) add(fmtDur(totalMs / 1000))
                    }.joinToString("  ·  ")
                    if (meta.isNotBlank()) {
                        BasicText(meta, style = TextStyle(color = c.text_disabled, fontSize = 12.sp))
                    }
                    album?.upc?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(2.dp))
                        BasicText("UPC $it", style = TextStyle(color = c.text_disabled, fontSize = 10.sp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Слушать потоком
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(c.accent_fill)
                                .pressable(enabled = tracks.isNotEmpty()) { tracks.firstOrNull()?.let { playFrom(it) } }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BasicText("▶", style = TextStyle(color = c.text_on_fill, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                            BasicText(tr("album.listen", lang), style = TextStyle(color = c.text_on_fill, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        }
                        // Скачать
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.border_subtle, RoundedCornerShape(999.dp))
                                .pressable(enabled = tracks.isNotEmpty()) {
                                    scope.launch { tracks.forEach { app.downloads.enqueue(it); queued[it.id] = true } }
                                }
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BasicText("↓", style = TextStyle(color = c.text_secondary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                            BasicText(
                                tr(if (singleTrack) "album.dl_track" else "np.dl_album", lang),
                                style = TextStyle(color = c.text_secondary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                    playMsg?.let {
                        Spacer(Modifier.height(10.dp))
                        BasicText(it, style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }

            if (sel == null) {
                item("load") {
                    Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                        // Пока идёт резолв — «Анализирую…»; когда он не удался
                        // (таймаут/ошибка) — говорим об этом, а не крутим вечно.
                        BasicText(
                            tr(if (resolveFailed) "album.resolve_failed" else "tools.analyzing", lang),
                            style = TextStyle(color = c.text_tertiary, fontSize = 12.sp, textAlign = TextAlign.Center),
                        )
                    }
                }
            }

            // треклист, группировка по дискам
            val byDisc = tracks.groupBy { it.discNumber ?: 1 }.toSortedMap()
            byDisc.forEach { (disc, list) ->
                if (byDisc.size > 1) {
                    item("disc-$disc") {
                        BasicText(
                            tr("album.disc", lang).replace("{n}", disc.toString()),
                            Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp),
                            style = TextStyle(color = c.text_tertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
                        )
                    }
                }
                itemsIndexed(list, key = { _, it -> "tr-" + it.id }) { idx, t ->
                    // Тап по строке — слушать потоком с этого трека; ↓ — скачать.
                    // Каждый трек — плоская плашка, отделённая тонкой линией от
                    // соседней (пожелание 03.09.2026): линия сверху у всех, кроме
                    // первого, — так список читается как набор строк-кнопок.
                    if (idx > 0) net.ripster.mobile.ui.components.RipsterHairline(inset = 24.dp)
                    TrackLine(
                        t = t, pos = idx + 1, queued = queued[t.id] == true, c = c,
                        onPlay = { playFrom(t) },
                        onDownload = { scope.launch { app.downloads.enqueue(t); queued[t.id] = true } },
                    )
                }
            }
            item("tail") { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun TrackLine(t: Track, pos: Int, queued: Boolean, c: net.ripster.mobile.ui.theme.RipsterColors, onPlay: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .pressable(pressedBg = c.surface_raised) { onPlay() }
            .padding(horizontal = 24.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText(
            // Настоящий номер трека, а если сервис его не отдал — порядковый в
            // списке (раньше был «•», и номера у трека не было вовсе).
            (t.trackNumber ?: 0).takeIf { it > 0 }?.toString() ?: pos.toString(),
            Modifier.width(20.dp),
            style = TextStyle(color = c.text_disabled, fontSize = 13.sp, textAlign = TextAlign.Center),
        )
        BasicText(
            t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = c.text_primary, fontSize = 14.sp),
        )
        t.durationMs?.let {
            BasicText(fmtDur(it / 1000), style = TextStyle(color = c.text_disabled, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
        }
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (queued) c.surface_active else c.surface_raised)
                .pressable(enabled = !queued) { onDownload() },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(if (queued) "✓" else "↓", style = TextStyle(color = if (queued) c.success_text else c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
    }
}

private fun fmtDur(sec: Long): String {
    val m = sec / 60; val s = sec % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}
