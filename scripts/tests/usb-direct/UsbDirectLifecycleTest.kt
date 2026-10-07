package dev.amenhancer.module.hook

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.os.*
import dev.amenhancer.module.UsbDirectIpc as Ipc
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Runs the real client/controller with deterministic Android/Binder/native boundary fixtures. */
class UsbDirectLifecycleTest {
    private val context = Context()
    private val requestTracks = mutableMapOf<Long, AudioTrack>()

    @Before fun setUp() {
        UsbDirectUacController.configure(false)
        UsbDirectDeviceClient.release(context)
        UsbDirectUacBridge.reset()
        Messenger.outgoing.clear()
        Handler.posted.clear()
        AudioTrack.keepAliveTracks.clear()
        AudioTrack.acceptsRouting = true
        requestTracks.clear()
    }

    @After fun tearDown() {
        UsbDirectUacController.configure(false)
        UsbDirectDeviceClient.release(context)
    }

    private fun lastAcquire() = Messenger.outgoing.last { it.what == Ipc.WHAT_ACQUIRE }

    private fun reply(request: Message, fd: ParcelFileDescriptor? = ParcelFileDescriptor(42),
        id: Long = request.data.getLong(Ipc.KEY_REQUEST_ID), success: Boolean = true,
        writeBoundary: Boolean = true) {
        request.replyTo!!.send(Message.obtain(null, Ipc.WHAT_RESULT).apply {
            data = Bundle().apply {
                putLong(Ipc.KEY_REQUEST_ID, id)
                putInt(Ipc.KEY_RESULT, if (success) Ipc.RESULT_OK else Ipc.RESULT_ERROR)
                putString(Ipc.KEY_ERROR, "test broker error")
                fd?.let { putParcelable(Ipc.KEY_FD, it) }
                putInt(Ipc.KEY_SAMPLE_RATE, request.data.getInt(Ipc.KEY_SAMPLE_RATE))
                putInt(Ipc.KEY_ENCODING, request.data.getInt(Ipc.KEY_ENCODING))
                putInt(Ipc.KEY_CHANNELS, request.data.getInt(Ipc.KEY_CHANNELS))
            }
        })
        if (writeBoundary) requestTracks[id]?.let(UsbDirectUacController::beforeWrite)
    }

    private fun observe(track: AudioTrack) {
        val previous = Messenger.outgoing.lastOrNull { it.what == Ipc.WHAT_ACQUIRE }
        UsbDirectUacController.afterOriginalWrite(context, track, arrayOf(shortArrayOf(1, 2), 0, 2), 2)
        Messenger.outgoing.lastOrNull { it.what == Ipc.WHAT_ACQUIRE }?.takeIf { it !== previous }?.let {
            requestTracks[it.data.getLong(Ipc.KEY_REQUEST_ID)] = track
        }
    }

    private fun activate(track: AudioTrack = AudioTrack()): AudioTrack {
        UsbDirectUacController.configure(true)
        observe(track)
        reply(lastAcquire())
        assertTrue(UsbDirectUacController.isActive(track))
        return track
    }

    private fun control(track: AudioTrack, operation: String) {
        UsbDirectUacController.onTransportControl(context, track, operation)
        when (operation) {
            "pause" -> track.pause()
            "stop", "release" -> track.stop()
        }
    }

    private fun otherThread(action: () -> Unit) {
        val error = AtomicReference<Throwable?>()
        val thread = Thread { try { action() } catch (failure: Throwable) { error.set(failure) } }
        thread.isDaemon = true
        thread.start()
        thread.join(2_000)
        assertFalse("Transport must remain responsive during nativeOpen", thread.isAlive)
        error.get()?.let { throw it }
    }

    @Test fun ownedTrackReportsDirectTransportState() {
        val track = activate()
        assertEquals(AudioTrack.PLAYSTATE_PAUSED, track.playState)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, UsbDirectUacController.playbackState(track))
        assertNull(UsbDirectUacController.playbackState(AudioTrack()))
        control(track, "pause")
        assertEquals(AudioTrack.PLAYSTATE_PAUSED, UsbDirectUacController.playbackState(track))
        assertTrue(UsbDirectUacController.beforePlay(context, track))
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, UsbDirectUacController.playbackState(track))
        control(track, "stop")
        assertNull(UsbDirectUacController.playbackState(track))
    }

    @Test fun brokerResponseDoesNotPauseAnInFlightOriginalWriteAndNextPcmWriteStartsUsb() {
        val track = AudioTrack()
        UsbDirectUacController.configure(true)
        observe(track)
        track.writeInFlight = true
        reply(lastAcquire(), writeBoundary = false)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        assertFalse(UsbDirectUacController.isActive(track))
        assertTrue(UsbDirectUacBridge.outputs.isEmpty())
        track.writeInFlight = false
        assertEquals(2, UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(7, 8), 0, 2)))
        assertTrue(UsbDirectUacController.isActive(track))
        assertEquals(listOf(7, 8), UsbDirectUacBridge.outputs.values.single().queued)
    }

    @Test fun pauseBeforeTheNextWriteCancelsThePreparedLeaseWithoutClaimingUsb() {
        val track = AudioTrack()
        UsbDirectUacController.configure(true)
        observe(track)
        val fd = ParcelFileDescriptor(76)
        reply(lastAcquire(), fd, writeBoundary = false)
        control(track, "pause")
        assertTrue(fd.closed)
        assertNull(UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(7, 8), 0, 2)))
        assertTrue(UsbDirectUacBridge.outputs.isEmpty())
    }

    @Test fun frameworkPlaybackRemainsActiveWithOnlySilentNonUsbAudio() {
        val track = activate()
        val keeper = AudioTrack.keepAliveTracks.single()
        assertEquals(AudioTrack.PLAYSTATE_PAUSED, track.playState)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, keeper.playState)
        assertEquals(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, keeper.preferredDevice!!.type)
        assertEquals(0f, keeper.volume, 0f)
        assertTrue(keeper.loadedSilence)
        assertEquals(-1, keeper.loopCount)
        assertEquals(4_800, keeper.loopFrames)
        UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2))
        assertEquals(listOf(1, 2), UsbDirectUacBridge.outputs.values.single().queued)
    }

    @Test fun takeoverCapturesUsbVolumeBeforeNativeClaimChangesTheSystemRoute() {
        context.audioManager.mediaVolume = 3
        UsbDirectUacBridge.duringOpen = {
            context.audioManager.mediaVolume = 0
        }
        val track = activate()
        UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2))
        assertEquals(0.2f, UsbDirectUacBridge.outputs.values.single().gainLeft, 0.0001f)
    }

    private fun pcmGain(track: AudioTrack): Float {
        val output = UsbDirectUacBridge.outputs.values.single()
        output.queued.clear()
        assertEquals(2, UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2)))
        return output.gainLeft
    }

    @Test fun repeatedSpeakerZeroVolumePollsPreserveUsbVolume() {
        context.audioManager.mediaVolume = 3
        val track = activate()
        context.audioManager.mediaVolume = 0
        repeat(5) {
            UsbDirectUacController.onSystemMediaVolumeChanged()
            assertEquals(0.2f, pcmGain(track), 0.0001f)
        }
    }

    @Test fun explicitVolumeChangesIncludingZeroSurviveSubsequentSpeakerPolling() {
        context.audioManager.mediaVolume = 3
        val track = activate()
        context.audioManager.mediaVolume = 0
        UsbDirectUacController.onSystemMediaVolumeChanged(6)
        UsbDirectUacController.onSystemMediaVolumeChanged()
        assertEquals(0.4f, pcmGain(track), 0.0001f)
        UsbDirectUacController.onSystemMediaVolumeChanged(0)
        UsbDirectUacController.onSystemMediaVolumeChanged()
        assertEquals(0f, pcmGain(track), 0f)
        UsbDirectUacController.onSystemMediaVolumeChanged(3)
        assertEquals(0.2f, pcmGain(track), 0.0001f)
    }

    @Test fun onlyVolumeNotificationsReplaceTheUsbStepEvenWhenRoutingLooksUnchanged() {
        val track = activate()
        context.audioManager.mediaVolume = 6
        UsbDirectUacController.onSystemMediaVolumeChanged()
        assertEquals(1f, pcmGain(track), 0f)
        UsbDirectUacController.onSystemMediaVolumeChanged(6)
        context.audioManager.mediaVolume = 0
        UsbDirectUacController.onSystemMediaVolumeChanged()
        assertEquals(0.4f, pcmGain(track), 0.0001f)
        UsbDirectUacController.onSystemMediaVolumeChanged(0)
        assertEquals(0f, pcmGain(track), 0f)
    }

    @Test fun globalMuteDoesNotLoseTheRememberedUsbStep() {
        context.audioManager.mediaVolume = 3
        val track = activate()
        context.audioManager.mediaVolume = 0
        UsbDirectUacController.onSystemMediaVolumeChanged()
        assertEquals(0.2f, pcmGain(track), 0.0001f)
        context.audioManager.mediaMuted = true
        UsbDirectUacController.onSystemMediaVolumeChanged()
        assertEquals(0f, pcmGain(track), 0f)
        context.audioManager.mediaMuted = false
        UsbDirectUacController.onSystemMediaVolumeChanged()
        assertEquals(0.2f, pcmGain(track), 0.0001f)
    }

    @Test fun silentFrameworkTrackPausesResumesAndReleasesWithTheUsbSession() {
        val track = activate()
        val keeper = AudioTrack.keepAliveTracks.single()
        control(track, "pause")
        assertEquals(AudioTrack.PLAYSTATE_PAUSED, keeper.playState)
        UsbDirectUacController.beforePlay(context, track)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, keeper.playState)
        assertEquals(1, AudioTrack.keepAliveTracks.size)
        control(track, "stop")
        assertEquals(1, keeper.releaseCalls)
        UsbDirectUacController.configure(false)
        assertEquals(1, keeper.releaseCalls)
    }

    @Test fun failedNativeWriteReleasesFrameworkTrackBeforeSystemFallback() {
        val track = activate()
        val keeper = AudioTrack.keepAliveTracks.single()
        UsbDirectUacBridge.failWrite = true
        assertNull(UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2)))
        assertEquals(1, keeper.releaseCalls)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
    }

    @Test fun producerStallReleasesSilentLoopAndFallsBackButPausedSessionIsPreserved() {
        val track = activate()
        val keeper = AudioTrack.keepAliveTracks.single()
        control(track, "pause")
        UsbDirectUacController.checkPlaybackHealth(System.nanoTime() + 31_000_000_000L)
        assertTrue(UsbDirectUacController.isActive(track))
        assertEquals(0, keeper.releaseCalls)
        UsbDirectUacController.beforePlay(context, track)
        UsbDirectUacController.checkPlaybackHealth()
        assertTrue(UsbDirectUacController.isActive(track))
        UsbDirectUacController.checkPlaybackHealth(System.nanoTime() + 31_000_000_000L)
        assertFalse(UsbDirectUacController.isActive(track))
        assertEquals(1, keeper.releaseCalls)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        assertTrue(UsbDirectUacBridge.outputs.isEmpty())
    }

    @Test fun disablingDirectOutputReleasesTheFrameworkLoop() {
        activate()
        val keeper = AudioTrack.keepAliveTracks.single()
        UsbDirectUacController.configure(false)
        assertEquals(1, keeper.releaseCalls)
        assertEquals(AudioTrack.PLAYSTATE_STOPPED, keeper.playState)
    }

    @Test fun unexpectedKeepAliveUsbRoutingClosesDirectOutputInsteadOfCompetingForTheDac() {
        val track = activate()
        val keeper = AudioTrack.keepAliveTracks.single()
        keeper.routedDevice = android.media.AudioDeviceInfo(android.media.AudioDeviceInfo.TYPE_USB_DEVICE)
        UsbDirectUacController.checkPlaybackHealth()
        assertFalse(UsbDirectUacController.isActive(track))
        assertEquals(1, keeper.releaseCalls)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        assertTrue(UsbDirectUacBridge.outputs.isEmpty())
    }

    @Test fun failedKeepAliveRoutingReleasesBothOutputsAndRestoresOriginalPlayback() {
        val track = AudioTrack()
        AudioTrack.acceptsRouting = false
        UsbDirectUacController.configure(true)
        observe(track)
        reply(lastAcquire())
        assertFalse(UsbDirectUacController.isActive(track))
        assertTrue(UsbDirectUacBridge.outputs.isEmpty())
        assertEquals(1, AudioTrack.keepAliveTracks.single().releaseCalls)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
    }

    @Test fun cancelledNativeOpenNeverCreatesAFrameworkKeepAliveTrack() {
        val track = AudioTrack()
        UsbDirectUacController.configure(true)
        observe(track)
        UsbDirectUacBridge.duringOpen = { otherThread { control(track, "pause") } }
        reply(lastAcquire())
        assertTrue(AudioTrack.keepAliveTracks.isEmpty())
        assertFalse(UsbDirectUacController.isActive(track))
    }

    @Test fun internalAudioOperationsRestoreNestedThreadLocalOwnershipAfterFailure() {
        assertFalse(UsbDirectUacController.isInternalTransition())
        UsbDirectUacController.withInternalTransition {
            assertTrue(UsbDirectUacController.isInternalTransition())
            runCatching {
                UsbDirectUacController.withInternalTransition { error("test") }
            }
            assertTrue(UsbDirectUacController.isInternalTransition())
        }
        assertFalse(UsbDirectUacController.isInternalTransition())
    }

    @Test fun nativeOpenWindowExposesPlayingUntilAnActualPauseCancelsIt() {
        val track = AudioTrack().apply { playbackHeadPosition = 123 }
        UsbDirectUacController.configure(true)
        observe(track)
        UsbDirectUacBridge.duringOpen = {
            otherThread {
                assertEquals(AudioTrack.PLAYSTATE_PLAYING, UsbDirectUacController.playbackState(track))
                assertEquals(123, UsbDirectUacController.playbackHeadPosition(track))
                assertEquals(false, UsbDirectUacController.playbackTimestamp(track, AudioTimestamp()))
                assertTrue(UsbDirectUacController.beforePlay(context, track))
                control(track, "pause")
                assertNull(UsbDirectUacController.playbackState(track))
            }
        }
        reply(lastAcquire())
        assertFalse(UsbDirectUacController.isActive(track))
        assertEquals(0, track.playCalls)
    }

    @Test fun directClockUsesCompletedUsbFramesAndResetsAfterFlush() {
        val track = activate(AudioTrack().apply { playbackHeadPosition = 1_234 })
        val output = UsbDirectUacBridge.outputs.values.single()
        val timestamp = AudioTimestamp()
        assertEquals(false, UsbDirectUacController.playbackTimestamp(track, timestamp))
        output.renderedFrames = 4_800
        output.renderedAtNanos = 123_456_789
        assertEquals(6_034, UsbDirectUacController.playbackHeadPosition(track))
        assertEquals(true, UsbDirectUacController.playbackTimestamp(track, timestamp))
        assertEquals(6_034L, timestamp.framePosition)
        assertEquals(123_456_789L, timestamp.nanoTime)
        control(track, "pause")
        assertEquals(6_034, UsbDirectUacController.playbackHeadPosition(track))
        control(track, "flush")
        assertEquals(0, UsbDirectUacController.playbackHeadPosition(track))
        assertEquals(false, UsbDirectUacController.playbackTimestamp(track, timestamp))
        control(track, "release")
        assertNull(UsbDirectUacController.playbackTimestamp(track, timestamp))
    }

    @Test fun playbackHeadKeepsUnsigned32BitWrapSemantics() {
        val track = activate(AudioTrack().apply { playbackHeadPosition = -2 })
        UsbDirectUacBridge.outputs.values.single().apply {
            renderedFrames = 5; renderedAtNanos = 1
        }
        assertEquals(3, UsbDirectUacController.playbackHeadPosition(track))
        val timestamp = AudioTimestamp()
        assertEquals(true, UsbDirectUacController.playbackTimestamp(track, timestamp))
        assertEquals(3L, timestamp.framePosition)
    }

    @Test fun directWakeLockReleasesOnPauseStopAndFailure() {
        val track = activate()
        val wake = context.powerManager.locks.single()
        assertTrue(wake.isHeld)
        assertEquals(30_000L, wake.timeout)
        control(track, "pause")
        assertFalse(wake.isHeld)
        UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2))
        assertFalse("Paused writes must not reacquire power", wake.isHeld)
        UsbDirectUacController.beforePlay(context, track)
        assertTrue(wake.isHeld)
        UsbDirectUacBridge.failWrite = true
        UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2))
        assertFalse(wake.isHeld)
        UsbDirectUacBridge.failWrite = false
        val other = activate()
        val second = context.powerManager.locks.last()
        assertTrue(second.isHeld)
        control(other, "stop")
        assertFalse(second.isHeld)
    }

    @Test fun releaseAcknowledgementAndOldAcquisitionCannotConsumeNewRequest() {
        assertTrue(UsbDirectDeviceClient.acquire(context, AudioFormat()) {})
        val old = lastAcquire()
        UsbDirectDeviceClient.release(context)
        assertNull(Messenger.outgoing.last { it.what == Ipc.WHAT_RELEASE }.replyTo)
        var result: UsbDirectDeviceClient.AcquireResult? = null
        assertTrue(UsbDirectDeviceClient.acquire(context, AudioFormat(96_000)) { result = it })
        val current = lastAcquire()
        assertNotEquals(old.data.getLong(Ipc.KEY_REQUEST_ID), current.data.getLong(Ipc.KEY_REQUEST_ID))
        reply(old, fd = null, id = 0L) // Legacy release ACK has no acquisition ID or FD.
        assertNull(result)
        val staleFd = ParcelFileDescriptor(43)
        reply(old, staleFd)
        assertTrue("Discarded responses must close their PFD", staleFd.closed)
        assertNull(result)
        reply(current)
        assertEquals(96_000, (result as UsbDirectDeviceClient.AcquireResult.Acquired).lease.sampleRate)
    }

    @Test fun lateErrorDoesNotFailNewRequest() {
        UsbDirectDeviceClient.acquire(context, AudioFormat()) {}
        val old = lastAcquire()
        UsbDirectDeviceClient.release(context)
        var result: UsbDirectDeviceClient.AcquireResult? = null
        UsbDirectDeviceClient.acquire(context, AudioFormat()) { result = it }
        val current = lastAcquire()
        reply(old, fd = null, success = false)
        assertNull(result)
        reply(current)
        assertTrue(result is UsbDirectDeviceClient.AcquireResult.Acquired)
    }

    @Test fun bindFailureIsDeliveredForTheAcceptedRequest() {
        context.bindingSucceeds = false
        var result: UsbDirectDeviceClient.AcquireResult? = null
        assertTrue(UsbDirectDeviceClient.acquire(context, AudioFormat()) { result = it })
        assertNull(result)
        Handler.runPosted()
        assertTrue(result is UsbDirectDeviceClient.AcquireResult.Failed)
    }

    @Test fun delayedBindFailureEndsControllerAcquisitionAndReportsFallback() {
        context.bindingSucceeds = false
        UsbDirectUacController.configure(true)
        observe(AudioTrack())
        assertEquals("direct_acquiring", UsbDirectUacController.currentStatus()?.state)
        Handler.runPosted()
        assertEquals("direct_fallback", UsbDirectUacController.currentStatus()?.state)
    }

    @Test fun responseAfterCancellationClosesItsFileDescriptor() {
        UsbDirectDeviceClient.acquire(context, AudioFormat()) {}
        val old = lastAcquire()
        UsbDirectDeviceClient.release(context)
        val fd = ParcelFileDescriptor(44)
        reply(old, fd)
        assertTrue(fd.closed)
    }

    @Test fun oldServiceConnectionCannotDisconnectOrResendNewRequest() {
        UsbDirectDeviceClient.acquire(context, AudioFormat()) {}
        val oldConnection = context.connections.last()
        UsbDirectDeviceClient.release(context)
        var result: UsbDirectDeviceClient.AcquireResult? = null
        UsbDirectDeviceClient.acquire(context, AudioFormat()) { result = it }
        val current = lastAcquire()
        val sentCount = Messenger.outgoing.size
        oldConnection.onServiceDisconnected(null)
        oldConnection.onNullBinding(null)
        oldConnection.onServiceConnected(null, Binder())
        assertEquals(sentCount, Messenger.outgoing.size)
        assertNull(result)
        reply(current)
        assertTrue(result is UsbDirectDeviceClient.AcquireResult.Acquired)
    }

    @Test fun cleanupOfOldLeaseDoesNotCancelNewAcquisition() {
        var oldLease: UsbDirectDeviceClient.Lease? = null
        UsbDirectDeviceClient.acquire(context, AudioFormat()) {
            oldLease = (it as UsbDirectDeviceClient.AcquireResult.Acquired).lease
        }
        reply(lastAcquire())
        UsbDirectDeviceClient.release(context, oldLease!!)
        var result: UsbDirectDeviceClient.AcquireResult? = null
        UsbDirectDeviceClient.acquire(context, AudioFormat()) { result = it }
        val current = lastAcquire()
        UsbDirectDeviceClient.release(context, oldLease!!)
        reply(current)
        assertTrue(result is UsbDirectDeviceClient.AcquireResult.Acquired)
    }

    @Test fun anotherTrackDoesNotOverwritePendingOwner() {
        UsbDirectUacController.configure(true)
        val first = AudioTrack()
        val second = AudioTrack()
        observe(first)
        val firstRequest = lastAcquire()
        observe(second)
        assertSame(firstRequest, lastAcquire())
        control(first, "stop")
        observe(second)
        assertNotSame(firstRequest, lastAcquire())
        reply(firstRequest)
        assertFalse(UsbDirectUacController.isActive(first))
        reply(lastAcquire())
        assertTrue(UsbDirectUacController.isActive(second))
    }

    @Test fun pausePreservesExistingPcmAndAcceptsNewPcmIntoNativeRing() {
        val track = activate()
        assertEquals(2, UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2)))
        control(track, "pause")
        assertEquals(2, UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(3, 4), 0, 2)))
        val output = UsbDirectUacBridge.outputs.values.single()
        assertTrue(output.suspended)
        assertEquals(listOf(1, 2, 3, 4), output.queued)
        assertTrue(UsbDirectUacController.beforePlay(context, track))
        assertFalse(output.suspended)
        assertEquals(listOf(1, 2, 3, 4), output.queued)
    }

    @Test fun pausedByteBufferAdvancesOnlyByAcceptedBytesAndKeepsBackpressure() {
        val track = activate()
        control(track, "pause")
        val output = UsbDirectUacBridge.outputs.values.single()
        output.capacity = 4
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(4, UsbDirectUacController.interceptWrite(track, arrayOf(buffer, 8, AudioTrack.WRITE_NON_BLOCKING)))
        assertEquals(4, buffer.position())
        assertEquals(0, UsbDirectUacController.interceptWrite(track, arrayOf(buffer, 4, AudioTrack.WRITE_NON_BLOCKING)))
        assertEquals(4, buffer.position())
        assertEquals(listOf(1, 2, 3, 4), output.queued)
    }

    @Test fun explicitFlushClearsPausedRingWithoutReleasingSession() {
        val track = activate()
        control(track, "pause")
        UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2))
        control(track, "flush")
        assertTrue(UsbDirectUacController.isActive(track))
        assertTrue(UsbDirectUacBridge.outputs.values.single().queued.isEmpty())
    }

    private fun cancelDuringOpen(operation: String) {
        UsbDirectUacController.configure(true)
        val track = AudioTrack()
        observe(track)
        val fd = ParcelFileDescriptor(45)
        UsbDirectUacBridge.duringOpen = { otherThread { control(track, operation) } }
        reply(lastAcquire(), fd)
        assertFalse(UsbDirectUacController.isActive(track))
        assertEquals(0, track.playCalls)
        assertTrue(fd.closed)
        assertTrue(UsbDirectUacBridge.outputs.isEmpty())
        assertEquals(listOf(UsbDirectUacBridge.nextHandle), UsbDirectUacBridge.closed)
    }

    @Test fun stopDuringOpenCancelsAndClosesNativeHandle() = cancelDuringOpen("stop")
    @Test fun releaseDuringOpenCancelsAndClosesNativeHandle() = cancelDuringOpen("release")
    @Test fun pauseDuringOpenCancelsAndClosesNativeHandle() = cancelDuringOpen("pause")
    @Test fun flushDuringOpenCancelsAndClosesNativeHandle() = cancelDuringOpen("flush")

    @Test fun disablingDuringOpenCancelsWithoutRestartingTrack() {
        UsbDirectUacController.configure(true)
        val track = AudioTrack()
        observe(track)
        UsbDirectUacBridge.duringOpen = { otherThread { UsbDirectUacController.configure(false) } }
        reply(lastAcquire())
        assertFalse(UsbDirectUacController.isActive(track))
        assertEquals(0, track.playCalls)
        assertTrue(UsbDirectUacBridge.outputs.isEmpty())
    }

    @Test fun failedOpenAfterStopDoesNotRestartOriginalTrack() {
        UsbDirectUacController.configure(true)
        val track = AudioTrack()
        observe(track)
        UsbDirectUacBridge.failOpen = true
        UsbDirectUacBridge.duringOpen = { otherThread { control(track, "stop") } }
        reply(lastAcquire())
        assertEquals(AudioTrack.PLAYSTATE_STOPPED, track.playState)
        assertEquals(0, track.playCalls)
    }

    @Test fun normalOpenFailureRestoresOriginalPlayback() {
        UsbDirectUacController.configure(true)
        val track = AudioTrack()
        observe(track)
        UsbDirectUacBridge.failOpen = true
        reply(lastAcquire())
        assertFalse(UsbDirectUacController.isActive(track))
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        assertEquals(1, track.playCalls)
    }

    @Test fun cancelledOpenCleanupCannotReleaseReplacementSession() {
        UsbDirectUacController.configure(true)
        val old = AudioTrack()
        val replacement = AudioTrack()
        observe(old)
        val oldRequest = lastAcquire()
        val oldFd = ParcelFileDescriptor(46)
        val newFd = ParcelFileDescriptor(47)
        UsbDirectUacBridge.duringOpen = {
            otherThread {
                control(old, "stop")
                observe(replacement)
                UsbDirectUacBridge.duringOpen = null
                reply(lastAcquire(), newFd)
            }
        }
        reply(oldRequest, oldFd)
        assertFalse(UsbDirectUacController.isActive(old))
        assertTrue(UsbDirectUacController.isActive(replacement))
        assertTrue(oldFd.closed)
        assertFalse(newFd.closed)
        assertEquals(1, UsbDirectUacBridge.outputs.size)
    }

    @Test fun failedWriteWhilePausedDoesNotRestartOriginalTrack() {
        val track = activate()
        control(track, "pause")
        UsbDirectUacBridge.failWrite = true
        assertNull(UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2)))
        assertEquals(AudioTrack.PLAYSTATE_PAUSED, track.playState)
        assertEquals(0, track.playCalls)
        assertFalse(UsbDirectUacController.isActive(track))
    }

    @Test fun normalWriteFailureRestoresOriginalPlayback() {
        val track = activate()
        UsbDirectUacBridge.failWrite = true
        assertNull(UsbDirectUacController.interceptWrite(track, arrayOf(shortArrayOf(1, 2), 0, 2)))
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        assertEquals(1, track.playCalls)
    }
}
