#include "UsbFeedbackClock.h"

using usb_feedback::PacketScheduler;
using usb_feedback::decodeFeedbackQ16;
using usb_feedback::feedbackFitsPacket;
using usb_feedback::feedbackMatchesRate;
using usb_feedback::nominalFeedbackQ16;
using usb_feedback::serviceTicks;

constexpr bool decodesQ10_14() {
    constexpr uint8_t payload[] = {0x00, 0x00, 0x0c};
    uint32_t result = 0;
    return decodeFeedbackQ16(payload, 3, &result) && result == (48u << 16);
}

constexpr bool decodesQ16_16() {
    constexpr uint8_t payload[] = {0x00, 0x00, 0x06, 0x00};
    uint32_t result = 0;
    return decodeFeedbackQ16(payload, 4, &result) && result == (6u << 16);
}

constexpr bool rejectsInvalidLength() {
    constexpr uint8_t payload[] = {0x00, 0x00};
    uint32_t result = 123;
    return !decodeFeedbackQ16(payload, 2, &result) && result == 123;
}

constexpr bool schedulesFullSpeed48k() {
    PacketScheduler scheduler(48u << 16);
    return scheduler.nextFrames(1) == 48 && scheduler.nextFrames(1) == 48;
}

constexpr bool schedulesHighSpeed48k() {
    PacketScheduler scheduler(6u << 16);
    return scheduler.nextFrames(1) == 6 && scheduler.nextFrames(1) == 6;
}

constexpr bool accumulatesFullSpeed44100() {
    PacketScheduler scheduler(nominalFeedbackQ16(44'100, 1'000));
    int frames = 0;
    for (int packet = 0; packet < 10; ++packet) frames += scheduler.nextFrames(1);
    return frames == 441;
}

constexpr bool scalesHighSpeedIntervals() {
    PacketScheduler scheduler(6u << 16);
    return serviceTicks(true, 4) == 8 && scheduler.nextFrames(8) == 48;
}

constexpr bool preservesPhaseAcrossFeedbackUpdate() {
    PacketScheduler scheduler((44u << 16) + 32'768u);
    if (scheduler.nextFrames(1) != 44) return false;
    scheduler.updateFeedback(45u << 16);
    return scheduler.nextFrames(1) == 45 && scheduler.phaseQ16() == 32'768u;
}

static_assert(decodesQ10_14());
static_assert(decodesQ16_16());
static_assert(rejectsInvalidLength());
static_assert(schedulesFullSpeed48k());
static_assert(schedulesHighSpeed48k());
static_assert(accumulatesFullSpeed44100());
static_assert(scalesHighSpeedIntervals());
static_assert(preservesPhaseAcrossFeedbackUpdate());
static_assert(serviceTicks(false, 4) == 4);
static_assert(feedbackMatchesRate(48u << 16, 1'000, 48'000));
static_assert(!feedbackMatchesRate(1u << 16, 1'000, 48'000));
static_assert(feedbackFitsPacket(48u << 16, 1, 8, 384));
static_assert(!feedbackFitsPacket(49u << 16, 1, 8, 384));
