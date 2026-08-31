# USB Audio Explicit Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add standards-compliant UAC1/UAC2 explicit-feedback pacing to the existing usbfs USB Direct output path without weakening fail-open behavior.

**Architecture:** Extend the descriptor parser and Broker/IPC/JNI lease with one optional feedback IN endpoint, then let the existing native usbfs worker reap feedback IN and audio OUT URBs on the same descriptor. Keep Q10.14/Q16.16 decoding and fractional packet scheduling in a pure C++17 header so descriptor behavior and clock math can be tested independently before the hardware-facing worker is enabled.

**Tech Stack:** Kotlin 1.9.24, Android USB Host/Messenger IPC, JNI, C++17, Linux usbfs isochronous URBs, JUnit 4, Gradle Android plugin.

**Spec:** `docs/superpowers/specs/2026-08-31-usb-explicit-feedback-design.md`

## Global Constraints

- Preserve Android `minSdk = 26`, `targetSdk = 37`, Java 17, and C++17.
- Support only standards-compliant explicit feedback for UAC1 and UAC2.
- Keep implicit feedback, vendor-specific feedback, UAC3/UAC4, libusb replacement, SuperSpeed companion-descriptor expansion, and resampling out of scope.
- Add no runtime dependency.
- Keep synchronous and adaptive USB Direct behavior unchanged.
- Do not make asynchronous alternate settings selectable until the native feedback URB worker is implemented in Task 4.
- Treat feedback payload length, not only UAC protocol, as the Q10.14/Q16.16 discriminator.
- On any feedback or usbfs failure, preserve the existing fail-open restoration of the original `AudioTrack` and AAudio/system fallback.
- Every production behavior starts with a focused failing test and a witnessed RED result.

## Execution Preflight

The current workstation has no discoverable Java, Android SDK, CMake, Ninja, or host C++ compiler. Before Task 1, run:

```powershell
Get-Command java -ErrorAction SilentlyContinue
[Environment]::GetEnvironmentVariable('JAVA_HOME')
[Environment]::GetEnvironmentVariable('ANDROID_HOME')
Test-Path "$env:LOCALAPPDATA\Android\Sdk"
```

If Java 17 or the Android SDK is still unavailable, pause and obtain approval to install a JDK 17 and Android SDK command-line toolchain. Do not replace RED/GREEN verification with source inspection. Once available, discover the approved installations and set the current shell explicitly:

```powershell
$javaCommand = Get-Command java -ErrorAction Stop
$env:JAVA_HOME = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent
$sdkCandidate = "$env:LOCALAPPDATA\Android\Sdk"
if (!(Test-Path -LiteralPath $sdkCandidate)) { throw "Android SDK not found at $sdkCandidate" }
$env:ANDROID_HOME = $sdkCandidate
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
java -version
```

Expected: a Java 17 runtime and a valid Android SDK containing platform/build-tools 37 plus the NDK/CMake versions resolved by Gradle.

---

### Task 1: Parse and pair standard explicit-feedback endpoints without enabling takeover

**Files:**
- Modify: `app/src/test/java/dev/amenhancer/module/usb/UsbAudioDescriptorParserTest.kt:1-88`
- Modify: `app/src/main/java/dev/amenhancer/module/usb/UsbAudioDescriptorParser.kt:1-252`

**Interfaces:**
- Consumes: raw USB configuration descriptors passed to `UsbAudioDescriptorParser.parse(raw: ByteArray)`.
- Produces: `StreamingAlt.feedbackEndpointAddress: Int`, `feedbackMaxPacketSize: Int`, `feedbackInterval: Int`, and `hasExplicitFeedback: Boolean` for Tasks 3 and 4.
- Safety gate: `select(...)` continues rejecting every asynchronous endpoint at the end of this task.

- [ ] **Step 1: Replace the old asynchronous fixture with parser-focused failing tests**

Add `assertFalse` with the other imports and put this test-only converter near
the top of `UsbAudioDescriptorParserTest`:

```kotlin
import org.junit.Assert.assertFalse

private fun bytes(vararg values: Int): ByteArray =
    ByteArray(values.size) { index -> values[index].toByte() }
```

Replace `rejects asynchronous endpoint until feedback pacing is implemented` and add the following tests. These tests catch a parser that ignores direction/usage, crosses alternate settings, or ignores UAC1 `bSynchAddress`:

```kotlin
@Test
fun `parses UAC1 explicit feedback linked by bSynchAddress but keeps takeover gated`() {
    val raw = bytes(
        9, 0x04, 1, 1, 2, 0x01, 0x02, 0x00, 0,
        7, 0x24, 0x01, 1, 1, 0x01, 0x00,
        11, 0x24, 0x02, 0x01, 2, 3, 24, 1, 0x80, 0xbb, 0x00,
        9, 0x05, 0x01, 0x05, 0x20, 0x01, 1, 0, 0x81,
        9, 0x05, 0x81, 0x01, 3, 0, 1, 4, 0,
    )

    val alternative = UsbAudioDescriptorParser.parse(raw).single()

    assertTrue(alternative.requiresExplicitFeedback)
    assertTrue(alternative.hasExplicitFeedback)
    assertEquals(0x81, alternative.feedbackEndpointAddress)
    assertEquals(3, alternative.feedbackMaxPacketSize)
    assertEquals(1, alternative.feedbackInterval)
    assertNull(
        UsbAudioDescriptorParser.select(
            listOf(alternative),
            sampleRate = 48_000,
            channels = 2,
            preferredBits = 24,
        ),
    )
}

@Test
fun `parses UAC2 feedback usage in the same alternate setting`() {
    val raw = bytes(
        9, 0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0,
        17, 0x24, 0x02, 1, 0x01, 0x01, 0, 10, 2, 0x03, 0, 0, 0, 0, 0, 0, 0,
        9, 0x04, 1, 1, 2, 0x01, 0x02, 0x20, 0,
        16, 0x24, 0x01, 1, 0, 0x01, 0, 0, 0, 0, 2, 0x03, 0, 0, 0, 0,
        6, 0x24, 0x02, 0x01, 4, 32,
        7, 0x05, 0x01, 0x05, 0x80, 0x01, 1,
        7, 0x05, 0x81, 0x11, 4, 0, 4,
    )

    val alternative = UsbAudioDescriptorParser.parse(raw).single()

    assertEquals(0x20, alternative.protocol)
    assertTrue(alternative.hasExplicitFeedback)
    assertEquals(0x81, alternative.feedbackEndpointAddress)
    assertEquals(4, alternative.feedbackMaxPacketSize)
    assertEquals(4, alternative.feedbackInterval)
}

@Test
fun `does not pair a UAC1 feedback endpoint with a different address`() {
    val raw = bytes(
        9, 0x04, 1, 1, 2, 0x01, 0x02, 0x00, 0,
        7, 0x24, 0x01, 1, 1, 0x01, 0x00,
        11, 0x24, 0x02, 0x01, 2, 3, 24, 1, 0x80, 0xbb, 0x00,
        9, 0x05, 0x01, 0x05, 0x20, 0x01, 1, 0, 0x82,
        9, 0x05, 0x81, 0x01, 3, 0, 1, 4, 0,
    )

    val alternative = UsbAudioDescriptorParser.parse(raw).single()

    assertTrue(alternative.requiresExplicitFeedback)
    assertFalse(alternative.hasExplicitFeedback)
    assertEquals(0, alternative.feedbackEndpointAddress)
}

@Test
fun `does not pair UAC2 feedback from another alternate setting`() {
    val raw = bytes(
        9, 0x04, 1, 1, 1, 0x01, 0x02, 0x20, 0,
        16, 0x24, 0x01, 1, 0, 0x01, 0, 0, 0, 0, 2, 0x03, 0, 0, 0, 0,
        6, 0x24, 0x02, 0x01, 4, 32,
        7, 0x05, 0x01, 0x05, 0x80, 0x01, 1,
        9, 0x04, 1, 2, 1, 0x01, 0x02, 0x20, 0,
        7, 0x05, 0x81, 0x11, 4, 0, 4,
    )

    val first = UsbAudioDescriptorParser.parse(raw)
        .single { it.alternateSetting == 1 }

    assertFalse(first.hasExplicitFeedback)
}

@Test
fun `implicit feedback usage is not treated as an audio OUT data endpoint`() {
    val raw = bytes(
        9, 0x04, 1, 1, 1, 0x01, 0x02, 0x20, 0,
        16, 0x24, 0x01, 1, 0, 0x01, 0, 0, 0, 0, 2, 0x03, 0, 0, 0, 0,
        6, 0x24, 0x02, 0x01, 4, 32,
        7, 0x05, 0x01, 0x25, 0x80, 0x01, 1,
    )

    assertTrue(UsbAudioDescriptorParser.parse(raw).isEmpty())
}
```

- [ ] **Step 2: Run the focused parser test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "dev.amenhancer.module.usb.UsbAudioDescriptorParserTest"
```

Expected: compilation fails because `feedbackEndpointAddress`, `feedbackMaxPacketSize`, `feedbackInterval`, and `hasExplicitFeedback` do not exist. This is the intended RED; a toolchain error is not RED.

- [ ] **Step 3: Add endpoint records and feedback pairing to the parser**

In `UsbAudioDescriptorParser.kt`, add constants and a private endpoint record:

```kotlin
private const val ENDPOINT_TRANSFER_MASK = 0x03
private const val ENDPOINT_ISOCHRONOUS = 0x01
private const val ENDPOINT_USAGE_MASK = 0x30
private const val ENDPOINT_USAGE_DATA = 0x00
private const val ENDPOINT_USAGE_FEEDBACK = 0x10

private data class EndpointDescriptor(
    val address: Int,
    val attributes: Int,
    val maxPacketSize: Int,
    val interval: Int,
    val synchAddress: Int,
) {
    val isIsochronous: Boolean
        get() = attributes and ENDPOINT_TRANSFER_MASK == ENDPOINT_ISOCHRONOUS
    val isIn: Boolean get() = address and 0x80 != 0
    val usage: Int get() = attributes and ENDPOINT_USAGE_MASK
    val synchronizationType: Int get() = (attributes ushr 2) and 0x03
}
```

Replace endpoint-specific mutable fields in `AltBuilder` with:

```kotlin
val endpoints: MutableList<EndpointDescriptor> = mutableListOf()
```

Extend `StreamingAlt` with the three feedback fields and:

```kotlin
val hasExplicitFeedback: Boolean
    get() = feedbackEndpointAddress and 0x80 != 0 &&
        feedbackMaxPacketSize in 3..4 &&
        feedbackInterval > 0
```

Change the `DESC_ENDPOINT` branch to record every standard endpoint belonging to the current builder:

```kotlin
DESC_ENDPOINT -> {
    val builder = currentBuilder
    if (builder != null && length >= 7) {
        val rawMaxPacket = raw.u16le(offset + 4)
        val basePacket = rawMaxPacket and 0x07ff
        val transactions = 1 + ((rawMaxPacket ushr 11) and 0x03)
        builder.endpoints += EndpointDescriptor(
            address = raw.u8(offset + 2),
            attributes = raw.u8(offset + 3),
            maxPacketSize = basePacket * transactions,
            interval = raw.u8(offset + 6).coerceAtLeast(1),
            synchAddress = if (length >= 9) raw.u8(offset + 8) else 0,
        )
    }
}
```

Resolve endpoints inside `builders.values.mapNotNull` before constructing `StreamingAlt`:

```kotlin
val dataEndpoint = builder.endpoints
    .filter { endpoint ->
        endpoint.isIsochronous &&
            !endpoint.isIn &&
            endpoint.usage == ENDPOINT_USAGE_DATA
    }
    .singleOrNull()
    ?: return@mapNotNull null

val feedbackEndpoint = if (dataEndpoint.synchronizationType == SYNC_ASYNCHRONOUS) {
    if (builder.protocol >= 0x20) {
        builder.endpoints
            .filter { endpoint ->
                endpoint.isIsochronous &&
                    endpoint.isIn &&
                    endpoint.usage == ENDPOINT_USAGE_FEEDBACK
            }
            .singleOrNull()
    } else {
        builder.endpoints
            .filter { endpoint ->
                endpoint.isIsochronous &&
                    endpoint.isIn &&
                    dataEndpoint.synchAddress != 0 &&
                    endpoint.address == dataEndpoint.synchAddress
            }
            .singleOrNull()
    }
} else {
    null
}
```

Use `dataEndpoint` for the existing data endpoint fields and
`feedbackEndpoint?.field ?: 0` for the new feedback fields. Keep the existing
`.filterNot(StreamingAlt::requiresExplicitFeedback)` line in `select` so Task 1 cannot activate an unpaced asynchronous stream.

- [ ] **Step 4: Run parser tests and verify GREEN**

Run the focused command from Step 2.

Expected: all `UsbAudioDescriptorParserTest` tests pass, including the original adaptive and discrete-rate cases, while the UAC1 asynchronous selection assertion remains `null`.

- [ ] **Step 5: Commit the parser metadata change**

```powershell
git add app/src/main/java/dev/amenhancer/module/usb/UsbAudioDescriptorParser.kt app/src/test/java/dev/amenhancer/module/usb/UsbAudioDescriptorParserTest.kt
git commit -m "feat: parse USB explicit feedback endpoints"
```

---

### Task 2: Implement and compile-test fixed-point feedback math

**Files:**
- Create: `app/src/main/cpp/UsbFeedbackClock.h`
- Create: `app/src/main/cpp/UsbFeedbackClockCompileTest.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt:4-11`

**Interfaces:**
- Consumes: three- or four-byte feedback payloads, requested sample rate, USB bus tick rate, and data endpoint service interval.
- Produces: `usb_feedback::decodeFeedbackQ16`, `nominalFeedbackQ16`, `serviceTicks`, `feedbackMatchesRate`, `feedbackFitsPacket`, and `PacketScheduler` for Task 4.

- [ ] **Step 1: Add the compile-time test target before the implementation header exists**

Create `UsbFeedbackClockCompileTest.cpp` with:

```cpp
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
```

Add `UsbFeedbackClockCompileTest.cpp` to `add_library` in `CMakeLists.txt`.

- [ ] **Step 2: Build native debug code and verify RED**

Run:

```powershell
.\gradlew.bat :app:externalNativeBuildDebug
```

Expected: C++ compilation fails because `UsbFeedbackClock.h` does not exist. A Gradle, SDK, or JDK setup failure is not the intended RED.

- [ ] **Step 3: Implement the pure C++17 feedback helpers**

Create `UsbFeedbackClock.h`:

```cpp
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
```

- [ ] **Step 4: Build native debug code and verify GREEN**

Run the command from Step 2.

Expected: native compilation succeeds and every `static_assert` passes.

- [ ] **Step 5: Commit the isolated clock implementation**

```powershell
git add app/src/main/cpp/UsbFeedbackClock.h app/src/main/cpp/UsbFeedbackClockCompileTest.cpp app/src/main/cpp/CMakeLists.txt
git commit -m "feat: add USB feedback clock scheduler"
```

---

### Task 3: Carry feedback endpoint parameters through Broker, IPC, Lease, and JNI

**Files:**
- Modify: `app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt:1-178`
- Modify: `app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt:18-29`
- Modify: `app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt:151-169`
- Modify: `app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt:27-45,207-227`
- Modify: `app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt:30-48,119-130`
- Modify: `app/src/main/cpp/UsbDirectUac.cpp:66-90,488-558`

**Interfaces:**
- Consumes: `StreamingAlt.feedbackEndpointAddress`, `feedbackMaxPacketSize`, and `feedbackInterval` from Task 1.
- Produces: the same three `Int` values in `UsbDirectDeviceClient.Lease` and the corresponding `jint` values in `nativeOpen` for Task 4.
- Safety gate: asynchronous selection is still disabled after this task.

- [ ] **Step 1: Add a failing transport-contract regression test**

Add this test to `UsbDirectUacStructuralRegressionTest`:

```kotlin
@Test
fun `explicit feedback endpoint survives broker IPC lease and JNI boundary`() {
    val ipc = projectFile(
        "app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt",
    )
    val broker = projectFile(
        "app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt",
    )
    val client = projectFile(
        "app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt",
    )
    val bridge = projectFile(
        "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt",
    )
    val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")

    assertTrue(ipc.contains("KEY_FEEDBACK_ENDPOINT_ADDRESS"))
    assertTrue(ipc.contains("KEY_FEEDBACK_MAX_PACKET_SIZE"))
    assertTrue(ipc.contains("KEY_FEEDBACK_INTERVAL"))
    assertTrue(broker.contains("alternative.feedbackEndpointAddress"))
    assertTrue(broker.contains("alternative.feedbackMaxPacketSize"))
    assertTrue(broker.contains("alternative.feedbackInterval"))
    assertTrue(client.contains("val feedbackEndpointAddress: Int"))
    assertTrue(client.contains("val feedbackMaxPacketSize: Int"))
    assertTrue(client.contains("val feedbackInterval: Int"))
    assertTrue(bridge.contains("lease.feedbackEndpointAddress"))
    assertTrue(bridge.contains("lease.feedbackMaxPacketSize"))
    assertTrue(bridge.contains("lease.feedbackInterval"))
    assertTrue(native.contains("jint feedbackEndpointAddress"))
    assertTrue(native.contains("jint feedbackMaxPacketSize"))
    assertTrue(native.contains("jint feedbackInterval"))
}
```

- [ ] **Step 2: Run the focused structural test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest.explicit feedback endpoint survives broker IPC lease and JNI boundary"
```

Expected: assertion failure on the first missing feedback key.

- [ ] **Step 3: Add the three bundle keys and Broker payload fields**

Add to `UsbDirectIpc`:

```kotlin
const val KEY_FEEDBACK_ENDPOINT_ADDRESS = "feedback_endpoint_address"
const val KEY_FEEDBACK_MAX_PACKET_SIZE = "feedback_max_packet_size"
const val KEY_FEEDBACK_INTERVAL = "feedback_interval"
```

Add to the Broker success bundle immediately after the data endpoint interval:

```kotlin
putInt(UsbDirectIpc.KEY_FEEDBACK_ENDPOINT_ADDRESS, alternative.feedbackEndpointAddress)
putInt(UsbDirectIpc.KEY_FEEDBACK_MAX_PACKET_SIZE, alternative.feedbackMaxPacketSize)
putInt(UsbDirectIpc.KEY_FEEDBACK_INTERVAL, alternative.feedbackInterval)
```

- [ ] **Step 4: Extend `Lease`, bundle decoding, and bridge argument order**

Add these fields after `interval` in `UsbDirectDeviceClient.Lease`:

```kotlin
val feedbackEndpointAddress: Int,
val feedbackMaxPacketSize: Int,
val feedbackInterval: Int,
```

Decode them in the same order:

```kotlin
feedbackEndpointAddress = data.getInt(UsbDirectIpc.KEY_FEEDBACK_ENDPOINT_ADDRESS),
feedbackMaxPacketSize = data.getInt(UsbDirectIpc.KEY_FEEDBACK_MAX_PACKET_SIZE),
feedbackInterval = data.getInt(UsbDirectIpc.KEY_FEEDBACK_INTERVAL),
```

Insert these arguments after `lease.interval` in both the call and external declaration in `UsbDirectUacBridge`:

```kotlin
lease.feedbackEndpointAddress,
lease.feedbackMaxPacketSize,
lease.feedbackInterval,
```

and:

```kotlin
feedbackEndpointAddress: Int,
feedbackMaxPacketSize: Int,
feedbackInterval: Int,
```

- [ ] **Step 5: Extend and validate the native open contract**

Add the same three `jint` parameters after `interval` in JNI `nativeOpen`, and add these session fields:

```cpp
int feedbackEndpointAddress = 0;
int feedbackMaxPacketSize = 0;
int feedbackInterval = 0;
bool usesExplicitFeedback = false;
```

Validate before duplicating the file descriptor:

```cpp
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
```

Copy the values into `Session` and set
`usesExplicitFeedback = anyFeedbackParameter`. Do not yet allocate feedback
URBs or remove the asynchronous selection gate.

- [ ] **Step 6: Run focused tests and native build and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest"
.\gradlew.bat :app:externalNativeBuildDebug
```

Expected: structural tests pass and Kotlin/C++ signatures compile.

- [ ] **Step 7: Commit the transport contract**

```powershell
git add app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt app/src/main/cpp/UsbDirectUac.cpp app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt
git commit -m "feat: carry USB feedback endpoint parameters"
```

---

### Task 4: Run feedback IN URBs and enable asynchronous endpoint selection

**Files:**
- Modify: `app/src/test/java/dev/amenhancer/module/usb/UsbAudioDescriptorParserTest.kt`
- Modify: `app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt`
- Modify: `app/src/main/java/dev/amenhancer/module/usb/UsbAudioDescriptorParser.kt:230-244`
- Modify: `app/src/main/cpp/UsbDirectUac.cpp:1-272,488-558`

**Interfaces:**
- Consumes: parsed/transported feedback endpoint fields from Tasks 1 and 3 and `usb_feedback` helpers from Task 2.
- Produces: a native session in which valid feedback controls future OUT packet sizes and asynchronous streams become selectable only when `hasExplicitFeedback` is true.

- [ ] **Step 1: Change the UAC1 parser test expectation to the desired selection behavior**

In `parses UAC1 explicit feedback linked by bSynchAddress but keeps takeover gated`, rename the test to:

```kotlin
fun `selects UAC1 asynchronous output when explicit feedback is linked`()
```

Replace the final `assertNull(...)` with:

```kotlin
val selected = UsbAudioDescriptorParser.select(
    listOf(alternative),
    sampleRate = 48_000,
    channels = 2,
    preferredBits = 24,
)
assertEquals(alternative, selected)
```

Add the equivalent selection assertion to the UAC2 parser test. Keep the mismatched-address, cross-alternate, and implicit-feedback rejection assertions unchanged.

- [ ] **Step 2: Add a failing native lifecycle contract test**

Extend `native engine uses usbfs isochronous URBs rather than AAudio` with:

```kotlin
assertTrue(native.contains("#include \"UsbFeedbackClock.h\""))
assertTrue(native.contains("kFeedbackUrbCount = 2"))
assertTrue(native.contains("IsoRole::FeedbackIn"))
assertTrue(native.contains("decodeFeedbackQ16"))
assertTrue(native.contains("feedbackMatchesRate"))
assertTrue(native.contains("feedbackFitsPacket"))
assertTrue(native.contains("USB Direct feedback timeout"))
assertTrue(native.contains("USB Direct feedback payload invalid"))
```

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "dev.amenhancer.module.usb.UsbAudioDescriptorParserTest" --tests "dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest"
```

Expected: the asynchronous selection assertions return `null`, and the native lifecycle assertions fail because no feedback URB worker exists.

- [ ] **Step 4: Enable only asynchronous alternatives with valid explicit feedback**

Replace the unconditional asynchronous rejection in `select`:

```kotlin
.filterNot(StreamingAlt::requiresExplicitFeedback)
```

with:

```kotlin
.filter { alternative ->
    !alternative.requiresExplicitFeedback || alternative.hasExplicitFeedback
}
```

- [ ] **Step 5: Add feedback state and speed-aware scheduler setup to `Session`**

Include the helper and add constants:

```cpp
#include "UsbFeedbackClock.h"

constexpr int kFeedbackUrbCount = 2;
constexpr int kMaxInvalidFeedbackPackets = 8;
constexpr auto kFeedbackTimeout = std::chrono::seconds(2);
```

Give every slot a role:

```cpp
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
```

Replace `intervalsPerSecond`/`framePhase` with these worker-owned fields in `Session`:

```cpp
bool highOrSuperSpeed = false;
int busTicksPerSecond = 1000;
int outputServiceTicks = 1;
usb_feedback::PacketScheduler packetScheduler{0};
bool hasValidFeedback = false;
int invalidFeedbackPackets = 0;
std::chrono::steady_clock::time_point feedbackStartedAt{};
std::chrono::steady_clock::time_point lastValidFeedbackAt{};
std::string lastFeedbackError;
```

Replace `detectIntervalsPerSecond` with a speed result:

```cpp
bool isHighOrSuperSpeed(int fd) {
    int speed = -1;
#ifdef USBDEVFS_GET_SPEED
    speed = ioctl(fd, USBDEVFS_GET_SPEED);
#endif
    return speed == 3 || speed == 5 || speed == 6;
}
```

During native open, initialize:

```cpp
session->highOrSuperSpeed = isHighOrSuperSpeed(duplicatedFd);
session->busTicksPerSecond = session->highOrSuperSpeed ? 8000 : 1000;
session->outputServiceTicks = usb_feedback::serviceTicks(
    session->highOrSuperSpeed,
    session->interval
);
session->packetScheduler.updateFeedback(
    usb_feedback::nominalFeedbackQ16(sampleRate, session->busTicksPerSecond)
);
```

Change `framesForNextInterval` to:

```cpp
int framesForNextInterval(Session* session) {
    return session->packetScheduler.nextFrames(session->outputServiceTicks);
}
```

- [ ] **Step 6: Split audio and feedback URB preparation**

Rename `fillSlot` to `fillAudioSlot` and reject non-audio roles at entry. Rename
`submitSlot` to `submitAudioSlot`. Add:

```cpp
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
```

Allocate two feedback slots before the existing audio slots when
`usesExplicitFeedback` is true:

```cpp
for (int index = 0; index < kFeedbackUrbCount; ++index) {
    auto slot = std::make_unique<IsoSlot>();
    slot->role = IsoRole::FeedbackIn;
    slot->urbStorage.resize(sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc));
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
```

Set `slot->role = IsoRole::AudioOut` explicitly in the existing audio allocation loop.

- [ ] **Step 7: Validate feedback completions and enforce freshness**

Add:

```cpp
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
```

- [ ] **Step 8: Update the shared worker reap loop**

At worker start, replace the existing all-slots submit loop with:

```cpp
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
```

In the reap loop, replace unconditional audio resubmission with:

```cpp
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
```

Keep the existing final discard loop over every slot, so both roles are cancelled on shutdown. Ensure expected cancellation while `closing` does not overwrite the last meaningful error.

- [ ] **Step 9: Run focused tests and native build and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "dev.amenhancer.module.usb.UsbAudioDescriptorParserTest" --tests "dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest" --tests "dev.amenhancer.module.hook.UsbDirectWriteFailurePolicyTest"
.\gradlew.bat :app:externalNativeBuildDebug
```

Expected: all focused tests pass, all feedback clock `static_assert`s compile, and the native library builds.

- [ ] **Step 10: Commit the paced asynchronous worker**

```powershell
git add app/src/main/java/dev/amenhancer/module/usb/UsbAudioDescriptorParser.kt app/src/main/cpp/UsbDirectUac.cpp app/src/test/java/dev/amenhancer/module/usb/UsbAudioDescriptorParserTest.kt app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt
git commit -m "feat: pace USB Direct output from explicit feedback"
```

---

### Task 5: Make unsupported-device diagnostics and UI copy accurate

**Files:**
- Modify: `app/src/test/java/dev/amenhancer/module/usb/UsbAudioDescriptorParserTest.kt`
- Modify: `app/src/test/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsUiStructuralRegressionTest.kt:30-58`
- Modify: `app/src/main/java/dev/amenhancer/module/usb/UsbAudioDescriptorParser.kt:230-252`
- Modify: `app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt:100-116`
- Modify: `app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsActivity.kt:246-257,568-580`

**Interfaces:**
- Consumes: parsed alternatives and `hasExplicitFeedback` from Task 1.
- Produces: `selectionFailureReason(...)`, device-identifying Broker errors, and UI text that advertises standard explicit feedback while retaining implicit/vendor fallback.

- [ ] **Step 1: Add failing behavioral tests for selection failure classification**

Add to `UsbAudioDescriptorParserTest`:

```kotlin
@Test
fun `explains missing explicit feedback separately from format mismatch`() {
    val asyncWithoutFeedback = UsbAudioDescriptorParser.parse(
        bytes(
            9, 0x04, 1, 1, 1, 0x01, 0x02, 0x00, 0,
            7, 0x24, 0x01, 1, 1, 0x01, 0x00,
            11, 0x24, 0x02, 0x01, 2, 3, 24, 1, 0x80, 0xbb, 0x00,
            9, 0x05, 0x01, 0x05, 0x20, 0x01, 1, 0, 0,
        ),
    )

    assertEquals(
        "Asynchronous UAC output has no standard explicit feedback endpoint",
        UsbAudioDescriptorParser.selectionFailureReason(
            asyncWithoutFeedback,
            sampleRate = 48_000,
            channels = 2,
        ),
    )
    assertEquals(
        "No UAC1/UAC2 isochronous OUT alternate setting matches 96000Hz/2ch",
        UsbAudioDescriptorParser.selectionFailureReason(
            asyncWithoutFeedback,
            sampleRate = 96_000,
            channels = 2,
        ),
    )
}
```

Add to `UsbBitPerfectSettingsUiStructuralRegressionTest`:

```kotlin
@Test
fun `USB Direct copy advertises explicit feedback and preserves implicit fallback`() {
    val activity = projectFile(
        "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsActivity.kt",
    )

    assertTrue(activity.contains("支持 UAC1/UAC2 标准显式 feedback"))
    assertTrue(activity.contains("隐式 feedback/厂商私有格式仍会回退"))
    assertFalse(activity.contains("第一版暂不支持异步 feedback DAC"))
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "dev.amenhancer.module.usb.UsbAudioDescriptorParserTest" --tests "dev.amenhancer.module.ui.UsbBitPerfectSettingsUiStructuralRegressionTest"
```

Expected: parser test does not compile because `selectionFailureReason` is missing, and the UI text assertions fail after that symbol is introduced.

- [ ] **Step 3: Implement deterministic failure classification**

Add to `UsbAudioDescriptorParser`:

```kotlin
fun selectionFailureReason(
    alternatives: List<StreamingAlt>,
    sampleRate: Int,
    channels: Int,
): String {
    val formatMatches = alternatives.filter { alternative ->
        alternative.matchesFormat(sampleRate, channels)
    }
    return if (
        formatMatches.any { alternative ->
            alternative.requiresExplicitFeedback && !alternative.hasExplicitFeedback
        }
    ) {
        "Asynchronous UAC output has no standard explicit feedback endpoint"
    } else {
        "No UAC1/UAC2 isochronous OUT alternate setting matches ${sampleRate}Hz/${channels}ch"
    }
}
```

In the Broker, retain the parsed list instead of parsing inline:

```kotlin
val alternatives = UsbAudioDescriptorParser.parse(rawDescriptors)
val alternative = UsbAudioDescriptorParser.select(
    alternatives,
    sampleRate = sampleRate,
    channels = channels,
    preferredBits = preferredBits,
)
```

On failure, include a stable device label:

```kotlin
val deviceLabel = device.productName?.takeIf(String::isNotBlank) ?: device.deviceName
val reason = UsbAudioDescriptorParser.selectionFailureReason(
    alternatives,
    sampleRate,
    channels,
)
replyError(message, "$deviceLabel: $reason")
```

- [ ] **Step 4: Update the USB Direct description**

Replace the old limitation sentence in `UsbBitPerfectSettingsActivity` with:

```kotlin
text = "Android USB Host 授权 → claim AudioStreaming interface → native usbfs isochronous；支持 UAC1/UAC2 标准显式 feedback，隐式 feedback/厂商私有格式仍会回退"
```

Keep the live unsupported-path wording general because format mismatch and unsupported feedback still share the same status state.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: parser and UI tests pass.

- [ ] **Step 6: Commit diagnostics and copy**

```powershell
git add app/src/main/java/dev/amenhancer/module/usb/UsbAudioDescriptorParser.kt app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsActivity.kt app/src/test/java/dev/amenhancer/module/usb/UsbAudioDescriptorParserTest.kt app/src/test/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsUiStructuralRegressionTest.kt
git commit -m "fix: explain USB feedback compatibility failures"
```

---

### Task 6: Verify the complete implementation and prepare hardware validation

**Files:**
- Verify only: all files changed in Tasks 1-5

**Interfaces:**
- Consumes: the complete parser, transport, clock, native worker, diagnostics, and fallback behavior.
- Produces: reproducible automated verification evidence and a concrete on-device validation checklist.

- [ ] **Step 1: Check formatting and unintended changes**

Run:

```powershell
git status --short
git diff --check HEAD~5..HEAD
git diff --stat HEAD~5..HEAD
```

Expected: only the planned USB feedback files and documentation are present; `git diff --check` prints nothing.

- [ ] **Step 2: Run the focused feedback regression set**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "dev.amenhancer.module.usb.UsbAudioDescriptorParserTest" --tests "dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest" --tests "dev.amenhancer.module.hook.UsbDirectWriteFailurePolicyTest" --tests "dev.amenhancer.module.ui.UsbBitPerfectSettingsUiStructuralRegressionTest"
```

Expected: all focused tests pass with no failing or skipped test.

- [ ] **Step 3: Run the complete unit-test suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` and no failed unit test.

- [ ] **Step 4: Build native code and assemble the debug APK**

```powershell
.\gradlew.bat :app:externalNativeBuildDebug :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`; all C++ `static_assert`s compile; Kotlin and C++ JNI declarations build; a debug APK is produced under `app/build/outputs/apk/debug/`.

- [ ] **Step 5: Inspect the final diff and verify fail-open invariants**

Confirm in the diff that:

```text
select(async) requires hasExplicitFeedback
feedback parameter validation occurs before dup(fd)
feedback URBs are submitted before audio URBs
feedback timeout and invalid-payload paths set session->failed
stopSession discards every slot and joins the worker
UsbDirectUacController and UsbDirectWriteFailurePolicy restoration logic are unchanged
```

Run:

```powershell
git diff HEAD~5..HEAD -- app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacController.kt app/src/main/java/dev/amenhancer/module/hook/UsbDirectWriteFailurePolicy.kt
```

Expected: no diff for the controller or failure policy unless a test-proven correction became necessary.

- [ ] **Step 6: Record hardware validation as required follow-up, not simulated proof**

On a standards-compliant explicit-feedback DAC, install the debug APK, grant USB Host permission, restart Apple Music, and verify 44.1, 48, and 96 kHz only where advertised by the DAC. For each supported rate, play continuously for five minutes and confirm:

```text
state reaches USB Direct configured/active
device name is reported
no feedback timeout or invalid payload is logged
no packet exceeds wMaxPacketSize
no audible underrun, overrun, click, or repeated segment occurs
disconnect/reconnect releases and reacquires the interface
pause/stop does not spuriously resume AudioTrack
```

If no physical DAC is available, report hardware validation as pending. Do not claim the real device issue resolved solely from local tests.

---

## Completion Evidence

Before reporting completion, capture:

```powershell
git status --short
git log --oneline -6
.\gradlew.bat :app:testDebugUnitTest :app:externalNativeBuildDebug :app:assembleDebug
```

The final report must distinguish automated verification from pending or completed hardware verification and must mention any toolchain installation performed.
