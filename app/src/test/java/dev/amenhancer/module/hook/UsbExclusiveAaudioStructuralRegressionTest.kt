package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveAaudioStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `native bridge requests and verifies real AAudio exclusive mode`() {
        val native = projectFile(
            "app/src/main/cpp/UsbExclusiveAaudio.cpp",
        )
        val cmake = projectFile("app/src/main/cpp/CMakeLists.txt")
        val gradle = projectFile("app/build.gradle.kts")

        assertTrue(native.contains("AAUDIO_SHARING_MODE_EXCLUSIVE"))
        assertTrue(native.contains("AAudioStreamBuilder_setDeviceId"))
        assertTrue(native.contains("AAudioStream_getSharingMode(stream) != AAUDIO_SHARING_MODE_EXCLUSIVE"))
        assertTrue(native.contains("AAudioStream_getDeviceId(stream) != deviceId"))
        assertTrue(native.contains("AAudioStream_getSampleRate(stream) != sampleRate"))
        assertTrue(native.contains("AAudioStream_getFormat(stream) != requestedFormat"))
        assertTrue(native.contains("AAudioStream_write"))
        assertTrue(native.contains("AAudioStream_requestFlush"))
        assertTrue(native.contains("AAudioStream_waitForStateChange"))
        assertTrue(native.indexOf("AAudioStream_write prefill") < native.indexOf("AAudioStream_requestStart"))
        assertTrue(native.contains("attenuatePcm"))
        assertTrue(native.contains("gainLeft"))
        assertTrue(native.contains("gainRight"))
        assertTrue(cmake.contains("find_library(aaudio_lib aaudio)"))
        assertTrue(gradle.contains("src/main/cpp/CMakeLists.txt"))
    }

    @Test
    fun `takeover observes Java PCM and fails open to the original AudioTrack`() {
        val feature = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbBitPerfectFeature.kt",
        )
        val controller = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbExclusiveAaudioController.kt",
        )
        val model = projectFile(
            "app/src/main/java/dev/amenhancer/module/model/ModuleModels.kt",
        )
        val schema = projectFile(
            "app/src/main/java/dev/amenhancer/module/config/ModuleSettingsSchema.kt",
        )

        assertTrue(feature.contains("hookAllMethods(\n            AudioTrack::class.java,\n            \"write\""))
        assertTrue(feature.contains("UsbExclusiveAaudioController.interceptWrite"))
        assertTrue(feature.contains("listOf(\"setVolume\", \"setStereoVolume\")"))
        assertTrue(feature.contains("UsbExclusiveAaudioController.afterVolumeChange"))
        assertTrue(controller.contains("isSupportedWrite(track, args)"))
        assertTrue(controller.contains("UsbExclusiveVolumePolicy.streamGain"))
        assertTrue(controller.contains("getStreamVolumeDb"))
        assertTrue(controller.contains("track.pause()"))
        assertTrue(controller.contains("track.flush()"))
        assertTrue(controller.contains("Drop the old exclusive queue before new PCM plays"))
        assertTrue(controller.contains("Release that stale exclusive owner"))
        assertTrue(controller.contains("runCatching { track.play() }"))
        assertTrue(controller.contains("已恢复原 AudioTrack"))
        assertTrue(model.contains("usbExclusiveAaudioEnabled: Boolean = false"))
        assertTrue(schema.contains("usb_exclusive_aaudio_enabled"))
    }
}
