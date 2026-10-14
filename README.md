# lapdog-android

Companion Android app for the LapDog FPV lap timer. It receives lap frames over **USB** (timer plugged in, via `usb-serial-for-android`) and **BLE** (advert bursts, scanned from a foreground service so it keeps working with goggles on and the screen off), then shows lap times.

**Status:** dark race-HUD UI from the design canvas: idle (practice/race), armed practice, race with the "From the timer" message panel, and settings that write back to the timer over USB. Not yet tried against a real timer; validate the BLE burst with nRF Connect first.

The **timer** is the source of truth for event name, heat prefix and heat number. The app reads them (USB `get`, or BLE `TEXT`/`ARMED` frames) and only changes them by sending `set …` + `save`. Radio output is exclusive: with the timer on ESP-NOW the app hears it only over USB.

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

Needs `local.properties` with `sdk.dir=` pointing at your Android SDK.

## Layout

| Path | What |
| --- | --- |
| `protocol/LapFrame.kt` | Frame format v2, mirrors `lap_frame.h`; tests share its hex vectors |
| `protocol/TimerLine.kt`, `TimerSettings.kt` | USB console lines (`F:`/`S:`/`R:`/OK) and the settings diff → `set` commands |
| `protocol/TextAssembler.kt`, `Channels.kt`, `ElrsUid.kt`, `Format.kt` | Chunked text, band table, bind-phrase UID, time formatting |
| `state/TimerModel.kt`, `LapStats.kt` | Pure reducer from frames to UI state (dedupe across USB+BLE by seq) and lap stats |
| `transport/UsbTransport.kt` | CDC-ACM on Espressif 303A:1001, reads lines and writes commands, reconnects |
| `transport/BleTransport.kt` | One long-running scan, `ScanFilter` on manufacturer ID 0xFFFF, low latency |
| `service/` | Foreground service running both transports; `LapRepository` state + command queue |
| `ui/` | Theme (bundled Chakra Petch / IBM Plex Sans / JetBrains Mono, OFL in `fonts-licenses/`) and screens |

ESP-NOW can't be received on Android directly. Use RacePanel or another ESP32 as a USB bridge if needed.
