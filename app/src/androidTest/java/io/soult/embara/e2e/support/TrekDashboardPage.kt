package io.soult.embara.e2e.support

import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator

/**
 * Page Object for TREK's authenticated dashboard, driven by Espresso-Web. Nav targets are NOT hard-coded
 * labels (which drift with copy/i18n and vary by route); the caller discovers a real in-app route from
 * the live DOM (see [TrekE2E.firstInAppNavTarget]) and this clicks the `<a>` whose href carries that
 * route — a structural selector on the app's own routing, with Espresso-Web's built-in synchronization.
 */
object TrekDashboardPage {

    /**
     * Clicks the in-app nav link whose href targets [route] (a pathname[+hash] discovered from the live
     * DOM). Matches href by substring so a relative ("/trips") or absolute ("https://…/trips") href both
     * resolve.
     */
    fun navigateToRoute(route: String) {
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "a[href*=\"$route\"]"))
            .perform(webClick())
    }
}
