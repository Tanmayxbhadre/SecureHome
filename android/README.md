# SecureHome for Android

The phone app, the same on both phones:

- **Start or join a household.** The first phone starts it (with the relay's founding code); other
  phones join by showing a QR code that a phone already in the household scans.
- **Send.** Spots OTPs in incoming SMS, encrypts them on the phone and sends them to the devices whose
  auto-share switch is on (on by default for the first phone, off for phones that join). Any code from
  the last 2 days of the SMS inbox can also be shared by hand, to the devices you pick.
- **Get OTP.** Shows codes shared with this phone in the last 5 minutes and stays live (WebSocket) for 5 more.
  Codes shared with this phone also show under *Shared with me*.

Protocol: `../PROTOCOL.md`.

## Build and install

Needs JDK 17 and the Android SDK. Set `ANDROID_HOME`, or put `sdk.dir=/path/to/android-sdk` in
`local.properties` (this machine-specific file is git-ignored).

| Build | Package | Use |
| --- | --- | --- |
| release | `com.tanmay.securehome` | The phones. Signed with the SecureHome release key. |
| debug | `com.tanmay.securehome.debug` | Testing. Installs alongside release, can reach a relay on your development machine, accepts fake SMS from adb. |
| qa | `com.tanmay.securehome.qa` | The release code exactly as shipped (R8-shrunk, not debuggable) plus the debug test hooks, signed with the debug key. Run the flows on it before installing a release build on a phone. |

```sh
./scripts/make-release-key.sh    # once: creates ~/.securehome/release.jks + keystore.properties; back them up
./gradlew testDebugUnitTest      # OTP fixtures + crypto vectors shared with the JavaScript code
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

**The release key is the app's identity.** Android only installs an update signed with the same key, so
if `~/.securehome` is lost, updating means uninstalling, which deletes the household keys on that phone.
Without `~/.securehome/keystore.properties` (or `$SECUREHOME_SIGNING`) release builds come out unsigned. Release
builds are minified and resource-shrunk with R8, signed with the v1, v2 and v3 schemes, and get their
version from git (below); CI builds the release variant to catch R8 regressions.

### Versioning

Versions come from the UTC time of the latest git commit, so each commit builds a newer,
installable version without a manual bump: `versionName` is `yymmdd.HHMM` (for example
`261122.0909`) and `versionCode` is minutes since 2020-01-01. A source tarball without git
history falls back to the build time. Debug builds append `-debug` to the version name.

Installing with `adb install` matters. Android treats SMS access (receive and read) as a restricted
permission and only lets the installer grant it. adb does; a downloaded APK opened from Files would need
"Allow restricted settings" in the app's info page first.

The app's relay client against a running local relay (founds, pairs, sends, leaves):

```sh
SECUREHOME_RELAY=http://127.0.0.1:8787 ./gradlew testDebugUnitTest --tests '*RelayIntegrationTest*'
```

That includes a second phone joining by QR, Get OTP's live connection, and sharing in both directions.

Cross-checking crypto with JavaScript (run after the unit tests):

```sh
cd ../relay && node scripts/vectors.mjs --check ../android/app/build/kotlin-vectors.json
```

## Why targetSdk is 36

Android 17 holds SMS that contain an OTP back for three hours from apps that **target API 37 or
higher** (both the `SMS_RECEIVED` broadcast and SMS database queries). Staying on 36 avoids that. The app
is sideloaded, so Play's target-API deadlines don't apply. SMS in the SMS Retriever (`<#> … hash`) or
WebOTP formats are held for every app regardless; those are rare for bank OTPs.

## Testing against a local relay (debug builds)

Debug builds allow plain HTTP to `127.0.0.1` and accept fake SMS from adb (only adb's shell user
holds the DUMP permission the receiver requires).

```sh
cd ../relay && npm run dev                # relay on :8787
adb reverse tcp:8787 tcp:8787             # phone's 127.0.0.1:8787 → your development machine
# In the app: founding code local-dev-founding-code, Relay settings → http://127.0.0.1:8787 → Start household
node scripts/laptop.mjs                   # prints PAIRING text; paste it in Devices → Pair → Can't scan?
adb shell am broadcast -a com.tanmay.securehome.DEBUG_SMS -n com.tanmay.securehome.debug/com.tanmay.securehome.sms.DebugSmsReceiver \
  --es from VM-HDFCBK --es body "'123456 is your OTP for HDFC Bank. Do not share.'"
```

### A second phone on the emulator

Without a second real phone, an emulator plays it. From an emulator, the host machine is `10.0.2.2`
(allowed for plain HTTP in debug builds), which is more reliable than `adb reverse`.

```sh
emulator -avd <AVD name> -no-window &               # any Play Store system image works
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk     # installs as "SecureHome debug"
cd ../relay && node scripts/phone.mjs init --state .phone-m3.json        # "my phone" on the CLI
# Emulator: Relay settings → http://10.0.2.2:8787 → Join a household → "Can't scan? Show the pairing text"
node scripts/phone.mjs pair "securehome:1:…" --name "Second phone" --state .phone-m3.json --yes
node scripts/phone.mjs send "Share OTP 4821 with the delivery partner." --from VM-SWIGGY --to "Second phone" --state .phone-m3.json
adb -s emulator-5554 emu sms send AXICICIT "482913 is the OTP for your ICICI Bank Credit Card. Do not share."
node scripts/phone.mjs inbox --state .phone-m3.json  # after sharing it from the second phone's history
```

## Where things live

| Path | What |
| --- | --- |
| `gradle/libs.versions.toml` | Single source of truth for plugin and dependency versions |
| `app/proguard-rules.pro` | R8 rules: keeps the WorkManager worker and crash stack-trace lines |
| `core/Crypto.kt` | Protocol crypto (P-256, HKDF, AES-GCM, P1363 signatures, roster checks). Pure JVM. |
| `core/OtpDetector.kt` | Port of `relay/scripts/lib/otp.mjs`. |
| `core/Household.kt` | Relay client, founding, pairing, sending, opening received codes. |
| `core/Joining.kt` | The joining phone's side of pairing (shows the QR, checks the reply). |
| `core/Live.kt` | WebSocket client for Get OTP. |
| `sms/` | SMS receiver, background sharing worker, inbox reader. |
| `ui/` | Jetpack Compose UI (Theme, Screens, MainViewModel). |
