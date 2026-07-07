package io.soult.embara.e2e.support

import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator

/**
 * Page Object for TREK's authenticated dashboard, driven by Espresso-Web. Navigation targets the real
 * `<a>` nav items by their visible link text (verified by the on-device DOM audit — "My Trips", "Vacay",
 * "Atlas") via `Locator.LINK_TEXT`: a user-facing, resilient selector (select by text/role, not by
 * structure), with Espresso-Web's built-in synchronization.
 */
object TrekDashboardPage {

    /** Clicks a top-level nav item by its visible link text (e.g. "My Trips", "Vacay", "Atlas"). */
    fun navigateTo(linkText: String) {
        onWebView()
            .withElement(findElement(Locator.LINK_TEXT, linkText))
            .perform(webClick())
    }
}
