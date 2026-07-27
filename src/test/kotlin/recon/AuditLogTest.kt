package recon

import recon.audit.AuditLog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuditLogTest {

    private val path: Path = Files.createTempDirectory("recon-audit").resolve("audit.jsonl")
    private var tick = 0
    private val log = AuditLog(path) { "2026-07-20T09:0${tick++}:00Z" }

    @AfterTest
    fun cleanup() {
        path.deleteIfExists()
    }

    @Test
    fun `records chain to their predecessor`() {
        val first = log.append("B-1001", "agent", "CHECK", "WITHIN_BAND")
        val second = log.append("B-1001", "agent", "PROPOSE_CLOSURE", "PROPOSED")

        assertNull(first.previousHash)
        assertEquals(first.recordHash, second.previousHash)
        assertEquals(1, first.seq)
        assertEquals(2, second.seq)
    }

    @Test
    fun `an intact chain verifies`() {
        log.append("B-1001", "agent", "CHECK", "WITHIN_BAND")
        log.append("B-1001", "agent", "PROPOSE_CLOSURE", "PROPOSED")
        log.append("B-1001", "analyst:asel", "CONFIRM_CLOSURE", "CLOSED")

        assertNull(log.verify())
        assertEquals(3, log.records().size)
    }

    @Test
    fun `editing a record breaks the chain`() {
        log.append("B-1003", "agent", "CHECK", "BREACH")
        log.append("B-1003", "agent", "PROPOSE_CLOSURE", "REFUSED")
        log.append("B-1003", "agent", "ESCALATE", "ESCALATED")

        // Someone rewrites history: the refusal becomes an approval.
        val tampered = Files.readAllLines(path).map { it.replace("\"REFUSED\"", "\"PROPOSED\"") }
        Files.write(path, tampered)

        assertEquals(2, assertNotNull(log.verify()))
    }

    @Test
    fun `removing a record breaks the chain`() {
        log.append("B-1001", "agent", "CHECK", "WITHIN_BAND")
        log.append("B-1001", "agent", "PROPOSE_CLOSURE", "PROPOSED")
        log.append("B-1001", "analyst:asel", "CONFIRM_CLOSURE", "CLOSED")

        val withoutMiddle = Files.readAllLines(path).filterIndexed { index, _ -> index != 1 }
        Files.write(path, withoutMiddle)

        assertNotNull(log.verify())
    }
}
