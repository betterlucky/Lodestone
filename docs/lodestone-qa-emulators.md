# Lodestone Android QA Emulators

Use these profiles for local screenshot and interaction smoke checks.

## Profiles

| Name | Device | System image | Purpose |
| --- | --- | --- | --- |
| `Lodestone_API35_phone` | `pixel_7` | `system-images;android-35;google_apis;arm64-v8a` | Primary portrait phone QA for Now, Journal, History, and Settings. |
| `Lodestone_API35_tablet` | `pixel_tablet` | `system-images;android-35;google_apis_tablet;arm64-v8a` | Wide-layout scan for wrapping, cards, and Settings/device discoverability. |

On x86_64 hosts, replace `arm64-v8a` with `x86_64`.

## Install SDK Packages

```bash
yes | sdkmanager \
  "emulator" \
  "system-images;android-35;google_apis;arm64-v8a" \
  "system-images;android-35;google_apis_tablet;arm64-v8a"
```

This Codex session installed the `emulator` package and both Android 35 ARM64 system images under `/opt/homebrew/share/android-commandlinetools`, then created both AVDs. The earlier setup looked stuck because `sdkmanager` prints little after `Preparing` while downloading multi-GB zips:

- phone image: `arm64-v8a-35_r09.zip`, about 1.78 GB compressed, about 3.8 GB installed
- tablet image: `arm64-v8a-35_r02.zip`, about 1.99 GB compressed, about 4.1 GB installed

For large image downloads, prefer a 30-minute timeout rather than a 10-minute timeout on this connection.

If the Android 35 image repeatedly stalls, use the matching Android 34 package as a temporary fallback:

```bash
yes | sdkmanager "system-images;android-34;google_apis;arm64-v8a"
avdmanager create avd \
  --force \
  --name Lodestone_API34_phone \
  --package "system-images;android-34;google_apis;arm64-v8a" \
  --device "pixel_7"
```

## Create AVDs

```bash
avdmanager create avd \
  --force \
  --name Lodestone_API35_phone \
  --package "system-images;android-35;google_apis;arm64-v8a" \
  --device "pixel_7"

avdmanager create avd \
  --force \
  --name Lodestone_API35_tablet \
  --package "system-images;android-35;google_apis_tablet;arm64-v8a" \
  --device "pixel_tablet"
```

## Run Screenshot Smoke

Start an AVD:

```bash
SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
"$SDK_ROOT/emulator/emulator" \
  -avd Lodestone_API35_phone \
  -no-snapshot \
  -no-boot-anim
```

Then run:

```bash
scripts/lodestone_screenshot_smoke.sh
```

When more than one Android device is connected, set `ADB_SERIAL`:

```bash
ADB_SERIAL=emulator-5556 scripts/lodestone_screenshot_smoke.sh
```

Host requirements: `adb`, `python3`, and a connected authorised emulator/device. The script builds and installs the debug APK before capture.

By default the smoke script stops Gradle daemons on exit so Codex-run QA does
not leave multi-GB Java processes behind. Set `LODESTONE_STOP_GRADLE=0` only
when you deliberately want a warm Gradle daemon. The script does not kill the
global ADB server by default; set `LODESTONE_KILL_ADB_SERVER=1` for a fully
self-cleaning run when Android Studio or another shell is not depending on ADB.

Artifacts are written to `build/qa-screenshots/<timestamp>/`:

- `00-launch.png/xml`
- `01-now.png/xml`
- `02-journal.png/xml`
- `03-history.png/xml`
- `04-settings.png/xml`

The script prefers uiautomator text/content-description taps for `Now`, `Journal`, `History`, and `Settings`, with screen-size coordinate fallbacks only when the hierarchy is unavailable.

Check that the Settings capture exposes the Loop device card with Scan, Connect,
Disconnect, selected device, connection, battery, firmware, and controlled Flow
maintenance copy.
