package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDirectUacStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `USB Host broker lends fd while native session owns interface lifecycle`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val permission = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectPermissionActivity.kt",
        )
        val broker = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt",
        )
        val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")

        assertTrue(manifest.contains("android.hardware.usb.host"))
        assertTrue(permission.contains("manager.requestPermission(device, permissionIntent)"))
        assertTrue(broker.contains("message.sendingUid"))
        assertTrue(broker.contains("ModuleConstants.TARGET_PACKAGE"))
        assertTrue(broker.contains("ParcelFileDescriptor.fromFd(connection.fileDescriptor)"))

        assertFalse(broker.contains("connection.claimInterface("))
        assertFalse(broker.contains("connection.setInterface("))
        assertFalse(broker.contains("UsbUacSampleRateControl.configureUac2"))
        assertFalse(broker.contains("connection.controlTransfer("))
        assertFalse(broker.contains("UsbUacSampleRateControl"))
        assertFalse(broker.contains("ClaimedSession"))
        assertFalse(broker.contains("releaseClaims("))

        assertTrue(native.contains("USBDEVFS_CLAIMINTERFACE"))
        assertTrue(native.contains("USBDEVFS_SETINTERFACE"))
        assertTrue(native.contains("USBDEVFS_RELEASEINTERFACE"))
        assertTrue(native.contains("USBDEVFS_CONTROL"))
    }

    @Test
    fun `broker client follows the actual debug or release application id`() {
        val client = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt",
        )
        val gradle = projectFile("app/build.gradle.kts")

        assertTrue(gradle.contains("applicationIdSuffix = \".debug\""))
        assertTrue(gradle.contains("buildConfig = true"))
        assertTrue(client.contains("import dev.amenhancer.module.BuildConfig"))
        assertTrue(client.contains("ComponentName(BuildConfig.APPLICATION_ID, UsbDirectIpc.SERVICE_CLASS)"))
        assertFalse(client.contains("ComponentName(ModuleConstants.MODULE_PACKAGE"))
    }

    @Test
    fun `broker visibility is granted to the injected Apple Music process`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val provider = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectVisibilityProvider.kt",
        )
        val application = projectFile(
            "app/src/main/java/dev/amenhancer/module/ModuleApplication.kt",
        )
        val permission = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectPermissionActivity.kt",
        )
        val client = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt",
        )

        assertTrue(manifest.contains("<package android:name=\"com.apple.android.music\" />"))
        assertTrue(manifest.contains("android:name=\".usb.UsbDirectVisibilityProvider\""))
        assertTrue(manifest.contains("android:authorities=\"\${applicationId}.usb-direct-visibility\""))
        assertTrue(manifest.contains("android:grantUriPermissions=\"true\""))
        assertTrue(manifest.contains("<grant-uri-permission android:path=\"/bridge\" />"))
        assertTrue(provider.contains("application.grantUriPermission("))
        assertTrue(provider.contains("ModuleConstants.TARGET_PACKAGE"))
        assertTrue(provider.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(application.contains("UsbDirectVisibilityGrant.grantToAppleMusic(this)"))
        assertTrue(permission.contains("UsbDirectVisibilityGrant.grantToAppleMusic(this)"))
        assertTrue(client.contains("resolveService(intent"))
        assertTrue(client.contains("not visible/resolvable"))
    }

    @Test
    fun `native UAC control helper defines payload and fallback policy`() {
        val relativePath = "app/src/main/cpp/UsbDirectUacControl.h"
        val headerFile = sequenceOf(File(relativePath), File("../$relativePath"))
            .firstOrNull(File::isFile)
        assertTrue(headerFile != null)
        val header = headerFile!!.readText()
        assertTrue(header.contains("uac1RatePayload"))
        assertTrue(header.contains("uac2RatePayload"))
        assertTrue(header.contains("uac2ControlIndex"))
        assertTrue(header.contains("acceptUac1SetCurResult"))
    }

    @Test
    fun `native engine uses usbfs isochronous URBs rather than AAudio`() {
        val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")
        val cmake = projectFile("app/src/main/cpp/CMakeLists.txt")

        assertTrue(native.contains("USBDEVFS_URB_TYPE_ISO"))
        assertTrue(native.contains("USBDEVFS_URB_ISO_ASAP"))
        assertTrue(native.contains("USBDEVFS_SUBMITURB"))
        assertTrue(native.contains("USBDEVFS_REAPURB"))
        assertTrue(native.contains("USBDEVFS_DISCARDURB"))
        assertTrue(native.contains("USBDEVFS_CONTROL"))
        assertTrue(native.contains("configureUac1Rate"))
        assertTrue(native.contains("configureUac2Rate"))
        assertTrue(native.contains("usb_direct_uac::uac2ControlIndex"))
        assertTrue(native.contains("dup(fd)"))
        assertTrue(native.contains("attenuateIntegerSample"))
        assertTrue(native.contains("gainForSample"))
        assertTrue(native.contains("gainLeft"))
        assertTrue(native.contains("gainRight"))
        assertTrue(native.contains("std::unordered_map<jlong, std::shared_ptr<Session>> gSessions"))
        assertTrue(native.contains("findSession(handle)"))
        assertTrue(native.contains("takeSession(handle)"))
        assertFalse(native.contains("delete session"))
        assertTrue(cmake.contains("UsbDirectUac.cpp"))
        assertTrue(native.contains("#include \"UsbFeedbackClock.h\""))
        assertTrue(native.contains("kFeedbackUrbCount = 2"))
        assertTrue(native.contains("IsoRole::FeedbackIn"))
        assertTrue(native.contains("decodeFeedbackQ16"))
        assertTrue(native.contains("feedbackMatchesRate"))
        assertTrue(native.contains("feedbackFitsPacket"))
        assertTrue(native.contains("USB Direct feedback timeout"))
        assertTrue(native.contains("USB Direct feedback payload invalid"))
        assertTrue(native.contains("if (!session->running.load() || session->closing.load()) break;"))

        val stopSession = native.substringAfter("void stopSession(Session* session)")
            .substringBefore("int64_t clampToBits")
        val joinIndex = stopSession.indexOf("session->worker.join()")
        val releaseIndex = stopSession.indexOf("releaseInterfaces(session)")
        assertTrue(joinIndex >= 0)
        assertTrue(releaseIndex > joinIndex)
    }

    @Test
    fun `native release reconnects kernel driver after interface release`() {
        val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")

        assertTrue(native.contains("USBDEVFS_DISCONNECT_CLAIM"))
        assertTrue(native.contains("USBDEVFS_IOCTL"))
        assertTrue(native.contains("USBDEVFS_CONNECT"))
        assertTrue(native.contains("reconnectKernelDriver"))

        val release = native.substringAfter("void releaseInterfaces(Session* session)")
            .substringBefore("bool selectStreamingAlternate")
        val releaseIndex = release.indexOf("USBDEVFS_RELEASEINTERFACE")
        val reconnectIndex = release.indexOf("reconnectKernelDriver")
        assertTrue(releaseIndex >= 0)
        assertTrue(reconnectIndex > releaseIndex)
    }

    @Test
    fun `AudioTrack handoff suspends cleanly and can replace idle sessions`() {
        val controller = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacController.kt",
        )
        val bridge = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt",
        )
        val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")

        assertTrue(controller.contains("val track: WeakReference<AudioTrack>"))
        assertTrue(controller.contains("var suspended: Boolean = false"))
        assertTrue(controller.contains("UsbDirectTrackHandoffPolicy.shouldHandoff("))
        assertTrue(controller.contains("UsbDirectTrackHandoffPolicy.actionFor(operation)"))
        assertTrue(controller.contains("suspendHandle = active.handle"))
        assertTrue(controller.contains("flushHandle = active.handle"))
        assertTrue(controller.contains("UsbDirectUacBridge.suspend(suspendHandle)"))
        assertTrue(controller.contains("UsbDirectUacBridge.flush(flushHandle)"))
        assertTrue(controller.contains("existingTrackIdle = existingTrackIdle"))
        assertTrue(controller.contains("UsbDirectTrackHandoffPolicy.isIdleOwner("))
        assertTrue(controller.contains("TRACK_HANDOFF_IDLE_NANOS = 1_000_000_000L"))
        assertTrue(controller.contains("TRACK_HANDOFF_STARTUP_GRACE_NANOS = 2_000_000_000L"))
        assertTrue(controller.contains("ownerStartedRealtimeNanos = active.ownerStartedRealtimeNanos"))
        assertTrue(controller.contains("val ownerStartedRealtimeNanos: Long = System.nanoTime()"))
        assertFalse(controller.contains("active.lastWriteRealtimeNanos == 0L ||"))
        assertTrue(controller.contains("if (active.suspended) return consumeSuspendedWrite(args)"))
        assertTrue(controller.contains("UsbDirectUacBridge.resume(resumeHandle)"))
        assertTrue(bridge.contains("fun suspend(handle: Long)"))
        assertTrue(bridge.contains("fun resume(handle: Long)"))
        assertTrue(bridge.contains("fun flush(handle: Long)"))
        assertTrue(bridge.contains("private external fun nativeSuspend(handle: Long)"))
        assertTrue(bridge.contains("private external fun nativeResume(handle: Long)"))
        assertTrue(bridge.contains("private external fun nativeFlush(handle: Long)"))
        assertTrue(native.contains("UsbDirectUacBridge_nativeSuspend"))
        assertTrue(native.contains("UsbDirectUacBridge_nativeResume"))
        assertTrue(native.contains("UsbDirectUacBridge_nativeFlush"))
        assertTrue(native.contains("std::atomic<bool> suspended{false}"))
        assertTrue(native.contains("if (session->suspended.load())"))
        assertTrue(native.contains("session->ringRead = 0"))
        assertTrue(native.contains("session->ringWrite = 0"))
        assertTrue(native.contains("session->ringCount = 0"))
    }

    @Test
    fun `direct takeover remains fail open to the original AudioTrack`() {
        val hook = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbBitPerfectFeature.kt",
        )
        val controller = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacController.kt",
        )
        val protocol = projectFile(
            "app/src/main/java/dev/amenhancer/module/UsbBitPerfectStatusProtocol.kt",
        )
        val bridge = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt",
        )

        val directIntercept = hook.indexOf("UsbDirectUacController.interceptWrite")
        assertTrue(directIntercept >= 0)
        assertTrue(hook.contains("UsbDirectUacController.afterOriginalWrite"))
        assertFalse(hook.contains("UsbExclusiveAaudioController"))
        assertTrue(hook.contains("UsbDirectUacController.onSystemMediaVolumeChanged(index)"))
        assertTrue(hook.contains("UsbDirectUacController.afterVolumeChange"))
        assertTrue(controller.contains("active.hasWrittenPcm"))
        assertTrue(controller.contains("STATE_DIRECT_ACTIVE"))
        assertTrue(controller.contains("UsbDirectDeviceClient.release(context)"))
        assertTrue(controller.contains("UsbDirectVolumePolicy.streamGain"))
        assertTrue(controller.contains("streamGainCache.refresh"))
        assertTrue(controller.contains("streamGainCache.effectiveGain"))
        assertTrue(controller.contains("it === expectedSession"))
        assertTrue(controller.contains("shouldResumeOriginalTrack(closedOwnedSession)"))
        assertTrue(controller.contains("runCatching { track.play() }"))
        assertTrue(controller.contains("Android 系统输出"))
        val hotPath = controller.substringAfter("private fun effectiveGains")
            .substringBefore("private fun")
        assertFalse(hotPath.contains("getStreamVolume"))
        assertFalse(hotPath.contains("getStreamMaxVolume"))
        assertFalse(hotPath.contains("getStreamVolumeDb"))
        assertTrue(controller.contains("gainLeft = gains.left"))
        assertTrue(controller.contains("gainRight = gains.right"))
        assertTrue(bridge.contains("gainLeft: Float"))
        assertTrue(bridge.contains("gainRight: Float"))
        assertTrue(protocol.contains("STATE_DIRECT_PERMISSION_REQUIRED"))
        assertTrue(protocol.contains("STATE_DIRECT_UNSUPPORTED_DEVICE"))
    }

    @Test
    fun `USB Direct reports descriptor bit resolution separately from Apple Music float PCM`() {
        val controller = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacController.kt",
        )
        val ui = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsActivity.kt",
        )

        assertTrue(controller.contains("lease.bitResolution <= 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED"))
        assertTrue(controller.contains("mixerEncoding = lease?.let(::usbEncoding) ?: 0"))
        assertTrue(ui.contains("AudioFormat.ENCODING_PCM_FLOAT -> \"PCM Float\""))
        assertTrue(ui.contains("AudioFormat.ENCODING_PCM_24BIT_PACKED -> \"PCM 24-bit\""))
        assertTrue(ui.contains("\$mixerFormat · USB DIRECT · usbfs ISO PCM"))
    }

    @Test
    fun `UAC ownership metadata survives broker IPC lease and JNI boundary`() {
        val ipc = projectFile("app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt")
        val broker = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt",
        )
        val client = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt",
        )
        val bridge = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt",
        )
        val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")

        listOf(
            "KEY_AUDIO_CONTROL_INTERFACE",
            "KEY_CLOCK_SOURCE_ID",
            "KEY_FIXED_SAMPLE_RATE_MATCH",
        ).forEach { key -> assertTrue(ipc.contains(key)) }

        assertTrue(broker.contains("alternative.audioControlInterface"))
        assertTrue(broker.contains("alternative.clockSourceId"))
        assertTrue(broker.contains("fixedSampleRateMatch"))

        assertTrue(client.contains("val audioControlInterface: Int"))
        assertTrue(client.contains("val clockSourceId: Int"))
        assertTrue(client.contains("val fixedSampleRateMatch: Boolean"))

        assertTrue(bridge.contains("lease.interfaceNumber"))
        assertTrue(bridge.contains("lease.alternateSetting"))
        assertTrue(bridge.contains("lease.audioControlInterface"))
        assertTrue(bridge.contains("lease.clockSourceId"))
        assertTrue(bridge.contains("lease.fixedSampleRateMatch"))

        assertTrue(native.contains("jint interfaceNumber"))
        assertTrue(native.contains("jint alternateSetting"))
        assertTrue(native.contains("jint audioControlInterface"))
        assertTrue(native.contains("jint clockSourceId"))
        assertTrue(native.contains("jboolean fixedSampleRateMatch"))
    }

    @Test
    fun `explicit feedback endpoint survives broker IPC lease and JNI boundary`() {
        val ipc = projectFile(
            "app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt",
        )
        val broker = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt",
        )
        val client = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt",
        )
        val bridge = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt",
        )
        val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")

        assertTrue(ipc.contains("KEY_FEEDBACK_ENDPOINT_ADDRESS"))
        assertTrue(ipc.contains("KEY_FEEDBACK_MAX_PACKET_SIZE"))
        assertTrue(ipc.contains("KEY_FEEDBACK_INTERVAL"))
        assertTrue(broker.contains("alternative.feedbackEndpointAddress"))
        assertTrue(broker.contains("alternative.feedbackMaxPacketSize"))
        assertTrue(broker.contains("alternative.feedbackInterval"))
        assertTrue(client.contains("val feedbackEndpointAddress: Int"))
        assertTrue(client.contains("val feedbackMaxPacketSize: Int"))
        assertTrue(client.contains("val feedbackInterval: Int"))
        assertTrue(bridge.contains("lease.feedbackEndpointAddress"))
        assertTrue(bridge.contains("lease.feedbackMaxPacketSize"))
        assertTrue(bridge.contains("lease.feedbackInterval"))
        assertTrue(native.contains("jint feedbackEndpointAddress"))
        assertTrue(native.contains("jint feedbackMaxPacketSize"))
        assertTrue(native.contains("jint feedbackInterval"))
    }
}
