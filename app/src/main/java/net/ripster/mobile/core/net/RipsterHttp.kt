package net.ripster.mobile.core.net

import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Единственный HTTP-клиент приложения. Причина одного клиента, а не «new
 * OkHttpClient() по месту»: пул соединений, троттл на 429 и таймауты должны
 * быть общими — Spotify-бан 23.08 (12 часов) случился ровно потому, что
 * фоллбэк долбил сервис без единого места, где стоит тормоз на 429.
 *
 * Пер-сервисные заголовки (User-Agent, авторизация) навешивает сам клиент
 * сервиса своим интерцептором поверх этого.
 */
object RipsterHttp {

    /** С запасом: сервисы отдают FLAC-альбомы, и это долгие тела. */
    private const val CALL_TIMEOUT_MIN = 30L
    private const val CONNECT_TIMEOUT_S = 20L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_MIN, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .addInterceptor(RateLimitBackoffInterceptor())
            .build()
    }

    /**
     * На 429 (и 503 с Retry-After) — подождать и повторить, а не пробросить
     * ошибку выше, где её обычно превращают в «сервис недоступен» и повторяют
     * циклом без паузы. Не более [MAX_RETRIES] попыток, потолок паузы [MAX_WAIT_MS].
     */
    private class RateLimitBackoffInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var attempt = 0
            var response = chain.proceed(chain.request())
            while ((response.code == 429 || response.code == 503) && attempt < MAX_RETRIES) {
                val waitMs = retryAfterMs(response) ?: backoffMs(attempt)
                response.close()
                try {
                    Thread.sleep(waitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted while backing off from HTTP ${response.code}", e)
                }
                attempt++
                response = chain.proceed(chain.request())
            }
            return response
        }

        private fun retryAfterMs(response: Response): Long? =
            response.header("Retry-After")?.toLongOrNull()?.let { min(it * 1000, MAX_WAIT_MS) }

        private fun backoffMs(attempt: Int): Long =
            min(BASE_WAIT_MS * (1L shl attempt), MAX_WAIT_MS)

        companion object {
            const val MAX_RETRIES = 4
            const val BASE_WAIT_MS = 2_000L
            const val MAX_WAIT_MS = 30_000L
        }
    }
}
