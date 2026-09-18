# USB Direct Single-Owner Design

Date: 2026-09-18  
Branch: `fix/usb-direct-single-owner`  
Base: `feat/usb-direct-buffer-settings`

## Problem

The current USB Direct path splits one USB streaming session across two process lifecycles:

- the AM++ process opens the USB device, force-claims interfaces, selects the alternate setting, configures sample rate, and retains the `UsbDeviceConnection`;
- the injected Apple Music process receives a duplicated usbfs file descriptor and owns the native isochronous URB worker.

This creates a dangerous ownership boundary. The broker may release interfaces or close its connection while Apple Music still has submitted or reapable URBs on the duplicated usbfs fd. Opening AM++ while USB Direct is active can change the broker process/service lifecycle and is correlated with a device hang/reboot.

Linux usbfs documents that interface claims belong to a file descriptor and are released by `USBDEVFS_RELEASEINTERFACE` or closing that fd. It also warns that releasing an interface is not authenticated against the task that made the claim. For periodic isochronous I/O, outstanding URBs must be stopped before the interface/file descriptor is torn down.

## Decision

Use a single streaming owner.

The AM++ broker becomes a permission-and-descriptor broker only. The injected Apple Music native session becomes the sole owner of the USB streaming lifecycle from interface claim through final release.

### Ownership after the change

AM++ process:

1. verifies caller UID;
2. finds an authorized USB Audio device;
3. calls `UsbManager.openDevice()`;
4. reads raw descriptors;
5. selects a compatible AudioStreaming alternate setting;
6. duplicates the device fd into a `ParcelFileDescriptor`;
7. returns the fd plus immutable stream metadata;
8. closes its own `UsbDeviceConnection` immediately after the reply has been sent;
9. retains no claimed interface and no active streaming session.

Apple Music native process:

1. duplicates the received fd into a native session-owned fd;
2. claims the required interfaces;
3. selects the AudioStreaming alternate setting;
4. configures the UAC sample rate;
5. creates/submits feedback and audio ISO URBs;
6. performs the streaming loop;
7. discards all submitted URBs;
8. joins the worker;
9. releases interfaces in reverse claim order;
10. closes the native fd.

No AM++ Activity, Service bind/unbind, or settings-process lifecycle event is allowed to release a USB interface that the Apple Music native worker is using.

## Broker responsibilities

`UsbDirectDeviceBrokerService` keeps descriptor parsing and stream selection because those are pure metadata decisions and already have tests.

It must stop doing all of the following:

- `connection.claimInterface(..., true)`
- `connection.setInterface(...)`
- UAC1 endpoint sample-rate `controlTransfer`
- UAC2 clock-source sample-rate `controlTransfer`
- retaining `ClaimedSession`
- releasing interfaces from `onUnbind()`
- releasing interfaces from `onDestroy()`

After creating the outgoing `ParcelFileDescriptor`, the broker closes only its own Java connection after the Binder reply has been sent. The receiver's duplicated fd remains valid.

`WHAT_RELEASE` becomes a compatibility acknowledgement rather than a USB teardown command. It may remain temporarily so older injected clients do not fail protocol negotiation, but it must not release an interface or close another process's active stream.

## IPC contract changes

The current lease already carries:

- streaming interface number;
- alternate setting;
- audio OUT endpoint and packet parameters;
- feedback endpoint parameters;
- subslot/bit resolution;
- UAC protocol.

Native sample-rate configuration also needs metadata that currently stays inside the broker.

Add:

- `audioControlInterfaceNumber`
- `clockSourceId`

Rules:

- UAC1: `audioControlInterfaceNumber = -1`, `clockSourceId = 0`
- UAC2: both values come from the selected parsed descriptor
- invalid UAC2 metadata causes acquisition to fail before a lease is returned

These fields pass through:

`UsbDirectIpc -> Broker Bundle -> UsbDirectDeviceClient.Lease -> UsbDirectUacBridge.nativeOpen() -> UsbDirectUac.cpp`

## Native interface claim

The native session claims interfaces before any `SETINTERFACE`, control transfer, or URB submission.

Claim order:

1. UAC2 AudioControl interface, when present;
2. AudioStreaming interface, unless it is the same interface number.

Release order is the reverse.

The native implementation must preserve the current `claimInterface(force=true)` behavior as closely as usbfs permits:

1. try `USBDEVFS_CLAIMINTERFACE`;
2. if the interface is busy, use the kernel-supported disconnect/claim mechanism when available;
3. if force-claim is unavailable or fails, fail open and return control to Android audio;
4. do not use `USBDEVFS_RESET` or `USBDEVFS_SETCONFIGURATION` as a fallback.

The goal is to avoid destabilizing kernel/device state merely to obtain ownership.

## Alternate setting

After claims succeed, native sends `USBDEVFS_SETINTERFACE` for the selected streaming interface and alternate setting.

Failure aborts native open before the worker starts.

## Sample-rate configuration

Move the existing behavior, unchanged in semantics, into native usbfs control transfers.

### UAC1

Use `USBDEVFS_CONTROL` with:

- direction: OUT;
- type: CLASS;
- recipient: ENDPOINT;
- request: `SET_CUR (0x01)`;
- value: `SAMPLING_FREQ_CONTROL << 8`;
- index: streaming endpoint address;
- payload: 24-bit little-endian sample rate;
- timeout: 1000 ms.

Preserve the existing compatibility rule: if the endpoint advertises exactly one supported sample rate equal to the requested rate, failure of the explicit SET_CUR may be accepted.

To preserve that rule, the lease must also carry a boolean indicating that the selected alternate has a single advertised sample rate matching the requested rate, or an equivalent native-safe flag.

### UAC2

Mirror `UsbUacSampleRateControl.configureUac2` exactly:

1. GET_CUR the clock-source frequency;
2. return success immediately if it already equals the requested rate;
3. otherwise SET_CUR the requested 32-bit little-endian frequency;
4. if the initial GET_CUR succeeded, GET_CUR again and verify;
5. if the initial GET_CUR was unsupported but SET_CUR succeeded, accept success.

Request addressing remains:

`wIndex = (clockSourceId << 8) | audioControlInterfaceNumber`.

## Native session state

Extend `Session` with ownership state sufficient for deterministic cleanup:

- claimed control-interface flag/number;
- claimed streaming-interface flag/number;
- whether the alternate setting was activated;
- whether the worker was started;
- existing fd, URB slots, ring state, failure/closing flags.

Cleanup must be idempotent.

A helper should perform teardown in this order:

1. set `closing=true` and `running=false`;
2. issue `USBDEVFS_DISCARDURB` for every submitted slot;
3. wake ring waiters;
4. join the worker if started;
5. release the streaming interface if claimed;
6. release the control interface if separately claimed;
7. close the native fd;
8. clear ownership flags.

The worker may also issue DISCARD during its exit path, but duplicate discard errors must be ignored. Interface release cannot happen until after the worker has stopped.

## Failure behavior

All setup failures remain fail-open.

If native open fails during claim, set-interface, sample-rate configuration, or URB setup:

- release any interfaces already claimed;
- close the fd;
- return handle 0;
- preserve a specific `lastError`;
- let `UsbDirectUacController` resume the original AudioTrack.

If streaming fails after takeover:

- stop/discard/join native URBs first;
- release native interface ownership;
- only then notify/release the broker compatibility binding;
- resume the original AudioTrack.

The broker must never attempt device teardown as part of this recovery.

## Service lifecycle

`UsbDirectDeviceBrokerService` no longer owns a streaming session.

Therefore:

- `onUnbind()` must not touch USB interface state;
- `onDestroy()` must not touch USB interface state;
- opening or closing the AM++ UI must have no effect on an active Apple Music USB Direct stream.

Moving the broker to a dedicated process is not required by this design because the dangerous resource is no longer retained by the broker. It can be considered separately if future diagnostics show main-process contention, but it is intentionally out of scope.

## Buffer settings

The buffer-settings work remains unchanged:

- PCM presets: 50 / 100 / 250 / 500 / 1000 ms;
- transfer presets: Auto / 2 / 4 / 8 / 16 ms;
- 4 ISO packets per URB;
- explicit feedback scheduling remains unchanged.

The ownership fix must not change packet pacing or feedback math.

## Tests

### Structural/JVM tests

Add or update tests to assert:

- Broker source contains no `claimInterface`, `setInterface`, or sample-rate configuration call.
- Broker has no `ClaimedSession` or release-on-unbind path.
- Broker includes and sends the new UAC metadata.
- Client lease and JNI bridge carry the new metadata.
- Native source contains `USBDEVFS_CLAIMINTERFACE`, `USBDEVFS_SETINTERFACE`, `USBDEVFS_RELEASEINTERFACE`, and `USBDEVFS_CONTROL`.
- Native teardown orders worker join before interface release.
- Existing fail-open assertions remain true.

### Native compile-time/unit helpers

Extract small pure helpers where practical for:

- building UAC1 24-bit sample-rate payload;
- building UAC2 32-bit payload;
- UAC2 `wIndex`;
- deciding UAC1 fixed-rate fallback;
- release-order policy if represented independently.

### CI verification

The final branch must pass the repository's existing command:

`./gradlew test lintVitalRelease assembleDebug --no-daemon`

This proves Kotlin/JVM tests, release lint, CMake/native compilation and debug APK linkage.

## Device verification

CI cannot prove absence of a kernel/host-controller hang. Before merging, test on the affected phone with this sequence:

1. reboot phone;
2. connect DAC;
3. enable USB Direct;
4. start Apple Music playback and confirm Direct Active;
5. open AM++ main settings repeatedly;
6. open/close the USB audio settings page repeatedly;
7. leave playback running while backgrounding/foregrounding both apps;
8. stop playback;
9. restart playback;
10. repeat with transfer buffer Auto first, then 8 ms, then 16 ms.

Success criteria:

- no UI freeze;
- no audio-worker deadlock;
- no device reboot;
- opening AM++ does not interrupt active direct audio;
- stopping/restarting playback does not leave the interface permanently busy;
- fallback to Android AudioTrack still works after an induced USB Direct failure.

If the phone still reboots after the single-owner change, capture `bootreason`, `bootstat`, and pstore/ramoops where available before attempting further architectural changes.

## Out of scope

- changing the USB feedback algorithm;
- changing PCM conversion/gain behavior;
- adding a dedicated broker process;
- adding kernel-driver-specific workarounds;
- adding device-specific UAC quirks before logs demonstrate a need.
