# Soundscape for Android

The Android app for [Soundscape](https://github.com/novakr12/soundscape), the self-hosted music tracker. It opens your own Soundscape server in a full-screen app, the way the [desktop app](https://github.com/novakr12/soundscape-desktop) does on Linux.

## Install

Download `soundscape-<version>.apk` from the [Releases page](https://github.com/novakr12/soundscape-android/releases) on your phone and open it. Android asks once to allow installs from your browser or file manager; allow it, install, and turn it back off if you like. Later versions install over the top and keep your login.

The app needs Android 8.0 or newer.

## First start

Type the address you open Soundscape at, for example `music.example.com`. The app adds `https://` when you leave it out, checks that a Soundscape server answers there, and remembers it. Then log in as you would in a browser.

Right after logging in the first time, Soundscape asks whether to connect the app. Connecting gives the app its own token, so it can keep your music playing when you leave it and show your notifications (see below). On Android 13 and newer it then asks once whether it may show notifications. You can see and revoke the token on Soundscape's Connect page. If you skip it, connect later from the app's settings (see below).

## Settings

The app's settings sit on Soundscape's own Settings page, under **This app**, next to your account and the player: background playback, notifications, connecting or disconnecting the app, and changing the server. They only show inside the app, and need an up-to-date Soundscape server. Long-press the app icon and choose **Settings** to go straight there.

**Disconnect** revokes the app's token on your server and turns off background playback and notifications until you connect again.

If the server can't be reached, the app shows a page with **Try again** and **Change server**, and long-pressing the icon offers **Change server** too.

## What it does now

- Your Soundscape pages, full screen, with the phone's back button going back a page
- Links to other sites (MusicBrainz, Wikipedia, Last.fm…) open in your browser
- Imports can pick files from the phone, and exports download to the Downloads folder
- While music plays, the usual media notification, the lock screen, Bluetooth and headset buttons control Soundscape's player
- When you leave the app or lock the screen, Android's own media player takes over from the same song and second, so playback keeps going reliably: it pauses for calls and other apps, stops when headphones are unplugged, plays albums without gaps, and carries on through the queue. Plays still count, the same way as in the page. When you come back, the page takes over again where Android's player is. This needs the app to be connected (see above); without it, the page keeps playing in the background on its own, as in Chrome.

## Downloads

Albums and playlists get a **Download** button in the app (in Soundscape's Music tab and on album pages), once the app is connected. The songs are saved in the app's own storage, with a progress notification, and continue after a lost connection or a restart. **Download only on Wi-Fi** holds them until you're on Wi-Fi.

Downloaded songs play from the phone: they don't use mobile data when you're online, and they play with no connection at all. Without a connection Soundscape's pages can't load, so the app has its own **Downloads** screen: long-press the icon and choose **Downloads**, press **Play downloads** on the "Can't reach your server" page, or **Manage downloads** under This app. It lists what's stored by album, with the space used, and plays or removes albums or single songs.

Songs you play offline still count: the app remembers each play with its time and sends them to Soundscape when it's back online.

## Android Auto

Soundscape shows up in Android Auto as a music app. Its four tabs are **New albums**, **Playlists**, **Shuffle** (50 random songs) and **Downloads**, and the search button and "play … on Soundscape" voice requests search your Navidrome library. Choosing a song plays the whole album or playlist from that song on. Playback in the car uses the same player as background playback, so plays count, Devices sees the phone, and when you open the app on the phone it continues from there.

It needs the app to be connected and Navidrome connected on Soundscape's Connect page. Without a connection the car shows your downloads only. Covers come through the app, so the car never sees your token.

Android Auto only lists apps installed from Google Play unless you turn on **Unknown sources** in Android Auto's developer settings: open Android Auto's settings on the phone, tap **Version** ten times, then in the menu (⋮) choose **Developer settings** and turn on **Unknown sources**.

## Devices

Soundscape's **Devices** list, in the player on any of your Soundscape windows, shows the phone as "Soundscape on" and its model name. From your computer you can see what the phone plays, pause it, skip, seek, change its volume, send it your music or take its music over, also while it plays in the background with the screen off. The app needs to be connected and **keep the music playing** turned on under **This app**.

The phone stays in the list while the app is open or playing. If you close it from the recent apps while nothing plays, it drops off after a couple of minutes. Music sent to the phone while the app is in the background starts within a few seconds, and when you open the app again it continues with that music.

## Notifications

Once the app is connected, it checks Soundscape for new notifications every 15 minutes, the most often Android lets apps check in the background, and only when the phone is online: new followers, someone passing you on the leaderboard, milestones and new releases from artists you play. Tapping one opens its page in the app. The first check after connecting only notes where things stand, so you don't get a flood of old ones, and more than three at once fold into one "more notifications" line.

Turn them off under **This app** on Soundscape's Settings page, or in Android's own settings for the app (long-press the icon → App info → Notifications), which also change how they show. If you revoke the app's token on Soundscape's Connect page, it stops checking and asks to connect again next time you open it.

Two more come through the same check, each with its own switch under **This app**:

- **Your week in music**, on Monday: plays, listening time, your most played artist and your place among friends. Tapping it opens the home page with the full recap. On by default.
- **Streak reminder**: at 9 pm, if you have a streak going and haven't played anything yet that day. Off by default.

## Update notice

Once a day the app looks at the latest release on GitHub. When a newer version is out, it shows one notification for it, and **This app** on the Settings page shows the new version with a **Download** button. Both open the release page, where you download the new APK and install it over the old one. Turn it off under **This app**.

## What's coming

1. F-Droid and Google Play

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
