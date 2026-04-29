# Data Safety — Play Store Form Reference

This document records the answers for Google Play's Data Safety form.

## Does your app collect or share any of the required user data types?

**No.**

## Data Collection Summary

| Data Type | Collected | Shared | Purpose |
|-----------|-----------|--------|---------|
| Location | No | No | — |
| Personal info (name, email, etc.) | No | No | — |
| Financial info | No | No | — |
| Health and fitness | No | No | — |
| Messages | No | No | — |
| Photos and videos | No | No | — |
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
- Requests only the INTERNET permission
- Stores only a server URL and session cookies locally

## Permissions Declared

| Permission | Reason |
|------------|--------|
| `android.permission.INTERNET` | Required to load the TREK web interface |

No other permissions are requested.
