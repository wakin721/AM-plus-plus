# Device QA scripts

These scripts are optional device regressions used while adapting Apple Music 6.5.0. They are not part of the Gradle build.

## USB Direct lifecycle verification (no device required)

`verify-usb-direct-lifecycle.ps1` compiles the current USB Direct controller and IPC client
with deterministic Android/Binder/native boundary fixtures. It exercises stale responses,
pause prefill/backpressure, explicit flush, and cancellation during native open, together
with the existing USB policy/descriptor/source-contract JUnit tests. Fixtures are isolated
under `scripts/tests/usb-direct` and are not packaged in the Android app.

```powershell
.\scripts\verify-usb-direct-lifecycle.ps1 -JavaHome "C:\path\to\jdk"
```

Requires PowerShell, Java 17+, and Gradle-cached Kotlin compiler/stdlib/script-runtime 2.3.10,
reflect 2.2.10, coroutines 1.8.0, annotations 13.0, JUnit 4.13.2, and Hamcrest 1.3. The script
does not download dependencies. Boundary fixtures do not establish real USB hardware compatibility.

`verify-usb-direct-native.ps1` compiles the production C++ engine into a ring-buffer test:
pause emits silence without consuming PCM, resume preserves PCM order, full buffers apply
backpressure, and explicit flush clears the ring. Run it in a Visual Studio Developer
PowerShell with MSVC and a JDK:

```powershell
.\scripts\verify-usb-direct-native.ps1 -JavaHome "C:\path\to\jdk"
```

This test substitutes Linux syscall declarations on Windows and disables the USB worker;
it verifies the actual native ring functions but does not issue USB ioctls or validate URB I/O.

## Requirements

- PowerShell 7 and ADB
- An unlocked device with Apple Music and the module installed
- `ANDROID_SERIAL` set, or the corresponding `-Serial` / `-Device` argument supplied
- Root access for scripts that record through `/data/local/tmp`
- Python with OpenCV (`cv2`) and NumPy for the image/video analyzers

Several liquid-glass checks use fixed coordinates or 1080 × 2376 regions from the reference phone. Review and adjust those values before running on another resolution. The tablet checks expect Apple Music to be open in landscape.

Example:

```powershell
$env:ANDROID_SERIAL = "your-device-serial"
.\scripts\verify-device-dual-pane.ps1
```

## Host profile verification (no device required)

`verify-host-profile.py` is a read-only DEX check of the exact host profile. It takes the original
XAPK (or a bare base APK) and verifies every class/method/field the AM++ version profile pins,
including the phone liquid-glass seams with `--glass`:

```powershell
python scripts\verify-host-profile.py "apple-music-6-5-3.xapk" --version-name 6.5.3 --version-code 1599 --glass
python scripts\verify-host-profile.py "Apple+Music_6.5.2_APKPure.xapk" --glass
```

The version tuple comes from `manifest.json` when the XAPK ships one; otherwise pass it explicitly.
A PASS means the static evidence behind a profile still holds for that package; it does not prove
runtime behaviour, resource IDs, container types or blur sampling.

Besides the pinned classes, methods and fields, the script asserts the two seams whose names R8
reuses between builds: the direct catalog query (only one method may satisfy the
`(String, Map, Continuation)` shape, and it must be the verified name for that version) and the
obfuscated `androidx.lifecycle.LiveData` alias that `COMPOSE_OBSERVE_AS_STATE` accepts.
