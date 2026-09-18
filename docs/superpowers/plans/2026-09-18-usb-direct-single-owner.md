# USB Direct Single-Owner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move USB Direct interface ownership from the AM++ broker process into the injected Apple Music native session so one process owns claim, alternate-setting selection, sample-rate control, isochronous URBs, release, and fd close.

**Architecture:** The broker remains responsible for Android USB permission, opening the device, descriptor parsing, stream selection, and lending a duplicated usbfs fd. The native Apple Music session receives all metadata required to claim interfaces and configure UAC1/UAC2, then performs setup and teardown entirely on its own fd before returning to Android AudioTrack on failure.

**Tech Stack:** Kotlin/Android USB Host API, Android Messenger/Binder IPC, JNI, C++17, Linux usbfs ioctls, JUnit structural regression tests, CMake compile-time tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-18-usb-direct-single-owner-design.md`

## Global Constraints

- Broker must not own a claimed USB interface after this change.
- Interface release must happen only after URBs are discarded and the native worker has joined.
- UAC1/UAC2 sample-rate behavior must preserve current semantics.
- Do not change explicit-feedback scheduling, PCM conversion/gain behavior, or 4-packets-per-URB layout.
- Do not add `USBDEVFS_RESET` or `USBDEVFS_SETCONFIGURATION` fallbacks.
- Existing buffer presets remain 50 / 100 / 250 / 500 / 1000 ms for PCM and Auto / 2 / 4 / 8 / 16 ms for transfer buffering.
- All setup failures remain fail-open to the original Android `AudioTrack`.
- Final verification command is `./gradlew test lintVitalRelease assembleDebug --no-daemon`.

---

## File map

- `app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt` — add immutable metadata keys required by native ownership.
- `app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt` — become stateless permission/descriptor/fd broker; remove interface ownership and sample-rate control.
- `app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt` — carry the new lease metadata.
- `app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt` — pass ownership metadata across JNI.
- `app/src/main/cpp/UsbDirectUacControl.h` — pure helpers for UAC payload/addressing and fixed-rate fallback.
- `app/src/main/cpp/UsbDirectUacControlCompileTest.cpp` — compile-time tests for pure UAC helpers.
- `app/src/main/cpp/UsbDirectUac.cpp` — claim/release interfaces, set alternate, configure UAC1/UAC2, and enforce teardown ordering.
- `app/src/main/cpp/CMakeLists.txt` — compile the new helper test TU.
- `app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt` — enforce single-owner architecture and JNI metadata.
- Existing `UsbUacSampleRateControl.kt` / tests remain temporarily as a behavior reference; delete only if the final repository has no other user and tests are migrated.

---

### Task 1: Lock the single-owner contract with failing structural tests

**Files:**
- Modify: `app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt`

**Interfaces:**
- Consumes: current broker/client/bridge/native source text.
- Produces: regression assertions defining the new architecture before implementation.

- [ ] **Step 1: Replace the broker-ownership test with a single-owner test**

Replace the current test named:

```kotlin
fun `USB Host permission broker claims AudioStreaming and validates Apple Music uid`()
```

with:

```kotlin
@Test
fun `USB Host broker lends fd while native session owns interface lifecycle`() {
    val manifest = projectFile("app/src/main/AndroidManifest.xml")
    val permission = projectFile(
        "app/src/main/java/dev/amenhancer/module/usb/UsbDirectPermissionActivity.kt",
    )
    val broker = projectFile(
        "app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt",
    )
    val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")

    assertTrue(manifest.contains("android.hardware.usb.host"))
    assertTrue(permission.contains("manager.requestPermission(device, permissionIntent)"))
    assertTrue(broker.contains("message.sendingUid"))
    assertTrue(broker.contains("ModuleConstants.TARGET_PACKAGE"))
    assertTrue(broker.contains("ParcelFileDescriptor.fromFd(connection.fileDescriptor)"))

    assertFalse(broker.contains("connection.claimInterface("))
    assertFalse(broker.contains("connection.setInterface("))
    assertFalse(broker.contains("UsbUacSampleRateControl.configureUac2"))
    assertFalse(broker.contains("connection.controlTransfer("))
    assertFalse(broker.contains("ClaimedSession"))
    assertFalse(broker.contains("releaseClaims("))

    assertTrue(native.contains("USBDEVFS_CLAIMINTERFACE"))
    assertTrue(native.contains("USBDEVFS_SETINTERFACE"))
    assertTrue(native.contains("USBDEVFS_RELEASEINTERFACE"))
    assertTrue(native.contains("USBDEVFS_CONTROL"))
}
```

- [ ] **Step 2: Add a metadata/JNI boundary test**

Add:

```kotlin
@Test
fun `UAC ownership metadata survives broker IPC lease and JNI boundary`() {
    val ipc = projectFile("app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt")
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

    listOf(
        "KEY_AUDIO_CONTROL_INTERFACE",
        "KEY_CLOCK_SOURCE_ID",
        "KEY_FIXED_SAMPLE_RATE_MATCH",
    ).forEach { key -> assertTrue(ipc.contains(key)) }

    assertTrue(broker.contains("alternative.audioControlInterface"))
    assertTrue(broker.contains("alternative.clockSourceId"))
    assertTrue(broker.contains("fixedSampleRateMatch"))

    assertTrue(client.contains("val audioControlInterface: Int"))
    assertTrue(client.contains("val clockSourceId: Int"))
    assertTrue(client.contains("val fixedSampleRateMatch: Boolean"))

    assertTrue(bridge.contains("lease.interfaceNumber"))
    assertTrue(bridge.contains("lease.alternateSetting"))
    assertTrue(bridge.contains("lease.audioControlInterface"))
    assertTrue(bridge.contains("lease.clockSourceId"))
    assertTrue(bridge.contains("lease.fixedSampleRateMatch"))

    assertTrue(native.contains("jint interfaceNumber"))
    assertTrue(native.contains("jint alternateSetting"))
    assertTrue(native.contains("jint audioControlInterface"))
    assertTrue(native.contains("jint clockSourceId"))
    assertTrue(native.contains("jboolean fixedSampleRateMatch"))
}
```

- [ ] **Step 3: Add teardown-order assertions**

Add to the native engine structural test:

```kotlin
val stopSession = native.substringAfter("void stopSession(Session* session)")
    .substringBefore("int64_t clampToBits")

val joinIndex = stopSession.indexOf("session->worker.join()")
val releaseIndex = stopSession.indexOf("releaseInterfaces(session)")
assertTrue(joinIndex >= 0)
assertTrue(releaseIndex > joinIndex)
```

- [ ] **Step 4: Run the focused test and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest --no-daemon
```

Expected: FAIL because broker still claims interfaces, new IPC keys/lease fields do not exist, and native lacks the new claim/release/control ownership.

- [ ] **Step 5: Commit the RED contract**

```bash
git add app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt
git commit -m "test: define single-owner USB Direct lifecycle"
```

---

### Task 2: Extend IPC and lease metadata without changing ownership yet

**Files:**
- Modify: `app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt`
- Modify: `app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt`
- Modify: `app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt`
- Modify: `app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt`
- Modify: `app/src/main/cpp/UsbDirectUac.cpp`

**Interfaces:**
- Consumes: `UsbAudioDescriptorParser.StreamingAlt.audioControlInterface`, `.clockSourceId`, `.sampleRates`.
- Produces:
  - `UsbDirectIpc.KEY_AUDIO_CONTROL_INTERFACE`
  - `UsbDirectIpc.KEY_CLOCK_SOURCE_ID`
  - `UsbDirectIpc.KEY_FIXED_SAMPLE_RATE_MATCH`
  - `Lease.audioControlInterface: Int`
  - `Lease.clockSourceId: Int`
  - `Lease.fixedSampleRateMatch: Boolean`
  - expanded `nativeOpen(...)` signature.

- [ ] **Step 1: Add IPC keys**

In `UsbDirectIpc.kt` add:

```kotlin
const val KEY_AUDIO_CONTROL_INTERFACE = "audio_control_interface"
const val KEY_CLOCK_SOURCE_ID = "clock_source_id"
const val KEY_FIXED_SAMPLE_RATE_MATCH = "fixed_sample_rate_match"
```

- [ ] **Step 2: Compute the UAC1 fixed-rate compatibility flag in the broker**

After selecting `alternative`, define:

```kotlin
val fixedSampleRateMatch =
    !alternative.isUac2 &&
        alternative.sampleRates.size == 1 &&
        sampleRate in alternative.sampleRates
```

In the reply Bundle add:

```kotlin
putInt(UsbDirectIpc.KEY_AUDIO_CONTROL_INTERFACE, alternative.audioControlInterface)
putInt(UsbDirectIpc.KEY_CLOCK_SOURCE_ID, alternative.clockSourceId)
putBoolean(UsbDirectIpc.KEY_FIXED_SAMPLE_RATE_MATCH, fixedSampleRateMatch)
```

Do not remove existing claim/configuration code in this task.

- [ ] **Step 3: Extend the client lease**

Add fields after `alternateSetting`:

```kotlin
val audioControlInterface: Int,
val clockSourceId: Int,
val fixedSampleRateMatch: Boolean,
```

Populate them in `handleResult`:

```kotlin
audioControlInterface = data.getInt(UsbDirectIpc.KEY_AUDIO_CONTROL_INTERFACE, -1),
clockSourceId = data.getInt(UsbDirectIpc.KEY_CLOCK_SOURCE_ID),
fixedSampleRateMatch = data.getBoolean(UsbDirectIpc.KEY_FIXED_SAMPLE_RATE_MATCH),
```

- [ ] **Step 4: Extend the JNI bridge signature**

Pass these values from `open()`:

```kotlin
nativeOpen(
    lease.fd.fd,
    lease.sampleRate,
    inputFormatCode,
    lease.channels,
    lease.interfaceNumber,
    lease.alternateSetting,
    lease.audioControlInterface,
    lease.clockSourceId,
    lease.fixedSampleRateMatch,
    lease.protocol,
    lease.endpointAddress,
    lease.maxPacketSize,
    lease.interval,
    lease.feedbackEndpointAddress,
    lease.feedbackMaxPacketSize,
    lease.feedbackInterval,
    lease.subslotBytes,
    lease.bitResolution,
    pcmBufferMs,
    transferBufferMs,
)
```

Update the declaration exactly:

```kotlin
private external fun nativeOpen(
    fd: Int,
    sampleRate: Int,
    inputFormatCode: Int,
    channels: Int,
    interfaceNumber: Int,
    alternateSetting: Int,
    audioControlInterface: Int,
    clockSourceId: Int,
    fixedSampleRateMatch: Boolean,
    protocol: Int,
    endpointAddress: Int,
    maxPacketSize: Int,
    interval: Int,
    feedbackEndpointAddress: Int,
    feedbackMaxPacketSize: Int,
    feedbackInterval: Int,
    targetSubslotBytes: Int,
    targetBitResolution: Int,
    pcmBufferMs: Int,
    transferBufferMs: Int,
): Long
```

- [ ] **Step 5: Update native JNI parameters only enough to compile**

Add corresponding parameters to `Java_dev_amenhancer_module_hook_UsbDirectUacBridge_nativeOpen`:

```cpp
jint interfaceNumber,
jint alternateSetting,
jint audioControlInterface,
jint clockSourceId,
jboolean fixedSampleRateMatch,
jint protocol,
```

Before native ownership is implemented, mark unused parameters explicitly:

```cpp
(void) interfaceNumber;
(void) alternateSetting;
(void) audioControlInterface;
(void) clockSourceId;
(void) fixedSampleRateMatch;
(void) protocol;
```

- [ ] **Step 6: Run focused structural and Kotlin compile tests**

```bash
./gradlew testDebugUnitTest --tests dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest --no-daemon
```

Expected: metadata assertions pass; broker/native ownership assertions remain RED.

- [ ] **Step 7: Commit metadata plumbing**

```bash
git add app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt \
        app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt \
        app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt \
        app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt \
        app/src/main/cpp/UsbDirectUac.cpp
git commit -m "refactor: carry USB ownership metadata to native"
```

---

### Task 3: Add pure native UAC-control helpers and compile-time tests

**Files:**
- Create: `app/src/main/cpp/UsbDirectUacControl.h`
- Create: `app/src/main/cpp/UsbDirectUacControlCompileTest.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`

**Interfaces:**
- Produces:
  - `usb_direct_uac::uac1RatePayload(int)`
  - `usb_direct_uac::uac2RatePayload(int)`
  - `usb_direct_uac::uac2ControlIndex(int, int)`
  - `usb_direct_uac::acceptUac1SetCurResult(int, bool)`

- [ ] **Step 1: Create compile-time tests first**

Create `UsbDirectUacControlCompileTest.cpp`:

```cpp
#include "UsbDirectUacControl.h"

constexpr auto kRate44 = usb_direct_uac::uac1RatePayload(44100);
static_assert(kRate44[0] == 0x44);
static_assert(kRate44[1] == 0xAC);
static_assert(kRate44[2] == 0x00);

constexpr auto kRate192 = usb_direct_uac::uac2RatePayload(192000);
static_assert(kRate192[0] == 0x00);
static_assert(kRate192[1] == 0xEE);
static_assert(kRate192[2] == 0x02);
static_assert(kRate192[3] == 0x00);

static_assert(usb_direct_uac::uac2ControlIndex(7, 1) == 0x0701);
static_assert(usb_direct_uac::acceptUac1SetCurResult(3, false));
static_assert(usb_direct_uac::acceptUac1SetCurResult(-1, true));
static_assert(!usb_direct_uac::acceptUac1SetCurResult(-1, false));
```

- [ ] **Step 2: Add the new test TU to CMake and verify RED**

Add `UsbDirectUacControlCompileTest.cpp` to `add_library(ampp_audio SHARED ...)`.

Run:

```bash
./gradlew assembleDebug --no-daemon
```

Expected: native compile FAIL because `UsbDirectUacControl.h` does not exist.

- [ ] **Step 3: Implement the pure helper header**

```cpp
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
```

- [ ] **Step 4: Verify GREEN native compile**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit pure helpers**

```bash
git add app/src/main/cpp/UsbDirectUacControl.h \
        app/src/main/cpp/UsbDirectUacControlCompileTest.cpp \
        app/src/main/cpp/CMakeLists.txt
git commit -m "test: add native UAC control helpers"
```

---

### Task 4: Make the broker stateless and remove Java-side interface ownership

**Files:**
- Modify: `app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt`
- Delete if unreferenced: `app/src/main/java/dev/amenhancer/module/usb/UsbInterfaceClaimTransaction.kt`
- Delete if unreferenced: `app/src/test/java/dev/amenhancer/module/usb/UsbInterfaceClaimTransactionTest.kt`

**Interfaces:**
- Consumes: selected `StreamingAlt` metadata and Android USB permission.
- Produces: one Binder reply containing a duplicated fd; broker retains no active USB session.

- [ ] **Step 1: Remove service session state and lifecycle teardown**

Delete:

```kotlin
private var session: ClaimedSession? = null
```

Change `WHAT_RELEASE` to:

```kotlin
UsbDirectIpc.WHAT_RELEASE -> replyOk(message, null)
```

Remove `onUnbind`, `onDestroy`, `releaseSession`, `releaseClaims`, and `ClaimedSession`.

- [ ] **Step 2: Remove broker-side claim/set-interface/sample-rate configuration**

Delete all production calls to:

```kotlin
connection.claimInterface(...)
connection.setInterface(...)
configureSampleRate(...)
UsbUacSampleRateControl.configureUac2(...)
connection.controlTransfer(...)
```

Also remove `findInterface`, `findAudioControlInterface`, `configureSampleRate`, and unused UAC constants/imports.

- [ ] **Step 3: Validate UAC2 ownership metadata before returning the fd**

```kotlin
if (
    alternative.isUac2 &&
    (alternative.audioControlInterface < 0 || alternative.clockSourceId <= 0)
) {
    connection.close()
    replyError(message, "Selected UAC2 stream is missing clock-source metadata")
    return
}
```

- [ ] **Step 4: Reply with the duplicated fd and close only the broker copy**

Keep fd duplication:

```kotlin
val parcelFd = runCatching {
    ParcelFileDescriptor.fromFd(connection.fileDescriptor)
}.getOrElse { error ->
    connection.close()
    replyError(message, "USB FD duplication failed: ${error.message ?: error.javaClass.simpleName}")
    return
}
```

After `replyOk(message, data)`:

```kotlin
runCatching { parcelFd.close() }
runCatching { connection.close() }
```

The reply must happen first so Binder can duplicate the descriptor into the transaction before the local copies close.

- [ ] **Step 5: Remove Java claim transaction helper if production-unreferenced**

Run:

```bash
git grep -n "UsbInterfaceClaimTransaction"
```

If only its implementation/test remain:

```bash
git rm app/src/main/java/dev/amenhancer/module/usb/UsbInterfaceClaimTransaction.kt
git rm app/src/test/java/dev/amenhancer/module/usb/UsbInterfaceClaimTransactionTest.kt
```

- [ ] **Step 6: Run focused structural test**

```bash
./gradlew testDebugUnitTest --tests dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest --no-daemon
```

Expected: broker-negative assertions GREEN; native ownership assertions still RED.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: make USB Direct broker stateless"
```

---

### Task 5: Move claim, alternate selection, and UAC rate control into native open

**Files:**
- Modify: `app/src/main/cpp/UsbDirectUac.cpp`
- Modify: `app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt`

**Interfaces:**
- Consumes: JNI metadata from Task 2 and pure helpers from Task 3.
- Produces:
  - `claimInterfaces(Session*) -> bool`
  - `releaseInterfaces(Session*) -> void`
  - `selectStreamingAlternate(Session*) -> bool`
  - `configureSampleRate(Session*) -> bool`

- [ ] **Step 1: Extend Session ownership state**

Add:

```cpp
int interfaceNumber = -1;
int alternateSetting = 0;
int audioControlInterface = -1;
int clockSourceId = 0;
int protocol = 0;
bool fixedSampleRateMatch = false;

bool controlInterfaceClaimed = false;
bool streamingInterfaceClaimed = false;
bool alternateSettingActive = false;
```

- [ ] **Step 2: Add UAC helper include and request constants**

```cpp
#include "UsbDirectUacControl.h"
```

Add:

```cpp
constexpr int kControlTimeoutMillis = 1000;
constexpr uint8_t kUacCur = 0x01;
constexpr uint16_t kSamplingFreqControl = 0x01 << 8;
constexpr uint8_t kUsbDirOut = 0x00;
constexpr uint8_t kUsbDirIn = 0x80;
constexpr uint8_t kUsbTypeClass = 0x20;
constexpr uint8_t kUsbRecipientInterface = 0x01;
constexpr uint8_t kUsbRecipientEndpoint = 0x02;
```

- [ ] **Step 3: Implement interface claim and release**

```cpp
bool claimInterface(Session* session, int interfaceNumber) {
    if (interfaceNumber < 0) return false;
    unsigned int iface = static_cast<unsigned int>(interfaceNumber);
    if (ioctl(session->fd, USBDEVFS_CLAIMINTERFACE, &iface) == 0) return true;

#ifdef USBDEVFS_DISCONNECT_CLAIM
    usbdevfs_disconnect_claim disconnectClaim{};
    disconnectClaim.interface = static_cast<unsigned int>(interfaceNumber);
    std::strncpy(
        disconnectClaim.driver,
        "snd-usb-audio",
        sizeof(disconnectClaim.driver) - 1
    );
    disconnectClaim.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    if (ioctl(session->fd, USBDEVFS_DISCONNECT_CLAIM, &disconnectClaim) == 0) return true;
#endif

    setError(
        "USBDEVFS_CLAIMINTERFACE failed for interface " +
        std::to_string(interfaceNumber) +
        " errno=" + std::to_string(errno)
    );
    return false;
}
```

Then:

```cpp
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

void releaseInterfaces(Session* session) {
    if (session->streamingInterfaceClaimed) {
        unsigned int iface = static_cast<unsigned int>(session->interfaceNumber);
        ioctl(session->fd, USBDEVFS_RELEASEINTERFACE, &iface);
        session->streamingInterfaceClaimed = false;
    }
    if (
        session->controlInterfaceClaimed &&
        session->audioControlInterface != session->interfaceNumber
    ) {
        unsigned int iface = static_cast<unsigned int>(session->audioControlInterface);
        ioctl(session->fd, USBDEVFS_RELEASEINTERFACE, &iface);
    }
    session->controlInterfaceClaimed = false;
}
```

If the NDK header does not define `USBDEVFS_DISCONNECT_CLAIM`, compile without force-claim support and fail open after ordinary claim. Do not define private ioctl numbers.

- [ ] **Step 4: Implement alternate-setting selection**

```cpp
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
```

- [ ] **Step 5: Implement usbfs class control transfer wrapper**

```cpp
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
```

- [ ] **Step 6: Implement UAC1 rate configuration**

```cpp
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
```

- [ ] **Step 7: Implement UAC2 GET_CUR / SET_CUR / verify parity**

```cpp
uint32_t u32le(const uint8_t* data) {
    return static_cast<uint32_t>(data[0]) |
        (static_cast<uint32_t>(data[1]) << 8) |
        (static_cast<uint32_t>(data[2]) << 16) |
        (static_cast<uint32_t>(data[3]) << 24);
}
```

Then:

```cpp
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
```

- [ ] **Step 8: Validate and populate native ownership parameters**

Add validation:

```cpp
const bool uac2 = protocol >= 0x20;
if (
    interfaceNumber < 0 || alternateSetting <= 0 ||
    (uac2 && (audioControlInterface < 0 || clockSourceId <= 0))
) {
    setError("Invalid USB Direct ownership parameters");
    return 0;
}
```

Populate:

```cpp
session->interfaceNumber = interfaceNumber;
session->alternateSetting = alternateSetting;
session->audioControlInterface = audioControlInterface;
session->clockSourceId = clockSourceId;
session->protocol = protocol;
session->fixedSampleRateMatch = fixedSampleRateMatch == JNI_TRUE;
```

- [ ] **Step 9: Perform ownership setup before URB worker can start**

After session fields are populated and before returning a handle:

```cpp
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
```

- [ ] **Step 10: Enforce teardown order**

Change `stopSession` to:

```cpp
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
```

Never call `releaseInterfaces` from the worker.

- [ ] **Step 11: Run focused verification**

```bash
./gradlew testDebugUnitTest --tests dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest --no-daemon
./gradlew assembleDebug --no-daemon
```

Expected: PASS.

- [ ] **Step 12: Commit native ownership**

```bash
git add app/src/main/cpp/UsbDirectUac.cpp \
        app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt
git commit -m "fix: move USB Direct interface ownership to native"
```

---

### Task 6: Remove obsolete Java sample-rate control only after parity is proven

**Files:**
- Delete if unreferenced: `app/src/main/java/dev/amenhancer/module/usb/UsbUacSampleRateControl.kt`
- Delete if unreferenced: `app/src/test/java/dev/amenhancer/module/usb/UsbUacSampleRateControlTest.kt`
- Modify if needed: `app/src/test/java/dev/amenhancer/module/hook/UsbDirectUacStructuralRegressionTest.kt`

**Interfaces:**
- Consumes: native UAC1/UAC2 control from Task 5.
- Produces: one authoritative sample-rate implementation.

- [ ] **Step 1: Search remaining production references**

```bash
git grep -n "UsbUacSampleRateControl"
```

Expected: only implementation/test files remain.

- [ ] **Step 2: Delete obsolete Java implementation and tests**

```bash
git rm app/src/main/java/dev/amenhancer/module/usb/UsbUacSampleRateControl.kt
git rm app/src/test/java/dev/amenhancer/module/usb/UsbUacSampleRateControlTest.kt
```

- [ ] **Step 3: Strengthen structural assertions**

Add:

```kotlin
assertTrue(native.contains("configureUac1Rate"))
assertTrue(native.contains("configureUac2Rate"))
assertTrue(native.contains("usb_direct_uac::uac2ControlIndex"))
assertTrue(native.contains("USBDEVFS_CONTROL"))
assertFalse(broker.contains("UsbUacSampleRateControl"))
```

- [ ] **Step 4: Run all unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit cleanup**

```bash
git add -A
git commit -m "refactor: retire broker-side UAC rate control"
```

---

### Task 7: Full regression verification and review gate

**Files:**
- No planned production changes unless verification exposes a concrete defect.

**Interfaces:**
- Consumes: complete branch.
- Produces: CI-ready implementation with explicit device-test requirement.

- [ ] **Step 1: Run full repository verification**

```bash
./gradlew test lintVitalRelease assembleDebug --no-daemon
```

Expected: PASS with zero failing tests, successful release lint, CMake/native compilation, and debug APK linkage.

- [ ] **Step 2: Inspect final diff for forbidden ownership remnants**

```bash
git grep -n "claimInterface" -- app/src/main/java/dev/amenhancer/module/usb
git grep -n "setInterface" -- app/src/main/java/dev/amenhancer/module/usb
git grep -n "USBDEVFS_CLAIMINTERFACE\|USBDEVFS_SETINTERFACE\|USBDEVFS_RELEASEINTERFACE" -- app/src/main/cpp
```

Expected:
- no production Java broker claim/set-interface calls;
- native source contains claim/set/release.

- [ ] **Step 3: Verify teardown order directly**

Confirm `stopSession` is exactly:

```text
closing/running flags
-> DISCARDURB
-> notify
-> worker.join()
-> releaseInterfaces()
-> close(fd)
```

- [ ] **Step 4: Request code review**

Review for:
- Binder fd duplication/close semantics;
- partial-claim cleanup;
- same-number control/streaming interface handling;
- UAC2 GET/SET/verify parity;
- worker teardown/double-discard safety;
- no buffer or feedback behavior drift.

- [ ] **Step 5: Apply only concrete review corrections and re-run full verification**

```bash
./gradlew test lintVitalRelease assembleDebug --no-daemon
```

Expected: PASS on final head.

- [ ] **Step 6: Push/update `fix/usb-direct-single-owner` and open/update a draft PR**

PR text must explicitly state that CI cannot prove absence of the affected phone's kernel/USB-host reboot and that real-device acceptance remains required before merge.

---

## Device acceptance test before merge

Run on the affected phone with transfer buffer Auto first:

1. reboot;
2. connect DAC;
3. start Apple Music USB Direct playback;
4. confirm Direct Active;
5. repeatedly open AM++ main settings;
6. repeatedly open/close AM++ USB audio settings;
7. background/foreground both apps while playback continues;
8. stop and restart playback;
9. repeat at 8 ms transfer buffer;
10. repeat at 16 ms transfer buffer.

Pass criteria:
- no AM++ freeze;
- no Apple Music deadlock;
- no phone reboot;
- opening AM++ does not interrupt direct playback;
- stopping/restarting does not leave the interface busy;
- fail-open resumes Android AudioTrack after a deliberate USB Direct setup failure.

If the phone still reboots, collect `ro.boot.bootreason`, `sys.boot.reason`, `dumpsys bootstat`, and pstore/ramoops before attempting another architectural change.