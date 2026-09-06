# Contributing to DacVolumeFix

Thank you for your interest in improving DacVolumeFix!

## How You Can Help

### 1. Test and Report Your DAC
The most valuable contribution is testing your USB-C DAC or 3.5mm adapter and reporting the results:
1. Connect your DAC to your Android device with DacVolumeFix installed.
2. Note your DAC model, brand, chipset (if known), and Android version.
3. Note whether the output volume increases noticeably and whether volume adjustment works during active playback.
4. Submit a **[DAC Compatibility Report](https://github.com/DeveshTone/DacVolumeFix/issues/new?template=dac_compatibility.yml)**.

### 2. Reporting Bugs
When reporting bugs, please provide:
- Phone model and Android version / ROM (e.g. Pixel 8 on Android 15, Galaxy S23 on One UI 6).
- DAC brand and model name.
- USB Vendor ID (VID) and Product ID (PID) if available.
- Steps to reproduce the issue.

### 3. Code Contributions
1. Fork the repository and create a feature branch (`git checkout -b feature/amazing-feature`).
2. Adhere to Kotlin and Jetpack Compose best practices.
3. Ensure the project builds cleanly without warnings: `./gradlew assembleRelease`.
4. Submit a Pull Request describing your changes.

## Development Setup
- Android Studio Ladybug (or newer)
- Android SDK 35 (compileSdk 35, minSdk 26)
- JDK 17+
