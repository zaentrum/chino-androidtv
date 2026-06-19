# chino-androidtv

Android TV client for **chino**, the movies-and-shows app of the
[zaentrum](https://github.com/zaentrum/zaentrum) self-hosted media platform.

Built with Jetpack Compose for TV for the 10-foot living-room experience
(Android TV / Fire TV). It is a **bring-your-own-server** client: it ships with
no server baked in. On first launch you point it at your own zaentrum server
through the in-app **Add-Server** flow, and the app configures itself from that
server.

## How it connects

1. **Add-Server** — enter your server's address on the setup screen.
2. **Discovery** — the app reads the server's `/api/config` to learn its API
   base URL and identity provider, then performs OpenID Connect discovery
   against that issuer.
3. **Sign in** — authentication uses the OAuth 2.0 Device Authorization Grant
   (RFC 8628): the TV shows a short code and a URL (plus a QR code), you confirm
   on your phone or laptop, and the TV completes sign-in. This is the only
   sensible flow for a 10-foot UI with no keyboard.

No server hostnames are compiled into the published build. The build-config
fields used as an internal pre-connection fallback are empty by default; the
real values always come from the connected server at runtime.

## Stack

- Kotlin 2.1 + Jetpack Compose for TV (`androidx.tv:tv-material`)
- Compose Navigation
- Retrofit + OkHttp + kotlinx.serialization
- DataStore Preferences for token and server-config persistence
- Media3 / ExoPlayer (incl. HLS) for playback
- Coil for poster artwork
- Min SDK 21, Target SDK 35, Java/JVM target 17

## Features

- Browse, search, and a detail screen with cast/crew
- Playback with trickplay scrubbing thumbnails
- Watchlists (named lists)
- Zap — a channel-surf discovery mode
- Multi-account, with an in-app account picker
- In-app feedback / bug reporting to the connected server

## Build

You'll need:

- JDK 17
- Android SDK with platform 35 + build-tools 35.x
- Android Studio (Ladybug or newer) or the Gradle CLI

There are two product flavors (`beta` and `prod`) that install side by side, and
debug builds get an extra `.debug` suffix so a local build never overwrites a
release you installed.

```bash
# Day-to-day development build:
./gradlew :app:assembleBetaDebug
# APK: app/build/outputs/apk/beta/debug/app-beta-debug.apk

# Prod-flavor debug build:
./gradlew :app:assembleProdDebug
# APK: app/build/outputs/apk/prod/debug/app-prod-debug.apk
```

Install on a real Android TV device (or an "Android TV" emulator AVD):

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/beta/debug/app-beta-debug.apk
```

### Optional build overrides

The published defaults are neutral (no server baked in). If you build your own
distribution and want to prefill a server or an internal fallback, pass Gradle
properties (on the command line or in `gradle.properties`):

| Property            | Effect                                                    |
|---------------------|-----------------------------------------------------------|
| `-PserverPreset`    | Prefills the Add-Server field with a one-tap suggestion.  |
| `-PoidcIssuer`      | Internal fallback OIDC issuer (pre-connection only).      |
| `-PbetaApiBaseUrl`  | Internal fallback API base URL for the `beta` flavor.     |
| `-PprodApiBaseUrl`  | Internal fallback API base URL for the `prod` flavor.     |
| `-PchinoAppId`      | Override the published `applicationId` base.              |

None of these are required to build or run; leave them unset for a fully
neutral build.

## Release signing

Release artifacts are signed only when signing material is supplied via
environment variables (`SIGNING_KEYSTORE_FILE`, `SIGNING_KEYSTORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). With none present, the release
build stays unsigned — fine for local development. No keystore is committed to
this repository.

## Server setup (OIDC clients)

This client signs in against whatever identity provider your zaentrum server
advertises. On your OIDC issuer, create public clients for the TV app:

- one client for the `beta` flavor and one for the `prod` flavor
- **public** clients (client authentication off)
- enable the **OAuth 2.0 Device Authorization Grant** flow (and confirm it is
  enabled at the realm/tenant level)
- redirect URIs and web origins can be left empty (device flow uses neither)
- default scopes: `openid`, `profile`, `email`, `offline_access`

The access token must be accepted by your server's API; if your API expects a
specific audience, add an audience mapper on the TV client accordingly.

## License

Licensed under the Mozilla Public License 2.0. See [LICENSE](LICENSE).
