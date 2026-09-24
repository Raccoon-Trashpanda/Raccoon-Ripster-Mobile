package net.ripster.mobile.core.settings

import android.content.SharedPreferences
import net.ripster.mobile.ui.theme.ACCENT_FROM_THEME
import net.ripster.mobile.ui.theme.AccentPalette
import net.ripster.mobile.ui.theme.RipsterThemeName
import net.ripster.mobile.ui.theme.THEME_SETTING_SYSTEM
import net.ripster.mobile.ui.theme.resolveThemeSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Персист настроек — это честный контракт «update() записал, новый экземпляр
 * прочитал то же». Он ломается тихо: Compose живёт на одном снимке и всё
 * выглядит правильно до следующей перезагрузки, после которой тема, папка
 * или согласие на закачку по мобильной возвращаются к дефолтам. Поэтому
 * каждый тип значения (строка, bool, int с зажимом, csv-множество, map
 * «k=v;») проверяется round-trip'ом через те же клавиши, что читает load().
 */
class AppSettingsPersistenceTest {

    private class FakePrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? =
            map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValue: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? Set<String>)?.toMutableSet() ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue
        override fun getFloat(key: String, defValue: Float): Float =
            map[key] as? Float ?: defValue
        override fun getInt(key: String, defValue: Int): Int =
            map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long =
            map[key] as? Long ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { map[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { map[key] = values }
            override fun putInt(key: String, value: Int) = apply { map[key] = value }
            override fun putLong(key: String, value: Long) = apply { map[key] = value }
            override fun putFloat(key: String, value: Float) = apply { map[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
            override fun remove(key: String) = apply { map.remove(key) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() = Unit
        }
    }

    @Test
    fun `theme choice — including the system mode — survives a restart`() {
        val prefs = FakePrefs()
        val s = AppSettings(prefs)
        s.update { it.copy(theme = THEME_SETTING_SYSTEM) }
        assertEquals(THEME_SETTING_SYSTEM, prefs.map["theme"])
        val revived = AppSettings(prefs).state.value
        assertEquals(THEME_SETTING_SYSTEM, revived.theme)
        // и главный потребитель связки «персист × резолвер»: после перезапуска
        // тема разрешается так же, как до него.
        assertEquals(RipsterThemeName.Dark, resolveThemeSetting(revived.theme, systemDark = true))
        assertEquals(RipsterThemeName.Light, resolveThemeSetting(revived.theme, systemDark = false))
    }

    @Test
    fun `every value shape round-trips — bool, clamped int, set, map`() {
        val prefs = FakePrefs()
        AppSettings(prefs).update {
            it.copy(
                wifiOnly = false,
                parallelDownloads = 99,                    // зажимается до 6
                searchServicesOff = setOf("deezer", "spotify"),
                perServiceQuality = mapOf("qobuz" to "flac_24_96"),
                onboardingDone = true,
            )
        }
        val revived = AppSettings(prefs).state.value
        assertEquals(false, revived.wifiOnly)
        assertEquals(6, revived.parallelDownloads)
        assertEquals(setOf("deezer", "spotify"), revived.searchServicesOff)
        assertEquals(mapOf("qobuz" to "flac_24_96"), revived.perServiceQuality)
        assertEquals(true, revived.onboardingDone)
    }

    @Test
    fun `accent and material you choices survive a restart`() {
        val prefs = FakePrefs()
        AppSettings(prefs).update { it.copy(accent = "jade", materialYou = true) }
        assertEquals("jade", prefs.map["accent"])
        assertEquals(true, prefs.map["material-you"])

        val revived = AppSettings(prefs).state.value
        assertEquals("jade", revived.accent)
        assertEquals(true, revived.materialYou)
        // Выбор переживает перезапуск живым id, а не строкой «на всякий
        // случай»: резолвер темы опознаёт ровно этот id.
        assertEquals("jade", AccentPalette.byId(revived.accent)?.id)
    }

    @Test
    fun `default accent is 'from the theme', not some colour pinned in code`() {
        val s = AppSettings(FakePrefs()).state.value
        assertEquals(ACCENT_FROM_THEME, s.accent)
        assertEquals(null, AccentPalette.byId(s.accent))
        assertEquals(false, s.materialYou)
    }

    @Test
    fun `fresh install starts from the sanctioned defaults`() {
        val s = AppSettings(FakePrefs()).state.value
        // Дефолт темы обязан быть разбераемым резолвером — иначе первый запуск
        // молча уезжает в запасной вариант.
        assertEquals(s.theme, resolveThemeSetting(s.theme, systemDark = true).name)
        assertTrue(s.theme, RipsterThemeName.entries.any { it.name == s.theme })
        assertEquals("Normal", s.density)
        assertEquals("ru", s.uiLang)
    }

    @Test
    fun `premium visuals are OFF until the owner turns them on`() {
        val s = AppSettings(FakePrefs())
        assertFalse("режим не должен включаться сам", s.state.value.premiumVisuals)
    }

    @Test
    fun `the premium flag survives a restart`() {
        val prefs = FakePrefs()
        AppSettings(prefs).update { it.copy(premiumVisuals = true) }
        // Новый AppSettings поверх того же хранилища — это и есть «перезапустили».
        assertTrue(AppSettings(prefs).state.value.premiumVisuals)
    }

    @Test
    fun `turning premium visuals back off is persisted too`() {
        val prefs = FakePrefs()
        val first = AppSettings(prefs)
        first.update { it.copy(premiumVisuals = true) }
        first.update { it.copy(premiumVisuals = false) }
        assertFalse(AppSettings(prefs).state.value.premiumVisuals)
    }

    @Test
    fun `the premium key in storage is the documented one`() {
        val prefs = FakePrefs()
        AppSettings(prefs).update { it.copy(premiumVisuals = true) }
        assertTrue(
            "имя ключа синхронизируется с десктопным config.yaml — менять его молча нельзя",
            prefs.map.containsKey("premium-visuals"),
        )
    }

    @Test
    fun `writing the premium flag does not wipe neighbours`() {
        val prefs = FakePrefs()
        val first = AppSettings(prefs)
        first.update { it.copy(adaptiveColors = false, premiumVisuals = true, theme = "Aurora") }
        val restarted = AppSettings(prefs).state.value
        assertFalse(restarted.adaptiveColors)
        assertTrue(restarted.premiumVisuals)
        assertEquals("Aurora", restarted.theme)
        // Дефолты незатронутых настроек не должны поехать от перезапуска.
        assertEquals(2, restarted.parallelDownloads)
        assertTrue(restarted.wifiOnly)
    }

    @Test
    fun `the stored premium flag is what feeds the visual plan`() {
        // Сквозная проверка: включённая настройка действительно меняет план
        // устройства, а не просто красиво лежит в хранилище.
        val prefs = FakePrefs()
        AppSettings(prefs).update { it.copy(premiumVisuals = true) }
        val s = AppSettings(prefs).state.value
        val plan = net.ripster.mobile.ui.premium.PremiumVisuals.resolve(
            enabled = s.premiumVisuals, sdkInt = 34, animationsOff = false,
            batterySaver = false, adaptiveColors = s.adaptiveColors,
        )
        assertEquals(
            net.ripster.mobile.ui.premium.GlassTier.Refractive,
            plan.glass,
        )
    }
}
