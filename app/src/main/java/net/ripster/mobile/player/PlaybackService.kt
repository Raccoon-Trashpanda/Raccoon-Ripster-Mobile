package net.ripster.mobile.player

import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Фоновый плеер на ExoPlayer + MediaSession. Отсюда «бесплатно» приходят:
 * управление с BT-гарнитуры и медиа-кнопок, аудиофокус (пауза, когда чужое
 * приложение перебивает), пауза при выдёргивании наушников
 * (`setHandleAudioBecomingNoisy`). Вывод на BT/USB/внешние устройства —
 * системная маршрутизация Android поверх этого же плеера.
 *
 * Кастинг на Яндекс Станции — отдельный слой поверх (протокол Glagol),
 * здесь не реализован.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // HTTP-источник для потокового воспроизведения (стрим релиза/станции без
        // скачивания): десктопный UA (нужен SoundCloud), редиректы CDN разрешены.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Свой audio session id, известный заранее → к нему цепляем эффекты
        // (эквалайзер / бас-буст / виртуализатор / громкость).
        runCatching {
            val sid = (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
            player.setAudioSessionId(sid)
            AudioEffects.init(applicationContext)
            AudioEffects.bind(sid)
        }

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        runCatching { AudioEffects.release() }
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
