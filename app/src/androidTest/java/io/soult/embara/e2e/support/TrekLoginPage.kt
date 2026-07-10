package io.soult.embara.e2e.support

import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator

/**
 * Page Object for TREK's login screen. Selectors are TREK's real semantic hooks (email/password by input
 * TYPE, submit by `type=submit`) — the standards order (Testing Library / Playwright): role/semantics
 * over structure, never position/nth-child. Keeping the login selectors here means a TREK change is a
 * one-place update.
 *
 * FILL vs CLICK: TREK's login inputs are REACT-CONTROLLED (`value={state} onChange`). Espresso-Web's
 * `webKeys` sends key events that React's onChange does NOT pick up here — the fields end up empty and
 * TREK's own validation ("Email and password are required") blocks the submit. So the VALUE is set the
 * React-correct way (native value setter + an 'input' event, see [fillFormJs], run via the WebView), and
 * only the real submit CLICK stays on Espresso-Web.
 */
object TrekLoginPage {

    private const val EMAIL = "input[type=email]"
    private const val PASSWORD = "input[type=password]"
    private const val SUBMIT = "button[type=submit]"

    /**
     * JS that fills the login inputs the way React registers a change: the native HTMLInputElement value
     * setter, then an 'input' (+ 'change') event so React's onChange runs. [emailLit]/[passLit] MUST be
     * JS string literals (quoted, e.g. via JSONObject.quote). Returns "true"/"false". The password only
     * reaches the WebView's JS engine — it is never logged, returned, or put in a failure message.
     */
    fun fillFormJs(emailLit: String, passLit: String): String = """
        (function(){
          function set(sel, val){
            var el = document.querySelector(sel);
            if (!el) return false;
            var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
            (d && d.set ? d.set : function(v){ el.value = v; }).call(el, val);
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            return true;
          }
          try { return String(set('$EMAIL', $emailLit) && set('$PASSWORD', $passLit)); }
          catch (e) { return 'false'; }
        })()
    """.trimIndent()

    /** Clicks the Sign In button via Espresso-Web (a real click that drives the SPA's submit handler). */
    fun clickSignIn() {
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, SUBMIT))
            .perform(webClick())
    }
}
