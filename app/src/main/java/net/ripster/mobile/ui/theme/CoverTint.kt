package net.ripster.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Цвет обложки как ИДЕНТИЧНОСТЬ, а не как состояние.
 *
 * Правило проекта, из-за которого этот файл вообще существует отдельно от
 * компонента: обложка вправе сказать «это вот тот альбом» и не вправе сказать
 * «играет / буферизуется / ошибка». Состояние кодируется формой и положением —
 * тем, что читается в оттенках серого. Поэтому здесь нет ни одной функции,
 * которая принимала бы состояние: на входе только картинка и поверхность темы.
 *
 * Вторая причина: обложка — произвольные данные. Она бывает белым квадратом,
 * чёрным квадратом, кислотно-зелёной и ровно того же серого, что и поверхность
 * темы. Значит цвет из неё нельзя брать «как есть» — его надо ЗАЖИМАТЬ. Ниже
 * настоящий зажим в Oklch с проверкой контраста по WCAG, а не «умножим на 0.8»:
 * умножение на константу сохраняет ровно ту проблему, ради которой его писали —
 * тёмная обложка на тёмной теме остаётся нечитаемой, светлая на светлой тоже.
 */

// ---------------------------------------------------------------------------
// sRGB ↔ линейное пространство и относительная яркость по WCAG 2.x
// ---------------------------------------------------------------------------

/** Обратная гамма sRGB. Точная кусочная кривая, не приближение x^2.2. */
internal fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

internal fun linearToSrgb(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) v * 12.92f
    else (1.055f * v.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f)
}

/**
 * Относительная яркость по WCAG. Считается по линейным компонентам —
 * ровно поэтому её нельзя получить из sRGB-значений напрямую.
 */
fun relativeLuminance(color: Color): Float =
    0.2126f * srgbToLinear(color.red) +
        0.7152f * srgbToLinear(color.green) +
        0.0722f * srgbToLinear(color.blue)

/** Контраст по WCAG: (L1 + 0.05) / (L2 + 0.05), всегда ≥ 1. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = max(la, lb)
    val lo = if (hi == la) lb else la
    return (hi + 0.05f) / (lo + 0.05f)
}

// ---------------------------------------------------------------------------
// Oklab / Oklch
// ---------------------------------------------------------------------------

/**
 * Зажим делается в Oklch, а не в HSL.
 *
 * HSL врёт про светлоту: жёлтый и синий с одинаковым L в HSL различаются по
 * реальной яркости примерно в шесть раз. Зажимать светлоту в HSL — значит
 * получить полосу, которая для жёлтых обложек слепит, а для синих тонет,
 * при формально одинаковых числах. В Oklab L примерно равномерен по восприятию,
 * поэтому поиск по L сходится предсказуемо, а хвост хроматики не тянет за собой
 * яркость.
 */
data class Oklch(val l: Float, val c: Float, val h: Float)

private fun cbrtf(x: Float): Float =
    if (x < 0f) -((-x).toDouble().pow(1.0 / 3.0)).toFloat() else (x.toDouble().pow(1.0 / 3.0)).toFloat()

fun Color.toOklch(): Oklch {
    val r = srgbToLinear(red)
    val g = srgbToLinear(green)
    val b = srgbToLinear(blue)

    val lc = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
    val mc = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
    val sc = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

    val l_ = cbrtf(lc)
    val m_ = cbrtf(mc)
    val s_ = cbrtf(sc)

    val ll = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
    val aa = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
    val bb = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_

    return Oklch(ll, sqrt(aa * aa + bb * bb), atan2(bb, aa))
}

/**
 * Обратно в sRGB с ОБРЕЗАНИЕМ по гамуту (простой clamp по каналам).
 *
 * Обрезание меняет фактическую яркость результата, поэтому контраст ниже
 * проверяется всегда на уже обрезанном цвете, а не на идеальном Oklch. Без
 * этого зажим давал бы «математически верный» цвет, который на экране другой.
 */
fun Oklch.toColor(alpha: Float = 1f): Color {
    val a = c * cos(h)
    val b = c * sin(h)

    val l_ = l + 0.3963377774f * a + 0.2158037573f * b
    val m_ = l - 0.1055613458f * a - 0.0638541728f * b
    val s_ = l - 0.0894841775f * a - 1.2914855480f * b

    val lc = l_ * l_ * l_
    val mc = m_ * m_ * m_
    val sc = s_ * s_ * s_

    val r = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
    val g = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
    val bl = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc

    return Color(
        red = linearToSrgb(r).coerceIn(0f, 1f),
        green = linearToSrgb(g).coerceIn(0f, 1f),
        blue = linearToSrgb(bl).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

// ---------------------------------------------------------------------------
// Средний цвет обложки
// ---------------------------------------------------------------------------

/**
 * Средний цвет обложки. Без Palette — лишняя зависимость ради того, что здесь
 * умещается в тридцать строк, и ради эвристик («vibrant», «muted»), которые нам
 * как раз не нужны: доминирующий цвет — это уже интерпретация, а нам нужен факт.
 *
 * ПОЧЕМУ УСРЕДНЯЕМ В ЛИНЕЙНОМ ПРОСТРАНСТВЕ. Байты sRGB — это не количество
 * света, а его перцептивная кодировка (гамма ≈ 2.2). Среднее арифметическое
 * закодированных значений не равно кодировке среднего света. Практическое
 * следствие видно сразу: обложка «половина чистого красного, половина чистого
 * зелёного» в sRGB усредняется в #808000 — тусклую грязную оливку, темнее обеих
 * половин; в линейном пространстве получается заметно более светлый жёлто-
 * коричневый, то есть то, что глаз действительно видит, отойдя на два шага.
 * Ошибка тем больше, чем контрастнее обложка — а контрастных обложек
 * большинство.
 *
 * Хроматику НЕ поднимаем. Если у обложки нет доминирующего тона (сине-оранжевый
 * постер усредняется в серый), то серый и есть честный ответ; «оживить» его
 * значило бы придумать альбому цвет, которого в нём нет.
 *
 * @param samplesPerAxis сетка семплов. 24×24 = 576 точек: достаточно, чтобы
 *   среднее не гуляло от кадра к кадру, и дёшево на любом размере картинки.
 * @param minAlpha почти прозрачные пиксели выбрасываются целиком, а не
 *   учитываются с малым весом: прозрачные поля PNG-обложек несут не «немного
 *   этого цвета», а «здесь ничего нет», и их премноженный чёрный утянул бы
 *   среднее в темноту.
 * @return null, если пригодных пикселей не нашлось — вызывающий обязан иметь
 *   запасной цвет темы, а не подставлять чёрный.
 */
fun averageCoverColor(
    image: ImageBitmap,
    samplesPerAxis: Int = 24,
    minAlpha: Float = 0.35f,
): Color? {
    val w = image.width
    val h = image.height
    if (w <= 0 || h <= 0) return null

    val rows = samplesPerAxis.coerceIn(2, h)
    val cols = samplesPerAxis.coerceIn(2, w)

    var rSum = 0.0
    var gSum = 0.0
    var bSum = 0.0
    var n = 0

    for (j in 0 until rows) {
        val y = ((j + 0.5f) / rows * h).toInt().coerceIn(0, h - 1)
        // Читаем ПОЛОСКОЙ в одну строку пикселей, а не всю картинку сразу:
        // toPixelMap() на обложке 1500×1500 — это 9 МБ единовременно, и на
        // слабом телефоне это заметный всплеск, повторяемый при каждой смене
        // трека.
        val row = image.toPixelMap(startX = 0, startY = y, width = w, height = 1)
        for (i in 0 until cols) {
            val x = ((i + 0.5f) / cols * w).toInt().coerceIn(0, w - 1)
            val px = row[x, 0]
            if (px.alpha < minAlpha) continue
            rSum += srgbToLinear(px.red).toDouble()
            gSum += srgbToLinear(px.green).toDouble()
            bSum += srgbToLinear(px.blue).toDouble()
            n++
        }
    }
    if (n == 0) return null

    return Color(
        red = linearToSrgb((rSum / n).toFloat()),
        green = linearToSrgb((gSum / n).toFloat()),
        blue = linearToSrgb((bSum / n).toFloat()),
    )
}

// ---------------------------------------------------------------------------
// Зажим под поверхность темы
// ---------------------------------------------------------------------------

/**
 * Верхняя граница хроматики в Oklch.
 *
 * Чистый красный sRGB имеет C ≈ 0.26, кислотно-зелёный ≈ 0.29. 0.11 — примерно
 * приглушённый средний тон. Две причины именно зажимать, а не пропускать:
 * (1) полоса перемотки лежит рядом с обложкой, и заливка с хроматикой самой
 * обложки начинает с ней спорить — вместо «это тот альбом» получается «смотри
 * на меня»; (2) у высокой хроматики гамут sRGB узкий по L, поиск светлоты
 * упирается в обрезание и перестаёт быть управляемым.
 */
const val CoverMaxChroma: Float = 0.11f

/**
 * Ниже этого порога считаем обложку ахроматичной и обнуляем C.
 * Иначе численный шум в почти-сером среднем даёт случайный тон, который скачет
 * между соседними треками одного альбома.
 */
const val CoverChromaFloor: Float = 0.015f

/**
 * ЗАЖИМ. Возвращает цвет, у которого от обложки остались тон и (ограниченная)
 * хроматика, а светлота ЗАДАНА поверхностью темы.
 *
 * Ключевое решение: мы ищем контраст РОВНО [targetContrast], а не «не меньше
 * трёх». Это и есть ответ на жалобу «заполненная часть спорит яркостью с
 * обложкой»: у всех альбомов заливка выходит одинаковой яркости относительно
 * фона, различаются только тон и насыщенность. Ни один альбом не может оказаться
 * ярче другого — светлота больше не принадлежит обложке.
 *
 * ХУДШИЙ СЛУЧАЙ — обложка, чья светлота совпала со светлотой поверхности
 * (серый альбом на Dark, кремовый на Sepia). Наивная реализация вернула бы
 * невидимую полосу. Здесь этого не происходит: поиск двигает L прочь от
 * поверхности в ту сторону, где больше запаса (от тёмной темы — вверх, от
 * светлой — вниз), пока контраст не достигнет цели. Обложка «того же серого»
 * превращается в заметно более светлый (или тёмный) серый ТОГО ЖЕ тона —
 * идентичность сохранена настолько, насколько её вообще было, читаемость
 * восстановлена.
 *
 * Достижимость цели доказуема, а не понадеяна. Максимум того, что вообще можно
 * получить против поверхности с яркостью Ls, равен max((Ls+0.05)/0.05,
 * 1.05/(Ls+0.05)); минимум этого максимума — при Ls ≈ 0.179, и он равен 4.58.
 * То есть 4.5:1 достижимо против ЛЮБОЙ поверхности, включая гипотетическую
 * средне-серую, а против шести наших (все либо почти чёрные, либо почти белые)
 * запас многократный. Порог 3:1 не может быть не достигнут в принципе.
 *
 * @param minContrast жёсткий минимум; если он вдруг не взят (только при
 *   экзотической поверхности и высокой хроматике), хроматика режется вдвое и
 *   поиск повторяется.
 */
fun clampCoverTint(
    cover: Color,
    surface: Color,
    targetContrast: Float = 4.5f,
    minContrast: Float = 3.0f,
    maxChroma: Float = CoverMaxChroma,
): Color {
    val src = cover.toOklch()
    var chroma = src.c.coerceAtMost(maxChroma)
    if (chroma < CoverChromaFloor) chroma = 0f

    val surfaceLuma = relativeLuminance(surface)
    // 0.179 — точка равного запаса вверх и вниз (см. выкладку выше).
    val goLighter = surfaceLuma < 0.179f

    var attempt = 0
    while (attempt < 3) {
        val found = searchLightness(src.h, chroma, surface, goLighter, targetContrast)
        if (contrastRatio(found, surface) >= minContrast) return found
        // Не взяли минимум — виновата хроматика, съевшая гамут. Режем её,
        // а не тон: тон и есть та часть обложки, ради которой всё затевалось.
        chroma /= 2f
        attempt++
        if (chroma < CoverChromaFloor) chroma = 0f
    }
    // Последний рубеж: чистая ахроматика в сторону запаса. Гарантированно ≥ 4.5.
    return if (goLighter) Color.White else Color.Black
}

/**
 * Двоичный поиск светлоты. Оценивает контраст на УЖЕ обрезанном по гамуту
 * цвете, поэтому обрезание не может тихо изменить результат.
 */
private fun searchLightness(
    hue: Float,
    chroma: Float,
    surface: Color,
    goLighter: Boolean,
    target: Float,
): Color {
    var lo = if (goLighter) 0f else 0f
    var hi = 1f
    // Границей поиска служит край шкалы в нужную сторону; исходная светлота
    // обложки намеренно НЕ используется как якорь — иначе тёмная обложка на
    // тёмной теме получила бы более тусклую полосу, чем светлая, то есть
    // яркость снова начала бы что-то означать.
    var best = Oklch(if (goLighter) 1f else 0f, chroma, hue).toColor()
    repeat(24) {
        val mid = (lo + hi) / 2f
        val c = Oklch(mid, chroma, hue).toColor()
        val ratio = contrastRatio(c, surface)
        if (goLighter) {
            // Контраст растёт с ростом L.
            if (ratio < target) lo = mid else { hi = mid; best = c }
        } else {
            // Контраст растёт с падением L.
            if (ratio < target) hi = mid else { lo = mid; best = c }
        }
    }
    return best
}

/**
 * Готовый цвет заливки полосы: обложка, зажатая под текущую поверхность.
 * Если обложки нет — тихо отдаёт запасной цвет темы, без попытки что-то выдумать.
 */
@Composable
fun rememberClampedCoverTint(
    cover: Color?,
    surface: Color,
    fallback: Color,
    targetContrast: Float = 4.5f,
): Color = remember(cover, surface, fallback, targetContrast) {
    if (cover == null) fallback else clampCoverTint(cover, surface, targetContrast)
}

/**
 * Извлечение среднего цвета вне главного потока. 576 семплов дёшевы, но чтение
 * пиксельных строк из ImageBitmap на UI-потоке при быстрой смене треков даёт
 * пропуск кадров, а полоса — как раз тот элемент, на котором пропуск заметен.
 */
@Composable
fun rememberAverageCoverColor(image: ImageBitmap?): State<Color?> =
    produceState<Color?>(initialValue = null, key1 = image) {
        value = if (image == null) null else withContext(Dispatchers.Default) { averageCoverColor(image) }
    }

/** Диагностика для tools/check_contrast: насколько зажатый тон отошёл от исходного. */
fun coverTintDrift(original: Color, clamped: Color): Float =
    abs(relativeLuminance(original) - relativeLuminance(clamped))
