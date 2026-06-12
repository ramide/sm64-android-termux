# Android 16 Compatibility Fork

This is a community fork of [izzy2fancy/termux-app](https://github.com/izzy2fancy/termux-app) (SM64 Builder v1.7) patched to run on Android 14, 15, and 16. The original app crashes immediately on launch on these versions due to OS-level breaking changes that were introduced after the app was last maintained.

## What was broken and why

Android 14 (API 34) made several changes that hard-crash apps built against older SDKs:

- **Foreground service type required** — Android 14+ throws an exception when any service calls `startForeground()` without a `foregroundServiceType` declared in the manifest. `TermuxService` and `RunCommandService` both lacked this, causing the app to die the moment it started.
- **PendingIntent mutability flags required** — Android 12+ requires `FLAG_IMMUTABLE` or `FLAG_MUTABLE` on every `PendingIntent`. Missing flags crash the app when notifications are tapped.
- **Wrong package name in manifest** — The `HEAD` branch had `package="com.am2rbuilder"` (a leftover from an earlier AM2R Builder fork) conflicting with `applicationId "com.sm64builder"`, causing a context lookup failure on every launch.

## What was changed

| File | Change |
|---|---|
| `AndroidManifest.xml` | Fixed package name; added `foregroundServiceType="specialUse"` to both services; added `FOREGROUND_SERVICE_SPECIAL_USE` permission |
| `TermuxService.java` | Uses 3-arg `startForeground()` with service type on API 34+; fixed `PendingIntent` flags |
| `RunCommandService.java` | Uses 3-arg `startForeground()` with service type on API 34+ |
| `gradle.properties` | targetSdk 28→34, compileSdk 30→34, NDK r22→r27c |
| `build.gradle` (root) | Android Gradle Plugin 4.2.2→7.4.2 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 7.2→7.6.4 |
| `app/src/main/cpp/Android.mk` | Added 16KB page-size linker flag for Android 15+ kernel compatibility |
| `terminal-emulator/.../Android.mk` | Added 16KB page-size linker flag |
| `termux-shared/.../Android.mk` | Added 16KB page-size linker flag |
| `.github/workflows/debug_build.yml` | Pinned JDK 17, added NDK r27c install step, updated upload-artifact to v4 |

All changes are backwards-compatible — the APK still runs on Android 5 (minSdk 21) through Android 16.

## Building

Push this fork to GitHub. The `Build` workflow in `.github/workflows/debug_build.yml` runs automatically on every push and produces split APKs as downloadable artifacts. Download the `arm64-v8a` artifact for modern Android phones.

---

## Original README

All that you need is a US Super Mario 64 rom, named baserom.us.z64
It can be either in the root of downloads or in the root of internal storage either way it will probably find it!

The external SM64EX builds need the base.zip moved to Android/data/files/res/ the base.zip is located inside the SM64 Builder in the sm64-izzys-port-android/build/us_pc/res/ folder.

Please consider sponsoring me so I can continue working on this and other projects. Thank you!


**Termux users! You must uninstall termux before installing.**



**I added the following as per a request**


**If you don't want to uninstall termux, then use the commands below.**

To build Coop use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-sm64ex-coop.sh)
```

To build Coop Render96 HD use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-sm64ex-coop-render96.sh)
```

To build OMM use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-sm64ex-omm.sh)
```

To build ALO use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-sm64ex-alo.sh)
```

To build SM64EX internal use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-sm64ex-INT.sh)
```

To build SM64EX internal No Touch use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-sm64ex-INTnoTouch.sh)
```

To build SM64EX external (**Needs Assets moved to Android/data/files/res/**)use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-sm64ex-EXT.sh)
```

To build SM64EX external No Touch (**Needs Assets moved to Android/data/files/res/**) use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-sm64ex-EXTnoTouch.sh)
```

To build SM64EX Porcino use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-sm64ex-porcino.sh)
```

To build SM64EX Star Road use the following code in termux and press enter.

```
bash <(curl -Ls https://github.com/izzy2fancy/termux-packages/raw/patch-2/packages/bash/build-starroad.sh)
```

