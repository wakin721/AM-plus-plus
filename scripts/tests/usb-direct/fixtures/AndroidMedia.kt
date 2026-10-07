package android.media
class AudioAttributes(val usage: Int=USAGE_MEDIA) {
 class Builder { fun setUsage(value: Int)=this; fun setContentType(value: Int)=this; fun build()=AudioAttributes() }
 companion object { const val USAGE_MEDIA=1; const val CONTENT_TYPE_MUSIC=2 }
}
class AudioDeviceInfo(val type: Int=TYPE_USB_DEVICE) { companion object { const val TYPE_USB_DEVICE=11; const val TYPE_BUILTIN_SPEAKER=2 } }
class AudioFormat(val sampleRate: Int=48000, val encoding: Int=ENCODING_PCM_16BIT, val channelCount: Int=2) {
 class Builder { fun setSampleRate(value: Int)=this; fun setEncoding(value: Int)=this; fun setChannelMask(value: Int)=this; fun build()=AudioFormat() }
 companion object { const val CHANNEL_OUT_MONO=4; const val ENCODING_PCM_16BIT=2; const val ENCODING_PCM_FLOAT=4; const val ENCODING_PCM_24BIT_PACKED=21; const val ENCODING_PCM_32BIT=22 }
}
class AudioTrack(val format: AudioFormat=AudioFormat()) {
 val audioAttributes=AudioAttributes()
 var routedDevice=AudioDeviceInfo()
 var playState=PLAYSTATE_PLAYING
 var playbackHeadPosition=0
 var playCalls=0
 var writeInFlight=false
 var releaseCalls=0
 var volume=1f
 var preferredDevice: AudioDeviceInfo?=null
 var loadedSilence=false
 var loopCount=0
 var loopFrames=0
 fun setPreferredDevice(device: AudioDeviceInfo): Boolean { preferredDevice=device; if (acceptsRouting) routedDevice=device; return acceptsRouting }
 fun setVolume(value: Float): Int { volume=value; return SUCCESS }
 fun write(data: ByteArray, offset: Int, size: Int): Int { loadedSilence=data.sliceArray(offset until offset+size).all { it == 0.toByte() }; return size }
 fun setLoopPoints(start: Int, end: Int, loops: Int): Int { loopFrames=end-start; loopCount=loops; return SUCCESS }
 fun release() { releaseCalls++; playState=PLAYSTATE_STOPPED }
 fun pause() { check(!writeInFlight) { "Cannot interrupt original PCM write" }; playState=PLAYSTATE_PAUSED }
 fun flush() {}
 fun play() { playCalls++; playState=PLAYSTATE_PLAYING }
 fun stop() { playState=PLAYSTATE_STOPPED }
 class Builder {
  fun setAudioAttributes(value: AudioAttributes)=this
  fun setAudioFormat(value: AudioFormat)=this
  fun setTransferMode(value: Int)=this
  fun setBufferSizeInBytes(value: Int)=this
  fun build()=AudioTrack().apply { playState=PLAYSTATE_STOPPED; keepAliveTracks.add(this) }
 }
 companion object {
  val keepAliveTracks=mutableListOf<AudioTrack>()
  var acceptsRouting=true
  const val MODE_STATIC=0; const val SUCCESS=0; const val PLAYSTATE_PLAYING=3; const val PLAYSTATE_PAUSED=2; const val PLAYSTATE_STOPPED=1; const val WRITE_NON_BLOCKING=1; const val WRITE_BLOCKING=0
 }
}
class AudioTimestamp { var framePosition=0L; var nanoTime=0L }
class AudioManager {
 var outputs=arrayOf(AudioDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
 var mediaVolume=15
 var mediaMuted=false
 fun getDevices(flags: Int)=outputs
 fun getStreamMaxVolume(stream: Int)=15
 fun getStreamVolume(stream: Int)=mediaVolume
 fun isStreamMute(stream: Int)=mediaMuted
 fun getStreamVolumeDb(stream: Int, index: Int, type: Int)=0f
 companion object { const val STREAM_MUSIC=3; const val GET_DEVICES_OUTPUTS=2 }
}
