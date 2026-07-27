package recon.cli

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import recon.audit.AuditLog
import recon.model.Verdict
import recon.policy.PolicyLoader
import recon.policy.ReconChecker
import recon.store.AnalystStore
import recon.store.BreakStore
import recon.store.CaseHistoryStore
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess

private val json = Json { prettyPrint = true; encodeDefaults = true }

/** Fixed clock support so a demo run is byte-for-byte reproducible. */
private val clock: () -> String = {
    System.getenv("RECON_CLOCK") ?: Instant.now().toString()
}

private fun env(name: String, fallback: String): Path =
    Path.of(System.getenv(name) ?: fallback)

private val policyPath get() = env("RECON_POLICY", "policy/tolerances.v1.yaml")
private val dataPath get() = env("RECON_DATA", "data/breaks.json")
private val auditPath get() = env("RECON_AUDIT", "audit/audit.jsonl")
private val analystPath get() = env("RECON_ANALYSTS", "data/analysts.json")
private val historyPath get() = env("RECON_HISTORY", "data/case_history.json")

private fun checker() = ReconChecker(PolicyLoader.load(policyPath), clock)
private fun store() = BreakStore(dataPath)
private fun audit() = AuditLog(auditPath, clock)

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        exitProcess(1)
    }
    val flags = parseFlags(args.drop(1))
    try {
        when (args[0]) {
            "list" -> list()
            "show-break" -> showBreak(required(args, 1, "break id"))
            "history" -> history(required(args, 1, "break id"))
            "check" -> check(required(args, 1, "break id"))
            "propose-closure" -> proposeClosure(required(args, 1, "break id"), flags["rationale"])
            "escalate" -> escalate(required(args, 1, "break id"), flags["findings"])
            "confirm" -> confirm(required(args, 1, "break id"), flags["analyst"])
            "audit" -> showAudit(args.getOrNull(1))
            "verify-audit" -> verifyAudit()
            "tools" -> println(TOOL_SCHEMA.trimIndent())
            "help", "--help", "-h" -> usage()
            else -> {
                System.err.println("unknown command: ${args[0]}")
                usage()
                exitProcess(1)
            }
        }
    } catch (e: IllegalStateException) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

private fun list() {
    store().all().forEach {
        println("${it.breakId}  ${it.product.padEnd(20)} ${it.currency} diff=${it.difference.toPlainString().padStart(10)}  ${it.counterpartyName}")
    }
}

private fun showBreak(breakId: String) = println(json.encodeToString(store().get(breakId).toAgentView()))

/**
 * The resolved-case library, filtered to this break's counterparty. Context for
 * the agent's narrative, never an input to the check: nothing here can move a
 * verdict.
 */
private fun history(breakId: String) {
    val record = store().get(breakId)
    val matches = CaseHistoryStore(historyPath).forCounterparty(record.counterpartyId)
    println(json.encodeToString(matches))
}

private fun check(breakId: String): Verdict {
    val verdict = checker().check(store().get(breakId))
    audit().append(
        breakId = breakId,
        actor = "agent",
        action = "CHECK",
        outcome = verdict.status.name,
        verdictId = verdict.verdictId,
        ruleId = verdict.ruleId,
        policyVersion = verdict.policyVersion,
    )
    println(json.encodeToString(verdict))
    return verdict
}

/**
 * The gate. A closure proposal is not a sentence the model writes, it is an
 * action, and the action re-runs the check itself. A proposal that contradicts
 * the verdict cannot be submitted, however the rationale is phrased.
 */
private fun proposeClosure(breakId: String, rationale: String?) {
    require(!rationale.isNullOrBlank()) { "--rationale is required" }
    val verdict = checker().check(store().get(breakId))

    if (!verdict.permitsProposedClosure) {
        audit().append(
            breakId = breakId,
            actor = "agent",
            action = "PROPOSE_CLOSURE",
            outcome = "REFUSED",
            verdictId = verdict.verdictId,
            ruleId = verdict.ruleId,
            policyVersion = verdict.policyVersion,
            detail = "Closure proposal refused: verdict is ${verdict.status}. Rationale offered: $rationale",
        )
        System.err.println(
            """
            REFUSED. The check returned ${verdict.status} under policy ${verdict.policyVersion}.
            Rule ${verdict.ruleId}: ${verdict.reasons.firstOrNull()?.detail ?: ""}
            Policy permits no agent-proposed closure on this break. Escalate it instead.
            """.trimIndent(),
        )
        exitProcess(2)
    }

    val record = audit().append(
        breakId = breakId,
        actor = "agent",
        action = "PROPOSE_CLOSURE",
        outcome = "PROPOSED",
        verdictId = verdict.verdictId,
        ruleId = verdict.ruleId,
        policyVersion = verdict.policyVersion,
        detail = rationale,
    )
    println("Closure proposed for $breakId (audit seq ${record.seq}), pending analyst confirmation.")
    println("Verdict ${verdict.verdictId} · rule ${verdict.ruleId} · policy ${verdict.policyVersion}")
}

private fun escalate(breakId: String, findings: String?) {
    require(!findings.isNullOrBlank()) { "--findings is required" }
    val verdict = checker().check(store().get(breakId))
    val record = audit().append(
        breakId = breakId,
        actor = "agent",
        action = "ESCALATE",
        outcome = "ESCALATED",
        verdictId = verdict.verdictId,
        ruleId = verdict.ruleId,
        policyVersion = verdict.policyVersion,
        detail = findings,
    )
    println("Escalated $breakId to a person (audit seq ${record.seq}). No recommendation attached.")
}

private fun confirm(breakId: String, analyst: String?) {
    require(!analyst.isNullOrBlank()) { "--analyst is required" }
    val log = audit()

    // s4.3.5: an authorised analyst confirms. Not merely a named one.
    val person = AnalystStore(analystPath).find(analyst)
    if (person == null || !person.authorised) {
        log.append(
            breakId = breakId,
            actor = "analyst:$analyst",
            action = "CONFIRM_CLOSURE",
            outcome = "REFUSED",
            detail = if (person == null) "Unknown analyst '$analyst'." else "Analyst '$analyst' is not authorised to confirm closures.",
        )
        System.err.println(
            "REFUSED. ${if (person == null) "Unknown analyst" else "Analyst is not authorised"}: '$analyst'. " +
                "Policy s4.3.5 requires an authorised analyst to confirm a closure.",
        )
        exitProcess(2)
    }

    val proposal = log.records().lastOrNull { it.breakId == breakId && it.outcome == "PROPOSED" }
        ?: error("no open closure proposal for $breakId")
    val record = log.append(
        breakId = breakId,
        actor = "analyst:$analyst",
        action = "CONFIRM_CLOSURE",
        outcome = "CLOSED",
        verdictId = proposal.verdictId,
        ruleId = proposal.ruleId,
        policyVersion = proposal.policyVersion,
        detail = "Confirmed by ${person.name} (authorised). Proposal from audit seq ${proposal.seq}.",
    )
    println("Break $breakId closed by $analyst (audit seq ${record.seq}).")
}

private fun showAudit(breakId: String?) {
    val records = audit().records().filter { breakId == null || it.breakId == breakId }
    if (records.isEmpty()) {
        println("no audit records")
        return
    }
    records.forEach {
        println("#${it.seq} ${it.recordedAt} ${it.breakId} ${it.actor} ${it.action} -> ${it.outcome}")
        println("     rule=${it.ruleId} policy=${it.policyVersion} verdict=${it.verdictId}")
        it.detail?.let { d -> println("     $d") }
        println("     hash=${it.recordHash.take(16)} prev=${it.previousHash?.take(16) ?: "-"}")
    }
}

private fun verifyAudit() {
    val broken = audit().verify()
    if (broken == null) {
        println("audit chain intact (${audit().records().size} records)")
    } else {
        System.err.println("audit chain broken at record #$broken")
        exitProcess(1)
    }
}

private fun required(args: Array<String>, index: Int, what: String): String =
    args.getOrNull(index)?.takeIf { !it.startsWith("--") } ?: error("expected $what")

private fun parseFlags(args: List<String>): Map<String, String> {
    val flags = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            val name = arg.removePrefix("--")
            val value = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
            if (value != null) {
                flags[name] = value
                i += 2
                continue
            }
            flags[name] = "true"
        }
        i++
    }
    return flags
}

private fun usage() = println(
    """
    recon — policy-gated settlement break tooling

      list                                     every break in the store
      show-break <id>                          the break as the agent may see it
      history <id>                             past resolved cases for this counterparty
      check <id>                               run the deterministic policy check
      propose-closure <id> --rationale <text>  propose closure (gated on the check)
      escalate <id> --findings <text>          hand the case to a person
      confirm <id> --analyst <name>            the analyst click
      audit [id]                               the trail
      verify-audit                             recompute the hash chain
      tools                                    the tool contract, as the agent sees it

    Environment: RECON_POLICY, RECON_DATA, RECON_HISTORY, RECON_AUDIT, RECON_CLOCK
    """.trimIndent(),
)
