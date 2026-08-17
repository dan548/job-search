package th.sibraine.jobagent.candidate.infrastructure

import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.candidate.domain.*
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("dev")
class DevelopmentCandidateSeed(
    private val repository: CandidateProfileJpaRepository,
    private val service: CandidateProfileService,
) : CommandLineRunner {
    override fun run(vararg args: String?) {
        if (repository.existsById(service.profileId)) return
        val skills = listOf(
            "Java", "Kotlin", "Spring Boot", "Ktor", "PostgreSQL", "Kafka", "Docker",
            "Kubernetes", "Testcontainers", "AWS", "REST API", "backend financial systems",
        )
        service.put(
            CandidateProfile(
                id = service.profileId,
                generalInfo = GeneralInfo("Development Candidate", "Backend Engineer"),
                roles = listOf("Backend Engineer"),
                skills = skills.toSet(),
                facts = skills.mapIndexed { index, skill ->
                    CandidateFact("seed-fact-${(index + 1).toString().padStart(3, '0')}", FactType.SKILL, skill, true)
                },
            )
        )
    }
}
