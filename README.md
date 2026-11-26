# Soundscape for Android

Android app for [Soundscape](https://github.com/novakr12/soundscape) (requires Android 8.0+).

## Features

- **Native background playback**: ExoPlayer audio engine with gapless playback, ReplayGain, 10-band equalizer, sleep timer, visualizer, queue editing, and saving queues as Navidrome playlists.
- **Offline downloads**: download albums and playlists (with optional Wi-Fi-only mode), play offline from the Downloads screen, and sync offline plays when reconnected.
- **Android Auto**: browse new albums, playlists, shuffle, and downloads, with voice search in the car.
- **Remote sync & lock screen**: appear in Soundscape **Devices**, hand off playback, and control other devices from the media notification and lock screen.
- **Immersive UI & notifications**: full-screen Theater Mode, accent-matched launcher icon, notifications (followers, releases, milestones, weekly recap, streak reminder), and update notices.
- **Discord status**: optional Rich Presence via the local Discord app RPC (no Discord login needed).

Configure app options in Soundscape under **Settings → This app**, or long-press the app icon for **Settings**, **Downloads**, or **Change server**.

## Install

Download `soundscape-<version>.apk` from [Releases](https://github.com/novakr12/soundscape-android/releases) on your phone and open it to install (newer versions update in place). On first launch, enter your Soundscape server address, log in, and approve the connection.

To enable Android Auto for sideloaded installs, open Android Auto settings on your phone, tap **Version** 10 times, then enable **Unknown sources** under menu → **Developer settings**.

## Build

Requires JDK 17+ and the Android SDK (`ANDROID_HOME` or `sdk.dir` in `local.properties`):

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Releasing

Generate a signing keystore once (keep it private):

```bash
keytool -genkeypair -v -keystore soundscape-release.keystore -alias soundscape -keyalg RSA -keysize 4096 -validity 10000
```

Configure GitHub Actions repository secrets (`SOUNDSCAPE_KEYSTORE_BASE64`, `SOUNDSCAPE_KEYSTORE_PASSWORD`, `SOUNDSCAPE_KEY_ALIAS`, `SOUNDSCAPE_KEY_PASSWORD`), bump `versionCode` and `versionName` in `app/build.gradle.kts`, and push a matching `v<version>` tag to build, sign, and publish the release APK.

## License

[AGPL-3.0-or-later](LICENSE)
