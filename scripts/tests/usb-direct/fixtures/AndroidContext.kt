package android.content
import android.os.*
import android.content.pm.PackageManager
import android.media.AudioManager
class ComponentName(val pkg: String, val name: String)
class Intent { fun setComponent(value: ComponentName) = this }
interface ServiceConnection {
 fun onServiceConnected(name: ComponentName?, service: IBinder?)
 fun onServiceDisconnected(name: ComponentName?)
 fun onBindingDied(name: ComponentName?)
 fun onNullBinding(name: ComponentName?)
}
open class Context {
 val connections = mutableListOf<ServiceConnection>()
 var bindingSucceeds = true
 val applicationContext: Context get() = this
 val packageManager = PackageManager()
 val powerManager = PowerManager()
 fun <T> getSystemService(type: Class<T>): T? = type.cast(
  if (type == PowerManager::class.java) powerManager else AudioManager())
 fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
  connections.add(connection)
  if (!bindingSucceeds) return false
  connection.onServiceConnected(null, Binder()); return true
 }
 fun unbindService(connection: ServiceConnection) {}
 companion object { const val BIND_AUTO_CREATE = 1 }
}
