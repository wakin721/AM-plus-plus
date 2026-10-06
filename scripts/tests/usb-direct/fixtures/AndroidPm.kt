package android.content.pm
import android.content.Intent
class PackageManager {
 class ResolveInfoFlags { companion object { fun of(value: Long) = ResolveInfoFlags() } }
 fun resolveService(intent: Intent, flags: Int): Any? = Any()
 fun resolveService(intent: Intent, flags: ResolveInfoFlags): Any? = Any()
}
