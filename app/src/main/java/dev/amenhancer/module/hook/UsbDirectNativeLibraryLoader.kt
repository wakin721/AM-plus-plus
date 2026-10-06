package dev.amenhancer.module.hook

import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Loads the module's USB JNI library even when the framework's lookup cannot find it. */
internal object UsbDirectNativeLibraryLoader {
    private const val LIBRARY_NAME = "ampp_audio"
    private const val LIBRARY_FILE = "libampp_audio.so"

    data class Result(val loaded: Boolean, val message: String? = null, val cause: Throwable? = null)

    fun load(
        nativeLibraryDir: String,
        moduleApkPaths: List<String>,
        processAbis: List<String>,
        cacheDir: File,
        loadLibrary: (String) -> Unit = { System.loadLibrary(it) },
        loadAbsolute: (String) -> Unit = { System.load(it) },
    ): Result {
        val failures = mutableListOf<String>()
        var lastError: Throwable? = null
        fun attempt(label: String, action: () -> Unit, includeMessage: Boolean = true): Boolean {
            return runCatching { action() }.fold(
                onSuccess = { true },
                onFailure = { error ->
                    lastError = error
                    // Class-loader lookup errors can contain an entire in-memory DEX dump.
                    val detail = if (includeMessage) error.message?.take(180) else null
                    failures += "$label: ${detail ?: error.javaClass.simpleName}"
                    false
                },
            )
        }
        if (attempt("framework lookup", { loadLibrary(LIBRARY_NAME) }, includeMessage = false)) {
            return Result(true)
        }
        if (nativeLibraryDir.isNotBlank()) {
            val extracted = File(nativeLibraryDir, LIBRARY_FILE)
            if (extracted.isFile && attempt("installed library", { loadAbsolute(extracted.absolutePath) })) {
                return Result(true)
            }
        }
        for (apkPath in moduleApkPaths.distinct()) {
            val apk = File(apkPath)
            if (!apk.isFile) {
                failures += "module APK unavailable: ${apk.name}"
                continue
            }
            try {
                ZipFile(apk).use { zip ->
                    for (abi in processAbis.distinct()) {
                        val entryPath = "lib/$abi/$LIBRARY_FILE"
                        val entry = zip.getEntry(entryPath) ?: continue
                        if (entry.method == ZipEntry.STORED &&
                            attempt("APK library ($abi)", { loadAbsolute("${apk.absolutePath}!/$entryPath") })
                        ) {
                            return Result(true)
                        }
                        val extracted = extract(zip, entry, File(cacheDir, "ampp-usb-native"), abi)
                        if (attempt("cached library ($abi)", { loadAbsolute(extracted.absolutePath) })) {
                            return Result(true)
                        }
                    }
                }
            } catch (error: Exception) {
                lastError = error
                failures += "read module APK ${apk.name}: ${error.message?.take(180) ?: error.javaClass.simpleName}"
            }
        }
        val abiLabel = processAbis.joinToString().ifBlank { "no process ABI" }
        return Result(
            false,
            "$LIBRARY_FILE ($abiLabel) unavailable; ${failures.takeLast(3).joinToString("; ")}",
            lastError,
        )
    }

    private fun extract(zip: ZipFile, entry: ZipEntry, directory: File, abi: String): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create native cache")
        }
        val temporary = File.createTempFile("usb-native-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { input ->
                DigestInputStream(input, digest).use { hashed ->
                    temporary.outputStream().use { output -> hashed.copyTo(output) }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(directory, "$abi-$hash-$LIBRARY_FILE")
            // Version the cache by library content and publish only a complete, read-only file.
            if (!target.isFile) {
                if (!temporary.setReadOnly()) throw IOException("Cannot protect native cache file")
                if (!temporary.renameTo(target) && !target.isFile) throw IOException("Cannot publish native cache file")
            }
            return target
        } finally {
            temporary.delete()
        }
    }
}
