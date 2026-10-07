package dev.amenhancer.module.hook

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Exact 1606 Compose material adapter. No host Modifier, function or Unit enters module Compose. */
object ChromeGlassFactory {
    fun supports(build: TargetBuild): Boolean = AppleMusicHostProfiles.find(build.packageName,
        build.versionName, build.versionCode)?.let {
        it.productionEnabled && it.capability("glass") && it.document.has("chromeGlass")
    } == true

    fun install(loader: ClassLoader, build: TargetBuild, painter: ChromeGlassPainter): HostSubscription {
        val names = checkNotNull(AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode))
            .document.getJSONObject("chromeGlass")
        fun type(name: String): Class<*> = when (name) {
            "void" -> Void.TYPE; "int" -> Integer.TYPE; "long" -> java.lang.Long.TYPE
            "float" -> java.lang.Float.TYPE; "boolean" -> java.lang.Boolean.TYPE
            else -> loader.loadClass(name)
        }
        // Resolve the complete contract before installing any callback.
        val methods = names.getJSONObject("methods").let { all -> all.keys().asSequence().associateWith { key ->
            val c = all.getJSONObject(key)
            val args = c.getJSONArray("parameters").let { a -> Array(a.length()) { type(a.getString(it)) } }
            type(c.getString("owner")).getDeclaredMethod(c.getString("name"), *args).apply {
                check(returnType == type(c.getString("returns")) && Modifier.isStatic(modifiers) == c.getBoolean("static"))
                isAccessible = true
            }
        } }
        val fields = names.getJSONObject("fields").let { all -> all.keys().asSequence().associateWith { key ->
            val c = all.getJSONObject(key)
            type(c.getString("owner")).getDeclaredField(c.getString("name")).apply {
                check(this.type == type(c.getString("type")) && Modifier.isStatic(modifiers) == c.getBoolean("static"))
                isAccessible = true
            }
        } }
        val hostFunction = type(names.getString("functionClass"))
        val unit = fields.getValue("unit").get(null)
        val emptyModifier = fields.getValue("emptyModifier").get(null)
        val scope = HookRegistrationScope()
        data class Material(val source: View, val popup: Boolean, var replaced: Boolean = false)
        val material = ThreadLocal<Material?>()
        fun source(composer: Any): View = methods.getValue("consume").invoke(composer,
            fields.getValue("localView").get(null)) as View
        fun hook(key: String, callback: ModernMethodHook) {
            check(ModernXposedRuntime.hookMethod(methods.getValue(key), callback, scope))
        }
        fun drawModifier(modifier: Any, view: View, popup: Boolean, nativeColor: Int): Any {
            painter.prepare(view)
            val reference = WeakReference(view)
            val identity = Any()
            val callback = Proxy.newProxyInstance(loader, arrayOf(hostFunction)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "AM++ native chrome material"
                    "invoke" -> {
                        val drawScope = args?.firstOrNull() ?: return@newProxyInstance unit
                        if (painter.capturing) return@newProxyInstance unit // Omit this consumer and its icon from the source.
                        val nativeSource = reference.get()
                        if (nativeSource != null) {
                            runCatching {
                                val context = methods.getValue("drawContext").invoke(drawScope)
                                val canvas = methods.getValue("nativeCanvas").invoke(null,
                                    methods.getValue("canvas").invoke(context)) as Canvas
                                val packed = methods.getValue("size").invoke(context) as Long
                                val width = Float.fromBits((packed ushr 32).toInt())
                                val height = Float.fromBits(packed.toInt())
                                // RecordingCanvas matrices omit ancestor RenderNode transforms.
                                // Read the draw modifier's native coordinates, including popup origins.
                                val node = fields.getValue("drawNode").get(drawScope)
                                val coordinates = methods.getValue("coordinator").invoke(null, node, 4)
                                val coordinateReference = WeakReference(coordinates)
                                val localToScreen = geometry@{
                                    val current = coordinateReference.get() ?: return@geometry null
                                    if (reference.get()?.isShown != true || methods.getValue("attached").invoke(current) != true) return@geometry null
                                    fun point(x: Float, y: Float): Pair<Float, Float> {
                                    val offset = (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xffffffffL)
                                    val result = methods.getValue("toScreen").invoke(current, offset) as Long
                                    return Float.fromBits((result ushr 32).toInt()) to Float.fromBits(result.toInt())
                                    }
                                    val origin = point(0f, 0f)
                                    val x = point(1f, 0f)
                                    val y = point(0f, 1f)
                                    Matrix().apply { setValues(floatArrayOf(
                                    x.first - origin.first, y.first - origin.first, origin.first,
                                    x.second - origin.second, y.second - origin.second, origin.second, 0f, 0f, 1f)) }
                                }
                                painter.draw(identity, nativeSource, canvas, localToScreen, width, height, popup, nativeColor)
                            }.onFailure { ModernXposedRuntime.log("chrome glass draw failed open", it) }
                        }
                        methods.getValue("drawContent").invoke(drawScope)
                        unit
                    }
                    else -> error("Unexpected host function method: $method")
                }
            }
            return methods.getValue("drawWithContent").invoke(null, modifier, callback)
        }
        fun composition(key: String, composerIndex: Int, popup: Boolean) {
            hook(key, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.extras["previousMaterial"] = material.get()
                    material.set(runCatching { Material(source(checkNotNull(param.args[composerIndex])), popup) }.getOrNull())
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    material.set(param.extras["previousMaterial"] as? Material)
                }
            })
        }
        try {
            // A software source traversal must copy hardware artwork before drawing it.
            // Scope this bridge to capture only; native playback/UI keeps its original bitmaps.
            val copies = LinkedHashMap<Bitmap, Bitmap>(8, .75f, true)
            var copyBytes = 0L
            val copyLimit = 16L * 1024 * 1024
            val bitmapHook = object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!painter.capturing || (param.thisObject as? Canvas)?.isHardwareAccelerated != false) return
                    val image = param.args[0] as? Bitmap ?: return
                    if (image.config != Bitmap.Config.HARDWARE) return
                    val copy = copies[image] ?: image.copy(Bitmap.Config.ARGB_8888, false) ?: return
                    if (copy.allocationByteCount <= copyLimit && !copies.containsKey(image)) {
                        while (copies.isNotEmpty() && copyBytes + copy.allocationByteCount > copyLimit) {
                            val entry = copies.entries.iterator().next()
                            copyBytes -= entry.value.allocationByteCount
                            entry.value.recycle(); copies.remove(entry.key)
                        }
                        copies[image] = copy; copyBytes += copy.allocationByteCount
                    } else if (copy.allocationByteCount > copyLimit) param.extras["temporaryBitmap"] = copy
                    param.args[0] = copy
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.extras["temporaryBitmap"] as? Bitmap)?.recycle()
                }
            }
            listOf(
                arrayOf(Bitmap::class.java, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Paint::class.java),
                arrayOf(Bitmap::class.java, Rect::class.java, Rect::class.java, Paint::class.java),
                arrayOf(Bitmap::class.java, Rect::class.java, RectF::class.java, Paint::class.java),
            ).forEach { parameters ->
                check(ModernXposedRuntime.hookMethod(Canvas::class.java.getDeclaredMethod("drawBitmap", *parameters), bitmapHook, scope))
            }
            scope.onClose { copies.values.forEach(Bitmap::recycle); copies.clear() }
            composition("button", 3, false)
            composition("menu", 2, true)
            hook("background", object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = material.get()?.takeUnless { it.popup || it.replaced } ?: return
                    // Host sRGB Color uses the ARGB value in the high word; reject other encodings.
                    val color = param.args[1] as Long
                    if (color and 63L != 0L) return
                    runCatching { drawModifier(checkNotNull(param.args[0]), owner.source, false, (color ushr 32).toInt()) }
                        .onSuccess { param.result = it; owner.replaced = true }
                        .onFailure { ModernXposedRuntime.log("chrome glass button kept native", it) }
                }
            })
            hook("border", object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (material.get()?.let { !it.popup && it.replaced } == true) param.result = param.args[2]
                }
            })
            hook("dropdown", object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = material.get()?.takeIf { it.popup } ?: return
                    val color = param.args[7] as Long
                    if (color and 63L != 0L) return
                    runCatching {
                        drawModifier(param.args[2] ?: emptyModifier, owner.source, true, (color ushr 32).toInt())
                    }.onSuccess { modifier ->
                        param.args[2] = modifier
                        param.args[6] = methods.getValue("roundedShape").invoke(null, 24f)
                        param.args[7] = 0L // Transparent sRGB surface: the glass modifier owns the background.
                        param.args[8] = 0f // No tonal overlay may make the surface opaque.
                        param.args[13] = (param.args[13] as Int) and (4 or 64 or 128 or 256).inv()
                    }.onFailure { ModernXposedRuntime.log("chrome glass menu kept native", it) }
                }
            })
            scope.activate()
            return HostSubscription { scope.close() }
        } catch (error: Throwable) { scope.close(); throw error }
    }
}
