# aOS — iOS in app on your android!

[![Build aOS APK](https://github.com/KodiGamingYT/aOS-android/actions/workflows/build.yml/badge.svg)](https://github.com/KodiGamingYT/aOS-android/actions/workflows/build.yml)
[![Build libqemu-ios.so Artifact](https://github.com/KodiGamingYT/aOS-android/actions/workflows/build-engine.yml/badge.svg)](https://github.com/KodiGamingYT/aOS-android/actions/workflows/build-engine.yml)

**aOS** is a native Android container and emulation environment designed to run legacy iOS releases directly on ARM64 Android devices (Android 10 through Android 17) using a custom `qemu-ios` backend.

## Features
- **Guided Setup Flow:** Clean dark-mode onboarding (`Welcome` → `Choose your first container` → `IPSW Fetch & Boot`).
- **Supported iOS Targets:**
  - **iOS 6.1.4** (`iPhone 5` / `n42ap`)
  - **iOS 7.1.2** (`iPhone 4` / `n90ap`)
  - **iOS 8.4.1** (`iPhone 5` / `n41ap`)
- **Direct Apple CDN Integration:** Automatically downloads official `.ipsw` restore images directly from Apple servers, extracts `kernelcache`, `DeviceTree`, and `RootFS`, and removes temporary archives after building the container.
- **Optional Jailbreak & Cydia Injection:** Pre-configures untethered jailbreak patches and installs **Cydia** during container creation when enabled.
- **Hardware-Mapped VM Controls:**
  - Dedicated on-screen **Home** and **Lock (Power)** buttons mapped to QEMU GPIO.
  - Hardware **Volume Up / Volume Down** key interception forwarded directly to the emulated iOS guest.
- **Exynos & ARM64 Tuning (`Settings`):** Configurable CPU core count, RAM allocation, resolution scaling (`50%`–`100%`), and selectable **OpenGL ES** / **Software** rendering backends.

## Requirements
- **Minimum OS:** Android 10 (API 29)
- **Target / Maximum OS:** Android 17 (API 37)
- **Architecture:** `arm64-v8a`

## Repository Layout
```text
aOS/
├── .github/workflows/build.yml               # Automated APK build via GitHub Actions
├── app/
│   ├── build.gradle.kts                      # App module configuration (com.codythelildev.aos)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── jniLibs/arm64-v8a/                # Place compiled libqemu-ios.so here
│       └── java/com/codythelildev/aos/
│           ├── MainActivity.kt               # Navigation, Welcome, Container Wizard & Installer UI
│           ├── AosModels.kt                  # iOS targets, IPSW URLs, VM settings & GPIO keycodes
│           ├── IpswInstaller.kt              # Apple CDN downloader, IPSW extractor & Cydia patcher
│           ├── QemuNativeBridge.kt           # JNI interface connecting Kotlin UI to libqemu-ios.so
│           ├── SettingsScreen.kt             # CPU/RAM/GPU & network bridge configuration
│           └── VmScreen.kt                   # QEMU video SurfaceView, Home/Lock & Volume capture
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
