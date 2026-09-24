package net.ripster.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Значение настройки «Системная» — не палитра, а режим: какая из двух
 * палитр (светлой или тёмной) возьмёт система, видно только в рантайме
 * по uiMode. Держим его отдельной строкой-настройкой, а не восьмым
 * элементом RipsterThemeName: множество палитр замкнуто (см. RipsterTheme.kt),
 * и «System» нечего показать в tools-проверке контраста — контраста у
 * режима нет, он чужой.
 */
const val THEME_SETTING_SYSTEM = "System"

/**
 * Тема по умолчанию и запасной вариант при неразбираемом значении.
 * Neon — утверждённый скин (design/design-system-neon.md, дефолт AppSettings).
 * Раньше дефолт жил в двух местах и расходился: Snapshot писал "Neon",
 * а MainActivity при непарсящемся значении молча скатывался в Dark —
 * с появлением резолвера единственное место с ответом — здесь.
 */
val DefaultThemeName = RipsterThemeName.Neon

/** Все значения настройки темы, в порядке показа в пикерах. */
fun themeSettingOptions(): List<String> =
    listOf(THEME_SETTING_SYSTEM) + RipsterThemeName.entries.map { it.name }

/**
 * Настройка + ночной режим системы → палитра. Одно место на весь апп:
 * заставка, бары и контент обязаны называть одну тему, иначе на долю
 * секунды получаем чужой холст под пальцем.
 *
 * «System» разрешается строго в Dark/Light — в тёмный или светлый канвас,
 * а не в чужой скин: человек, выбравший «как в системе», про внешность
 * ничего не говорил, и навязывать ему пурпур Aurora было бы выдумкой.
 */
fun resolveThemeSetting(saved: String?, systemDark: Boolean): RipsterThemeName = when (saved) {
    THEME_SETTING_SYSTEM -> if (systemDark) RipsterThemeName.Dark else RipsterThemeName.Light
    null -> DefaultThemeName
    else -> RipsterThemeName.entries.firstOrNull { it.name == saved } ?: DefaultThemeName
}

/**
 * Светлая ли поверхность под системными барами (иконки должны быть тёмными).
 * Порог не выдуман: берём тот же WCAG-контраст (contrastRatio из CoverTint.kt),
 * что и для текста, и смотрим, с белым или чёрным содержимым канвас контрастнее.
 */
fun isLightSurface(background: Color): Boolean =
    contrastRatio(Color.White, background) < contrastRatio(Color.Black, background)
