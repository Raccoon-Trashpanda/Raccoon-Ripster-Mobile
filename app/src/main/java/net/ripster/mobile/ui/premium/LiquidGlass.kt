package net.ripster.mobile.ui.premium

import androidx.compose.ui.graphics.Color
import net.ripster.mobile.ui.theme.contrastRatio
import net.ripster.mobile.ui.theme.relativeLuminance

/**
 * Стекло как СПОСОБ РИСОВАНИЯ САМОГО ОРГАНА УПРАВЛЕНИЯ, а не фона экрана.
 *
 * Претензия владельца после сборки на эмуляторе была по делу: режим рисовал
 * мутную серую пелену на весь экран и называл это «стеклом». Стекло — это
 * кнопка «плей», чип «Трек-лист», пилюля качества, сик-бар, мини-плеер и
 * нижняя навигация: у каждого из них есть толщина, блик по верхней кромке,
 * светлая окантовка, внутренняя тень снизу, мягкая тень под ним и преломление
 * на краях; всё это берёт тон из того, что ЧЕРЕЗ него видно, и никогда не
 * бывает непрозрачной серой краской.
 *
 * Поэтому здесь только чистые числа: какой слой стекла положен телефону, какими
 * параметрами оно рисуется и читается ли иконка поверх этого стекла. Композиция
 * (`LiquidGlassSurface.kt`) берёт посчитанное и ничего сама не решает — ровно
 * как в `PremiumVisuals.resolve` и `PremiumMotion`, где выбор тоже вынесен из
 * `Context` и Compose, потому что на приборе его не прокликать.
 */

/**
 * Насколько настоящим стеклом можно нарисовать этот орган.
 *
 * Границы — из API, не «примерно с одиннадцатой»: размытие под панелью требует
 * `RenderEffect` (Android 12, API 31), преломление на кромках — `RuntimeShader`
 * (Android 13, API 33).
 */
enum class GlassTier {
    /** Стекла нет: орган остаётся такой заливкой темы, какой был до режима. */
    Plain,

    /** Полупрозрачный тон + светлая кромка + внутренняя тень. Любой Android. */
    Tinted,

    /** Плюс настоящее размытие того, что видно под органом. С API 31. */
    Blurred,

    /** Плюс AGSL-линза: то, что за органом, преломляется на его кромках, а
     * верхняя грань собирается светом. С API 33.
     */
    Refractive,
}

/** Есть ли у уровня настоящее размытие того, что под органом. */
val GlassTier.hasBlur: Boolean
    get() = this == GlassTier.Blurred || this == GlassTier.Refractive

/**
 * Числа одной стеклянной поверхности. `null` там, где слой телефону не положен,
 * — рисующий код обязан пропустить его, а не нарисовать с нулём.
 *
 * @param tint тон стекла: почти прозрачный, с оттенком того, что за ним.
 * @param highlight альфа зеркального блика по верхней кромке.
 * @param highlightAt где блик кончается (доля высоты органа).
 * @param rim альфа светлой окантовки по всему периметру.
 * @param innerShadow альфа внутренней тени у нижней кромки — она и даёт
 *   ощущение, что у стекла есть толщина.
 * @param innerShadowAt где внутренняя тень кончается (доля высоты).
 * @param lens сила преломления на кромках, 0 — линзы нет.
 * @param elevationDp насколько орган оторван от подложки (мягкая тень под ним).
 */
data class GlassSurface(
    val tint: Color,
    val highlight: Float,
    val highlightAt: Float,
    val rim: Float,
    val innerShadow: Float,
    val innerShadowAt: Float,
    val lens: Float,
    val elevationDp: Float,
)

object LiquidGlass {

    /** AGSL (`RuntimeShader`), то есть линза и преломление, — с Android 13. */
    const val MIN_SDK_REFRACTIVE: Int = 33

    /** `RenderEffect`, то есть размытие под органом, — с Android 12. */
    const val MIN_SDK_BLURRED: Int = 31

    /**
     * Пол под читаемостью иконки и текста на стекле. 4.5 — WCAG AA для обычного
     * кегля; на стекле он ещё и обязателен, потому что тон под кнопкой
     * меняющийся и без пола иконка то исчезает, то выплывает.
     */
    const val MIN_ICON_CONTRAST: Float = 4.5f

    /**
     * Сколько стекла вообще положено телефону.
     *
     * Экономия заряда откатывает стекло на дешёвый слой: размытие и шейдер
     * стоят GPU-прохода каждый кадр, а под стеклом в этом режиме остаётся ровно
     * та же форма, те же кромка и блик — просто без глубины резкости.
     */
    fun tierFor(enabled: Boolean, sdkInt: Int, batterySaver: Boolean): GlassTier = when {
        !enabled -> GlassTier.Plain
        batterySaver -> GlassTier.Tinted
        sdkInt >= MIN_SDK_REFRACTIVE -> GlassTier.Refractive
        sdkInt >= MIN_SDK_BLURRED -> GlassTier.Blurred
        else -> GlassTier.Tinted
    }

    /** Потолок альфы тонировки. Выше — стекло перестаёт быть стеклом и становится плашкой. */
    const val MAX_TINT_ALPHA: Float = 0.20f

    /** Доля тона задника, которая подмешивается в тон стекла: «берёт цвет из-под себя». */
    const val TINT_CONTENT_PICKUP: Float = 0.45f

    /**
     * Тон стекла поверх [backdrop].
     *
     * Стекло не красят серым: оно поднимает светлоту и на четверть перенимает
     * оттенок того, что за ним, поэтому на тёплой обложке кромка тёплая, а на
     * холодной — холодная. Никогда не непрозрачный: [MAX_TINT_ALPHA].
     */
    fun tintFor(backdrop: Color, dark: Boolean, alpha: Float = MAX_TINT_ALPHA): Color {
        val a = alpha.coerceIn(0f, MAX_TINT_ALPHA)
        // Базовая краска зеркала: белый сверху в тёмной теме и почти чёрный в
        // светлой — иначе на светлом холсте стекло не читается как объём.
        val base = if (dark) Color.White else Color(0xFF0B0B0F)
        val mixed = Color(
            red = base.red + (backdrop.red - base.red) * TINT_CONTENT_PICKUP,
            green = base.green + (backdrop.green - base.green) * TINT_CONTENT_PICKUP,
            blue = base.blue + (backdrop.blue - base.blue) * TINT_CONTENT_PICKUP,
            alpha = a,
        )
        return mixed
    }

    /** Параметры поверхности по уровню. `Plain` — surfaces нет, рисующий пропустит слой. */
    fun surfaceFor(tier: GlassTier, backdrop: Color, dark: Boolean): GlassSurface? = when (tier) {
        GlassTier.Plain -> null
        GlassTier.Tinted -> GlassSurface(
            tint = tintFor(backdrop, dark, alpha = 0.16f),
            highlight = 0.20f,
            highlightAt = 0.42f,
            rim = 0.22f,
            innerShadow = 0.10f,
            innerShadowAt = 0.34f,
            lens = 0f,
            elevationDp = 2f,
        )
        GlassTier.Blurred -> GlassSurface(
            tint = tintFor(backdrop, dark, alpha = 0.10f),
            highlight = 0.30f,
            highlightAt = 0.46f,
            rim = 0.34f,
            innerShadow = 0.16f,
            innerShadowAt = 0.38f,
            lens = 0f,
            elevationDp = 6f,
        )
        GlassTier.Refractive -> GlassSurface(
            // Линза сама собирает свет на кромках — краску под ней держим
            // прозрачнее всего, иначе «стекло» снова станет серой пеленой.
            tint = tintFor(backdrop, dark, alpha = 0.07f),
            highlight = 0.42f,
            highlightAt = 0.5f,
            rim = 0.5f,
            innerShadow = 0.22f,
            innerShadowAt = 0.42f,
            lens = 0.055f,
            elevationDp = 10f,
        )
    }

    /**
     * Цвет поверх стекла: [fill] с его альфой, налитый на [backdrop]. Нужен,
     * чтобы проверять читаемость не «по цвету заливки из макета», а по тому,
     * что реально попадает на глаз сквозь полупрозрачное стекло.
     */
    fun composited(fill: Color, backdrop: Color): Color {
        val a = fill.alpha
        return Color(
            red = fill.red * a + backdrop.red * (1f - a),
            green = fill.green * a + backdrop.green * (1f - a),
            blue = fill.blue * a + backdrop.blue * (1f - a),
            alpha = (a + backdrop.alpha * (1f - a)).coerceIn(0f, 1f),
        )
    }

    /**
     * Доля пика блика, которая приходится на глиф. Блик — полоса у верхней
     * кромки, а не заливка через всю плашку: проверять читаемость по её самой
     * светлой линии означало бы forbid белый значок на любом приличном стекле,
     * а считать по среднему по органу — значит разрешить нечитаемую кромку.
     */
    const val HIGHLIGHT_FIELD_SHARE: Float = 0.5f

    /**
     * Худший по контрасту участок поверхности: то, что под органом, плюс блик у
     * верхней кромки (он осветляет) и внутренняя тень у нижней (он затемняет).
     * Иконка обязана читаться на обоих, а не на среднем.
     */
    fun surfacePoints(surface: GlassSurface, backdrop: Color): List<Color> {
        val tinted = composited(surface.tint, backdrop)
        return listOf(
            backdrop,
            tinted,
            composited(Color.White.copy(alpha = surface.highlight * HIGHLIGHT_FIELD_SHARE), tinted),
            composited(Color.Black.copy(alpha = surface.innerShadow), tinted),
        )
    }

    /** Наихудший контраст [icon] по всем участкам поверхности. */
    fun worstIconContrast(icon: Color, surface: GlassSurface, backdrop: Color): Float =
        surfacePoints(surface, backdrop).minOf { contrastRatio(icon, it) }

    /**
     * Цвет иконки/текста поверх стекла: свой, если он выдерживает
     * [MIN_ICON_CONTRAST] на худшем участке, иначе — тот из пары
     * [light]/[dark], что контрастнее.
     *
     * Выбор по тому же признаку, по которому Apple кладёт на «жидкое стекло» то
     * белый, то чёрный глиф: решение принимает светлота самого стекла, а не
     * тема приложения.
     */
    fun readableIcon(icon: Color, surface: GlassSurface, backdrop: Color, light: Color, dark: Color): Color {
        if (worstIconContrast(icon, surface, backdrop) >= MIN_ICON_CONTRAST) return icon
        val points = surfacePoints(surface, backdrop)
        val best = listOf(light, dark).maxByOrNull { candidate -> points.minOf { contrastRatio(candidate, it) } }
        // Пустой список теоретически невозможен, но возвращать «ничего» в
        // функции про читаемость было бы странно: отдаём исходный цвет.
        return best ?: icon
    }

    /**
     * Светлота стекла — по ней решают, какую краску класть на орган, когда
     * стекла нет (ветка [GlassTier.Plain]), и подбирают кромку.
     */
    fun glassLuminance(surface: GlassSurface, backdrop: Color): Float =
        relativeLuminance(composited(surface.tint, backdrop))

    /**
     * Цвет, который глаз видит ЗА стеклом, — тон стекла поверх подложки.
     *
     * Нужен тем, кто лежит ПОСЛЕ стеклянной панели и обещает контраст против
     * конкретной поверхности (заливка перемотки, подпись): против чего мерить,
     * если под ними теперь стекло? Вот против этого цвета и меряют.
     */
    fun glassPaint(surface: GlassSurface, backdrop: Color): Color =
        composited(surface.tint, backdrop)

    /**
     * Тёмный ли холст под органом.
     *
     * Стекло решает по светлоте того, что НАХОДИТСЯ ЗА НИМ, а не по имени темы:
     * белая базовая краска нужна и в светлой теме, и в тёмной теме поверх
     * засвеченной обложки. Держать перечисление тем «какая из них светлая» —
     * значит забыть про каждую палитру, заведённую позже; светлота холста
     * известна здесь и всегда.
     *
     * Порог — средний серый: у `0xFF808080` относительная светлота 0.216, всё
     * ниже неё глаз читает как тёмное основание.
     */
    const val DARK_CANVAS_LUMINANCE: Float = 0.2f

    fun isDarkCanvas(canvas: Color): Boolean =
        relativeLuminance(canvas) < DARK_CANVAS_LUMINANCE
}
