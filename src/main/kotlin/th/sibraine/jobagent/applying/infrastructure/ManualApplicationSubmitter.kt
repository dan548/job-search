package th.sibraine.jobagent.applying.infrastructure

import th.sibraine.jobagent.applying.domain.ApplicationSubmitter
import th.sibraine.jobagent.applying.domain.SubmissionCommand
import th.sibraine.jobagent.applying.domain.SubmissionMode
import th.sibraine.jobagent.applying.domain.SubmissionReceipt
import org.springframework.stereotype.Component

/**
 * Default submitter: the agent never presses Submit itself. It records the approved application as
 * manually submitted by the user, together with the reference the user reports back. The browser
 * runner of stage 6 replaces this bean without touching the workflow.
 */
@Component
class ManualApplicationSubmitter : ApplicationSubmitter {
    override fun submit(command: SubmissionCommand) = SubmissionReceipt(
        mode = SubmissionMode.MANUAL,
        reference = command.reference,
        note = "Submitted by the user; the agent only recorded the approved answers and artifacts.",
    )
}
