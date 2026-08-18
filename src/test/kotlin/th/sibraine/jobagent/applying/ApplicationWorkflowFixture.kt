package th.sibraine.jobagent.applying

import th.sibraine.jobagent.applying.application.ApplicationDraftService
import th.sibraine.jobagent.applying.application.ApplicationSettingsService
import th.sibraine.jobagent.applying.application.BrowserSessionService
import th.sibraine.jobagent.applying.domain.*
import th.sibraine.jobagent.applying.infrastructure.*
import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.candidate.application.SINGLE_USER_PROFILE_ID
import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.rendering.application.RenderResumeUseCase
import th.sibraine.jobagent.rendering.domain.RenderedResumePdf
import th.sibraine.jobagent.tailoring.application.TailorResumeUseCase
import th.sibraine.jobagent.tailoring.domain.CURRENT_TEMPLATE_ID
import th.sibraine.jobagent.tailoring.domain.CURRENT_TEMPLATE_VERSION
import th.sibraine.jobagent.tailoring.domain.ResumeVariant
import th.sibraine.jobagent.tailoring.domain.TailoringPlan
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/** In-memory wiring of the application workflow: real domain services, repositories backed by lists. */
class ApplicationWorkflowFixture(
    val vacancyId: UUID = UUID.randomUUID(),
    val variantId: UUID = UUID.randomUUID(),
    val variantReviewedAt: Instant? = Instant.EPOCH,
) {
    val profileId: UUID = UUID.fromString(SINGLE_USER_PROFILE_ID)
    var settings: ApplicationSettings = ApplicationSettings()
    val catalog: MutableList<AnswerCatalogEntry> = mutableListOf()

    val draftRows = mutableListOf<ApplicationDraftEntity>()
    val runRows = mutableListOf<ApplicationRunEntity>()
    val approvalRows = mutableListOf<ApplicationApprovalEntity>()
    val artifactRows = mutableListOf<ApplicationArtifactEntity>()
    val browserSessionRows = mutableListOf<BrowserSessionEntity>()
    val browserAuditRows = mutableListOf<BrowserAuditEntryEntity>()
    val browserDiagnosticRows = mutableListOf<BrowserDiagnosticSnapshotEntity>()

    private val drafts = mockk<ApplicationDraftJpaRepository>()
    private val runs = mockk<ApplicationRunJpaRepository>()
    private val approvals = mockk<ApplicationApprovalJpaRepository>()
    private val artifacts = mockk<ApplicationArtifactJpaRepository>()
    private val browserSessions = mockk<BrowserSessionJpaRepository>()
    private val browserAudit = mockk<BrowserAuditEntryJpaRepository>()
    private val browserDiagnostics = mockk<BrowserDiagnosticSnapshotJpaRepository>()
    private val settingsService = mockk<ApplicationSettingsService>(relaxed = true)
    private val variants = mockk<TailorResumeUseCase>()
    private val renderResume = mockk<RenderResumeUseCase>()
    private val profiles = mockk<CandidateProfileService>()

    val clock: Clock = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC)

    val service: ApplicationDraftService
    val browserSessionService: BrowserSessionService

    /** Swappable so a test can put the browser submitter behind the very same workflow. */
    var submitter: ApplicationSubmitter = ManualApplicationSubmitter()
    private val delegatingSubmitter = object : ApplicationSubmitter {
        override fun submit(command: SubmissionCommand) = submitter.submit(command)
    }

    init {
        stubDrafts()
        stubRuns()
        stubApprovals()
        stubArtifacts()
        stubBrowserSessions()

        every { settingsService.settings() } answers { settings }
        every { settingsService.catalog() } answers { catalog.toList() }
        every { settingsService.remember(any(), any(), any(), any()) } answers {
            val key = firstArg<String>()
            catalog.removeAll { it.key == key }
            catalog += AnswerCatalogEntry(key, secondArg(), thirdArg(), arg(3), Instant.now(clock))
        }

        every { variants.get(variantId) } returns variant()
        every { variants.latest(profileId, vacancyId) } returns variant()
        every { renderResume.execute(variantId) } returns RenderedResumePdf(
            bytes = "%PDF-1.7 tailored resume".toByteArray(),
            pageCount = 1,
            extractedText = "Ada Lovelace",
            clickableLinkCount = 1,
        )
        every { profiles.profileId } returns profileId
        every { profiles.get() } returns profile()

        browserSessionService = BrowserSessionService(browserSessions, browserAudit, browserDiagnostics, clock)

        service = ApplicationDraftService(
            drafts = drafts,
            runs = runs,
            approvals = approvals,
            artifacts = artifacts,
            settings = settingsService,
            variants = variants,
            renderResume = renderResume,
            profiles = profiles,
            resolver = AnswerResolver(),
            classifier = FormFieldClassifier(),
            stateMachine = ApplicationStateMachine(),
            submitter = delegatingSubmitter,
            clock = clock,
        )
    }

    fun pendingApproval(fieldKey: String): ApplicationApprovalEntity = approvalRows.first {
        it.fieldKey == fieldKey && it.status == ApprovalStatus.PENDING
    }

    fun textField(key: String, label: String, required: Boolean = true) =
        ObservedFormField(fieldKey = key, label = label, required = required)

    private fun stubDrafts() {
        every { drafts.save(any()) } answers {
            firstArg<ApplicationDraftEntity>().also { entity ->
                if (draftRows.none { it.draftId == entity.draftId }) {
                    entity.version = draftRows.size + 1L
                    draftRows += entity
                }
            }
        }
        every { drafts.findByDraftId(any()) } answers {
            draftRows.firstOrNull { it.draftId == firstArg<UUID>() }
        }
        every { drafts.findFirstByVacancyIdOrderByVersionDesc(any()) } answers {
            draftRows.filter { it.vacancyId == firstArg<UUID>() }.maxByOrNull { it.version }
        }
        every {
            drafts.findFirstByCandidateProfileIdAndVacancyIdAndStatusNotInOrderByVersionDesc(any(), any(), any())
        } answers {
            val statuses = thirdArg<Collection<ApplicationStatus>>()
            draftRows
                .filter { it.candidateProfileId == firstArg<UUID>() && it.vacancyId == secondArg<UUID>() }
                .filter { it.status !in statuses }
                .maxByOrNull { it.version }
        }
    }

    private fun stubRuns() {
        every { runs.save(any()) } answers {
            firstArg<ApplicationRunEntity>().also { entity ->
                if (runRows.none { it.runId == entity.runId }) runRows += entity
            }
        }
        every { runs.findFirstByDraftIdOrderByStartedAtDesc(any()) } answers {
            runRows.filter { it.draftId == firstArg<UUID>() }.maxByOrNull { it.startedAt }
        }
        every { runs.findByDraftIdOrderByStartedAtDesc(any()) } answers {
            runRows.filter { it.draftId == firstArg<UUID>() }.sortedByDescending { it.startedAt }
        }
        every { runs.findByDraftIdAndIdempotencyKey(any(), any()) } answers {
            runRows.firstOrNull {
                it.draftId == firstArg<UUID>() && it.idempotencyKey == secondArg<String>()
            }
        }
    }

    private fun stubApprovals() {
        every { approvals.save(any()) } answers {
            firstArg<ApplicationApprovalEntity>().also { entity ->
                if (approvalRows.none { it.approvalId == entity.approvalId }) approvalRows += entity
            }
        }
        every { approvals.findByDraftIdOrderByCreatedAtAsc(any()) } answers {
            approvalRows.filter { it.draftId == firstArg<UUID>() }.toList()
        }
        every { approvals.findById(any()) } answers {
            Optional.ofNullable(approvalRows.firstOrNull { it.approvalId == firstArg<UUID>() })
        }
    }

    private fun stubArtifacts() {
        every { artifacts.save(any()) } answers {
            firstArg<ApplicationArtifactEntity>().also { entity ->
                if (artifactRows.none { it.artifactId == entity.artifactId }) artifactRows += entity
            }
        }
        every { artifacts.findByDraftIdOrderByCreatedAtAsc(any()) } answers {
            artifactRows.filter { it.draftId == firstArg<UUID>() }.toList()
        }
        every { artifacts.findFirstByDraftIdAndType(any(), any()) } answers {
            artifactRows.firstOrNull { it.draftId == firstArg<UUID>() && it.type == secondArg() }
        }
        every { artifacts.findById(any()) } answers {
            Optional.ofNullable(artifactRows.firstOrNull { it.artifactId == firstArg<UUID>() })
        }
    }

    private fun stubBrowserSessions() {
        every { browserSessions.save(any()) } answers {
            firstArg<BrowserSessionEntity>().also { entity ->
                if (browserSessionRows.none { it.draftId == entity.draftId }) browserSessionRows += entity
            }
        }
        every { browserSessions.findByDraftId(any()) } answers {
            browserSessionRows.firstOrNull { it.draftId == firstArg<UUID>() }
        }
        every { browserAudit.save(any()) } answers {
            firstArg<BrowserAuditEntryEntity>().also { entity ->
                entity.sequence = browserAuditRows.size + 1L
                browserAuditRows += entity
            }
        }
        every { browserAudit.findByDraftIdOrderBySequenceAsc(any()) } answers {
            browserAuditRows.filter { it.draftId == firstArg<UUID>() }.sortedBy { it.sequence }
        }
        every { browserDiagnostics.save(any()) } answers {
            firstArg<BrowserDiagnosticSnapshotEntity>().also { entity ->
                entity.sequence = browserDiagnosticRows.size + 1L
                browserDiagnosticRows += entity
            }
        }
        every { browserDiagnostics.findByDraftIdOrderBySequenceAsc(any()) } answers {
            browserDiagnosticRows.filter { it.draftId == firstArg<UUID>() }.sortedBy { it.sequence }
        }
    }

    private fun profile() = CandidateProfile(
        id = profileId,
        generalInfo = GeneralInfo("Ada Lovelace", "Backend Engineer"),
        facts = listOf(CandidateFact("fact-kotlin", FactType.SKILL, "Built Kotlin services", true)),
    )

    private fun variant() = ResumeVariant(
        variantId = variantId,
        version = 3,
        vacancyId = vacancyId,
        candidateProfileId = profileId,
        baseImportId = UUID.randomUUID(),
        baseImportVersion = 2,
        templateId = CURRENT_TEMPLATE_ID,
        templateVersion = CURRENT_TEMPLATE_VERSION,
        plan = TailoringPlan(),
        resume = resume(),
        diff = emptyList(),
        createdAt = Instant.EPOCH,
        reviewedAt = variantReviewedAt,
    )

    private fun resume() = StructuredResume(
        identity = ResumeIdentity("identity-1", "Ada Lovelace", "Backend Engineer", confirmed()),
        contacts = listOf(
            ResumeContact("contact-email", ResumeContactType.EMAIL, "ada@example.com", metadata = confirmed()),
            ResumeContact("contact-phone", ResumeContactType.PHONE, "+48 111 222 333", metadata = confirmed()),
            ResumeContact("contact-city", ResumeContactType.LOCATION, "Warsaw", metadata = confirmed()),
        ),
        skills = listOf(ResumeSkill("skill-kotlin", "Kotlin", metadata = confirmed())),
    )

    private fun confirmed() = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
}
