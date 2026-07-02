# karoo-phonestatus

A small [Hammerhead Karoo](https://www.hammerhead.io/) extension that displays
the **phone connection status** as an indicator near the top clock bar during a
ride.

Built on [`karoo-ext`](https://github.com/hammerheadnav/karoo-ext). Compatible
with Karoo 2 and Karoo 3.

> **Status: prototype.** The overlay renders and the app connects to the Karoo
> system and enumerates paired devices. Wiring the *live* per-device connection
> stream is the next step — see [Wiring live status](#wiring-live-status).

## How it works

The Karoo status/clock bar is owned by Karoo OS and has no injection API, so this
extension takes the same approach as [karoo-powerbar](https://github.com/timklge/karoo-powerbar):
it draws an Android `WindowManager` overlay (`TYPE_APPLICATION_OVERLAY`)
positioned at the top of the screen, and updates it from Karoo system events.

- `MainActivity` — launcher screen; requests the one-time "draw over other apps"
  permission, then starts the service.
- `PhoneStatusOverlayService` — foreground service that adds the overlay view and
  subscribes to Karoo events.

Indicator states: 📱 connected · 🔍 searching · 📵 disconnected · … unknown.

## Prerequisites

- Android Studio (Ladybug or newer)
- JDK 17
- A GitHub personal access token with the `read:packages` scope (GitHub Packages
  requires auth even for the public `karoo-ext` package).

## Setup

1. Clone and open in Android Studio (it will provision the Gradle wrapper on
   first sync).
2. Add your GitHub Packages credentials. Prefer your **global**
   `~/.gradle/gradle.properties` so they never get committed:

   ```properties
   gpr.user=your-github-username
   gpr.token=ghp_xxxxxxxxxxxxxxxxxxxx
   ```

   (Alternatively export `USERNAME` and `TOKEN` env vars — used as a fallback.)
3. Sync Gradle.

## Build

```bash
./gradlew assembleDebug
# APK -> app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/build.yml`) also builds a debug APK on every push using the
Actions `GITHUB_TOKEN`, and uploads it as an artifact.

## Install on the Karoo (sideloading)

**Karoo 3** — on your phone's browser, long-press the link to `app-debug.apk`
(from a GitHub release or the Actions artifact) and share it with the Hammerhead
Companion app; the Karoo shows an install prompt.

**Karoo 2** — enable ADB sideloading ([DC Rainmaker's guide](https://www.dcrainmaker.com/2021/03/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html))
and run `adb install app-debug.apk`.

Then open **Phone Status** from the Karoo app menu once, grant the overlay
permission, and the indicator appears on the ride screen.

## Wiring live status

The prototype currently derives state from `SavedDevices` (paired + enabled).
For true real-time state, subscribe to the phone device's `OnConnectionStatus`
(`DeviceEvent`, enum `CONNECTED` / `SEARCHING` / `DISCONNECTED` / `DISABLED`).

Steps:
1. Run the app on your Karoo and watch `adb logcat -s PhoneStatus`. Every paired
   device is logged with its `id`, `connectionType`, and `name`.
2. Set `PHONE_MATCH` in `PhoneStatusOverlayService` to match your phone/companion
   entry.
3. Subscribe to that device's `OnConnectionStatus` events and call
   `updateOverlay(...)`. The [karoo-powerbar](https://github.com/timklge/karoo-powerbar)
   and [Ki2](https://github.com/valterc/ki2) sources show the device consumer API.

## Position tuning

`x = 90`, `y = 6` with `Gravity.TOP or Gravity.END` in `addOverlayView()` is a
starting guess for sitting next to the clock. Adjust for your Karoo model.

## References

- [karoo-ext](https://github.com/hammerheadnav/karoo-ext) — the SDK
- [karoo-ext API docs](https://hammerheadnav.github.io/karoo-ext/)
- [awesome-karoo](https://github.com/timklge/awesome-karoo) — community extensions
- [Hammerhead Extensions FAQ](https://support.hammerhead.io/hc/en-us/articles/31150180125083-Hammerhead-Extensions)

## License

Apache 2.0. See [LICENSE](LICENSE).
