# square-bridge

An Android kiosk app that lets a web-based point of sale take card payments through the Square POS app.

We run [Odoo Community](https://www.odoo.com/) as our register at Moody Teas. Odoo's POS is a web app, and Square's in-person payments on Android go through the Square POS app with an Android intent. A browser can't send that intent and get the result back, so this app sits in between:

1. **KioskActivity** shows the POS full-screen in a WebView and gives the page a small JavaScript bridge (`window.Android.launchIntent`).
2. When the cashier picks Square, the POS page fires a deep link: `<scheme>://charge?...`.
3. **BridgeActivity** catches it, starts the Square POS app with the charge, and waits.
4. When Square returns, the bridge calls the POS server back with the result (`status=ok|failed&state=<token>`). The POS polls its own server and finishes the order.

A second launcher icon, **KDSActivity**, shows a kitchen display full-screen on the same tablet. It has its own task affinity, so tapping either icon opens that app instead of whichever one was used last.

## Things we learned the hard way

- **Process death mid-payment.** Cheap tablets with little RAM kill your process while Square is in the foreground. When Android recreates the activity, it delivers the pending result again. If `onCreate` starts Square a second time, you get a second charge. `BridgeActivity` checks `savedInstanceState` and waits for the result instead.
- **`intent://` URIs need `Intent.parseUri(url, Intent.URI_INTENT_SCHEME)`.** Wrapping one in `ACTION_VIEW` makes Square answer "no activity found to handle intent".
- **Reader disconnects look like instant failures.** If Square comes back in under four seconds with a reader error, the app tells staff to reconnect the reader instead of letting them retry over and over.
- **Battery optimization unpairs the reader.** The app asks Android to exempt both itself and the Square app.
- **Some tablet makers ignore orientation requests.** With `WRITE_SECURE_SETTINGS` granted over adb, the app writes `ignore_orientation_request=0` each time it starts, so landscape survives a reboot.
- **Square ties your app to its signing key.** Register the SHA-1 of a key you keep. A key that changes every build breaks payments without much of a clue why.

## Configure

Everything specific to one deployment is set at build time. Defaults point at `example.com`.

| Variable | Purpose |
|---|---|
| `APP_ID` | Android application id (default `com.example.squarebridge`) |
| `BRIDGE_SCHEME` | Deep link scheme the POS fires (default `squarebridge`) |
| `POS_URL` | Your POS web app. Also the only origin allowed to use the bridge and receive callbacks |
| `KDS_URL` | Your kitchen display |
| `POS_AUTH_USER` / `POS_AUTH_PASS` | Optional HTTP basic auth in front of the POS |
| `UPDATE_MANIFEST_URL` | Optional JSON manifest for self-update (`{"versionCode": 12, "url": "https://.../app.apk"}`) |

The GitHub Actions workflow reads these from repository variables and secrets. It signs the APK only when `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` and `KEY_ALIAS` are set.

## The deep link

The POS server builds it. Ours looks like this:

```
intent://charge?amount=450&currency=USD&token=<one-time token>
  &callback=https://pos.example.com/pos/square/callback?token=<token>
  &location_id=<square location>&client_id=<square application id>
#Intent;scheme=squarebridge;package=com.example.squarebridge;end
```

`amount` is in cents. The POS page passes it to `window.Android.launchIntent(deepLink)`.

## Security notes

- The bridge only answers pages on the `POS_URL` host, and only sends callbacks to that host over https. The deep link is `BROWSABLE`, so any page can open it. Treat the callback as a hint and confirm the payment on your server.
- Don't commit credentials. Earlier versions of this app kept defaults in `build.gradle`. That's why this repo starts from a fresh history.

## Setup on a tablet

```
adb install -r app-signed.apk
adb shell pm grant <APP_ID> android.permission.WRITE_SECURE_SETTINGS
```

Then sign in to the Square POS app, pick your location, and give it a minute to sync before the first test charge. A fresh install can report "app client id is unknown" until Square finishes syncing.

## License

MIT. See `LICENSE`.
