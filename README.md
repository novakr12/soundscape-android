# Soundscape for Android

The Android app for [Soundscape](https://github.com/novakr12/soundscape), the self-hosted music tracker. It opens your own Soundscape server in a full-screen app, the way the [desktop app](https://github.com/novakr12/soundscape-desktop) does on Linux.

## Install

Download `soundscape-<version>.apk` from the [Releases page](https://github.com/novakr12/soundscape-android/releases) on your phone and open it. Android asks once to allow installs from your browser or file manager; allow it, install, and turn it back off if you like. Later versions install over the top and keep your login.

The app needs Android 8.0 or newer.

## First start

Type the address you open Soundscape at, for example `music.example.com`. The app adds `https://` when you leave it out, checks that a Soundscape server answers there, and remembers it. Then log in as you would in a browser.

To switch servers later, long-press the app icon and choose **Change server**. When the server can't be reached, the app shows a page with **Try again** and **Change server** too.

## What it does now

- Your Soundscape pages, full screen, with the phone's back button going back a page
- Links to other sites (MusicBrainz, Wikipedia, Last.fm…) open in your browser
- Imports can pick files from the phone, and exports download to the Downloads folder
- The player keeps playing when you switch apps, with lock-screen controls, as in Chrome

## What's coming

1. Playback through Android's own media player, so music keeps going reliably in the background and with Bluetooth and headset buttons
2. Downloads, to keep albums and playlists on the phone and play them offline
3. Android Auto
4. Google Play

## Build

You need JDK 17 or newer and the Android SDK (Android Studio installs both). Put the SDK path in `local.properties` as `sdk.dir=…`, or set `ANDROID_HOME`, then:

```
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Every push to `main` also builds one on GitHub Actions; it's attached to the run as `soundscape-debug-apk`.

## Signing

Releases are signed with one key, so each new version installs over the previous one. Create it once, keep it safe, and never commit it:

```
keytool -genkeypair -v -keystore soundscape-release.keystore -alias soundscape -keyalg RSA -keysize 4096 -validity 10000
```

Then add four repository secrets under Settings → Secrets and variables → Actions:

- `SOUNDSCAPE_KEYSTORE_BASE64`: the output of `base64 -w0 soundscape-release.keystore`
- `SOUNDSCAPE_KEYSTORE_PASSWORD`: the keystore password
- `SOUNDSCAPE_KEY_ALIAS`: `soundscape`
- `SOUNDSCAPE_KEY_PASSWORD`: the key password (the same as the keystore password unless you chose another)

Pushing a tag like `v0.1.0` that matches `versionName` in `app/build.gradle.kts` then builds, signs and publishes the APK on the Releases page.

## License

AGPL-3.0, like Soundscape.
