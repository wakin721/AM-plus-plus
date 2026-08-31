#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace usb_feedback {

constexpr bool decodeFeedbackQ16(
    const uint8_t* data,
    size_t length,
    uint32_t* result
) {
    if (data == nullptr || result == nullptr) return false;
    uint32_t value = 0;
    if (length == 3) {
        value = (
            static_cast<uint32_t>(data[0]) |
            (static_cast<uint32_t>(data[1]) << 8) |
            (static_cast<uint32_t>(data[2]) << 16)
        ) << 2;
    } else if (length == 4) {
        value = static_cast<uint32_t>(data[0]) |
            (static_cast<uint32_t>(data[1]) << 8) |
            (static_cast<uint32_t>(data[2]) << 16) |
            (static_cast<uint32_t>(data[3]) << 24);
    } else {
        return false;
    }
    *result = value;
    return true;
}

constexpr uint32_t nominalFeedbackQ16(int sampleRate, int busTicksPerSecond) {
    if (sampleRate <= 0 || busTicksPerSecond <= 0) return 0;
    return static_cast<uint32_t>(
        ((static_cast<uint64_t>(sampleRate) << 16) + busTicksPerSecond / 2) /
        static_cast<uint64_t>(busTicksPerSecond)
    );
}

constexpr int serviceTicks(bool highOrSuperSpeed, int endpointInterval) {
    if (!highOrSuperSpeed) return std::clamp(endpointInterval, 1, 255);
    const int exponent = std::clamp(endpointInterval, 1, 16) - 1;
    return 1 << exponent;
}

constexpr bool feedbackMatchesRate(
    uint32_t feedbackQ16,
    int busTicksPerSecond,
    int sampleRate
) {
    if (feedbackQ16 == 0 || busTicksPerSecond <= 0 || sampleRate <= 0) return false;
    const uint64_t actual = static_cast<uint64_t>(feedbackQ16) * busTicksPerSecond;
    const uint64_t nominal = static_cast<uint64_t>(sampleRate) << 16;
    return actual * 4 >= nominal * 3 && actual * 4 <= nominal * 5;
}

constexpr bool feedbackFitsPacket(
    uint32_t feedbackQ16,
    int ticksPerServiceInterval,
    int frameBytes,
    int maxPacketSize
) {
    if (
        feedbackQ16 == 0 || ticksPerServiceInterval <= 0 ||
        frameBytes <= 0 || maxPacketSize <= 0
    ) return false;
    const uint64_t intervalQ16 =
        static_cast<uint64_t>(feedbackQ16) * ticksPerServiceInterval;
    const uint64_t maximumFrames = (intervalQ16 + 0xffffu) >> 16;
    return maximumFrames * static_cast<uint64_t>(frameBytes) <=
        static_cast<uint64_t>(maxPacketSize);
}

class PacketScheduler {
public:
    constexpr explicit PacketScheduler(uint32_t feedbackQ16)
        : feedbackQ16_(feedbackQ16) {}

    constexpr void updateFeedback(uint32_t feedbackQ16) {
        feedbackQ16_ = feedbackQ16;
    }

    constexpr int nextFrames(int ticksPerServiceInterval) {
        phaseQ16_ += static_cast<uint64_t>(feedbackQ16_) *
            static_cast<uint64_t>(std::max(1, ticksPerServiceInterval));
        const uint64_t frames = phaseQ16_ >> 16;
        phaseQ16_ &= 0xffffu;
        return frames > static_cast<uint64_t>(std::numeric_limits<int>::max())
            ? std::numeric_limits<int>::max()
            : static_cast<int>(frames);
    }

    constexpr uint32_t phaseQ16() const {
        return static_cast<uint32_t>(phaseQ16_);
    }

private:
    uint32_t feedbackQ16_ = 0;
    uint64_t phaseQ16_ = 0;
};

}  // namespace usb_feedback
