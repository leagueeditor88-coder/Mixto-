package com.example.automix

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
    .addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_RELEASE
    )
    .build()

/**
 * Fachada Media3 del motor de Automix.
 *
 * El motor real (dos MediaPlayer, cola, precarga y crossfade) sigue viviendo en [AutomixService].
 * Esta clase solo le cuenta a la MediaSession qué canción suena, si está en pausa y en qué
 * posición va, y traduce los controles de la notificación / pantalla de bloqueo en llamadas al
 * motor. Así Android gestiona la notificación sin tocar el crossfade.
 *
 * La lista expuesta tiene 3 elementos [anterior, actual, siguiente]. Los de los extremos son
 * marcadores: existen para que Media3 muestre los botones anterior/siguiente y para saber, en
 * [handleSeek], qué quiso hacer el usuario. La cola real nunca se entrega a Media3.
 */
class AutomixPlayer(private val service: AutomixService) : SimpleBasePlayer(Looper.getMainLooper()) {

    private companion object {
        const val PREV_UID = "automix:prev"
        const val NEXT_UID = "automix:next"
        const val CURRENT_INDEX = 1
    }

    /** Fuerza a Media3 a releer el estado del motor. Llamar siempre desde el hilo principal. */
    fun refresh() {
        invalidateState()
    }

    override fun getState(): SimpleBasePlayer.State {
        val builder = SimpleBasePlayer.State.Builder().setAvailableCommands(AVAILABLE_COMMANDS)
        val track = service.current ?: return builder.setPlaybackState(Player.STATE_IDLE).build()

        val progress = service.getProgress()
        val durationMs = if (progress.second > 0) progress.second.toLong() else track.durationMs

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.takeIf { it.isNotBlank() })
            .setIsPlayable(true)
            .also { b ->
                service.currentArtwork?.let { b.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()

        val currentItem = SimpleBasePlayer.MediaItemData.Builder("automix:" + track.uri.toString())
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(track.uri.toString())
                    .setMediaMetadata(metadata)
                    .build()
            )
            .setMediaMetadata(metadata)
            .setDurationUs(if (durationMs > 0) durationMs * 1000L else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()

        val playlist = listOf(placeholder(PREV_UID), currentItem, placeholder(NEXT_UID))

        return builder
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(!service.paused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(CURRENT_INDEX)
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier { service.getProgress().first.toLong() })
            .build()
    }

    private fun placeholder(uid: String): SimpleBasePlayer.MediaItemData =
        SimpleBasePlayer.MediaItemData.Builder(uid)
            .setMediaItem(MediaItem.Builder().setMediaId(uid).build())
            .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        service.setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when {
            mediaItemIndex > CURRENT_INDEX -> service.next()
            mediaItemIndex < CURRENT_INDEX -> service.previous()
            else -> {
                val target = if (positionMs == C.TIME_UNSET) 0L else positionMs
                service.seekTo(target.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
