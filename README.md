# Nuvio Watch

An Android app for a household that shares a Nuvio account. It shows:

- **Now**: what each profile is watching or watched last, with progress and time watched today.
- **Stats**: time watched, films, episodes, shows, a 30-day chart, time of day, most-watched titles and favourite genres, for everyone or one profile.
- **History**: everything each profile has watched, newest first.
- **Alerts**: a notification when a profile starts watching something, usually within about a minute.

It only **reads** your Nuvio account: it calls Nuvio's sync "pull" functions and never writes anything.

## How alerts work and their limits

The Nuvio app uploads watch progress to its server when the viewer **pauses, skips, finishes or closes the player**. It doesn't upload when playback starts. So Nuvio Watch:

- checks every profile's progress feed every 30 seconds to 2 minutes (you choose), while running as a background service with a small ongoing notification;
- alerts you when a profile has new progress from the last 15 minutes;
- sends only one "watching" alert per show per 90 minutes, so a binge doesn't buzz on every episode.

If someone presses play and watches without pausing or skipping, the alert comes when they first pause or stop.

## Building the APK (with GitHub, no Android Studio)

1. Create a new **private** repository on GitHub and upload this folder to it. You can drag and drop the files on GitHub's "Add file → Upload files" page, or use git.
2. Open the repository's **Actions** tab. The **Build APK** workflow runs on every push to `main`; you can also start it from there.
3. When the run finishes, download the APK. Once the signing key below is set up, each build is also published as a Release, and the newest one is always at
   `https://github.com/DetectiveSula/NuvioWatch/releases/latest/download/NuvioWatch.apk`
   (for anyone who can see the repository). Before that, download **NuvioWatch-apk** from the run's Artifacts section and unzip it.
4. Open the `.apk` on the phone and allow "install unknown apps" when asked.

During the build, the workflow downloads the latest official Nuvio APK from Nuvio's GitHub releases and reads Nuvio's **public** server key from it. This is the key every copy of the Nuvio app contains. It is never stored in this repository.

### Signing key (needed for Releases and for updates without uninstalling)

Each GitHub build is signed with a new temporary key, so to install a newer build you must uninstall the old one first. To avoid that, create a signing key once:

```bash
keytool -genkeypair -v -keystore nuviowatch.keystore -alias nuviowatch -keyalg RSA -keysize 2048 -validity 10000
```

```bash
base64 -w0 nuviowatch.keystore
```

Then add these repository secrets (**Settings → Secrets and variables → Actions**): `SIGNING_KEYSTORE_BASE64` (the base64 output), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`nuviowatch`) and `SIGNING_KEY_PASSWORD`.

## First run on the phone

1. Open Nuvio Watch. It finds Nuvio's server automatically: from the key added at build time or, if that's missing, from the official Nuvio app if it's installed on the phone.
2. Sign in with your Nuvio email and password.
3. Go to **Settings**, turn on **Watching alerts** and allow notifications.
4. Tap **Allow running in background** and **Allow precise timing**, so Android doesn't delay the checks.
5. Under **Alert me about**, switch off your own profile if you don't want alerts about yourself.

## Mac (Nuvio Desktop)

Nuvio Watch only runs on Android. For instant alerts when someone watches on a Mac, install the small background helper once. Paste the command from **Settings → Mac (Nuvio Desktop)** in the app into Terminal. It looks like this:

```bash
curl -fsSL https://raw.githubusercontent.com/DetectiveSula/NuvioWatch/main/mac-helper/get.sh | bash -s SETUP_CODE "Mac"
```

Then turn on **Discord Rich Presence** in Nuvio Desktop's settings and restart it. The helper receives what Nuvio Desktop would send to Discord and forwards play, pause and stop to the family. The Discord app isn't needed; if it's running, Nuvio talks to it instead. To remove the helper, run:

```bash
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/app.nuviowatch.helper.plist; rm -rf ~/Library/LaunchAgents/app.nuviowatch.helper.plist ~/Library/Application\ Support/NuvioWatch
```

## Self-hosted Nuvio servers

On the sign-in screen, tap **Use a different server** and enter your server's address. Nuvio Watch reads its settings from `/.well-known/nuvio`, or you can paste the key yourself.

## Project layout

- `core/`: plain Kotlin with no Android code. Contains the Nuvio client (sign-in, token refresh, profiles, progress, history, change feed), Cinemeta title lookups, statistics and alert rules. It has unit tests (`./gradlew :core:test`).
- `app/`: the Android app (Jetpack Compose UI, background service, notifications).
- `scripts/find_nuvio_key.py`: reads the public key from an official Nuvio APK during the build.

## Privacy

Everyone using the shared account should know their viewing is visible in this app and can trigger alerts.
