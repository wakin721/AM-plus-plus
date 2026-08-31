#pragma once

#include <cstdint>

namespace usb_pcm {

constexpr uint64_t leftJustifiedSampleBits(
    int64_t sample,
    int subslotBytes,
    int bitResolution
) {
    const int paddingBits = subslotBytes * 8 - bitResolution;
    const uint64_t sampleMask = (uint64_t{1} << bitResolution) - 1;
    return (static_cast<uint64_t>(sample) & sampleMask) << paddingBits;
}

constexpr uint8_t littleEndianSampleByte(
    int64_t sample,
    int subslotBytes,
    int bitResolution,
    int byteIndex
) {
    return static_cast<uint8_t>(
        (leftJustifiedSampleBits(sample, subslotBytes, bitResolution) >> (byteIndex * 8)) & 0xffu
    );
}

}  // namespace usb_pcm
