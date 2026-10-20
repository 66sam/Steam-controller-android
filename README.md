# SC Mapper: Steam Controllers (and other gamepads) on Android

SC Mapper is a Steam Input-style remapper for Android. It supports the **Steam Controller (2015)**, the
**Steam Controller (2026)** and **any other gamepad** Android detects. It can turn a controller into an Xbox-style
gamepad, a keyboard and mouse, or a mix of both. You build your own profiles and switch between them from the
controller.

## Supported controllers

| Controller | USB cable | Wireless receiver | Bluetooth |
|---|---|---|---|
| Steam Controller (2015) | ✅ | ✅ (up to 4 controllers) | ✅ needs Valve's BLE firmware |
| Steam Controller (2026) | ✅ | ✅ | ✅ |
| Other gamepads (Xbox, PlayStation, Switch, 8BitDo, …) | ✅ | ✅ | ✅ through Android's own driver |

The Steam Controllers are driven directly. SC Mapper turns off their keyboard/mouse emulation ("lizard mode") and
reads the full state, including both trackpads, the gyro and the back buttons. It also drives the haptics and rumble.
The protocol code is ported from Valve's driver in SDL.

**Other gamepads** use Android's normal driver. Through Shizuku, SC Mapper reads their raw input and takes them over
exclusively, so games only see SC Mapper's output. You choose which controllers it takes over. A setup wizard can
learn any unusual layout. Gyro also works when the driver exposes motion sensors, as with DualSense, DualShock 4 and
Switch Pro controllers.

## Features

- **Real virtual devices.** Android and games see a hardware **Xbox controller**, keyboard and mouse, created
  through the kernel's `uhid` interface. Games that only accept real controllers work too.
- **Every input can be remapped:** face buttons, bumpers, Back/Start, Steam/Guide, quick access, up to **four back
  buttons**, stick clicks, the D-pad, analog triggers (soft-pull and full-pull click actions), both sticks, both
  trackpads and the gyro.
- **Modes for the trackpads and sticks:**
  - Analog stick (left or right), with deadzone, sensitivity and optional click-to-activate
  - Directional zones: a 4- or 8-way D-pad, **ABXY**, WASD, arrow keys, or any actions you pick, plus a center zone
  - **Touch-zone grid** (1×1 up to 4×4): each region of the pad is its own button
  - Mouse / trackball with momentum and haptic ticks; a stick can also drive the mouse
  - Circular scroll wheel
- **Button extras:** a separate long-press action, and turbo.
- **Gyro:** to the mouse or to a joystick. Activation can be always on, while held, ratchet or toggle. You can set
  sensitivity per axis, yaw or roll steering, an anti-deadzone and small-movement filtering. Calibration is automatic,
  or manual with Steam + X.
- **Haptics:** feedback when you move between zones, mouse ticks, scroll clicks and shortcut confirmations. Game
  rumble is forwarded to the controller: the 2015 controller plays it on its trackpad actuators, the 2026 controller
  on its rumble motors.
- **Profiles:** create, copy, rename, delete, import and export them.
- **Steam Input `.vdf` import and export.** You can import configurations from Steam or from sites like
  steaminputdb.com. The default action set's button, D-pad, stick, trackpad, trigger and gyro groups are converted,
  along with press and long-press bindings. After an import, SC Mapper lists what it couldn't convert, such as
  action layers, mode shifts, chords and double presses.
- **Switching from the controller:** shortcuts, plus an on-screen menu drawn over the running game.

### Built-in presets

| Preset | What it does |
|---|---|
| **Gamepad** | Xbox layout. The right pad is the right stick (click = R3), the left pad is a click D-pad and the back buttons are L3/R3 |
| **FPS · Gamepad + gyro aim** | Same layout, with gyro on the right stick. Hold the right back button to recenter (ratchet) |
| **Keyboard & Mouse** | Right pad = trackball mouse, left pad = scroll wheel, triggers = clicks, stick/D-pad = arrow keys, B = Android Back, Steam = Home, back buttons = volume |
| **FPS · Keyboard & Mouse + gyro** | WASD stick, mouse on the right pad and right stick, gyro mouse while you touch the right pad, triggers = fire / aim |
| **Retro · Pads as D-pad + ABXY** | The left pad is a touch D-pad, the right pad is a click ABXY pad |

On the Steam Controller (2026), the **…** (quick access) button opens the profile menu in every preset.

### Controller shortcuts

| Shortcut | Action |
|---|---|
| Steam + RB / LB | Next / previous profile |
| Steam + ◀ or ▶ (or …) | Profile menu (navigate with the stick, left pad or D-pad; A selects, B closes) |
| Steam + Y | Gyro on / off |
| Steam + X | Calibrate the gyro (hold still for about 1 s) |
| Hold Steam for 3 s | Turn the controller off (2015) |
| Tap Steam | The Steam button's own binding (Guide by default) |

On other gamepads the Guide / Home / PS button acts as the Steam button.

## Requirements

- Android 8.0 or newer.
- **[Shizuku](https://shizuku.rikka.app/)**, running in Wireless debugging mode (no root needed) or root mode.
  Android doesn't let normal apps create input devices or read other apps' controllers. Shizuku runs a small helper
  with `adb shell` rights, and that helper uses `/dev/uhid` and `/dev/input`. With Wireless debugging you need to
  start Shizuku again after each reboot.
- For USB: a USB OTG adapter or a USB-C hub.
- For Bluetooth on the 2015 controller: Valve's **BLE firmware**, installed once from Steam on a PC
  (Big Picture → Settings → Controller → your Steam Controller → firmware).

## Setup

1. Install the APK. You can get it from the **Actions** tab of this repository (artifact `scmapper-apk`) or build it
   yourself (see below).
2. Install and start Shizuku, open SC Mapper and tap **Grant Shizuku permission**.
3. **Steam Controllers over USB or a receiver:** plug it in and accept the prompt.
4. **Steam Controllers over Bluetooth:** put the controller in pairing mode (on the 2015 controller, hold **Y** while
   pressing **Steam**) and pair it in Android's Bluetooth settings. In SC Mapper, tap **Add Bluetooth** and pick the
   controller. The app remembers it and reconnects automatically.
5. **Other gamepads:** connect the gamepad as usual, then tick it under **Other controllers**. If buttons come out
   wrong, long-press it to run the setup wizard.
6. Optional: allow **Display over other apps**, which the on-controller profile menu needs.

### Virtual controller type (Settings)

- **Xbox Wireless Controller (default):** presents itself as an Xbox One S Bluetooth pad. The Linux `hid-microsoft`
  driver attaches to it, so games can **rumble**, and SC Mapper forwards that rumble to your controller.
- **Xbox 360 Controller:** a fallback with the most common Android key layout. It has no rumble.

## How it works

```
Steam Controller 2015/2026 ──USB (UsbManager) / BLE (GATT)──┐
Other gamepads ──kernel evdev──▶ Shizuku helper (grab) ─────┤
                                                            ▼
        Mapper (one per controller, profile-driven: buttons, pads, gyro, shortcuts, haptics)
                                                            ▼
        OutputHub ──Binder──▶ Shizuku helper ──/dev/uhid──▶ virtual Xbox pad / keyboard / mouse ──▶ games
```

- `controller/`: the Steam Controller (2015) and (2026) protocols, the USB and BLE transports, and generic gamepads
  (`getevent` discovery, evdev translation, and the layout learned by the wizard).
- `mapping/`: the profile model, presets, the mapping engine, and Steam Input VDF import/export.
- `output/`: HID descriptors, the Shizuku helper (uhid devices and evdev reading), and the output hub.
- `service/`: the foreground service and overlay menu. `ui/`: the screens, built from framework Views only.

## Building

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # protocol, mapping, VDF and evdev unit tests
```

CI (`.github/workflows/build.yml`) runs the tests and builds debug and release APKs on every push.

## Status and known limitations

This build hasn't been tested on real hardware yet. The Steam Controller protocols follow Valve's SDL code, and the
unit tests check the parsers against Valve's packet layouts. Things that may need adjusting after a first test:

- **Gyro direction:** if aiming moves the wrong way, use the *Invert* switches in the gyro settings.
- **Steam Controller (2026):** its Bluetooth pairing steps and haptic side numbering are based on SDL and may
  differ on some firmware versions.
- **Other gamepads:** default layouts follow Linux conventions; run the setup wizard if a button is wrong. Their
  rumble and touchpads are not used yet.
- **`.vdf` export** is best effort. Media keys and SC Mapper shortcuts have no Steam Input equivalent and are left
  out.
- **Rumble from games** depends on the kernel's `hid-microsoft` driver, which most Android kernels include. If the
  Xbox Wireless type causes problems, switch to the Xbox 360 type.
- A virtual **keyboard** is only created when the active profile uses keys. While it exists, Android may hide the
  on-screen keyboard, the same as with any physical keyboard.
