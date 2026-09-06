# Changelog

All notable changes to **DacVolumeFix** will be documented in this file.

## [1.0.0] - 2026-09-06

### ✨ Initial Public Release
- **Pure Kotlin Engine:** Replaced native C++ `libusb` with native Android `UsbDeviceConnection.controlTransfer()` over endpoint 0, reducing APK size from 21.5 MB to 2.4 MB.
- **Audio Mute Bug Solved:** Implemented multi-tier non-destructive control transfers (Strategy 1: `0x21` without claim & Strategy 2: `0x20` Device Recipient) allowing hardware volume changes while music is actively playing with zero audio drops.
- **Invisible USB Trampoline:** Added windowless, zero-UI `UsbTrampolineActivity` (`Theme.NoDisplay`) to capture `ACTION_USB_DEVICE_ATTACHED` and dispatch unlocks without interrupting the user's active foreground app.
- **Hardware Decibel Slider:** Added continuous -80 dB to 0 dB volume fine-tuning with UAC 8.8 signed fixed-point conversion.
- **Dynamic Descriptor Parsing:** Traverses raw USB configuration descriptors to detect AudioControl interfaces and Feature Unit IDs dynamically across different DAC topologies.
- **Kernel Driver Re-Bind via ioctl:** Integrated fallback `USBDEVFS_RESET` (ioctl code 21780) to force ALSA sound card re-enumeration if strict driver detachment occurs.
- **Automated Permission Grants:** Configured `device_filter.xml` to eliminate repetitive USB permission popups after initial confirmation.
- **Material 3 Design:** Built clean, symmetric dashboard with Google Material 3 components and dynamic theming.
- **Themed App Icon:** Added monochrome adaptive vector icon support for Android 13+ Material You dynamic color themes and a dedicated status bar open padlock notification icon.
