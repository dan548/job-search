package th.sibraine.jobagent.applying.infrastructure

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BrowserUrlPolicyTest {
    private val policy = BrowserUrlPolicy(listOf("boards.greenhouse.io", ".jobs.example.com"))

    @Test
    fun `allows only https destinations on configured hosts or their subdomains`() {
        assertTrue(policy.allows("https://boards.greenhouse.io/acme/jobs/1"))
        assertTrue(policy.allows("https://eu.jobs.example.com/apply"))
        assertFalse(policy.allows("http://boards.greenhouse.io/acme/jobs/1"))
        assertFalse(policy.allows("https://boards.greenhouse.io.evil.example/apply"))
        assertFalse(policy.allows("https://user:secret@boards.greenhouse.io/apply"))
        assertFalse(policy.allows("https://127.0.0.1/internal"))
    }

    @Test
    fun `requires an explicit allowlist`() {
        assertThrows<IllegalArgumentException> { BrowserUrlPolicy(emptyList()) }
        assertThrows<IllegalArgumentException> { policy.requireAllowed("https://example.net/apply") }
    }

    @Test
    fun `local fixture mode permits only explicitly allowlisted loopback http`() {
        val local = BrowserUrlPolicy(listOf("127.0.0.1"), allowHttpLocalhost = true)

        assertTrue(local.allows("http://127.0.0.1:8080/lever/apply"))
        assertFalse(local.allows("http://localhost:8080/lever/apply"))
        assertFalse(local.allows("http://192.168.1.10:8080/lever/apply"))
        assertFalse(BrowserUrlPolicy(listOf("example.com"), allowHttpLocalhost = true).allows("http://example.com/apply"))
        assertFalse(BrowserUrlPolicy(listOf("127.0.0.1")).allows("http://127.0.0.1:8080/lever/apply"))
    }
}
