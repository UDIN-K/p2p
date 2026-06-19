<p align="center">
  <img src="app_icon.svg" width="150" alt="P2P Connect Icon">
</p>

# P2P Connect 📡

An open-source Android application for seamless over-the-air peer-to-peer file sharing and text chatting. Built using modern Android foundations like Kotlin, Jetpack Compose, and Coroutines.

## Features ✨

- **Wi-Fi Direct Sharing**: Send and receive files locally at blazing-fast speeds without the need for an active internet connection.
- **Bluetooth Sharing**: Fallback connection for sharing files directly to paired devices using standard Bluetooth.
- **App Sharing**: Easily share installed apps directly from your phone to a connected peer.
- **Local Chatting**: Send and receive text messages securely on local network protocols.
- **Modern UI**: Full Material 3 design complete with dynamic colors, animations, dark mode, and elegant progress indicators.
- **Fast & Lightweight**: Built efficiently without any cloud synchronization dependencies, fully respects user privacy.

## Screenshots 📸

The app uses standard UI components from Material 3 to provide consistent and aesthetically pleasing interactive flows. File transfers showcase real-time progress indicators, speeds, and ETAs.

## Building and Running 🛠

To build and run this Android project from the source code, follow these steps:
1. Clone or download the repository.
2. Open the project in [Android Studio](https://developer.android.com/studio).
3. Wait for Gradle to fully sync the project dependencies.
4. Run the app on an emulator or a physical device connected via USB or Wi-Fi debugging (Physical device required to fully test Wi-Fi Direct and Bluetooth).

```bash
# Run tests locally
./gradlew testDebugUnitTest

# Assemble Debug APK
./gradlew assembleDebug
```

## Setup Permissions ✅

Make sure your physical device has Location Services turned on (a requirement by Android OS for Wi-Fi Direct discovery) and Bluetooth capabilities enabled to fully test peer discovery and connections.

## License 📄

This project is open-source and available under the terms of the **MIT License**.

See the [LICENSE](LICENSE) file for more information.
