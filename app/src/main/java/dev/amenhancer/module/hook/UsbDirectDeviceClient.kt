package dev.amenhancer.module.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import dev.amenhancer.module.BuildConfig
import dev.amenhancer.module.UsbDirectIpc

/** Cross-process client for the permission-owning AM++ USB broker service. */
internal object UsbDirectDeviceClient {
    sealed interface AcquireResult {
        data class Acquired(val lease: Lease) : AcquireResult
        data class Failed(val reason: String) : AcquireResult
    }

    data class Lease(
        val fd: ParcelFileDescriptor,
        val sampleRate: Int,
        val encoding: Int,
        val channels: Int,
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val endpointAddress: Int,
        val maxPacketSize: Int,
        val interval: Int,
        val subslotBytes: Int,
        val bitResolution: Int,
        val protocol: Int,
        val deviceName: String?,
        val vendorId: Int,
        val productId: Int,
    )

    private data class PendingAcquire(
        val context: Context,
        val format: AudioFormat,
        val callback: (AcquireResult) -> Unit,
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceMessenger: Messenger? = null
    private var serviceConnection: ServiceConnection? = null
    private var pending: PendingAcquire? = null
    private var activeLease: Lease? = null

    private val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == UsbDirectIpc.WHAT_RESULT) {
            handleResult(message.data)
            true
        } else {
            false
        }
    })

    fun acquire(
        context: Context,
        format: AudioFormat,
        callback: (AcquireResult) -> Unit,
    ): Boolean {
        val application = context.applicationContext
        synchronized(lock) {
            if (activeLease != null || pending != null) return false
            pending = PendingAcquire(application, format, callback)
            val messenger = serviceMessenger
            if (messenger != null) {
                sendAcquireLocked(messenger)
                return true
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val messenger = service?.let(::Messenger)
                    synchronized(lock) {
                        serviceMessenger = messenger
                        if (messenger == null) {
                            failPendingLocked("USB Direct broker returned no Binder")
                        } else {
                            sendAcquireLocked(messenger)
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    synchronized(lock) {
                        serviceMessenger = null
                        if (pending != null) failPendingLocked("USB Direct broker disconnected")
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    synchronized(lock) {
                        serviceMessenger = null
                        if (pending != null) failPendingLocked("USB Direct broker binding died")
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    synchronized(lock) {
                        serviceMessenger = null
                        failPendingLocked("USB Direct broker refused binding")
                    }
                }
            }
            serviceConnection = connection
            val brokerIntent = Intent().setComponent(
                ComponentName(BuildConfig.APPLICATION_ID, UsbDirectIpc.SERVICE_CLASS),
            )
            val resolvableBeforeBind = isBrokerResolvable(application, brokerIntent)
            val bound = runCatching {
                application.bindService(
                    brokerIntent,
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!bound) {
                serviceConnection = null
                failPendingLocked(
                    if (resolvableBeforeBind) {
                        "bindService returned false for resolved AM++ USB Direct broker (${BuildConfig.APPLICATION_ID})"
                    } else {
                        "AM++ USB Direct broker is not visible/resolvable (${BuildConfig.APPLICATION_ID}); open AM++ USB settings or reconnect the DAC to refresh package visibility"
                    },
                )
            }
            return bound
        }
    }

    fun release(context: Context) {
        val application = context.applicationContext
        val lease: Lease?
        val messenger: Messenger?
        val connection: ServiceConnection?
        synchronized(lock) {
            lease = activeLease
            activeLease = null
            pending = null
            messenger = serviceMessenger
            serviceMessenger = null
            connection = serviceConnection
            serviceConnection = null
        }
        runCatching { lease?.fd?.close() }
        if (messenger != null) {
            runCatching {
                messenger.send(
                    Message.obtain(null, UsbDirectIpc.WHAT_RELEASE).apply {
                        replyTo = replyMessenger
                    },
                )
            }
        }
        if (connection != null) {
            runCatching { application.unbindService(connection) }
        }
    }

    private fun sendAcquireLocked(messenger: Messenger) {
        val request = pending ?: return
        val message = Message.obtain(null, UsbDirectIpc.WHAT_ACQUIRE).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putInt(UsbDirectIpc.KEY_SAMPLE_RATE, request.format.sampleRate)
                putInt(UsbDirectIpc.KEY_ENCODING, request.format.encoding)
                putInt(UsbDirectIpc.KEY_CHANNELS, request.format.channelCount)
            }
        }
        runCatching { messenger.send(message) }
            .onFailure { error ->
                failPendingLocked(error.message ?: "USB Direct broker request failed")
            }
    }

    private fun handleResult(data: Bundle) {
        val request: PendingAcquire
        synchronized(lock) {
            request = pending ?: return
            pending = null
        }
        if (data.getInt(UsbDirectIpc.KEY_RESULT) != UsbDirectIpc.RESULT_OK) {
            request.callback(
                AcquireResult.Failed(
                    data.getString(UsbDirectIpc.KEY_ERROR)?.takeIf(String::isNotBlank)
                        ?: "USB Direct broker rejected device acquisition",
                ),
            )
            return
        }
        @Suppress("DEPRECATION")
        val fd = data.getParcelable(UsbDirectIpc.KEY_FD) as? ParcelFileDescriptor
        if (fd == null) {
            request.callback(AcquireResult.Failed("USB Direct broker response contained no file descriptor"))
            return
        }
        val lease = Lease(
            fd = fd,
            sampleRate = data.getInt(UsbDirectIpc.KEY_SAMPLE_RATE),
            encoding = data.getInt(UsbDirectIpc.KEY_ENCODING),
            channels = data.getInt(UsbDirectIpc.KEY_CHANNELS),
            interfaceNumber = data.getInt(UsbDirectIpc.KEY_INTERFACE_NUMBER),
            alternateSetting = data.getInt(UsbDirectIpc.KEY_ALTERNATE_SETTING),
            endpointAddress = data.getInt(UsbDirectIpc.KEY_ENDPOINT_ADDRESS),
            maxPacketSize = data.getInt(UsbDirectIpc.KEY_MAX_PACKET_SIZE),
            interval = data.getInt(UsbDirectIpc.KEY_INTERVAL),
            subslotBytes = data.getInt(UsbDirectIpc.KEY_SUBSLOT_BYTES),
            bitResolution = data.getInt(UsbDirectIpc.KEY_BIT_RESOLUTION),
            protocol = data.getInt(UsbDirectIpc.KEY_PROTOCOL),
            deviceName = data.getString(UsbDirectIpc.KEY_DEVICE_NAME),
            vendorId = data.getInt(UsbDirectIpc.KEY_VENDOR_ID),
            productId = data.getInt(UsbDirectIpc.KEY_PRODUCT_ID),
        )
        synchronized(lock) { activeLease = lease }
        request.callback(AcquireResult.Acquired(lease))
    }

    private fun isBrokerResolvable(context: Context, intent: Intent): Boolean = runCatching {
        val manager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.resolveService(intent, PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            manager.resolveService(intent, 0) != null
        }
    }.getOrDefault(false)

    private fun failPendingLocked(reason: String) {
        val request = pending ?: return
        pending = null
        mainHandler.post { request.callback(AcquireResult.Failed(reason)) }
    }
}
