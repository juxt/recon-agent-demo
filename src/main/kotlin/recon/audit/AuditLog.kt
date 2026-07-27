package recon.audit

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Append-only, hash-chained trail. Every check, every proposal, every refusal
 * and every confirmation lands here, and each record carries the hash of the one
 * before it, so a record cannot be altered or removed without breaking the chain.
 */
@Serializable
data class AuditRecord(
    val seq: Int,
    val recordedAt: String,
    val breakId: String,
    val actor: String,
    val action: String,
    val outcome: String,
    val verdictId: String? = null,
    val ruleId: String? = null,
    val policyVersion: String? = null,
    val detail: String? = null,
    val previousHash: String? = null,
    val recordHash: String = "",
)

class AuditLog(private val path: Path, private val clock: () -> String) {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    fun records(): List<AuditRecord> {
        if (!Files.exists(path)) return emptyList()
        return Files.readAllLines(path).filter { it.isNotBlank() }.map { json.decodeFromString(it) }
    }

    fun append(
        breakId: String,
        actor: String,
        action: String,
        outcome: String,
        verdictId: String? = null,
        ruleId: String? = null,
        policyVersion: String? = null,
        detail: String? = null,
    ): AuditRecord {
        val existing = records()
        val previous = existing.lastOrNull()
        val draft = AuditRecord(
            seq = existing.size + 1,
            recordedAt = clock(),
            breakId = breakId,
            actor = actor,
            action = action,
            outcome = outcome,
            verdictId = verdictId,
            ruleId = ruleId,
            policyVersion = policyVersion,
            detail = detail,
            previousHash = previous?.recordHash,
        )
        val sealed = draft.copy(recordHash = hash(draft))

        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            json.encodeToString(sealed) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        return sealed
    }

    /** Recomputes the chain from the start. Returns the first sequence number that fails, or null. */
    fun verify(): Int? {
        var previousHash: String? = null
        for (record in records()) {
            if (record.previousHash != previousHash) return record.seq
            if (record.recordHash != hash(record.copy(recordHash = ""))) return record.seq
            previousHash = record.recordHash
        }
        return null
    }

    private fun hash(record: AuditRecord): String {
        val payload = listOf(
            record.seq.toString(),
            record.recordedAt,
            record.breakId,
            record.actor,
            record.action,
            record.outcome,
            record.verdictId ?: "NULL",
            record.ruleId ?: "NULL",
            record.policyVersion ?: "NULL",
            record.detail ?: "NULL",
            record.previousHash ?: "NULL",
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
