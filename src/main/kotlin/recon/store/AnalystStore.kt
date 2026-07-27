package recon.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Policy s4.3.6 says an *authorised* analyst confirms. Authorisation is a
 * property of the person, held here rather than anywhere the agent can reach.
 *
 * The specification carried this from the start. The code did not, until a
 * review compared the two.
 */
@Serializable
data class Analyst(
    val analystId: String,
    val name: String,
    val authorised: Boolean,
)

class AnalystStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    private val analysts: Map<String, Analyst> by lazy {
        require(Files.exists(path)) { "analyst data not found: $path" }
        json.decodeFromString<List<Analyst>>(Files.readString(path)).associateBy { it.analystId }
    }

    fun find(analystId: String): Analyst? = analysts[analystId]
}
