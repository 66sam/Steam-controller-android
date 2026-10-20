# SC Mapper: original Steam Controller on Android

SC Mapper lets you use the **original (2015) Steam Controller** on Android with Steam-style remapping. It can turn the
controller into an Xbox-style gamepad, a keyboard and mouse, or a mix of both. You build your own profiles, and you can
switch between them from the controller.

## Features

- **Connections:** USB cable, the **Steam Controller wireless receiver** (dongle, up to 4 controllers) through USB OTG,
  and **Bluetooth LE** (needs Valve's BLE firmware).
- **Real virtual devices.** Android and games see a hardware **Xbox controller**, keyboard and mouse. They are created
  through the kernel's `uhid` interface, so games that only accept real controllers work too.
- **Every input can be remapped:** A/B/X/Y, bumpers, Back/Start, the Steam button, **both back paddles**, the stick
  click, analog triggers (soft-pull and full-pull click actions), the stick, both trackpads and the gyro.
- **Trackpad modes**, separately for each pad (the stick uses a subset):
  - Analog stick (left or right), with deadzone, sensitivity and optional click-to-activate
  - Directional zones: a 4- or 8-way D-pad, **ABXY**, WASD, arrow keys, or any actions you pick, plus a center zone
  - **Touch-zone grid** (1×1 up to 4×4): each region of the pad is its own button
  - Mouse / trackball with momentum and friction, and haptic ticks like on PC
  - Circular scroll wheel
  - Pad click action, rotation and per-pad haptics
- **Button extras:** a separate long-press action, and turbo.
- **Gyro:** to the mouse, or to a joystick for gamepad-only games. Activation can be always on, while held, ratchet
  (off while held) or toggle. You can set sensitivity per axis, yaw or roll steering, inversion, an anti-deadzone and
  small-movement filtering. Calibration is automatic, or manual with Steam + X.
- **Haptics:** feedback when you move between zones, mouse ticks, scroll clicks and shortcut confirmations. Game rumble
  is forwarded to the trackpad actuators, the same way Steam does it.
- **Profiles:** create, copy, rename, delete, import and export them as JSON.
- **Switching from the controller:** shortcuts, plus an on-screen menu drawn over the running game.

### Built-in presets

| Preset | What it does |
|---|---|
| **Gamepad** | Xbox layout. The right pad is the right stick (click = R3), the left pad is a click D-pad and the paddles are L3/R3 |
| **FPS · Gamepad + gyro aim** | Same layout, with gyro on the right stick. Hold the right paddle to recenter (ratchet) |
| **Keyboard & Mouse** | Right pad = trackball mouse, left pad = scroll wheel, triggers = clicks, stick = arrow keys, B = Android Back, Steam = Home, paddles = volume |
| **FPS · Keyboard & Mouse + gyro** | WASD stick, mouse on the right pad, gyro mouse while you touch the right pad, triggers = fire / aim |
| **Retro · Pads as D-pad + ABXY** | The left pad is a touch D-pad, the right pad is a click ABXY pad |

### Controller shortcuts

| Shortcut | Action |
|---|---|
| Steam + RB / LB | Next / previous profile |
| Steam + ◀ or ▶ | Profile menu (navigate with the stick or left pad, A selects, B closes) |
| Steam + Y | Gyro on / off |
| Steam + X | Calibrate the gyro (hold the controller still for about 1 s) |
| Hold Steam for 3 s | Turn the controller off |
| Tap Steam | The Steam button's own binding (Guide by default) |

## Requirements

- Android 8.0 or newer.
- **[Shizuku](https://shizuku.rikka.app/)**, running in Wireless debugging mode (no root needed) or root mode.
  Android doesn't let normal apps create input devices. Shizuku runs a small helper with `adb shell` rights, and that
  helper can use `/dev/uhid`. With Wireless debugging you need to start Shizuku again after each reboot.
- For USB: a USB OTG adapter or a USB-C hub.
- For Bluetooth: the controller must be running Valve's **BLE firmware**. You install it once from Steam on a PC
  (Big Picture → Settings → Controller → your Steam Controller → firmware).

## Setup

1. Install the APK. You can get it from the **Actions** tab of this repository (artifact `scmapper-apk`) or build it
   yourself (see below).
2. Install and start Shizuku, open SC Mapper and tap **Grant Shizuku permission**.
3. **USB / wireless receiver:** plug it in and accept the prompt.
4. **Bluetooth:** hold **Y** while pressing the **Steam** button to enter pairing mode, then pair the controller in
   Android's Bluetooth settings. In SC Mapper, tap **Add Bluetooth** and pick the controller. The app remembers it and
   reconnects automatically.
5. Optional: allow **Display over other apps**, which the on-controller profile menu needs.

### Virtual controller type (Settings)

- **Xbox Wireless Controller (default):** presents itself as an Xbox One S Bluetooth pad. The Linux `hid-microsoft`
  driver attaches to it, so games can **rumble**, and SC Mapper plays that rumble on the trackpads.
- **Xbox 360 Controller:** a fallback with the most common Android key layout. It has no rumble.

## How it works

```
Steam Controller ──USB (UsbManager) / BLE (GATT, Valve service)──▶ SteamProtocol parser
      ▲ haptics / config (feature reports)                              │
      │                                                                 ▼
   Mapper (one per controller, engine thread, profile-driven) ──▶ OutputHub ──Binder──▶
   UhidService (Shizuku process, shell uid) ──/dev/uhid──▶ kernel input devices ──▶ Android / games
```

- `controller/`: the USB and BLE transports and the wire protocol. Lizard mode is turned off, then the IMU and LED are
  configured, and haptics are sent as feature reports. The protocol follows Valve's code that ships in SDL.
- `mapping/`: the profile model and JSON format, the presets, and `Mapper`, which handles buttons, pads, gyro,
  shortcuts and haptics.
- `output/`: HID descriptors, the uhid helper that runs under Shizuku, and the output hub, which sends only changed
  reports.
- `service/`: the foreground service and the overlay menu. `ui/`: the screens, built from framework Views only, so
  the app has no library dependencies apart from Shizuku.

## Building

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # protocol and mapping unit tests
```

CI (`.github/workflows/build.yml`) runs the tests and builds debug and release APKs on every push.

## Status and known limitations

This build hasn't been tested on real hardware yet. The protocol handling is based on Valve's SDL code and the
sc-controller project. Things that may need adjusting after a first test:

- **Gyro direction:** gyro axis signs differ between descriptions of the protocol. If aiming moves the wrong way, use
  the *Invert* switches in the gyro settings.
- **BLE packet format:** some Android Bluetooth stacks add a report ID byte and some don't. The parser handles both.
- **Rumble** depends on the kernel's `hid-microsoft` driver, which most Android kernels include. If the Xbox Wireless
  type causes problems, switch to the Xbox 360 type.
- A virtual **keyboard** is only created when the active profile uses keys. While it exists, Android may hide the
  on-screen keyboard, the same as with any physical keyboard.
