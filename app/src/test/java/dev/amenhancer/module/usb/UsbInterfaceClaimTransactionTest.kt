package dev.amenhancer.module.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbInterfaceClaimTransactionTest {
    @Test
    fun `UAC2 claims control before streaming and releases both in reverse order`() {
        val events = mutableListOf<String>()

        val result = UsbInterfaceClaimTransaction.acquire(
            controlInterface = "control-1",
            streamingInterface = "streaming-2",
            isSameInterface = String::equals,
            claimInterface = { usbInterface ->
                events += "claim:$usbInterface"
                true
            },
            releaseInterface = { usbInterface -> events += "release:$usbInterface" },
        )

        assertTrue(result is UsbInterfaceClaimResult.Acquired)
        (result as UsbInterfaceClaimResult.Acquired<String>).claims.releaseWith { usbInterface ->
            events += "release:$usbInterface"
        }
        assertEquals(
            listOf(
                "claim:control-1",
                "claim:streaming-2",
                "release:streaming-2",
                "release:control-1",
            ),
            events,
        )
    }

    @Test
    fun `streaming claim failure releases the already claimed control interface`() {
        val events = mutableListOf<String>()

        val result = UsbInterfaceClaimTransaction.acquire(
            controlInterface = "control-1",
            streamingInterface = "streaming-2",
            isSameInterface = String::equals,
            claimInterface = { usbInterface ->
                events += "claim:$usbInterface"
                usbInterface != "streaming-2"
            },
            releaseInterface = { usbInterface -> events += "release:$usbInterface" },
        )

        assertTrue(result is UsbInterfaceClaimResult.Failed)
        assertEquals(
            listOf(
                "claim:control-1",
                "claim:streaming-2",
                "release:control-1",
            ),
            events,
        )
    }

    @Test
    fun `UAC1 claims and releases only the streaming interface`() {
        val events = mutableListOf<String>()

        val result = UsbInterfaceClaimTransaction.acquire(
            controlInterface = null,
            streamingInterface = "streaming-2",
            isSameInterface = String::equals,
            claimInterface = { usbInterface ->
                events += "claim:$usbInterface"
                true
            },
            releaseInterface = { usbInterface -> events += "release:$usbInterface" },
        )

        (result as UsbInterfaceClaimResult.Acquired<String>).claims.releaseWith { usbInterface ->
            events += "release:$usbInterface"
        }
        assertEquals(
            listOf("claim:streaming-2", "release:streaming-2"),
            events,
        )
    }

    @Test
    fun `control claim failure does not claim or release streaming`() {
        val events = mutableListOf<String>()

        val result = UsbInterfaceClaimTransaction.acquire(
            controlInterface = "control-1",
            streamingInterface = "streaming-2",
            isSameInterface = String::equals,
            claimInterface = { usbInterface ->
                events += "claim:$usbInterface"
                false
            },
            releaseInterface = { usbInterface -> events += "release:$usbInterface" },
        )

        assertTrue(result is UsbInterfaceClaimResult.Failed)
        assertEquals(listOf("claim:control-1"), events)
    }

    @Test
    fun `same interface identity is claimed and released only once`() {
        val events = mutableListOf<String>()

        val result = UsbInterfaceClaimTransaction.acquire(
            controlInterface = 1,
            streamingInterface = 1,
            isSameInterface = Int::equals,
            claimInterface = { usbInterface ->
                events += "claim:$usbInterface"
                true
            },
            releaseInterface = { usbInterface -> events += "release:$usbInterface" },
        )

        (result as UsbInterfaceClaimResult.Acquired<Int>).claims.releaseWith { usbInterface ->
            events += "release:$usbInterface"
        }
        assertEquals(listOf("claim:1", "release:1"), events)
    }
}
