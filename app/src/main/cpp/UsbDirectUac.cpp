#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "UsbDirectBufferSizing.h"
#include "UsbDirectUacControl.h"
#include "UsbFeedbackClock.h"
#include "UsbPcmPacking.h"

namespace {

constexpr int kFormatI16 = 1;
constexpr int kFormatFloat = 2;
constexpr int kFormatI24 = 3;
constexpr int kFormatI32 = 4;

constexpr int kIsoPacketsPerUrb = 4;
constexpr int kFeedbackUrbCount = 2;
constexpr int kMaxInvalidFeedbackPackets = 8;
constexpr auto kFeedbackTimeout = std::chrono::seconds(2);
constexpr int kWriteWaitMillis = 500;
constexpr int kControlTimeoutMillis = 1000;
constexpr uint8_t kUacCur = 0x01;
constexpr uint16_t kSamplingFreqControl = 0x01 << 8;
constexpr uint8_t kUsbDirOut = 0x00;
constexpr uint8_t kUsbDirIn = 0x80;
constexpr uint8_t kUsbTypeClass = 0x20;
constexpr uint8_t kUsbRecipientInterface = 0x01;
constexpr uint8_t kUsbRecipientEndpoint = 0x02;

constexpr int kSessionStateMissing = 0;
constexpr int kSessionStateClaimed = 1;
constexpr int kSessionStateStreaming = 2;
constexpr int kSessionStateFailed = 3;

std::mutex gErrorMutex;
std::string gLastError;

void setError(const std::string& message) {
    std::lock_guard<std::mutex> lock(gErrorMutex);
    gLastError = message;
}

std::string getError() {
    std::lock_guard<std::mutex> lock(gErrorMutex);
    return gLastError;
}

int inputBytesPerSample(int formatCode) {
    switch (formatCode) {
        case kFormatI16: return 2;
        case kFormatFloat: return 4;
        case kFormatI24: return 3;
        case kFormatI32: return 4;
        default: return 0;
    }
}

enum class IsoRole {
    AudioOut,
    FeedbackIn,
};

struct IsoSlot {
    IsoRole role = IsoRole::AudioOut;
    std::vector<uint8_t> urbStorage;
    std::vector<uint8_t> buffer;
    usbdevfs_urb* urb = nullptr;
};

struct Session {
    int fd = -1;
    int sampleRate = 0;
    int inputFormat = 0;
    int inputBytesPerSample = 0;
    int channels = 0;
    int interfaceNumber = -1;
    int alternateSetting = 0;
    int audioControlInterface = -1;
    int clockSourceId = 0;
    int protocol = 0;
    bool fixedSampleRateMatch = false;
    bool controlInterfaceClaimed = false;
    bool streamingInterfaceClaimed = false;
    bool alternateSettingActive = false;
    int endpointAddress = 0;
    int maxPacketSize = 0;
    int interval = 1;
    int feedbackEndpointAddress = 0;
    int feedbackMaxPacketSize = 0;
    int feedbackInterval = 0;
    bool usesExplicitFeedback = false;
    int targetSubslotBytes = 0;
    int targetBitResolution = 0;
    size_t targetFrameBytes = 0;
    bool highOrSuperSpeed = false;
    int busTicksPerSecond = 1000;
    int outputServiceTicks = 1;
    usb_feedback::PacketScheduler packetScheduler{0};
    bool hasValidFeedback = false;
    int invalidFeedbackPackets = 0;
    std::chrono::steady_clock::time_point feedbackStartedAt{};
    std::chrono::steady_clock::time_point lastValidFeedbackAt{};
    std::string lastFeedbackError;

    std::vector<uint8_t> ring;
    size_t ringRead = 0;
    size_t ringWrite = 0;
    size_t ringCount = 0;
    std::mutex ringMutex;
    std::condition_variable spaceAvailable;
    std::mutex lifecycleMutex;

    std::atomic<bool> running{false};
    std::atomic<bool> failed{false};
    std::atomic<bool> closing{false};
    std::atomic<bool> workerStarted{false};
    std::thread worker;
    std::vector<std::unique_ptr<IsoSlot>> slots;
};

std::mutex gSessionsMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> gSessions;
std::atomic<jlong> gNextSessionHandle{1};

jlong registerSession(const std::shared_ptr<Session>& session) {
    const jlong handle = gNextSessionHandle.fetch_add(1);
    std::lock_guard<std::mutex> lock(gSessionsMutex);
    gSessions.emplace(handle, session);
    return handle;
}

std::shared_ptr<Session> findSession(jlong handle) {
    std::lock_guard<std::mutex> lock(gSessionsMutex);
    const auto found = gSessions.find(handle);
    return found == gSessions.end() ? nullptr : found->second;
}

std::shared_ptr<Session> takeSession(jlong handle) {
    std::lock_guard<std::mutex> lock(gSessionsMutex);
    const auto found = gSessions.find(handle);
    if (found == gSessions.end()) return nullptr;
    auto session = found->second;
    gSessions.erase(found);
    return session;
}

bool isHighOrSuperSpeed(int fd) {
    int speed = -1;
#ifdef USBDEVFS_GET_SPEED
    speed = ioctl(fd, USBDEVFS_GET_SPEED);
#endif
    // Linux enum usb_device_speed: FULL=2, HIGH=3, SUPER=5, SUPER_PLUS=6.
    return speed == 3 || speed == 5 || speed == 6;
}


bool claimInterface(Session* session, int interfaceNumber) {
    if (session == nullptr || session->fd < 0 || interfaceNumber < 0) return false;
    unsigned int iface = static_cast<unsigned int>(interfaceNumber);
    if (ioctl(session->fd, USBDEVFS_CLAIMINTERFACE, &iface) == 0) return true;

#if defined(USBDEVFS_DISCONNECT_CLAIM) && defined(USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER)
    usbdevfs_disconnect_claim disconnectClaim{};
    disconnectClaim.interface = static_cast<unsigned int>(interfaceNumber);
    disconnectClaim.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    std::strncpy(
        disconnectClaim.driver,
        "snd-usb-audio",
        sizeof(disconnectClaim.driver) - 1
    );
    if (ioctl(session->fd, USBDEVFS_DISCONNECT_CLAIM, &disconnectClaim) == 0) {
        return true;
    }
#endif

    setError(
        "USBDEVFS_CLAIMINTERFACE failed for interface " +
        std::to_string(interfaceNumber) +
        " errno=" + std::to_string(errno)
    );
    return false;
}

bool claimInterfaces(Session* session) {
    if (session->protocol >= 0x20) {
        if (!claimInterface(session, session->audioControlInterface)) return false;
        session->controlInterfaceClaimed = true;
    }

    if (
        session->audioControlInterface != session->interfaceNumber ||
        !session->controlInterfaceClaimed
    ) {
        if (!claimInterface(session, session->interfaceNumber)) return false;
    }
    session->streamingInterfaceClaimed = true;
    return true;
}

void reconnectKernelDriver(Session* session, int interfaceNumber) {
    if (session == nullptr || session->fd < 0 || interfaceNumber < 0) return;
    usbdevfs_ioctl control{};
    control.ifno = interfaceNumber;
    control.ioctl_code = USBDEVFS_CONNECT;
    control.data = nullptr;
    ioctl(session->fd, USBDEVFS_IOCTL, &control);
}

void releaseInterfaces(Session* session) {
    if (session == nullptr || session->fd < 0) return;
    if (session->streamingInterfaceClaimed) {
        unsigned int iface = static_cast<unsigned int>(session->interfaceNumber);
        if (ioctl(session->fd, USBDEVFS_RELEASEINTERFACE, &iface) == 0) {
            reconnectKernelDriver(session, session->interfaceNumber);
        }
        session->streamingInterfaceClaimed = false;
    }
    if (
        session->controlInterfaceClaimed &&
        session->audioControlInterface != session->interfaceNumber
    ) {
        unsigned int iface = static_cast<unsigned int>(session->audioControlInterface);
        if (ioctl(session->fd, USBDEVFS_RELEASEINTERFACE, &iface) == 0) {
            reconnectKernelDriver(session, session->audioControlInterface);
        }
    }
    session->controlInterfaceClaimed = false;
    session->alternateSettingActive = false;
}

bool selectStreamingAlternate(Session* session) {
    usbdevfs_setinterface setting{};
    setting.interface = session->interfaceNumber;
    setting.altsetting = session->alternateSetting;
    if (ioctl(session->fd, USBDEVFS_SETINTERFACE, &setting) == 0) {
        session->alternateSettingActive = true;
        return true;
    }
    setError(
        "USBDEVFS_SETINTERFACE failed interface=" +
        std::to_string(session->interfaceNumber) +
        " alt=" + std::to_string(session->alternateSetting) +
        " errno=" + std::to_string(errno)
    );
    return false;
}

int controlTransfer(
    Session* session,
    uint8_t requestType,
    uint8_t request,
    uint16_t value,
    uint16_t index,
    void* data,
    uint16_t length
) {
    usbdevfs_ctrltransfer transfer{};
    transfer.bRequestType = requestType;
    transfer.bRequest = request;
    transfer.wValue = value;
    transfer.wIndex = index;
    transfer.wLength = length;
    transfer.timeout = kControlTimeoutMillis;
    transfer.data = data;
    return ioctl(session->fd, USBDEVFS_CONTROL, &transfer);
}

uint32_t u32le(const uint8_t* data) {
    return static_cast<uint32_t>(data[0]) |
        (static_cast<uint32_t>(data[1]) << 8) |
        (static_cast<uint32_t>(data[2]) << 16) |
        (static_cast<uint32_t>(data[3]) << 24);
}

bool configureUac1Rate(Session* session) {
    auto payload = usb_direct_uac::uac1RatePayload(session->sampleRate);
    const int transferred = controlTransfer(
        session,
        kUsbDirOut | kUsbTypeClass | kUsbRecipientEndpoint,
        kUacCur,
        kSamplingFreqControl,
        static_cast<uint16_t>(session->endpointAddress),
        payload.data(),
        static_cast<uint16_t>(payload.size())
    );
    if (usb_direct_uac::acceptUac1SetCurResult(
            transferred,
            session->fixedSampleRateMatch
        )) {
        return true;
    }
    setError(
        "UAC1 sample-rate control rejected " +
        std::to_string(session->sampleRate) + "Hz"
    );
    return false;
}

bool configureUac2Rate(Session* session) {
    const uint16_t index = static_cast<uint16_t>(
        usb_direct_uac::uac2ControlIndex(
            session->clockSourceId,
            session->audioControlInterface
        )
    );

    std::array<uint8_t, 4> current{};
    const int initialRead = controlTransfer(
        session,
        kUsbDirIn | kUsbTypeClass | kUsbRecipientInterface,
        kUacCur,
        kSamplingFreqControl,
        index,
        current.data(),
        static_cast<uint16_t>(current.size())
    );
    if (
        initialRead == static_cast<int>(current.size()) &&
        u32le(current.data()) == static_cast<uint32_t>(session->sampleRate)
    ) {
        return true;
    }

    auto target = usb_direct_uac::uac2RatePayload(session->sampleRate);
    const int written = controlTransfer(
        session,
        kUsbDirOut | kUsbTypeClass | kUsbRecipientInterface,
        kUacCur,
        kSamplingFreqControl,
        index,
        target.data(),
        static_cast<uint16_t>(target.size())
    );
    if (written != static_cast<int>(target.size())) {
        setError(
            "UAC2 sample-rate control rejected " +
            std::to_string(session->sampleRate) + "Hz"
        );
        return false;
    }

    if (initialRead != static_cast<int>(current.size())) return true;

    current.fill(0);
    const int verifyRead = controlTransfer(
        session,
        kUsbDirIn | kUsbTypeClass | kUsbRecipientInterface,
        kUacCur,
        kSamplingFreqControl,
        index,
        current.data(),
        static_cast<uint16_t>(current.size())
    );
    if (
        verifyRead == static_cast<int>(current.size()) &&
        u32le(current.data()) == static_cast<uint32_t>(session->sampleRate)
    ) {
        return true;
    }

    setError("UAC2 sample-rate verification failed");
    return false;
}

bool configureSampleRate(Session* session) {
    return session->protocol >= 0x20
        ? configureUac2Rate(session)
        : configureUac1Rate(session);
}

int framesForNextInterval(Session* session) {
    return session->packetScheduler.nextFrames(session->outputServiceTicks);
}

void copyFromRingOrSilence(Session* session, uint8_t* destination, size_t bytes) {
    std::unique_lock<std::mutex> lock(session->ringMutex);
    size_t copied = 0;
    while (copied < bytes && session->ringCount > 0) {
        const size_t contiguous = std::min({
            bytes - copied,
            session->ringCount,
            session->ring.size() - session->ringRead,
        });
        std::memcpy(destination + copied, session->ring.data() + session->ringRead, contiguous);
        session->ringRead = (session->ringRead + contiguous) % session->ring.size();
        session->ringCount -= contiguous;
        copied += contiguous;
    }
    if (copied < bytes) {
        std::memset(destination + copied, 0, bytes - copied);
    }
    lock.unlock();
    session->spaceAvailable.notify_all();
}

bool fillAudioSlot(Session* session, IsoSlot* slot) {
    if (
        slot == nullptr || slot->urb == nullptr ||
        slot->role != IsoRole::AudioOut
    ) return false;
    auto* urb = slot->urb;
    int totalBytes = 0;
    for (int packet = 0; packet < kIsoPacketsPerUrb; ++packet) {
        const int frames = framesForNextInterval(session);
        const int packetBytes = frames * static_cast<int>(session->targetFrameBytes);
        if (packetBytes < 0 || packetBytes > session->maxPacketSize) {
            setError(
                "USB Direct packet exceeds endpoint wMaxPacketSize: " +
                std::to_string(packetBytes) + " > " + std::to_string(session->maxPacketSize)
            );
            session->failed.store(true);
            return false;
        }
        urb->iso_frame_desc[packet].length = static_cast<unsigned int>(packetBytes);
        urb->iso_frame_desc[packet].actual_length = 0;
        urb->iso_frame_desc[packet].status = 0;
        if (packetBytes > 0) {
            copyFromRingOrSilence(session, slot->buffer.data() + totalBytes, packetBytes);
            totalBytes += packetBytes;
        }
    }
    urb->buffer_length = totalBytes;
    urb->actual_length = 0;
    urb->status = 0;
    urb->error_count = 0;
    return true;
}

bool submitAudioSlot(Session* session, IsoSlot* slot) {
    if (!fillAudioSlot(session, slot)) return false;
    if (ioctl(session->fd, USBDEVFS_SUBMITURB, slot->urb) < 0) {
        setError("USBDEVFS_SUBMITURB failed errno=" + std::to_string(errno));
        session->failed.store(true);
        return false;
    }
    return true;
}

bool submitFeedbackSlot(Session* session, IsoSlot* slot) {
    if (
        slot == nullptr || slot->urb == nullptr ||
        slot->role != IsoRole::FeedbackIn
    ) return false;
    auto* urb = slot->urb;
    urb->buffer_length = session->feedbackMaxPacketSize;
    urb->actual_length = 0;
    urb->status = 0;
    urb->error_count = 0;
    urb->iso_frame_desc[0].length =
        static_cast<unsigned int>(session->feedbackMaxPacketSize);
    urb->iso_frame_desc[0].actual_length = 0;
    urb->iso_frame_desc[0].status = 0;
    if (ioctl(session->fd, USBDEVFS_SUBMITURB, urb) < 0) {
        setError("USB feedback SUBMITURB failed errno=" + std::to_string(errno));
        session->failed.store(true);
        return false;
    }
    return true;
}

bool recordInvalidFeedback(Session* session, const std::string& reason) {
    session->lastFeedbackError = reason;
    session->invalidFeedbackPackets++;
    if (session->invalidFeedbackPackets < kMaxInvalidFeedbackPackets) return true;
    setError("USB Direct feedback payload invalid: " + reason);
    session->failed.store(true);
    return false;
}

bool consumeFeedback(Session* session, IsoSlot* slot) {
    auto* urb = slot->urb;
    auto& packet = urb->iso_frame_desc[0];
    if (urb->status != 0 || urb->error_count != 0 || packet.status != 0) {
        setError(
            "USB feedback URB failed status=" + std::to_string(urb->status) +
            " packetStatus=" + std::to_string(packet.status)
        );
        session->failed.store(true);
        return false;
    }
    uint32_t feedbackQ16 = 0;
    const size_t length = static_cast<size_t>(packet.actual_length);
    if (!usb_feedback::decodeFeedbackQ16(slot->buffer.data(), length, &feedbackQ16)) {
        return recordInvalidFeedback(session, "length=" + std::to_string(length));
    }
    if (!usb_feedback::feedbackMatchesRate(
            feedbackQ16,
            session->busTicksPerSecond,
            session->sampleRate
        )) {
        return recordInvalidFeedback(session, "rate outside 75%-125% window");
    }
    if (!usb_feedback::feedbackFitsPacket(
            feedbackQ16,
            session->outputServiceTicks,
            static_cast<int>(session->targetFrameBytes),
            session->maxPacketSize
        )) {
        return recordInvalidFeedback(session, "packet exceeds wMaxPacketSize");
    }
    session->packetScheduler.updateFeedback(feedbackQ16);
    session->hasValidFeedback = true;
    session->invalidFeedbackPackets = 0;
    session->lastFeedbackError.clear();
    session->lastValidFeedbackAt = std::chrono::steady_clock::now();
    return true;
}

bool feedbackTimedOut(Session* session) {
    if (!session->usesExplicitFeedback) return false;
    const auto now = std::chrono::steady_clock::now();
    const auto reference = session->hasValidFeedback
        ? session->lastValidFeedbackAt
        : session->feedbackStartedAt;
    if (now - reference <= kFeedbackTimeout) return false;
    setError("USB Direct feedback timeout");
    session->failed.store(true);
    return true;
}

void directWorker(Session* session) {
    session->feedbackStartedAt = std::chrono::steady_clock::now();
    for (const auto& slot : session->slots) {
        if (slot->role != IsoRole::FeedbackIn) continue;
        if (!session->running.load() || !submitFeedbackSlot(session, slot.get())) {
            session->running.store(false);
            break;
        }
    }
    if (session->running.load()) {
        for (const auto& slot : session->slots) {
            if (slot->role != IsoRole::AudioOut) continue;
            if (!submitAudioSlot(session, slot.get())) {
                session->running.store(false);
                break;
            }
        }
    }

    while (session->running.load()) {
        void* completed = nullptr;
        const int result = ioctl(session->fd, USBDEVFS_REAPURB, &completed);
        if (result < 0) {
            if (errno == EINTR) continue;
            if (!session->running.load()) break;
            setError("USBDEVFS_REAPURB failed errno=" + std::to_string(errno));
            session->failed.store(true);
            session->running.store(false);
            break;
        }
        auto* urb = static_cast<usbdevfs_urb*>(completed);
        if (urb == nullptr) continue;
        if (!session->running.load() || session->closing.load()) break;
        auto* slot = static_cast<IsoSlot*>(urb->usercontext);
        if (slot == nullptr) continue;

        bool resubmitted = false;
        if (slot->role == IsoRole::FeedbackIn) {
            if (consumeFeedback(session, slot) && session->running.load()) {
                resubmitted = submitFeedbackSlot(session, slot);
            }
        } else {
            if (urb->status != 0 || urb->error_count != 0) {
                setError(
                    "USB isochronous URB failed status=" + std::to_string(urb->status) +
                    " packetErrors=" + std::to_string(urb->error_count)
                );
                session->failed.store(true);
            } else if (session->running.load()) {
                resubmitted = submitAudioSlot(session, slot);
            }
        }

        if (feedbackTimedOut(session) || session->failed.load() || !resubmitted) {
            session->running.store(false);
            break;
        }
    }

    for (const auto& slot : session->slots) {
        ioctl(session->fd, USBDEVFS_DISCARDURB, slot->urb);
    }
    session->spaceAvailable.notify_all();
}

bool startWorkerIfNeeded(Session* session) {
    std::lock_guard<std::mutex> lock(session->lifecycleMutex);
    if (session->closing.load()) return false;
    bool expected = false;
    if (!session->workerStarted.compare_exchange_strong(expected, true)) return true;
    session->running.store(true);
    session->worker = std::thread(directWorker, session);
    return true;
}

void stopSession(Session* session) {
    if (session == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(session->lifecycleMutex);
        session->closing.store(true);
        session->running.store(false);
    }
    for (const auto& slot : session->slots) {
        if (slot && slot->urb) ioctl(session->fd, USBDEVFS_DISCARDURB, slot->urb);
    }
    session->spaceAvailable.notify_all();
    if (session->worker.joinable()) {
        session->worker.join();
    }
    if (session->fd >= 0) {
        releaseInterfaces(session);
        close(session->fd);
        session->fd = -1;
    }
}

int64_t clampToBits(int64_t value, int bits) {
    const int64_t minimum = -(int64_t{1} << (bits - 1));
    const int64_t maximum = (int64_t{1} << (bits - 1)) - 1;
    return std::clamp(value, minimum, maximum);
}

int64_t convertIntegerSample(int64_t value, int sourceBits, int targetBits) {
    if (sourceBits < targetBits) {
        return clampToBits(value << (targetBits - sourceBits), targetBits);
    }
    if (sourceBits > targetBits) {
        return clampToBits(value >> (sourceBits - targetBits), targetBits);
    }
    return clampToBits(value, targetBits);
}

int64_t convertFloatSample(float value, int targetBits) {
    if (!std::isfinite(value)) return 0;
    const double sample = std::clamp(static_cast<double>(value), -1.0, 1.0);
    const int64_t negativeScale = int64_t{1} << (targetBits - 1);
    const int64_t positiveScale = negativeScale - 1;
    const double scaled = sample < 0.0
        ? sample * static_cast<double>(negativeScale)
        : sample * static_cast<double>(positiveScale);
    return clampToBits(static_cast<int64_t>(std::llround(scaled)), targetBits);
}

float sanitizeGain(float gain) {
    if (!std::isfinite(gain)) return 0.0F;
    return std::clamp(gain, 0.0F, 1.0F);
}

float gainForSample(float left, float right, int sampleIndex, int channels) {
    if (channels <= 1 || sampleIndex % channels == 0) return left;
    if (sampleIndex % channels == 1) return right;
    return (left + right) * 0.5F;
}

int64_t attenuateIntegerSample(int64_t value, int bits, float gain) {
    return clampToBits(
        static_cast<int64_t>(std::llround(static_cast<double>(value) * gain)),
        bits
    );
}

void packLittleEndian(
    int64_t sample,
    int subslotBytes,
    int bitResolution,
    uint8_t* destination
) {
    for (int byte = 0; byte < subslotBytes; ++byte) {
        destination[byte] = usb_pcm::littleEndianSampleByte(
            sample,
            subslotBytes,
            bitResolution,
            byte
        );
    }
}

std::vector<uint8_t> convertIntegerFrames(
    Session* session,
    const int64_t* samples,
    int sampleCount,
    int sourceBits,
    float gainLeft,
    float gainRight
) {
    const float left = sanitizeGain(gainLeft);
    const float right = sanitizeGain(gainRight);
    std::vector<uint8_t> output(
        static_cast<size_t>(sampleCount) * session->targetSubslotBytes
    );
    for (int index = 0; index < sampleCount; ++index) {
        const int64_t converted = convertIntegerSample(
            samples[index], sourceBits, session->targetBitResolution
        );
        packLittleEndian(
            attenuateIntegerSample(
                converted,
                session->targetBitResolution,
                gainForSample(left, right, index, session->channels)
            ),
            session->targetSubslotBytes,
            session->targetBitResolution,
            output.data() + static_cast<size_t>(index) * session->targetSubslotBytes
        );
    }
    return output;
}

std::vector<uint8_t> convertFloatFrames(
    Session* session,
    const float* samples,
    int sampleCount,
    float gainLeft,
    float gainRight
) {
    const float left = sanitizeGain(gainLeft);
    const float right = sanitizeGain(gainRight);
    std::vector<uint8_t> output(
        static_cast<size_t>(sampleCount) * session->targetSubslotBytes
    );
    for (int index = 0; index < sampleCount; ++index) {
        packLittleEndian(
            convertFloatSample(
                samples[index] * gainForSample(left, right, index, session->channels),
                session->targetBitResolution
            ),
            session->targetSubslotBytes,
            session->targetBitResolution,
            output.data() + static_cast<size_t>(index) * session->targetSubslotBytes
        );
    }
    return output;
}

int64_t readSignedLittleEndian(const uint8_t* data, int bytes) {
    uint64_t value = 0;
    for (int index = 0; index < bytes; ++index) {
        value |= static_cast<uint64_t>(data[index]) << (index * 8);
    }
    const int bits = bytes * 8;
    if (bits < 64 && (value & (uint64_t{1} << (bits - 1))) != 0) {
        value |= (~uint64_t{0}) << bits;
    }
    return static_cast<int64_t>(value);
}

std::vector<uint8_t> convertByteFrames(
    Session* session,
    const uint8_t* input,
    int sampleCount,
    float gainLeft,
    float gainRight
) {
    if (session->inputFormat == kFormatFloat) {
        std::vector<float> samples(sampleCount);
        for (int index = 0; index < sampleCount; ++index) {
            float value = 0.0f;
            std::memcpy(&value, input + static_cast<size_t>(index) * 4, sizeof(float));
            samples[index] = value;
        }
        return convertFloatFrames(
            session,
            samples.data(),
            sampleCount,
            gainLeft,
            gainRight
        );
    }

    const int sourceBits = session->inputBytesPerSample * 8;
    std::vector<int64_t> samples(sampleCount);
    for (int index = 0; index < sampleCount; ++index) {
        samples[index] = readSignedLittleEndian(
            input + static_cast<size_t>(index) * session->inputBytesPerSample,
            session->inputBytesPerSample
        );
    }
    return convertIntegerFrames(
        session,
        samples.data(),
        sampleCount,
        sourceBits,
        gainLeft,
        gainRight
    );
}

size_t enqueueTarget(Session* session, const uint8_t* data, size_t bytes, bool blocking) {
    if (bytes == 0) return 0;
    if (session->failed.load() || session->closing.load()) return 0;
    if (bytes % session->targetFrameBytes != 0) {
        setError("USB Direct target write is not frame aligned");
        session->failed.store(true);
        return 0;
    }

    if (!startWorkerIfNeeded(session)) return 0;
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kWriteWaitMillis);
    size_t written = 0;
    std::unique_lock<std::mutex> lock(session->ringMutex);
    while (written < bytes && !session->failed.load() && !session->closing.load()) {
        size_t space = session->ring.size() - session->ringCount;
        space -= space % session->targetFrameBytes;
        if (space == 0) {
            if (!blocking) break;
            if (session->spaceAvailable.wait_until(lock, deadline) == std::cv_status::timeout) break;
            continue;
        }
        size_t amount = std::min(space, bytes - written);
        amount -= amount % session->targetFrameBytes;
        if (amount == 0) break;
        const size_t first = std::min(amount, session->ring.size() - session->ringWrite);
        std::memcpy(session->ring.data() + session->ringWrite, data + written, first);
        if (first < amount) {
            std::memcpy(session->ring.data(), data + written + first, amount - first);
        }
        session->ringWrite = (session->ringWrite + amount) % session->ring.size();
        session->ringCount += amount;
        written += amount;
        if (!blocking) break;
    }
    return written;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeOpen(
    JNIEnv*,
    jclass,
    jint fd,
    jint sampleRate,
    jint inputFormatCode,
    jint channels,
    jint interfaceNumber,
    jint alternateSetting,
    jint audioControlInterface,
    jint clockSourceId,
    jboolean fixedSampleRateMatch,
    jint protocol,
    jint endpointAddress,
    jint maxPacketSize,
    jint interval,
    jint feedbackEndpointAddress,
    jint feedbackMaxPacketSize,
    jint feedbackInterval,
    jint targetSubslotBytes,
    jint targetBitResolution,
    jint pcmBufferMs,
    jint transferBufferMs
) {
    setError("");
    const int inputSampleBytes = inputBytesPerSample(inputFormatCode);
    if (
        fd < 0 || sampleRate <= 0 || channels <= 0 || inputSampleBytes == 0 ||
        endpointAddress <= 0 || (endpointAddress & 0x80) != 0 || maxPacketSize <= 0 ||
        targetSubslotBytes < 2 || targetSubslotBytes > 4 ||
        targetBitResolution < 8 || targetBitResolution > targetSubslotBytes * 8 ||
        interfaceNumber < 0 || alternateSetting <= 0 ||
        (protocol >= 0x20 && (audioControlInterface < 0 || clockSourceId <= 0)) ||
        pcmBufferMs <= 0 || transferBufferMs < 0
    ) {
        setError("Invalid USB Direct stream parameters");
        return 0;
    }

    const bool anyFeedbackParameter = feedbackEndpointAddress != 0 ||
        feedbackMaxPacketSize != 0 || feedbackInterval != 0;
    const bool validFeedbackParameters = !anyFeedbackParameter || (
        (feedbackEndpointAddress & 0x80) != 0 &&
        (feedbackEndpointAddress & 0x0f) != 0 &&
        feedbackMaxPacketSize >= 3 && feedbackMaxPacketSize <= 4 &&
        feedbackInterval > 0
    );
    if (!validFeedbackParameters) {
        setError("Invalid USB Direct explicit feedback parameters");
        return 0;
    }

    const int duplicatedFd = dup(fd);
    if (duplicatedFd < 0) {
        setError("dup(usb fd) failed errno=" + std::to_string(errno));
        return 0;
    }

    auto session = std::make_shared<Session>();
    session->fd = duplicatedFd;
    session->sampleRate = sampleRate;
    session->inputFormat = inputFormatCode;
    session->inputBytesPerSample = inputSampleBytes;
    session->channels = channels;
    session->interfaceNumber = interfaceNumber;
    session->alternateSetting = alternateSetting;
    session->audioControlInterface = audioControlInterface;
    session->clockSourceId = clockSourceId;
    session->protocol = protocol;
    session->fixedSampleRateMatch = fixedSampleRateMatch == JNI_TRUE;
    session->endpointAddress = endpointAddress;
    session->maxPacketSize = maxPacketSize;
    session->interval = std::max(1, interval);
    session->feedbackEndpointAddress = feedbackEndpointAddress;
    session->feedbackMaxPacketSize = feedbackMaxPacketSize;
    session->feedbackInterval = feedbackInterval;
    session->usesExplicitFeedback = anyFeedbackParameter;
    session->targetSubslotBytes = targetSubslotBytes;
    session->targetBitResolution = targetBitResolution;
    session->targetFrameBytes = static_cast<size_t>(channels) * targetSubslotBytes;
    session->highOrSuperSpeed = isHighOrSuperSpeed(duplicatedFd);
    session->busTicksPerSecond = session->highOrSuperSpeed ? 8000 : 1000;
    session->outputServiceTicks = usb_feedback::serviceTicks(
        session->highOrSuperSpeed,
        session->interval
    );
    session->packetScheduler.updateFeedback(
        usb_feedback::nominalFeedbackQ16(sampleRate, session->busTicksPerSecond)
    );

    if (!claimInterfaces(session.get())) {
        releaseInterfaces(session.get());
        close(session->fd);
        session->fd = -1;
        return 0;
    }
    if (!selectStreamingAlternate(session.get())) {
        releaseInterfaces(session.get());
        close(session->fd);
        session->fd = -1;
        return 0;
    }
    if (!configureSampleRate(session.get())) {
        releaseInterfaces(session.get());
        close(session->fd);
        session->fd = -1;
        return 0;
    }

    const size_t ringBytes = usb_direct_buffer::ringBytesForDuration(
        sampleRate,
        session->targetFrameBytes,
        pcmBufferMs
    );
    session->ring.resize(ringBytes);

    const int isoUrbCount = usb_direct_buffer::isoUrbCountForTarget(
        session->busTicksPerSecond,
        session->outputServiceTicks,
        transferBufferMs,
        kIsoPacketsPerUrb
    );

    if (session->usesExplicitFeedback) {
        for (int index = 0; index < kFeedbackUrbCount; ++index) {
            auto slot = std::make_unique<IsoSlot>();
            slot->role = IsoRole::FeedbackIn;
            slot->urbStorage.resize(
                sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc)
            );
            std::memset(slot->urbStorage.data(), 0, slot->urbStorage.size());
            slot->buffer.resize(static_cast<size_t>(feedbackMaxPacketSize));
            slot->urb = reinterpret_cast<usbdevfs_urb*>(slot->urbStorage.data());
            slot->urb->type = USBDEVFS_URB_TYPE_ISO;
            slot->urb->endpoint = static_cast<unsigned char>(feedbackEndpointAddress);
            slot->urb->flags = USBDEVFS_URB_ISO_ASAP;
            slot->urb->buffer = slot->buffer.data();
            slot->urb->number_of_packets = 1;
            slot->urb->usercontext = slot.get();
            session->slots.push_back(std::move(slot));
        }
    }

    for (int index = 0; index < isoUrbCount; ++index) {
        auto slot = std::make_unique<IsoSlot>();
        slot->role = IsoRole::AudioOut;
        slot->urbStorage.resize(
            sizeof(usbdevfs_urb) + kIsoPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc)
        );
        std::memset(slot->urbStorage.data(), 0, slot->urbStorage.size());
        slot->buffer.resize(static_cast<size_t>(maxPacketSize) * kIsoPacketsPerUrb);
        slot->urb = reinterpret_cast<usbdevfs_urb*>(slot->urbStorage.data());
        slot->urb->type = USBDEVFS_URB_TYPE_ISO;
        slot->urb->endpoint = static_cast<unsigned char>(endpointAddress);
        slot->urb->flags = USBDEVFS_URB_ISO_ASAP;
        slot->urb->buffer = slot->buffer.data();
        slot->urb->number_of_packets = kIsoPacketsPerUrb;
        slot->urb->usercontext = slot.get();
        session->slots.push_back(std::move(slot));
    }

    return registerSession(session);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeWriteFloats(
    JNIEnv* env,
    jclass,
    jlong handle,
    jfloatArray data,
    jint offset,
    jint size,
    jboolean blocking,
    jfloat gainLeft,
    jfloat gainRight
) {
    auto session = findSession(handle);
    if (session == nullptr || data == nullptr || session->inputFormat != kFormatFloat) {
        setError("USB Direct float write is incompatible with active session");
        return -1;
    }
    const jsize length = env->GetArrayLength(data);
    if (offset < 0 || size < 0 || offset > length || size > length - offset || size % session->channels != 0) {
        setError("USB Direct float write range is invalid");
        return -1;
    }
    std::vector<float> samples(size);
    env->GetFloatArrayRegion(data, offset, size, samples.data());
    if (env->ExceptionCheck()) return -1;
    auto converted = convertFloatFrames(
        session.get(),
        samples.data(),
        size,
        gainLeft,
        gainRight
    );
    const size_t accepted = enqueueTarget(
        session.get(), converted.data(), converted.size(), blocking == JNI_TRUE
    );
    if (session->failed.load() || session->closing.load()) return -1;
    const size_t targetBytesPerSample = static_cast<size_t>(session->targetSubslotBytes);
    return static_cast<jint>(accepted / targetBytesPerSample);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeWriteShorts(
    JNIEnv* env,
    jclass,
    jlong handle,
    jshortArray data,
    jint offset,
    jint size,
    jboolean blocking,
    jfloat gainLeft,
    jfloat gainRight
) {
    auto session = findSession(handle);
    if (session == nullptr || data == nullptr || session->inputFormat != kFormatI16) {
        setError("USB Direct short write is incompatible with active session");
        return -1;
    }
    const jsize length = env->GetArrayLength(data);
    if (offset < 0 || size < 0 || offset > length || size > length - offset || size % session->channels != 0) {
        setError("USB Direct short write range is invalid");
        return -1;
    }
    std::vector<jshort> source(size);
    env->GetShortArrayRegion(data, offset, size, source.data());
    if (env->ExceptionCheck()) return -1;
    std::vector<int64_t> samples(size);
    for (int index = 0; index < size; ++index) samples[index] = source[index];
    auto converted = convertIntegerFrames(
        session.get(),
        samples.data(),
        size,
        16,
        gainLeft,
        gainRight
    );
    const size_t accepted = enqueueTarget(
        session.get(), converted.data(), converted.size(), blocking == JNI_TRUE
    );
    if (session->failed.load() || session->closing.load()) return -1;
    return static_cast<jint>(accepted / session->targetSubslotBytes);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeWriteBytes(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray data,
    jint offset,
    jint size,
    jboolean blocking,
    jfloat gainLeft,
    jfloat gainRight
) {
    auto session = findSession(handle);
    if (session == nullptr || data == nullptr) {
        setError("USB Direct byte write has no active session");
        return -1;
    }
    const jsize length = env->GetArrayLength(data);
    const int inputFrameBytes = session->inputBytesPerSample * session->channels;
    if (
        offset < 0 || size < 0 || offset > length || size > length - offset ||
        inputFrameBytes <= 0 || size % inputFrameBytes != 0
    ) {
        setError("USB Direct byte write range is invalid or not frame aligned");
        return -1;
    }
    std::vector<uint8_t> source(size);
    env->GetByteArrayRegion(
        data,
        offset,
        size,
        reinterpret_cast<jbyte*>(source.data())
    );
    if (env->ExceptionCheck()) return -1;
    const int sampleCount = size / session->inputBytesPerSample;
    auto converted = convertByteFrames(
        session.get(),
        source.data(),
        sampleCount,
        gainLeft,
        gainRight
    );
    const size_t accepted = enqueueTarget(
        session.get(), converted.data(), converted.size(), blocking == JNI_TRUE
    );
    if (session->failed.load() || session->closing.load()) return -1;
    const size_t acceptedSamples = accepted / session->targetSubslotBytes;
    return static_cast<jint>(acceptedSamples * session->inputBytesPerSample);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeState(
    JNIEnv*,
    jclass,
    jlong handle
) {
    auto session = findSession(handle);
    if (
        session == nullptr ||
        session->fd < 0 ||
        session->closing.load()
    ) {
        return kSessionStateMissing;
    }
    if (session->failed.load()) {
        return kSessionStateFailed;
    }
    if (
        session->workerStarted.load() &&
        session->running.load()
    ) {
        return kSessionStateStreaming;
    }
    return kSessionStateClaimed;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeFlush(
    JNIEnv*,
    jclass,
    jlong handle
) {
    auto session = findSession(handle);
    if (session == nullptr || session->closing.load()) return;
    {
        std::lock_guard<std::mutex> lock(session->ringMutex);
        session->ringRead = 0;
        session->ringWrite = 0;
        session->ringCount = 0;
    }
    session->spaceAvailable.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeClose(
    JNIEnv*,
    jclass,
    jlong handle
) {
    auto session = takeSession(handle);
    if (session == nullptr) return;
    stopSession(session.get());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeLastError(
    JNIEnv* env,
    jclass
) {
    const std::string error = getError();
    return env->NewStringUTF(error.c_str());
}
