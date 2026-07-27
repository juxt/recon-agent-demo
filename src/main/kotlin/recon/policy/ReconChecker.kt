package recon.policy

import recon.model.BreakRecord
import recon.model.Reason
import recon.model.Verdict
import recon.model.VerdictStatus
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * The deterministic half.
 *
 * Ordinary software, written and tested in advance, owned by the bank. It takes
 * a break record and a policy document and returns a verdict. No model is
 * involved and none can be: the same inputs always produce the same answer.
 */
class ReconChecker(private val policy: PolicyDocument, private val clock: () -> String) {

    fun check(record: BreakRecord): Verdict {
        val tolerance = policy.closureTolerances.firstOrNull {
            it.matches(record.currency)
        }

        val threshold = tolerance?.threshold()
        val reasons = mutableListOf<Reason>()

        // Policy s4.3.3: conditions that require a person whatever the tolerance says.
        val fired = policy.escalationTriggers.filter { it.holdsFor(record) }
        fired.forEach { reasons += Reason(it.id, it.detail) }

        val status: VerdictStatus
        val ruleId: String

        when {
            tolerance == null -> {
                status = VerdictStatus.ESCALATE_REQUIRED
                ruleId = "ESC-NO-TOLERANCE"
                reasons += Reason(
                    ruleId,
                    "No closure tolerance is configured for ${record.currency}. A cap set in another " +
                        "currency is not applied here by conversion, so policy permits no agent-proposed " +
                        "closure and the case is escalated.",
                )
            }

            fired.isNotEmpty() -> {
                status = VerdictStatus.ESCALATE_REQUIRED
                ruleId = fired.first().id
                reasons += Reason(
                    tolerance.id,
                    "Difference ${record.difference.toPlainString()} ${record.currency} against a " +
                        "threshold of ${threshold!!.toPlainString()} ${record.currency}, but an " +
                        "escalation trigger takes precedence.",
                )
            }

            record.difference <= threshold!! -> {
                status = VerdictStatus.WITHIN_BAND
                ruleId = tolerance.id
                reasons += Reason(
                    tolerance.id,
                    "Difference ${record.difference.toPlainString()} ${record.currency} is within the " +
                        "${threshold.toPlainString()} ${record.currency} closure tolerance.",
                )
            }

            else -> {
                status = VerdictStatus.BREACH
                ruleId = tolerance.id
                reasons += Reason(
                    tolerance.id,
                    "Difference ${record.difference.toPlainString()} ${record.currency} exceeds the " +
                        "threshold of ${threshold.toPlainString()} ${record.currency}.",
                )
            }
        }

        val checkedAt = clock()
        return Verdict(
            verdictId = verdictId(record.breakId, status, ruleId, policy.policyVersion, record.difference, checkedAt),
            breakId = record.breakId,
            status = status,
            ruleId = ruleId,
            difference = record.difference,
            thresholdApplied = threshold,
            currency = record.currency,
            policyVersion = policy.policyVersion,
            policySource = policy.source,
            reasons = reasons,
            checkedAt = checkedAt,
        )
    }

    private fun verdictId(
        breakId: String,
        status: VerdictStatus,
        ruleId: String,
        policyVersion: String,
        difference: BigDecimal,
        checkedAt: String,
    ): String {
        val payload = listOf(breakId, status.name, ruleId, policyVersion, difference.toPlainString(), checkedAt)
            .joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return "v_" + digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}

/**
 * Config names a condition; code decides what it means. A closed vocabulary keeps
 * the policy declarative without letting it smuggle in arbitrary behaviour.
 */
private fun EscalationTrigger.holdsFor(record: BreakRecord): Boolean = when (condition) {
    "amendment_in_flight" -> record.amendmentInFlight
    "confirmation_version_mismatch" -> record.confirmedVersion != record.currentVersion
    "open_dispute" -> record.openDispute
    else -> error("unknown escalation condition '$condition' in trigger $id")
}
