# ZeroTrace: a private browser for Samsung / Android phones

ZeroTrace is a tabbed web browser for Android that sends all your traffic through
the **Tor network**, fakes or blocks the device details websites use to recognize
you, and **erases everything when you close it**.

## Install on your Samsung phone

1. Get the APK. Open the **Actions** tab of this repository, open the latest
   "Build APK" run, and download the `ZeroTrace-apk` artifact (a zip containing
   `app-release.apk`). You can also build it yourself (see below).
2. On the phone, open the APK. When asked, allow your browser or My Files to
   **Install unknown apps** (Settings → Apps → *that app* → Install unknown apps).
3. Open **ZeroTrace**. The first connection to Tor takes about 10–60 seconds. Then
   type a search or address.

You can also set it as the app that opens web links (Settings → Apps → Choose
default apps → Browser app).

## Using it

| Action | How |
| --- | --- |
| Open a new tab | ⋮ → New tab, or tap the tab counter → New tab |
| Switch or close tabs | Tap the square tab counter next to the address bar |
| Start over as a different person | ⋮ → **New identity** (erases everything and reconnects with new Tor circuits and a new fingerprint) |
| Close and erase everything | ⋮ → **Exit & wipe everything**, press Back twice on the last page, or swipe the app away in Recents |

The dot on the left of the address bar turns purple once you are connected
through Tor. Until then, **nothing loads at all**: there is no fallback to a
normal connection.

## What it protects

**Your IP address and location**
- All web traffic, DNS lookups included, goes through an embedded Tor client
  over SOCKS5. Websites see the IP address of a random Tor exit relay.
- Each website gets its own Tor circuit (`IsolateDestAddr`), so trackers on
  different sites can't link you by exit IP.
- WebRTC, the usual way a real IP leaks past a proxy, is removed from every page
  and frame.
- Geolocation is always denied. The app doesn't even have the location permission.
- Time zone is reported as UTC and language as en-US for every site.
- Plain `http://` is upgraded to `https://` so Tor exit relays can't read or change
  pages (`.onion` sites excepted, since they're already end-to-end encrypted).
  Invalid certificates are always blocked.

**Your device and fingerprint**
- The user agent and client-hint headers match generic Chrome on Android: no phone
  model, no real Android version, no WebView marker, and no app ID
  (`X-Requested-With`) header.
- CPU cores, RAM, touch points and storage quota are set to fixed common values.
  Battery, network type, Bluetooth, USB, gamepads, cameras and microphones are hidden.
- Canvas, WebGL and audio fingerprints get random noise that changes every session,
  so a fingerprint taken today can't be matched tomorrow. The GPU model is hidden.
- Google Safe Browsing, WebView metrics, Media Integrity (Play device attestation),
  ad attribution and payment APIs are turned off.
- Third-party cookies are blocked.
- Downloads, file uploads and links that open other apps (`intent:`, `tel:`,
  `market:` and so on) are blocked, because they are common ways to escape Tor or
  to leak photo GPS data.
- The only Android permission is `INTERNET`. The app never reads your phone
  number, IMEI, advertising ID, accounts or contacts.

**Nothing left behind**
- On exit, the app deletes cookies, local storage, IndexedDB, service workers,
  cache, history, form data, the Tor state and keys, and every other file it wrote.
  It also clears the clipboard. Then it kills its own process, so nothing survives
  in memory either.
- The same wipe runs every time the app starts, which catches crashes,
  force-stops, a dead battery and reboots.
- Every session uses a new, randomly named WebView profile.
- The app blocks screenshots and screen recording, and its Recents thumbnail is
  blank. Android backup and device-to-device transfer are disabled.

## Limits you should know about

No app can promise perfect invisibility. Please read this section.

- **Tor Browser is still the gold standard.** ZeroTrace uses Android's built-in
  WebView (Chromium), and it can't make every user look *identical* the way Tor
  Browser (Firefox-based) does. For example, screen size, installed fonts and
  WebGL limits still differ from phone to phone. ZeroTrace makes you *unlinkable
  between sessions*, not identical to everyone. For the highest-risk situations,
  use the official Tor Browser for Android.
- **What you do still counts.** If you log in to Google, Samsung, Facebook or any
  other account, or type your name, email or phone number, the site knows who you
  are, Tor or not.
- **Your internet provider or mobile carrier can see that you use Tor**, but not
  which sites you visit. On networks that block Tor, the app won't connect
  (bridges aren't built in yet).
- **Samsung Keyboard and Gboard** may learn words you type into web pages. The
  address bar asks keyboards not to learn, but web page fields can't. For
  sensitive typing, turn on your keyboard's incognito mode.
- **Web workers** (background scripts) aren't covered by the fingerprint script.
- **Android System WebView updates** come from Google Play / Galaxy Store. Keep it
  up to date. If it's too old to route traffic safely, the app refuses to browse.
- Some websites block Tor users or show extra CAPTCHAs.

## Build it yourself

Requirements: JDK 17+ and the Android SDK (platform `android-37.1`, build-tools 37).

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the standard debug key, so it can be sideloaded
straight away. Use your own signing key if you distribute it.

## How it works (code map)

| File | What it does |
| --- | --- |
| `ZeroApp.kt` | Wipes leftovers on startup, pins locale/time zone, creates a fresh WebView profile |
| `TorController.kt` | Starts the embedded Tor (`tor-android`), writes a hardened `torrc`, waits for bootstrap |
| `MainActivity.kt` | Tabs, address bar, menu, SOCKS5 proxy override (no direct fallback), permission denials |
| `Privacy.kt` | WebView lockdown: UA and client hints, Safe Browsing off, Media Integrity off, etc. |
| `assets/privacy_shim.js` | Runs before any page script in every frame: WebRTC kill, UTC, fixed hardware values, canvas/WebGL/audio noise |
| `Wiper.kt` | Deletes every file the app owns, clears WebView stores and the clipboard, kills the process |
| `WipeWatcherService.kt` | Wipes when you swipe the app away from Recents |
| `RestartActivity.kt` | Relaunches a completely fresh browser for "New identity" |
