# R1 Tools

Device utilities for the Rabbit R1's single **motorized camera** and its sensors. Kotlin app
(package `com.r1motor`, label "R1 Tools").

## Features
- **Camera motor control** — writes the orientation sysfs node
  (`/sys/devices/platform/step_motor_ms35774/orientation`, `FRONT=0 / REAR=180 / STOW=90`) via root.
- **Auto-point** — a `CameraManager.AvailabilityCallback` service that rotates the lens when the
  camera opens.
- **Auto-rotate** — reads the accelerometer directly and sets `user_rotation` (the R1's framework
  auto-rotate is unreliable), with landscape mapping and a Quick Settings tile.
- Boot receiver + QS tiles for the motor and rotate services.

## Requirements
- **Root** (writes sysfs) + the **motor-sepolicy** Magisk module (SELinux rules for the motor node).

## Build & install
```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Grant root when prompted, flash [`magisk-modules/motor-sepolicy`](../../magisk-modules/motor-sepolicy),
then enable the tiles/toggles you want.
