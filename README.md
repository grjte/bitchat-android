> [!WARNING]
> This software has not received external security review and contains vulnerabilities. It does not meet its stated security goals. Do not use it for sensitive use cases, and do not rely on its security.

# bitchat for Android - forked to add direct messaging over Wi-Fi Aware (~5x range, ~100x bandwidth)

"A secure, decentralized, peer-to-peer messaging app that works over Bluetooth mesh networks. No internet required for mesh chats, no servers, no phone numbers - just pure encrypted communication. Bitchat also supports geohash channels, which use an internet connection to connect you with others in your geographic area." (from the original [bitchat-android README](https://github.com/permissionlesstech/bitchat-android))

This is a fork of the **Android port** of the original [bitchat iOS app](https://github.com/jackjackbits/bitchat), which maintained 100% protocol compatibility for cross-platform communication. In this fork, that compatibility is preserved, but Wi-Fi Aware connections will only be made between Android devices (in all other cases, messages will continue to be sent over BLE or Nostr).

**This fork provides prototype code that explores the use of Wi-Fi Aware as an additional transport for high-bandwidth and longer-range direct messaging within Bitchat over an encrypted P2P Wi-Fi connection.**

[Wi-Fi Aware](https://www.wi-fi.org/alternative-topologies) is a framework for peer-to-peer Wi-Fi connections that is supported on both [Android](https://developer.android.com/develop/connectivity/wifi/wifi-aware) and [iOS](https://developer.apple.com/documentation/WiFiAware). The range of Wi-Fi Aware is roughly ~5x the range available on Bluetooth Low Energy (BLE) for mobile devices (~50-100m vs. ~10m), and the bandwidth increase is ~100x or more. 

This fork implements Wi-Fi Aware as an additional transport in the [`app/src/main/java/com/bitchat/android/wifi-aware`](https://github.com/grjte/bitchat-android/tree/feat/wifi-aware/app/src/main/java/com/bitchat/android/wifi-aware) directory, with minimal changes within the rest of the code that serve to:
1. request/check Wi-Fi Aware permissions (and capabilities)
2. initiate the Wi-Fi Aware service (when available)
3. make it the preferred transport for direct messaging (when available)
4. display a Wi-Fi icon in the direct messaging chat UI when peers are connected via a Wi-Fi Aware connection

Read more about this project and the possibilities and challenges of using Wi-Fi Aware for mobile ad-hoc networks [here](https://hackmd.io/@grjte/bitchat-wifi-aware). Learn more about Wi-Fi Aware implementation details and cross-platform compatibility [here](https://hackmd.io/@grjte/cross-platform-wifi-aware).

> [!WARNING]
> This implementation is NOT SECURE. It should be used only for reference and not for real-world use. It was vibe-coded by Claude with my guidance, which primarily focused on the Wi-Fi Aware connectivity lifecycle, since both Claude Code and ChatGPT are currently unable to handle it correctly. 
> 
> In particular: 
> 1. I have not implemented the cryptography required for peers to agree on a secure passphrase for their Wi-Fi connection (a hard-coded passphrase is used).
> 2. I have not taken care with handling closed or dropped Wi-Fi Aware connections and ensuring peers are correctly switched back to encrypted messaging over BLE.
> 3. Any vulnerabilities that existed in the bitchat-android code at the time it was forked are still present.

The original README for bitchat-android continues below (with minor edits that remove the Google Play Store publication instructions and update the Contributing guidelines.). 

Note that the installation instructions below are for the official version. To experiment with this version, you'll need to build it locally after cloning this repository. Instructions should otherwise be the same as those written below.

-----

## Install bitchat

You can download the latest version of bitchat for Android from the [GitHub Releases page](https://github.com/permissionlesstech/bitchat-android/releases).

Or you can:

[<img alt="Get it on Google Play" height="60" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"/>](https://play.google.com/store/apps/details?id=com.bitchat.droid)

**Instructions:**

1.  **Download the APK:** On your Android device, navigate to the link above and download the latest `.apk` file. Open it.
2.  **Allow Unknown Sources:** On some devices, before you can install the APK, you may need to enable "Install from unknown sources" in your device's settings. This is typically found under **Settings > Security** or **Settings > Apps & notifications > Special app access**.
3.  **Install:** Open the downloaded `.apk` file to begin the installation.

## License

This project is released into the public domain. See the [LICENSE](LICENSE.md) file for details.

## Features

- **✅ Cross-Platform Compatible**: Full protocol compatibility with iOS bitchat
- **✅ Decentralized Mesh Network**: Automatic peer discovery and multi-hop message relay over Bluetooth LE
- **✅ End-to-End Encryption**: X25519 key exchange + AES-256-GCM for private messages
- **✅ Channel-Based Chats**: Topic-based group messaging with optional password protection
- **✅ Store & Forward**: Messages cached for offline peers and delivered when they reconnect
- **✅ Privacy First**: No accounts, no phone numbers, no persistent identifiers
- **✅ IRC-Style Commands**: Familiar `/join`, `/msg`, `/who` style interface
- **✅ Message Retention**: Optional channel-wide message saving controlled by channel owners
- **✅ Emergency Wipe**: Triple-tap logo to instantly clear all data
- **✅ Modern Android UI**: Jetpack Compose with Material Design 3
- **✅ Dark/Light Themes**: Terminal-inspired aesthetic matching iOS version
- **✅ Battery Optimization**: Adaptive scanning and power management

## Android Setup

### Prerequisites

- **Android Studio**: Arctic Fox (2020.3.1) or newer
- **Android SDK**: API level 26 (Android 8.0) or higher
- **Kotlin**: 1.8.0 or newer
- **Gradle**: 7.0 or newer

### Build Instructions

1. **Clone the repository:**
   ```bash
   git clone https://github.com/permissionlesstech/bitchat-android.git
   cd bitchat-android
   ```

2. **Open in Android Studio:**
   ```bash
   # Open Android Studio and select "Open an Existing Project"
   # Navigate to the bitchat-android directory
   ```

3. **Build the project:**
   ```bash
   ./gradlew build
   ```

4. **Install on device:**
   ```bash
   ./gradlew installDebug
   ```

### Development Build

For development builds with debugging enabled:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release Build

For production releases:

```bash
./gradlew assembleRelease
```

## Android-Specific Requirements

### Permissions

The app requires the following permissions (automatically requested):

- **Bluetooth**: Core BLE functionality
- **Location**: Required for BLE scanning on Android
- **Notifications**: Message alerts and background updates

### Hardware Requirements

- **Bluetooth LE (BLE)**: Required for mesh networking
- **Android 8.0+**: API level 26 minimum
- **RAM**: 2GB recommended for optimal performance

## Usage

### Basic Commands

- `/j #channel` - Join or create a channel
- `/m @name message` - Send a private message
- `/w` - List online users
- `/channels` - Show all discovered channels
- `/block @name` - Block a peer from messaging you
- `/block` - List all blocked peers
- `/unblock @name` - Unblock a peer
- `/clear` - Clear chat messages
- `/pass [password]` - Set/change channel password (owner only)
- `/transfer @name` - Transfer channel ownership
- `/save` - Toggle message retention for channel (owner only)

### Getting Started

1. **Install the app** on your Android device (requires Android 8.0+)
2. **Grant permissions** for Bluetooth and location when prompted
3. **Launch bitchat** - it will auto-start mesh networking
4. **Set your nickname** or use the auto-generated one
5. **Connect automatically** to nearby iOS and Android bitchat users
6. **Join a channel** with `/j #general` or start chatting in public
7. **Messages relay** through the mesh network to reach distant peers

### Android UI Features

- **Jetpack Compose UI**: Modern Material Design 3 interface
- **Dark/Light Themes**: Terminal-inspired aesthetic matching iOS
- **Haptic Feedback**: Vibrations for interactions and notifications
- **Adaptive Layout**: Optimized for various Android screen sizes
- **Message Status**: Real-time delivery and read receipts
- **RSSI Indicators**: Signal strength colors for each peer

### Channel Features

- **Password Protection**: Channel owners can set passwords with `/pass`
- **Message Retention**: Owners can enable mandatory message saving with `/save`
- **@ Mentions**: Use `@nickname` to mention users (with autocomplete)
- **Ownership Transfer**: Pass control to trusted users with `/transfer`

## Security & Privacy

### Encryption
- **Private Messages**: X25519 key exchange + AES-256-GCM encryption
- **Channel Messages**: Argon2id password derivation + AES-256-GCM
- **Digital Signatures**: Ed25519 for message authenticity
- **Forward Secrecy**: New key pairs generated each session

### Privacy Features
- **No Registration**: No accounts, emails, or phone numbers required
- **Ephemeral by Default**: Messages exist only in device memory
- **Cover Traffic**: Random delays and dummy messages prevent traffic analysis
- **Emergency Wipe**: Triple-tap logo to instantly clear all data
- **Bundled Tor Support**: Built-in Tor network integration for enhanced privacy when internet connectivity is available

## Performance & Efficiency

### Message Compression
- **LZ4 Compression**: Automatic compression for messages >100 bytes
- **30-70% bandwidth savings** on typical text messages
- **Smart compression**: Skips already-compressed data

### Battery Optimization
- **Adaptive Power Modes**: Automatically adjusts based on battery level
  - Performance mode: Full features when charging or >60% battery
  - Balanced mode: Default operation (30-60% battery)
  - Power saver: Reduced scanning when <30% battery
  - Ultra-low power: Emergency mode when <10% battery
- **Background efficiency**: Automatic power saving when app backgrounded
- **Configurable scanning**: Duty cycle adapts to battery state

### Network Efficiency
- **Optimized Bloom filters**: Faster duplicate detection with less memory
- **Message aggregation**: Batches small messages to reduce transmissions
- **Adaptive connection limits**: Adjusts peer connections based on power mode

## Technical Architecture

### Binary Protocol
bitchat uses an efficient binary protocol optimized for Bluetooth LE:
- Compact packet format with 1-byte type field
- TTL-based message routing (max 7 hops)
- Automatic fragmentation for large messages
- Message deduplication via unique IDs

### Mesh Networking
- Each device acts as both client and peripheral
- Automatic peer discovery and connection management
- Store-and-forward for offline message delivery
- Adaptive duty cycling for battery optimization

### Android-Specific Optimizations
- **Coroutine Architecture**: Asynchronous operations for mesh networking
- **Kotlin Coroutines**: Thread-safe concurrent mesh operations
- **EncryptedSharedPreferences**: Secure storage for user settings
- **Lifecycle-Aware**: Proper handling of Android app lifecycle
- **Battery Optimization**: Foreground service and adaptive scanning

## Android Technical Architecture

### Core Components

1. **BitchatApplication.kt**: Application-level initialization and dependency injection
2. **MainActivity.kt**: Main activity handling permissions and UI hosting
3. **ChatViewModel.kt**: MVVM pattern managing app state and business logic
4. **BluetoothMeshService.kt**: Core BLE mesh networking (central + peripheral roles)
5. **EncryptionService.kt**: Cryptographic operations using BouncyCastle
6. **BinaryProtocol.kt**: Binary packet encoding/decoding matching iOS format
7. **ChatScreen.kt**: Jetpack Compose UI with Material Design 3

### Dependencies

- **Jetpack Compose**: Modern declarative UI
- **BouncyCastle**: Cryptographic operations (X25519, Ed25519, AES-GCM)
- **Nordic BLE Library**: Reliable Bluetooth LE operations
- **Kotlin Coroutines**: Asynchronous programming
- **LZ4**: Message compression (when enabled)
- **EncryptedSharedPreferences**: Secure local storage

### Binary Protocol Compatibility

The Android implementation maintains 100% binary protocol compatibility with iOS:
- **Header Format**: Identical 13-byte header structure
- **Packet Types**: Same message types and routing logic
- **Encryption**: Identical cryptographic algorithms and key exchange
- **UUIDs**: Same Bluetooth service and characteristic identifiers
- **Fragmentation**: Compatible message fragmentation for large content

## Cross-Platform Communication

This Android port enables seamless communication with the original iOS bitchat app:

- **iPhone ↔ Android**: Full bidirectional messaging
- **Mixed Groups**: iOS and Android users in same channels
- **Feature Parity**: All commands and encryption work across platforms
- **Protocol Sync**: Identical message format and routing behavior

**iOS Version**: For iPhone/iPad users, get the original bitchat at [github.com/jackjackbits/bitchat](https://github.com/jackjackbits/bitchat)

-----

## Contributing

Please do not attempt to contribute to this repository. It is an exploratory prototype of Bitchat with Wi-Fi Aware. The official repos welcome contributions:
- https://github.com/permissionlesstech/bitchat
- https://github.com/permissionlesstech/bitchat-android
