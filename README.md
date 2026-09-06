<div align="center">
  <img src="docs/icon.png" width="100" height="100" style="border-radius: 22px;" alt="DacVolumeFix App Icon" />

  <h1>DacVolumeFix</h1>

  <p><strong>Automatically unlocks full hardware volume on USB DACs connected to Android — no root, 2.4 MB.</strong></p>

  <p>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License: GPL-3.0" /></a>
    <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg" alt="Android 8.0+" /></a>
    <a href="https://github.com/DeveshTone/DacVolumeFix/releases"><img src="https://img.shields.io/badge/APK%20Size-2.4%20MB-success.svg" alt="APK Size: 2.4 MB" /></a>
    <a href="https://github.com/DeveshTone/DacVolumeFix/releases"><img src="https://img.shields.io/github/v/release/DeveshTone/DacVolumeFix" alt="GitHub release" /></a>
  </p>
</div>

<p align="center">
  <img src="docs/screenshots/always_ready_in_the_background.png" width="31%" alt="Always Ready in the Background" />
  &nbsp;
  <img src="docs/screenshots/plug_in_done.png" width="31%" alt="Plug In. Done" />
  &nbsp;
  <img src="docs/screenshots/build_for_audiophiles.png" width="31%" alt="Build For Audiophiles" />
</p>

---

## The Problem

Most modern Android smartphones have dropped the 3.5mm headphone jack, requiring you to use a USB-C to 3.5mm headphone adapter or DAC. However, when you connect popular DACs like the **Apple USB-C Headphone Adapter**, your music often sounds surprisingly quiet and underpowered—even with your Android volume slider maxed out at 100%.

This happens because the DAC initializes its internal hardware volume chip to an aggressive low default (usually around -20 dB to -40 dB, or roughly 15% to 25% of potential output). While Windows, macOS, and iOS automatically set the DAC's internal hardware volume to full (0 dB unity gain), **Android does not adjust the DAC's hardware volume registers**. The Android volume slider only scales the digital audio before sending it out, leaving the physical hardware amplifier starved of power.

**DacVolumeFix solves this automatically.** Whenever your DAC is connected, DacVolumeFix sends standard USB Audio Class commands to set the hardware volume register to full 0 dB output—giving you the full volume, dynamic range, and driving power your headphones were designed for.

---

## Supported DACs

| DAC / Headphone Adapter | Chipset / Model | Status | Output Level |
|---|---|---|---|
| **Apple USB-C Adapter (US)** | Model A2049 (VID: `0x05AC`, PID: `0x110A`) | **Confirmed Working** | 1.0 Vrms (Full Output) |
| **Apple USB-C Adapter (EU)** | Model A2155 (VID: `0x05AC`, PID: `0x110B`) | **Confirmed Working** | 0.5 Vrms (Full Output) |
| **Conexant / Synaptics** | CX31993 (JCALLY JA04, Abigail, etc.) | *Expected Compatible* | Needs Community Test |
| **Realtek** | ALC5686 / ALC4042 / ALC4050 | *Expected Compatible* | Needs Community Test |
| **Cirrus Logic** | CS43131 / CS43198 (Moondrop, Tanchjim) | *Expected Compatible* | Needs Community Test |
| **FiiO** | KA11, KA13, JA11 | *Expected Compatible* | Needs Community Test |
| **ESS Sabre** | ES9280AC / ES9281AC / ES9038Q2M | *Expected Compatible* | Needs Community Test |
| **Generic USB Audio Class 1/2** | Any compliant UAC1 / UAC2 DAC with Feature Units | *Expected Compatible* | Needs Community Test |

*Have a DAC that is not confirmed yet? [Report your test results here!](https://github.com/DeveshTone/DacVolumeFix/issues/new?template=dac_compatibility.yml)*

---

## Why DacVolumeFix

- **Pure Kotlin** — zero C++ native libraries, zero NDK, runs natively on any Android CPU
- **2.4 MB** — no native binary overhead from cross-compiled `.so` files
- **Invisible** — windowless background operation, never interrupts what you're doing
- **One-time permission** — `device_filter.xml` pre-grants USB access, no repeated dialogs
- **Zero idle battery** — completely dormant when no DAC is connected, event-driven only
- **Universal** — unlocks volume system-wide for every app, not tied to any specific music player
- **Open source** — GPL-3.0, full source code available, no telemetry, no internet permission

---

## Installation & Setup

### Download APK
Download the signed release APK from the [Releases Page](https://github.com/DeveshTone/DacVolumeFix/releases/latest):
- **[`DacVolumeFix.apk`](https://github.com/DeveshTone/DacVolumeFix/releases/latest/download/DacVolumeFix.apk)** (~2.4 MB)

*(F-Droid submission planned)*

### Setup
1. Install and launch **DacVolumeFix**.
2. Connect your USB DAC or 3.5mm adapter.
3. The app automatically detects your DAC and unlocks the hardware volume.
4. If "Auto-apply on connect" is enabled, future connections will automatically unlock in the background without needing to open the app.

---

## How It Works

1. **Background Trampoline:** When your DAC is inserted, Android triggers an invisible, windowless dispatcher that launches without interrupting whatever you are doing.
2. **Pure Framework Control Transfers:** DacVolumeFix is written entirely in pure Kotlin using Android's native `UsbDeviceConnection.controlTransfer()`, avoiding native C++ dependencies.
3. **Automated Hardware Programming:** DacVolumeFix communicates with the DAC's AudioControl interface and Feature Unit, setting the digital attenuation register to 0 dB (or your custom target level).
4. **Self-Terminating Service:** The unlock completes quickly, displays a confirmation notification in your status bar, and shuts down immediately. It consumes **0.0% battery** while idle.

> 📖 **Want the deep technical breakdown?**  
> Read our full [Technical Documentation & Architecture Specification](docs/TECHNICAL_DOCUMENTATION.md) covering kernel `devio` internals, UAC 8.8 fixed-point decibel conversion, descriptor parsing, and `USBDEVFS_RESET`.

---

## Compatibility

Compatible with Android 8.0 through Android 15 (API 26+):
- **Android 15** (Vanilla Ice Cream)
- **Android 14** (Upside Down Cake)
- **Android 13** (Tiramisu)
- **Android 12 / 12L** (Snow Cone)
- **Android 11** (Red Velvet Cake)
- **Android 10** (Quince Tart)
- **Android 9.0 Pie & 8.0 Oreo**

---

## Building from Source

### Prerequisites
- Android Studio Ladybug (or newer)
- JDK 17 or higher
- Android SDK 35 (API 26 minSdk)

### Build Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/DeveshTone/DacVolumeFix.git
   cd DacVolumeFix
   ```
2. Build the signed release APK using Gradle:
   ```bash
   ./gradlew assembleRelease
   ```
3. The compiled APK will be generated at:
   `app/build/outputs/apk/release/app-release.apk`

---

## Contributing

Pull requests and hardware test reports are welcome!

- **Report a new DAC:** If you have tested a DAC or adapter, please submit a [DAC Compatibility Report](https://github.com/DeveshTone/DacVolumeFix/issues/new?template=dac_compatibility.yml) with your device's USB Vendor ID (VID) and Product ID (PID).
- **Bug Reports & Feature Requests:** Please open an issue using the [Issue Templates](https://github.com/DeveshTone/DacVolumeFix/issues).

---

## Acknowledgements

- Built with the assistance of [Antigravity AI](https://antigravity.ai) for code generation, architecture design, and debugging.
- Technical research inspired by the prior work of [ibaiGorordo](https://github.com/ibaiGorordo), [guyman624](https://github.com/guyman624), and [polhdez](https://github.com/polhdez).

---

## License

This project is licensed under the **GNU General Public License v3.0** — see the [LICENSE](LICENSE) file for details.
