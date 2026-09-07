# Changelog

All notable changes to **DacVolumeFix** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-09-06

### 🚀 Core Architecture & Engine
- **Pure Kotlin Framework Engine:** Replaced native C++ `libusb` wrappers with Android's native `UsbDeviceConnection.controlTransfer()` over Endpoint 0, eliminating native `.so` binaries, JNI boundaries, and reducing APK footprint from **21.5 MB down to ~2.2 MB** (an 89% reduction).
- **Zero-UI Windowless Trampoline:** Introduced `UsbTrampolineActivity` (`Theme.NoDisplay`) to intercept `ACTION_USB_DEVICE_ATTACHED` and immediately forward events to the background unlock service without interrupting active foreground applications or introducing window focus flicker.
- **Self-Terminating Background Service:** Implemented `UsbUnlockService` as a transient foreground service that executes in < 250 ms, posts an unlock confirmation notification, and terminates immediately—guaranteeing **0.0% idle battery drain**.

### 🎛️ Audio & Hardware Control
- **Multi-Tier UAC Unlock Pipeline:** Engineered multi-strategy control transfers:
  - *Strategy 1:* Non-claiming interface transfer (`0x21` request type) to manipulate volume registers while maintaining active ALSA audio playback.
  - *Strategy 2:* Device-recipient transfer (`0x20` request type) for compliant UAC2 peripherals.
  - *Strategy 3:* Channel-specific discrete targeting (`0x0201` Left, `0x0202` Right).
  - *Strategy 4:* Claimed interface fallback with kernel driver re-binding.
- **Dynamic Descriptor Parsing:** Developed `UsbDescriptorParser` to walk raw USB configuration descriptors at runtime, identifying AudioControl interfaces and Feature Unit IDs across diverse DAC chipsets (Apple A2049/A2155, CX31993, ALC5686, CS43131, ES9280, etc.).
- **Hardware Decibel Fine-Tuning:** Provided continuous -80 dB to 0 dB volume customization using standard USB Audio Class 8.8 signed fixed-point math.
- **Kernel Driver Recovery (`USBDEVFS_RESET`):** Built pure-Kotlin reflection wrapper over `android.system.Os.ioctlInt` to issue ioctl 21780, forcing ALSA sound card re-enumeration if strict driver detachment occurs.

### 🛡️ Permissions & System Integration
- **USB Audio Capture Warning Suppression:** Declared `RECORD_AUDIO` with a clear, user-facing rationale dialog on first launch to permanently suppress Android's recurring modal warning on DACs exposing microphone/ADC endpoints.
- **Automated USB Hardware Grants:** Configured `device_filter.xml` with explicit Vendor/Product IDs and class filters (`Audio` Class 1 and `IAD` Class 239/2/1) to enable permanent permission persistence.
- **Android 14/15 Compatibility:** Added `FOREGROUND_SERVICE_CONNECTED_DEVICE` and explicit service type declarations for compliance with modern Android security models.

### 🎨 User Interface & Experience
- **Material 3 Design:** Built clean, symmetric dashboard with Google Material 3 components, dynamic color theming, and real-time connection status indicators.
- **Status Bar Notification:** Added status bar unlock indicator to confirm successful volume unlock upon DAC insertion.
- **Privacy Assurance:** 100% offline application with zero network permissions (`android.permission.INTERNET`), zero telemetry, and zero tracking.
