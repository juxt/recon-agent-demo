package recon

import recon.model.BreakRecord
import recon.model.VerdictStatus
import recon.policy.PolicyLoader
import recon.policy.ReconChecker
import recon.store.BreakStore
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The checker is the part a bank owns and tests. These are the cases the policy
 * describes, written down as assertions before anything agentic goes near them.
 */
class ReconCheckerTest {

    private val policy = PolicyLoader.load(Path.of("policy/tolerances.v1.yaml"))
    private val store = BreakStore(Path.of("data/breaks.json"))
    private val checker = ReconChecker(policy) { "2026-07-20T09:00:00Z" }

    private fun verdictFor(id: String) = checker.check(store.get(id))

    @Test
    fun `small clean difference is within band`() {
        val verdict = verdictFor("B-1001")
        assertEquals(VerdictStatus.WITHIN_BAND, verdict.status)
        assertEquals("TOLERANCE-USD", verdict.ruleId)
        assertEquals(BigDecimal("250.00"), verdict.thresholdApplied)
        assertTrue(verdict.permitsProposedClosure)
    }

    @Test
    fun `difference beyond the threshold is a breach`() {
        val verdict = verdictFor("B-1003")
        assertEquals(VerdictStatus.BREACH, verdict.status)
        assertEquals(BigDecimal("230.00"), verdict.thresholdApplied)
        assertFalse(verdict.permitsProposedClosure)
    }

    @Test
    fun `the cap applied is the one set for the break's currency`() {
        // B-1005 is a GBP break; the GBP cap applies, not the USD one.
        val verdict = verdictFor("B-1005")
        assertEquals(BigDecimal("200.00"), verdict.thresholdApplied)
        assertEquals("GBP", verdict.currency)
    }

    @Test
    fun `an amendment in flight escalates even inside the tolerance`() {
        val verdict = verdictFor("B-1002")
        assertEquals(VerdictStatus.ESCALATE_REQUIRED, verdict.status)
        assertEquals("ESC-AMEND-IN-FLIGHT", verdict.ruleId)
        assertFalse(verdict.permitsProposedClosure)
        assertTrue(verdict.difference < verdict.thresholdApplied!!)
    }

    @Test
    fun `an open dispute escalates even inside the tolerance`() {
        val verdict = verdictFor("B-1005")
        assertEquals(VerdictStatus.ESCALATE_REQUIRED, verdict.status)
        assertEquals("ESC-OPEN-DISPUTE", verdict.ruleId)
        assertTrue(verdict.difference < verdict.thresholdApplied!!)
    }

    @Test
    fun `an unconfirmed trade version escalates even inside the tolerance`() {
        val verdict = verdictFor("B-1004")
        assertEquals(VerdictStatus.ESCALATE_REQUIRED, verdict.status)
        assertEquals("ESC-UNCONFIRMED-VERSION", verdict.ruleId)
        assertTrue(verdict.difference < verdict.thresholdApplied!!)
    }

    @Test
    fun `a break in a currency with no configured tolerance cannot be closed by an agent`() {
        val verdict = verdictFor("B-1006")
        assertEquals(VerdictStatus.ESCALATE_REQUIRED, verdict.status)
        assertEquals("ESC-NO-TOLERANCE", verdict.ruleId)
        // No cap for JPY, so there is no threshold to measure the difference against.
        assertEquals(null, verdict.thresholdApplied)
    }

    @Test
    fun `every verdict pins the policy version it ran under`() {
        assertEquals("2026.07.v1", verdictFor("B-1001").policyVersion)
    }

    @Test
    fun `the same break checked twice gives the same answer`() {
        assertEquals(verdictFor("B-1001"), verdictFor("B-1001"))
    }

    @Test
    fun `tightening the policy flips a previously closeable break`() {
        val tightened = PolicyLoader.load(Path.of("policy/tolerances.v2.yaml"))
        val v1 = verdictFor("B-1001")
        val v2 = ReconChecker(tightened) { "2026-07-20T09:00:00Z" }.check(store.get("B-1001"))

        assertEquals(VerdictStatus.WITHIN_BAND, v1.status)
        assertEquals(VerdictStatus.BREACH, v2.status)
        assertEquals("2026.07.v1", v1.policyVersion)
        assertEquals("2026.08.v1", v2.policyVersion)
    }

    @Test
    fun `a break with no amendment and no dispute is unaffected by the triggers`() {
        val record = BreakRecord(
            breakId = "B-TEST",
            product = "INTEREST_RATE_SWAP",
            currency = "USD",
            tradeAmount = BigDecimal("1000.00"),
            confirmationAmount = BigDecimal("999.00"),
            counterpartyId = "CP-0001",
            counterpartyName = "Test Counterparty",
            raisedAt = "2026-07-20T00:00:00Z",
        )
        assertEquals(VerdictStatus.WITHIN_BAND, checker.check(record).status)
    }
}
