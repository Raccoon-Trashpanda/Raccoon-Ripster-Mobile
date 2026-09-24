package net.ripster.mobile.ui.screens

import net.ripster.mobile.ui.components.MARQUEE_SECOND_LINE_DELAY
import net.ripster.mobile.ui.components.ripsterMarquee
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import net.ripster.mobile.ui.components.WaveformSeek
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.ui.theme.CaptionFit
import net.ripster.mobile.ui.theme.PlayerDarkSurface
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.legibleOn
import net.ripster.mobile.ui.components.QualityBadge
import net.ripster.mobile.ui.components.QualityBadgeState
import net.ripster.mobile.ui.components.SeekPlaybackState
import net.ripster.mobile.ui.components.SeekStrip
import net.ripster.mobile.ui.components.drawNextGlyph
import net.ripster.mobile.ui.components.drawPrevGlyph
import net.ripster.mobile.ui.components.drawRepeatGlyph
import net.ripster.mobile.ui.components.drawShuffleGlyph
import net.ripster.mobile.ui.components.rememberCoverEdgePalette
import net.ripster.mobile.ui.premium.PremiumMotion
import net.ripster.mobile.ui.premium.PremiumPlayerFx
import net.ripster.mobile.ui.premium.glassSquish
import net.ripster.mobile.ui.premium.glyphColor
import net.ripster.mobile.ui.premium.liquidGlass
import net.ripster.mobile.ui.premium.premiumCoverEnter
import net.ripster.mobile.ui.premium.rememberPremiumPlayerFx
import net.ripster.mobile.ui.premium.liquidGlassSource
import net.ripster.mobile.ui.theme.Motion
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr

/**
 * Фон «Studio»: near-black, поверх которого ложится всё — и подписи тоже.
 *
 * Величина взята из theme/ChromeSurface.kt, а не живёт здесь: хром обязан
 * называть ту же поверхность, что и плеер, иначе над развёрнутым плеером
 * ляжет светлая полоса (см. chromeColorsFor).
 */
internal val StudioBackground = PlayerDarkSurface

/**
 * Now Playing — тема «Studio». Данные приходят параметром [NowPlayingState].
 */
data class NowPlayingState(
    val title: String,
    val artist: String,
    val album: String,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long = 0,
    val isPlaying: Boolean,
    val loading: Boolean = false,
    val shuffle: Boolean,
    val repeat: Boolean,
    val quality: QualityBadgeState,
    val format: String,
    val artworkUrl: String? = null,
)

/**
 * Тема «Studio» — кинематографичный now-playing (редизайн 14.09.2026 по дизайн-
 * процессу apply-aesthetic; владелец: прежняя «параша/убожество»).
 *
 * Решения против ИИ-клише:
 *  · ФОН — палитровая заливка ИЗ обложки (её же цвета, затемнённые в near-black),
 *    а НЕ сырое фото и не серая стеклянная коробка. Под обложкой — мягкое цветное
 *    свечение из той же палитры (глубина без дорогого blur).
 *  · Контролы «плавают» на заливке, без тяжёлой карты.
 *  · Есть полноценный ряд действий (Трек-лист/Текст/Спектр/Эквалайзер/Каст) —
 *    раньше в этой теме их не было вовсе; «Скачать альбом» — компактной иконкой
 *    среди них, а не огромной кнопкой.
 */
@Composable
fun NowPlayingScreen(
    state: NowPlayingState,
    onSeek: (Long) -> Unit,
    onScrubPreview: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onDownloadAlbum: () -> Unit,
    /**
     * Прямоугольник обложки мини-плашки: из неё выезжает большая обложка.
     * `null` (режим выкл, пружины запрещены или плашка не замерена) — обложка
     * появляется ровно там же, где появлялась всегда.
     */
    enterFromCover: androidx.compose.ui.geometry.Rect? = null,
    modifier: Modifier = Modifier,
) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val app = RipsterApp.from(LocalContext.current)
    // План этого телефона — через общий хелпер плееров: Studio не имеет права
    // остаться единственной оболочкой, которой режим обещан, но не дан.
    // Фон стиля не меняется: под панелями живёт собственная заливка StudioAmbient,
    // и она же — источник размытия для стекла (см. liquidGlassSource ниже).
    val fx = rememberPremiumPlayerFx()
    val plan = fx.plan
    var sheet by remember { mutableStateOf(0) }
    // Таймер сна: подпись кнопки — обратный отсчёт, пока активен.
    val sleep by app.player.sleep.collectAsState()
    var sleepTick by remember { mutableStateOf(0L) }
    LaunchedEffect(sleep.active) {
        while (sleep.active) { sleepTick = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(1000) }
    }
    val sleepLabel = when {
        !sleep.active -> tr("ref.sleep", lang)
        sleep.endOfTrack -> "♪ →"
        else -> {
            val left = (sleep.fireAtElapsed - (sleepTick.takeIf { it > 0 } ?: android.os.SystemClock.elapsedRealtime())).coerceAtLeast(0L)
            "${left / 60000}:${((left / 1000) % 60).toString().padStart(2, '0')}"
        }
    }

    // Действия плеера — СПИСОК, а не семь вызовов подряд: ряд раскладывается по
    // тому, сколько ячейке достаётся ширины (см. CaptionFit), для чего состав
    // надо укладывать строками.
    val studioActions = listOf(
        StudioActionSpec(tr("ref.tracklist", lang), onClick = { sheet = 1 }) { listGlyph(it) },
        StudioActionSpec(tr("ref.lyrics", lang), onClick = { sheet = 2 }) { lyricsGlyph(it) },
        StudioActionSpec(tr("ref.spectrum", lang), onClick = { sheet = 3 }) { barsGlyph(it) },
        StudioActionSpec(tr("ref.equalizer", lang), onClick = { sheet = 4 }) { eqGlyph(it) },
        StudioActionSpec(sleepLabel, onClick = { sheet = 7 }, active = sleep.active) { moonGlyph(it) },
        StudioActionSpec(tr("ref.cast", lang), onClick = { sheet = 6 }) { castGlyph(it) },
        StudioActionSpec(tr("np.dl_short", lang), onClick = onDownloadAlbum) { dlGlyph(it) },
    )

    // Волновой сик-бар: честные пики трека. Декод локального файла на IO + кэш;
    // нет пути/стрим/не вышло → null → откат на обычную полосу.
    val ctx = LocalContext.current
    val currentPath = app.player.state.collectAsState().value.currentPath
    val peaks by androidx.compose.runtime.produceState<FloatArray?>(null, currentPath) {
        value = net.ripster.mobile.core.audio.Waveform.peaks(ctx, currentPath)
    }

    // Палитра краёв обложки → цвет заливки/свечения. Затемняем к near-black.
    val palette = rememberCoverEdgePalette(state.artworkUrl)
    val deep = StudioBackground
    val topTint = lerp(palette.getOrElse(0) { deep }, deep, 0.62f)
    val glow = palette.getOrElse(1) { c.accent_fill }
    // Органы Studio плавают над палитровой заливкой: стекло подмешивает себе её
    // тон, а не имя темы. Здесь controls лежат в нижней, затемнённой части.
    val glassBehind = deep

    BoxWithConstraints(modifier.fillMaxSize().background(deep)) {
        // ── фон ──
        // Статичная палитровая заливка со стабильными Color-входами: Compose
        // ПРОПУСКАЕТ её на тик позиции (5×/сек), поэтому тяжёлый полноэкранный
        // градиент+дизер не перерисовываются зря — плавнее. Дорогим визуалам
        // здесь делать нечего: режим меняет ТО, как нарисованы органы
        // управления, а фон Studio остаётся ровно тем, что был до режима.
        // Заливка помечена как слой-источник: стеклянные панели размывают её.
        StudioAmbient(
            topTint = topTint, deep = deep,
            modifier = Modifier.liquidGlassSource(fx, deep),
        )

        val coverSide = (maxHeight * 0.30f).coerceAtMost(280.dp)
        // Ширина, доступная ряду действий: экран минус поля колонны (26.dp×2).
        val actionRowWidthDp = (maxWidth - 52.dp).value
        // Масштаб обложки берётся из чистого слоя (PremiumMotion): вне режима и
        // при «без анимации» он 1f в обоих состояниях, то есть движения нет.
        val artworkScale by animateFloatAsState(
            targetValue = PremiumMotion.artworkScale(plan.springMotion, state.isPlaying),
            animationSpec = Motion.standard,
            label = "artwork-breath",
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            // Обложка с мягким цветным свечением снизу.
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(coverSide + 60.dp)) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(glow.copy(alpha = 0.34f), Color.Transparent),
                            radius = size.minDimension / 2f,
                        ),
                        radius = size.minDimension / 2f,
                        center = Offset(size.width / 2f, size.height * 0.62f),
                    )
                }
                net.ripster.mobile.ui.components.Cover(
                    url = state.artworkUrl,
                    modifier = Modifier.size(coverSide)
                        // Доехать из мини-плашки (пустой модификатор, когда пружины
                        // запрещены или плашка ещё не замеряла себя).
                        .premiumCoverEnter(enterFromCover)
                        // Сесть на паузе и пружинить обратно на старте: масштаб
                        // считает чистый PremiumMotion, вне режима он 1f всегда.
                        .graphicsLayer {
                            scaleX = artworkScale
                            scaleY = artworkScale
                        }
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                )
            }

            Spacer(Modifier.height(28.dp))
            BasicText(
                state.title, maxLines = 1, modifier = Modifier.ripsterMarquee(),
                style = TextStyle(color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.W800,
                    letterSpacing = (-0.5).sp, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                if (state.album.isNotBlank() && !state.album.equals(state.title, true))
                    "${state.artist}  ·  ${state.album}" else state.artist,
                maxLines = 1, modifier = Modifier.ripsterMarquee(MARQUEE_SECOND_LINE_DELAY),
                style = TextStyle(color = Color.White.copy(alpha = 0.64f), fontSize = 14.5.sp, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(12.dp))
            // формат + вердикт качества, тап → «Паспорт трека»
            Row(
                Modifier.liquidGlass(
                    // Строка качества — тоже орган: стеклянная капсула, под которой
                    // видно заливку. Без стекла капсула невидима, ряд остаётся тем,
                    // чем был до режима.
                    fx, RoundedCornerShape(50), glassBehind, fallback = Color.Transparent,
                ).clip(RoundedCornerShape(50)).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { sheet = 5 }.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (state.format.isNotBlank()) BasicText(
                    state.format,
                    style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                )
                QualityBadge(state = state.quality)
            }

            if (state.title.isNotBlank()) {
                val route = rememberOutputRoute(lang)
                Spacer(Modifier.height(9.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Canvas(Modifier.size(13.dp)) { outGlyph(route.kind, Color.White.copy(alpha = 0.6f)) }
                    BasicText(
                        route.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            val wf = peaks
            // Сик-бар — орган, а не голая линия: стеклянная капсула под дорожкой.
            // Без режима капсулы и её отступов нет, полоса стоит как раньше.
            val seekPad = if (fx.hasGlass) 14.dp else 0.dp
            Box(
                Modifier.fillMaxWidth()
                    .liquidGlass(fx, RoundedCornerShape(16.dp), glassBehind, fallback = Color.Transparent)
                    .padding(horizontal = seekPad, vertical = seekPad / 2f),
            ) {
                if (wf != null && state.durationMs > 0) {
                    WaveformSeek(
                        peaks = wf, positionMs = state.positionMs, durationMs = state.durationMs,
                        onSeek = onSeek, onScrubChange = onScrubPreview,
                        tint = c.accent_fill,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    )
                } else {
                    SeekStrip(
                        positionMs = state.positionMs, durationMs = state.durationMs,
                        onSeek = onSeek, onScrubChange = onScrubPreview,
                        state = if (state.isPlaying) SeekPlaybackState.Playing else SeekPlaybackState.Paused,
                        // «Studio» красит собственный near-black, а не surface_*
                        // палитры: без честного ответа про фон компонент зажимает
                        // цвета против поверхности, под которой их никто не рисует.
                        surfaceBehind = deep,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            // Транспорт: шафл · prev · [Play] · next · повтор
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleGlyph(active = state.shuffle, onClick = onToggleShuffle, accent = c.accent_text, fx = fx, behind = glassBehind) { drawShuffleGlyph(it) }
                GlassCircle(52.dp, onPrevious, fx, glassBehind) { drawPrevGlyph(it) }
                AccentPlay(isPlaying = state.isPlaying, loading = state.loading, accent = c.accent_fill, onClick = onPlayPause, fx = fx)
                GlassCircle(52.dp, onNext, fx, glassBehind) { drawNextGlyph(it) }
                ToggleGlyph(active = state.repeat, onClick = onToggleRepeat, accent = c.accent_text, fx = fx, behind = glassBehind) { drawRepeatGlyph(it) }
            }

            Spacer(Modifier.height(18.dp))
            // Ряд действий. Ширина раздаётся не «поровну и как получится»: на
            // 411dp семи ячейкам достаётся по 51dp, чего «Эквалайзеру» не хватает,
            // и подпись умирала как «Эквала…». Сначала считаем, сколько ячеек
            // влезает дочитываемой, и только тогда кладём; на узком экране ряд
            // раскладывается в два, скролла нет.
            val rowWidthDp = actionRowWidthDp
            val gapDp = 4.dp.value
            val columns = CaptionFit.columnsFor(
                count = studioActions.size,
                rowWidthDp = rowWidthDp,
                gapDp = gapDp,
                minSlotDp = CaptionFit.ActionRowMinSlotDp,
            )
            val slotDp = CaptionFit.slotDp(rowWidthDp, minOf(columns, studioActions.size), gapDp)
            // Кегль и число строк — ОДНИ на весь ряд, и вычисляются здесь, а не
            // в кнопке: посчитанные по отдельности подписи легли бы на разные
            // высоты, и ряд разъехался бы вертикально (тот же дефект, что ловили
            // на табах 03.09.2026).
            val actionLabels = studioActions.map { it.label }
            val captionLines = CaptionFit.rowCaptionLines(actionLabels, slotDp)
            val captionSp = CaptionFit.rowCaptionSp(actionLabels, slotDp, captionLines).value
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                studioActions.chunked(columns).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gapDp.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        row.forEach { action ->
                            StudioAction(
                                label = action.label,
                                onClick = action.onClick,
                                active = action.active,
                                fx = fx,
                                behind = glassBehind,
                                captionSp = captionSp,
                                captionLines = captionLines,
                                modifier = Modifier.weight(1f),
                                draw = action.draw,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }

        // ── панели поверх (те же, что в других плеерах) ──
        if (sheet != 0) {
            BackHandler(enabled = true) { sheet = 0 }
            Column(Modifier.fillMaxSize().background(deep)) {
                Row(
                    Modifier.fillMaxWidth().clickable { sheet = 0 }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText("‹", style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 22.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        tr(when (sheet) {
                            1 -> "ref.tracklist"; 2 -> "ref.lyrics"; 3 -> "ref.spectrum"
                            5 -> "pass.title"; 6 -> "ref.cast"; 7 -> "sleep.title"; else -> "ref.equalizer"
                        }, lang),
                        style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.W700),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (sheet) {
                        1 -> TracklistPanel(app, c) { sheet = 0 }
                        2 -> LyricsPanel(state, c, lang)
                        3 -> SpectrumPanel(app, c, lang)
                        5 -> StreamInfoPanel(app, c, lang)
                        6 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            net.ripster.mobile.ui.screens.cast.YandexStationBlock()
                        }
                        7 -> SleepPanel(app, c, lang)
                        else -> EqPanel(c, lang)
                    }
                }
            }
        }
    }
}

/** Круглая стеклянная кнопка транспорта. */
@Composable
private fun GlassCircle(
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    fx: PremiumPlayerFx,
    behind: Color,
    draw: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier.size(size)
            .glassSquish(fx, pressed, plain = 0.88f, label = "gc")
            .clip(CircleShape)
            // OFF: глухая плашка surface + рамка, как была. ON: стекло рисует тон
            // из-под органа и весь свет само, рамка темы ему только мешает.
            .liquidGlass(fx, CircleShape, behind, fallback = Color.White.copy(alpha = 0.09f))
            .then(if (fx.hasGlass) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size * 0.42f)) { draw(fx.glyphColor(Color.White.copy(alpha = 0.9f), behind)) } }
}

/** Плоский глиф-переключатель (шафл/повтор): active подсвечен акцентом. */
@Composable
private fun ToggleGlyph(
    active: Boolean,
    onClick: () -> Unit,
    accent: Color,
    fx: PremiumPlayerFx,
    behind: Color,
    draw: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val own = if (active) accent else Color.White.copy(alpha = 0.55f)
    Box(
        Modifier.size(44.dp)
            .glassSquish(fx, pressed, plain = 0.82f, label = "tg")
            .then(
                if (fx.hasGlass) Modifier.clip(CircleShape).liquidGlass(fx, CircleShape, behind, fallback = Color.Transparent)
                else Modifier,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(20.dp)) { draw(fx.glyphColor(own, behind)) } }
}

/** Круглая акцентная Play с мягким свечением. */
@Composable
private fun AccentPlay(
    isPlaying: Boolean,
    loading: Boolean,
    accent: Color,
    onClick: () -> Unit,
    fx: PremiumPlayerFx,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Акцентная Play остаётся акцентной — лицо Studio; пружину нажатия берёт у стекла.
    Box(
        Modifier.size(72.dp).glassSquish(fx, pressed, plain = 0.9f, label = "pp"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.4f), Color.Transparent), radius = this.size.minDimension / 2f))
        }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(accent)
                .clickable(interactionSource = interaction, indication = null, enabled = !loading, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) androidx.compose.material3.CircularProgressIndicator(
                Modifier.size(26.dp), color = Color(0xFF0D0F13), strokeWidth = 2.5.dp)
            else Canvas(Modifier.size(25.dp)) {
                val w = size.width; val col = Color(0xFF0D0F13)
                if (isPlaying) {
                    drawRect(col, Offset(w * 0.16f, 0f), Size(w * 0.22f, w))
                    drawRect(col, Offset(w * 0.62f, 0f), Size(w * 0.22f, w))
                } else {
                    val p = Path().apply { moveTo(w * 0.2f, 0f); lineTo(w * 0.2f, w); lineTo(w, w * 0.5f); close() }
                    drawPath(p, col)
                }
            }
        }
    }
}

/** Полноэкранный амбиент Studio: палитровый градиент + дизер. Вынесен отдельно
 *  со стабильными Color-входами — Compose пропускает его на тик позиции, и
 *  тяжёлый фон не перерисовывается зря (плавнее свёртка/развёртка). */
@Composable
private fun StudioAmbient(topTint: Color, deep: Color, modifier: Modifier = Modifier) {
    val dither = net.ripster.mobile.ui.components.rememberDitherBrush()
    Box(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to topTint, 0.55f to lerp(topTint, deep, 0.7f), 1f to deep),
        ),
    )
    Canvas(Modifier.fillMaxSize()) { drawRect(dither, alpha = 0.035f) }
}

/** Одно действие ряда «Studio»: подпись, что открывает, и глиф. */
private class StudioActionSpec(
    val label: String,
    val onClick: () -> Unit,
    val active: Boolean = false,
    val draw: DrawScope.(Color) -> Unit,
)

/**
 * Компактная кнопка действия (иконка + подпись). Кегль и число строк подписи
 * приходят ИЗВНЕ (см. CaptionFit): их считает ряд целиком, иначе подписи разной
 * длины легли бы на разные высоты. Многоточие — единственный честный исход,
 * когда не хватило и двух строк.
 */
@Composable
private fun StudioAction(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    fx: PremiumPlayerFx,
    behind: Color,
    captionSp: Float = 12f,
    captionLines: Int = 1,
    modifier: Modifier = Modifier,
    draw: DrawScope.(Color) -> Unit,
) {
    val accent = Color(0xFFFF6B8B)
    val tile = RoundedCornerShape(13.dp)
    val fill = if (active) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.11f)
    val rim = if (active) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.22f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Контраст поднят: плитка и подпись раньше тонули в near-black (жалоба
    // «сливаются, нечитаемо»). Фон/рамка/глиф/подпись стали заметно светлее.
    // Подпись — не менее 4.5:1 к фону экрана, иначе «Эквалайзер» формально есть,
    // а глазами его не собрать.
    val captionInk = legibleOn(
        if (active) accent else Color.White.copy(alpha = 0.86f),
        StudioBackground,
    )
    Column(
        modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(42.dp)
                // Пружину добавляем только под стеклом: без режима плитки не
                // сжимались, и OFF обязан остаться ровно таким.
                .then(if (fx.hasGlass) Modifier.glassSquish(fx, pressed) else Modifier)
                .clip(tile)
                // Активная плитка (заведённый таймер) сохраняет акцентную рамку и
                // при стекле — иначе состояние перестало бы читаться.
                .liquidGlass(fx, tile, behind, fallback = fill)
                .then(if (fx.hasGlass && !active) Modifier else Modifier.border(1.dp, rim, tile))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Canvas(Modifier.size(19.dp)) { draw(fx.glyphColor(if (active) accent else Color.White.copy(alpha = 0.95f), behind)) } }
        val lineHeightSp = captionSp * 1.18f
        // Высота резервируется в dp, но считается из кегля: строка `12.sp × 2`
        // на системном масштабе 1.3 занимает не 24dp, и фиксированная плитка
        // молча съела бы вторую строку.
        val captionBlockDp = with(androidx.compose.ui.platform.LocalDensity.current) {
            (lineHeightSp * captionLines).sp.toDp()
        }
        BasicText(
            label, maxLines = captionLines, overflow = TextOverflow.Ellipsis,
            // Место под подпись резервируется фиксированной высотой, а не
            // «сколько заняло»: кнопка с одной строкой рядом с двухстрочной
            // сдвинула бы иконку вверх, и ряд перестал бы быть рядом.
            modifier = Modifier
                .fillMaxWidth()
                .height(captionBlockDp),
            style = TextStyle(
                color = captionInk,
                fontSize = captionSp.sp,
                lineHeight = lineHeightSp.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** Глиф «скачать» — стрелка вниз в лоток. */
private fun DrawScope.dlGlyph(color: Color) {
    val w = size.width; val h = size.height; val sw = w * 0.1f
    drawLine(color, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.62f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.28f, h * 0.42f), Offset(w * 0.5f, h * 0.64f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.72f, h * 0.42f), Offset(w * 0.5f, h * 0.64f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.16f, h * 0.86f), Offset(w * 0.84f, h * 0.86f), sw, StrokeCap.Round)
}
