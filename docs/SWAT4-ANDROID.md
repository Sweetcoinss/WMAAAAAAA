# Architecture — SWAT 4 on Android

## 1. What runs where

```
┌───────────────────────── Android (arm64) ─────────────────────────┐
│  OpenSWAT4.apk                                                    │
│    LauncherActivity   choose the game folder → copy (1.74 GB)     │
│    GameActivity       FramebufferView + Swat4OverlayView         │
│    InputBridge        touch / gamepad → 8-byte messages → socket  │
│    AudioBridge        ALSA FIFO → AudioTrack                     │
│    proot (Termux)     the Linux runtime, same paths as Android    │
└───────────────────────────────┬───────────────────────────────────┘
                                │  /opt/ut/run-swat4.sh
┌───────────────────────── Debian trixie (arm64) ───────────────────┐
│  Xvfb :0 1280x720x24        virtual screen, framebuffer in /tmp/fb │
│  xbridge                    TCP → XTest (keys, buttons, motion)  │
│  Box86                      x86 → ARM64 translator                │
│  Wine 11.21 (x86)           32-bit Windows API                   │
│  wined3d                    D3D8/9 → OpenGL                       │
│  Mesa Zink → Turnip         OpenGL → Vulkan → Adreno (KGSL)      │
│  glibc i386 (/opt/box86/i386) the C library of the guest         │
└───────────────────────────────┬───────────────────────────────────┘
                                │
                        Swat4.exe (i386)
```

## 2. Input path

1. `Swat4OverlayView` (Android) turns touches into game keys/mouse events
   using the game's own key table (`Content/System/User.ini`).
2. `InputBridge` packs them into 8-byte messages (type, code, value) and
   writes them to the TCP socket the app opened.
3. `xbridge` (inside the container) receives them and calls
   `XTestFakeKeyEvent` / `XTestFakeButtonEvent` / `XTestFakeRelativeMotionEvent`
   on the Xvfb display.
4. Wine's X11 driver sees real X input and delivers it to `Swat4.exe`.

Menu screens are driven the same way: the game's GUI is mouse-driven, so the
existing menu cursor (`MenuCursorView`) is used and taps act as clicks.

## 3. Why Box86 and not FEX

SWAT 4 is a **32-bit** x86 binary. On ARM64:

* Wine exists for arm64/armhf but **not** for i386 → the 32-bit Wine build is
  itself an x86 binary → it needs an x86 emulator anyway.
* Box86 is the mature x86 (32-bit) translator used by every 32-bit Windows
  game on Android (Winlator uses it for the same purpose).
* FEX-Emu targets x86-64 (and 32-bit in a less tested mode) and is used for
  64-bit games.

The guest's i386 glibc comes from the Debian i386 archive and is executed by
Box86 the same way.

## 4. Rendering

SWAT 4's engine (`D3DDrv.dll`) uses DirectX 8.1/9.

* **wined3d** implements `d3d8.dll` / `d3d9.dll` on top of OpenGL → the same
  Zink → Turnip → Adreno path already used by the UT2004 launcher.
* DXVK would be faster (DirectX → Vulkan directly) but **DXVK has no 32-bit
  build**. The 32-bit equivalent, D9VK, exists and could be added later; it
  requires a Vulkan WSI path on the Xvfb window, which is the risky part.
* `shaders.spk` (the game's compiled shader pack) is D3D-specific: under
  wined3d the engine recompiles the shaders it needs, which costs a few
  seconds at first launch.

## 5. Settings applied to the game

`setup-wine.sh` patches `Content/System/Swat4.ini` after copying the game:

| Setting | Value | Why |
|---|---|---|
| `WindowedViewportX/Y` | screen size | the game renders in the Xvfb window |
| `StartupFullscreen` | `False` | there is no real fullscreen in the container |
| `FirstRun` | `0` | skip the first-run wizard |
| `MouseX/YMultiplier` | `0.35` | the touch look sends relative deltas |
| `UseJoystick` | `False` | input comes from the touch layer |

The `.u` script packages (already compiled UE2 bytecode) are used as-is; the
game recompiles nothing at startup.

## 6. Performance notes

* The game is from 2005: it is happy with 720p and a modern phone GPU.
* The costly part is the translation (Box86) plus the OpenGL→Vulkan path
  (Zink): expect a frame rate in the 20–40 fps range depending on the device.
* Lowering the render resolution to 540p is the first thing to try on a slow
  device; the launcher already offers 540p / 720p / 1080p.

## 7. What is done / what remains

| Item | State |
|---|---|
| runtime scripts (Box86, Wine, i386 libs, xbridge, Mesa) | written, needs a Linux/arm64 build |
| Wine prefix creation + game ini patch | written |
| launcher: choose folder → copy → run | written |
| touch overlay (stick, look, 12 buttons) | written |
| APK builds on Windows with the Android SDK | verified (debug APK, placeholder runtime) |
| real rootfs (Box86 + Wine + Turnip) | needs the Docker build (CI) |
| on-device test | pending |
| audio verification (ALAudio → OpenAL → ALSA FIFO) | pending |
| D9VK as a faster alternative to wined3d | optional |
