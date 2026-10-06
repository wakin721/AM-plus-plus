package android.media
class AudioAttributes(val usage: Int=USAGE_MEDIA) { companion object { const val USAGE_MEDIA=1 } }
class AudioDeviceInfo(val type: Int=TYPE_USB_DEVICE) { companion object { const val TYPE_USB_DEVICE=11 } }
class AudioFormat(val sampleRate: Int=48000, val encoding: Int=ENCODING_PCM_16BIT, val channelCount: Int=2) {
 companion object { const val ENCODING_PCM_16BIT=2; const val ENCODING_PCM_FLOAT=4; const val ENCODING_PCM_24BIT_PACKED=21; const val ENCODING_PCM_32BIT=22 }
}
class AudioTrack(val format: AudioFormat=AudioFormat()) {
 val audioAttributes=AudioAttributes()
 val routedDevice=AudioDeviceInfo()
 var playState=PLAYSTATE_PLAYING
 var playbackHeadPosition=0
 var playCalls=0
 fun pause() { playState=PLAYSTATE_PAUSED }
 fun flush() {}
 fun play() { playCalls++; playState=PLAYSTATE_PLAYING }
 fun stop() { playState=PLAYSTATE_STOPPED }
 companion object { const val SUCCESS=0; const val PLAYSTATE_PLAYING=3; const val PLAYSTATE_PAUSED=2; const val PLAYSTATE_STOPPED=1; const val WRITE_NON_BLOCKING=1; const val WRITE_BLOCKING=0 }
}
class AudioTimestamp { var framePosition=0L; var nanoTime=0L }
class AudioManager {
 fun getStreamMaxVolume(stream: Int)=15
 fun getStreamVolume(stream: Int)=15
 fun isStreamMute(stream: Int)=false
 fun getStreamVolumeDb(stream: Int, index: Int, type: Int)=0f
 companion object { const val STREAM_MUSIC=3 }
}
