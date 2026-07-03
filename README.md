# karoo-phonestatus

A small [Hammerhead Karoo](https://www.hammerhead.io/) extension that displays
the **phone connection status** as an indicator near the top clock bar during a
ride.

Built on [`karoo-ext`](https://github.com/hammerheadnav/karoo-ext). Compatible
with Karoo 2 (Android 8) and Karoo 3 (Android 12).

> **Status: working on Karoo 3.** Validated on a Karoo 3 (ROM 1.638.x): the
> overlay renders next to the clock and reflects the live companion-phone
> connection state pushed by the system. The green "connected" state shares the
> exact same decode path as the other states, differing only in the enum value.

## How it works

Two parts, and both need a workaround because Karoo OS has no direct API:

**The indicator** — the Karoo status/clock bar is owned by Karoo OS and has no
injection API, so this extension takes the same approach as karoo-powerbar: it
draws an Android `WindowManager` overlay (`TYPE_APPLICATION_OVERLAY`) at the
top of the screen.

**The signal** — getting the phone state is the hard part. Two dead ends,
confirmed on-device:

- **karoo-ext** does *not* expose the companion-phone link. `SavedDevices` /
  `OnConnectionStatus` cover paired *sensors* (HR, power, radar…) only — no
  event or data type for the phone (verified against karoo-ext 1.1.9 sources).
- **The Android Bluetooth stack** doesn't see it either. On Karoo 3 the phone
  link runs on the device's nRF radio, not the standard Bluetooth adapter —
  `dumpsys bluetooth_manager` reads `state: OFF` while the phone is paired and
  connected. So `BluetoothManager.getConnectedDevices(...)` never lists it.

The real source of truth is the system service **`io.hammerhead.phoneservice`**
— the same one that drives the status-bar phone icon. It is `exported=true`
with no permission gate, so any app can bind it. This extension reproduces the
single subscription it needs — the connection-status stream — reverse-engineered
from the phoneservice / systemui binaries. See
[`PhoneServiceClient.kt`](app/src/main/java/com/example/karoophonestatus/PhoneServiceClient.kt)
and [Re-deriving the phoneservice protocol](#re-deriving-the-phoneservice-protocol).

The indicator is designed to be indistinguishable from a native status icon —
it **mirrors the real status bar** rather than guessing per-screen:

- It **appears only when the phone is connected** (like a native connectivity
  icon).
- It is **pinned just left of the left-most icon** in the status bar's right
  cluster, and follows it: when the wifi icon disappears (as during a ride) the
  indicator slides right to sit next to whatever is now left-most.
- It **hides whenever the status bar itself is hidden**, and shows when it
  returns — so it matches the bar on every screen without a hard-coded list.
- It is **tinted like the other status icons**: white off-ride, black in-ride.

To do that it combines three signals:

- **Connection** — from `phoneservice` (above).
- **Ride state** — from karoo-ext `KarooSystemService` (`RideState`), which
  selects the black/white tint.
- **The live top status row** — an accessibility service reads whatever status
  row is at the very top of the screen (its visibility and the on-screen bounds
  of its icons). It is window-agnostic on purpose: off-ride that row is the
  systemui status bar; during a ride Karoo hides that bar and the ride app draws
  its own top row (battery + clock, no wifi); on the ride-upload/delete screens
  there is no top row at all. Reading "the top row, whoever draws it" makes the
  indicator behave right in every case. Accessibility is the only third-party
  API that exposes another app's on-screen layout.

Components:

- `MainActivity` — one-time setup: requests "draw over other apps" and prompts
  to enable the accessibility service, then starts the overlay. (No Bluetooth.)
- `PhoneServiceClient` — binds `phoneservice`, subscribes to the
  `PhoneConnectionStatus` stream, decodes the `RadioStatus`.
- `StatusBarMonitorService` — accessibility service; reads the top status row
  (systemui bar or the ride app's header) and publishes visibility + the
  left-most right-cluster icon's position via `StatusBarState`. Read-only.
- `PhoneStatusOverlayService` — foreground service that draws the indicator and
  positions/shows/tints it from connection + ride + status-bar state.
- `PhoneStatusExtension` — karoo-ext registration; Karoo OS binds it at boot,
  which restarts the overlay automatically after a reboot.

## Prerequisites

- JDK 17 (CI uses Temurin 17; locally e.g. `brew install openjdk@17`)
- Android SDK with platform 34
- A GitHub personal access token with the `read:packages` scope (GitHub
  Packages requires auth even for the public `karoo-ext` package).

## Setup

1. Add your GitHub Packages token to your **global**
   `~/.gradle/gradle.properties` so it never gets committed:

   ```properties
   gpr.token=ghp_xxxxxxxxxxxxxxxxxxxx
   ```

   Only the token is validated by GitHub Packages; `gpr.user` is optional.
   (Alternatively export `USERNAME` / `TOKEN` env vars — used as a fallback.)
2. Build from the CLI or open in Android Studio and sync.

## Build

```bash
./gradlew assembleDebug
# APK -> app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/build.yml`) also builds a debug APK on every push using
the Actions `GITHUB_TOKEN`, and uploads it as an artifact.

## Install on the Karoo (sideloading)

**Karoo 3** — on your phone's browser, long-press the link to `app-debug.apk`
(from a GitHub release or the Actions artifact) and share it with the Hammerhead
Companion app; the Karoo shows an install prompt.

**Karoo 2** — enable ADB sideloading ([DC Rainmaker's guide](https://www.dcrainmaker.com/2021/03/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html))
and run `adb install app-debug.apk`.

Then open **Phone Status** from the Karoo app menu once and grant what it asks
for — "Display over other apps", and enabling the **Phone Status accessibility
service** (Settings → Accessibility). The accessibility service is what lets the
indicator sit next to the other status icons and mirror the bar; it only reads
the status bar and performs no actions. After setup the indicator appears in the
status bar whenever the phone is connected and the status bar is showing, and it
restarts automatically on boot.

## Verifying on-device

```bash
adb logcat -s PhoneStatus
```

You should see `status-bar monitor connected`, then `render …` lines showing
`connected`, `barShown`, and `anchorLeft` (the x of the left-most native icon).
Turn wifi off and `anchorLeft` jumps right as the indicator follows the battery
icon; disconnect the phone and it hides.

## Position tuning

Position is derived at runtime from the real status bar (see
`StatusBarMonitorService` / `PhoneStatusOverlayService`), so it needs no
per-screen coordinates. Two knobs in `PhoneStatusOverlayService`:

- `GAP_DP` — space between the indicator and the left-most native icon.
- `iconHeightPx` (in `addOverlayView`) — the icon height; it is matched to the
  native status icons (~26px in the 45px bar on a Karoo 3, 480×800 @ 300dpi).

The native icon bounds reported by accessibility include each icon's own
(variable) internal padding, so `GAP_DP` is a small compromise value that keeps
a little air on both the wide-padded wifi anchor and the zero-padded battery %
anchor.

## Re-deriving the phoneservice protocol

The connection state is read from `io.hammerhead.phoneservice` over its private,
R8-obfuscated Rx-over-AIDL interface. The transaction codes in
`PhoneServiceClient.kt` are **not a stable API** — they are assigned by the
phoneservice build and are correct for the ROM this was developed against
(Karoo 3, 1.638.x). If a Karoo OS update changes them the overlay degrades to
gray (UNKNOWN) instead of crashing.

To re-derive them, pull and decompile the system service:

```bash
adb pull $(adb shell pm path io.hammerhead.phoneservice | sed 's/package://') phoneservice.apk
jadx -d out phoneservice.apk
```

Then in the decompiled sources:
- Find the AIDL stub (`enforceInterface("…PhoneServiceControllerAIDL")` in its
  `onTransact`). Each `case N:` that reads a `String clientId` + a strong
  binder listener is a subscription; the one whose emitted type is
  `PhoneConnectionStatus` is the one to use (subscribe code, and `code+1` to
  dispose).
- The callback wire format (`io.hammerhead.aidlrx.IParcelableListener`,
  `onNext = txn 1`: `String, String, byte[], boolean`) has been stable; the
  `byte[]` is a marshalled `PhoneConnectionStatus` whose first field is
  `radioStatus.name()`.

## References

- [karoo-ext](https://github.com/hammerheadnav/karoo-ext) — the SDK
- [karoo-ext API docs](https://hammerheadnav.github.io/karoo-ext/)
- [karoo-powerbar](https://github.com/timklge/karoo-powerbar) — overlay + extension patterns this repo follows
- [awesome-karoo](https://github.com/timklge/awesome-karoo) — community extensions
- [Hammerhead Extensions FAQ](https://support.hammerhead.io/hc/en-us/articles/31150180125083-Hammerhead-Extensions)

## License

Apache 2.0. See [LICENSE](LICENSE).
