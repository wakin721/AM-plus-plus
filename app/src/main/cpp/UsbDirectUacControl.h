#pragma once

#include <array>
#include <cstdint>

namespace usb_direct_uac {

constexpr std::array<uint8_t, 3> uac1RatePayload(int sampleRate) {
    return {
        static_cast<uint8_t>(sampleRate & 0xff),
        static_cast<uint8_t>((sampleRate >> 8) & 0xff),
        static_cast<uint8_t>((sampleRate >> 16) & 0xff),
    };
}

constexpr std::array<uint8_t, 4> uac2RatePayload(int sampleRate) {
    return {
        static_cast<uint8_t>(sampleRate & 0xff),
        static_cast<uint8_t>((sampleRate >> 8) & 0xff),
        static_cast<uint8_t>((sampleRate >> 16) & 0xff),
        static_cast<uint8_t>((sampleRate >> 24) & 0xff),
    };
}

constexpr int uac2ControlIndex(int clockSourceId, int audioControlInterface) {
    return (clockSourceId << 8) | (audioControlInterface & 0xff);
}

constexpr bool acceptUac1SetCurResult(int transferred, bool fixedSampleRateMatch) {
    return transferred == 3 || fixedSampleRateMatch;
}

}  // namespace usb_direct_uac
