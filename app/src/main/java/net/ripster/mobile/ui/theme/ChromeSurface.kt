package net.ripster.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

/**
 * Хром обязан краситься по той поверхности, НА КОТОРУЮ человек смотрит.
 *
 * Что было не так (оркестратор, 23.09.2026, системный светлый режим): шапка,
 * нижняя навигация и полоса «N готово» брали холст темы — светлый, — а экраны
 * «студия» и «иммерсив» красят себя почти чёрным всегда. Между ними получался
 * лоскут: светлая полоса поверх тёмного плеера, и та же полоса под системными
 * барами. Каждый элемент по отдельности правильный, вместе — приложение,
 * которое не знает, какая у него тема.
 *
 * Честного ответа «сделай плеер светлым» здесь нет и не будет: плеер — тёмная
 * комната, обложка и подсветка в ней, это утверждённый скин. Поэтому правило
 * одностороннее: это хром подстраивается под поверхность плеера, а не наоборот.
 *
 * Функция чистая и не знает про Compose-состояние — чем и проверяется ниже без
 * эмулятора.
 */

/** Поверхность плееров «студия» и «иммерсив» — своя, почти чёрная (NowPlayingScreen.kt). */
val PlayerDarkSurface: Color = Color(0xFF07070A)

/**
 * Палитра постоянного хрома: шапки, нижней навигации или рельса и полосы
 * «готово».
 *
 * [playerSurface] — null, когда развёрнутого плеера нет (или он красится холстом
 * темы, как «reference»). Дальше два случая:
 *
 * * яркости уже согласованы — палитра НЕ меняется. В тёмной теме это принципиально:
 *   Neon, Aurora, Ember и Midnight остаются самими собой, а не съезжают в
 *   безликий Dark ради поверхности, которая и так тёмная;
 * * яркости разъехались — хром берёт палитру той яркости, что у плеера, и её же
 *   холстом красит полотно под системными барами, иначе на стыке полос и плеера
 *   осталась бы та же светлая лента, из-за которой всё и затеяли. Акцент при этом
 *   переносится: навигация под плеером обязана светиться тем же оттенком, что и
 *   кнопка воспроизведения над ним, иначе настройка акцента «работает, пока
 *   плеёр свёрнут».
 */
fun chromeColorsFor(app: RipsterColors, playerSurface: Color?): RipsterColors {
    val surface = playerSurface ?: return app
    val playerLight = isLightSurface(surface)
    if (isLightSurface(app.surface_canvas) == playerLight) return app
    val swapped = (if (playerLight) LightColors else DarkColors).copy(surface_canvas = surface)
    return applyAccent(swapped, app.accent_fill)
}

/**
 * Подменить палитру для subtree хрома.
 *
 * Отдельная функция, а не `CompositionLocalProvider` на каждом из четырёх мест:
 * хром в оболочке живёт разбросанным по дереву (шапка, рельс, нижняя строка,
 * навигация), и однажды забытая обёртка — это та же светлая полоса, только
 * одна. Обёрнутый код внутри читает обычный `RipsterTheme.colors`.
 */
@Composable
fun chromeScope(colors: RipsterColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRipsterColors provides colors, content = content)
}

/**
 * Полярность пиктограмм системных баров — за поверхностью ПОД ними, а не за
 * ночным режимом системы (см. ThemeMode.kt).
 *
 * Кто красит полотно под барами, тот и вызывает это: онбординг — корень
 * ([MainActivity]), остальное — оболочка. Двум владельцам на одном окне быть
 * нельзя, иначе при смене темы остаётся устаревший ответ.
 */
@Composable
fun applySystemBarInk(surface: Color, enabled: Boolean = true) {
    if (!enabled) return
    val barsView = LocalView.current
    val lightBars = isLightSurface(surface)
    SideEffect {
        val window = barsView.context as? android.app.Activity ?: return@SideEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(
            window.window, window.window.decorView,
        )
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
    }
}
