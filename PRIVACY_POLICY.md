# Privacy Policy

**Embara — for TREK**
*Last updated: August 29, 2026*

## Overview

Embara is an open-source Android client for self-hosted TREK instances. It is developed by Soult IO Ltd and distributed via the Google Play Store.

## Data Collection

Embara does **not** collect, store, transmit, or share any personal data. Specifically:

- **No analytics or telemetry** — WebView metrics are explicitly disabled
- **No crash reporting** — no third-party crash reporting SDKs are included
- **No advertising** — no ad networks or tracking pixels
- **No user accounts** — Embara does not have its own account system
- **No server-side component** — Soult IO does not operate any backend for this app

## Data Stored on Device

Embara stores the following data locally on your device only:

- **Server URL** — the TREK instance URL you enter during setup (stored in SharedPreferences)
- **Session cookies** — authentication cookies from your TREK server (stored in WebView cookie storage)
- **WebView cache** — cached web content from your TREK server for performance

This data never leaves your device except as requests to the TREK server you configured.

## Device Permissions

Embara asks for these only at the moment the TREK page you are viewing asks the browser for them,
and never in the background. Soult IO receives none of it — there is no backend to receive it.

- **Location** (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) — TREK 4.0.0 can pin a journal
  entry where you are standing ("Use my current location") and its mobile quick-capture screen looks
  up the nearby place name and weather. When that happens, Android asks you first, and your position
  is handed to the page and sent to your own TREK server. It is not stored by Embara, not retained
  between pages, and only your configured server may ask.
- **Camera and photos** — choosing a photo or taking one for a TREK entry opens Android's own
  picker or camera app. Embara declares no camera permission; the picture is taken by the camera app
  and uploaded to your TREK server. A photo taken this way is written to Embara's private cache
  so the page can upload it, and is deleted the next time Embara starts. Photos you pick from the
  gallery are never copied.
- **Storage** (`WRITE_EXTERNAL_STORAGE`, Android 9 and below only) — saving a file TREK offers you,
  such as your MFA backup codes or a trip's calendar export, into your Downloads folder. Android 10
  and above needs no permission for this.

## Network Connections

Embara connects only to the TREK server URL you provide. It does not contact any other servers, including any operated by Soult IO.

## Third-Party Services

Embara does not integrate with any third-party services. The only network communication is between the app and your self-hosted TREK instance.

## Cookies

Embara uses first-party cookies from your TREK server to maintain your login session. Third-party cookies are explicitly blocked. No cookies are shared with Soult IO or any other party.

## Children's Privacy

Embara does not collect any data from any user, including children. The app connects to user-specified servers and has no way to determine the age of its users.

## Changes to This Policy

Updates to this policy will be reflected in this document with an updated date. As Embara collects no data, material changes are unlikely.

## Contact

For questions about this privacy policy:

- **Developer:** Soult IO Ltd
- **Email:** privacy@soult.io
- **Source code:** [github.com/soult-io/embara-android](https://github.com/soult-io/embara-android)
