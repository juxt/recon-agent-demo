package recon.store

import kotlinx.serialization.json.Json
import recon.model.ResolvedCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The resolved-case library: past breaks and how they were resolved. It is the
 * model's context, not the check's. The agent reads it to enrich a narrative; the
 * checker never opens it. Retrieval here is a plain filter by counterparty,
 * standing in for what a production loop would do with a search index over a much
 * larger history.
 */
class CaseHistoryStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    private val cases: List<ResolvedCase> by lazy {
        if (!Files.exists(path)) emptyList()
        else json.decodeFromString<List<ResolvedCase>>(Files.readString(path))
    }

    /** Past resolutions for the counterparty on a break, most recent first. */
    fun forCounterparty(counterpartyId: String): List<ResolvedCase> =
        cases.filter { it.counterpartyId == counterpartyId }.sortedByDescending { it.resolvedAt }
}
