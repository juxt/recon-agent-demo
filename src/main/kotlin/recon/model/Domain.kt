package recon.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal

/**
 * Money is BigDecimal end to end. No doubles anywhere near a settlement amount.
 */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())
}

/**
 * A break as the reconciliation platform raised it. This is the system of record:
 * the agent never supplies these figures, it only supplies the break id.
 */
@Serializable
data class BreakRecord(
    val breakId: String,
    val product: String,
    val currency: String,
    @Serializable(with = BigDecimalSerializer::class) val tradeAmount: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class) val confirmationAmount: BigDecimal,
    val counterpartyId: String,
    val counterpartyName: String,
    val raisedAt: String,
    val amendmentInFlight: Boolean = false,
    val confirmedVersion: Int = 1,
    val currentVersion: Int = 1,
    val openDispute: Boolean = false,
    val note: String? = null,
) {
    /** Absolute settlement difference. The only figure the tolerance is measured against. */
    val difference: BigDecimal get() = (tradeAmount - confirmationAmount).abs()

    /** The projection the agent is allowed to see, per the AgentToolset surface. */
    fun toAgentView(): BreakView = BreakView(
        breakId = breakId,
        product = product,
        currency = currency,
        difference = difference,
        counterpartyId = counterpartyId,
        counterpartyName = counterpartyName,
        raisedAt = raisedAt,
        note = note,
    )
}

/**
 * What `show-break` returns: the case as the agent is allowed to see it. It is a
 * deliberate projection of BreakRecord, and what it leaves out is the point. The
 * agent sees the derived difference but never the two raw amounts it came from,
 * and none of the trigger inputs the check evaluates (an amendment in flight, a
 * version mismatch, an open dispute). It learns which of those fired from the
 * verdict, not by inspecting the record. It narrates and routes; it never handles
 * the figures and never decides a trigger for itself.
 */
@Serializable
data class BreakView(
    val breakId: String,
    val product: String,
    val currency: String,
    @Serializable(with = BigDecimalSerializer::class) val difference: BigDecimal,
    val counterpartyId: String,
    val counterpartyName: String,
    val raisedAt: String,
    val note: String? = null,
)

/**
 * A past break and how it was resolved. This is the model's context and only the
 * model's: the resolved-case library the agent reads to see whether a break like
 * this one has settled cleanly before. Nothing here can move a verdict. The
 * checker never opens it; it informs the narrative and reaches a person, and it
 * is nowhere in the specification, because the specification governs what the
 * check does and the check does not read history.
 */
@Serializable
data class ResolvedCase(
    val caseId: String,
    val counterpartyId: String,
    val counterpartyName: String,
    val product: String,
    val currency: String,
    @Serializable(with = BigDecimalSerializer::class) val difference: BigDecimal,
    val resolvedAt: String,
    val outcome: String,
    val summary: String,
)

enum class VerdictStatus {
    /** Inside the configured tolerance and no escalation trigger fired. Closure may be proposed. */
    WITHIN_BAND,

    /** Difference exceeds the configured tolerance. */
    BREACH,

    /** Policy requires a person regardless of the tolerance. */
    ESCALATE_REQUIRED,
}

@Serializable
data class Reason(val ruleId: String, val detail: String)

/**
 * What the checker hands back. Every field here is what an auditor asks for:
 * which rule fired, against which threshold, under which version of the policy.
 */
@Serializable
data class Verdict(
    val verdictId: String,
    val breakId: String,
    val status: VerdictStatus,
    val ruleId: String,
    @Serializable(with = BigDecimalSerializer::class) val difference: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class) val thresholdApplied: BigDecimal?,
    val currency: String,
    val policyVersion: String,
    val policySource: String,
    val reasons: List<Reason>,
    val checkedAt: String,
) {
    val permitsProposedClosure: Boolean get() = status == VerdictStatus.WITHIN_BAND
}
