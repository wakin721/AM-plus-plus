package dev.amenhancer.module.hook

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * The USB engine is invisible to Android's playback monitor. A silent, looping
 * track in the same process preserves the framework's real playing state while
 * USB owns the music track. It contains only zeros and explicitly prefers the
 * built-in speaker; unexpected routing is treated as a takeover failure.
 */
internal class UsbDirectPlaybackKeepAlive(private val context: Context) {
    private var track: AudioTrack? = null

    fun start(): Boolean = UsbDirectUacController.withInternalTransition {
        runCatching {
            val output = track ?: createTrack().also { track = it }
            output.play()
            check(hasSafeRoute()) { "Keep-alive left the built-in speaker route" }
            true
        }.getOrElse {
            ModernXposedRuntime.log("usb_direct: framework playback keep-alive failed", it)
            close()
            false
        }
    }

    fun hasSafeRoute(): Boolean = track?.routedDevice?.type.let {
        it == null || it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    private fun createTrack(): AudioTrack {
        val manager = checkNotNull(context.getSystemService(AudioManager::class.java))
        val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: error("No built-in speaker for USB playback keep-alive")
        val frames = 4_800
        val silence = ByteArray(frames * 2)
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(silence.size).build()
        try {
            check(output.setPreferredDevice(speaker)) { "Cannot route keep-alive to built-in speaker" }
            check(output.setVolume(0f) == AudioTrack.SUCCESS) { "Cannot mute keep-alive" }
            check(output.write(silence, 0, silence.size) == silence.size) { "Cannot load keep-alive silence" }
            check(output.setLoopPoints(0, frames, -1) == AudioTrack.SUCCESS) { "Cannot loop keep-alive silence" }
            return output
        } catch (error: Throwable) {
            output.release()
            throw error
        }
    }

    fun pause() = UsbDirectUacController.withInternalTransition {
        runCatching { track?.pause() }
            .onFailure { ModernXposedRuntime.log("usb_direct: keep-alive pause failed", it) }
        Unit
    }

    fun close() = UsbDirectUacController.withInternalTransition {
        val output = track
        track = null
        runCatching { output?.release() }
            .onFailure { ModernXposedRuntime.log("usb_direct: keep-alive release failed", it) }
        Unit
    }
}
