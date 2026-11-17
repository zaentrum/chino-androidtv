# chino-androidtv

chino's app for Android TV: a Kotlin app with Jetpack Compose for TV
(`androidx.tv:tv-material`), driven by the remote's D-pad. A person browses and
searches the films and series of a zaentrum server, plays titles with Media3,
and a trailer the server holds in the same player (a trailer link it hands to
another app), keeps watchlists and surfs titles in Zap. The app carries no
server address: the person adds one on the TV, and the app talks to that
server's chino-api under `/api/` and signs in with the OpenID Connect issuer
the server names, by a device code (RFC 8628) confirmed on a phone or a
computer.
How a change is proposed and what a pull request has to pass is in the
[guide to contributing](https://github.com/zaentrum/.github/blob/main/CONTRIBUTING.md);
what may be built is in [building from the specification](https://github.com/zaentrum/zaentrum/blob/main/AGENTS.md).

## Build

```sh
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

It needs JDK 17 and an Android SDK with platform 35, found through
`ANDROID_HOME` or `local.properties`. The wrapper brings Gradle 8.11.1;
`gradle/libs.versions.toml` pins Kotlin 2.1.0 and AGP 8.7.3. There is one app
id and no product flavor; the debug app id ends in `.debug`, so it installs
beside a release. A release (`:app:bundleRelease`) is signed only when
`SIGNING_KEYSTORE_FILE`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD` are set: the workflow's `release` job builds the signed
AAB where the keystore is held as a repository secret, and only once the jobs
`neutrality` and `build` (the tests) have passed.

## Test

```sh
bash scripts/check-neutrality.sh
./gradlew :app:verifyRoborazziDebug
```

`.github/workflows/ci.yml` runs these on a pull request to `main`, on `main`
and on a pushed tag `v*` (jobs `neutrality` and `build`), then builds the
debug APK. The guard needs
ripgrep (`rg`) on the PATH. `verifyRoborazziDebug` runs the JVM unit tests and
compares the screenshots that `FocusScreenshotTest` takes with the golden
images in `app/src/test/screenshots`; a screenshot in which more than 0.5 % of
the pixels differ fails the run, with the images of the difference in
`app/build/outputs/roborazzi`. No device or emulator takes part, and no server
but the tests' own: the API tests decode JSON shaped as chino-api writes it, an
OkHttp interceptor answers most requests a test sends, two tests
(`SupHttpTest`, `SignInLapseTest`) send real HTTP to a socket they open on
127.0.0.1, and the screens render under Robolectric at the TV's 960 × 540 dp. Robolectric
downloads its Android runtime on the first run.

## Run it

Install the debug APK on an Android TV emulator (an Android Virtual Device with
an Android TV system image), or on a TV with Android's developer options and
debugging on:

```sh
adb connect <tv-address>:5555    # a TV on the network; an emulator is attached already
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch the TV asks for the server's address: the reference demo
([how it is deployed](https://github.com/zaentrum/zaentrum/blob/main/docs/reference-demo.md))
or your own instance, such as `https://media.example.org`; without a scheme it
tries https, then http. A build with `-PserverPreset=<address>` offers that
address as one press. The app reads `/api/healthz` and `/api/config`, discovers
the issuer that `/api/config` names, and needs its device authorization
endpoint. It signs in as the client that `/api/config` gives as
`oidcClientId.tv` (`chino` where it gives none): the TV shows a code, a link
and a QR code, and the person confirms on a phone or a computer.

A release build talks https only. A debug build also reaches `http://localhost`
and `http://127.0.0.1`, for a server on the development machine reached through
`adb reverse tcp:<port> tcp:<port>`. There is no harness of recorded answers to
run the app against.

## What the tests leave unchecked

- Nothing runs on a TV or an emulator: the app has no instrumented tests, and
  the remote's focus is tested under Robolectric only.
- The way onto a server: the address probe (`ServerBootstrap`), OIDC discovery,
  the device-code client (`OidcDeviceClient`) and the server setup screen.
- Playback: Media3 playing a real stream, the TV's decoders, E-AC-3 passed
  through to a receiver, Zap's prefetch (`ZapPrefetcher`). The tests check what
  the player asks the server for and which tracks it picks.
- The notices, profile and addon slot screens.
- The screenshots show the focus style, the player's menus and controls, and
  the error pages; other screens are checked through their semantics, or not
  at all.
- The signed release AAB: only where the keystore secret is held.

## Before a pull request

- `bash scripts/check-neutrality.sh`, as the `neutrality` job runs it.
- A change to a look that a screenshot shows: `./gradlew :app:recordRoborazziDebug`
  writes the golden images in `app/src/test/screenshots` anew. They are
  committed with the change, since the workflow compares against them.
