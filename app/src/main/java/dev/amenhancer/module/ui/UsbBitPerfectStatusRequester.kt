package dev.amenhancer.module.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.UsbBitPerfectStatusDetails
import dev.amenhancer.module.UsbBitPerfectStatusProtocol
import java.util.UUID

/** Issues one live status request to the USB Bit-Perfect hook running inside Apple Music. */
internal class UsbBitPerfectStatusRequester(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val applicationContext = context.applicationContext
    private var activeRequest: ActiveRequest? = null
    private val timeout = Runnable {
        activeRequest?.let { request -> complete(request.token, null) }
    }
    private val resultReceiver = object : ResultReceiver(handler) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val token = resultData?.getString(UsbBitPerfectStatusProtocol.EXTRA_REQUEST_TOKEN)
                ?: return
            val state = resultData
                .takeIf { resultCode == UsbBitPerfectStatusProtocol.RESULT_AVAILABLE }
                ?.getString(UsbBitPerfectStatusProtocol.EXTRA_STATE)
                ?.takeIf(String::isNotBlank)
            complete(
                token,
                state?.let {
                    UsbBitPerfectStatusDetails(
                        state = it,
                        deviceName = resultData.stringOrNull(
                            UsbBitPerfectStatusProtocol.EXTRA_DEVICE_NAME,
                        ),
                        trackSampleRate = resultData.getInt(
                            UsbBitPerfectStatusProtocol.EXTRA_TRACK_SAMPLE_RATE,
                        ),
                        trackEncoding = resultData.getInt(
                            UsbBitPerfectStatusProtocol.EXTRA_TRACK_ENCODING,
                        ),
                        trackChannels = resultData.getInt(
                            UsbBitPerfectStatusProtocol.EXTRA_TRACK_CHANNELS,
                        ),
                        mixerSampleRate = resultData.getInt(
                            UsbBitPerfectStatusProtocol.EXTRA_MIXER_SAMPLE_RATE,
                        ),
                        mixerEncoding = resultData.getInt(
                            UsbBitPerfectStatusProtocol.EXTRA_MIXER_ENCODING,
                        ),
                        mixerChannels = resultData.getInt(
                            UsbBitPerfectStatusProtocol.EXTRA_MIXER_CHANNELS,
                        ),
                        message = resultData.stringOrNull(
                            UsbBitPerfectStatusProtocol.EXTRA_MESSAGE,
                        ),
                    )
                },
            )
        }
    }

    fun request(onResult: (UsbBitPerfectStatusDetails?) -> Unit): Boolean {
        if (activeRequest != null) return false
        val request = ActiveRequest(UUID.randomUUID().toString(), onResult)
        activeRequest = request
        handler.postDelayed(timeout, TIMEOUT_MILLIS)
        applicationContext.sendBroadcast(
            Intent(UsbBitPerfectStatusProtocol.REQUEST_ACTION)
                .setPackage(ModuleConstants.TARGET_PACKAGE)
                .putExtra(UsbBitPerfectStatusProtocol.EXTRA_REQUEST_TOKEN, request.token)
                .putExtra(UsbBitPerfectStatusProtocol.EXTRA_RESULT_RECEIVER, resultReceiver),
        )
        return true
    }

    fun cancel() {
        handler.removeCallbacks(timeout)
        activeRequest = null
    }

    private fun complete(token: String, status: UsbBitPerfectStatusDetails?) {
        val request = activeRequest?.takeIf { it.token == token } ?: return
        handler.removeCallbacks(timeout)
        activeRequest = null
        request.onResult(status)
    }

    private fun Bundle.stringOrNull(key: String): String? = getString(key)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private data class ActiveRequest(
        val token: String,
        val onResult: (UsbBitPerfectStatusDetails?) -> Unit,
    )

    private companion object {
        const val TIMEOUT_MILLIS = 1_500L
    }
}
