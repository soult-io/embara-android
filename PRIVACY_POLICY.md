# Privacy Policy

**Embara — for TREK**
*Last updated: April 29, 2026*

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
