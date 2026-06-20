<p align="center">
  <img src="app_icon.svg" width="150" alt="P2P Connect Icon">
</p>

# P2P Connect 📡

An open-source Android application for seamless over-the-air peer-to-peer file sharing and text chatting. Built using modern Android foundations like Kotlin, Jetpack Compose, and Coroutines.

## Features ✨

- **Wi-Fi Direct & Bluetooth**: Dual-protocol support! Send files at blazing-fast speeds via Wi-Fi Direct, or use Bluetooth for legacy device fallback.
- **P2P Text Chatting**: Send and receive text messages securely over local P2P connections (no internet required).
- **File & App Sharing**: Share documents, media, and even installed applications directly from your phone to a connected peer.
- **Modern UI**: Full Material 3 design complete with dynamic colors, smooth animations, dark mode, and elegant progress indicators.
- **Lifecycle Aware**: Ensures minimal battery drainage by automatically pausing and stopping network discovery when the app is backgrounded.
- **Privacy First**: Fully offline architecture. No cloud synchronization, no hidden accounts, and no data collection. 

## Developers / Contributors 💻

This project was built and is maintained by:
- **[UDIN-K](https://github.com/UDIN-K/)**
- **[Duwiii-0](https://github.com/Duwiii-0)**
- **[Blip (Muhammad Irzaldi)](https://github.com/muhammadirzaldialamsyahtik24-blip)**

## Building and Running 🛠

To build and run this Android project from the source code, follow these steps:
  
1. Clone or download the repository.
2. Open the project in [Android Studio](https://developer.android.com/studio). Or Any IDE
3. Wait for Gradle to fully sync the project dependencies.
4. Run the app on a physical device. **Note:** An actual Android device with Wi-Fi and Bluetooth capabilities is required to fully test Wi-Fi Direct and Bluetooth connections (emulators do not fully support P2P simulation).

```bash
# Run tests locally
./gradlew testDebugUnitTest

# Assemble Debug APK
./gradlew assembleDebug
```

## Permissions ✅

The following permissions are required for the app to function correctly:
- **Location**: Required by the Android OS for Wi-Fi Direct and Bluetooth discovery.
- **Storage/Media**: Required for reading and writing files during transfers.
- **Bluetooth/Wi-Fi**: Required for device pairing and data transmission.

Make sure your physical device has Location Services and Bluetooth turned on before initiating a connection search.

## License 📄

This project is open-source and available under the terms of the **MIT License**.

See the [LICENSE](LICENSE) file for more information.
