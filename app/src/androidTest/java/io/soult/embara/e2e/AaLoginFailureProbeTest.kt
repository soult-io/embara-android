package io.soult.embara.e2e

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.soult.embara.EmbaraPrefs
import io.soult.embara.MainActivity
import io.soult.embara.e2e.support.E2EConfig
import io.soult.embara.e2e.support.ServerHealthCheck
import io.soult.embara.e2e.support.TrekE2E
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEMPORARY diagnostic: fill the login form once, submit, and dump WHAT THE PAGE SHOWS afterward —
 * error text (Invalid / Too many / MFA), whether Espresso actually filled the email field (length only,
 * never the value), whether an MFA-code field appeared, whether submit is stuck loading. Reveals why the
 * web login fails when the API + creds work. Remove after.
 */
@RunWith(AndroidJUnit4::class)
class AaLoginFailureProbeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val trek = TrekE2E(InstrumentationRegistry.getInstrumentation())

    private companion object {
        const val DUMP_JS = """
            (function(){
              try {
                var bt=(document.body&&document.body.innerText||'');
                var phrases=['Invalid','Too many','locked','try again','required','MFA','verify','code','Sign In','password'];
                var found=phrases.filter(function(p){return bt.toLowerCase().indexOf(p.toLowerCase())>=0;});
                var ev=(document.querySelector('input[type=email]')||{}).value; var emailLen=ev?ev.length:-1;
                var pv=(document.querySelector('input[type=password]')||{}).value; var pwLen=pv?pv.length:-1;
                var mfa=!!([].slice.call(document.querySelectorAll('input')).find(function(i){return /code|otp|mfa/i.test((i.name||'')+(i.placeholder||'')+(i.autocomplete||''));}));
                var sd=(document.querySelector('button[type=submit]')||{}).disabled;
                return 'path='+location.pathname+' | errPhrases='+JSON.stringify(found)+' | emailFilledLen='+emailLen+' | pwFilledLen='+pwLen+' | mfaCodeField='+mfa+' | submitDisabled='+sd+' | body="'+bt.replace(/\s+/g,' ').slice(0,220)+'"';
              } catch(e){ return 'ERR '+e; }
            })()
        """
    }

    @Before
    fun setUp() {
        ServerHealthCheck.assumeReachable()
        assumeTrue("probe skipped: no creds", E2EConfig.hasCredentials)
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
    }

    @Test
    fun probe_whyLoginFails() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val webView = trek.webViewOf(scenario)
            assertTrue("login form never rendered", trek.waitForLoginForm(webView))
            val before = trek.evalJs(
                webView,
                "String(document.querySelectorAll('input[type=email]').length+','+" +
                    "document.querySelectorAll('input[type=password]').length+','+" +
                    "document.querySelectorAll('button[type=submit]').length)",
            ).trim('"')
            trek.signIn(E2EConfig.userEmail!!, E2EConfig.password!!)
            Thread.sleep(6_000)
            throw AssertionError("LOGINPROBE before(email,pw,submit)=$before || after: ${trek.evalJs(webView, DUMP_JS)}")
        }
    }
}
