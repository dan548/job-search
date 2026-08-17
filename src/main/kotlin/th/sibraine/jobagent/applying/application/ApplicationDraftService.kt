package th.sibraine.jobagent.applying.application

import th.sibraine.jobagent.applying.domain.*
import th.sibraine.jobagent.applying.infrastructure.*
import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.rendering.application.RenderResumeUseCase
import th.sibraine.jobagent.shared.ConflictException
import th.sibraine.jobagent.shared.NotFoundException
import th.sibraine.jobagent.tailoring.application.TailorResumeUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class StartRunCommand(
    val observedFields: List<ObservedFormField>,
    val formUrl: String? = null,
    val idempotencyKey: String? = null,
    val blockingChallenges: Set<BrowserChallenge> = emptySet(),
)

data class AnswerSubmission(
    val fieldKey: String,
    val value: String? = null,
    val declined: Boolean = false,
    val saveToCatalog: Boolean = false,
    val note: String? = null,
)

data class ApprovalDecision(
    val approved: Boolean,
    val value: String? = null,
    val note: String? = null,
    val saveToCatalog: Boolean = false,
)

data class ApplicationRunResult(
    val run: ApplicationRun,
    val draft: ApplicationDraftView,
    val replayed: Boolean = false,
)

data class ApplicationArtifactContent(val artifact: ApplicationArtifact, val content: ByteArray)

/**
 * Owns the application workflow `DRAFT → FILLING → NEEDS_INPUT → READY_TO_SUBMIT → SUBMITTED/FAILED`.
 *
 * Two rules are enforced here rather than left to the caller: answers for sensitive topics come only
 * from explicit user input (see [AnswerResolver]), and the final submit needs a separate approval
 * bound to the exact state that was approved.
 */
@Service
class ApplicationDraftService(
    private val drafts: ApplicationDraftJpaRepository,
    private val runs: ApplicationRunJpaRepository,
    private val approvals: ApplicationApprovalJpaRepository,
    private val artifacts: ApplicationArtifactJpaRepository,
    private val settings: ApplicationSettingsService,
    private val variants: TailorResumeUseCase,
    private val renderResume: RenderResumeUseCase,
    private val profiles: CandidateProfileService,
    private val resolver: AnswerResolver,
    private val classifier: FormFieldClassifier,
    private val stateMachine: ApplicationStateMachine,
    private val submitter: ApplicationSubmitter,
    private val clock: Clock,
) {
    @Transactional
    fun create(vacancyId: UUID, resumeVariantId: UUID? = null): ApplicationDraftView {
        val profileId = profiles.profileId
        drafts.findFirstByCandidateProfileIdAndVacancyIdAndStatusNotInOrderByVersionDesc(
            profileId, vacancyId, TERMINAL,
        )?.let {
            throw ConflictException(
                "APPLICATION_DRAFT_ALREADY_OPEN",
                "An open application draft already exists for this vacancy",
            )
        }
        val variant = resumeVariantId?.let { variants.get(it) } ?: variants.latest(profileId, vacancyId)
        if (variant.vacancyId != vacancyId) {
            throw ConflictException(
                "RESUME_VARIANT_VACANCY_MISMATCH",
                "Resume variant belongs to another vacancy",
            )
        }
        val now = Instant.now(clock)
        val entity = drafts.save(
            ApplicationDraftEntity(
                draftId = UUID.randomUUID(),
                candidateProfileId = profileId,
                vacancyId = vacancyId,
                resumeVariantId = variant.variantId,
                resumeVariantVersion = variant.version,
                status = ApplicationStatus.DRAFT,
                stateFingerprint = stateMachine.fingerprint(variant.variantId, emptyList(), emptyList()),
                createdAt = now,
                updatedAt = now,
            )
        )
        return view(entity)
    }

    @Transactional(readOnly = true)
    fun get(draftId: UUID): ApplicationDraftView = view(find(draftId))

    @Transactional(readOnly = true)
    fun latest(vacancyId: UUID): ApplicationDraftView = drafts
        .findFirstByCandidateProfileIdAndVacancyIdOrderByVersionDesc(profiles.profileId, vacancyId)
        ?.let { view(it) }
        ?: throw NotFoundException("APPLICATION_DRAFT_NOT_FOUND", "Application draft not found")

    @Transactional(readOnly = true)
    fun runHistory(draftId: UUID): List<ApplicationRun> = runs
        .findByDraftIdOrderByStartedAtDesc(find(draftId).draftId)
        .map { it.toDomain() }

    /** Records what a form runner observed, fills what it may fill and asks for the rest. */
    @Transactional
    fun startRun(draftId: UUID, command: StartRunCommand): ApplicationRunResult {
        val entity = find(draftId)
        stateMachine.requireActive(entity.status)
        require(command.observedFields.isNotEmpty() || command.blockingChallenges.isNotEmpty()) {
            "Observed form must contain at least one field or a blocking browser challenge"
        }
        val fieldKeys = command.observedFields.map { it.fieldKey }
        require(fieldKeys.all { it.isNotBlank() }) { "Observed form fieldKey must not be blank" }
        require(fieldKeys.distinct().size == fieldKeys.size) { "Observed form fieldKey values must be unique" }

        val idempotencyKey = command.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        require(idempotencyKey == null || idempotencyKey.length <= 128) {
            "Run idempotencyKey must contain at most 128 characters"
        }
        val requestFingerprint = runRequestFingerprint(command)
        if (idempotencyKey != null) {
            runs.findByDraftIdAndIdempotencyKey(entity.draftId, idempotencyKey)?.let { existing ->
                if (existing.requestFingerprint != requestFingerprint) {
                    throw ConflictException(
                        "APPLICATION_RUN_IDEMPOTENCY_CONFLICT",
                        "The idempotency key was already used for a different browser form state",
                    )
                }
                return ApplicationRunResult(existing.toDomain(), view(entity), replayed = true)
            }
        }

        val now = Instant.now(clock)
        entity.status = stateMachine.require(entity.status, ApplicationStatus.FILLING)
        command.formUrl?.let { entity.formUrl = it }
        entity.observedFields = command.observedFields

        if (command.blockingChallenges.isNotEmpty()) {
            entity.status = stateMachine.require(entity.status, ApplicationStatus.NEEDS_INPUT)
            entity.updatedAt = now
            val run = runs.save(
                ApplicationRunEntity(
                    runId = UUID.randomUUID(),
                    draftId = entity.draftId,
                    status = ApplicationRunStatus.NEEDS_INPUT,
                    formUrl = entity.formUrl,
                    observedFields = command.observedFields,
                    pendingFieldKeys = emptyList(),
                    messages = listOf(
                        "Paused for browser challenge(s): " +
                            command.blockingChallenges.sortedBy { it.name }.joinToString(",") { it.name }
                    ),
                    idempotencyKey = idempotencyKey,
                    requestFingerprint = requestFingerprint,
                    startedAt = now,
                    finishedAt = now,
                )
            )
            return ApplicationRunResult(run.toDomain(), view(entity))
        }

        val resolution = resolve(entity, now)
        val run = runs.save(
            ApplicationRunEntity(
                runId = UUID.randomUUID(),
                draftId = entity.draftId,
                status = if (entity.status == ApplicationStatus.READY_TO_SUBMIT) {
                    ApplicationRunStatus.COMPLETED
                } else {
                    ApplicationRunStatus.NEEDS_INPUT
                },
                formUrl = entity.formUrl,
                observedFields = command.observedFields,
                filledFieldKeys = resolution.answers.map { it.fieldKey },
                pendingFieldKeys = resolution.questions.map { it.fieldKey },
                messages = messages(resolution),
                idempotencyKey = idempotencyKey,
                requestFingerprint = requestFingerprint,
                startedAt = now,
                finishedAt = now,
            )
        )
        return ApplicationRunResult(run.toDomain(), view(entity))
    }

    /** Answers pending questions, or overrides an answer the agent derived from the resume. */
    @Transactional
    fun answer(draftId: UUID, submissions: List<AnswerSubmission>): ApplicationDraftView {
        val entity = find(draftId)
        stateMachine.requireActive(entity.status)
        require(submissions.isNotEmpty()) { "At least one answer is required" }
        val now = Instant.now(clock)
        val pending = approvals.findByDraftIdOrderByCreatedAtAsc(entity.draftId)
            .filter { it.type == ApprovalType.ANSWER && it.status == ApprovalStatus.PENDING }
            .associateBy { it.fieldKey }

        submissions.forEach { submission ->
            val approval = pending[submission.fieldKey] ?: newAnswerApproval(entity, submission.fieldKey, now)
            decide(
                approval,
                ApprovalDecision(
                    approved = !submission.declined,
                    value = submission.value,
                    note = submission.note,
                    saveToCatalog = submission.saveToCatalog,
                ),
                now,
            )
        }
        resolve(entity, now)
        return view(entity)
    }

    @Transactional
    fun decideApproval(draftId: UUID, approvalId: UUID, decision: ApprovalDecision): ApplicationDraftView {
        val entity = find(draftId)
        stateMachine.requireActive(entity.status)
        val approval = approvals.findById(approvalId).orElse(null)?.takeIf { it.draftId == entity.draftId }
            ?: throw NotFoundException("APPROVAL_REQUEST_NOT_FOUND", "Approval request not found")
        val now = Instant.now(clock)
        if (approval.type == ApprovalType.SUBMIT && approval.stateFingerprint != entity.stateFingerprint) {
            throw ConflictException(
                "APPLICATION_APPROVAL_STALE",
                "The application changed after this approval was requested",
            )
        }
        decide(approval, decision, now)
        if (approval.type == ApprovalType.ANSWER) resolve(entity, now)
        return view(entity)
    }

    /**
     * Opens the separate submit approval. It is bound to the current state fingerprint, so an
     * approval never covers answers or an artifact the user has not seen.
     */
    @Transactional
    fun requestSubmitApproval(draftId: UUID): ApprovalRequest {
        val entity = find(draftId)
        if (entity.status != ApplicationStatus.READY_TO_SUBMIT) {
            throw ConflictException(
                "APPLICATION_NOT_READY",
                "Application is ${entity.status} and cannot be prepared for submission",
            )
        }
        val existing = approvals.findByDraftIdOrderByCreatedAtAsc(entity.draftId)
            .filter { it.type == ApprovalType.SUBMIT && it.status == ApprovalStatus.PENDING }
        existing.firstOrNull { it.stateFingerprint == entity.stateFingerprint }?.let { return it.toDomain() }
        val now = Instant.now(clock)
        existing.forEach { supersede(it, now) }

        val answered = entity.answers.count { it.value != null }
        return approvals.save(
            ApplicationApprovalEntity(
                approvalId = UUID.randomUUID(),
                draftId = entity.draftId,
                type = ApprovalType.SUBMIT,
                status = ApprovalStatus.PENDING,
                question = "Submit the application to vacancy ${entity.vacancyId} with $answered answers " +
                    "and resume variant ${entity.resumeVariantId}?",
                required = true,
                stateFingerprint = entity.stateFingerprint,
                createdAt = now,
            )
        ).toDomain()
    }

    /** Submits only what an approval of the current state covers. */
    @Transactional
    fun submit(draftId: UUID, reference: String? = null): ApplicationDraftView {
        val entity = find(draftId)
        if (entity.status == ApplicationStatus.SUBMITTED) return view(entity)
        if (entity.status != ApplicationStatus.READY_TO_SUBMIT) {
            throw ConflictException(
                "APPLICATION_NOT_READY",
                "Application is ${entity.status} and cannot be submitted",
            )
        }
        val approval = approvals.findByDraftIdOrderByCreatedAtAsc(entity.draftId).lastOrNull {
            it.type == ApprovalType.SUBMIT &&
                it.status == ApprovalStatus.APPROVED &&
                it.stateFingerprint == entity.stateFingerprint
        } ?: throw ConflictException(
            "APPLICATION_SUBMIT_NOT_APPROVED",
            "Submitting requires a separate user approval of the current application state",
        )

        val stored = artifacts.findByDraftIdOrderByCreatedAtAsc(entity.draftId)
        val receipt = submitter.submit(
            SubmissionCommand(
                draft = entity.toDomain(),
                artifacts = stored.map { it.toDomain() },
                approvalId = approval.approvalId,
                approvedStateFingerprint = checkNotNull(approval.stateFingerprint) {
                    "A submit approval always carries the state it covers"
                },
                reference = reference,
            )
        )
        val now = Instant.now(clock)
        entity.status = stateMachine.require(entity.status, ApplicationStatus.SUBMITTED)
        entity.submission = ApplicationSubmission(
            mode = receipt.mode,
            approvalId = approval.approvalId,
            reference = receipt.reference,
            note = receipt.note,
            submittedAt = now,
        )
        entity.updatedAt = now
        return view(entity)
    }

    @Transactional
    fun fail(draftId: UUID, reason: String): ApplicationDraftView {
        val entity = find(draftId)
        stateMachine.requireActive(entity.status)
        val now = Instant.now(clock)
        entity.status = stateMachine.require(entity.status, ApplicationStatus.FAILED)
        entity.failureReason = reason
        entity.updatedAt = now
        runs.findFirstByDraftIdOrderByStartedAtDesc(entity.draftId)?.let { run ->
            if (run.finishedAt == null || run.status == ApplicationRunStatus.NEEDS_INPUT) {
                run.status = ApplicationRunStatus.FAILED
                run.messages = run.messages + reason
                run.finishedAt = now
            }
        }
        approvals.findByDraftIdOrderByCreatedAtAsc(entity.draftId)
            .filter { it.status == ApprovalStatus.PENDING }
            .forEach { supersede(it, now) }
        return view(entity)
    }

    @Transactional(readOnly = true)
    fun artifact(draftId: UUID, artifactId: UUID): ApplicationArtifactContent =
        artifacts.findById(artifactId).orElse(null)?.takeIf { it.draftId == draftId }
            ?.let { ApplicationArtifactContent(it.toDomain(), it.content) }
            ?: throw NotFoundException("APPLICATION_ARTIFACT_NOT_FOUND", "Application artifact not found")

    private fun resolve(entity: ApplicationDraftEntity, now: Instant): AnswerResolution {
        val variant = variants.get(entity.resumeVariantId)
        val stored = approvals.findByDraftIdOrderByCreatedAtAsc(entity.draftId)
        val userAnswers = stored
            .filter { it.type == ApprovalType.ANSWER && it.fieldKey != null }
            .filter { it.status == ApprovalStatus.APPROVED || it.status == ApprovalStatus.REJECTED }
            .associate {
                it.fieldKey!! to UserAnswer(it.decisionValue, declined = it.status == ApprovalStatus.REJECTED)
            }
        val resumeArtifact = resumeArtifact(entity, now)
        val resolution = resolver.resolve(
            AnswerResolutionRequest(
                fields = entity.observedFields,
                resume = variant.resume,
                profile = profiles.get(),
                settings = settings.settings(),
                catalog = settings.catalog(),
                userAnswers = userAnswers,
                resumeArtifact = resumeArtifact.toDomain(),
            )
        )
        entity.answers = resolution.answers
        entity.stateFingerprint = stateMachine.fingerprint(
            entity.resumeVariantId,
            resolution.answers,
            artifacts.findByDraftIdOrderByCreatedAtAsc(entity.draftId).map { it.toDomain() },
        )
        // Runs after the new fingerprint so a submit approval for an older state is retired here.
        syncApprovals(entity, stored, resolution.questions, now)
        entity.status = stateMachine.require(entity.status, stateMachine.statusAfter(resolution))
        entity.updatedAt = now
        return resolution
    }

    private fun syncApprovals(
        entity: ApplicationDraftEntity,
        stored: List<ApplicationApprovalEntity>,
        questions: List<ApplicationQuestion>,
        now: Instant,
    ) {
        val answerApprovals = stored.filter { it.type == ApprovalType.ANSWER }
        val asked = questions.map { it.fieldKey }.toSet()

        questions.forEach { question ->
            val open = answerApprovals.firstOrNull {
                it.fieldKey == question.fieldKey && it.status == ApprovalStatus.PENDING
            }
            if (open != null && open.question == question.question && open.reason == question.reason &&
                open.options == question.options
            ) {
                return@forEach
            }
            answerApprovals
                .filter { it.fieldKey == question.fieldKey && it.status != ApprovalStatus.SUPERSEDED }
                .forEach { supersede(it, now) }
            approvals.save(
                ApplicationApprovalEntity(
                    approvalId = UUID.randomUUID(),
                    draftId = entity.draftId,
                    type = ApprovalType.ANSWER,
                    status = ApprovalStatus.PENDING,
                    question = question.question,
                    fieldKey = question.fieldKey,
                    topic = question.topic,
                    reason = question.reason,
                    required = question.required,
                    options = question.options,
                    catalogKey = question.catalogKey,
                    createdAt = now,
                )
            )
        }

        answerApprovals
            .filter { it.status == ApprovalStatus.PENDING && it.fieldKey !in asked }
            .forEach { supersede(it, now) }

        stored
            .filter { it.type == ApprovalType.SUBMIT && it.status == ApprovalStatus.PENDING }
            .filter { it.stateFingerprint != entity.stateFingerprint }
            .forEach { supersede(it, now) }
    }

    private fun decide(approval: ApplicationApprovalEntity, decision: ApprovalDecision, now: Instant) {
        if (approval.status != ApprovalStatus.PENDING) {
            val code = if (approval.status == ApprovalStatus.SUPERSEDED) "APPLICATION_APPROVAL_STALE"
            else "APPROVAL_ALREADY_DECIDED"
            throw ConflictException(code, "Approval request is ${approval.status}")
        }
        if (decision.approved) {
            if (approval.type == ApprovalType.ANSWER) {
                val value = decision.value?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("An approved answer must contain a value")
                approval.decisionValue = value
                if (decision.saveToCatalog) {
                    settings.remember(
                        key = approval.catalogKey ?: classifier.catalogKey(
                            approval.topic ?: FormFieldTopic.UNKNOWN,
                            approval.question,
                        ),
                        question = approval.question,
                        value = value,
                        topic = approval.topic ?: FormFieldTopic.UNKNOWN,
                    )
                }
            }
            approval.status = ApprovalStatus.APPROVED
        } else {
            approval.status = ApprovalStatus.REJECTED
        }
        approval.decisionNote = decision.note
        approval.decidedAt = now
    }

    private fun newAnswerApproval(
        entity: ApplicationDraftEntity,
        fieldKey: String,
        now: Instant,
    ): ApplicationApprovalEntity {
        val field = entity.observedFields.firstOrNull { it.fieldKey == fieldKey }
            ?: throw NotFoundException("APPLICATION_FIELD_NOT_FOUND", "Form field $fieldKey was not observed")
        val topic = classifier.classify(field)
        return approvals.save(
            ApplicationApprovalEntity(
                approvalId = UUID.randomUUID(),
                draftId = entity.draftId,
                type = ApprovalType.ANSWER,
                status = ApprovalStatus.PENDING,
                question = field.label,
                fieldKey = fieldKey,
                topic = topic,
                reason = QuestionReason.NO_EXPLICIT_ANSWER,
                required = field.required,
                options = field.options,
                catalogKey = classifier.catalogKey(topic, field.label),
                createdAt = now,
            )
        )
    }

    private fun resumeArtifact(entity: ApplicationDraftEntity, now: Instant): ApplicationArtifactEntity =
        artifacts.findFirstByDraftIdAndType(entity.draftId, ApplicationArtifactType.RESUME_PDF)
            ?: renderResume.execute(entity.resumeVariantId).let { rendered ->
                artifacts.save(
                    ApplicationArtifactEntity(
                        artifactId = UUID.randomUUID(),
                        draftId = entity.draftId,
                        type = ApplicationArtifactType.RESUME_PDF,
                        fileName = "resume-${entity.resumeVariantId}.pdf",
                        contentType = "application/pdf",
                        sha256 = sha256(rendered.bytes),
                        byteSize = rendered.bytes.size,
                        content = rendered.bytes,
                        createdAt = now,
                    )
                )
            }

    private fun supersede(approval: ApplicationApprovalEntity, now: Instant) {
        approval.status = ApprovalStatus.SUPERSEDED
        approval.decidedAt = now
        approval.decisionNote = "Superseded by a newer application state"
    }

    private fun messages(resolution: AnswerResolution): List<String> = buildList {
        add("Filled ${resolution.answers.count { it.value != null }} of ${resolution.answers.size + resolution.questions.size} fields")
        if (resolution.questions.isNotEmpty()) {
            add("Paused on ${resolution.questions.size} question(s), ${resolution.blockingQuestions.size} of them required")
        }
    }

    private fun view(entity: ApplicationDraftEntity) = ApplicationDraftView(
        draft = entity.toDomain(),
        pendingApprovals = approvals.findByDraftIdOrderByCreatedAtAsc(entity.draftId)
            .filter { it.status == ApprovalStatus.PENDING }
            .map { it.toDomain() },
        artifacts = artifacts.findByDraftIdOrderByCreatedAtAsc(entity.draftId).map { it.toDomain() },
        latestRun = runs.findFirstByDraftIdOrderByStartedAtDesc(entity.draftId)?.toDomain(),
    )

    private fun find(draftId: UUID): ApplicationDraftEntity = drafts.findByDraftId(draftId)
        ?: throw NotFoundException("APPLICATION_DRAFT_NOT_FOUND", "Application draft not found")

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

    private fun runRequestFingerprint(command: StartRunCommand): String = FormObservationDigest.of(
        command.formUrl,
        command.blockingChallenges,
        command.observedFields,
    )

    private companion object {
        private val TERMINAL = listOf(ApplicationStatus.SUBMITTED, ApplicationStatus.FAILED)
    }
}
