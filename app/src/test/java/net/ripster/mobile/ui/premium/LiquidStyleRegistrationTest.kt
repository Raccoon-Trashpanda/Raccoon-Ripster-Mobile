package net.ripster.mobile.ui.premium

import java.io.File
import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.screens.LiquidStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регистрация четвёртого стиля плеера — «Флюид» — чистыми тестами.
 *
 * Стиль заведён по вердикту владельца 23.09.2026 («у эпл хорошие большие
 * кнопочки, мягкие… наш стеклянный плеер не идёт в сравнение»), и чтобы он
 * не оказался «ещё одной папкой с экраном, о которой забыли в настройках»,
 * здесь проверяются все три конца проводки: словарь, список в настройках и
 * маршрутизация AppShell. Плюс имя: оно обязано быть нашим — ни одной чужой
 * вывески в списке стилей проект не носит.
 */
class LiquidStyleRegistrationTest {

    private val shell = File("src/main/java/net/ripster/mobile/ui/AppShell.kt")
    private val settingsHost = File("src/main/java/net/ripster/mobile/ui/screens/settings/SettingsHost.kt")

    @Test
    fun `style id is the settled literal`() {
        // Настройка хранит строку; константа и пикер обязаны сойтись на ней.
        assertEquals("liquid", LiquidStyle.ID)
        if (settingsHost.exists()) {
            assertTrue(
                "в пикере настроек нет строки стиля по константе",
                settingsHost.readText().contains("\"${LiquidStyle.ID}\" to \"${LiquidStyle.NAME_KEY}\""),
            )
        }
    }

    @Test
    fun `style name is translated into every language`() {
        for (lang in AppLang.entries) {
            val name = tr(LiquidStyle.NAME_KEY, lang)
            assertTrue("пустое имя стиля в $lang", name.isNotBlank())
            // Пропущенный перевод tr отдаёт ключом — это не имя.
            assertTrue("в $lang нет перевода имени стиля", name != LiquidStyle.NAME_KEY)
            // Имя — наше, не чужая вывеска: сравниваем с известными заимствованиями.
            assertFalse("имя стиля копирует чужую вывеску: $name",
                name.contains("Apple", true) || name.equals("Music", true))
        }
        // И не дублирует существующие стили — иначе в пикере два одинаковых пункта.
        val names = AppLang.RU.let { lang ->
            listOf(
                "player.style_studio", "player.style_immersive", "player.style_reference",
            ).map { tr(it, lang) } + tr(LiquidStyle.NAME_KEY, lang)
        }
        assertEquals("имена стилей не должны повторяться", names.size, names.toSet().size)
    }

    @Test
    fun `AppShell routes the style to its own screen`() {
        if (!shell.exists()) return
        val text = shell.readText()
        assertTrue(
            "AppShell не маршрутизирует \"${LiquidStyle.ID}\" на LiquidPlayerScreen",
            Regex("playerStyle == \"${LiquidStyle.ID}\"[\\s\\S]{0,600}?LiquidPlayerScreen\\(").containsMatchIn(text),
        )
        // Маршрут обязан быть ДО fallback-а на Reference, иначе «стиль» молча
        // открывал бы чужой экран и настройка врала бы.
        val liquidAt = text.indexOf("playerStyle == \"${LiquidStyle.ID}\"")
        val referenceAt = text.indexOf("ReferencePlayerScreen(")
        assertTrue("маршрут \"liquid\" стоит после Reference и недостижим", liquidAt in 0..<referenceAt)
    }

    @Test
    fun `the action menu and bottom capsule cover the same entries as the old chips`() {
        val screen = File("src/main/java/net/ripster/mobile/ui/screens/LiquidPlayerScreen.kt")
        if (!screen.exists()) return
        val text = screen.readText()
        // «⋯» обязывает стеклянный лист показать всё, что раньше несли чипы.
        for (key in listOf(
                "ref.tracklist", "ref.lyrics", "ref.spectrum", "ref.equalizer",
                "ref.sleep", "ref.cast", "ref.download",
            )) {
            assertTrue("из листа действий пропал вход «$key»", text.contains("\"$key\""))
        }
        // Нижняя капсула: три входа без подписей — текст, вывод, очередь.
        assertTrue("капсула не ведёт в текст", text.contains("sheet = SHEET_LYRICS"))
        assertTrue("капсула не ведёт на колонку", text.contains("sheet = SHEET_CAST"))
        assertTrue("капсула не ведёт в очередь", text.contains("sheet = SHEET_TRACKLIST"))
        // Минус одно — само определение composable-функции MiniAction.
        assertEquals(
            "подписи после вердикта «хват чипов» не место: иконок в капсуле три",
            3, Regex("MiniAction\\(").findAll(text).count() - 1,
        )
    }
}
