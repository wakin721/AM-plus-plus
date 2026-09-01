package dev.amenhancer.module.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbUacSampleRateControlTest {
    @Test
    fun `matching current UAC2 rate succeeds without SET_CUR`() {
        val calls = mutableListOf<ControlCall>()

        val configured = UsbUacSampleRateControl.configureUac2(
            sampleRate = 48_000,
            clockSourceId = 10,
            audioControlInterface = 0,
            controlTransfer = UsbControlTransfer { requestType, request, value, index, buffer, length, timeout ->
                calls += ControlCall(requestType, request, value, index, length, timeout)
                buffer[0] = 0x80.toByte()
                buffer[1] = 0xbb.toByte()
                buffer[2] = 0x00
                buffer[3] = 0x00
                length
            },
        )

        assertTrue(configured)
        assertEquals(
            listOf(
                ControlCall(
                    requestType = 0xa1,
                    request = 0x01,
                    value = 0x0100,
                    index = 0x0a00,
                    length = 4,
                    timeout = 1_000,
                ),
            ),
            calls,
        )
    }

    @Test
    fun `different readable UAC2 rate writes target and verifies readback`() {
        val calls = mutableListOf<ControlCall>()
        var readCount = 0
        var writtenRate = 0

        val configured = UsbUacSampleRateControl.configureUac2(
            sampleRate = 48_000,
            clockSourceId = 10,
            audioControlInterface = 0,
            controlTransfer = UsbControlTransfer { requestType, request, value, index, buffer, length, timeout ->
                calls += ControlCall(requestType, request, value, index, length, timeout)
                if (requestType == 0xa1) {
                    val currentRate = if (readCount++ == 0) 44_100 else 48_000
                    buffer.writeU32le(currentRate)
                } else {
                    writtenRate = buffer.readU32le()
                }
                length
            },
        )

        assertTrue(configured)
        assertEquals(
            listOf(
                ControlCall(0xa1, 0x01, 0x0100, 0x0a00, 4, 1_000),
                ControlCall(0x21, 0x01, 0x0100, 0x0a00, 4, 1_000),
                ControlCall(0xa1, 0x01, 0x0100, 0x0a00, 4, 1_000),
            ),
            calls,
        )
        assertEquals(48_000, writtenRate)
    }

    @Test
    fun `unreadable UAC2 rate keeps SET_CUR compatible path`() {
        val requestTypes = mutableListOf<Int>()
        var writtenRate = 0

        val configured = UsbUacSampleRateControl.configureUac2(
            sampleRate = 48_000,
            clockSourceId = 10,
            audioControlInterface = 0,
            controlTransfer = UsbControlTransfer { requestType, _, _, _, buffer, length, _ ->
                requestTypes += requestType
                if (requestType == 0xa1) {
                    -1
                } else {
                    writtenRate = buffer.readU32le()
                    length
                }
            },
        )

        assertTrue(configured)
        assertEquals(listOf(0xa1, 0x21), requestTypes)
        assertEquals(48_000, writtenRate)
    }

    @Test
    fun `readable UAC2 rate rejects a write that does not change the clock`() {
        val requestTypes = mutableListOf<Int>()

        val configured = UsbUacSampleRateControl.configureUac2(
            sampleRate = 48_000,
            clockSourceId = 10,
            audioControlInterface = 0,
            controlTransfer = UsbControlTransfer { requestType, _, _, _, buffer, length, _ ->
                requestTypes += requestType
                if (requestType == 0xa1) buffer.writeU32le(44_100)
                length
            },
        )

        assertFalse(configured)
        assertEquals(listOf(0xa1, 0x21, 0xa1), requestTypes)
    }

    @Test
    fun `failed UAC2 SET_CUR rejects configuration without another read`() {
        val requestTypes = mutableListOf<Int>()

        val configured = UsbUacSampleRateControl.configureUac2(
            sampleRate = 48_000,
            clockSourceId = 10,
            audioControlInterface = 0,
            controlTransfer = UsbControlTransfer { requestType, _, _, _, buffer, length, _ ->
                requestTypes += requestType
                if (requestType == 0xa1) {
                    buffer.writeU32le(44_100)
                    length
                } else {
                    -1
                }
            },
        )

        assertFalse(configured)
        assertEquals(listOf(0xa1, 0x21), requestTypes)
    }

    @Test
    fun `unreadable verification after successful UAC2 SET_CUR rejects configuration`() {
        val requestTypes = mutableListOf<Int>()
        var readCount = 0

        val configured = UsbUacSampleRateControl.configureUac2(
            sampleRate = 48_000,
            clockSourceId = 10,
            audioControlInterface = 0,
            controlTransfer = UsbControlTransfer { requestType, _, _, _, buffer, length, _ ->
                requestTypes += requestType
                when {
                    requestType == 0xa1 && readCount++ == 0 -> {
                        buffer.writeU32le(44_100)
                        length
                    }
                    requestType == 0xa1 -> -1
                    else -> length
                }
            },
        )

        assertFalse(configured)
        assertEquals(listOf(0xa1, 0x21, 0xa1), requestTypes)
    }

    private fun ByteArray.writeU32le(value: Int) {
        this[0] = (value and 0xff).toByte()
        this[1] = ((value ushr 8) and 0xff).toByte()
        this[2] = ((value ushr 16) and 0xff).toByte()
        this[3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun ByteArray.readU32le(): Int =
        (this[0].toInt() and 0xff) or
            ((this[1].toInt() and 0xff) shl 8) or
            ((this[2].toInt() and 0xff) shl 16) or
            ((this[3].toInt() and 0xff) shl 24)

    private data class ControlCall(
        val requestType: Int,
        val request: Int,
        val value: Int,
        val index: Int,
        val length: Int,
        val timeout: Int,
    )
}
