package net.ripster.mobile.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import net.ripster.mobile.core.db.LibraryEntity

/**
 * Медиасессия, ведомая нативным движком.
 *
 * Сессию всегда вёл ExoPlayer. Когда включён нативный тракт, Exo остановлен —
 * и на экране блокировки, в шторке и в машине по Bluetooth висел ПРОШЛЫЙ трек
 * из его очереди, а кнопки управляли не тем, что звучит. Поймано 05.09.2026
 * при проверке движка: играло «Shattered Memories», система показывала «Daft
 * Punk — Around The World».
 *
 * Здесь ровно то, что нужно сессии: список, текущая позиция и команды. Никакого
 * своего воспроизведения — все действия уходят в [NativeAudioEngine] через
 * переданные обработчики, поэтому кнопка «пауза» на экране блокировки ставит на
 * паузу именно то, что играет.
 *
 * Подменяется только на время работы нативного тракта: обычный путь
 * (ExoPlayer + весь стриминг) остаётся нетронутым.
 */
@UnstableApi
class NativeSessionPlayer(
    looper: Looper,
    /** Очередь нативного движка — источник названий и обложек. */
    private val queue: () -> List<LibraryEntity>,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onStop: () -> Unit,
) : SimpleBasePlayer(looper) {

    private val commands: Player.Commands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
        )
        .build()

    private fun itemOf(e: LibraryEntity): MediaItemData {
        val meta = MediaMetadata.Builder()
            .setTitle(e.title)
            .setArtist(e.artist)
            .setAlbumTitle(e.album)
            .apply { e.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        return MediaItemData.Builder(e.id)
            .setMediaItem(MediaItem.Builder().setMediaId(e.id).setMediaMetadata(meta).build())
            .setMediaMetadata(meta)
            // Длительность известна из заголовка файла; 0 значит «неизвестно»,
            // и подсовывать вместо неё выдуманную нельзя — по ней рисуется шкала.
            .setDurationUs(e.durationSec.takeIf { it > 0 }?.let { it * 1_000_000L } ?: C_TIME_UNSET)
            .build()
    }

    override fun getState(): State {
        val items = queue()
        val idx = NativeAudioEngine.index().coerceIn(0, (items.size - 1).coerceAtLeast(0))
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(
                NativeAudioEngine.isPlaying(),
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlaylist(ImmutableList.copyOf(items.map { itemOf(it) }))
            .setCurrentMediaItemIndex(idx)
            .setContentPositionMs { NativeAudioEngine.positionMs() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) onPlay() else onPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        onStop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onPrevious()
            else -> if (positionMs >= 0) onSeek(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    /** Позвать, когда движок сменил трек/состояние: сессия перечитает [getState]. */
    fun refresh() = invalidateState()

    private companion object {
        /** `C.TIME_UNSET` без тяги всего `C` в этот файл. */
        const val C_TIME_UNSET = Long.MIN_VALUE + 1
    }
}
