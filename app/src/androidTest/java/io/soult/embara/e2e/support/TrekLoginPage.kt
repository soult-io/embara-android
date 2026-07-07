package io.soult.embara.e2e.support

import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.clearElement
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.DriverAtoms.webKeys
import androidx.test.espresso.web.webdriver.Locator

/**
 * Page Object for TREK's login screen, driven with Espresso-Web — the official Android WebView test API
 * (WebDriver atoms) with built-in synchronization, so there are no hand-rolled `evaluateJavascript`
 * polls or `Thread.sleep`s here.
 *
 * Selectors use TREK's real hooks, verified by an on-device DOM audit: the email and password fields by
 * their semantic input TYPE, and the submit control by `type=submit` (its accessible name is "Sign In").
 * TREK exposes no id/name/data-testid on the login form, so these semantic selectors are the most stable
 * available — the standards order (Testing Library / Playwright): prefer role/semantics over structure,
 * never position/nth-child. Keeping every login selector in this one class means a TREK change is a
 * one-place update.
 */
object TrekLoginPage {

    private const val EMAIL = "input[type=email]"
    private const val PASSWORD = "input[type=password]"
    private const val SUBMIT = "button[type=submit]"

    /**
     * Types the credentials into the login form and submits. Espresso-Web auto-synchronizes on each
     * element, so no explicit waits are needed. The PASSWORD is never surfaced: if entering it fails,
     * the error is re-thrown generically so no Espresso message can carry the value into the report.
     */
    fun signIn(email: String, password: String) {
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, EMAIL))
            .perform(clearElement())
            .perform(webKeys(email))
        try {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, PASSWORD))
                .perform(clearElement())
                .perform(webKeys(password))
        } catch (_: RuntimeException) {
            throw AssertionError("Failed to enter the password into the TREK login form.")
        }
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, SUBMIT))
            .perform(webClick())
    }
}
