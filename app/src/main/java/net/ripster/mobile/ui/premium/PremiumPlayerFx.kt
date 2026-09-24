package net.ripster.mobile.ui.premium

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import net.ripster.mobile.ui.theme.Motion

/**
 * Дорогие визуалы ОДНОГО плеера: стекло его органов управления и пружины.
 *
 * Файл появился из жалобы владельца: режим был вшит только в один из трёх
 * стилей плеера, и настройка обещала картинку, которой в двух других стилях не
 * было вовсе. Чтобы обещание и картинка не расходились НИ в одном стиле, у
 * стилей одна точка входа: [rememberPremiumPlayerFx] +
 * [Modifier.liquidGlassSource] + [Modifier.liquidGlass]. План читается здесь, а
 * не в каждом экране, — поэтому добавить четвёртый стиль, пропустив режим,
 * нельзя даже по невнимательности (сторож `PlayerPremiumWiringTest`).
 *
 * Чего здесь больше НЕТ: собственного фона плеера. Предыдущая сборка клала
 * живой меш или размытую обложку на весь экран и называла это «стеклом»;
 * вердикт владельца — «стекло это не фон, это не навес на весь экран, это стиль
 * самого плеера». Задник «Сейчас играет» при включённом режиме ровно тот же,
 * что и при выключенном, а стекло живёт в кнопках, чипах, сик-баре, мини-плеере
 * и нижней навигации.
 *
 * Размывать панелям больше «нечего специального»: под ними то, что стиль и так
 * рисует — обложка, её свечение, список треков. Поэтому [liquidGlassSource]
 * помечает НЕ новый слой, а существующий, который лежит под органами, и телефон
 * размывает именно его.
 */

/** Всё, что нужно экрану плеера от режима. Создаётся в композиции, дальше только читается. */
class PremiumPlayerFx internal constructor(
    val plan: PremiumPlan,
    /**
     * Слой-источник для панелей этого плеера: то, что стекло размывает за
     * собой. `null` — размытия телефону не положено (режим выкл, Android младше
     * 12, экономия заряда), и панели остаются точной заливкой темы.
     */
    val glass: HazeState?,
) {
    /** Насколько настоящим стеклом рисовать органы. */
    val tier: GlassTier
        get() = plan.glass

    /**
     * Рисовать ли органы стеклом.
     *
     * Нужна не только ради самой заливки: у стеклянных органов свет рисуется
     * самим стеклом (блик, кромка, внутренняя тень), и прежняя рамка темы тогда
     * лишняя; отступ же под стеклянную плашку, наоборот, нужен, а под
     * невидимой заглушкой он сдвинул бы базовый экран. То есть вызывающему
     * приходится раздваивать оформление, и спрашивает он об одном — о стекле.
     */
    val hasGlass: Boolean
        get() = tier != GlassTier.Plain

    /**
     * Пружина нажатия. Вне режима (и когда телефон запретил пружины) — ровно
     * тот дефолт `animateFloatAsState`, что стоял раньше: «дорогие» числа не
     * имеют права тихонько поменять базовый экран.
     *
     * Внутри режима — [Motion.pressScale]: с недобоем (dampingRatio 0.72), то
     * есть палец ужал стекло, и оно слегка выпрыгнуло обратно. Стекло, которое
     * сжимается пружинистее, читается физичнее, чем стекло на линейной анимации.
     */
    val pressSpec: AnimationSpec<Float> = if (plan.springMotion) Motion.pressScale else spring()

    /**
     * Числа стеклянной панели этого плеера, висящей над [behind].
     *
     * `null` — стекла нет, и вызывающий обязан остаться на своей обычной
     * заливке (см. `fallback` в [liquidGlass]). Когда «адаптивные цвета» сняты,
     * панель не подмешивает оттенок обложки: стекло остаётся нейтральным, но
     * стеклом.
     */
    fun surfaceFor(behind: Color, dark: Boolean): GlassSurface? =
        LiquidGlass.surfaceFor(tier = tier, backdrop = backdropFor(behind, dark), dark = dark)

    /**
     * Цвет, которым стекло этого плеера закрывает [behind]: тон поверх того, что
     * за ним. Нужен тем, кто лежит ПОСЛЕ стеклянной панели и держит обещание
     * контраста против конкретной поверхности (заливка перемотки). `null` —
     * стекла нет, и подложку вызывающий знает сам, как и до режима.
     */
    fun glassPaint(behind: Color): Color? {
        val dark = LiquidGlass.isDarkCanvas(behind)
        val surface = surfaceFor(behind, dark) ?: return null
        // Против того же задника, что стекло реально подмешало себе в тон:
        // мерить контраст об один цвет и рисовать по другому — значит проверить
        // одно, а показать другое.
        return LiquidGlass.glassPaint(surface, backdropFor(behind, dark))
    }

    /** Что стекло видит за собой: оттенок содержимого либо нейтраль, когда
     *  «адаптивные цвета» сняты. */
    private fun backdropFor(behind: Color, dark: Boolean): Color =
        if (plan.tintsFromContent) behind else neutralBackdrop(dark)

    private fun neutralBackdrop(dark: Boolean): Color = if (dark) Color.Black else Color.White
}

/**
 * План этого телефона + слой стекла для панелей этого плеера.
 *
 * Вызывается В НАЧАЛЕ экрана плеера, до раскладки: [PremiumPlayerFx] хранит
 * `HazeState`, который переживает смену трека (иначе панель мигала бы
 * прозрачностью).
 *
 * @param ambient источник размытия, который стиль РИСУЕТ НЕ сам: под
 *   Reference-плеером прозрачно, и за его панелями живёт глобальный ambilight
 *   AppShell. Тогда стеклу размывать его, и нового слоя не нужно. `null` —
 *   стиль перекрывает собой всё, и что под панелями, объявляет сам через
 *   [Modifier.liquidGlassSource].
 */
@Composable
fun rememberPremiumPlayerFx(ambient: HazeState? = null): PremiumPlayerFx {
    val plan = rememberPremiumPlan()
    // Один HazeState на экран: создаётся, только когда стекло вообще возможно.
    val haze = when {
        !plan.glass.hasBlur -> null
        else -> ambient ?: remember { HazeState() }
    }
    return remember(plan, haze) { PremiumPlayerFx(plan, haze) }
}

/**
 * Пометить СЛОЙ, который стеклянные панели этого плеера должны размывать: тот,
 * что лежит ПОД органами управления (обложка, её свечение, заливка стиля).
 * Визуально модификатор ничего не меняет — он только отдаёт Haze этот слой, —
 * поэтому фон остаётся фоном стиля. `fx` без стекла → модификатор пустой.
 *
 * Слой обязан быть СОСЕДОМ панелей, а не их родителем: иначе панель попадёт в
 * собственный размываемый слой и блюр закрутится в обратную связь.
 */
@Composable
fun Modifier.liquidGlassSource(fx: PremiumPlayerFx, canvas: Color): Modifier {
    val source = fx.glass
    return if (source == null) this else hazeBackdrop(source, canvas)
}
