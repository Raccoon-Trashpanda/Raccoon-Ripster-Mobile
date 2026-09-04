package net.ripster.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.model.Album
import net.ripster.mobile.core.model.MediaSelection
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.service.ServiceClient
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.engineErrorText
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.components.pressable
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.i18n.errorText
import net.ripster.mobile.ui.components.busyHalo

/**
 * Поток «найти и поставить в очередь». Сервисы выбираются галочками в
 * выпадающем списке (одна строка → меню), поиск идёт СРАЗУ по всем отмеченным
 * и результаты сливаются. Тип (альбом / сингл-EP / трек), год и сортировка —
 * фильтры поверх слитого результата, как в ПК-версии.
 */
/** Сервисы без текстового поиска — только по ссылке (search() у них пуст). */
private val LINK_ONLY = setOf(Service.SPOTIFY, Service.BBC)

@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onOpenArtist: (String, String, String) -> Unit = { _, _, _ -> },
    onOpenAlbum: (net.ripster.mobile.ui.components.ReleaseCardData) -> Unit = {},
    onOpenPlayer: () -> Unit = {},
) {
    val lang = LocalAppLang.current
    val c = RipsterTheme.colors
    val app = RipsterApp.from(LocalContext.current)
    val scope = rememberCoroutineScope()

    val settings by app.settings.state.collectAsState()

    // Поколение реестра растёт после ввода токена в Настройках — пересобираем
    // список сервисов БЕЗ перезапуска приложения.
    val registryGen by ServiceRegistry.generation.collectAsState()
    val configured by produceState<List<ServiceClient>?>(initialValue = null, registryGen) {
        // НЕ гасим прошлый список на переезоне: после сопряжения generation
        // бампается несколько раз подряд, и `value = null` заставлял экран
        // мигать «Проверяю сервисы…». Первый прогон покажет пробу один раз.
        value = runCatching { ServiceRegistry.configured() }.getOrDefault(value ?: emptyList())
    }
    val ready = configured.orEmpty()

    // Набор выбранных сервисов — Ripster ПОМНИТ снятые галочки между сессиями
    // (settings.searchServicesOff). Новый/неупомянутый сервис по умолчанию включён.
    val picked = remember { mutableStateMapOf<Service, Boolean>() }
    var pickerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(ready, settings.searchServicesOff) {
        ready.forEach { picked[it.service] = it.service.id !in settings.searchServicesOff }
    }
    fun persistPicked() {
        val off = ready.map { it.service }.filter { picked[it] == false }.map { it.id }.toSet()
        app.settings.update { it.copy(searchServicesOff = off) }
    }
    val selectedServices = ready.map { it.service }.filter { picked[it] == true }

    // Клавиатура закрывается при запуске поиска. Без этого она оставалась
    // поверх выдачи и при 1–2 результатах экран выглядел ПУСТЫМ — результаты
    // были, но за клавиатурой (жалоба 03.09.2026 «пустой экран поиска»).
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MediaSelection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val queued = remember { mutableStateMapOf<String, Boolean>() }

    // Фильтры поверх слитого результата — тоже запоминаются.
    var typeFilter by remember { mutableStateOf(settings.searchType) }   // 0 всё · 1 альбомы · 2 синглы/EP · 3 треки
    var sortNew by remember { mutableStateOf(settings.searchSortNew) }
    var yearText by remember { mutableStateOf(settings.searchYear) }
    // Один резолв воспроизведения за раз. Жалоба 03.09.2026: юзер жал ▶ на
    // карточке поиска несколько раз — каждый тап пускал свою корутину, все
    // молча висели на 20-с таймауте `ReleasePlayback.play`, ответом была тишина.
    // Плюс и строка, и кружок ▶ дёргают onPlay — даже один тап мог сдвоиться.
    var playPending by remember { mutableStateOf(false) }
    // Какую именно карточку сейчас резолвим — чтобы ореол зажёгся на ней, а не
    // на всех сразу. Строка «начинаю…» остаётся, но она над списком и при
    // прокрутке не видна: отклик должен быть там, где палец (жалоба e79).
    var playingKey by remember { mutableStateOf<String?>(null) }
    // Исход попытки ▶ — отдельно от `error`. `error` рисуется НАД списком, и
    // при прокрутке его не видно: жалоба e79 «нет звука, непонятно что
    // происходит» была именно про это. Исход показываем плашкой у нижнего края,
    // где палец и где мини-плеер: там его нельзя не заметить.
    var playNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playNotice) {
        // Плашка живёт ограниченно: сообщение об отказе не должно висеть
        // вечно и притворяться состоянием экрана.
        if (playNotice != null) {
            kotlinx.coroutines.delay(6000)
            playNotice = null
        }
    }
    LaunchedEffect(typeFilter, sortNew, yearText) {
        app.settings.update { it.copy(searchType = typeFilter, searchSortNew = sortNew, searchYear = yearText) }
    }

    fun go() {
        val q = query.trim()
        if (q.isEmpty() || running) return
        keyboard?.hide(); focusManager.clearFocus()
        running = true; error = null; result = null
        // IO-диспетчер: не полагаемся на то, что КАЖДЫЙ клиент сам ушёл с Main
        // (Яндекс, напр., этого не делал → NetworkOnMainThreadException).
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val isUrl = q.startsWith("http")
                if (isUrl) {
                    val merged = ready.firstNotNullOfOrNull { runCatching { it.resolve(q) }.getOrNull() }
                    result = merged
                    if (merged == null) error = tr("search.nothing", lang)
                    return@launch
                }
                val targets = selectedServices.ifEmpty { ready.map { it.service } }
                // Spotify (конверсия по ISRC) и BBC (nondrm по ссылке) НЕ умеют
                // текстовый поиск — их search() пуст. Если выбраны ТОЛЬКО такие —
                // это не «ничего не найдено», а «ищутся только по ссылке».
                if (targets.isNotEmpty() && targets.all { it in LINK_ONLY }) {
                    error = tr("search.link_only", lang)
                    return@launch
                }
                // Ищем в каждом выбранном сервисе, но НЕ глотаем ошибки: если
                // сервис упал — покажем его причину, а не «ничего не найдено».
                data class R(val svc: Service, val sel: MediaSelection?, val err: String?)
                val results = coroutineScope {
                    targets.map { svc ->
                        async {
                            // Потолок на КАЖДЫЙ сервис: один зависший login/
                            // ensureSession раньше держал весь поиск в «Ищу…»
                            // навсегда (awaitAll ждал самого медленного).
                            val r = runCatching {
                                // 22 с, а не 15: холодный Qobuz внутри одного
                                // поиска ещё может доскрейпить bundle.js (после
                                // первого раза он на диске — см. QobuzBundle).
                                kotlinx.coroutines.withTimeoutOrNull(22_000) {
                                    ServiceRegistry.get(svc)?.search(q)
                                } ?: throw java.io.IOException("__timeout__")
                            }
                            R(svc, r.getOrNull(), r.exceptionOrNull()?.let { humanNetError(it, lang) })
                        }
                    }.awaitAll()
                }
                val rawMerged = results.mapNotNull { it.sel }.reduceOrNull { a, b ->
                    a.copy(
                        tracks = a.tracks + b.tracks,
                        albums = a.albums + b.albums,
                        artists = a.artists + b.artists,
                    )
                }
                // Раньше здесь была просто склейка списков — точное совпадение
                // тонуло под рыхлыми, один трек дублировался по разу на сервис.
                // Прогоняем через ранкер: текст → популярность → аффинити к
                // библиотеке/истории → дедуп по ISRC/UPC.
                val merged = rawMerged?.let { rm ->
                    val lib = runCatching {
                        app.db.library().observeAll().first()
                    }.getOrDefault(emptyList())
                    val hist = runCatching { app.db.plays().recent(200) }.getOrDefault(emptyList())
                    val ctx = net.ripster.mobile.core.service.SearchRanker.Ctx(
                        libArtists = lib.map { net.ripster.mobile.core.service.SearchRanker.norm(it.artist) }.toSet(),
                        libAlbums = lib.mapNotNull { a ->
                            a.album?.let {
                                net.ripster.mobile.core.service.SearchRanker.norm(a.artist) + "|" +
                                    net.ripster.mobile.core.service.SearchRanker.norm(it)
                            }
                        }.toSet(),
                        histArtists = hist.map { net.ripster.mobile.core.service.SearchRanker.norm(it.artist) }.toSet(),
                    )
                    net.ripster.mobile.core.service.SearchRanker.rank(q, rm, ctx)
                }
                result = merged
                val hasContent = merged != null && (merged.tracks.isNotEmpty() || merged.albums.isNotEmpty())
                val failed = results.filter { it.err != null }
                when {
                    hasContent -> {
                        // частичный успех — тихо, но упомянем упавшие сервисы
                        if (failed.isNotEmpty()) {
                            error = failed.joinToString("\n") { "${it.svc.label}: ${it.err}" }
                        }
                    }
                    failed.size == results.size && failed.isNotEmpty() -> {
                        // все выбранные сервисы упали — это НЕ «ничего не найдено»
                        error = failed.joinToString("\n") { "${it.svc.label}: ${it.err}" }
                    }
                    // ровно один сервис, ответил без ошибки, но пусто — вероятно
                    // протух токен, а не «нет такого трека»
                    results.size == 1 && failed.isEmpty() ->
                        error = tr("search.one_empty", lang).replace("{svc}", results[0].svc.label)
                    else -> error = tr("search.nothing", lang)
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                running = false
            }
        }
    }

    // Пришли из карточки релиза по тапу на артиста/лейбл — подхватываем запрос.
    val busQuery by net.ripster.mobile.core.service.SearchBus.query.collectAsState()
    LaunchedEffect(busQuery, ready) {
        val q = busQuery
        if (!q.isNullOrBlank() && ready.isNotEmpty()) {
            net.ripster.mobile.core.service.SearchBus.consume()
            query = q
            go()
        }
    }

    Box(modifier.fillMaxSize().background(c.surface_canvas)) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (configured == null) {
            BasicText(tr("search.checking", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
            return@Column
        }
        if (ready.isEmpty()) {
            BasicText(tr("search.no_services", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
            return@Column
        }

        // ── одна строка: где искать → тап раскрывает меню с галочками ──
        val summary = when {
            selectedServices.isEmpty() -> tr("search.pick_none", lang)
            selectedServices.size == ready.size -> tr("search.pick_all", lang)
            else -> selectedServices.joinToString(", ") { it.label }
        }
        Box(
            Modifier.fillMaxWidth()
                .background(c.surface_raised, RoundedCornerShape(10.dp))
                .border(1.dp, if (pickerOpen) c.border_strong else c.border_subtle, RoundedCornerShape(10.dp))
                .clickable { pickerOpen = !pickerOpen }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    tr("search.in", lang) + ": ",
                    style = TextStyle(color = c.text_tertiary, fontSize = 13.sp),
                )
                BasicText(
                    summary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = c.text_primary, fontSize = 13.sp, fontWeight = FontWeight.W600),
                )
                BasicText(if (pickerOpen) "▲" else "▼", style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
            }
        }

        if (pickerOpen) {
            Box(Modifier.height(6.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(c.surface_raised, RoundedCornerShape(10.dp))
                    .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp))
                    .padding(vertical = 4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MiniLink(tr("search.pick_all", lang)) { ready.forEach { picked[it.service] = true }; persistPicked() }
                    MiniLink(tr("search.pick_none", lang)) { ready.forEach { picked[it.service] = false }; persistPicked() }
                }
                ready.forEach { client ->
                    val on = picked[client.service] == true
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { picked[client.service] = !on; persistPicked() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(18.dp)
                                .background(if (on) c.accent_fill else c.surface_active, RoundedCornerShape(5.dp))
                                .border(1.dp, if (on) c.accent_fill else c.border_subtle, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) BasicText("✓", style = TextStyle(color = c.text_on_fill, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        }
                        BasicText(client.service.label, style = TextStyle(color = c.text_primary, fontSize = 13.sp))
                        if (client.service in LINK_ONLY) {
                            BasicText(
                                tr("search.by_link", lang),
                                style = TextStyle(color = c.text_tertiary, fontSize = 10.sp),
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.height(10.dp))

        // ── строка запроса + кнопка ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f)
                    .background(c.surface_raised, RoundedCornerShape(10.dp))
                    .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (query.isEmpty()) {
                    BasicText(tr("search.hint", lang), style = TextStyle(color = c.text_tertiary, fontSize = 15.sp))
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.text_primary, fontSize = 15.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent_text),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { pickerOpen = false; go() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier
                    .background(if (running) c.surface_raised else c.accent_fill, RoundedCornerShape(10.dp))
                    .pressable(enabled = !running) { pickerOpen = false; go() }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                BasicText(
                    tr(if (running) "search.searching" else "search.go", lang),
                    style = TextStyle(
                        color = if (running) c.text_tertiary else c.text_on_fill,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        // ── фильтры результата: тип · сортировка · год ──
        if (result != null) {
            Box(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val types = listOf(
                    tr("search.type_all", lang), tr("search.type_albums", lang),
                    tr("search.type_singles", lang), tr("search.type_tracks", lang),
                )
                types.forEachIndexed { i, t ->
                    FilterChip(t, typeFilter == i) { typeFilter = i }
                }
                FilterChip(if (sortNew) tr("search.sort_new", lang) else tr("search.sort_rel", lang), sortNew) {
                    sortNew = !sortNew
                }
                Box(
                    Modifier.background(c.surface_raised, RoundedCornerShape(16.dp))
                        .border(1.dp, c.border_subtle, RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(tr("search.year", lang) + " ", style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                        Box(Modifier.size(width = 42.dp, height = 18.dp), contentAlignment = Alignment.CenterStart) {
                            if (yearText.isEmpty()) {
                                BasicText(tr("search.year_any", lang), style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                            }
                            BasicTextField(
                                value = yearText,
                                onValueChange = { v -> yearText = v.filter { it.isDigit() }.take(4) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = TextStyle(color = c.text_primary, fontSize = 11.sp),
                            )
                        }
                    }
                }
            }
        }

        // «Ничего не найдено» / «сервис вернул пусто» НИКОГДА не должно висеть над
        // непустым списком результатов (жалоба 03.09.2026, видео: «Nothing found»
        // и прямо под ним полная выдача). go() такое не ставит, но гонка снапшотов
        // между `result = merged` и присвоением `error` на IO-потоке, либо
        // устаревший error от прошлого поиска — могут. Гейтим по факту наличия
        // выдачи: ошибки уровня сервиса (в них есть подпись сервиса) остаются,
        // «пусто»-сообщения при живой выдаче — гасятся.
        val hasResults = result?.let { it.tracks.isNotEmpty() || it.albums.isNotEmpty() } == true
        val emptyNotes = remember(lang) {
            setOf(tr("search.nothing", lang), tr("search.filter_empty", lang))
        }
        error?.takeUnless { hasResults && it in emptyNotes }?.let {
            Box(Modifier.height(12.dp))
            BasicText(it, style = TextStyle(color = c.text_secondary, fontSize = 13.sp))
        }

        Box(Modifier.height(12.dp))

        val sel = result
        if (sel != null) {
            val yr = yearText.toIntOrNull()
            var albums = sel.albums
            var tracks = sel.tracks
            if (yr != null) {
                albums = albums.filter { it.year == null || it.year == yr }
                tracks = tracks.filter { it.year == null || it.year == yr }
            }
            // «сингл/EP» ≈ альбом на 1–3 трека; «альбом» ≈ 4+. ВАЖНО: у выдачи
            // поиска число треков часто НЕ приходит (null/0) — такие релизы
            // считаем альбомами, а не выкидываем (иначе фильтр давал ноль).
            when (typeFilter) {
                1 -> { albums = albums.filterNot { it.trackCount != null && it.trackCount in 1..3 }; tracks = emptyList() }
                2 -> { albums = albums.filter { it.trackCount != null && it.trackCount in 1..3 }; tracks = emptyList() }
                3 -> albums = emptyList()
            }
            val filteredToNothing = albums.isEmpty() && tracks.isEmpty() &&
                (sel.albums.isNotEmpty() || sel.tracks.isNotEmpty())
            if (sortNew) {
                // «Сначала новые» — ВТОРИЧНАЯ сортировка: сперва держим наверху то,
                // что реально совпало со всем запросом (иначе точный трек 2017-го
                // тонул под рыхлым релизом 2022-го — «искал X, нашёл Y»).
                val words = query.lowercase().trim().split(Regex("\\s+")).filter { it.length > 1 }
                fun hit(hay: String): Boolean {
                    if (words.isEmpty()) return false
                    val h = hay.lowercase()
                    return words.all { it in h }
                }
                albums = albums.sortedWith(
                    compareByDescending<net.ripster.mobile.core.model.Album> { hit(it.artist + " " + it.title) }
                        .thenByDescending { it.year ?: 0 },
                )
                tracks = tracks.sortedWith(
                    compareByDescending<net.ripster.mobile.core.model.Track> { hit(it.artist + " " + it.title) }
                        .thenByDescending { it.year ?: 0 },
                )
            }

            val quality = settings.qualityFor(onWifi = true)
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp),
            ) {
                if (filteredToNothing) {
                    item(key = "filt-empty") {
                        BasicText(
                            tr("search.filter_empty", lang),
                            Modifier.padding(vertical = 20.dp),
                            style = TextStyle(color = c.text_tertiary, fontSize = 13.sp),
                        )
                    }
                }
                if (albums.isNotEmpty()) {
                    item(key = "h-albums") { SectionHeader(tr("search.type_albums", lang)) }
                    // Индекс в ключе обязателен: у части источников id альбома
                    // приходит пустым, и два таких результата дают одинаковый ключ
                    // «a:qobuz:» → LazyGrid роняет весь экран (IllegalArgumentException
                    // "Key … was already used").
                    itemsIndexed(albums, key = { i, it -> "a:${it.service.id}:${it.id}:$i" }) { _, a ->
                        val akey = "a:${a.service.id}:${a.id}"
                        val albTracks = sel.tracks.filter {
                            it.service == a.service && (it.albumTitle == a.title || it.raw["albumId"] == a.id)
                        }
                        AlbumRow(
                            album = a,
                            queued = queued[akey] == true,
                            onArtist = { onOpenArtist(a.artist, a.service.id, "") },
                            onOpen = {
                                val u = streamableAlbumUrl(a.service.id, a.id)
                                if (u.isNotBlank()) onOpenAlbum(
                                    net.ripster.mobile.ui.components.ReleaseCardData(
                                        title = a.title, artist = a.artist, service = a.service.id,
                                        url = u, coverUrl = a.artworkUrl, trackCount = a.trackCount,
                                        type = when {
                                            (a.trackCount ?: 99) <= 3 -> "single"
                                            (a.trackCount ?: 0) in 4..6 -> "ep"
                                            else -> "album"
                                        },
                                    ),
                                ) else error = tr("search.cant_play", lang)
                            },
                            busy = playingKey == akey,
                            onPlay = {
                                if (!playPending) scope.launch {
                                    playPending = true
                                    playingKey = akey
                                    error = tr("search.starting", lang)   // мгновенный отклик, не тишина
                                    try {
                                        // Треков этого альбома в выдаче обычно НЕТ
                                        // (поиск отдал либо альбомы, либо треки).
                                        // Резолвим сам альбом по URL — как карточки релизов везде.
                                        var ok = false
                                        val items = net.ripster.mobile.core.service.StreamResolver
                                            .toStreamItems(albTracks, quality, limit = 40, fallbackArtwork = a.artworkUrl)
                                        if (items.isNotEmpty()) { app.player.playStream(items); ok = true }
                                        if (!ok) {
                                            val u = streamableAlbumUrl(a.service.id, a.id)
                                            if (u.isNotBlank()) ok = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                                                net.ripster.mobile.core.service.ReleasePlayback.play(app.player, u, quality, fallbackArtwork = a.artworkUrl)
                                            } == true
                                        }
                                        if (ok) { error = null; playNotice = null; onOpenPlayer() }
                                        else {
                                            val why = tr(
                                                if (a.service.id == "apple" || a.service.id == "soundcloud")
                                                    "search.cant_play" else "search.album_open_tracks", lang)
                                            error = why
                                            playNotice = why
                                        }
                                    } catch (t: Throwable) {
                                        val text = humanNetError(t, lang)
                                        error = text
                                        playNotice = text
                                    } finally {
                                        playPending = false
                                        playingKey = null
                                    }
                                }
                            },
                            onDownload = {
                                scope.launch {
                                    // Треков этого альбома в выдаче обычно НЕТ:
                                    // поиск отдаёт либо альбомы, либо треки. Раньше
                                    // кнопка в этом случае просто говорила «открой
                                    // вкладку Треки» и НЕ качала ничего — на живом
                                    // телефоне очередь оставалась пустой. Резолвим
                                    // релиз по ссылке, как это делает ▶.
                                    var list = albTracks
                                    if (list.isEmpty()) {
                                        val u = streamableAlbumUrl(a.service.id, a.id)
                                        if (u.isNotBlank()) {
                                            error = tr("search.starting", lang)
                                            list = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                                                net.ripster.mobile.core.service.ServiceRegistry.all()
                                                    .firstNotNullOfOrNull { runCatching { it.resolve(u) }.getOrNull() }
                                                    ?.tracks.orEmpty()
                                            }.orEmpty()
                                        }
                                    }
                                    if (list.isNotEmpty()) {
                                        list.forEach { app.downloads.enqueue(it) }
                                        queued[akey] = true
                                        error = null
                                    } else {
                                        error = tr("search.album_open_tracks", lang)
                                    }
                                }
                            },
                        )
                    }
                }
                if (tracks.isNotEmpty()) {
                    item(key = "h-tracks") { SectionHeader(tr("search.type_tracks", lang)) }
                    itemsIndexed(tracks, key = { i, it -> "t:${it.service.id}:${it.id}:$i" }) { idx, t ->
                        TrackRow(
                            track = t,
                            queued = queued["${t.service.id}:${t.id}"] == true,
                            onArtist = { onOpenArtist(t.artist, t.service.id, t.raw["artId"].orEmpty()) },
                            busy = playingKey == "${t.service.id}:${t.id}",
                            onPlay = {
                                if (!playPending) scope.launch {
                                    playPending = true
                                    playingKey = "${t.service.id}:${t.id}"
                                    error = tr("search.starting", lang)
                                    try {
                                        val ordered = tracks.drop(idx)
                                        // Порядок ровно такой, как ждёт человек,
                                        // нажавший ▶ на КОНКРЕТНОМ треке:
                                        //
                                        //   1. играем ЕГО с его же сервиса;
                                        //   2. не отдал поток — ищем ЭТОТ ЖЕ трек
                                        //      у тех, кто стримит (Deezer, Qobuz,
                                        //      Яндекс — любой подключённый);
                                        //   3. не нашли нигде — говорим, что
                                        //      слушать можно после скачивания.
                                        //
                                        // Раньше шаг 1 резолвил сразу ЧЕТЫРЕ трека
                                        // и играл то, что получилось: если нажатый
                                        // не стримился, а следующий стримился —
                                        // начинал играть не тот трек, по которому
                                        // нажали, и молча. Сначала — нажатый, и
                                        // только он.
                                        val first = net.ripster.mobile.core.service.StreamResolver
                                            .toStreamItems(listOf(t), quality, limit = 1)
                                        // Причину запоминаем СРАЗУ: playSearch ниже
                                        // сам зовёт резолвер и затрёт её своей.
                                        val ownWhy = net.ripster.mobile.core.service.StreamResolver
                                            .lastStreamError
                                        val head = first.ifEmpty {
                                            // Ищем ТОТ ЖЕ трек, а не «похожий по
                                            // названию»: TrackMatch сверяет ISRC, а
                                            // без него — название, исполнителя,
                                            // пометку версии и длительность разом.
                                            // Обычный текстовый поиск подсунул бы
                                            // ремикс или кавер, и человек слушал бы
                                            // не то, что нажал.
                                            val twin = net.ripster.mobile.core.service.TrackMatch
                                                .sameTrackElsewhere(t)
                                            if (twin != null) {
                                                net.ripster.mobile.core.service.StreamResolver
                                                    .toStreamItems(listOf(twin), quality, limit = 1)
                                            } else {
                                                emptyList()
                                            }
                                        }
                                        if (head.isEmpty()) {
                                            // Ни свой сервис, ни чужие. Если движок
                                            // объяснил отказ — показываем ЕГО причину:
                                            // она говорит, что делать. Иначе — общий
                                            // ответ про скачивание.
                                            val why = ownWhy
                                            val haveStream = net.ripster.mobile.core.service.ServiceRegistry.all()
                                                .any { it.service in setOf(Service.DEEZER, Service.QOBUZ, Service.TIDAL, Service.YANDEX) }
                                            val text = when {
                                                why != null -> humanNetError(why, lang)
                                                !haveStream -> tr("search.need_stream_svc", lang)
                                                else -> tr("search.download_to_listen", lang)
                                            }
                                            error = text
                                            playNotice = text
                                            return@launch
                                        }
                                        app.player.playStream(head)
                                        error = null
                                        playNotice = null
                                        onOpenPlayer()
                                        // Играет нажатый трек — дальше очередь из
                                        // остальных. drop(1), а не drop(4): пачку
                                        // из четырёх больше не резолвим.
                                        if (ordered.size > 1) {
                                            app.player.appendStream(
                                                net.ripster.mobile.core.service.StreamResolver
                                                    .toStreamItems(ordered.drop(1), quality, limit = 40),
                                            )
                                        }
                                    } catch (t: Throwable) {
                                        val text = humanNetError(t, lang)
                                        error = text
                                        playNotice = text
                                    } finally {
                                        playPending = false
                                        playingKey = null
                                    }
                                }
                            },
                            onQueue = {
                                scope.launch {
                                    app.downloads.enqueue(t)
                                    queued["${t.service.id}:${t.id}"] = true
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Плашка исхода — у нижнего края, поверх списка. Именно сюда смотрит
    // человек, нажавший ▶: строка над выдачей при прокрутке не видна.
    playNotice?.let { note ->
        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surface_raised)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicText(
                    note,
                    style = TextStyle(color = c.text_primary, fontSize = 13.sp, lineHeight = 18.sp),
                )
            }
        }
    }
    }
}

@Composable
private fun MiniLink(text: String, onClick: () -> Unit) {
    val c = RipsterTheme.colors
    BasicText(
        text,
        modifier = Modifier.pressable { onClick() },
        style = TextStyle(color = c.accent_text, fontSize = 12.sp, fontWeight = FontWeight.W600),
    )
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = RipsterTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (on) c.surface_active else c.surface_raised)
            .border(1.dp, if (on) c.accent_text else c.border_subtle, RoundedCornerShape(16.dp))
            .pressable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        BasicText(label, style = TextStyle(color = if (on) c.accent_text else c.text_tertiary, fontSize = 11.sp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    val c = RipsterTheme.colors
    BasicText(
        text.uppercase(),
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        style = TextStyle(color = c.text_tertiary, fontSize = 11.sp, fontWeight = FontWeight.W700, letterSpacing = 1.sp),
    )
}

@Composable
private fun AlbumRow(
    album: Album,
    queued: Boolean,
    busy: Boolean = false,
    onArtist: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    Row(
        // Тап по карточке — ОТКРЫТЬ альбом (любой: сингл/EP/компил), как везде
        // в приложении. ▶ рядом — «слушать потоком». Раньше вся строка играла,
        // и открыть релиз было нельзя (жалоба 03.09.2026).
        Modifier.fillMaxWidth()
            .busyHalo(busy, c.accent_text)
            .pressable(pressedBg = c.surface_raised) { onOpen() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        net.ripster.mobile.ui.components.Cover(
            url = album.artworkUrl,
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(6.dp),
        )
        Column(Modifier.weight(1f)) {
            BasicText(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_primary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            Box(Modifier.height(2.dp))
            Row {
                BasicText(
                    album.artist,
                    modifier = Modifier.pressable { onArtist() },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = c.accent_text, fontSize = 12.sp),
                )
                BasicText(
                    buildString {
                        append("  ·  ").append(album.service.label)
                        album.year?.let { append("  ·  ").append(it) }
                        album.trackCount?.let { append("  ·  ").append(it).append(" ").append(tr("search.tracks_short", lang)) }
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = c.text_tertiary, fontSize = 12.sp),
                )
            }
        }
        PlayCircle(onPlay, c)
        DownloadPill(queued = queued, onClick = onDownload, c = c, lang = lang)
    }
}

@Composable
private fun TrackRow(
    track: Track,
    queued: Boolean,
    busy: Boolean = false,
    onArtist: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    Row(
        Modifier
            .fillMaxWidth()
            .busyHalo(busy, c.accent_text)
            .pressable(pressedBg = c.surface_raised) { onPlay() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        net.ripster.mobile.ui.components.Cover(
            url = track.artworkUrl,
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(6.dp),
        )
        Column(Modifier.weight(1f)) {
            BasicText(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_primary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            Box(Modifier.height(2.dp))
            Row {
                BasicText(
                    track.artist,
                    modifier = Modifier.pressable { onArtist() },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = c.accent_text, fontSize = 12.sp),
                )
                BasicText(
                    "  ·  ${track.service.label}" + (track.year?.let { "  ·  $it" } ?: ""),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = c.text_tertiary, fontSize = 12.sp),
                )
            }
        }
        PlayCircle(onPlay, c)
        DownloadPill(queued = queued, onClick = onQueue, c = c, lang = lang)
    }
}

/** Кружок ▶ — «слушать потоком», как на карточках релизов везде в приложении. */
@Composable
private fun PlayCircle(onClick: () -> Unit, c: net.ripster.mobile.ui.theme.RipsterColors) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(c.surface_active).pressable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("▶", style = TextStyle(color = c.text_primary, fontSize = 12.sp))
    }
}

/** Явная кнопка «Скачать» / «В очереди» — чтобы новичку было видно, что делать. */
@Composable
private fun DownloadPill(queued: Boolean, onClick: () -> Unit, c: net.ripster.mobile.ui.theme.RipsterColors, lang: AppLang) {
    if (queued) {
        Row(
            Modifier.background(c.surface_active, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText("✓", style = TextStyle(color = c.success_text, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            BasicText(tr("search.queued", lang), style = TextStyle(color = c.text_secondary, fontSize = 12.sp))
        }
    } else {
        Row(
            Modifier.background(c.accent_fill, RoundedCornerShape(999.dp)).pressable { onClick() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            BasicText("↓", style = TextStyle(color = c.text_on_fill, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            BasicText(tr("search.dl", lang), style = TextStyle(color = c.text_on_fill, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
    }
}

/** Осталось для совместимости вызовов внутри экрана: разбор переехал в
 *  [net.ripster.mobile.ui.i18n.errorText], чтобы у всех экранов он был один. */
private fun humanNetError(e: Throwable, lang: AppLang): String = errorText(e, lang)

/** URL альбома из id+сервиса — для resolve()/ReleasePlayback, когда в выдаче
 *  поиска нет треков этого альбома. Пусто → плеер по этому альбому не собрать
 *  (Apple/SoundCloud на мобиле не стримятся, Spotify — только конверсия). */
private fun streamableAlbumUrl(serviceId: String, id: String): String {
    if (id.isBlank() || id == "0") return ""
    return when (serviceId) {
        "deezer" -> "https://www.deezer.com/album/$id"
        "qobuz" -> "https://open.qobuz.com/album/$id"
        "tidal" -> "https://listen.tidal.com/album/$id"
        "yandex" -> "https://music.yandex.ru/album/$id"
        "beatport" -> "https://www.beatport.com/release/_/$id"
        else -> ""
    }
}
