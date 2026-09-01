# Ripster Mobile

A **native Android** app for downloading and playing lossless music from
**Deezer, Qobuz, Tidal, SoundCloud and Yandex Music** — and, when paired with a
PC running [desktop Ripster](https://github.com/Raccoon-Trashpanda/Raccoon-Ripster),
also **Apple Music** and **Spotify** link conversion, with your service logins
synced over from the PC.

It runs entirely on your phone. There is no account with *us* and no cloud — the
only server it ever talks to (optionally) is *your own* copy of desktop Ripster
on your local network.

> You need your own valid subscriptions / credentials for each service you use.
> Ripster automates downloading from services **you already pay for** — it does
> not provide accounts and does not bypass paid tiers.

---

## Contents

- [What you get](#what-you-get)
- [Standalone vs. paired with a PC](#standalone-vs-paired-with-a-pc)
- [Install](#install)
- [First launch](#first-launch)
- [Pairing with desktop Ripster](#pairing-with-desktop-ripster)
- [Connecting each service](#connecting-each-service)
- [The native audio engine](#the-native-audio-engine)
- [Updating](#updating)
- [Building from source](#building-from-source)
- [Permissions](#permissions)
- [Credits](#credits)
- [Disclaimer & License](#disclaimer--license)

---

## What you get

- 🎧 **Download lossless** — FLAC up to 24-bit/192 kHz from Deezer, Qobuz and
  Tidal (incl. Tidal **HI-RES via MPEG-DASH**), lossless / high-AAC SoundCloud,
  FLAC from Yandex Music (with Plus).
- 🔍 **Search across services** from one box, with ▶ instant preview and one-tap
  queueing.
- 🎚 **Built-in player** — plays through the OS codec by default, or through a
  **native Oboe engine** (AAudio, exclusive, bit-perfect) for local FLAC / WAV /
  ALAC, with a **gapless queue**.
- 🎛 **Equalizer & effects** — multi-band EQ, bass boost, virtualizer, and the
  audio-engine switch, all in one place under **Settings → Equalizer & effects**.
- 📈 **Spectrogram** — five views, same as the desktop app, with a
  lossless / upscaled-fake verdict.
- 🎤 **Floating lyrics** — line-by-line, the active line centred and lit, lines
  above it fading as they scroll away.
- 🦝 **Ripster Radar** — a release feed for the artists and labels you follow,
  with sticky date headers, a fast scrollbar, per-service filters, and ▶ preview
  straight from the card (it *streams*, it doesn't silently download).
- 🎨 **6 themes**, **5-language interface** — English, Russian, Hindi, Japanese,
  Chinese.
- 💿 **Reads your existing music** — point it at a folder and it scans your local
  library for playback and tagging.

### Only with a PC paired (see below)

- 🍎 **Apple Music** — ALAC 16/24-bit pulled through the PC's decrypt wrapper;
  storefront and catalogue come from the PC.
- 🟢 **Spotify** — ban-safe link conversion (no calls to `api.spotify.com` from
  the phone; the PC does the lookup).
- 🔑 **Synced logins** — Deezer / Qobuz / Tidal / Spotify / Yandex tokens are
  copied from the PC on pairing and kept fresh as the PC rotates them.
- 📡 **Radar from the PC watchlist** — the same follow list and the same
  personalised "new for you" signal the desktop app uses.
- 🎬 **Artist & label pages** — full discography, proxied from the PC's engines.
- ↔️ **Activity sync** — plays and downloads on the phone show up in the PC's
  history.

---

## Standalone vs. paired with a PC

| Feature | Standalone (phone only) | Paired with desktop Ripster |
|---|:---:|:---:|
| Search & download — Deezer, Qobuz, Tidal, SoundCloud, Yandex | ✅ (your own logins) | ✅ (logins synced from PC) |
| Built-in player, EQ & effects, native audio engine | ✅ | ✅ |
| Spectrogram, floating lyrics, themes, i18n | ✅ | ✅ |
| Local library scan & playback | ✅ | ✅ |
| Ripster Radar | ✅ (manual follow list) | ✅ (PC watchlist + Live signal) |
| **Apple Music (ALAC)** | ❌ | ✅ (via PC wrapper) |
| **Spotify link conversion** | ❌ | ✅ (ban-safe, PC does the lookup) |
| Artist / label discography pages | ⚠️ search-only fallback | ✅ (PC engines) |
| Download / listen history on the PC | ❌ | ✅ (activity sync) |

Pairing is **optional** and can be done later from **Settings → PC pairing**.

---

## Install

Ripster Mobile is **not on Google Play** — it is a sideloaded APK.

1. On your phone, open the
   **[Releases](https://github.com/Raccoon-Trashpanda/Raccoon-Ripster-Mobile/releases)**
   page and download the latest **`Ripster-Mobile-<version>.apk`**.
2. Tap the downloaded file. Android will ask to allow installing from this
   source — allow it for your browser / files app, then confirm the install.
3. Open **Ripster** from your app drawer.

Requires **Android 8.0 (API 26)** or newer. The APK ships `arm64-v8a` and
`x86_64` native libraries.

---

## First launch

A short setup runs once:

1. **Language**, **theme**, **text size**.
2. **Download folder** — pick where downloaded music is written (SAF; you can
   change it later in **Settings → Storage**).
3. **Pair with a PC** *(optional)* — see below. Skip it to use the app
   standalone; you can pair any time later.

---

## Pairing with desktop Ripster

On the **PC**, in desktop Ripster open **Settings → PC pairing** (or the
equivalent) and press **Show code** — you get an 8-digit code valid for a few
minutes.

On the **phone**, during first-launch setup (or later in **Settings → PC
pairing**):

1. Enter the PC address. On the same network this is usually
   `http://<PC-LAN-IP>:7799`. (An Android emulator reaches the host at
   `http://10.0.2.2:7799`.)
2. Enter the 8-digit code and tap **Pair**.

The phone then pulls your service tokens, the pairing mode, and the Apple
storefront from the PC, and registers the streaming clients. From then on the
phone re-syncs rotated tokens automatically whenever it can reach the PC.

Turning off Wi-Fi on the phone does **not** break the pairing — the token is
stored on the device; the phone just falls back to standalone until it can see
the PC again.

---

## Connecting each service

Every service can be connected **directly on the phone** in **Settings →
Accounts** — or, if you pair with a PC, its login is copied over and you don't
enter anything.

| Service | What you need |
|---|---|
| **Deezer** | The `arl` cookie. |
| **Qobuz** | Auth token, or email + password. |
| **Tidal** | Access token (paste it, or sync it from the PC). |
| **SoundCloud** | Nothing for public tracks; OAuth token for more. |
| **Yandex Music** | OAuth token. |
| **Apple Music** | PC pairing only — no phone-side login. |
| **Spotify** | PC pairing only — used for link conversion, not audio. |
| **BBC** | Nothing — public. |

---

## The native audio engine

**Settings → Equalizer & effects → Audio engine.**

- **System (ExoPlayer)** — the default. Handles every source, streaming and
  local.
- **Native (Oboe)** — *beta.* For **local** FLAC / WAV / ALAC files it bypasses
  ExoPlayer and feeds the samples straight to an AAudio exclusive-mode stream
  (Float, no resampling when the device rate matches), with a gapless queue and
  a bit-perfect / "resampled → N Hz" readout on the Now Playing screen. Non-local
  or non-lossless tracks fall back to the system engine automatically.

The ALAC path decodes Apple Lossless files you already have — it is **playback**,
not FairPlay DRM removal. Apple Music downloads still require a paired PC.

---

## Updating

Ripster Mobile is sideloaded, so it does **not** auto-update the way a Play Store
app does. To update, download the newest `Ripster-Mobile-<version>.apk` from the
[Releases](https://github.com/Raccoon-Trashpanda/Raccoon-Ripster-Mobile/releases)
page and install it over the existing app — your settings, logins, pairing and
downloaded music are kept.

> An in-app update check (compare against the latest GitHub release, one-tap
> download & install) is on the roadmap.

---

## Building from source

You need **JDK 17**, the **Android SDK** (compileSdk 34), and the **NDK**
(`26.3.11579264`) with **CMake 3.22.1** for the native audio module.

```bash
git clone https://github.com/Raccoon-Trashpanda/Raccoon-Ripster-Mobile.git
cd Raccoon-Ripster-Mobile
echo "sdk.dir=/absolute/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. Opening the project in
**Android Studio** works too.

**Release signing.** The signing keystore is *not* in the repo. To build a
signed release, add to `local.properties` (git-ignored) or your environment:

```properties
RIPSTER_RELEASE_STORE_FILE=ripster-release.jks
RIPSTER_RELEASE_STORE_PASSWORD=…
RIPSTER_RELEASE_KEY_ALIAS=…
RIPSTER_RELEASE_KEY_PASSWORD=…
```

Without them `./gradlew assembleRelease` still runs and produces an **unsigned**
release APK.

**Tech:** Kotlin · Jetpack Compose (Foundation, no Material components) ·
Media3 / ExoPlayer · Oboe + dr_libs + Apple's ALAC decoder (NDK) · Room ·
WorkManager · OkHttp · kotlinx.serialization · Coil.

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | streaming and downloading |
| `FOREGROUND_SERVICE*`, `WAKE_LOCK`, `POST_NOTIFICATIONS` | downloads and playback survive the app being backgrounded |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` (≤ API 32) | scan your existing music library |
| `MODIFY_AUDIO_SETTINGS` | equalizer, bass boost, virtualizer |
| `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE` | mDNS discovery of Yandex Station speakers |

---

## Credits

Ripster Mobile is a UI and orchestration layer. The heavy lifting is done by
open-source projects — **thanks to every author and contributor.**

- [google/oboe](https://github.com/google/oboe) — low-latency AAudio/OpenSL audio
- [mackron/dr_libs](https://github.com/mackron/dr_libs) — `dr_flac`, `dr_wav`
- [macosforge/alac](https://github.com/macosforge/alac) — Apple Lossless decoder
- [androidx Media3 / ExoPlayer](https://github.com/androidx/media) — playback, MediaSession
- [square/okhttp](https://github.com/square/okhttp) · [Kotlin](https://github.com/JetBrains/kotlin) · [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) · [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)
- [coil-kt/coil](https://github.com/coil-kt/coil) — image loading
- [Adonai/jaudiotagger](https://github.com/Adonai/jaudiotagger) — FLAC / MP3 / M4A tags
- [nathom/streamrip](https://github.com/nathom/streamrip) — the download logic the desktop engines are modelled on
- Service metadata: [Deezer API](https://developers.deezer.com/api) · [Qobuz API](https://www.qobuz.com/api.json/0.2) · [Tidal API](https://developer.tidal.com/documentation) · [MarshalX/yandex-music-api](https://github.com/MarshalX/yandex-music-api) · [iTunes Search API](https://performance-partners.apple.com/search-api)
- [desktop Ripster](https://github.com/Raccoon-Trashpanda/Raccoon-Ripster) — the PC side of pairing (Apple Music, Spotify, token sync)

---

## Disclaimer & License

Ripster Mobile is **not affiliated with** Apple, Spotify, Qobuz, Tidal, Deezer,
SoundCloud, or Yandex. All trademarks belong to their respective owners.

**For personal use.** Respect the terms of service of each provider and only
download content you are entitled to.
