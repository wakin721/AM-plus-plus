package dev.amenhancer.module.usb

internal sealed interface UsbInterfaceClaimResult<T> {
    data class Acquired<T>(val claims: UsbClaimedInterfaces<T>) : UsbInterfaceClaimResult<T>
    data class Failed<T>(val failedInterface: T) : UsbInterfaceClaimResult<T>
}

internal class UsbClaimedInterfaces<T> internal constructor(
    private val interfacesInClaimOrder: List<T>,
) {
    fun releaseWith(releaseInterface: (T) -> Unit) {
        interfacesInClaimOrder.asReversed().forEach(releaseInterface)
    }
}

internal object UsbInterfaceClaimTransaction {
    fun <T> acquire(
        controlInterface: T?,
        streamingInterface: T,
        isSameInterface: (T, T) -> Boolean,
        claimInterface: (T) -> Boolean,
        releaseInterface: (T) -> Unit,
    ): UsbInterfaceClaimResult<T> {
        val interfaces = buildList {
            if (controlInterface != null) add(controlInterface)
            if (controlInterface == null || !isSameInterface(controlInterface, streamingInterface)) {
                add(streamingInterface)
            }
        }
        val claimed = mutableListOf<T>()
        interfaces.forEach { usbInterface ->
            if (!claimInterface(usbInterface)) {
                claimed.asReversed().forEach(releaseInterface)
                return UsbInterfaceClaimResult.Failed(usbInterface)
            }
            claimed += usbInterface
        }
        return UsbInterfaceClaimResult.Acquired(UsbClaimedInterfaces(claimed))
    }
}
