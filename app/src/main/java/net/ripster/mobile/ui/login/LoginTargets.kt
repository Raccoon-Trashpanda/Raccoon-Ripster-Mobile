package net.ripster.mobile.ui.login

import net.ripster.mobile.core.settings.CredentialStore

/**
 * Куда открывать окно входа и что оттуда забирать — калька с ПК
 * (`ripster/service_login.py` `TARGETS`). Человек логинится КАК УГОДНО (почта,
 * Google, телефон), а мы снимаем нужный токен из WebView:
 *  · `cookieName` — кука, которая появляется ПОСЛЕ входа;
 *  · `urlTokenParam` — токен приезжает во фрагменте адреса (OAuth implicit).
 */
data class LoginTarget(
    val service: String,
    val title: String,
    val url: String,
    val domain: String,
    val credKey: CredentialStore.Key,
    val cookieName: String? = null,
    val urlTokenParam: String? = null,
    val hintKey: String,
)

object LoginTargets {

    val ALL: List<LoginTarget> = listOf(
        LoginTarget(
            service = "soundcloud",
            title = "SoundCloud",
            url = "https://soundcloud.com/signin",
            domain = "soundcloud.com",
            credKey = CredentialStore.Key.SOUNDCLOUD_OAUTH,
            cookieName = "oauth_token",
            hintKey = "login.hint_soundcloud",
        ),
        LoginTarget(
            service = "deezer",
            title = "Deezer",
            url = "https://www.deezer.com/login",
            domain = "deezer.com",
            credKey = CredentialStore.Key.DEEZER_ARL,
            cookieName = "arl",
            hintKey = "login.hint_deezer",
        ),
        LoginTarget(
            service = "spotify",
            title = "Spotify",
            url = "https://accounts.spotify.com/login",
            domain = "spotify.com",
            credKey = CredentialStore.Key.SPOTIFY_SP_DC,
            cookieName = "sp_dc",
            hintKey = "login.hint_spotify",
        ),
        LoginTarget(
            service = "yandex",
            title = "Yandex Music",
            url = "https://oauth.yandex.ru/authorize?response_type=token" +
                "&client_id=23cabbbdc6cd418abb4b39c32c41195d",
            domain = "yandex.ru",
            credKey = CredentialStore.Key.YANDEX_OAUTH,
            urlTokenParam = "access_token",
            hintKey = "login.hint_yandex",
        ),
    )

    fun byService(service: String): LoginTarget? = ALL.firstOrNull { it.service == service }
}
