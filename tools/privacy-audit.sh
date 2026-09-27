#!/usr/bin/env bash
# Privacy audit for the built APK. Fails (exit 1) if the app could secretly send data:
#   1. any permission other than INTERNET
#   2. any known analytics / ads / crash-reporting / attribution SDK
#   3. any Java/Kotlin code that opens network connections itself (outside the local
#      Tor control connection) – all real traffic must go through Tor
#   4. any device-identifier / advertising-ID API
#   5. any hard-coded web address that isn't on the reviewed allowlist
#
# Usage: tools/privacy-audit.sh [path/to/app.apk]   (needs ANDROID_HOME with build-tools)
set -uo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
AAPT2="$BT/aapt2"
DEXDUMP="$BT/dexdump"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAIL=0
fail() { echo "  ✗ $*"; FAIL=1; }
ok() { echo "  ✓ $*"; }

[ -f "$APK" ] || { echo "APK not found: $APK"; exit 2; }
unzip -q "$APK" -d "$WORK/apk"
for f in "$WORK"/apk/classes*.dex; do "$DEXDUMP" -d "$f" 2>/dev/null; done > "$WORK/dex.txt"
# "<calling class> <instruction>" for every call / allocation / class reference.
awk '/Class descriptor/{c=$4} /invoke-|new-instance|const-class|sget|iget/{print c" "$0}' "$WORK/dex.txt" > "$WORK/calls.txt"

echo "1. Permissions"
PERMS="$("$AAPT2" dump permissions "$APK" | sed -n "s/^uses-permission: name='\(.*\)'.*/\1/p")"
for p in $PERMS; do
  case "$p" in
    android.permission.INTERNET|*.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) ok "$p" ;;
    *) fail "unexpected permission $p" ;;
  esac
done

echo "2. Tracking / analytics / ads / crash-reporting SDKs"
SDKS='com/google/firebase|com/google/android/gms|com/google/ads|com/google/android/play/core|com/crashlytics|io/fabric|io/sentry|com/bugsnag|com/instabug|com/microsoft/appcenter|com/newrelic|com/datadog|com/facebook|com/appsflyer|com/adjust/sdk|com/amplitude|com/mixpanel|com/segment|com/flurry|com/onesignal|com/braze|com/appboy|io/branch|com/kochava|com/clevertap|com/applovin|com/unity3d/ads|com/ironsource|com/vungle|com/chartboost|com/inmobi|com/mopub|com/yandex/metrica|com/umeng|com/huawei/hms|com/samsung/android/sdk|com/heapanalytics|com/smartlook|com/uxcam|com/localytics|io/embrace|com/splunk|com/posthog|io/rollbar'
HITS="$(grep -oE "L($SDKS)/[A-Za-z0-9/_$]+" "$WORK/dex.txt" | cut -d/ -f1-3 | sort -u)"
if [ -n "$HITS" ]; then fail "found: $(echo $HITS)"; else ok "none present"; fi

echo "3. Code that talks to the network directly (must be none; Tor does all networking)"
NET='Ljava/net/(URL;|HttpURLConnection|URLConnection|Socket;|DatagramSocket|MulticastSocket|InetAddress;->getBy|ServerSocket)|Ljavax/net/|Lokhttp3/|Lcom/squareup/okhttp|Lorg/apache/http|Lcom/android/volley|Lcronet|Lorg/chromium/net/|Landroid/net/http/HttpEngine|Landroid/app/DownloadManager|Landroid/net/(ConnectivityManager|wifi/)'
# Allowed: the Tor control library (a local connection to the embedded Tor process),
# and TorService's local-port check (binds a ServerSocket on the phone, sends nothing).
ALLOW="^'Lnet/freehaven/tor/control/|^'Lorg/torproject/jni/TorService;' .*Ljava/net/ServerSocket;"
BAD="$(grep -E "$NET" "$WORK/calls.txt" | grep -vE "$ALLOW" | awk '{print $1}' | sort -u)"
if [ -n "$BAD" ]; then
  fail "network code in: $(echo $BAD)"
  grep -E "$NET" "$WORK/calls.txt" | grep -vE "$ALLOW" | grep -oE 'L[A-Za-z/$]+;[.>-]+[A-Za-z<>]+' | sort -u | sed 's/^/      /'
else ok "only the local Tor control connection"; fi

echo "4. Device identifiers / advertising ID"
IDS='AdvertisingIdClient|getAdvertisingIdInfo|Landroid/provider/Settings\$Secure;.getString|Landroid/telephony/TelephonyManager;.(getDeviceId|getImei|getMeid|getSubscriberId|getSimSerialNumber|getLine1Number)|Landroid/os/Build;.getSerial|Landroid/accounts/AccountManager|Landroid/net/wifi/WifiInfo;.getMacAddress|Landroid/bluetooth/BluetoothAdapter;.getAddress|Landroid/location/|getInstalledPackages|getInstalledApplications'
BAD="$(grep -E "$IDS" "$WORK/calls.txt" | grep -oE 'L[A-Za-z/$]+;[.>-]+[A-Za-z<>]+' | sort -u)"
if [ -n "$BAD" ]; then fail "identifier APIs used: $(echo $BAD)"; else ok "none used"; fi

echo "5. Hard-coded web addresses"
{ strings -n 8 "$WORK"/apk/classes*.dex; cat "$WORK"/apk/assets/*.js "$WORK"/apk/assets/*.html 2>/dev/null; } \
  | grep -oE '(https?|wss?)://[A-Za-z0-9.-]+' | sort -u > "$WORK/urls.txt"
while read -r u; do
  if grep -qxF "$u" "$HERE/allowed-urls.txt"; then ok "$u"; else fail "not on allowlist: $u"; fi
done < "$WORK/urls.txt"

echo
if [ "$FAIL" -ne 0 ]; then echo "PRIVACY AUDIT FAILED"; exit 1; fi
echo "PRIVACY AUDIT PASSED"
