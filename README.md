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
| Close and erase everything | **One tap on 🔥**, or ⋮ → Exit & wipe everything, press Back twice on the last page, or swipe the app away in Recents |
| See every connection a page made | ⋮ → **Connections** (in memory only; shows what was loaded and what was blocked) |
| Choose or turn off search | ⋮ → **Search engine**: DuckDuckGo, DuckDuckGo onion, Startpage, Brave Search, Mojeek, or None |

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
- About 280 known tracker, analytics and ad domains are blocked when loaded as a third
  party (`assets/trackers.txt`). The list ships with the app and is never updated
  over the network.
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

## Privacy checklist

| Requirement | Status |
| --- | --- |
| No analytics or telemetry | ✅ No analytics code or SDKs (enforced by `tools/privacy-audit.sh`). WebView metrics are opted out in the manifest. |
| No advertising SDKs | ✅ None. The dependencies are a few AndroidX core libraries, Kotlin, tor-android and jtorctl only. |
| No crash-reporting services | ✅ None in the app. (Android and WebView have their own system-level crash settings.) |
| No connection to the developer's servers | ✅ There are none. The app only connects to the Tor network and to the sites you open. |
| No account/login system | ✅ |
| No remote configuration or tracking | ✅ Nothing is fetched to configure the app. The tracker list is bundled. |
| Doesn't collect history, URLs, searches, IPs, device IDs or usage stats | ✅ Nothing is collected or sent anywhere. |
| Doesn't store browsing data | ⚠️ Cookies and cache exist *during* a session (sites need them) and are deleted on exit and on every launch. The only thing kept is your search-engine choice. |
| One-tap Clear all | ✅ 🔥 button |
| Doesn't retain cookies | ✅ Deleted on exit; third-party cookies are never accepted. |
| Blocks third-party trackers | ✅ Bundled list of about 280 domains. Basic: not as complete as uBlock Origin. |
| No injected tracking scripts, pixels or affiliate IDs | ✅ The only script added to pages is the local, open-source privacy script (`assets/privacy_shim.js`), which *removes* tracking surfaces. |
| Doesn't sell, share or transmit browsing data | ✅ |
| Network connections visible/auditable | ✅ ⋮ → Connections lists every request per tab. (Not shown: WebSockets, and Tor's own connections to relays.) |
| HTTPS wherever available | ✅ Stricter than that: `http://` is always upgraded to `https://` (except `.onion`), so http-only sites won't load. |
| Source code available | ✅ Everything is in this repository. |
| Minimal permissions | ✅ `INTERNET` only. No contacts, location, mic, camera, Bluetooth, files, phone or accounts. |
| No Android advertising ID | ✅ Never read. The `AD_ID` permission is explicitly stripped from the manifest. |
| No unnecessary persistent identifiers | ✅ No IDs are created or stored. The WebView profile and Tor state are new every session. |
| No URLs sent to "safe browsing" or search services unless chosen | ✅ Safe Browsing is off. Only text you type in the address bar goes to the search engine *you* pick (or none). No search suggestions. |
| Configurable DNS/search | Search: ✅. DNS: intentionally ❌. Every lookup is resolved by the Tor network, because using any other DNS server would reveal the sites you visit and your IP. |

## Proving it doesn't secretly send data

**1. Automatic audit on every build.** `tools/privacy-audit.sh` inspects the compiled
APK, not just the source, and the build fails if any of these show up:

- a permission other than `INTERNET`;
- any known analytics, advertising, attribution or crash-reporting SDK (Firebase,
  Google Play Services, Crashlytics, Sentry, Facebook, AppsFlyer, Adjust, Mixpanel
  and about 40 more);
- *any* app code that opens a network connection itself. The only socket code
  allowed is the local control channel to the embedded Tor process, so every
  byte that leaves the phone has to go through Tor to a site you opened;
- device-identifier APIs: advertising ID, Android ID, IMEI, phone number, serial,
  MAC address, accounts, location, installed-apps list;
- any hard-coded web address not on the reviewed allowlist
  (`tools/allowed-urls.txt`: the search engines and the "check Tor" link).

Run it yourself with `./gradlew assembleRelease && tools/privacy-audit.sh`.

**2. Watch the traffic on your own phone.** Install
[PCAPdroid](https://github.com/emanuele-f/PCAPdroid) (free, open source, no root),
start a capture filtered to ZeroTrace, and browse for a while. You should see:

- **zero DNS queries** from ZeroTrace (Tor connects to relays by IP address, and
  websites' names are resolved inside the Tor network);
- connections **only to Tor relays** (IP addresses, usually on ports 443 or 9001),
  and nothing to Google, Samsung, analytics companies or any "developer" server;
- no traffic at all after you tap 🔥 (the app process is gone).

Traffic attributed to *Android System WebView*, *Google Play services* or the
*Galaxy Store* belongs to those system apps (for example, their own updates), not
to ZeroTrace.

**3. Match the APK to the source.** Every CI run prints the SHA-256 of the APK it
built from this exact code, so you can compare it with the file you installed.

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
| `TrackerBlocker.kt`, `assets/trackers.txt` | Bundled third-party tracker blocklist |
| `Settings.kt` | Search-engine choice (the only stored setting) |
| `Wiper.kt` | Deletes every file the app owns, clears WebView stores and the clipboard, kills the process |
| `WipeWatcherService.kt` | Wipes when you swipe the app away from Recents |
| `RestartActivity.kt` | Relaunches a completely fresh browser for "New identity" |
