package dev.amenhancer.module.hook

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsbDirectNativeLibraryLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    private val bytes = byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 1, 2, 3)
    private val missing: (String) -> Unit = { throw UnsatisfiedLinkError("missing") }

    private fun apk(name: String, abi: String, compressed: Boolean = false, data: ByteArray = bytes): File {
        val file = File(temporary.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            val entry = ZipEntry("lib/$abi/libampp_audio.so")
            if (!compressed) {
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.crc = CRC32().apply { update(data) }.value
            }
            zip.putNextEntry(entry)
            zip.write(data)
            zip.closeEntry()
        }
        return file
    }

    @Test fun frameworkSuccessDoesNotTouchModuleFiles() {
        val result = UsbDirectNativeLibraryLoader.load("", emptyList(), listOf("arm64-v8a"), temporary.root,
            loadLibrary = { assertEquals("ampp_audio", it) }, loadAbsolute = { error("Unexpected fallback") })
        assertTrue(result.loaded)
        assertNull(result.message)
    }

    @Test fun installedNativeDirectoryIsUsedAfterFrameworkFailure() {
        val library = File(temporary.root, "libampp_audio.so").apply { writeBytes(bytes) }
        val calls = mutableListOf<String>()
        val result = UsbDirectNativeLibraryLoader.load(temporary.root.path, emptyList(), listOf("arm64-v8a"),
            temporary.root, missing, { calls += it })
        assertTrue(result.loaded)
        assertEquals(listOf(library.absolutePath), calls)
    }

    @Test fun storedLibraryCanLoadDirectlyFromSplitApk() {
        val base = apk("base.apk", "x86_64")
        val split = apk("split.apk", "arm64-v8a")
        val calls = mutableListOf<String>()
        val result = UsbDirectNativeLibraryLoader.load("", listOf(base.path, split.path), listOf("arm64-v8a"),
            temporary.root, missing, { calls += it })
        assertTrue(result.loaded)
        assertEquals(listOf("${split.absolutePath}!/lib/arm64-v8a/libampp_audio.so"), calls)
    }

    @Test fun failedApkLoadFallsBackToCompleteCachedLibrary() {
        val file = apk("base.apk", "arm64-v8a")
        val calls = mutableListOf<String>()
        val result = UsbDirectNativeLibraryLoader.load("", listOf(file.path), listOf("arm64-v8a"), temporary.root,
            missing, { path ->
                calls += path
                if (path.contains("!/")) throw UnsatisfiedLinkError("APK lookup failed")
                assertArrayEquals(bytes, File(path).readBytes())
            })
        assertTrue(result.loaded)
        assertEquals(2, calls.size)
        assertFalse(File(calls.last()).parentFile.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun compressedLibraryExtractsAndContentChangesInvalidateCache() {
        val file = apk("base.apk", "arm64-v8a", compressed = true)
        val calls = mutableListOf<String>()
        fun load(): Boolean = UsbDirectNativeLibraryLoader.load("", listOf(file.path), listOf("arm64-v8a"),
            temporary.root, missing, { calls += it }).loaded
        assertTrue(load())
        assertTrue(load())
        assertEquals(calls[0], calls[1])
        val updated = bytes + byteArrayOf(9)
        apk("base.apk", "arm64-v8a", compressed = true, data = updated)
        assertTrue(load())
        assertNotEquals(calls[0], calls[2])
        assertArrayEquals(updated, File(calls[2]).readBytes())
        assertFalse(calls.any { it.contains("!/") })
    }

    @Test fun wrongProcessAbiNeverLoadsIncompatibleLibrary() {
        val file = apk("base.apk", "arm64-v8a")
        val result = UsbDirectNativeLibraryLoader.load("", listOf(file.path), listOf("armeabi-v7a"),
            temporary.root, missing, { error("Must not load a 64-bit library in a 32-bit process") })
        assertFalse(result.loaded)
        assertTrue(result.message!!.contains("armeabi-v7a"))
    }

    @Test fun missingModuleDiagnosticDoesNotDumpClassLoader() {
        val result = UsbDirectNativeLibraryLoader.load("", listOf(File(temporary.root, "missing.apk").path),
            listOf("arm64-v8a"), temporary.root,
            { throw UnsatisfiedLinkError("LspModuleClassLoader " + "DEX".repeat(5000)) }, missing)
        assertFalse(result.loaded)
        assertTrue(result.message!!.length < 300)
        assertFalse(result.message.contains("DEX"))
        assertTrue(result.message.contains("missing.apk"))
    }

    @Test fun cachedLoadFailureIsReportedAndCanRetry() {
        val file = apk("base.apk", "arm64-v8a", compressed = true)
        val failed = UsbDirectNativeLibraryLoader.load("", listOf(file.path), listOf("arm64-v8a"),
            temporary.root, missing, { throw UnsatisfiedLinkError("dlopen failed: permission denied") })
        assertFalse(failed.loaded)
        assertTrue(failed.message!!.contains("permission denied"))
        val retried = UsbDirectNativeLibraryLoader.load("", listOf(file.path), listOf("arm64-v8a"),
            temporary.root, missing, { assertArrayEquals(bytes, File(it).readBytes()) })
        assertTrue(retried.loaded)
    }
}
