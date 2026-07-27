package recon

import recon.store.AnalystStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Policy s4.3.5 says an *authorised* analyst confirms a closure.
 *
 * The specification carried `Analyst.authorised` from the first draft. The code
 * accepted any name at all until a review put the two side by side. These are
 * the assertions that review should have produced first.
 */
class AnalystAuthorisationTest {

    private val analysts = AnalystStore(Path.of("data/analysts.json"))

    @Test
    fun `an authorised analyst is recognised`() {
        val analyst = analysts.find("asel")
        assertEquals("Asel Duman", analyst?.name)
        assertTrue(analyst!!.authorised)
    }

    @Test
    fun `a known but unauthorised analyst is not permitted to confirm`() {
        assertFalse(analysts.find("tempstaff")!!.authorised)
    }

    @Test
    fun `an unknown analyst is not permitted to confirm`() {
        assertNull(analysts.find("nobody"))
    }
}
