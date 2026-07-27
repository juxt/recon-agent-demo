package recon.store

import kotlinx.serialization.json.Json
import recon.model.BreakRecord
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stands in for the reconciliation platform. In a real deployment the checker
 * would read the break from that system directly; what matters is that the
 * figures come from the system of record and never from the agent.
 */
class BreakStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    private val records: Map<String, BreakRecord> by lazy {
        require(Files.exists(path)) { "break data not found: $path" }
        json.decodeFromString<List<BreakRecord>>(Files.readString(path)).associateBy { it.breakId }
    }

    fun get(breakId: String): BreakRecord =
        records[breakId] ?: error("no such break: $breakId")

    fun all(): List<BreakRecord> = records.values.sortedBy { it.breakId }
}
