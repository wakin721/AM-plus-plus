package android.os
class PowerManager {
 val locks = mutableListOf<WakeLock>()
 fun newWakeLock(level: Int, tag: String) = WakeLock().also { locks += it }
 class WakeLock {
  var isHeld=false
  var acquisitions=0
  var timeout=0L
  fun setReferenceCounted(value: Boolean) {}
  fun acquire(timeout: Long) { this.timeout=timeout; isHeld=true; acquisitions++ }
  fun release() { isHeld=false }
 }
 companion object { const val PARTIAL_WAKE_LOCK=1 }
}
object Build {
 object VERSION { const val SDK_INT = 37 }
 object VERSION_CODES { const val P=28; const val Q=29; const val TIRAMISU=33 }
}
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(val looper: Looper, val callback: ((Message)->Boolean)? = null) {
 fun post(task: ()->Unit) { posted.add(task) }
 companion object {
  val posted = mutableListOf<() -> Unit>()
  fun runPosted() { while (posted.isNotEmpty()) posted.removeAt(0).invoke() }
 }
}
interface IBinder
class Binder: IBinder
class Messenger {
 val handler: Handler?
 constructor(handler: Handler) { this.handler=handler }
 constructor(binder: IBinder) { this.handler=null }
 fun send(message: Message) { if(handler == null) outgoing.add(message) else handler.callback!!.invoke(message) }
 companion object { val outgoing = mutableListOf<Message>() }
}
class Message {
 var what = 0
 var replyTo: Messenger? = null
 var data = Bundle()
 companion object { fun obtain(unused: Any?, what: Int) = Message().apply { this.what=what } }
}
class Bundle {
 private val values = mutableMapOf<String,Any?>()
 fun putInt(key: String, value: Int) { values[key]=value }
 fun putLong(key: String, value: Long) { values[key]=value }
 fun getLong(key: String): Long = values[key] as? Long ?: 0L
 fun getInt(key: String, default: Int=0): Int = values[key] as? Int ?: default
 fun putBoolean(key: String, value: Boolean) { values[key]=value }
 fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false
 fun putString(key: String, value: String) { values[key]=value }
 fun getString(key: String): String? = values[key] as? String
 fun putParcelable(key: String, value: Any) { values[key]=value }
 fun getParcelable(key: String): Any? = values[key]
}
class ParcelFileDescriptor(val fd: Int) { var closed=false; fun close() { closed=true } }
