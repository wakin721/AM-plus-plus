package dev.amenhancer.module.usb

import kotlin.math.abs

/** Minimal UAC1/UAC2 descriptor parser used only for direct USB output candidate selection. */
internal object UsbAudioDescriptorParser {
    private const val DESC_INTERFACE = 0x04
    private const val DESC_ENDPOINT = 0x05
    private const val DESC_CS_INTERFACE = 0x24

    private const val AUDIO_CLASS = 0x01
    private const val AUDIO_CONTROL_SUBCLASS = 0x01
    private const val AUDIO_STREAMING_SUBCLASS = 0x02

    private const val CS_AS_GENERAL = 0x01
    private const val CS_FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01

    private const val AC_INPUT_TERMINAL = 0x02
    private const val AC_OUTPUT_TERMINAL = 0x03

    const val SYNC_NONE = 0
    const val SYNC_ASYNCHRONOUS = 1
    const val SYNC_ADAPTIVE = 2
    const val SYNC_SYNCHRONOUS = 3

    data class StreamingAlt(
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val protocol: Int,
        val channels: Int,
        val subslotBytes: Int,
        val bitResolution: Int,
        val sampleRates: Set<Int>,
        val minSampleRate: Int?,
        val maxSampleRate: Int?,
        val endpointAddress: Int,
        val maxPacketSize: Int,
        val interval: Int,
        val synchronizationType: Int,
        val audioControlInterface: Int,
        val clockSourceId: Int,
    ) {
        val isUac2: Boolean get() = protocol >= 0x20
        val requiresExplicitFeedback: Boolean get() = synchronizationType == SYNC_ASYNCHRONOUS

        fun supportsSampleRate(sampleRate: Int): Boolean {
            if (isUac2) return true
            if (sampleRates.isNotEmpty()) return sampleRate in sampleRates
            val min = minSampleRate
            val max = maxSampleRate
            return min != null && max != null && sampleRate in min..max
        }

        fun matchesFormat(sampleRate: Int, channels: Int): Boolean =
            (protocol == 0x00 || protocol == 0x20) &&
                (this.channels == 0 || this.channels == channels) &&
                supportsSampleRate(sampleRate)
    }

    private data class AltBuilder(
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val protocol: Int,
        var terminalLink: Int = 0,
        var channels: Int = 0,
        var subslotBytes: Int = 0,
        var bitResolution: Int = 0,
        val sampleRates: MutableSet<Int> = linkedSetOf(),
        var minSampleRate: Int? = null,
        var maxSampleRate: Int? = null,
        var endpointAddress: Int = 0,
        var maxPacketSize: Int = 0,
        var interval: Int = 0,
        var synchronizationType: Int = SYNC_NONE,
    )

    fun parse(raw: ByteArray): List<StreamingAlt> {
        val builders = linkedMapOf<Pair<Int, Int>, AltBuilder>()
        val terminalClock = mutableMapOf<Int, Pair<Int, Int>>()

        var offset = 0
        var currentClass = -1
        var currentSubclass = -1
        var currentProtocol = 0
        var currentInterface = -1
        var currentAlternate = 0
        var currentBuilder: AltBuilder? = null

        while (offset + 2 <= raw.size) {
            val length = raw.u8(offset)
            if (length < 2 || offset + length > raw.size) break
            val type = raw.u8(offset + 1)

            when (type) {
                DESC_INTERFACE -> if (length >= 9) {
                    currentInterface = raw.u8(offset + 2)
                    currentAlternate = raw.u8(offset + 3)
                    currentClass = raw.u8(offset + 5)
                    currentSubclass = raw.u8(offset + 6)
                    currentProtocol = raw.u8(offset + 7)
                    currentBuilder = if (
                        currentClass == AUDIO_CLASS &&
                        currentSubclass == AUDIO_STREAMING_SUBCLASS &&
                        currentAlternate > 0
                    ) {
                        builders.getOrPut(currentInterface to currentAlternate) {
                            AltBuilder(
                                interfaceNumber = currentInterface,
                                alternateSetting = currentAlternate,
                                protocol = currentProtocol,
                            )
                        }
                    } else {
                        null
                    }
                }

                DESC_CS_INTERFACE -> if (length >= 3) {
                    val subtype = raw.u8(offset + 2)
                    if (currentClass == AUDIO_CLASS && currentSubclass == AUDIO_STREAMING_SUBCLASS) {
                        val builder = currentBuilder
                        if (builder != null) {
                            when (subtype) {
                                CS_AS_GENERAL -> {
                                    if (length >= 4) builder.terminalLink = raw.u8(offset + 3)
                                    // UAC2 AS_GENERAL carries bNrChannels at byte 10.
                                    if (currentProtocol >= 0x20 && length >= 11) {
                                        builder.channels = raw.u8(offset + 10)
                                    }
                                }

                                CS_FORMAT_TYPE -> if (length >= 6 && raw.u8(offset + 3) == FORMAT_TYPE_I) {
                                    if (currentProtocol >= 0x20) {
                                        // UAC2 FORMAT_TYPE_I: bSubslotSize, bBitResolution.
                                        builder.subslotBytes = raw.u8(offset + 4)
                                        builder.bitResolution = raw.u8(offset + 5)
                                    } else if (length >= 8) {
                                        // UAC1 FORMAT_TYPE_I.
                                        builder.channels = raw.u8(offset + 4)
                                        builder.subslotBytes = raw.u8(offset + 5)
                                        builder.bitResolution = raw.u8(offset + 6)
                                        val frequencyType = raw.u8(offset + 7)
                                        if (frequencyType == 0 && length >= 14) {
                                            builder.minSampleRate = raw.u24le(offset + 8)
                                            builder.maxSampleRate = raw.u24le(offset + 11)
                                        } else {
                                            repeat(frequencyType) { index ->
                                                val position = offset + 8 + index * 3
                                                if (position + 3 <= offset + length) {
                                                    builder.sampleRates += raw.u24le(position)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (
                        currentClass == AUDIO_CLASS &&
                        currentSubclass == AUDIO_CONTROL_SUBCLASS &&
                        currentProtocol >= 0x20
                    ) {
                        // UAC2 terminals contain the clock source used by the linked AS interface.
                        when (subtype) {
                            AC_INPUT_TERMINAL -> if (length >= 8) {
                                val terminalId = raw.u8(offset + 3)
                                terminalClock[terminalId] = currentInterface to raw.u8(offset + 7)
                            }
                            AC_OUTPUT_TERMINAL -> if (length >= 9) {
                                val terminalId = raw.u8(offset + 3)
                                terminalClock[terminalId] = currentInterface to raw.u8(offset + 8)
                            }
                        }
                    }
                }

                DESC_ENDPOINT -> {
                    val builder = currentBuilder
                    if (builder != null && length >= 7) {
                        val address = raw.u8(offset + 2)
                        val attributes = raw.u8(offset + 3)
                        val transferType = attributes and 0x03
                        val isOut = address and 0x80 == 0
                        if (transferType == 0x01 && isOut) {
                            val rawMaxPacket = raw.u16le(offset + 4)
                            val basePacket = rawMaxPacket and 0x07ff
                            val transactions = 1 + ((rawMaxPacket ushr 11) and 0x03)
                            builder.endpointAddress = address
                            builder.maxPacketSize = basePacket * transactions
                            builder.interval = raw.u8(offset + 6).coerceAtLeast(1)
                            builder.synchronizationType = (attributes ushr 2) and 0x03
                        }
                    }
                }
            }
            offset += length
        }

        return builders.values.mapNotNull { builder ->
            if (
                builder.endpointAddress == 0 ||
                builder.maxPacketSize <= 0 ||
                builder.subslotBytes !in 2..4 ||
                builder.bitResolution !in 8..32
            ) {
                return@mapNotNull null
            }
            val clock = terminalClock[builder.terminalLink]
            StreamingAlt(
                interfaceNumber = builder.interfaceNumber,
                alternateSetting = builder.alternateSetting,
                protocol = builder.protocol,
                channels = builder.channels,
                subslotBytes = builder.subslotBytes,
                bitResolution = builder.bitResolution,
                sampleRates = builder.sampleRates.toSet(),
                minSampleRate = builder.minSampleRate,
                maxSampleRate = builder.maxSampleRate,
                endpointAddress = builder.endpointAddress,
                maxPacketSize = builder.maxPacketSize,
                interval = builder.interval,
                synchronizationType = builder.synchronizationType,
                audioControlInterface = clock?.first ?: -1,
                clockSourceId = clock?.second ?: 0,
            )
        }
    }

    fun select(
        alternatives: List<StreamingAlt>,
        sampleRate: Int,
        channels: Int,
        preferredBits: Int,
    ): StreamingAlt? = alternatives
        .asSequence()
        .filter { it.matchesFormat(sampleRate, channels) }
        // Phase 1 intentionally refuses asynchronous endpoints. Their DAC clock
        // must be paced from a feedback endpoint; sending nominal-rate packets
        // without feedback eventually drifts and is not acceptable for a direct
        // audio path.
        .filterNot(StreamingAlt::requiresExplicitFeedback)
        .sortedWith(
            compareByDescending<StreamingAlt> {
                // Prefer formats nearest the source precision, then the higher resolution.
                1000 - abs(it.bitResolution - preferredBits) * 20 + it.bitResolution
            }.thenByDescending { it.subslotBytes },
        )
        .firstOrNull()

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.u16le(offset: Int): Int =
        u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.u24le(offset: Int): Int =
        u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16)
}
