package io.soult.embara.e2e.support

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Configuration for the T3 end-to-end journeys, injected at RUN time via instrumentation arguments —
 * never committed, never baked into the APK.
 *
 * On the self-hosted FRAME-DESK runner, app/build.gradle.kts reads a host-only secret file
 * (/srv/android/secrets/trek-test.creds) at configure time and forwards the test-server URL + the
 * seeded gplay-test-acc credentials to `am instrument` as `-e` arguments. On GitHub-hosted PR runners
 * and dev machines the file is absent, the arguments are unset, and the server-dependent journeys skip
 * (see [ServerHealthCheck]) rather than fail — so a red E2E run always means the APP broke, not infra.
 *
 * Argument keys:
 *   e2eServerUrl  - the test TREK instance (e.g. https://trek-test.stabpablo.eu)
 *   e2eUserEmail  - the seeded test account
 *   e2ePassword   - the seeded test account password (from the host secret)
 */
object E2EConfig {
    private fun arg(key: String): String? =
        InstrumentationRegistry.getArguments().getString(key)?.takeIf { it.isNotBlank() }

    val serverUrl: String? get() = arg("e2eServerUrl")
    val userEmail: String? get() = arg("e2eUserEmail")
    val password: String? get() = arg("e2ePassword")

    /** A server was injected, so server-dependent journeys can run. */
    val isConfigured: Boolean get() = serverUrl != null

    /** Authenticated journeys additionally need the seeded account credentials. */
    val hasCredentials: Boolean get() = isConfigured && userEmail != null && password != null
}
