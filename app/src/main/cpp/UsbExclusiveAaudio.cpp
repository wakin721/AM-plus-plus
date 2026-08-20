#include <aaudio/AAudio.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr int kFormatI16 = 1;
constexpr int kFormatFloat = 2;
constexpr int kFormatI24 = 3;
constexpr int kFormatI32 = 4;
constexpr int64_t kBlockingWriteTimeoutNanos = 100'000'000LL;
constexpr int64_t kStateTransitionTimeoutNanos = 100'000'000LL;

thread_local std::string gLastError;

struct Session {
    AAudioStream* stream = nullptr;
    int32_t formatCode = 0;
    int32_t channels = 0;
    int32_t bytesPerSample = 0;
    bool started = false;
    std::vector<uint8_t> scratch;
    std::mutex mutex;
};

void setError(const std::string& message) {
    gLastError = message;
}

void setAaudioError(const char* operation, aaudio_result_t result) {
    std::ostringstream message;
    message << operation << ": " << AAudio_convertResultToText(result) << " (" << result << ")";
    setError(message.str());
}

aaudio_format_t formatForCode(int formatCode) {
    switch (formatCode) {
        case kFormatI16:
            return AAUDIO_FORMAT_PCM_I16;
        case kFormatFloat:
            return AAUDIO_FORMAT_PCM_FLOAT;
        case kFormatI24:
            return AAUDIO_FORMAT_PCM_I24_PACKED;
        case kFormatI32:
            return AAUDIO_FORMAT_PCM_I32;
        default:
            return AAUDIO_FORMAT_INVALID;
    }
}

int32_t bytesPerSampleForCode(int formatCode) {
    switch (formatCode) {
        case kFormatI16:
            return 2;
        case kFormatFloat:
            return 4;
        case kFormatI24:
            return 3;
        case kFormatI32:
            return 4;
        default:
            return 0;
    }
}

void waitForTransition(AAudioStream* stream, aaudio_stream_state_t transientState) {
    if (stream == nullptr || AAudioStream_getState(stream) != transientState) return;
    aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNKNOWN;
    AAudioStream_waitForStateChange(
        stream,
        transientState,
        &nextState,
        kStateTransitionTimeoutNanos
    );
}

void discardAndCloseStream(AAudioStream* stream) {
    if (stream == nullptr) return;
    waitForTransition(stream, AAUDIO_STREAM_STATE_STARTING);

    auto state = AAudioStream_getState(stream);
    if (state == AAUDIO_STREAM_STATE_STARTED) {
        const auto pauseResult = AAudioStream_requestPause(stream);
        if (pauseResult == AAUDIO_OK) {
            waitForTransition(stream, AAUDIO_STREAM_STATE_PAUSING);
        } else {
            AAudioStream_requestStop(stream);
            waitForTransition(stream, AAUDIO_STREAM_STATE_STOPPING);
        }
    }

    state = AAudioStream_getState(stream);
    if (state == AAUDIO_STREAM_STATE_OPEN ||
        state == AAUDIO_STREAM_STATE_PAUSED ||
        state == AAUDIO_STREAM_STATE_STOPPED ||
        state == AAUDIO_STREAM_STATE_FLUSHED) {
        if (AAudioStream_requestFlush(stream) == AAUDIO_OK) {
            waitForTransition(stream, AAUDIO_STREAM_STATE_FLUSHING);
        }
    }
    AAudioStream_close(stream);
}

float sanitizeGain(float gain) {
    if (!std::isfinite(gain)) return 0.0F;
    return std::clamp(gain, 0.0F, 1.0F);
}

float gainForChannel(float left, float right, int32_t channel, int32_t channels) {
    if (channels <= 1 || channel == 0) return left;
    if (channel == 1) return right;
    return (left + right) * 0.5F;
}

const uint8_t* attenuatePcm(
    Session* session,
    const uint8_t* data,
    int32_t frameCount,
    float gainLeft,
    float gainRight,
    std::vector<uint8_t>* scratch
) {
    const float left = sanitizeGain(gainLeft);
    const float right = sanitizeGain(gainRight);
    if (left == 1.0F && right == 1.0F) return data;

    const int32_t sampleCount = frameCount * session->channels;
    const int64_t byteCount = static_cast<int64_t>(sampleCount) * session->bytesPerSample;
    scratch->resize(static_cast<size_t>(byteCount));
    auto* output = scratch->data();

    for (int32_t sampleIndex = 0; sampleIndex < sampleCount; ++sampleIndex) {
        const float gain = gainForChannel(
            left,
            right,
            sampleIndex % session->channels,
            session->channels
        );
        const int64_t byteOffset = static_cast<int64_t>(sampleIndex) * session->bytesPerSample;
        const uint8_t* source = data + byteOffset;
        uint8_t* target = output + byteOffset;

        switch (session->formatCode) {
            case kFormatI16: {
                int16_t sample = 0;
                std::memcpy(&sample, source, sizeof(sample));
                const auto scaled = static_cast<int16_t>(std::clamp<int64_t>(
                    std::llround(static_cast<double>(sample) * gain),
                    std::numeric_limits<int16_t>::min(),
                    std::numeric_limits<int16_t>::max()
                ));
                std::memcpy(target, &scaled, sizeof(scaled));
                break;
            }
            case kFormatFloat: {
                float sample = 0.0F;
                std::memcpy(&sample, source, sizeof(sample));
                const float scaled = std::clamp(sample * gain, -1.0F, 1.0F);
                std::memcpy(target, &scaled, sizeof(scaled));
                break;
            }
            case kFormatI24: {
                int32_t sample = static_cast<int32_t>(source[0]) |
                    (static_cast<int32_t>(source[1]) << 8) |
                    (static_cast<int32_t>(source[2]) << 16);
                if ((sample & 0x00800000) != 0) sample |= static_cast<int32_t>(0xFF000000);
                const auto scaled = static_cast<int32_t>(std::clamp<int64_t>(
                    std::llround(static_cast<double>(sample) * gain),
                    -8'388'608,
                    8'388'607
                ));
                target[0] = static_cast<uint8_t>(scaled & 0xFF);
                target[1] = static_cast<uint8_t>((scaled >> 8) & 0xFF);
                target[2] = static_cast<uint8_t>((scaled >> 16) & 0xFF);
                break;
            }
            case kFormatI32: {
                int32_t sample = 0;
                std::memcpy(&sample, source, sizeof(sample));
                const auto scaled = static_cast<int32_t>(std::clamp<int64_t>(
                    std::llround(static_cast<double>(sample) * gain),
                    std::numeric_limits<int32_t>::min(),
                    std::numeric_limits<int32_t>::max()
                ));
                std::memcpy(target, &scaled, sizeof(scaled));
                break;
            }
            default:
                return data;
        }
    }
    return scratch->data();
}

int32_t writeFrames(
    Session* session,
    const uint8_t* data,
    int32_t frameCount,
    bool blocking,
    float gainLeft,
    float gainRight
) {
    if (session == nullptr || data == nullptr || frameCount < 0) {
        setError("invalid AAudio write arguments");
        return -1;
    }
    if (frameCount == 0) return 0;

    const int32_t bytesPerFrame = session->bytesPerSample * session->channels;
    if (bytesPerFrame <= 0) {
        setError("invalid AAudio frame size");
        return -1;
    }

    std::lock_guard<std::mutex> guard(session->mutex);
    if (session->stream == nullptr) {
        setError("AAudio write raced with a closed session");
        return -1;
    }

    const uint8_t* writeData = attenuatePcm(
        session,
        data,
        frameCount,
        gainLeft,
        gainRight,
        &session->scratch
    );
    int32_t total = 0;
    if (!session->started) {
        const auto prefilled = AAudioStream_write(
            session->stream,
            writeData,
            frameCount,
            0
        );
        if (prefilled < 0) {
            setAaudioError("AAudioStream_write prefill", prefilled);
            return prefilled;
        }
        total = prefilled;
        if (total == 0) return 0;

        const auto startResult = AAudioStream_requestStart(session->stream);
        if (startResult != AAUDIO_OK) {
            setAaudioError("AAudioStream_requestStart", startResult);
            return startResult;
        }
        session->started = true;
        if (!blocking || total == frameCount) return total;
    }

    while (total < frameCount) {
        const int32_t remaining = frameCount - total;
        const int64_t timeout = blocking ? kBlockingWriteTimeoutNanos : 0;
        const auto result = AAudioStream_write(
            session->stream,
            writeData + static_cast<int64_t>(total) * bytesPerFrame,
            remaining,
            timeout
        );
        if (result < 0) {
            setAaudioError("AAudioStream_write", result);
            return result;
        }
        if (result == 0) break;
        total += result;
        if (!blocking) break;
    }
    return total;
}

Session* fromHandle(jlong handle) {
    return reinterpret_cast<Session*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_amenhancer_module_hook_UsbExclusiveAaudioBridge_nativeOpen(
    JNIEnv*,
    jclass,
    jint deviceId,
    jint sampleRate,
    jint formatCode,
    jint channels
) {
    gLastError.clear();
    const auto requestedFormat = formatForCode(formatCode);
    const int32_t bytesPerSample = bytesPerSampleForCode(formatCode);
    if (requestedFormat == AAUDIO_FORMAT_INVALID || bytesPerSample == 0) {
        setError("unsupported PCM format for AAudio exclusive mode");
        return 0;
    }
    if (deviceId <= 0 || sampleRate <= 0 || channels <= 0) {
        setError("invalid device or AudioTrack format for AAudio exclusive mode");
        return 0;
    }

    AAudioStreamBuilder* builder = nullptr;
    auto result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        setAaudioError("AAudio_createStreamBuilder", result);
        return 0;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setDeviceId(builder, deviceId);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setFormat(builder, requestedFormat);

    AAudioStream* stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream == nullptr) {
        setAaudioError("AAudioStreamBuilder_openStream", result);
        return 0;
    }

    if (AAudioStream_getSharingMode(stream) != AAUDIO_SHARING_MODE_EXCLUSIVE) {
        setError("AAudio returned SHARED instead of EXCLUSIVE");
        discardAndCloseStream(stream);
        return 0;
    }
    if (AAudioStream_getDeviceId(stream) != deviceId) {
        std::ostringstream message;
        message << "AAudio opened deviceId=" << AAudioStream_getDeviceId(stream)
                << " instead of requested deviceId=" << deviceId;
        setError(message.str());
        discardAndCloseStream(stream);
        return 0;
    }
    if (AAudioStream_getSampleRate(stream) != sampleRate ||
        AAudioStream_getFormat(stream) != requestedFormat ||
        AAudioStream_getChannelCount(stream) != channels) {
        std::ostringstream message;
        message << "AAudio actual format mismatch: "
                << AAudioStream_getSampleRate(stream) << "Hz format="
                << static_cast<int>(AAudioStream_getFormat(stream)) << " channels="
                << AAudioStream_getChannelCount(stream);
        setError(message.str());
        discardAndCloseStream(stream);
        return 0;
    }

    auto* session = new Session();
    session->stream = stream;
    session->formatCode = formatCode;
    session->channels = channels;
    session->bytesPerSample = bytesPerSample;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_amenhancer_module_hook_UsbExclusiveAaudioBridge_nativeWriteBytes(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray data,
    jint offsetBytes,
    jint sizeBytes,
    jboolean blocking,
    jfloat gainLeft,
    jfloat gainRight
) {
    auto* session = fromHandle(handle);
    if (session == nullptr || data == nullptr) {
        setError("AAudio byte write has no active session or buffer");
        return -1;
    }
    const jsize length = env->GetArrayLength(data);
    if (offsetBytes < 0 || sizeBytes < 0 || offsetBytes > length || sizeBytes > length - offsetBytes) {
        setError("AAudio byte write range is invalid");
        return -1;
    }
    const int32_t bytesPerFrame = session->bytesPerSample * session->channels;
    if (bytesPerFrame <= 0 || sizeBytes % bytesPerFrame != 0) {
        setError("AAudio byte write is not aligned to complete PCM frames");
        return -1;
    }

    jboolean copied = JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(data, &copied);
    if (bytes == nullptr) {
        setError("GetByteArrayElements failed");
        return -1;
    }
    const int32_t frames = sizeBytes / bytesPerFrame;
    const int32_t writtenFrames = writeFrames(
        session,
        reinterpret_cast<const uint8_t*>(bytes) + offsetBytes,
        frames,
        blocking == JNI_TRUE,
        gainLeft,
        gainRight
    );
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return writtenFrames < 0 ? writtenFrames : writtenFrames * bytesPerFrame;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_amenhancer_module_hook_UsbExclusiveAaudioBridge_nativeWriteFloats(
    JNIEnv* env,
    jclass,
    jlong handle,
    jfloatArray data,
    jint offsetFloats,
    jint sizeFloats,
    jboolean blocking,
    jfloat gainLeft,
    jfloat gainRight
) {
    auto* session = fromHandle(handle);
    if (session == nullptr || data == nullptr || session->bytesPerSample != 4) {
        setError("AAudio float write is incompatible with the active session");
        return -1;
    }
    const jsize length = env->GetArrayLength(data);
    if (offsetFloats < 0 || sizeFloats < 0 || offsetFloats > length || sizeFloats > length - offsetFloats ||
        sizeFloats % session->channels != 0) {
        setError("AAudio float write range is invalid");
        return -1;
    }

    jboolean copied = JNI_FALSE;
    jfloat* samples = env->GetFloatArrayElements(data, &copied);
    if (samples == nullptr) {
        setError("GetFloatArrayElements failed");
        return -1;
    }
    const int32_t frames = sizeFloats / session->channels;
    const int32_t writtenFrames = writeFrames(
        session,
        reinterpret_cast<const uint8_t*>(samples + offsetFloats),
        frames,
        blocking == JNI_TRUE,
        gainLeft,
        gainRight
    );
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
    return writtenFrames < 0 ? writtenFrames : writtenFrames * session->channels;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_amenhancer_module_hook_UsbExclusiveAaudioBridge_nativeWriteShorts(
    JNIEnv* env,
    jclass,
    jlong handle,
    jshortArray data,
    jint offsetShorts,
    jint sizeShorts,
    jboolean blocking,
    jfloat gainLeft,
    jfloat gainRight
) {
    auto* session = fromHandle(handle);
    if (session == nullptr || data == nullptr || session->bytesPerSample != 2) {
        setError("AAudio short write is incompatible with the active session");
        return -1;
    }
    const jsize length = env->GetArrayLength(data);
    if (offsetShorts < 0 || sizeShorts < 0 || offsetShorts > length || sizeShorts > length - offsetShorts ||
        sizeShorts % session->channels != 0) {
        setError("AAudio short write range is invalid");
        return -1;
    }

    jboolean copied = JNI_FALSE;
    jshort* samples = env->GetShortArrayElements(data, &copied);
    if (samples == nullptr) {
        setError("GetShortArrayElements failed");
        return -1;
    }
    const int32_t frames = sizeShorts / session->channels;
    const int32_t writtenFrames = writeFrames(
        session,
        reinterpret_cast<const uint8_t*>(samples + offsetShorts),
        frames,
        blocking == JNI_TRUE,
        gainLeft,
        gainRight
    );
    env->ReleaseShortArrayElements(data, samples, JNI_ABORT);
    return writtenFrames < 0 ? writtenFrames : writtenFrames * session->channels;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_amenhancer_module_hook_UsbExclusiveAaudioBridge_nativeClose(
    JNIEnv*,
    jclass,
    jlong handle
) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(session->mutex);
        discardAndCloseStream(session->stream);
        session->stream = nullptr;
    }
    delete session;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_amenhancer_module_hook_UsbExclusiveAaudioBridge_nativeLastError(
    JNIEnv* env,
    jclass
) {
    return env->NewStringUTF(gLastError.c_str());
}
