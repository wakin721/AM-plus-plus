# USB Audio Explicit Feedback Design

## Problem

AM++ currently rejects every asynchronous USB Audio playback endpoint. The
descriptor parser recognizes the asynchronous synchronization type, but
`UsbAudioDescriptorParser.select` removes it because the native usbfs engine
sends packets at a nominal fixed rate and does not consume the DAC's feedback
endpoint. Many UAC1 and UAC2 DACs are asynchronous sinks, so the user sees
`No UAC1/UAC2 isochronous OUT alternate setting matches ...` even when the
device advertises the requested PCM format.

Removing the rejection without implementing feedback pacing is unsafe. The
host and DAC clocks would drift, eventually causing an underrun, overrun,
repeated samples, dropped samples, or audible corruption.

## Goals

- Support standards-compliant explicit feedback for asynchronous UAC1 and
  UAC2 playback endpoints.
- Continue supporting the existing synchronous and adaptive output paths.
- Drive USB OUT packet sizes from feedback using deterministic fixed-point
  arithmetic.
- Fail open to the original `AudioTrack`, AAudio, or the Android mixer when
  feedback is absent, malformed, stale, or unsafe.
- Keep descriptor interpretation and feedback math independently testable.

## Non-goals

- Implicit feedback endpoints.
- Vendor-specific feedback encodings or device-specific quirks.
- UAC3 or UAC4 descriptor layouts.
- Replacing usbfs with libusb.
- Supporting SuperSpeed burst/companion-descriptor features that the current
  USB Direct engine does not already support.
- Resampling PCM. The selected alternate setting must still match the source
  sample rate and channel count.

## Normative Basis

- UAC1 specifies a three-byte Q10.14 feedback value for an asynchronous sink
  and links the OUT endpoint to its synchronization IN endpoint through
  `bSynchAddress`:
  <https://www.usb.org/sites/default/files/audio10.pdf>
- UAC2 allows an AudioStreaming alternate setting to expose one data endpoint
  and one explicit feedback endpoint. The feedback endpoint is identified by
  isochronous transfer type and `Usage Type = Feedback`:
  <https://www.usb.org/sites/default/files/Audio2_with_Errata_and_ECN_through_Apr_2_2025.pdf>
- The Linux USB Audio driver treats explicit sync payloads as three- or
  four-byte Q10.14/Q16.16 values and scales feedback by the data endpoint's
  service interval:
  <https://github.com/torvalds/linux/blob/master/sound/usb/endpoint.c>

## Architecture

The existing ownership model remains unchanged: the AM++ broker obtains USB
permission, opens and configures the device, claims the AudioStreaming
interface, and lends a duplicated usbfs file descriptor to the injected Apple
Music process. The change extends the selected stream description with an
optional explicit-feedback endpoint and lets the existing native worker own
both the feedback IN and audio OUT URBs.

The data flow is:

1. `UsbAudioDescriptorParser` parses all endpoints in an AudioStreaming
   alternate setting and pairs a standards-compliant feedback IN endpoint with
   an asynchronous data OUT endpoint.
2. `UsbDirectDeviceBrokerService` selects the alternate setting and adds the
   feedback endpoint address, maximum packet size, and interval to the result
   bundle.
3. `UsbDirectDeviceClient.Lease` carries those fields to
   `UsbDirectUacBridge.nativeOpen`.
4. The native worker submits feedback IN URBs and audio OUT URBs on the same
   usbfs descriptor. Valid feedback updates a fixed-point clock; subsequent
   OUT packets use that clock to choose a complete PCM-frame count.
5. A feedback failure marks the direct session failed. The existing controller
   observes the failed write, closes the direct session, restores the original
   `AudioTrack` when appropriate, and permits AAudio/system fallback.

No Java thread processes live feedback, and no per-packet IPC is introduced.

## Descriptor Parsing and Selection

### Endpoint classification

The parser must retain these standard endpoint fields while it is inside one
non-zero-bandwidth AudioStreaming alternate setting:

- endpoint address;
- transfer type;
- synchronization type;
- usage type from `bmAttributes` bits 5..4;
- effective maximum packet size;
- `bInterval`;
- `bSynchAddress` when the standard descriptor is at least nine bytes.

An audio data endpoint is an isochronous OUT endpoint with `Usage Type = Data`.
An explicit UAC2 feedback endpoint is an isochronous IN endpoint with
`Usage Type = Feedback`. UAC1 predates the usage-type association used by
UAC2, so a UAC1 asynchronous data endpoint is paired with the isochronous IN
endpoint whose address exactly matches its non-zero `bSynchAddress`.

The parser must not pair endpoints from different interface numbers or
alternate settings. It must reject an ambiguous alternate setting rather than
guess when more than one endpoint satisfies the relevant feedback rule.

### Stream representation

`UsbAudioDescriptorParser.StreamingAlt` gains:

```kotlin
val feedbackEndpointAddress: Int
val feedbackMaxPacketSize: Int
val feedbackInterval: Int
```

Zero in all three fields means that the stream does not use explicit feedback.
`hasExplicitFeedback` is true only when the endpoint is IN, its effective
maximum packet size is either three or four bytes, and its interval is
positive.

### Selection policy

- Synchronous and adaptive data endpoints remain eligible without feedback.
- An asynchronous data endpoint is eligible only when
  `hasExplicitFeedback == true`.
- An implicit-feedback data endpoint remains ineligible.
- Format, channel, sample-rate, bit-resolution, and packet-size checks remain
  in force.
- If no candidate remains, the broker error must distinguish "asynchronous
  endpoint has no standard explicit feedback" from a true format mismatch and
  include the device name or device path for diagnosis.

## IPC and JNI Contract

`UsbDirectIpc` gains these bundle keys:

```kotlin
KEY_FEEDBACK_ENDPOINT_ADDRESS = "feedback_endpoint_address"
KEY_FEEDBACK_MAX_PACKET_SIZE = "feedback_max_packet_size"
KEY_FEEDBACK_INTERVAL = "feedback_interval"
```

The broker always writes the three fields. `UsbDirectDeviceClient.Lease`
always reads them, defaulting to zero for a non-feedback stream. The bridge
appends the same three integers to `nativeOpen` after the existing data
endpoint interval and before the PCM target-format fields.

The native open call accepts all-zero feedback fields for synchronous and
adaptive streams. If any feedback field is non-zero, all three must describe
a valid isochronous IN endpoint: address bit 7 set, maximum packet size three
or four, and positive interval. Partial or invalid feedback parameters fail
the open before any worker thread starts.

## Native Feedback Clock

### Isolated math component

A new header, `UsbFeedbackClock.h`, contains Android-independent fixed-point
helpers. `UsbDirectUac.cpp` owns usbfs I/O and calls these helpers; feedback
decoding and packet scheduling do not depend on JNI, Android, or mutable global
state.

The helper API represents samples per USB bus tick as unsigned Q16.16:

```cpp
bool decodeFeedbackQ16(const uint8_t* data, size_t length, uint32_t* result);
uint32_t nominalFeedbackQ16(int sampleRate, int busTicksPerSecond);
int serviceTicks(bool highOrSuperSpeed, int endpointInterval);
class PacketScheduler {
public:
    explicit PacketScheduler(uint32_t feedbackQ16);
    void updateFeedback(uint32_t feedbackQ16);
    int nextFrames(int ticksPerServiceInterval);
};
```

Decoding rules are based on actual payload length:

- three bytes: little-endian unsigned Q10.14, converted to Q16.16 by shifting
  left two bits;
- four bytes: little-endian unsigned Q16.16;
- every other length: invalid.

The format is not inferred only from UAC protocol because USB bus speed and
endpoint behavior determine whether the standard payload is three or four
bytes.

`busTicksPerSecond` is 1000 at full speed and 8000 at high, SuperSpeed, or
SuperSpeedPlus. The output endpoint's service-tick count is its literal
`bInterval` at full speed and `2^(bInterval - 1)` microframes at high or
SuperSpeed. The value is clamped to descriptor-valid ranges before shifting.

For every audio OUT packet, the scheduler performs:

```text
phaseQ16 += feedbackQ16 * ticksPerServiceInterval
frames = phaseQ16 >> 16
phaseQ16 &= 0xffff
```

This distributes fractional samples across packets without sending partial PCM
frames. Updating feedback preserves the fractional phase.

### Feedback validation

The worker starts with the nominal sample rate converted to Q16.16. A received
feedback value becomes active only when all checks pass:

- payload length is three or four bytes;
- decoded value is non-zero;
- decoded samples per second are between 75% and 125% of the requested sample
  rate;
- the resulting maximum complete frame count fits the audio endpoint's
  `wMaxPacketSize`.

The range is intentionally broad enough for normal oscillator correction but
prevents malformed feedback from requesting an unsafe packet size.

## Native URB Lifecycle

`IsoSlot` gains an explicit role so a reaped `usercontext` can be classified as
audio OUT or feedback IN. Audio slots retain four packets per URB and the
existing ring-buffer behavior. Two feedback slots each contain one
isochronous packet and a buffer sized to the feedback endpoint's maximum packet
size.

Worker startup order is:

1. initialize the nominal feedback clock;
2. submit both feedback IN URBs;
3. submit all audio OUT URBs using nominal packet sizes;
4. enter the shared reap loop.

On feedback completion, the worker validates and applies the payload, clears
the invalid-feedback counter, records the last-valid timestamp, and resubmits
that feedback URB. On audio completion, it fills and resubmits the audio slot
using the latest valid clock.

The direct session fails when any of these conditions occurs:

- feedback URB or its packet completes with an error;
- eight consecutive feedback payloads are invalid;
- no valid feedback arrives within two seconds of startup;
- more than two seconds elapse after the last valid feedback;
- feedback-driven audio packet bytes exceed the data endpoint maximum;
- an existing usbfs submit, reap, ring, or conversion failure occurs.

Shutdown sets `closing`, discards both audio and feedback URBs, wakes blocked
writers, joins the worker, and closes the duplicated descriptor. Expected URB
cancellation during shutdown must not replace the meaningful session error or
resume an intentionally paused `AudioTrack`.

## Diagnostics and UI

The USB settings description changes from "does not support asynchronous
feedback DACs" to "supports standard UAC1/UAC2 explicit feedback; implicit and
vendor-specific feedback still fall back." The live status continues to show
USB Direct only after PCM has actually been written to the claimed endpoint.

Failure messages must name the failing layer: descriptor pairing, invalid
feedback parameters, feedback timeout, malformed feedback, feedback rate
range, packet capacity, or usbfs URB failure. Descriptor-selection failures
include the USB device display name or device path so "device name not
reported" does not obscure the actual incompatibility.

## Testing

### Kotlin descriptor tests

`UsbAudioDescriptorParserTest` adds independent byte fixtures for:

- UAC1 asynchronous 48 kHz stereo OUT with a nine-byte data endpoint
  descriptor whose `bSynchAddress` matches a three-byte feedback IN endpoint;
- UAC2 asynchronous 48 kHz stereo OUT with a seven-byte data endpoint and a
  four-byte `Usage Type = Feedback` IN endpoint in the same alternate setting;
- missing UAC1 feedback endpoint;
- mismatched UAC1 `bSynchAddress`;
- UAC2 feedback endpoint in a different alternate setting;
- implicit-feedback usage remaining unsupported;
- preservation of existing adaptive and sample-rate selection behavior.

The production mutations these tests catch are incorrect endpoint direction,
incorrect usage-bit masking, pairing across alternate settings, ignoring
`bSynchAddress`, and unconditionally accepting asynchronous endpoints.

### Native compile-time tests

`UsbFeedbackClockCompileTest.cpp` uses `static_assert` with hand-derived
values to verify:

- `00 00 0c` decodes as 48 samples per full-speed frame in Q16.16;
- `00 00 06 00` decodes as 6 samples per high-speed microframe in Q16.16;
- invalid payload lengths are rejected;
- 48 kHz produces stable 48-frame full-speed packets and 6-frame high-speed
  packets at interval one;
- 44.1 kHz produces a deterministic mixture of adjacent packet sizes whose
  accumulated frame count matches the fixed-point input;
- service-interval scaling is correct for high-speed `bInterval > 1`;
- feedback updates preserve fractional phase.

### Regression and build verification

- Run the focused parser unit tests first.
- Build the native debug library so all compile-time assertions execute.
- Run the complete debug unit-test suite.
- Assemble the debug APK for ABI/JNI signature validation.

Hardware validation remains necessary after automated tests because usbfs
isochronous scheduling and DAC feedback cadence cannot be reproduced by local
JVM tests. On a standards-compliant explicit-feedback DAC, acceptance requires
five minutes of continuous playback at 44.1, 48, and 96 kHz without direct-path
fallback, packet-capacity errors, or audible underrun/overrun. Rates not
advertised by the DAC are excluded from hardware acceptance.

## Acceptance Criteria

- A matching UAC1 or UAC2 asynchronous alternate setting with a standard
  explicit feedback endpoint is selected instead of rejected.
- Asynchronous endpoints without unambiguous standard explicit feedback remain
  rejected before interface takeover.
- The feedback endpoint fields survive the Broker-to-JNI boundary unchanged.
- Three- and four-byte standard feedback values alter future audio OUT packet
  frame counts through the fixed-point scheduler.
- Malformed, stale, or unsafe feedback fails open to the existing fallback
  path without leaving the interface, worker, or `AudioTrack` owned.
- Existing synchronous/adaptive USB Direct and AAudio fallback tests continue
  to pass.
- Debug native build, complete unit tests, and debug APK assembly succeed.
