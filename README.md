# lapdog-android

Companion Android app for the LapDog FPV lap timer. It receives lap frames over **USB** (timer plugged in, via `usb-serial-for-android`) and **BLE** (advert bursts, scanned from a foreground service so it keeps working with goggles on and the screen off), then shows lap times.

**Status:** dark race-HUD UI from the design canvas: idle (practice/race), armed practice, race with the "From the timer" message panel, and settings that write back to the timer over USB. Not yet tried against a real timer; validate the BLE burst with nRF Connect first.

False-read protection lives on the timer (minimum lap time, ignore parked quads); the app edits it in Settings and shows passes the timer ignored as a red note per heat.

Every gate pass the timer counts plays a **lap sound** on the phone: Bark (the default), Tone, Ding, Bell, Chirp, Double beep or Off, picked at the top of Settings and kept on the phone. It plays from the foreground service, so it works with the screen off, on the media volume. The bark is a real German shepherd recording (CC0); the rest are synthesised by `tools/gen-sounds.py` (numpy) into `res/raw/`. Sources and licences are in `sounds-licenses/`.

The **timer** is the source of truth for event name, heat prefix and heat number. The app reads them (USB `get`, or BLE `TEXT`/`ARMED` frames) and only changes them by sending `set …` + `save`. Radio output is exclusive: with the timer on ESP-NOW the app hears it only over USB.

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

Needs `local.properties` with `sdk.dir=` pointing at your Android SDK.

## Releases

The website offers the app at https://lapdog.funkynerd.com/app, from this repo's latest release. Cut one from an up-to-date `main`:

```bash
tools/release.sh 0.2.0
```

It sets `versionName`, bumps `versionCode`, commits, tags `v0.2.0` and pushes. The tag runs `.forgejo/workflows/release.yml`: unit tests, a signed `assembleRelease`, and a Forgejo release with `lapdog.apk` and `lapdog.apk.sha256`. A suffixed tag (`0.2.0-beta.1`) is a prerelease, which the website ignores.

Release builds are signed with the LapDog release key, from the repo secrets `LAPDOG_KEYSTORE_B64` and `LAPDOG_KEYSTORE_PASSWORD`. Make the key and store them once with:

```bash
FORGEJO_TOKEN=<token with write:repository> tools/make-signing-key.sh
```

It keeps the key in `~/.config/lapdog-android/`. **Back that folder up.** Every release must be signed with the same key, or Android won't install it as an update. Locally, `LAPDOG_KEYSTORE` (path), `LAPDOG_KEYSTORE_PASSWORD` and optionally `LAPDOG_KEY_ALIAS` sign `assembleRelease` the same way; without them it builds unsigned.

**Website sync** (Settings > Website sync) uploads finished heats to the pilot's account on lapdog.funkynerd.com with the same device API LapDog LCD uses (lapdog-web `docs/dev/device-api.md`). Pair with a code from the website's Devices page. Then every heat with at least one lap, practice or race, goes up as a race: after each heat ("Sync after each heat", on by default, via WorkManager so it waits for a network and retries), or with **Sync now** there or on the Heats screen. Race numbers come from the website's `counters` (shared with the pilot's LCD dashboards, and pushed past every number used), not the phone's heat ids. They're kept per heat in `files/sync.json`, so a retry reuses its number.

**Update notification:** once a day (and on opening the app, at most every 6 hours) the app asks the website's `/app/latest.json` for the latest release. If it's newer, it posts a notification (once per version) and shows a banner on the timer screen until it's dismissed. Either one opens the APK download on the website, and Android's installer takes it from there.

## Layout

| Path | What |
| --- | --- |
| `protocol/LapFrame.kt` | Frame format v2, mirrors `lap_frame.h`; tests share its hex vectors |
| `protocol/TimerLine.kt`, `TimerSettings.kt` | USB console lines (`F:`/`S:`/`R:`/OK) and the settings diff → `set` commands |
| `protocol/TextAssembler.kt`, `Channels.kt`, `ElrsUid.kt`, `Format.kt` | Chunked text, band table, bind-phrase UID, time formatting |
| `state/TimerModel.kt`, `LapStats.kt` | Pure reducer from frames to UI state (dedupe across USB+BLE by seq) and lap stats |
| `transport/UsbTransport.kt` | CDC-ACM on Espressif 303A:1001, reads lines and writes commands, reconnects |
| `transport/BleTransport.kt` | One long-running scan, `ScanFilter` on manufacturer ID 0xFFFF, low latency |
| `service/` | Foreground service running both transports; `LapRepository` state + command queue; `LapSounds` (lap sound choice + SoundPool) |
| `sync/` | Website sync: `WebApi` (device API over HTTPS), `RaceUpload` (heat → race body), `SyncState` (pairing + numbering, `files/sync.json`), `WebSync` + `SyncWorker` |
| `update/` | `AppUpdates` (version check, banner state, notification) + the daily `UpdateWorker` |
| `ui/` | Theme (bundled Chakra Petch / IBM Plex Sans / JetBrains Mono, OFL in `fonts-licenses/`) and screens |

ESP-NOW can't be received on Android directly. Use LapDog LCD or another ESP32 as a USB bridge if needed.
