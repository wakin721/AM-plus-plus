#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace usb_direct_buffer {

constexpr std::size_t ringBytesForDuration(
    int sampleRate,
    std::size_t frameBytes,
    int bufferMillis
) {
    if (sampleRate <= 0 || frameBytes == 0 || bufferMillis <= 0) return frameBytes;
    const std::uint64_t bytes =
        static_cast<std::uint64_t>(sampleRate) * frameBytes *
        static_cast<std::uint64_t>(bufferMillis) / 1000u;
    const std::size_t aligned =
        static_cast<std::size_t>(bytes - (bytes % frameBytes));
    return std::max(frameBytes, aligned);
}

constexpr int isoUrbCountForTarget(
    int busTicksPerSecond,
    int serviceTicks,
    int targetMillis,
    int packetsPerUrb
) {
    if (targetMillis <= 0) return 4;
    if (busTicksPerSecond <= 0 || serviceTicks <= 0 || packetsPerUrb <= 0) return 4;

    const std::int64_t numerator =
        static_cast<std::int64_t>(targetMillis) *
        static_cast<std::int64_t>(busTicksPerSecond);
    const std::int64_t denominator =
        static_cast<std::int64_t>(1000) *
        static_cast<std::int64_t>(serviceTicks) *
        static_cast<std::int64_t>(packetsPerUrb);

    const std::int64_t roundedUp = (numerator + denominator - 1) / denominator;
    return static_cast<int>(std::clamp<std::int64_t>(roundedUp, 1, 32));
}

}  // namespace usb_direct_buffer
