package net.ripster.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.ui.components.Cover
import net.ripster.mobile.ui.components.MARQUEE_SECOND_LINE_DELAY
import net.ripster.mobile.ui.components.rememberCoverEdgePalette
import net.ripster.mobile.ui.components.ripsterMarquee
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.premium.PremiumPlayerFx
import net.ripster.mobile.ui.premium.glassSquish
import net.ripster.mobile.ui.premium.liquidGlass
import net.ripster.mobile.ui.premium.liquidGlassSource
import net.ripster.mobile.ui.premium.rememberPremiumPlayerFx
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.Motion
import kotlin.math.hypot
import kotlin.math.min

/**
 * Четвёртый стиль плеера — «Флюид».
 *
 * Причина появления — вердикт владельца после сравнения с эталоном на эмуляторе
 * (23.09.2026): «наш стеклянный плеер не идёт в сравнение с эпл, там хорошие
 * большие кнопочки, мягкие и очень высокого качества». Наш ответ прежний —
 * маленькие диски с ободком вокруг каждого глифа и ряд подписанных чипов —
 * читается как дёшево. Поэтому этот стиль строится на других решениях:
 *  · транспорт — КРУПНЫЕ мягкие глифы без дисков и рамок, нарисованные нашими
 *    векторными путями (геометрия — [LiquidGlyphMath], покрыта тестами);
 *    отклик — стеклянный блоб, расцветающий ПОД глифом, а не обводка вокруг;
 *  · действия прячутся под «⋯» в стеклянном листе, а не семкой чипов в ряд;
 *  · фон — собственная тёмная заливка стиля из палитры обложки, глубокая и
 *    насыщенная, никогда не мутно-серая;
 *  · стекло живёт НА органах (пилюля качества, блобы нажатий, лист действий),
 *    это стиль самого плеера, не навес на весь экран (правило всех стилей).
 *
 * Остальные три стиля (Макет, Студийный, Погружение) этот файл не касается.
 */

/** Идентификатор стиля в настройке `playerStyle`. */
object LiquidStyle {
    const val ID = "liquid"
    const val NAME_KEY = "player.style_liquid"
}

/** Белый глиф транспорта: ~95%, без подложки-диска и без рамок. */
private val GlyphWhite = Color.White.copy(alpha = 0.95f)

/** Коды листов действий «Флюида» (те же панели, что у остальных стилей). */
private const val SHEET_NONE = 0
private const val SHEET_TRACKLIST = 1
private const val SHEET_LYRICS = 2
private const val SHEET_SPECTRUM = 3
private const val SHEET_EQ = 4
private const val SHEET_CAST = 6
private const val SHEET_SLEEP = 7
private const val SHEET_DOWNLOAD = 8

@Composable
fun LiquidPlayerScreen(
    state: NowPlayingState,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDownloadAlbum: () -> Unit,
) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val app = RipsterApp.from(LocalContext.current)
    // План телефона читают все стили через общий хелпер (сторож
    // PlayerPremiumWiringTest): «Флюид» не имеет права обещать стекло и не
    // рисовать его на своих органах.
    val fx = rememberPremiumPlayerFx()
    // Собственный фон стиля: низ палитры обложки, утопленный в почти чёрный.
    // Проба та же, что у остальных стилей (rememberCoverEdgePalette), — поэтому
    // фон «живой», из самой картинки, а не дефолтная серость; при выключенных
    // анимациях он просто статичен (никаких «дыханий» фона план не разрешает).
    val deep = Color(0xFF05050A)
    val pal = rememberCoverEdgePalette(state.artworkUrl)
    val tint = run {
        val base = pal.getOrElse(2) { Color(0xFF101018) }
        val b2 = pal.getOrElse(3) { base }
        lerp(lerp(base, b2, 0.5f), deep, 0.62f)
    }
    // «⋯» открывает стеклянный лист; выбранный пункт — панель поверх плеера,
    // ровно те же панели, что у остальных стилей (переиспользование 1-в-1).
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(SHEET_NONE) }
    // Сон: подпись пункта показывает обратный отсчёт, пока таймер заведён.
    val sleep by app.player.sleep.collectAsState()
    var sleepTick by remember { mutableStateOf(0L) }
    LaunchedEffect(sleep.active) {
        while (sleep.active) {
            sleepTick = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1000)
        }
    }
    val sleepLabel = when {
        !sleep.active -> tr("ref.sleep", lang)
        sleep.endOfTrack -> "♪ →"
        else -> {
            val left = (sleep.fireAtElapsed - (sleepTick.takeIf { it > 0 }
                ?: android.os.SystemClock.elapsedRealtime())).coerceAtLeast(0L)
            "${left / 60000}:${((left / 1000) % 60).toString().padStart(2, '0')}"
        }
    }

    Box(Modifier.fillMaxSize().background(deep)) {
        // Слой-источник стекла: панели размывают то, что стиль сам рисует под
        // ними, а не чужой ambilight.
        Box(
            Modifier.fillMaxSize().liquidGlassSource(fx, deep).background(
                Brush.verticalGradient(0f to deep, 0.62f to tint, 1f to deep),
            ),
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp)) {
            // ── ОБЛОЖКА: крупный квадрат, свой радиус 12dp и мягкая тень.
            // На паузе — пружинно сжимается до 0.9: «живой» центр композиции,
            // как у больших плееров; без пружин плана — стоит статично.
            val artScale by animateFloatAsState(
                targetValue = if (state.isPlaying) 1f else 0.9f,
                animationSpec = if (fx.plan.springMotion) Motion.standard else snap(),
                label = "liquid-art-scale",
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Cover(
                    url = state.artworkUrl,
                    modifier = Modifier.fillMaxWidth(0.74f).aspectRatio(1f).scale(artScale)
                        .shadow(26.dp, RoundedCornerShape(12.dp), clip = false),
                    shape = RoundedCornerShape(12.dp),
                )
            }
            Spacer(Modifier.height(22.dp))

            // ── Шапка: заголовок и исполнитель СЛЕВА, «⋯» справа. Долгие
            // названия — бегущая строка (наш общий marquee), а не многоточие.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        state.title,
                        maxLines = 1,
                        modifier = Modifier.ripsterMarquee(),
                        style = TextStyle(color = GlyphWhite, fontSize = 22.sp, fontWeight = FontWeight.W600),
                    )
                    Spacer(Modifier.height(3.dp))
                    BasicText(
                        state.artist,
                        maxLines = 1,
                        modifier = Modifier.ripsterMarquee(MARQUEE_SECOND_LINE_DELAY),
                        style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp),
                    )
                }
                DotsButton(fx = fx, behind = tint) { menu = true }
            }
            if (state.format.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                // Пилюля качества — маленький стеклянный орган у заголовка,
                // а не подпись: стекло здесь стиль самого элемента.
                val pill = RoundedCornerShape(7.dp)
                BasicText(
                    state.format,
                    modifier = Modifier.clip(pill)
                        .liquidGlass(fx, pill, tint, fallback = Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 10.5.sp),
                )
            }

            Spacer(Modifier.weight(1f))

            LiquidScrubber(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                fx = fx,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            LiquidTransport(
                isPlaying = state.isPlaying,
                loading = state.loading,
                fx = fx,
                behind = tint,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(22.dp))

            // Низ — три маленьких входа БЕЗ подписей в стеклянной капсуле:
            // текст, вывод звука (каст), очередь. Ряд чипов с ярлыками — ровно
            // то, за что вердикт 23.09.2026 назвал плеер дешёвым; здесь их
            // роль несёт форма: короткая строка мягких глифов на стекле.
            val route = rememberOutputRoute(lang)
            val capsule = RoundedCornerShape(24.dp)
            Row(
                Modifier.align(Alignment.CenterHorizontally)
                    .clip(capsule)
                    .liquidGlass(fx, capsule, tint, fallback = Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 20.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniAction(fx, onClick = { sheet = SHEET_LYRICS }) { lyricsGlyph(GlyphWhite) }
                MiniAction(fx, onClick = { sheet = SHEET_CAST }) { outGlyph(route.kind, GlyphWhite) }
                MiniAction(fx, onClick = { sheet = SHEET_TRACKLIST }) { listGlyph(GlyphWhite) }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ── Стеклянный лист действий вместо семи чипов (решение стиля:
        // показывать те же возможности, но одним входом).
        if (menu) {
            BackHandler(enabled = true) { menu = false }
            LiquidActionMenu(
                fx = fx,
                behind = tint,
                sleepLabel = sleepLabel,
                sleepActive = sleep.active,
                onClose = { menu = false },
                onPick = { id ->
                    menu = false
                    if (id == SHEET_DOWNLOAD) onDownloadAlbum() else sheet = id
                },
            )
        }

        // ── панель поверх плеера (те же панели, что у остальных стилей) ────
        if (sheet != SHEET_NONE) {
            BackHandler(enabled = true) { sheet = SHEET_NONE }
            Column(Modifier.fillMaxSize().background(deep)) {
                Row(
                    Modifier.fillMaxWidth().clickable { sheet = SHEET_NONE }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "‹",
                        style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.size(10.dp))
                    BasicText(
                        tr(
                            when (sheet) {
                                SHEET_TRACKLIST -> "ref.tracklist"; SHEET_LYRICS -> "ref.lyrics"
                                SHEET_SPECTRUM -> "ref.spectrum"; SHEET_CAST -> "ref.cast"
                                SHEET_SLEEP -> "sleep.title"; else -> "ref.equalizer"
                            }, lang,
                        ),
                        style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.W700),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (sheet) {
                        SHEET_TRACKLIST -> TracklistPanel(app, c) { sheet = SHEET_NONE }
                        SHEET_LYRICS -> LyricsPanel(state, c, lang)
                        SHEET_SPECTRUM -> SpectrumPanel(app, c, lang)
                        SHEET_SLEEP -> SleepPanel(app, c, lang)
                        SHEET_CAST -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        ) { net.ripster.mobile.ui.screens.cast.YandexStationBlock() }
                        else -> EqPanel(c, lang)
                    }
                }
            }
        }
    }
}

/** Одна маленькая иконка нижней капсулы: 24dp глиф, зона пальца 38dp. */
@Composable
private fun MiniAction(
    fx: PremiumPlayerFx,
    onClick: () -> Unit,
    glyph: DrawScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier.size(38.dp)
            .glassSquish(fx, pressed, plain = 0.85f, label = "liq-mini")
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) { glyph() }
    }
}

/** Круглая полупрозрачная кнопка «⋯»: вход во все действия стиля. */
@Composable
private fun DotsButton(
    fx: PremiumPlayerFx,
    behind: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val circle = CircleShape
    Box(
        Modifier.size(44.dp)
            .glassSquish(fx, pressed, plain = 0.9f, label = "liq-dots")
            .clip(circle)
            .liquidGlass(fx, circle, behind, fallback = Color.White.copy(alpha = 0.12f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            for (i in 0..2) {
                drawCircle(GlyphWhite, w * 0.085f, Offset(w * (0.22f + i * 0.28f), w * 0.5f))
            }
        }
    }
}

/**
 * Стеклянный лист действий: те же семь входов, что у других стилей чипами,
 * но одним — через «⋯». Панель и есть орган: стекло на ней (тон из-под неё,
 * блик, кромка), вне режима — тёмная полупрозрачная подложка.
 */
@Composable
private fun LiquidActionMenu(
    fx: PremiumPlayerFx,
    behind: Color,
    sleepLabel: String,
    sleepActive: Boolean,
    onClose: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val lang = LocalAppLang.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
    ) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)
                .clip(shape)
                .liquidGlass(fx, shape, behind, fallback = Color(0xE6121218))
                // съедает тап, чтобы лист не закрывался тапом ПО СЕБЕ
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(vertical = 8.dp),
        ) {
            MenuRow(SHEET_TRACKLIST, tr("ref.tracklist", lang), onPick) { listGlyph(GlyphWhite) }
            MenuRow(SHEET_LYRICS, tr("ref.lyrics", lang), onPick) { lyricsGlyph(GlyphWhite) }
            MenuRow(SHEET_SPECTRUM, tr("ref.spectrum", lang), onPick) { barsGlyph(GlyphWhite) }
            MenuRow(SHEET_EQ, tr("ref.equalizer", lang), onPick) { eqGlyph(GlyphWhite) }
            MenuRow(SHEET_SLEEP, sleepLabel, onPick, accent = sleepActive) { moonGlyph(GlyphWhite) }
            MenuRow(SHEET_CAST, tr("ref.cast", lang), onPick) { castGlyph(GlyphWhite) }
            MenuRow(SHEET_DOWNLOAD, tr("ref.download", lang), onPick) { downloadGlyph(GlyphWhite) }
        }
    }
}

@Composable
private fun MenuRow(
    id: Int,
    label: String,
    onPick: (Int) -> Unit,
    accent: Boolean = false,
    glyph: DrawScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) { onPick(id) }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(22.dp)) { glyph() }
        Spacer(Modifier.size(14.dp))
        BasicText(
            label,
            style = TextStyle(
                color = if (accent) Color(0xFFFF6B8B) else GlyphWhite,
                fontSize = 15.sp, fontWeight = FontWeight.W500,
            ),
        )
    }
}

/** Наша векторная «скачать»: стрелка на поддон — в том же мягком стиле. */
private fun DrawScope.downloadGlyph(color: Color) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.5f, w * 0.14f)
        lineTo(w * 0.5f, w * 0.56f)
    }
    drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    val arrow = Path().apply {
        moveTo(w * 0.28f, w * 0.38f); lineTo(w * 0.5f, w * 0.60f); lineTo(w * 0.72f, w * 0.38f)
    }
    drawPath(arrow, color, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    val tray = Path().apply {
        moveTo(w * 0.16f, w * 0.72f); lineTo(w * 0.16f, w * 0.86f); lineTo(w * 0.84f, w * 0.86f); lineTo(w * 0.84f, w * 0.72f)
    }
    drawPath(tray, color, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/**
 * Сик-бар стиля: тонкая капсула БЕЗ ползунка (5.5dp), на касании раздувается
 * до 12dp пружиной и сдувается; под концами — прошедшее и ОСТАТОК «−m:ss».
 * числа и цели анимации — в [LiquidScrubMath], покрыты тестом.
 */
@Composable
private fun LiquidScrubber(
    positionMs: Long,
    durationMs: Long,
    fx: PremiumPlayerFx,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var touching by remember { mutableStateOf(false) }
    val height by animateFloatAsState(
        targetValue = LiquidScrubMath.heightTarget(touching),
        animationSpec = if (fx.plan.springMotion) Motion.gentle else snap(),
        label = "liquid-scrub-height",
    )
    val frac = if (durationMs > 0)
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    fun seekAt(x: Float, width: Int) {
        if (durationMs > 0 && width > 0)
            onSeek((LiquidScrubMath.fraction(x, width.toFloat()) * durationMs).toLong())
    }
    Column(modifier) {
        // Зона пальца шире самой полосы: целиться в 5dp никто не будет.
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(durationMs) {
                    detectTapGestures(
                        onPress = { pos ->
                            touching = true
                            seekAt(pos.x, size.width)
                            tryAwaitRelease()
                            touching = false
                        },
                    )
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos -> touching = true; seekAt(pos.x, size.width) },
                        onDragEnd = { touching = false },
                        onDragCancel = { touching = false },
                    ) { change, _ ->
                        seekAt(change.position.x, size.width)
                        change.consume()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(height.dp)) {
                val h = size.height
                val r = androidx.compose.ui.geometry.CornerRadius(h / 2f)
                val sz = androidx.compose.ui.geometry.Size(size.width, h)
                drawRoundRect(Color.White.copy(alpha = 0.25f), Offset.Zero, sz, r)
                drawRoundRect(GlyphWhite, Offset.Zero,
                    androidx.compose.ui.geometry.Size(size.width * frac, h), r)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(
                LiquidScrubMath.clock(positionMs),
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp),
            )
            BasicText(
                LiquidScrubMath.remaining(positionMs, durationMs),
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp),
            )
        }
    }
}

/**
 * Транспорт стиля: крупные глифы без дисков. Никаких подложек-кружков и
 * обводок — только белый мягкий рисунок (~68dp play, ~42dp переходы), а под
 * пальцем вместо рамки — стеклянный блоб, расцветающий ПОД глифом; сам глиф
 * при этом продавливается до 0.88 и упруго возвращается. play↔pause — морф
 * геометрии ([LiquidGlyphMath.playPauseQuads]), а не резкая подмена картинки.
 *
 * Размеры слотов и щели между ними берутся из [LiquidTransportLayout] и только
 * там могут меняться: строка обязана быть симметрична ПО КРАЯМ рисунков, а не
 * по рамкам, поэтому каждый глиф центрируется по своему габариту
 * ([LiquidGlyphMath.inkCentering]) — иначе остриё большого треугольника
 * въезжало в щель к «вперёд» (замер на эмуляторе 23.09.2026).
 */
@Composable
private fun LiquidTransport(
    isPlaying: Boolean,
    loading: Boolean,
    fx: PremiumPlayerFx,
    behind: Color,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Морф живёт в доле 0..1: пружина, когда ей можно, иначе короткая
    // линейная смена; при выключенных системных анимациях доводчик
    // сжимается в мгновенный сам (шкала анимаций делает это с tween-ами).
    val morph by animateFloatAsState(
        targetValue = LiquidGlyphMath.morphTarget(isPlaying),
        animationSpec = if (fx.plan.springMotion) Motion.standard else tween(260),
        label = "liquid-play-morph",
    )
    val slots = LiquidTransportLayout.slots()
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(
            LiquidTransportLayout.GAP_DP.dp, Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftGlyph(
            slots[0], LiquidGlyphMath.skipInk(back = true),
            fx = fx, behind = behind, onSkip = onPrevious,
        ) { w -> skipGlyph(w, GlyphWhite, back = true) }
        SoftGlyph(
            slots[1], LiquidGlyphMath.playPauseInk(morph),
            fx = fx, behind = behind, enabled = !loading, loading = loading,
            onSkip = onPlayPause,
        ) { w -> playPauseGlyph(w, morph, GlyphWhite) }
        SoftGlyph(
            slots[2], LiquidGlyphMath.skipInk(back = false),
            fx = fx, behind = behind, onSkip = onNext,
        ) { w -> skipGlyph(w, GlyphWhite, back = false) }
    }
}

/**
 * Одна кнопка транспорта стиля: зона нажатия крупнее рисунка, под рисунком —
 * стеклянный блоб отклика (scale-in + fade пружиной [PremiumPlayerFx.pressSpec]),
 * сам глиф сжимается до 0.88. Дисков и рамок нет намеренно.
 *
 * Блоб стоит НЕ в центре слота, а в оптическом центре глифа ([ink]) — у
 * треугольника это центроид, а не середина рамки, иначе свет ложится криво.
 * И блоб не монтируется в покое: у стеклянного органа есть тень от
 * возвышения, а её `alpha` не гасит (см. [LiquidGlyphMath.blobMounted]) —
 * полагаться только на нулевую альфу нельзя, иначе в покое под глифом видно
 * серое пятно.
 *
 * `loading` — крутилка вместо глифа: нажатие засчитано, трек готовится
 * (прежняя жалоба «нет реакции» закрыта и здесь).
 */
@Composable
private fun SoftGlyph(
    slot: LiquidTransportLayout.Slot,
    ink: LiquidGlyphMath.LiquidInk,
    fx: PremiumPlayerFx,
    behind: Color,
    enabled: Boolean = true,
    loading: Boolean = false,
    onSkip: () -> Unit,
    draw: DrawScope.(Float) -> Unit,
) {
    val hit = slot.hit.dp
    val blob = slot.blob.dp
    val glyph = slot.glyph.dp
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Пружины запрещены планом — блоб не летает: показывается статичной
    // полупрозрачной монеткой на время нажатия и гаснет сразу.
    val blobSpec = if (fx.plan.springMotion) fx.pressSpec else snap()
    val blobScale by animateFloatAsState(LiquidGlyphMath.blobScaleTarget(pressed), blobSpec, label = "liq-blob-scale")
    val blobAlpha by animateFloatAsState(LiquidGlyphMath.blobAlphaTarget(pressed), blobSpec, label = "liq-blob-alpha")
    val circle = CircleShape
    Box(
        Modifier.size(hit)
            .clickable(
                interactionSource = interaction, indication = null,
                enabled = enabled, onClick = onSkip,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (LiquidGlyphMath.blobMounted(blobAlpha)) {
            Box(
                Modifier.size(blob)
                    // монета крупнее зоны нажатия из её центра не вылезает:
                    // ребёнок больше родителя в Box ставится в отрицательный
                    // offset, и центрирование уезжает
                    .offset(x = glyph * LiquidGlyphMath.blobShift(ink))
                    .scale(blobScale)
                    .alpha(blobAlpha)
                    .clip(circle)
                    .liquidGlass(fx, circle, behind, fallback = Color.White.copy(alpha = 0.14f)),
            )
        }
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(glyph * 0.38f), color = GlyphWhite, strokeWidth = 3.dp,
            )
        } else {
            Canvas(
                Modifier.size(glyph)
                    .glassSquish(fx, pressed, plain = 0.88f, label = "liq-glyph"),
            ) { draw(size.minDimension) }
        }
    }
}

/** Морф play→pause: две четверох из [LiquidGlyphMath], мягко огранённые. */
private fun DrawScope.playPauseGlyph(w: Float, m: Float, color: Color) {
    val (left, right) = LiquidGlyphMath.playPauseQuads(m)
    val (wl, wr) = LiquidGlyphMath.playPauseCornerWeights(m)
    val shift = LiquidGlyphMath.inkCentering(LiquidGlyphMath.playPauseInk(m)) * w
    val r = LiquidGlyphMath.morphCornerRadius(m) * w
    translate(shift, 0f) {
        drawPath(roundedPolyPath(left, w, r, wl), color)
        drawPath(roundedPolyPath(right, w, r, wr), color)
    }
}

/** Переход: два наших скруглённых треугольника, зеркало для «назад». */
private fun DrawScope.skipGlyph(w: Float, color: Color, back: Boolean) {
    val ink = LiquidGlyphMath.skipInk(back)
    val shift = LiquidGlyphMath.inkCentering(ink) * w
    translate(shift, 0f) {
        for (tri in LiquidGlyphMath.skipTriangles(back)) {
            drawPath(roundedPolyPath(tri, w, w * 0.09f), color)
        }
    }
}

/**
 * Многоугольник со скруглёнными вершинами: к каждой вершине радиус
 * подтесается до половины более короткого из двух прилегающих рёбер — тогда
 * вырожденный угол (вершина треугольника в начале морфа, где две точки
 * совпадают) не «выплёвывает» безумную дугу, а остаётся острым, но мягким.
 *
 * [weights] — по весу на вершину (1 — полный радиус, 0 — оставить острой):
 * так половины play склеиваются в один треугольник по острому шву (см.
 * [LiquidGlyphMath.playPauseCornerWeights]), а не расходятся двумя фигурами.
 */
private fun roundedPolyPath(pts: FloatArray, s: Float, rIn: Float, weights: FloatArray? = null): Path {
    val n = pts.size / 2
    val path = Path()
    if (n < 3) return path
    val vx = FloatArray(n) { pts[it * 2] * s }
    val vy = FloatArray(n) { pts[it * 2 + 1] * s }
    val r = FloatArray(n) { i ->
        val p = (i - 1 + n) % n
        val nx = (i + 1) % n
        val d1 = hypot(vx[i] - vx[p], vy[i] - vy[p])
        val d2 = hypot(vx[i] - vx[nx], vy[i] - vy[nx])
        val weight = weights?.getOrNull(i) ?: 1f
        min(rIn * weight, min(d1, d2) * 0.5f)
    }
    val aX = FloatArray(n)
    val aY = FloatArray(n)
    val bX = FloatArray(n)
    val bY = FloatArray(n)
    for (i in 0 until n) {
        val p = (i - 1 + n) % n
        val nx = (i + 1) % n
        val (uxp, uyp) = unitTo(vx[p] - vx[i], vy[p] - vy[i])
        val (uxn, uyn) = unitTo(vx[nx] - vx[i], vy[nx] - vy[i])
        aX[i] = vx[i] + uxp * r[i]; aY[i] = vy[i] + uyp * r[i]
        bX[i] = vx[i] + uxn * r[i]; bY[i] = vy[i] + uyn * r[i]
    }
    // Обход: от a[0] дугой вокруг вершины 0 в b[0], ребром до a[1], дугой…
    // Последнее ребро (b[n-1] → a[0]) натягивает close(). При r=0 a==b==вершина,
    // и вырожденный угол становится обычным острым — ломать контур нечем.
    path.moveTo(aX[0], aY[0])
    for (i in 0 until n) {
        path.quadraticBezierTo(vx[i], vy[i], bX[i], bY[i])
        val next = (i + 1) % n
        if (next != 0) path.lineTo(aX[next], aY[next])
    }
    path.close()
    return path
}

private fun unitTo(dx: Float, dy: Float): Pair<Float, Float> {
    val d = hypot(dx, dy)
    if (d == 0f) return 0f to 0f
    return dx / d to dy / d
}
