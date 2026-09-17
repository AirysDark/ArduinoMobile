# ArduinoMobile

Android app for editing Arduino sketches, compiling them with GitHub Actions, and flashing supported boards over USB OTG.

## v0.1 scope

- Arduino Uno (ATmega328P)
- Arduino Nano with the current bootloader
- Arduino Nano with the old bootloader
- Android USB Host / OTG serial access
- In-app sketch editor
- GitHub Actions compilation using Arduino CLI
- Direct STK500v1 flashing from Android
- Read-back verification after programming
- GitHub Actions APK build artifact

## How compilation works

The Android app dispatches `.github/workflows/compile-sketch.yml` with the selected FQBN and the sketch source. GitHub Actions runs Arduino CLI, installs the required Arduino core, compiles the sketch, and returns the generated firmware as a short-lived workflow artifact. The app downloads the HEX file and keeps it in memory for flashing.

Because workflow dispatch requires authentication, enter a GitHub fine-grained personal access token in the app with access to this repository and Actions read/write permission. Do not commit tokens to the repository.

## Flashing

Connect the Arduino to the Android device using a USB OTG adapter. Tap **Detect USB Arduino**, compile the sketch, then tap **Flash**. The app uses the Android USB Host API and `usb-serial-for-android`, then talks STK500v1 directly to the ATmega328P bootloader.

### Nano bootloader choice

If a Nano will not sync at 115200 baud, select **Arduino Nano - old bootloader**, which uses 57600 baud and the corresponding Arduino FQBN.

## Building the Android APK

Pushes to `main`, pull requests, and manual dispatches run `.github/workflows/android-build.yml`. The workflow builds a debug APK and uploads it as the artifact `ArduinoMobile-debug-apk`.

## Planned backends

ESP32/ESP8266, SAMD, RP2040 and other boards require different reset/bootloader/upload protocols. They should be implemented as separate upload backends rather than routed through STK500v1.
