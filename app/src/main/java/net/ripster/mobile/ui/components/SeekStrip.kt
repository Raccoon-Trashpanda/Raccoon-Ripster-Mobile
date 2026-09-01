package net.ripster.mobile.ui.components

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.theme.MinTouchTarget
import net.ripster.mobile.ui.theme.contrastRatio
import net.ripster.mobile.ui.theme.Radii
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.Weights
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * ============================================================================
 *  ПОЛОСА ПЕРЕМОТКИ — горизонтальная, под обложкой.
 * ============================================================================
 *
 * 29.08.2026: компонент раньше умел ВТОРУЮ ось — полосу вдоль края экрана под
 * большой палец (SeekOrientation.Vertical), с флик-переключением трека, hold
 * для временной громкости и свайпом-закрытием. Владелец решил убрать её из
 * объёма ПОКА — не отменить идею, а не тащить непроверенный второй режим
 * ввода в первую реальную сборку экрана. Код и вся физика вертикального
 * режима (флик-порог, hold-жест, раскладка подписи у ручки) не удалены
 * бесследно — тот же файл в истории git до этой правки. Возвращать —
 * реализацией заново по тем же принципам ниже, не копипастой: часть API
 * (edge, invertVerticalAxis, onHoldDelta) была специфична для края экрана и
 * могла не подойти без переосмысления под то, что покажет первый реальный
 * экран.
 *
 * ---------------------------------------------------------------------------
 * ЧТО ИМЕННО БЫЛО НЕ ТАК И ЧТО СДЕЛАНО (актуально для горизонтали)
 * ---------------------------------------------------------------------------
 *
 * (а) Ручка-кружок жила отдельной жизнью от полосы.
 *     Круг другой формы, другого радиуса, часто другого цвета, со своей
 *     анимацией — он читался как объект, лежащий НА полосе, а не как её часть,
 *     и при перетаскивании казалось, что двигаешь не шкалу, а фишку.
 *     РЕШЕНИЕ: ручки в покое НЕТ вообще. Позицию показывает край заливки — то,
 *     чем шкала и показывает позицию. При касании из этого края ВЫРАСТАЕТ
 *     лепесток: та же капсула, та же ось, толщина = толщина полосы плюс
 *     небольшой выступ, ширина и выступ — чистые функции одной величины
 *     раскрытия. У ручки физически нет собственного состояния, которым она
 *     могла бы жить отдельно.
 *
 * (б) Резкий скачок при касании.
 *     РЕШЕНИЕ: одна пружинообразная величина `expansion` (0..1) на всё —
 *     толщину, зазор, ручку, кегль подписей. Всё едет синхронно, потому что
 *     это буквально одно число. Плюс асимметричные длительности (ниже).
 *
 * (в) Заполненная часть спорила яркостью с обложкой.
 *     РЕШЕНИЕ в CoverTint.kt: светлота заливки ЗАДАЁТСЯ поверхностью темы
 *     (ровно 4.5:1), а не берётся из обложки. От обложки остаются тон и
 *     ограниченная хроматика. Полоса яркого альбома не ярче полосы тусклого —
 *     яркость перестала принадлежать картинке, поэтому спорить больше нечем.
 *
 * ---------------------------------------------------------------------------
 * ЧЕСТНОСТЬ (правило проекта №1)
 * ---------------------------------------------------------------------------
 * Цвет обложки — идентичность. Он окрашивает ровно две вещи: заливку и ручку,
 * то есть ПОЗИЦИЮ. Он не окрашивает состояние. Состояние кодируется формой:
 *
 *   Playing    сплошная ручка, скруглённые торцы
 *   Paused     ручка полая (кольцо) — «позиция есть, движения нет»
 *   Buffering  за краем буфера бежит пунктир 2/5 — тот же знак «данных ещё
 *              нет», что у пунктирного бейджа и у кружков очереди
 *   Offline    остаток за буфером пунктирный и НЕПОДВИЖНЫЙ — «больше не придёт»
 *   Error      прямые торцы вместо скруглённых, ручка-прямоугольник
 *
 * Обесцветьте макет — все пять различимы. Ни одно из них не отличается только
 * цветом, и ни одно не отбирает у обложки её тон: даже при ошибке заливка
 * остаётся окрашенной, потому что альбом не перестал быть этим альбомом.
 *
 * Подписи времени НЕ красятся обложкой, хотя контраст это позволил бы: подпись
 * несёт состояние («играет столько-то»), а состояние цветом кодировать нельзя.
 *
 * ---------------------------------------------------------------------------
 * ГРАНИЦА ГАРАНТИИ КОНТРАСТА
 * ---------------------------------------------------------------------------
 * Гарантия ≥3:1 даётся против [surfaceBehind]. Если положить полосу прямо на
 * обложку, гарантии нет ни у кого и быть не может: под полосой тогда лежат
 * произвольные пиксели. Класть — только на поверхность темы либо на плашку
 * поверх обложки. Это ограничение компонента, а не недоделка.
 */

/** Состояние воспроизведения. Кодируется ТОЛЬКО формой (см. шапку файла). */
enum class SeekPlaybackState { Playing, Paused, Buffering, Offline, Error }

/**
 * ВЫСОТЫ. Числа выведены, а не подобраны.
 *
 * Покой — 6 dp.
 *   Нижняя граница: полоса обязана нести ТРИ уровня яркости (остаток, буфер,
 *   сыгранное) и торец-капсулу радиусом h/2. При h < 4 dp капсульный торец на
 *   экране с плотностью 2 вырождается в один-два пикселя и читается как обрыв,
 *   а не как конец шкалы; граница буфера на такой толщине теряется на глаз.
 *   Верхняя граница: подпись caption имеет высоту прописной ≈ 9–10 dp. Полоса
 *   толще этого начинает читаться как КОНТЕЙНЕР — плашка, внутри которой что-то
 *   лежит, — а не как шкала. Отсюда потолок ≈ 8 dp. 6 dp — середина коридора
 *   [4, 8] и вдвое больше типичных «шляпных» 2–3 dp, из-за которых полоса
 *   пропадала на фоне и не имела места под буферный слой.
 *
 * Касание — 14 dp.
 *   Ведущий палец закрывает пятно ≈ 9–11 мм. Обратная связь при перемотке идёт
 *   не от полосы целиком, а от КРАЯ заливки и от ручки, которые видны сбоку от
 *   пятна. Чтобы край устойчиво отслеживался в движении, он должен занимать
 *   ≈ 0.5° поля зрения: на дистанции 30 см это ≈ 2.6 мм ≈ 10 dp. Прибавляем
 *   2 dp запаса сверху и снизу под выступ ручки — получаем 14.
 *   Приятный побочный эффект: при h = 14 радиус капсулы h/2 = 7 dp — это ровно
 *   Radii.RCtl, то есть в раскрытом состоянии полоса скруглена штатным радиусом
 *   управляющего элемента, а не собственным. Новых радиусов не заведено:
 *   в покое берётся тот же RCtl, обрезанный геометрией до h/2 = 3 dp.
 *
 * Толщина НЕ зависит от плотности. Плотность — про «как показано»: кегли и
 * зазоры. Эти два числа — про палец и про порог различения края, то есть про
 * физику, ровно как MinTouchTarget.
 */
private val RestThickness: Dp = 8.dp
private val ActiveThickness: Dp = 18.dp

/** Ширина ручки: тонкая в покое, шире при взятии пальцем (владелец: «выступает
 *  из линии на ~2мм сверху и снизу, тонкая, при касании увеличивается»). */
private val KnobWidthRest: Dp = 3.dp
private val KnobWidthActive: Dp = 10.dp

/** Насколько ручка выступает за толщину полосы с каждой стороны: ~2dp в покое,
 *  больше при касании. */
private val KnobOvershootRest: Dp = 2.dp
private val KnobOvershootActive: Dp = 4.dp

/** Зазор между полосой и ручкой при полном раскрытии. В покое 0 — шва нет. */
private val KnobGap: Dp = 4.dp

/** Метка реальной позиции во время перемотки. Тонкая и всегда одной ширины. */
private val GhostWidth: Dp = 2.dp

/** Обводка «намеченного» участка между «где играет» и «куда отпустишь». */
private val PendingStroke: Dp = 1.5.dp

/**
 * АСИММЕТРИЯ РАСКРЫТИЯ. Раскрытие вдвое быстрее сжатия — и это не вкус.
 *
 * Раскрытие — подтверждение касания. Отклик на прикосновение, приходящий позже
 * ≈ 100–150 мс, перестаёт восприниматься как вызванный этим касанием: человек
 * успевает начать сомневаться, попал ли. 120 мс — верхняя граница «мгновенно»
 * с запасом на кадр.
 *
 * Сжатие не подтверждает ничего. Палец уже поднят, намерение исполнено, взгляд
 * ушёл на другое. Быстрое сжатие здесь — это движение на периферии зрения,
 * которое ДЁРГАЕТ взгляд обратно к элементу, с которым уже закончили. Медленное
 * (260 мс) остаётся ниже порога, на котором периферия требует внимания.
 * Ровно поэтому вход и выход не могут иметь одну длительность: они отвечают на
 * разные вопросы.
 *
 * Easing тоже разный. Раскрытие — сильное замедление в конце (быстрый старт,
 * мягкая посадка): движение уже произошло к тому моменту, как глаз его поймал.
 * Сжатие — плавное с обоих концов, без рывка на старте, чтобы не привлекать.
 */
private const val ExpandMs = 120
private const val CollapseMs = 260
private val ExpandEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val CollapseEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

/** Шаг accessibility-действий «вперёд/назад». */
private const val AccessibilityStepMs = 15_000L

@Composable
fun SeekStrip(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Сколько загружено. По умолчанию равно позиции — то есть буфера не показываем. */
    bufferedMs: Long = positionMs,
    state: SeekPlaybackState = SeekPlaybackState.Playing,
    /**
     * Цвет заливки. Ожидается уже ЗАЖАТЫЙ результат clampCoverTint /
     * rememberClampedCoverTint. Сырое среднее обложки сюда передавать нельзя:
     * компонент не может проверить, что оно читаемо, — он не знает, что под ним.
     */
    tint: Color = RipsterTheme.colors.accent_text,
    /** Поверхность, ПРОТИВ которой считался контраст заливки. См. шапку файла. */
    surfaceBehind: Color = RipsterTheme.colors.surface_canvas,
    /** Сообщается непрерывно во время ведения — для превью обложки/волны, например. */
    onScrubChange: (Long) -> Unit = {},
    /** Подписи. Латиница по умолчанию, локализация — в ресурсах вызывающего. */
    contentDescription: String = "Seek position",
    stateLabelPlaying: String = "playing",
    stateLabelPaused: String = "paused",
    stateLabelBuffering: String = "buffering",
    stateLabelOffline: String = "offline",
    stateLabelError: String = "playback error",
    forwardActionLabel: String = "Forward 15 seconds",
    backwardActionLabel: String = "Back 15 seconds",
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type
    val density = LocalDensity.current

    val enabled = durationMs > 0L

    /*
     * УВАЖЕНИЕ К ОТКЛЮЧЁННЫМ АНИМАЦИЯМ. Читаем системный масштаб длительности:
     * при нуле (настройки разработчика, а также режимы экономии и часть
     * настроек доступности) все переходы становятся мгновенными, а бегущий
     * пунктир буферизации замирает в статичный. Замирает именно ПУНКТИР, а не
     * исчезает: он несёт состояние, и убрать его значило бы отобрать у человека
     * информацию под предлогом заботы.
     */
    val motionScale = rememberMotionScale()
    val animated = motionScale > 0f

    // Реальная доля — то, ГДЕ ИГРАЕТ. Не путать с целевой.
    val realFraction = if (enabled) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (enabled) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    // -1 означает «пальца нет». Отдельное значение, а не Boolean + Float:
    // так невозможно оказаться в состоянии «тянем, но неизвестно куда».
    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    var touched by remember { mutableStateOf(false) }
    val scrubbing = scrubFraction >= 0f

    val headFraction = if (scrubbing) scrubFraction else realFraction

    // ОДНА величина на всё раскрытие. См. комментарий про (б) в шапке.
    val expansion by animateFloatAsState(
        targetValue = if (touched) 1f else 0f,
        animationSpec = if (touched) {
            tween((ExpandMs * motionScale).toInt(), easing = ExpandEasing)
        } else {
            tween((CollapseMs * motionScale).toInt(), easing = CollapseEasing)
        },
        label = "seek-expansion",
    )

    val dashPhase = rememberDashPhase(
        running = animated && state == SeekPlaybackState.Buffering,
    )

    // Геометрия в пикселях. Считается один раз на композицию, а не в draw-фазе.
    val restPx = with(density) { RestThickness.toPx() }
    val activePx = with(density) { ActiveThickness.toPx() }
    val knobWRestPx = with(density) { KnobWidthRest.toPx() }
    val knobWActivePx = with(density) { KnobWidthActive.toPx() }
    val knobOvRestPx = with(density) { KnobOvershootRest.toPx() }
    val knobOvActivePx = with(density) { KnobOvershootActive.toPx() }
    val knobGapPx = with(density) { KnobGap.toPx() }
    val ghostPx = with(density) { GhostWidth.toPx() }
    val pendingPx = with(density) { PendingStroke.toPx() }
    val maxCornerPx = with(density) { Radii.RCtl.toPx() }

    val thickness = restPx + (activePx - restPx) * expansion
    // Ручка ЕСТЬ всегда: тонкая (3dp) и выступающая на ~2dp сверху/снизу в покое,
    // растёт до 10dp / +4dp при взятии пальцем, плавно возвращается назад.
    val knobHalf = (knobWRestPx + (knobWActivePx - knobWRestPx) * expansion) / 2f
    val knobThicknessHalf =
        thickness / 2f + knobOvRestPx + (knobOvActivePx - knobOvRestPx) * expansion
    val gap = knobGapPx * expansion
    val squareCaps = state == SeekPlaybackState.Error
    val corner = if (squareCaps) 0f else min(thickness / 2f, maxCornerPx)

    /*
     * ТРИ УРОВНЯ ЯРКОСТИ, а не три цвета. Буфер — именно уровень: он не имеет
     * собственного смысла помимо «между остатком и сыгранным».
     *   остаток  border_default  — канавка, самый тусклый
     *   буфер    text_disabled   — средний, приглушённый, неинтерактивный
     *   играно   tint            — зажат до 4.5:1, самый яркий
     * Порядок монотонен во всех шести палитрах, проверяется тем же
     * tools/check_contrast.py.
     */
    val trackColor = colors.border_default
    val bufferColor = colors.text_disabled
    val ghostColor = colors.text_primary

    /*
     * ПОСЛЕДНЯЯ ПРОВЕРКА КОНТРАСТА — здесь, а не только в CoverTint.
     *
     * Зажим считается против поверхности, которую ему назвали. Вызывающий может
     * ошибиться темой, поверхностью или передать сырое среднее обложки, и тогда
     * заливка окажется нечитаемой ровно на том экране, куда никто не посмотрел.
     * Компонент, которому поверхность известна, обязан это поймать сам: ниже
     * 3:1 обложка молча уступает место нейтральной роли темы. Идентичность —
     * приятное свойство, читаемость — обязательное, и при конфликте выигрывает
     * обязательное.
     */
    val effectiveTint = remember(tint, surfaceBehind, colors) {
        if (contrastRatio(tint, surfaceBehind) >= 3f) tint else colors.text_primary
    }

    // Свежие значения для жестового блока: pointerInput не должен
    // перезапускаться на каждый тик позиции.
    val currentDuration by rememberUpdatedState(durationMs)
    val currentSeek by rememberUpdatedState(onSeek)
    val currentScrubChange by rememberUpdatedState(onScrubChange)

    val gestures = Modifier.pointerInput(enabled) {
        if (!enabled) return@pointerInput

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val lengthPx = size.width.toFloat()
            if (lengthPx <= 0f) return@awaitEachGesture

            fun fractionAt(o: Offset): Float = (o.x / lengthPx).coerceIn(0f, 1f)

            touched = true

            // Абсолютная точка входа: видна вся шкала, палец сразу указывает
            // на место — тап или начало перемотки трактуются одинаково.
            val startFraction = fractionAt(down.position)
            scrubFraction = startFraction
            currentScrubChange((startFraction * currentDuration).roundToLong())

            var last = down.position
            var moved = false

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                last = change.position
                val f = fractionAt(last)
                if (abs(f - startFraction) > 0.002f) moved = true
                scrubFraction = f
                currentScrubChange((f * currentDuration).roundToLong())
                change.consume()
                if (!change.pressed) break
            }

            currentSeek((fractionAt(last) * currentDuration).roundToLong())
            scrubFraction = -1f
            touched = false
        }
    }

    // Значение снимается ДО semantics-блока: внутри блока простое имя
    // contentDescription разрешилось бы в свойство SemanticsPropertyReceiver,
    // у которого нет геттера, а не в параметр функции.
    val a11yDescription = contentDescription
    val semanticsModifier = Modifier.semantics {
        this.contentDescription = a11yDescription
        this.stateDescription = when (state) {
            SeekPlaybackState.Playing -> stateLabelPlaying
            SeekPlaybackState.Paused -> stateLabelPaused
            SeekPlaybackState.Buffering -> stateLabelBuffering
            SeekPlaybackState.Offline -> stateLabelOffline
            SeekPlaybackState.Error -> stateLabelError
        } + ", " + formatClock(positionMs) + " of " + formatClock(durationMs)
        // progressBarRangeInfo + setProgress — то, что TalkBack превращает в
        // штатный ползунок с жестами вверх/вниз. Собственных «кнопок» перемотки
        // для скринридера здесь не заводится: свой велосипед в этом месте
        // означал бы, что человек с TalkBack учит наш интерфейс отдельно.
        this.progressBarRangeInfo = ProgressBarRangeInfo(realFraction, 0f..1f)
        if (enabled) {
            setProgress { target ->
                onSeek((target.coerceIn(0f, 1f) * durationMs).roundToLong())
                true
            }
            this.customActions = listOf(
                CustomAccessibilityAction(forwardActionLabel) {
                    onSeek(min(durationMs, positionMs + AccessibilityStepMs)); true
                },
                CustomAccessibilityAction(backwardActionLabel) {
                    onSeek(max(0L, positionMs - AccessibilityStepMs)); true
                },
            )
        }
    }

    val draw: DrawScope.() -> Unit = {
        drawSeekStrip(
            length = size.width,
            crossCenter = size.height / 2f,
            thickness = thickness,
            knobHalf = knobHalf,
            knobThicknessHalf = knobThicknessHalf,
            gap = gap,
            corner = corner,
            squareCaps = squareCaps,
            headFraction = headFraction,
            realFraction = realFraction,
            bufferedFraction = bufferedFraction,
            scrubbing = scrubbing,
            hollowKnob = state == SeekPlaybackState.Paused,
            dashPhase = dashPhase,
            dashAfterBuffer = state == SeekPlaybackState.Buffering,
            dashRemainder = state == SeekPlaybackState.Offline,
            trackColor = trackColor,
            bufferColor = bufferColor,
            fillColor = effectiveTint,
            ghostColor = ghostColor,
            pendingStroke = pendingPx,
            ghostWidth = ghostPx,
        )
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Видимая толщина 6/14 dp и мишень 48 dp — РАЗНЫЕ величины.
                // Мишень константна: она про палец, а не про оформление.
                .height(MinTouchTarget)
                .then(gestures)
                .then(semanticsModifier),
        ) {
            Canvas(modifier = Modifier.fillMaxSize(), onDraw = draw)
        }
        Spacer(Modifier.height(spacing.xs))
        HorizontalTimeRow(
            positionMs = positionMs,
            durationMs = durationMs,
            scrubTargetMs = if (scrubbing) (scrubFraction * durationMs).roundToLong() else null,
            captionColor = colors.text_secondary,
            targetColor = colors.text_primary,
            deltaColor = colors.text_tertiary,
        )
    }
}

/**
 * Строка времени под полосой.
 *
 * ЧЕСТНОСТЬ ПЕРЕМОТКИ. Пока палец ведёт, слева продолжает идти РЕАЛЬНОЕ время —
 * оно тикает, потому что музыка не остановилась. По центру появляется целевое
 * время и знаковая дельта. Это две разные величины, и они показаны как две
 * разные величины. Подмена левой подписи целевым временем (так делают многие)
 * означает, что во время ведения интерфейс говорит неправду о том, что звучит,
 * и после отпускания нечем проверить, попал ли ты.
 *
 * Центральный слот занимает место ВСЕГДА. Появление подписи не должно двигать
 * соседей: движение соседей читается как их собственное изменение.
 */
@Composable
private fun HorizontalTimeRow(
    positionMs: Long,
    durationMs: Long,
    scrubTargetMs: Long?,
    captionColor: Color,
    targetColor: Color,
    deltaColor: Color,
) {
    val type = RipsterTheme.type
    val density = LocalDensity.current
    val rowHeight = with(density) { (type.caption.toPx() * 1.6f).toDp() }

    Row(
        modifier = Modifier.fillMaxWidth().height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = formatClock(positionMs),
            style = TextStyle(color = captionColor, fontSize = type.caption, fontWeight = Weights.Quiet),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (scrubTargetMs != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = formatClock(scrubTargetMs),
                        style = TextStyle(
                            color = targetColor,
                            fontSize = type.caption,
                            fontWeight = Weights.Standard,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        text = formatDelta(scrubTargetMs - positionMs),
                        style = TextStyle(color = deltaColor, fontSize = type.caption, fontWeight = Weights.Body),
                    )
                }
            }
        }
        BasicText(
            text = formatClock(durationMs),
            style = TextStyle(color = captionColor, fontSize = type.caption, fontWeight = Weights.Quiet),
        )
    }
}

/**
 * Отрисовка. Порядок слоёв снизу вверх: остаток → буфер → сыгранное →
 * намеченное → метка реальной позиции → ручка. Ручка последняя, потому что
 * она обязана перекрывать всё: она — точка приложения пальца.
 */
private fun DrawScope.drawSeekStrip(
    length: Float,
    crossCenter: Float,
    thickness: Float,
    knobHalf: Float,
    knobThicknessHalf: Float,
    gap: Float,
    corner: Float,
    squareCaps: Boolean,
    headFraction: Float,
    realFraction: Float,
    bufferedFraction: Float,
    scrubbing: Boolean,
    hollowKnob: Boolean,
    dashPhase: Float,
    dashAfterBuffer: Boolean,
    dashRemainder: Boolean,
    trackColor: Color,
    bufferColor: Color,
    fillColor: Color,
    ghostColor: Color,
    pendingStroke: Float,
    ghostWidth: Float,
) {
    if (length <= 0f) return

    val half = thickness / 2f
    val headM = headFraction * length
    val realM = realFraction * length
    val bufM = bufferedFraction * length

    fun rectOf(m0: Float, m1: Float, crossHalf: Float): Rect {
        val lo = min(m0, m1)
        val hi = max(m0, m1)
        return Rect(lo, crossCenter - crossHalf, hi, crossCenter + crossHalf)
    }

    fun seg(color: Color, m0: Float, m1: Float, crossHalf: Float, r: Float) {
        if (m1 - m0 <= 0.25f) return
        val rect = rectOf(m0, m1, crossHalf)
        drawRoundRect(color, rect.topLeft, rect.size, CornerRadius(r, r))
    }

    fun point(m: Float): Offset = Offset(m, crossCenter)

    // Вырез вокруг ручки — только при раскрытии (gap>0). В покое тонкая ручка
    // просто лежит на линии без шва.
    val hs = if (gap > 0.5f) knobHalf + gap else 0f
    val cutLo = (headM - hs).coerceIn(0f, length)
    val cutHi = (headM + hs).coerceIn(0f, length)

    // 1. Остаток — самый тусклый уровень.
    seg(trackColor, cutHi, length, half, corner)

    // 2. Буфер — средний уровень. Только та его часть, что за головкой.
    if (bufM > cutHi) seg(bufferColor, cutHi, bufM, half, corner)

    // 3. Сыгранное — заливка обложкой. До МЕНЬШЕГО из «где играет» и «куда
    //    отпустишь»: это участок, про который правда в обоих смыслах.
    val solidEnd = min(min(realM, headM), cutLo)
    seg(fillColor, 0f, solidEnd, half, corner)

    // 4. Намеченное — участок между реальной позицией и целью. Не заливка, а
    //    обводка: «этого ещё не произошло». Разница формы, а не оттенка, поэтому
    //    видна и в оттенках серого, и на любой обложке.
    if (scrubbing) {
        // Намеченный участок лежит по ту сторону ручки, куда едет палец:
        // вперёд — справа от реальной позиции, назад — слева. Обе стороны
        // считаются отдельно, иначе перемотка НАЗАД оставляла бы уже сыгранный
        // кусок нарисованным как «сыгранный», хотя после отпускания он таким
        // быть перестанет.
        val forward = headM >= realM
        val pendLo = if (forward) realM else cutHi
        val pendHi = if (forward) cutLo else realM
        if (pendHi - pendLo > 1f) {
            val rect = rectOf(pendLo, pendHi, half - pendingStroke / 2f)
            drawRoundRect(
                color = fillColor,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = pendingStroke),
            )
        }
        // 5. Метка «где играет на самом деле». Нейтральный цвет темы, не обложка:
        //    это утверждение о состоянии, а состояние не красится обложкой.
        if (abs(realM - headM) > 1f) {
            seg(ghostColor, realM - ghostWidth / 2f, realM + ghostWidth / 2f, knobThicknessHalf, 0f)
        }
    }

    // 6. Пунктир состояний. Тот же узор 2/5, что у пунктирного бейджа
    //    «не измеряли» и у кружков очереди: один знак — один смысл на весь
    //    продукт.
    if (dashAfterBuffer || dashRemainder) {
        val from = max(cutHi, bufM)
        if (length - from > 2f) {
            val effect = PathEffect.dashPathEffect(
                floatArrayOf(2.dp.toPx(), 5.dp.toPx()),
                if (dashAfterBuffer) dashPhase else 0f,
            )
            drawLine(
                color = bufferColor,
                start = point(from),
                end = point(length),
                strokeWidth = max(thickness * 0.5f, 1.dp.toPx()),
                cap = StrokeCap.Butt,
                pathEffect = effect,
            )
        }
    }

    // 7. Ручка. Её ширина, выступ и зазор — функции одной величины раскрытия,
    //    поэтому она не может «отстать» от полосы или зажить своей жизнью.
    if (knobHalf > 0.25f) {
        val r = if (squareCaps) 0f else min(knobHalf, knobThicknessHalf)
        val rect = rectOf(headM - knobHalf, headM + knobHalf, knobThicknessHalf)
        if (hollowKnob) {
            // Пауза: кольцо вместо тела. Позиция есть, движения нет — читается
            // без цвета.
            drawRoundRect(
                color = fillColor,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = max(pendingStroke, 1.dp.toPx())),
            )
        } else {
            drawRoundRect(fillColor, rect.topLeft, rect.size, CornerRadius(r, r))
        }
    }
}

/**
 * Системный масштаб длительности анимаций. Ноль — «анимации отключены».
 *
 * Читается один раз: значение меняется через системные настройки, то есть с
 * пересозданием активити. Подписка на ContentObserver здесь была бы кодом,
 * который никогда не срабатывает.
 */
@Composable
private fun rememberMotionScale(): Float {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f).coerceIn(0f, 4f)
    }
}

/** Фаза бегущего пунктира. При остановленной анимации возвращает 0 и не крутится. */
@Composable
private fun rememberDashPhase(running: Boolean): Float {
    if (!running) return 0f
    val transition = rememberInfiniteTransition(label = "seek-buffering")
    val density = LocalDensity.current
    val period = with(density) { 7.dp.toPx() } // 2 + 5, один период узора
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = -period,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "seek-buffering-phase",
    )
    return phase
}

/**
 * m:ss, а при длительности от часа — h:mm:ss.
 *
 * Формат выбирается по ВЕЛИЧИНЕ, а не по треку: «0:00:47» на сорокасекундном
 * треке заставляет читать три группы там, где значимы две. Только цифры и
 * двоеточие — локализации здесь нечего переводить.
 */
fun formatClock(ms: Long): String {
    val total = (max(0L, ms) + 500L) / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) "$h:${pad2(m)}:${pad2(s)}" else "$m:${pad2(s)}"
}

/** Знаковая дельта перемотки. Знак ставится всегда, включая ноль-со-знаком «+0:00». */
fun formatDelta(deltaMs: Long): String {
    val sign = if (deltaMs < 0L) "-" else "+"
    return sign + formatClock(abs(deltaMs))
}

private fun pad2(v: Long): String = if (v < 10L) "0$v" else v.toString()
