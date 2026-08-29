# Data Safety — Play Store Form Reference

This document records the answers for Google Play's Data Safety form.

## Does your app collect or share any of the required user data types?

**No.**

## Data Collection Summary

| Data Type | Collected | Shared | Purpose |
|-----------|-----------|--------|---------|
| Location | No | No | — (see note below) |
| Personal info (name, email, etc.) | No | No | — |
| Financial info | No | No | — |
| Health and fitness | No | No | — |
| Messages | No | No | — |
| Photos and videos | No | No | — (see note below) |
| Audio files | No | No | — |
| Files and docs | No | No | — |
| Calendar | No | No | — |
| Contacts | No | No | — |
| App activity | No | No | — |
| Web browsing | No | No | — |
| App info and performance | No | No | — |
| Device or other IDs | No | No | — |

## Security Practices

| Question | Answer |
|----------|--------|
| Is data encrypted in transit? | Yes — HTTPS enforced, plain HTTP blocked |
| Can users request data deletion? | N/A — no data is collected |
| Committed to Play Families Policy? | No (not a children's app) |

## Justification

Embara is a WebView wrapper that connects to a user-specified server. All data processing occurs between the WebView and the user's own TREK server. The app itself:

- Has no backend
- Has no analytics SDK
- Has no advertising SDK
- Has no crash reporting SDK
- Stores only a server URL and session cookies locally

Embara declares location and (pre-Android 10) storage permissions so the WebView can serve what the
user's own TREK server asks for. Nothing is collected by the app: a position is handed straight to
the page and never stored or transmitted anywhere else, a chosen photo goes to the user's own
server, and a download is written to the user's own device. There is no backend to send it to.

## Permissions Declared

| Permission | Reason |
|------------|--------|
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | Requested only when the TREK page asks the browser for a position ("Use my current location", mobile quick capture). Granted only to the configured server's own origin, never retained across pages. |
| `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28) | Saving a TREK export to Downloads on Android 9 and below. Android 10+ uses scoped storage and needs no permission. |
| `android.permission.INTERNET` | Required to load the TREK web interface |

No other permissions are requested.
