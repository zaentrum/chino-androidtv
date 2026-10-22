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
- Min SDK 24 (the least Play's automatic protection takes), Target SDK 35, Java/JVM target 17

## Features

- Browse, search, and a detail screen with cast/crew
- Search: films and series in one grid, ranked together by your server's
  search (`/api/v1/search`), best match first, with the cast & crew whose
  names match in a row above — one request. A server without that search is
  asked as before: its films, then its series, and the people apart
- One focus style for the remote, the design system's
  [tv focus](https://nalet.github.io/design-system/patterns/tv-focus/): what
  has focus gets a 3 dp ring in the focus colour, a lighter surface behind a
  row, plain button or chip, a little growth and brighter text, on every
  screen; the episode a detail page was opened on has a bar along its left
  edge instead (`ui/theme/Focus.kt`)
- Playback with trickplay scrubbing thumbnails
- A quality step down the player makes by itself is said on screen for a few
  seconds, the quality named as its menu names it: "Switched to Medium (720p)
  quality: the connection can't keep up with the original."
- Playback quality reports, as chino-web sends them: the time to the first
  frame, each stall mid-play and how long it lasted, the bandwidth the player
  sees with its segments' load times and time to first byte, the switches of
  the quality on screen, and fatal errors - in batches to the server's
  `/api/v1/play/events`, which counts them; nothing about the account or the
  device goes with them
- 5.1 audio where the TV plays E-AC-3 — it decodes it, or passes it through
  to a receiver or sound bar: a title's 5.1 tracks are in the audio menu
  beside the stereo ones, and the 5.1 track of the language plays first where
  the TV's audio output takes 5.1, the stereo one where it is stereo. Zap
  plays in stereo
- Subtitles, one at a time, with one look for every title: white text with a
  black outline and nothing behind it, 5.33 % of the picture's height, centred
  at the bottom within 80 % of its width and inside the TV's safe area; a
  file's own colours, boxes, sizes and places are ignored, and Android's
  caption settings win where you have switched them on (accessibility). The
  menu, "subtitles", lists "off", then your subtitle language (Settings), then
  the subtitle languages your server is set up for
  (`/api/v1/subtitles/languages`), then "more languages" with the rest of the
  title's tracks - a package's other renditions, its sidecars and, for a
  title played from its original file, the file's own text streams, each
  fetched only once it is switched on. On a server that names no subtitle
  languages, all of the tracks are listed directly: your language first, then
  the file's order, a language's tracks together. A track is named by
  chino-web's rule, so the TV, the phone and the web call it the same: its
  language, its script or region in brackets, and its kind in words
  ("Chinese (Simplified)", "English · for deaf and hard of hearing", "German
  · AI", "Chinese · Cantonese"); words in its own label that only name the
  language again are dropped wherever they stand ("Deutsch Kommentar" is
  "German · Kommentar"), and tracks that read the same show once, never
  numbered, the file's own first.
  What comes on by itself: your subtitle language when the audio is in
  another language, else the foreign-language parts only in the language of
  the audio, which follow the audio into another language; nothing else, and
  a pick in the menu, Off included, holds for the rest of the playback
- Trailers: a trailer your server has plays in the app's one player, with the
  controls, quality, audio and subtitle menus and remote a title has, from the
  start with sound, and closes at its end; it writes no progress, so Continue
  Watching is left alone. Without one, the Trailer button opens the title's
  YouTube link
- Watchlists (named lists). On a title's page and its episode rows the
  bookmark adds the title to the Watchlist or takes it off - that list only -
  and "⌄" beside it, in the same frame, picks the other lists; watched is a
  check, liked a heart, each dark on its colour when on. Cards mark the
  Watchlist and watched the same way
- Zap — a channel-surf discovery mode
- Multi-account, with an in-app account picker
- Delete Account (Settings → Account): deletes the signed-in account and what
  the server keeps of it, after asking, and signs it out on the TV
- Notices: what an addon on your server tells you ("your title is ready").
  The bell in the top bar counts the unread ones; a notice about a title opens
  it. Nothing shows on a server whose portal does not answer
- Addon buttons where a search finds nothing (the `search.empty` slot): an
  action is sent from the TV; a link shows its address as a QR code to open on
  your phone, since a TV has no browser
- In-app feedback / bug reporting to the connected server

## Build

You'll need:

- JDK 17
- Android SDK with platform 35 + build-tools 35.x
- Android Studio (Ladybug or newer) or the Gradle CLI

There is one app id; debug builds get an extra `.debug` suffix so a local build
never overwrites a release you installed.

```bash
# Day-to-day development build:
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# JVM unit tests (paging, credits, people, series resume, the API contract,
# what a search asks the server for, account deletion, addon slots, notices,
# trailers, what the player asks the server for a title and for a trailer,
# the caps, the 5.1 audio, the subtitles - their menu, names, look and what
# comes on by itself - the playback quality reports and the focus style's
# contrast):
./gradlew :app:testDebugUnitTest

# The same, and the focus style's screenshots compared with their golden
# images in app/src/test/screenshots (rendered under Robolectric, no device):
./gradlew :app:verifyRoborazziDebug
# After a change to the focus style's look, write the golden images anew:
./gradlew :app:recordRoborazziDebug
```

Install on a real Android TV device (or an "Android TV" emulator AVD):

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
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
