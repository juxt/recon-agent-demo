package recon.policy

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import recon.model.BigDecimalSerializer
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path

/**
 * The policy as configuration the business owns, not code a vendor owns.
 *
 * Everything here is data: thresholds, the products they apply to, and the
 * conditions that force a person to look. Changing a tolerance is a config
 * change with a new version, not a code release.
 */
@Serializable
data class PolicyDocument(
    @SerialName("policy_version") val policyVersion: String,
    val source: String,
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("approved_by") val approvedBy: String,
    @SerialName("closure_tolerances") val closureTolerances: List<Tolerance>,
    @SerialName("escalation_triggers") val escalationTriggers: List<EscalationTrigger>,
)

@Serializable
data class Tolerance(
    val id: String,
    val currency: String,
    @Serializable(with = BigDecimalSerializer::class) @SerialName("absolute_cap") val absoluteCap: BigDecimal,
) {
    /**
     * The cap is set per currency and stated in that currency. A cap denominated
     * in one currency tells you nothing about a difference denominated in
     * another, so an unmatched currency is not a near miss: it is no tolerance at
     * all. The committee sets an equivalent cap for each currency rather than
     * converting one cap at a floating rate, because a tolerance that moves with
     * the FX market cannot be audited after the fact.
     */
    fun matches(currency: String): Boolean = this.currency == currency

    /** Policy s4.3.2: the threshold is the cap set for the break's currency. */
    fun threshold(): BigDecimal = absoluteCap.setScale(2, RoundingMode.HALF_UP)
}

/**
 * A condition that sends the case to a person whatever the tolerance says.
 * `condition` is drawn from a closed vocabulary the checker knows how to evaluate:
 * config decides which triggers apply, code decides what they mean.
 */
@Serializable
data class EscalationTrigger(
    val id: String,
    val condition: String,
    val detail: String,
)

object PolicyLoader {
    private val yaml = Yaml.default

    fun load(path: Path): PolicyDocument {
        require(Files.exists(path)) { "policy file not found: $path" }
        return yaml.decodeFromString(PolicyDocument.serializer(), Files.readString(path))
    }
}
