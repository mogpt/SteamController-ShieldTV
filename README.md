# Steam Controller 2 (2026) on Android TV and NVIDIA SHIELD TV

Use Valve's new **Steam Controller** — the 2026 model, also called the **Steam Controller 2**,
codename *Ibex* — as a **standard gamepad on Android TV**, with **no root required**.

Built and tested for the **NVIDIA SHIELD TV**. The controller speaks Valve's own protocol
rather than standard Bluetooth HID, so Android does not recognise it as a gamepad on its own:
pair it directly and nothing happens. This app reads that protocol and publishes a real
virtual gamepad through Linux `uinput`, so games see a genuine input device rather than
injected events.

**Works in:** GeForce NOW · Moonlight · Steam Link · RetroArch and other emulators · any
Android TV game with controller support.

**Connects over:** Bluetooth LE (direct, no dongle) or USB / the wireless Puck.

**Presents as:** Xbox 360 · Xbox One · DualShock 4 · DualSense · or a mouse + keyboard
(Desktop mode).

---

## Compatibility

| | |
|---|---|
| **Controller** | Steam Controller 2026 / Steam Controller 2 (Valve, *Ibex*) |
| **Tested on** | NVIDIA SHIELD TV 2019 (the "tube", `sif`), Android 11, `armeabi-v7a` |
| **Should work on** | Other Android TV devices, Android 8.0+ — untested |
| **Requires** | [Shizuku](https://shizuku.rikka.app/) (no root), or root |
| **Does not require** | An unlocked bootloader, a modified ROM, or a PC after setup |

Only the SHIELD TV tube has actually been tested. Everything else is plausible but unverified
— reports from other devices are welcome.

## Why this fork exists

This is a fork of [SonicDX12/SteamController-Android](https://github.com/SonicDX12/SteamController-Android),
which targets Android generally. This one targets **Android TV and the SHIELD specifically**,
and fixes a set of bugs that only show up on a real TV box under real use.

### Bugs fixed here

Each of these was found by debugging against actual hardware, not by reading the code:

- **The controller stopped working whenever you switched apps** (most visibly when launching
  GeForce NOW). Every ungraceful stop leaked a virtual gamepad, mouse and keyboard that stayed
  registered with Android until reboot. Five had accumulated on the test device. A game
  claiming "player 1" takes the lowest input device id — by then a dead one — so the pad did
  nothing in game while the app still reported itself connected. Stale devices are now reaped
  on startup, and the helper self-destructs when its client goes away.
- **"Ready" but no input at all.** A dropped Android GATT callback left the app subscribed
  only to the first notify characteristic, which carries 5-byte status packets rather than the
  45-byte state reports. The subscription chain now has a watchdog and always runs to
  completion.
- **Random disconnects that never recovered.** The app never attempted to reconnect — any BLE
  blip was terminal until you restarted the service by hand. Now retries with backoff, holds a
  wake lock, and asks for battery-optimisation exemption.
- **Silent failure to connect after re-pairing.** The controller's BLE address rotates, and the
  app stored a fixed address. It now matches on the device name, which carries the serial.
- **The trackpad mouse never worked**, because the button debounce froze the capacitive touch
  flags at whatever they were during the last button press.
- **No buttons at all in fallback mode** — the injection path compared a frame against itself
  and always produced an empty key list.
- **Freezes and ANRs when stopping**, from blocking binder calls on the main thread and the USB
  connection being closed mid-read.

### Added for Android TV

- **Gyro aiming.** The controller's IMU was parsed and then discarded upstream. It now drives
  the right stick, gated behind trackpad touch or a paddle, with adjustable sensitivity.
- **NVIDIA SHIELD theme**, and a layout that fits a 1080p TV screen without scrolling.
- **Visible DPAD focus**, so you can tell what is selected from across the room.
- **An Install Shizuku button** — Shizuku is a phone app with no leanback launcher, so it is
  invisible on the SHIELD home screen and otherwise impossible to reach with a remote.
- **A real report-rate readout** (81 Hz over BLE on the test device).
- Per-frame allocations removed from the input path, which matters on the 2 GB tube.

## Install

1. Install **Shizuku** and start it (the app offers a shortcut if it is missing).
2. Install the APK from [Releases](https://github.com/mogpt/SteamController-ShieldTV/releases).
3. Open the app, grant Shizuku permission, pick **Bluetooth** or **USB**, select your
   controller, press **Start Service**.

Releases here are signed with a different key than upstream, so the two cannot be installed
over each other — uninstall one first, exporting your settings from inside the app beforehand.

## Credits

All of the original work is by [SonicDX12](https://github.com/SonicDX12/SteamController-Android),
including the protocol implementation this depends on. This fork only adapts it to Android TV
and fixes what broke there.

---

## Features

### Connection
- **USB OTG** — wired or via the wireless Puck dongle
- **Bluetooth LE** — direct pairing with the controller, no dongle needed
- **Live transport switching** in the UI (toggle group with USB and Bluetooth icons)
- **Refresh paired BT devices** without restarting the app
- **In-app help dialog** explaining the controller's wireless mode combos (Steam+A+R1, Steam+B+R1, etc.)

### Emulation
- **Five virtual profiles**: Xbox 360 (default), Xbox One, Sony DualShock 4, Sony DualSense, and **Desktop** (mouse + keyboard)
- **Cycle gamepad profiles directly from the notification** (`↻ → next profile`) without opening the app
- **Real `InputDevice`** via Linux `uinput` (UID shell via Shizuku UserService) — recognised by games as a real gamepad, not filtered like injected events
- **Automatic fallback** to `IInputManager.injectInputEvent` if `/dev/uinput` is denied (less compatible, kept as safety net)

### Desktop mode
- Turns the controller into a virtual **mouse + keyboard** — right trackpad drives the cursor, left trackpad scrolls, buttons map to common keys (volume, play/pause, back, home, enter, escape, tab, space, etc.)
- Even while a gamepad profile is active, the trackpads can double as a mouse sidecar (toggle in Calibration) so you can still navigate menus without switching profiles
- Full **Android TV** support: dedicated banner/leanback UI, D-pad focus navigation, on-screen keyboard shows up correctly when a text field is focused

### Game Profiles
- Save the current calibration, button mapping, rumble intensity and mouse sensitivity as a **named preset**
- Load, rename, duplicate or delete presets from a dedicated screen
- **Bind a preset to one or more apps** — the service automatically switches profile when you launch a bound game (foreground-app detection, no manual step)

### Tuning
- **Per-stick calibration** — radial dead zone (0–30%), center offset capture, Y-axis inversion, live 2D preview
- **Custom button mapping** — categorised list (Face / Bumpers / Triggers full-press / Stick clicks / System / Back paddles / Grips), using the official Steam Input button icons. Any source button to any target, including back paddles L4/L5/R4/R5, the Quick Access Menu button, and forcing a trigger to "fully pulled"
- **Special actions** — map any button to **📸 Take screenshot** (saved in Pictures/Screenshots, visible immediately in the gallery)
- **Rumble forwarding pipeline** with adjustable intensity (0–100%) and a manual "Test rumble" button (Bluetooth only for now — see Known limitations)

### Backup & restore
- **Export** every live setting and all Game Profiles to a single JSON file via the system file picker
- **Import** that file back at any time — handy after reinstalling the app or moving to a new phone

### Auto-update
- Checks GitHub Releases for a newer version at launch (once every 24h) or on demand
- Shows the release notes and downloads the signed APK straight from GitHub, then hands off to the system installer

### Debug & status
- **HID debug view** — every button, stick, trigger, trackpad, IMU quaternion and raw hex dump, updated at the controller's ~300 Hz
- **Battery indicator** in the status card and in the notification — works over both USB and Bluetooth
- **Persistent foreground service notification** with the active emulation profile, battery, profile-cycle action, and stop action

### Platform
- **Material 3** design with Steam blue accents
- **Adaptive layouts** — phone (max-width 520dp), tablet (`sw600dp`, two-column layouts) and Android TV (`television`, leanback navigation)

## Requirements

- Android 8.0+ (API 26) — phone, tablet, or Android TV
- [Shizuku](https://shizuku.rikka.app/) installed and running
- For USB: USB Host (OTG) support on the phone/tablet
- For Bluetooth: standard BLE (available on every modern Android)
- A **Steam Controller 2026** (Valve Ibex). The older Steam Controller is not yet supported.

## Setup

1. Install [Shizuku](https://shizuku.rikka.app/) and start it (ADB Wireless on Android 11+, or one-time ADB cable for older versions).
2. Install this app and grant it the Shizuku permission when prompted.
3. Grant the **POST_NOTIFICATIONS** permission when asked (Android 13+) so the foreground status notification shows up.
4. **For USB**: plug the Puck (or the controller directly) into the OTG port. Android will ask for USB permission.
5. **For Bluetooth**: pair the controller via Android Settings → Bluetooth first, then select it from the Bluetooth device dropdown in the app. Use the `↻` refresh button if you just paired it.
6. Pick the emulated controller profile (Xbox 360 is the safest default for games — broadest compatibility. Pick Desktop for mouse + keyboard, e.g. on Android TV).
7. Hit **Start Service**. The status card shows `Mode: <profile> (uinput) ✓` when everything is up.
8. Optional: tune everything to your liking (calibration, mapping, rumble) and save it as a **Game Profile** — bind it to a game so it auto-loads next time you launch it.

If `uinput` is blocked by SELinux on your device (rare on stock Android, possible on some hardened ROMs), the app falls back to `injectInputEvent`, which works in most apps but is filtered by many games.

## How it works

```
Steam Controller (USB or BT)
         │
         ▼
HID report parser  (report 0x45 state, 53B USB / 45B BLE — plus a
                     dedicated 0x43 battery status report)
         │  validated against SteamlessController.h + hardware capture
         ▼
ControllerService
   • debounce (15-bit injectable mask, 3 frames)
   • baseline state (ignore buttons held at startup)
   • mapping (Steam buttons → Xbox buttons or special actions)
   • per-stick calibration
   • rumble intensity scaling
   • foreground-app polling → Game Profile auto-switch
         │
         ▼
UInputGamepad → AIDL/Binder → UInputService (UID shell via Shizuku)
                                       │
                                       ▼
                                JNI uinput_jni.cpp
                                       │
                          ┌────────────┴────────────┐
                          ▼                          ▼
                  gamepad device            mouse+keyboard sidecar
                (Xbox/DS4/DualSense)      (Desktop mode, or trackpad-
                                            as-mouse alongside a gamepad)
                          │                          │
                          └────────────┬─────────────┘
                                       ▼
                              /dev/uinput → kernel
                                       │
                                       ▼
                        Android sees a real "Microsoft
                       X-Box 360 pad" (or DS4, mouse, etc.)
```

The HID protocol is parsed natively and translated into the chosen profile's button/axis layout before being written to a virtual gamepad created via Linux `uinput`. The Shizuku `UserService` runs as the `shell` user (UID 2000) which has access to `/dev/uinput` on most Android builds.

For BLE, the standard HID service (`0x1812`) is claimed by the OS, so the app uses Valve's vendor service (`100f6c32-1735-4313-b402-38567131e5f3`) directly. Connection priority is bumped to `HIGH` after connect to bring the BLE interval from ~50 ms down to ~11 ms.

## Build

Standard Android Gradle build, requires:

- Android Studio Hedgehog or newer
- Android Gradle Plugin 8.5+
- Kotlin 2.0+
- NDK + CMake 3.22.1 (for the native `uinput` JNI library)

**First time:** open the project in Android Studio and let it sync — this regenerates the Gradle wrapper. After that:

```bash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

The app supports `arm64-v8a`, `armeabi-v7a` and `x86_64` ABIs.

For signed release builds and publishing to GitHub Releases, see [RELEASING.md](RELEASING.md).

## Configuration

Live settings and Game Profiles are persisted in `SharedPreferences`:

- Selected transport (USB or Bluetooth) and paired BT device address
- Emulated profile (Xbox 360 / Xbox One / DS4 / DualSense / Desktop)
- Per-stick calibration (dead zone, center offset, invert Y)
- Per-button mapping (source buttons → Xbox targets, keyboard keys, or special actions like screenshot)
- Rumble intensity (0–100%) and mouse sensitivity (Desktop mode / trackpad sidecar)
- Named Game Profiles, each with its own copy of the settings above plus the list of apps it auto-switches on

You can tweak everything live — most changes apply within ~250 ms (next mapping cache refresh) without restarting the service. Changing transport or emulated profile requires restarting the service (or use the notification's profile cycle action). Use **Export backup** / **Import backup** in the Game Profiles screen to move all of this to a JSON file — useful before uninstalling the app or when setting it up on a new device.

## Known limitations

- **`shell` UID access to `/dev/uinput`** depends on the device's SELinux policy. Most stock Android builds allow it; some hardened ROMs may not. The app falls back to `injectInputEvent` automatically in that case.
- **Steam button** passes through as `KEYCODE_BUTTON_MODE`. Android handles it as the system "Guide" key which may open the launcher in some setups.
- **Rumble byte format** is an empirically-tuned best guess based on the Linux `hid-steam` driver. Works over Bluetooth. **USB rumble is not implemented yet** — the controller only vibrates when connected over BT.
- **Trackpads**: usable as a mouse (Desktop mode, or as an optional sidecar cursor alongside a gamepad profile). Not yet exposed as a DualShock 4/DualSense touchpad to games that support one natively.
- **Gyroscope** (quaternion IMU) is parsed but not yet routed anywhere. Gyro aiming is planned for a future release, fits best with the DualShock 4 / DualSense profiles.
- **Bluetooth auto-reconnect**: if the controller powers off, the app does not retry the GATT connection.
- **Shizuku at reboot**: the user must restart Shizuku after each reboot of the device (an Android limitation, not the app's).

## Roadmap

- USB rumble implementation
- Trackpad as a real touchpad input (DS4/DualSense profile) for games that support it
- Gyro aiming for DS4 / DualSense profiles
- USB / BT auto-reconnect
- HID debug log export ("Log to File")

## Credits

The HID protocol reverse engineering credit goes to:

- [**SteamlessController**](https://github.com/ddeverill/SteamlessController) by ddeverill — the definitive `SteamController.h` byte layout reference for the SC2026
- The [**Linux kernel `hid-steam` driver**](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-steam.c) for additional validation of button bit positions and the rumble command structure

Other key dependencies:

- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps — the `uinput` access path without root
- [Android USB Host API](https://developer.android.com/guide/topics/connectivity/usb/host) and the BLE GATT stack
- [Material Components for Android](https://github.com/material-components/material-components-android) for the Material 3 UI

## License

MIT — see [LICENSE](LICENSE).
