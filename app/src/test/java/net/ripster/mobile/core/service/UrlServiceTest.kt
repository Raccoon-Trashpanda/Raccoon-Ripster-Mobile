package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Какому сервису принадлежит ссылка.
 *
 * Правила перебраны в порядке перечисления, и этот порядок — часть контракта
 * (зеркало ПК `service_layer.detect_service`). Проверить есть чего: короткая
 * ссылка SoundCloud, зеркало домена Яндекса, `deezer.page` вместо `deezer.com`,
 * «не знаю» вместо «не поддерживается» и случай, когда в ссылке два домена
 * сразу — тогда решает первый названный.
 */
class UrlServiceTest {

    private fun of(url: String?) = UrlService.of(url!!)

    @Test
    fun appleMusicLinks() {
        assertEquals("apple", of("https://music.apple.com/us/album/mezzanine/123"))
        assertEquals("apple", of("https://music.apple.com/ru/artist/massive-attack/75719"))
    }

    @Test
    fun qobuzDeezerTidalSoundcloud() {
        assertEquals("qobuz", of("https://www.qobuz.com/us-en/music/albums/mezzanine"))
        assertEquals("deezer", of("https://www.deezer.com/album/14188116"))
        assertEquals("tidal", of("https://listen.tidal.com/album/123"))
        assertEquals("soundcloud", of("https://soundcloud.com/blondish/solar-return"))
    }

    @Test
    fun deezerPageMirrorIsDeezer() {
        assertEquals("deezer", of("https://deezer.page.link/abcDEF123"))
    }

    @Test
    fun shortSoundcloudLinksAreSoundcloud() {
        assertEquals("soundcloud", of("https://on.soundcloud.com/9ZxQv"))
        assertEquals("soundcloud", of("https://snd.sc/1a2b3c"))
    }

    @Test
    fun yandexDomainMirrors() {
        for (zone in listOf("ru", "com", "kz", "by", "az")) {
            assertEquals(zone, "yandex", of("https://music.yandex.$zone/album/12/track/34"))
        }
    }

    @Test
    fun spotifyBeatportAmazonBbcJioSaavn() {
        assertEquals("spotify", of("https://open.spotify.com/track/12345"))
        assertEquals("beatport", of("https://www.beatport.com/track/x/1234567"))
        assertEquals("amazon", of("https://music.amazon.com/albums/B01N2XR57S"))
        assertEquals("amazon", of("https://music.amazon.de/albums/B01N2XR57S"))
        assertEquals("bbc", of("https://www.bbc.co.uk/programmes/m000abcd"))
        assertEquals("bbc", of("https://www.bbc.co.uk/sounds/play/w3ct6hj0"))
        assertEquals("jiosaavn", of("https://www.jiosaavn.com/song/gerua/AAFhdxRedQ"))
    }

    @Test
    fun schemeAndCaseDoNotMatter() {
        assertEquals("apple", of("HTTPS://MUSIC.APPLE.COM/US/ALBUM/X/1"))
        assertEquals("apple", of("http://music.apple.com/us/album/x/1"))
    }

    @Test
    fun unknownButSupportedLookingDomainsAreEmpty() {
        assertEquals("", of("https://www.mixcloud.com/artist/mix/"))
        assertEquals("", of("https://example.com/track"))
        assertEquals("", of("mezzanine"))
    }

    @Test
    fun nothingAtAllIsNotAKnownService() {
        assertEquals("", UrlService.of(null))
        assertEquals("", UrlService.of(""))
        assertEquals("", UrlService.of("   "))
    }

    @Test
    fun yandexLinkWithoutMusicSubdomainIsNotGuessed() {
        // Намеренно: «не знаю» дешевле, чем Servizio-угада. ПК-зеркало так же.
        assertEquals("", of("https://yandex.ru/music/album/12"))
    }

    @Test
    fun firstRuleWinsWhenAUrlNamesTwoDomains() {
        // Порядок перечисления — контракт. Такой ссылкой никто не делится
        // намеренно, но переход-редирект выглядит именно так.
        assertEquals("apple", of("https://listen.tidal.com/album/1?ref=music.apple.com"))
        assertEquals("deezer", of("https://tidal.com/album/1?u=https://www.deezer.com/album/2"))
    }

    @Test
    fun queryAndPathDoNotHideTheHost() {
        assertEquals("soundcloud", of("https://soundcloud.com/blondish?utm_source=x&u=y"))
        assertEquals("tidal", of("https://listen.tidal.com/album/123?country=us"))
    }

    @Test
    fun isLinkAcceptsOnlyAbsoluteHttp() {
        assertTrue(UrlService.isLink("https://example.com"))
        assertTrue(UrlService.isLink("  http://example.com"))
        assertFalse(UrlService.isLink("example.com"))
        assertFalse(UrlService.isLink("//example.com"))
        assertFalse(UrlService.isLink("ftp://example.com"))
        assertFalse(UrlService.isLink(null))
        assertFalse(UrlService.isLink(""))
    }

    @Test
    fun uppercaseSchemeIsNotRecognisedAsALink() {
        // Документированный край: `isLink` сравнивает префикс без приведения
        // регистра, а `of` — приводит. RFC разрешает заглавную схему.
        assertFalse(UrlService.isLink("HTTPS://EXAMPLE.COM"))
    }

    /**
     * Ключ обязан читаться моделью: `Service.byId` — единственная дорога от
     * «распознали сервис» к «взяли клиент», и расхождение здесь молча
     * превращает кликабельную находку в «сервис не поддерживается».
     */
    @Test
    fun everyDetectedKeyResolvesToAService() {
        val urls = listOf(
            "https://music.apple.com/us/album/x/1", "https://www.qobuz.com/us-en/album",
            "https://www.deezer.com/album/1", "https://listen.tidal.com/album/1",
            "https://soundcloud.com/a/b", "https://snd.sc/1", "https://open.spotify.com/track/1",
            "https://www.beatport.com/track/x/1", "https://music.yandex.ru/album/1",
            "https://www.bbc.co.uk/programmes/m000abcd", "https://www.jiosaavn.com/song/x/1",
        )
        for (u in urls) {
            val key = UrlService.of(u)
            assertNotNull("ключ «$key» для $u", net.ripster.mobile.core.model.Service.byId(key))
        }
    }

    @Test
    // Имя было «…AServiceTheModelDoesNotHave» — до правки БАГ-6 оно описывало
    // сам баг. Теперь у «amazon» есть сервис в модели, поэтому тест зовётся по
    // обещанию из шапки UrlService: один перечень адресов и сервисов.
    fun amazonLinkNamesAServiceTheModelKnows() {
        val key = UrlService.of("https://music.amazon.com/albums/B01N2XR57S")
        assertEquals("amazon", key)
        assertNotNull(Service.byId(key))
    }
}