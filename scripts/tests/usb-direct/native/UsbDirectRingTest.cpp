// Compile the production engine into this test translation unit, replacing
// only Linux syscall declarations on Windows. No device or JVM is needed.
#include "../../../../../app/src/main/cpp/UsbDirectUac.cpp"
#include <cassert>
#include <iostream>

int main() {
    auto session = std::make_shared<Session>();
    session->channels = 1;
    session->targetSubslotBytes = 2;
    session->targetFrameBytes = 2;
    session->ring.resize(4);
    // Exercise the real ring without starting a USB worker or making syscalls.
    session->workerStarted.store(true);
    const jlong handle = registerSession(session);
    const uint8_t first[] = {1, 2};
    const uint8_t second[] = {3, 4};
    uint8_t output[4] = {};

    assert(enqueueTarget(session.get(), first, sizeof(first), false) == 2);
    Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeSuspend(nullptr, nullptr, handle);
    assert(enqueueTarget(session.get(), second, sizeof(second), false) == 2);
    assert(session->ringCount == 4);
    copyFromRingOrSilence(session.get(), output, sizeof(output));
    assert((std::array<uint8_t, 4>{output[0], output[1], output[2], output[3]} ==
            std::array<uint8_t, 4>{0, 0, 0, 0}));
    assert(session->ringCount == 4); // Silence during pause must not consume PCM.
    assert(enqueueTarget(session.get(), first, sizeof(first), false) == 0);
    const auto started = std::chrono::steady_clock::now();
    assert(enqueueTarget(session.get(), first, sizeof(first), true) == 0);
    assert(std::chrono::steady_clock::now() - started >= std::chrono::milliseconds(400));

    Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeResume(nullptr, nullptr, handle);
    copyFromRingOrSilence(session.get(), output, sizeof(output));
    assert((std::array<uint8_t, 4>{output[0], output[1], output[2], output[3]} ==
            std::array<uint8_t, 4>{1, 2, 3, 4}));
    assert(session->ringCount == 0);

    IsoSlot slot;
    slot.urbStorage.resize(sizeof(usbdevfs_urb) + kIsoPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc));
    slot.urb = reinterpret_cast<usbdevfs_urb*>(slot.urbStorage.data());
    slot.buffer.resize(4 * 96);
    session->maxPacketSize = 96;
    session->packetScheduler.updateFeedback(usb_feedback::nominalFeedbackQ16(48000, 1000));
    assert(enqueueTarget(session.get(), output, sizeof(output), false) == 4);
    assert(fillAudioSlot(session.get(), &slot));
    assert(session->renderedFrames == 0); // Enqueue/submit is not playback completion.
    assert(slot.pcmFrames[0] == 2);
    recordAudioCompletion(session.get(), &slot);
    assert(session->renderedFrames == 2 && session->renderedAtNanos > 0);
    assert(fillAudioSlot(session.get(), &slot));
    recordAudioCompletion(session.get(), &slot);
    assert(session->renderedFrames == 2); // Underrun silence does not advance source PCM.
    assert(enqueueTarget(session.get(), first, sizeof(first), false) == 2);
    Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeSuspend(nullptr, nullptr, handle);
    assert(fillAudioSlot(session.get(), &slot));
    recordAudioCompletion(session.get(), &slot);
    assert(session->renderedFrames == 2 && session->ringCount == 2);
    Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeResume(nullptr, nullptr, handle);
    assert(fillAudioSlot(session.get(), &slot));
    slot.urb->status = -1;
    recordAudioCompletion(session.get(), &slot);
    assert(session->renderedFrames == 2); // Failed transfers are not played PCM.
    slot.urb->status = 0;

    assert(enqueueTarget(session.get(), first, sizeof(first), false) == 2);
    Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeFlush(nullptr, nullptr, handle);
    assert(session->ringCount == 0);
    assert(session->ringRead == 0 && session->ringWrite == 0);
    recordAudioCompletion(session.get(), &slot);
    assert(session->renderedFrames == 0 && session->renderedAtNanos == 0);
    // The previous slot's completion belongs to the timeline before flush.
    Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeClose(nullptr, nullptr, handle);
    assert(findSession(handle) == nullptr);
    std::cout << "PASS: PCM retention/backpressure and USB completion clock, silence/pause/failure/flush epochs\n";
}
