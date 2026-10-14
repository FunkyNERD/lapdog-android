# lapdog-android

Companion Android app for the LapDog FPV lap timer. It receives lap frames over **USB** (timer plugged in, via `usb-serial-for-android`) and **BLE** (advert bursts, scanned from a foreground service so it keeps working with goggles on and the screen off), then shows lap times.

**Status: skeleton.** The transports, the shared frame parser and a minimal Compose UI are in place. The firmware's BLE burst is still a TODO, so validate it with nRF Connect first.

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

Needs `local.properties` with `sdk.dir=` pointing at your Android SDK.

## Layout

| Path | What |
| --- | --- |
| `protocol/LapFrame.kt` | The one frame format, which mirrors `lap_frame.h` in lapdog-firmware. The unit test shares its byte vector. |
| `transport/UsbTransport.kt` | CDC-ACM on Espressif 303A:1001, reads `F:<hex>` lines |
| `transport/BleTransport.kt` | One long-running scan, `ScanFilter` on manufacturer ID 0xFFFF, low latency, de-dupe by seq |
| `service/LapService.kt` | Foreground service (`connectedDevice`) running all transports |
| `ui/MainActivity.kt` | Status, last lap, lap list |

ESP-NOW can't be received on Android directly. Use RacePanel or another ESP32 as a USB bridge if needed.
