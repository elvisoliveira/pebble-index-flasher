# Pebble Index Flasher

Android app to install and manage the firmware on the **Pebble Index 01** ring.

It is the companion app to the [pebble-index-cfw](https://github.com/elvisoliveira/pebble-index-cfw)
custom firmware: that repository is the firmware that runs **inside** the ring;
this is the app that runs on your **phone** and puts that firmware onto the ring —
no official app needed.

## What it does

On launch, the app finds the ring over Bluetooth and shows which firmware it is
running right now:

- **Official** — the factory firmware.
- **CFW** — the custom firmware. Here the app also shows a counter that goes up
  each time you press the ring's button.
- **Failsafe** — a recovery mode, from which any firmware can be reinstalled.

Depending on the state, it offers the right button: install the custom firmware,
go back to the official one, or enter recovery mode. You tap a button and the app
handles the rest, telling you when to bring the ring closer or press its button.

The ring is never "stuck": from any state you can get back to the official
firmware.

## Works offline

Both firmwares ship inside the app, so it uses **no internet** — no data leaves
the phone. You can use it even in airplane mode.

## Install

Download the latest `.apk` from [Releases](../../releases) and install it on the
phone (Android 12 or newer). You will need to allow "install from unknown
sources".

## Build

```
./gradlew assembleDebug
```

The APK lands in `build/outputs/apk/debug/`. Requires the Android SDK (point to it
with the `ANDROID_HOME` variable or a `local.properties` file).

No firmware binaries are kept in git. At build time Gradle fetches both — the
official firmware from the update manifest and the latest custom firmware from the
[pebble-index-cfw](https://github.com/elvisoliveira/pebble-index-cfw) releases —
and writes them into the app's assets. So the build needs network access, and
GitHub Actions ([`.github/workflows/build.yml`](.github/workflows/build.yml))
builds a complete APK on every push (and publishes it as a Release on a version
tag).
