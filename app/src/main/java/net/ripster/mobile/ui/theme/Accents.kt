package net.ripster.mobile.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

/**
 * Акцент, который человек выбирает сам, — поверх палитры темы.
 *
 * Почему это вообще отдельная настройка (референс владельца — обложка скина
 * Poweramp «Aurora», 23.09.2026): тема решает, какой Вокруг экран (тёмный или
 * светлый, тёплый или холодный), а акцент решает, ЧТО НА ЭКРАНЕ главное —
 * кнопка воспроизведения, активный пункт навигации, полоса перемотки. В вебе
 * эти два выбора уже были разделены; на телефоне акцент до сих пор был
 * пришит к теме, и «сделай посинее» означало «поменяй тему целиком».
 *
 * Почему акцент — НЕ просто «вот цвет, рисуй»:
 *
 * * акцент лежит и как подпись на холсте, и как заливка под текстом темы. Один
 *   и тот же цвет не может годиться для обоих сразу на любой палитре — поэтому
 *   светлота акцента ищется ПОД текущую палитру ([legibleAccent]), а не
 *   выбирается человеком на глаз;
 * * отсюда и замкнутый список шестнадцати оттенков: произвольный цвет из
 *   пипетки пришлось бы подбирать и пояснять, а здесь каждый проходит
 *   [AccentContrastTest] по всем семи палитрам — ровно как палитры проходят
 *   PaletteContrastTest. Правило то же: множество замкнуто, иначе появляется
 *   сто тридцать первый оттенок, который никто не мерил.
 */
data class AccentSwatch(val id: String, val hue: Color)

/** Пустое значение настройки — «акцент берётся из темы», ничего не перекрываем. */
const val ACCENT_FROM_THEME = ""

/**
 * Верхняя граница хроматики акцента.
 *
 * Шире, чем у заливки из обложки ([CoverMaxChroma] = 0.11): обложка — это чужая
 * картинка, и спорить с ней интерфейсу нечего, а акцент, наоборот, обязан
 * быть заметным. Ниже 0.16 не уходишь в кислоту, а выше чистые sRGB-пигменты
 * начинают терять канал при обрезании по гамуту — светлота перестаёт отвечать
 * за контраст.
 */
const val AccentMaxChroma: Float = 0.16f

/**
 * Шестнадцать оттенков. Это НЕ «все цвета радуги», а выверенный круг: пары
 * соседних различимы и в малом кегле (активный пункт навигации — это 11.sp),
 * и в оттенках серого различаются по светлоте достаточно, чтобы выбор человека
 * не превращался в угадайку.
 */
object AccentPalette {
    val all: List<AccentSwatch> = listOf(
        AccentSwatch("ruby", Color(0xFFEF5B5B)),
        AccentSwatch("coral", Color(0xFFFF7A5C)),
        AccentSwatch("ember", Color(0xFFE0873C)),
        AccentSwatch("amber", Color(0xFFE3B23A)),
        AccentSwatch("olive", Color(0xFFA8B84A)),
        AccentSwatch("lime", Color(0xFF8CD24A)),
        AccentSwatch("green", Color(0xFF46C37A)),
        AccentSwatch("jade", Color(0xFF23BE9A)),
        AccentSwatch("teal", Color(0xFF2FC0D6)),
        AccentSwatch("azure", Color(0xFF4DA3FF)),
        AccentSwatch("indigo", Color(0xFF7A80E8)),
        AccentSwatch("violet", Color(0xFFA76BF0)),
        AccentSwatch("orchid", Color(0xFFC85CE8)),
        AccentSwatch("fuchsia", Color(0xFFEA4FB0)),
        AccentSwatch("pink", Color(0xFFFF6BA1)),
        AccentSwatch("slate", Color(0xFF9AA8BC)),
    )

    fun byId(id: String): AccentSwatch? = all.firstOrNull { it.id == id }
}

/**
 * Динамические цвета (Material You) есть не везде, и ответ «нет» обязан быть
 * с причиной: ниже Android 12 системной палитры не существует в принципе, и
 * выключенный переключатель без объяснения человек читает как сломанный.
 */
const val MaterialYouMinSdk: Int = 31

fun materialYouSupported(sdkInt: Int): Boolean = sdkInt >= MaterialYouMinSdk

/** Статус переключателя на экране настроек — ровно два честных ответа. */
enum class MaterialYouStatus {
    /** Палитра доступна и её можно включить. */
    Available,

    /** Система старее Android 12 — включить нельзя, надо объяснить почему. */
    RequiresAndroid12,
}

fun materialYouStatus(sdkInt: Int): MaterialYouStatus =
    if (materialYouSupported(sdkInt)) MaterialYouStatus.Available
    else MaterialYouStatus.RequiresAndroid12

/**
 * Акцент из системной палитры (Material You). `null`, если динамических цветов
 * нет — устройство без поддержки или прошивка, где они отключены.
 *
 * Берём ровно `primary`, а не всю динамическую scheme: наши поверхности —
 * contract-проверенные палитры (см. шапку RipsterColors.kt и tools/
 * check_contrast.py), и отдать под их раскраску чужой генератор означало бы
 * потерять единственное место, где контраст доказан. Человек выбирает акцент,
 * а не переезжает в чужой дом.
 */
@Composable
fun dynamicAccentColor(context: Context, dark: Boolean): Color? {
    if (!materialYouSupported(Build.VERSION.SDK_INT)) return null
    // Ошибка здесь не фатальна и не должна ронять интерфейс: у части прошивок
    // динамическая палитра есть, но отдаёт то, что разобрать нельзя.
    return runCatching {
        if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
    }.getOrNull()
}

/**
 * Тот же тон и (насколько гамут пустит) та же насыщенность; светлота — своя,
 * если она годится в акцент ПЕРЕД всеми поверхностями [surfaces], и иначе
 * сдвинутая ровно настолько, чтобы годилась.
 *
 * Направление прочь от поверхности, как в [clampCoverTint]: по тёмным холстам
 * акцент светлеет, по светлым темнеет. Ищется контраст ровно `min`, а не
 * «максимум возможного» — перекрученный акцент кричит и спорит с обложкой.
 *
 * Если цели не выходит (узкий гамут: чистый жёлтый на белом холсте темнее
 * нужного становится уже по всем каналам), хроматика режется вдвое и поиск
 * повторяется — тон важнее насыщенности. На последнем рубеже остаётся
 * ахроматика: акцент превратится в серый, но не в невидимый.
 */
fun legibleAccent(accent: Color, surfaces: List<Color>, min: Float = MinTextContrast): Color {
    if (surfaces.isEmpty()) return accent
    val src = accent.toOklch()
    val lightSurface = isLightSurface(surfaces.first())
    var chroma = src.c.coerceAtMost(AccentMaxChroma)
    if (chroma < CoverChromaFloor) chroma = 0f

    // Светлоту не трогаем, если она и так проходит: человек выбрал вот этот
    // оттенок, а не «минимально допустимый той же волны». В отличие от
    // заливки из обложки (clampCoverTint), где светлоту НАДО выровнять —
    // иначе одни альбомы кричат, а другие бледнеют, — у акцента один и тот
    // же выбор на всех экранах, и сплющивать его к порогу незачем.
    val asChosen = Oklch(src.l, chroma, src.h).toColor()
    if (surfaces.all { contrastRatio(asChosen, it) >= min }) return asChosen

    var attempt = 0
    while (attempt < 3) {
        val found = searchAccentLightness(src.h, chroma, surfaces, lighten = !lightSurface, min)
        if (surfaces.all { contrastRatio(found, it) >= min }) return found
        chroma /= 2f
        attempt++
        if (chroma < CoverChromaFloor) chroma = 0f
    }
    return if (lightSurface) Color.Black else Color.White
}

/** Двоичный поиск светлоты по ОКЛАХ против худшей из поверхностей. */
private fun searchAccentLightness(
    hue: Float,
    chroma: Float,
    surfaces: List<Color>,
    lighten: Boolean,
    min: Float,
): Color {
    var lo = 0f
    var hi = 1f
    var best = Oklch(if (lighten) 1f else 0f, chroma, hue).toColor()
    repeat(24) {
        val mid = (lo + hi) / 2f
        val c = Oklch(mid, chroma, hue).toColor()
        // Худшая поверхность та, до которой контраст минимален: акцент обязан
        // выдержать все четыре, а не среднее по ним.
        val worst = surfaces.minOf { contrastRatio(c, it) }
        if (lighten) {
            if (worst < min) lo = mid else { hi = mid; best = c }
        } else {
            if (worst < min) hi = mid else { lo = mid; best = c }
        }
    }
    return best
}

/**
 * Палитра темы с перекрашенным акцентом.
 *
 * Троаются четыре токена, и только они: `accent_text` (подпись, иконка
 * активного таба, тон полосы перемотки), `accent_fill` (главная кнопка,
 * кружок мини-плеера, заполненная часть прогресса) и два их состояния.
 * `text_on_fill` НЕ подменяется: текст на кнопке остаётся тем, для чего
 * создатель палитры уже доказал контраст, — акцент под него подбирается сам
 * (светлеет по тёмным холстам, темнеет по светлым), и [AccentContrastTest]
 * меряет эту пару напрямую, а не на веру.
 *
 * [accent] == null — «по теме», палитра возвращается без изменения (та же
 * ссылка, чтобы `remember` зря не перекрашивал весь экран).
 */
fun applyAccent(base: RipsterColors, accent: Color?): RipsterColors {
    val hue = accent ?: return base
    val surfaces = listOf(
        base.surface_canvas, base.surface_sunken, base.surface_raised, base.surface_overlay,
    )
    val fill = legibleAccent(hue, surfaces)
    // Состояния — тот же тон, чуть сильнее разведённый в сторону текста темы:
    // текст по определению «дальше» от холста, чем акцент, поэтому и hover, и
    // active уходят от холста, а не к нему. Каждый прогоняется через
    // legibleAccent заново: смешение двух законных цветов обязано остаться
    // законным, а не «надеюсь, на глаз прошло».
    val hover = legibleAccent(lerp(fill, base.text_primary, 0.18f), surfaces)
    val active = legibleAccent(lerp(fill, base.text_secondary, 0.22f), surfaces)
    return base.copy(
        accent_text = fill,
        accent_fill = fill,
        accent_hover = hover,
        accent_active = active,
    )
}
