package net.ripster.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Шесть тем перечислением, а не строкой и не числом.
 *
 * Причина: в вебе тема хранилась строкой, и опечатка в имени давала не ошибку
 * сборки, а молча уехавшую палитру. Перечисление делает невозможной тему,
 * которой нет в RipsterColors.kt.
 */
enum class RipsterThemeName {
    Dark,
    Light,
    Midnight,
    Ember,
    Sepia,
    Neon,
}

/**
 * Палитра выбирается ЗДЕСЬ и больше нигде.
 *
 * Причина: как только вызывающий код получает право собрать RipsterColors сам,
 * появляется седьмая тема, не прошедшая tools/check_contrast.py. Проверка
 * контраста имеет смысл только если множество палитр замкнуто.
 */
fun colorsFor(theme: RipsterThemeName): RipsterColors = when (theme) {
    RipsterThemeName.Dark -> DarkColors
    RipsterThemeName.Light -> LightColors
    RipsterThemeName.Midnight -> MidnightColors
    RipsterThemeName.Ember -> EmberColors
    RipsterThemeName.Sepia -> SepiaColors
    RipsterThemeName.Neon -> NeonColors
}

/**
 * Плотность — три положения. Она меняет кегли и отступы, то есть КАК показано.
 *
 * Она не меняет и не может менять минимальную мишень касания: 48 dp — это размер
 * пальца, а не вкус пользователя. Отсюда же следует, что RipsterDensity НЕ
 * трогает androidx LocalDensity: подмена системной плотности отмасштабировала бы
 * заодно и мишени, то есть тихо отменила бы порог. Здесь это отдельная величина,
 * которую читают только типографика и отступы.
 */
enum class RipsterDensity {
    Compact,
    Normal,
    Large,
}

/**
 * staticCompositionLocalOf, а не compositionLocalOf: смена темы перерисовывает
 * весь экран целиком, и это правильно — тема меняется по действию человека,
 * несколько раз за жизнь установки. Точечная инвалидация здесь стоила бы дороже,
 * чем даёт.
 */
val LocalRipsterColors = staticCompositionLocalOf<RipsterColors> {
    error("RipsterColors requested outside RipsterTheme")
}

val LocalRipsterSpacing = staticCompositionLocalOf<RipsterSpacing> {
    error("RipsterSpacing requested outside RipsterTheme")
}

val LocalRipsterType = staticCompositionLocalOf<RipsterType> {
    error("RipsterType requested outside RipsterTheme")
}

val LocalRipsterDensity = staticCompositionLocalOf { RipsterDensity.Normal }

val LocalRipsterThemeName = staticCompositionLocalOf { RipsterThemeName.Dark }

/**
 * Точка доступа для компонентов. Объект и composable-функция ниже носят одно имя
 * намеренно: обращение RipsterTheme.colors читается как «цвета текущей темы»,
 * и другого источника цвета у компонента нет.
 */
object RipsterTheme {
    val colors: RipsterColors
        @Composable @ReadOnlyComposable get() = LocalRipsterColors.current

    val spacing: RipsterSpacing
        @Composable @ReadOnlyComposable get() = LocalRipsterSpacing.current

    val type: RipsterType
        @Composable @ReadOnlyComposable get() = LocalRipsterType.current

    val density: RipsterDensity
        @Composable @ReadOnlyComposable get() = LocalRipsterDensity.current

    val name: RipsterThemeName
        @Composable @ReadOnlyComposable get() = LocalRipsterThemeName.current
}

@Composable
fun RipsterTheme(
    theme: RipsterThemeName = RipsterThemeName.Dark,
    density: RipsterDensity = RipsterDensity.Normal,
    content: @Composable () -> Unit,
) {
    val colors = colorsFor(theme)

    // remember по density, а не пересборка каждый кадр: величины неизменяемы и
    // зависят ровно от одного ключа.
    val spacing = remember(density) { spacingFor(density) }
    val type = remember(density) { typeFor(density) }

    CompositionLocalProvider(
        LocalRipsterThemeName provides theme,
        LocalRipsterColors provides colors,
        LocalRipsterDensity provides density,
        LocalRipsterSpacing provides spacing,
        LocalRipsterType provides type,
        content = content,
    )
}
