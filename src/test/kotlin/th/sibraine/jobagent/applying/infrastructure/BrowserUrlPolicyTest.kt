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
}
