# Devplan: update a timer's firmware from the app over USB

Tracks [lapdog-android#3](https://git.hazaar.dev/jamie/lapdog-android/issues/3). Today a timer is
updated with the website's browser flasher, which needs a computer running Chrome or Edge. The app
already talks to the timer over USB, so it can write the new firmware itself, using the same
serial bootloader protocol as esptool.

**Goal:** plug an out-of-date timer into the phone, tap **Update timer**, and get it back running
the new version, with its settings, heat counter and (on the touch timer) Wi-Fi networks,
website pairing and stored heats untouched.

**Out of scope:**
- Over-the-air updates, with the timer downloading its own firmware. They'd need two app
  partitions on the touch timer, and they don't help the mini.
- The LapDog LCD, which already updates over the air.
- The C3 OLED test timer, which is no longer offered in the flasher.

---

## How it works

```
App                                   Timer (ESP32-C5, USB Serial/JTAG 303A:1001)
 │  get  ─────────────────────────────▶  S:fw=0.4.2  S:board=c5-touch  S:state=idle
 │  (website: latest.json says 0.4.3 for c5-touch; download firmware.bin, check SHA-256)
 │  bootloader  ──────────────────────▶  sets FORCE_DOWNLOAD_BOOT, esp_restart()
 │                                       ── restarts into the ROM's download mode ──
 │  SYNC ×n  ─────────────────────────▶  ROM loader answers
 │  SPI_ATTACH, SPI_SET_PARAMS
 │  FLASH_DEFL_BEGIN @0x10000 (erases the app slot)
 │  FLASH_DEFL_DATA ×N (1 KB blocks)  ─▶  progress
 │  SPI_FLASH_MD5  ───────────────────▶  checks against the image's MD5
 │  WRITE_REG (clear force-download), FLASH_DEFL_END(reboot)
 │                                       ── restarts into the new firmware ──
 │  get  ─────────────────────────────▶  S:fw=0.4.3   done
```

Only the **app image** (`<device>-firmware.bin`) is written, at `0x10000`. That's the same
offset on both boards (`partitions.csv`, `partitions_touch.csv`). The bootloader (`0x2000` on the
C5), the partition table and NVS (`0x9000`) are never touched, so:
- everything saved survives the update;
- a failed write still leaves a bootloader that can get the timer into download mode, so the
  update can be retried or the browser flasher used instead.

### Getting into download mode: three ways, best first

1. **Firmware command (new):** a `bootloader` console command sets
   `LP_AON_SYS_CFG_REG.FORCE_DOWNLOAD_BOOT = 01` and restarts the chip. This is the same thing
   ESP-IDF's USB console does on chips with USB-OTG (`esp_system/port/usb_console.c`). It needs
   no DTR/RTS timing and no BOOT button, and it works for any firmware from the release that
   adds it.
2. **DTR/RTS sequence (any firmware):** esptool's USB-Serial/JTAG reset, from
   `esp_pylib.serial_reset.usb_jtag_bootloader_reset`, in pyserial terms (True = asserted).
   Each step is held 100 ms:

   | Step | DTR | RTS |
   |---|---|---|
   | Idle | off | off |
   | Hold IO0 | on | off |
   | Reset | off | on |
   | Release | off | off |

   `usb-serial-for-android` sends `SET_CONTROL_LINE_STATE` with both lines together, so it can
   reproduce this exactly. PlatformIO uploads to both boards use this sequence from Linux without
   BOOT. Whether it works from Android is spike question 2.
3. **BOOT held (always works):** "Hold BOOT and plug the timer in again." This is the browser
   flasher's instruction, and the fallback whenever 1 and 2 fail.

Clearing the force-download bit before the final reset matters. Without that, the chip may keep
restarting into download mode; esptool clears the equivalent bit on the S3 before it resets.

### ROM loader, not a stub

Flashing a 0.7–3 MB app image is fine with the ROM loader alone. A stub would mean shipping and
uploading another binary for no real gain.

**ROM loader notes for the C5** (`esptool/loader.py`, `targets/esp32c5.py`):
- **Framing:** SLIP (`0xC0` ends a frame; `0xDB 0xDC` / `0xDB 0xDD` escape bytes).
- **Requests:** `00 op len16 checksum32 data`.
- **Responses:** `01 op len16 value32 data…`, with the last 2 bytes a status word (0 = ok).
- **Opcodes:**

  | Command | Opcode |
  |---|---|
  | SYNC | 0x08 |
  | WRITE_REG | 0x09 |
  | READ_REG | 0x0A |
  | SPI_SET_PARAMS | 0x0B |
  | SPI_ATTACH | 0x0D |
  | FLASH_DEFL_BEGIN | 0x10 |
  | FLASH_DEFL_DATA | 0x11 |
  | FLASH_DEFL_END | 0x12 |
  | SPI_FLASH_MD5 | 0x13 |
  | GET_SECURITY_INFO | 0x14 |

- **SYNC:** `07 07 12 20` followed by 32 × `0x55`. Retry it about 10 times, and drain the
  replies before the next command.
- **Identify the chip:** `GET_SECURITY_INFO` returns its chip id. The C5's is 23, the same as
  `IMAGE_CHIP_ID`. Refuse to flash if the chip doesn't match the image's board.
- **FLASH_DEFL_BEGIN** takes five values:
  - `write_size`: the uncompressed size rounded up to a whole 1 KB block, as the ROM expects;
  - `num_blocks`: the number of compressed blocks;
  - `block_size`: `0x400`;
  - `offset`: `0x10000`;
  - `encrypted`: 0.

  The ROM erases the whole region up front, so the response timeout scales with size: esptool
  allows about 30 s per MB.
- **Blocks:** each FLASH_DEFL_DATA carries `len, seq, 0, 0` followed by the data. The request's
  checksum is `0xEF` XORed with each data byte.
- **Compression:** `java.util.zip.Deflater` (zlib format, the same as Python's
  `zlib.compress`).
- **SPI_SET_PARAMS:** 16 MB on the touch timer and 4 MB on the mini. Without it the ROM may refuse
  writes past its default size.
- **Expected speed:** about 15–30 s for the mini's 740 KB image. Measure it in the spike.

---

## Phase 0: spike (go/no-go before any UI)

Answer these on real hardware, the mini and the touch timer:

1. **Does the `bootloader` command work?** Build it into a test firmware, send it from Linux,
   then run `esptool --before no_reset chip_id`. Check that the chip enters download mode without
   BOOT, whether the port re-enumerates, and that clearing the bit and resetting boots the app
   normally.
2. **Does the DTR/RTS sequence work from Android?** Try it with `usb-serial-for-android` on
   Jamie's phone, with a throwaway debug screen or an instrumented test that only SYNCs and reads
   the chip id. This decides whether older firmware (≤ 0.4.2) can update without BOOT.
3. **Is ROM throughput over Android acceptable?** Write the mini's image once from a debug build,
   then check it with `esptool verify_flash`.
4. **What happens to USB permission?** Find out whether the device keeps it through the reset
   into download mode and the reset back, or whether Android asks again.

**Done when:** each question has a written answer in this file, and the chosen entry method is
confirmed.

### Spike results so far (2026-10-28, C5 mini, from Linux)

**Question 1: entering download mode works.** A test build's `bootloader` command (not committed)
does three things:
1. sets `FORCE_DOWNLOAD_BOOT = 01`;
2. waits for the reply to go out;
3. calls `esp_restart()`.

The ROM printed `rst:0xc (SW_CPU),boot:0x8 (DOWNLOAD(UART0/USB))` and "waiting for download". The
port stayed open and didn't re-enumerate, so USB permission wouldn't be lost. With no BOOT button
and no DTR/RTS sequence, esptool `--before no_reset --no-stub` then:
- identified the chip;
- wrote the mini's 1.29 MB app image at `0x10000`, a 2.1 s erase and a 10.5 s write, about 15 s
  in all;
- verified it.

After a power-on reset the image written by the ROM boots and runs normally.

**Leaving download mode is the problem.** Nothing tried brings the app back with working USB,
short of a power-on reset (RST, or unplugging):

| Exit tried | Result |
|---|---|
| ROM "run user code" (FLASH_END, as esptool's `soft_reset`) | The chip stops reading USB (the host's writes time out) |
| RTS reset, force-download field still set | Back into download mode: `LP_AON_SYS_CFG_REG = 0x28000000`, bit 29 set, status bit 27 set. **The field survives a chip reset, so it must be cleared.** |
| Field cleared with WRITE_REG, then esptool's USB hard reset (DTR asserted by the open) | Re-enumerates, back into download mode |
| Field cleared, RTS reset with DTR held off | `rst:0x15 (USB_UART_HPSYS), boot:0x1e (SPI_FAST_FLASH_BOOT)`: the bootloader loads the app, then USB goes dead. The app prints nothing and never reads. |

**nerd-deck ruled out:** the system monitor's host software, nerd-deck, was found holding the
timer's port during some runs. With it closed and nothing else on the port, the dead state
reproduced: field cleared, then esptool's USB hard reset. The chip didn't re-enumerate, and the
host's writes timed out. It's the chip, not the test computer.

In this state the mini's LED is off or solid, not its idle blink, so **the app hangs early in
startup**. It isn't running with only USB broken.

**Comparison (b): the `bootloader` command, esptool's stub, field cleared, hard reset.** It fails
the same way:
1. The stub writes the image in 5.5 s (760 KB compressed) and verifies it.
2. The field reads 0, and the hard reset gives
   `rst:0x15 (USB_UART_HPSYS), boot:0x18 (SPI_FAST_FLASH_BOOT)`.
3. The app hangs, and USB doesn't come back.

PlatformIO's uploads use the same stub and the same hard reset, but esptool's DTR/RTS entry, and
they always come back. So **the stub isn't the fix: entering through `FORCE_DOWNLOAD_BOOT` is
what breaks the way out**. Until that's solved, method 1 isn't usable.

**Comparison (a): esptool's DTR/RTS entry, the ROM loader (no stub), hard reset. It works.**
1. The ROM writes the image in 10.9 s and verifies it.
2. The hard reset re-enumerates the device (after the force-download entry it didn't), and the
   app boots.
3. The app answers `get` straight away.

**What this means for the plan:**
- **Method 2 (the DTR/RTS sequence) is the one to build on:** enter download mode as esptool
  does, flash with the ROM loader (no stub needed), and leave with esptool's USB hard reset. Spike
  question 2, whether the same sequence works through `usb-serial-for-android`, is now the
  deciding question.
- **Method 1 (`FORCE_DOWNLOAD_BOOT` + `esp_restart()`) stays out until it can leave cleanly.**
  One idea to try later: set the field, then make a full chip reset (for example the RTC
  watchdog's system-reset stage) rather than `esp_restart()`'s CPU reset. That would make the
  later RTS reset a full one too, as it is after method 2's entry.
- **Recovery is still RST or unplugging,** so the app's failure screen should say so.

The spike's `bootloader` command was never committed. It's kept as a patch for the next try. PlatformIO uploads don't hit this, because they use the stub
loader and esptool's DTR/RTS entry rather than the ROM loader and the force-download field.
Still to find out which of the two makes the difference:
- (a) esptool's DTR/RTS entry, `--no-stub`, hard reset;
- (b) the `bootloader` command, esptool's stub (`--before no_reset`, stub on), hard reset.

If the stub is what fixes it, the app ships esptool's C5 stub (a few KB, GPL-2.0, like esptool)
and uploads it with MEM_BEGIN/MEM_DATA/MEM_END. If neither helps, the update ends with "Unplug the
timer and plug it back in", which cycles power on a USB-powered mini but not on a touch timer
running on its battery.

## Phase 1: firmware (lapdog-firmware)

- `get` adds `S:board=c5-mini|c5-touch|c3-oled`, the device ids from `devices.json`. Set it in
  `include/config.h` from the build env flags (`LAPDOG_TOUCH`, `LAPDOG_OLED`, otherwise the mini).
- New console command `bootloader`, accepted only while idle: reply `OK`, flush stdout, set
  `FORCE_DOWNLOAD_BOOT`, then `esp_restart()`. Document it in `src/console.h`.
- Release it, the same way as v0.4.2. Timers from before this release fall back to entry methods 2
  and 3, and the app asks which board they are.

## Phase 2: website (lapdog-web)

- **Release images** (`tools/build-flash-images.py`): `flash.json` gains the app image for each
  device: `firmware`, `firmwareSize`, `firmwareSha256`, plus its `md5` for the ROM's check.
  Releases already attach `<id>-firmware.bin`; only `flash.json` is missing these fields.
- **`App\Models\Firmware`:** read the new fields in `fetchFlashInfo()`. Add `appImage($version,
  $device)` beside `flashImage()`, stored in `firmware_images` with a new `kind` column
  ('factory' | 'app') and its own migration.
- **Routes** (`app/route.php`, `Controllers\Flash`):
  - `GET /flash/{device}/{version}/firmware.bin` serves the app image, with
    `X-Firmware-SHA256`.
  - `GET /app/firmware/latest.json` returns, for each timer device (lapdog-firmware's repo only,
    not the LCD's): `{id, name, version, size, sha256, md5, url}`.
- **Tests and docs:** a PHPUnit test for the device-list filtering, and the endpoint documented
  in `docs/dev/device-api.md`.
- Older releases' `flash.json` files have no app image, so those devices are left out of
  `latest.json` until the next timer release.

## Phase 3: app (lapdog-android)

**Pure protocol (unit-tested off device)**, in `flash/`:
- `Slip.kt`: encode, and a streaming decoder.
- `RomLoader.kt`: command building, checksums, response parsing and status errors, written
  against a small `SerialLink` interface (`write`, `read with timeout`, `setDtrRts`) so tests
  can run against a scripted fake ROM.
- `FlashPlan.kt`: compresses the image into 1 KB blocks and works out `write_size`, the block
  count and the MD5.
- Tests in `app/src/test/java/io/hazaar/lapdog/flash/`:
  - SLIP round trips, including the escape bytes;
  - known-good request bytes captured from esptool's `--trace` output;
  - the fake ROM answering SYNC, BEGIN, DATA and MD5;
  - error paths: a bad status, a timeout, an MD5 mismatch.

**Who owns the port:** `UsbTransport` is the only thing that opens the timer's port, and it
should stay that way. Add a lease:
- `LapRepository.flashTimer(job)` asks the transport to hand over its port.
- `session()` ends cleanly, `onUsb(false)` is not reported as an unplug, and the transport calls
  `job(port)` in its place.
- When the job finishes, or the device re-enumerates, the transport goes back to its normal
  poll, reopens the port and sends `get`.
- No BLE or USB arm commands go out while the job runs.

**Update flow:** `update/TimerUpdates.kt`, modelled on `AppUpdates.kt`:
- Check `/app/firmware/latest.json` when the timer connects over USB, at most once an hour, and
  compare it with `S:fw` using the existing `isNewer()`.
- No `S:board`: ask once ("Which timer is this?", listing the website's devices), and remember the
  answer by the timer's USB serial number.
- Download to `cacheDir/firmware/<device>-<version>.bin` and check the size and SHA-256 before
  touching the timer.
- Checks before anything starts:
  - `S:state=idle`;
  - the image fits the board's app slot (`0x1F0000` on the mini, `0x600000` on the touch timer);
  - the chip id matches.
- **Steps:**
  1. Enter download mode, using method 1, 2, then 3, whichever works first.
  2. SYNC.
  3. Attach and set the flash size.
  4. Write.
  5. Verify the MD5.
  6. Clear the force-download bit and restart.
  7. Wait for the port to come back and send `get`.
  8. Confirm `S:fw`.
- Keep the screen on, and don't let the job be cancelled mid-write without a warning.

**UI** (`ui/SettingsScreen.kt`, Timer section):
- An "Update timer to 0.4.3" card whenever an update is available, and a one-line hint on the
  idle timer screen.
- A progress sheet with its stages: downloading, starting install mode, erasing, writing N %,
  checking, restarting.
- **On failure:** the reason, plus **Try again** and **Use the browser flasher**, which opens the
  website's flash page.
- **BOOT fallback:** an illustrated "Hold BOOT, unplug and plug the timer back in", then the flow
  resumes when the ROM answers SYNC.
- **Reinstall from scratch** (the factory image at `0x0`): keep this out of v1. If it's added
  later, it needs a clear warning that it erases everything.

## Phase 4: release order

1. Firmware with `S:board` and `bootloader`.
2. The website, once that firmware release is published, so `latest.json` has app images.
3. The app.

The first app update a pilot runs takes them from ≤ 0.4.2 to the new firmware, so it relies on
method 2 or 3. From then on, method 1 handles every update.

---

## Risks

- **`FORCE_DOWNLOAD_BOOT` is undocumented on the C5:** the register file says "internal debugging
  purposes". If the spike shows it doesn't work, the app relies on methods 2 and 3, and method 3
  always works.
- **Android USB stacks vary:** some phones drop the device or the permission on re-enumeration.
  The transport already recovers from unplugs, but test on a second phone if possible.
- **A dying battery or a pulled cable mid-write** leaves a timer without a working app. The
  bootloader is still intact, so it shows up in download mode with BOOT held: say so on screen,
  and document it in the README.
- **The touch timer's image is large:** it uses a 6 MB slot and the build is several MB, so
  writing it may take a minute or more. Show an honest time estimate.

## Done when

- A mini and a touch timer on the previous release both update from the app, from **Update
  timer** to the new `S:fw`, and keep:
  - their settings and heat counter;
  - on the touch timer, Wi-Fi networks, website pairing and the heats waiting on the SD card.
- A timer on older firmware (no `S:board`, no `bootloader`) updates with method 2, or with
  method 3 if 2 fails.
- An update interrupted by pulling the cable mid-write can be finished with **Try again**.
- The unit tests above pass, and `./gradlew test` runs in CI.
