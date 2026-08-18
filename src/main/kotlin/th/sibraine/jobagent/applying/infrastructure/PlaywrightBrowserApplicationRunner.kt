package th.sibraine.jobagent.applying.infrastructure

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.FilePayload
import com.microsoft.playwright.options.SelectOption
import com.microsoft.playwright.options.WaitForSelectorState
import com.microsoft.playwright.options.WaitUntilState
import th.sibraine.jobagent.applying.domain.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

@ConfigurationProperties("job-agent.browser.playwright")
data class PlaywrightBrowserProperties(
    val enabled: Boolean = false,
    val headless: Boolean = true,
    val allowedHosts: List<String> = emptyList(),
    val navigationTimeoutMs: Double = 30_000.0,
    val actionTimeoutMs: Double = 10_000.0,
    /** Enables plain HTTP only for an explicitly allowlisted loopback host, for local test fixtures. */
    val allowHttpLocalhost: Boolean = false,
    /**
     * Whether cookies and local storage of the ATS session may be stored in PostgreSQL. Off by
     * default: it lets a signed-in session survive a restart, at the price of keeping session
     * cookies in the database.
     */
    val persistStorageState: Boolean = false,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlaywrightBrowserProperties::class)
@ConditionalOnProperty(prefix = "job-agent.browser.playwright", name = ["enabled"], havingValue = "true")
class PlaywrightBrowserConfiguration {
    @Bean(destroyMethod = "close")
    fun browserApplicationRunner(properties: PlaywrightBrowserProperties): BrowserApplicationRunner =
        PlaywrightBrowserApplicationRunner(properties)
}

class BrowserCheckpointMismatchException(message: String) : RuntimeException(message)

class BrowserAutocompleteException(message: String) : RuntimeException(message)

internal class BrowserUrlPolicy(
    allowedHosts: Collection<String>,
    private val allowHttpLocalhost: Boolean = false,
) {
    private val hosts = allowedHosts.map { it.trim().lowercase().removePrefix(".") }
        .filter { it.isNotEmpty() }
        .toSet()

    init {
        require(hosts.isNotEmpty()) {
            "At least one PLAYWRIGHT_ALLOWED_HOSTS value is required when Playwright is enabled"
        }
    }

    fun allows(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val localHttp = allowHttpLocalhost && uri.scheme.equals("http", ignoreCase = true) && host in LOOPBACK_HOSTS
        if (!secure && !localHttp) return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    fun requireAllowed(value: String) {
        require(allows(value)) { "Browser URL is not an allowed destination" }
    }

    private companion object {
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}

/** Concrete Playwright implementation. All Playwright objects stay on one dedicated driver thread. */
class PlaywrightBrowserApplicationRunner(
    private val properties: PlaywrightBrowserProperties,
) : BrowserApplicationRunner, AutoCloseable {
    private data class Session(
        val initialFormUrl: String,
        val context: BrowserContext,
        val page: Page,
        var checkpoint: String,
        val blockedHosts: MutableSet<String> = linkedSetOf(),
        val fillResults: MutableMap<String, Pair<String, BrowserFillResult>> = mutableMapOf(),
        val submitResults: MutableMap<String, Pair<String, SubmissionReceipt>> = mutableMapOf(),
    )

    private val urlPolicy = BrowserUrlPolicy(properties.allowedHosts, properties.allowHttpLocalhost)
    private val driver = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "playwright-browser-runner").apply { isDaemon = true }
    }
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private var playwright: Playwright? = null
    private var browser: Browser? = null

    init {
        require(properties.navigationTimeoutMs > 0 && properties.actionTimeoutMs > 0) {
            "Playwright timeouts must be positive"
        }
    }

    override fun inspect(command: BrowserInspectCommand): BrowserFormSnapshot = onDriver {
        urlPolicy.requireAllowed(command.formUrl)
        val session = sessions[command.draftId] ?: newSession(command).also {
            sessions[command.draftId] = it
        }
        require(session.initialFormUrl == command.formUrl) {
            "A browser session for this draft is already bound to another form URL"
        }
        urlPolicy.requireAllowed(session.page.url())
        waitForFormReady(session.page)
        val fields = observeFields(session.page)
        val challenges = detectChallenges(session.page)
        val checkpoint = checkpoint(session.page.url(), fields)
        session.checkpoint = checkpoint
        BrowserFormSnapshot(session.page.url(), fields, checkpoint, challenges)
    }

    override fun fill(command: BrowserFillCommand): BrowserFillResult = onDriver {
        val session = session(command.draftId)
        val requestFingerprint = fillFingerprint(command)
        session.fillResults[command.idempotencyKey]?.let { (fingerprint, result) ->
            require(fingerprint == requestFingerprint) {
                "Browser fill idempotency key was already used for different actions"
            }
            return@onDriver result
        }
        requireCheckpoint(session, command.expectedCheckpoint)

        val (applied, actionErrors) = applyActions(session.page, command.actions, session.blockedHosts)
        val fields = observeFields(session.page)
        val result = BrowserFillResult(
            checkpoint = checkpoint(session.page.url(), fields),
            appliedFieldKeys = applied,
            validationErrors = validationErrors(session.page) + actionErrors,
            challenges = detectChallenges(session.page),
        )
        session.checkpoint = result.checkpoint
        session.fillResults[command.idempotencyKey] = requestFingerprint to result
        result
    }

    override fun submit(command: BrowserSubmitCommand): SubmissionReceipt = onDriver {
        val session = session(command.draftId)
        val requestFingerprint = sha256(
            "${command.expectedCheckpoint}|${command.approvedStateFingerprint}".toByteArray()
        )
        session.submitResults[command.idempotencyKey]?.let { (fingerprint, receipt) ->
            require(fingerprint == requestFingerprint) {
                "Browser submit idempotency key was already used for another approved state"
            }
            return@onDriver receipt
        }
        requireCheckpoint(session, command.expectedCheckpoint)
        val challenges = detectChallenges(session.page)
        check(challenges.isEmpty()) { "Browser submission is paused for ${challenges.joinToString()}" }
        val page = session.page
        val beforeUrl = page.url()
        val submit = submitControl(page)
        try {
            submit.click(Locator.ClickOptions().setTimeout(properties.actionTimeoutMs))
        } catch (error: TimeoutError) {
            // Playwright waits for the control to become enabled and then reports a bare timeout.
            // A still disabled Submit means the site considers the form incomplete, which is worth
            // saying out loud: it is the answer to why the run stopped here.
            check(runCatching { submit.isEnabled }.getOrDefault(true)) {
                "The Submit control stayed disabled, so the site still considers the form incomplete"
            }
            throw error
        }
        runCatching { session.page.waitForLoadState(LoadState.DOMCONTENTLOADED) }
        var confirmationPoll = 0
        while (confirmationPoll < SUBMIT_CONFIRMATION_POLLS &&
            !submissionConfirmed(page, submit, beforeUrl) &&
            submissionError(page) == null
        ) {
            page.waitForTimeout(SUBMIT_CONFIRMATION_POLL_MS)
            confirmationPoll++
        }
        urlPolicy.requireAllowed(page.url())
        // An error banner only counts while the page has not confirmed anything: reporting a
        // failure for an application that did go through would invite a duplicate submission.
        val confirmed = submissionConfirmed(page, submit, beforeUrl)
        if (!confirmed) {
            submissionError(page)?.let { banner -> error("The site rejected the submission: $banner") }
        }
        val validation = validationErrors(page)
        check(validation.isEmpty()) {
            "The form still has ${validation.size} invalid field(s) after Submit was pressed"
        }
        check(confirmed) { "The page did not confirm that the application was submitted" }
        val receipt = SubmissionReceipt(
            mode = SubmissionMode.BROWSER,
            reference = page.url(),
            note = "Submitted by the Playwright browser runner after explicit approval.",
        )
        session.submitResults[command.idempotencyKey] = requestFingerprint to receipt
        receipt
    }

    override fun exportState(draftId: UUID): BrowserSessionState? = onDriver {
        sessions[draftId]?.let { session ->
            BrowserSessionState(
                currentUrl = session.page.url(),
                storageState = if (properties.persistStorageState) {
                    runCatching { session.context.storageState() }.getOrNull()
                } else {
                    null
                },
            )
        }
    }

    override fun release(draftId: UUID) {
        onDriver { sessions.remove(draftId)?.let { session -> runCatching { session.context.close() } } }
    }

    /**
     * Rebuilds a session that is no longer live. A stored storage state restores the signed-in ATS
     * context and the run continues on the page it stopped on; without one it starts from the form
     * URL again. Both destinations still have to pass the allowlist.
     */
    private fun newSession(command: BrowserInspectCommand): Session {
        val browser = browser()
        val options = Browser.NewContextOptions()
            .setAcceptDownloads(false)
            .setIgnoreHTTPSErrors(false)
        command.resume?.storageState?.let { options.setStorageState(it) }
        val context = browser.newContext(options)
        context.setDefaultTimeout(properties.actionTimeoutMs)
        context.setDefaultNavigationTimeout(properties.navigationTimeoutMs)
        val blockedHosts = linkedSetOf<String>()
        context.route("**/*") { route ->
            if (urlPolicy.allows(route.request().url())) {
                route.resume()
            } else {
                URI.create(route.request().url()).host?.lowercase()?.let(blockedHosts::add)
                route.abort()
            }
        }
        val target = command.resume?.currentUrl?.takeIf { urlPolicy.allows(it) } ?: command.formUrl
        val page = context.newPage()
        page.navigate(
            target,
            Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(properties.navigationTimeoutMs),
        )
        urlPolicy.requireAllowed(page.url())
        return Session(command.formUrl, context, page, checkpoint(page.url(), emptyList()), blockedHosts)
    }

    private fun browser(): Browser {
        browser?.let { return it }
        val createdPlaywright = Playwright.create()
        val createdBrowser = createdPlaywright.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(properties.headless)
        )
        playwright = createdPlaywright
        browser = createdBrowser
        return createdBrowser
    }

    internal fun applyActions(
        page: Page,
        actions: List<BrowserFillAction>,
        blockedHosts: MutableSet<String> = linkedSetOf(),
    ): Pair<List<String>, List<BrowserValidationError>> {
        val applied = mutableListOf<String>()
        val errors = mutableListOf<BrowserValidationError>()
        // Lever parses an uploaded resume and then rewrites name, email, phone and company with the
        // values its parser read out of the file. Uploading before anything is typed lets the
        // approved answers land last and win over those guesses.
        actions.sortedBy { if (it.type == FormFieldType.FILE) 0 else 1 }.forEach { action ->
            try {
                apply(page, action, blockedHosts)
                applied += action.fieldKey
            } catch (error: BrowserAutocompleteException) {
                errors += BrowserValidationError(
                    fieldKey = action.fieldKey,
                    message = error.message.orEmpty().take(500),
                    code = "AUTOCOMPLETE_OPTIONS_UNAVAILABLE",
                )
            }
        }
        return applied to errors
    }

    internal fun observeFields(page: Page): List<ObservedFormField> {
        @Suppress("UNCHECKED_CAST")
        val raw = page.locator("input:not([type=hidden]), textarea, select").evaluateAll(FIELD_DISCOVERY_SCRIPT)
            as List<Map<String, Any?>>
        return raw.mapNotNull { item ->
            val key = item["fieldKey"] as? String ?: return@mapNotNull null
            val type = runCatching { FormFieldType.valueOf(item["type"] as String) }.getOrNull()
                ?: FormFieldType.TEXT
            ObservedFormField(
                fieldKey = key,
                label = (item["label"] as? String)?.trim().orEmpty().ifBlank { key },
                type = type,
                required = item["required"] as? Boolean ?: false,
                placeholder = (item["placeholder"] as? String)?.takeIf { it.isNotBlank() },
                options = (item["options"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                maxLength = (item["maxLength"] as? Number)?.toInt()?.takeIf { it > 0 },
                locator = item["locator"] as? String,
                page = page.url(),
            )
        }
    }

    internal fun apply(
        page: Page,
        action: BrowserFillAction,
        blockedHosts: MutableSet<String> = linkedSetOf(),
    ) {
        val locator = page.locator(action.locator)
        check(locator.count() > 0) { "Control ${action.fieldKey} is no longer present" }
        when (action.type) {
            FormFieldType.FILE -> {
                val file = requireNotNull(action.artifact) { "File action is missing its artifact" }
                locator.first().setInputFiles(
                    FilePayload(file.fileName, file.contentType, file.content),
                    Locator.SetInputFilesOptions().setTimeout(properties.actionTimeoutMs),
                )
                waitForUpload(page)
            }
            FormFieldType.CHECKBOX -> locator.first().setChecked(
                requireNotNull(action.checked),
                Locator.SetCheckedOptions().setTimeout(properties.actionTimeoutMs),
            )
            FormFieldType.SELECT -> locator.first().selectOption(
                SelectOption().setLabel(requireNotNull(action.value)),
                Locator.SelectOptionOptions().setTimeout(properties.actionTimeoutMs),
            )
            FormFieldType.RADIO -> selectRadio(locator, requireNotNull(action.value))
            FormFieldType.COMBOBOX -> fillAutocomplete(
                page,
                locator.first(),
                requireNotNull(action.value),
                action.fieldKey,
                blockedHosts,
            )
            else -> locator.first().fill(
                requireNotNull(action.value),
                Locator.FillOptions().setTimeout(properties.actionTimeoutMs),
            )
        }
    }

    private fun fillAutocomplete(
        page: Page,
        control: Locator,
        value: String,
        fieldKey: String,
        blockedHosts: MutableSet<String>,
    ) {
        // Ignore unrelated analytics/resources blocked while the page was loading. Requests made
        // after typing are the useful diagnostic if the suggestions never become available.
        blockedHosts.clear()
        control.fill(value, Locator.FillOptions().setTimeout(properties.actionTimeoutMs))

        var offered = 0
        val deadline = System.nanoTime() + (properties.actionTimeoutMs * 1_000_000).toLong()
        while (System.nanoTime() < deadline) {
            val options = autocompleteOptions(page)
            offered = maxOf(offered, options.size)
            matchingOption(options, value)?.let { option ->
                option.click(Locator.ClickOptions().setTimeout(properties.actionTimeoutMs))
                return
            }
            page.waitForTimeout(AUTOCOMPLETE_POLL_MS)
        }

        val blocked = blockedHosts.sorted().joinToString(", ")
        val diagnostic = when {
            blocked.isNotBlank() ->
                "The suggestions could not load because these hosts are not allowed: $blocked. " +
                    "Add the required host to PLAYWRIGHT_ALLOWED_HOSTS and try again"
            offered > 0 ->
                "$offered suggestion(s) were offered, none of them matched the approved answer and " +
                    "nothing was selected; answer this field by hand"
            else ->
                "The suggestions did not become available within ${properties.actionTimeoutMs.toLong()} ms"
        }
        throw BrowserAutocompleteException("Autocomplete field '$fieldKey' was not selected. $diagnostic")
    }

    private fun autocompleteOptions(page: Page): List<Locator> =
        visibleLocators(page.locator(AUTOCOMPLETE_OPTION_SELECTOR)).filter { option ->
            option.getAttribute("aria-disabled") != "true" &&
                runCatching { option.innerText().trim() }.getOrDefault("").let { text ->
                    text.isNotBlank() && !AUTOCOMPLETE_LOADING_TEXT.matches(text)
                }
        }

    /**
     * Lever rewrites a typed city into its own canonical form, so an exact match is not guaranteed
     * and the leading part of the answer is matched too. An unrelated suggestion is never taken:
     * applying with the wrong location is worse than reporting the field as still unanswered.
     */
    private fun matchingOption(options: List<Locator>, value: String): Locator? {
        val labelled = options.map { it to runCatching { it.innerText().trim() }.getOrDefault("") }
        val head = value.substringBefore(',').trim().ifBlank { value }
        return labelled.firstOrNull { (_, text) -> text.equals(value, ignoreCase = true) }?.first
            ?: labelled.firstOrNull { (_, text) -> text.contains(value, ignoreCase = true) }?.first
            ?: labelled.firstOrNull { (_, text) -> text.contains(head, ignoreCase = true) }?.first
    }

    private fun selectRadio(group: Locator, value: String) {
        val match = (0 until group.count()).map { group.nth(it) }.firstOrNull { radio ->
            radio.getAttribute("value")?.equals(value, ignoreCase = true) == true ||
                (radio.evaluate("el => el.labels?.[0]?.innerText || ''") as? String)
                    ?.trim()?.equals(value, ignoreCase = true) == true
        } ?: error("Radio option '$value' is no longer available")
        match.check(Locator.CheckOptions().setTimeout(properties.actionTimeoutMs))
    }

    internal fun validationErrors(page: Page): List<BrowserValidationError> =
        visibleLocators(page.locator(INVALID_CONTROL_SELECTOR))
            .mapIndexed { index, locator ->
                index to BrowserValidationError(
                    fieldKey = locator.getAttribute("data-job-agent-field-key"),
                    message = (locator.evaluate(VALIDATION_MESSAGE_SCRIPT) as? String)
                        ?.take(300).orEmpty().ifBlank { "The field is invalid" },
                    code = (locator.evaluate(
                        """el => {
                          const names = ['valueMissing', 'typeMismatch', 'patternMismatch', 'tooLong',
                            'tooShort', 'rangeUnderflow', 'rangeOverflow', 'stepMismatch', 'badInput'];
                          return names.find(name => el.validity?.[name]) || 'invalidValue';
                        }"""
                    ) as? String)?.replace(Regex("([a-z])([A-Z])"), "${'$'}1_${'$'}2")?.uppercase()
                        ?: "INVALID_VALUE",
                )
            }
            .distinctBy { (index, error) -> error.fieldKey?.let { "$it|${error.code}" } ?: "anonymous-$index" }
            .map { it.second }

    internal fun submitControl(page: Page): Locator {
        // Lever marks its own submit control, which settles the choice on a page that also carries
        // secondary buttons such as "Save" or the search form of the job board around the posting.
        visibleLocators(page.locator(LEVER_SUBMIT_SELECTOR)).singleOrNull()?.let { return it }
        val explicit = visibleLocators(page.locator("form button[type=submit], form input[type=submit]"))
        val candidates = if (explicit.isNotEmpty()) explicit else {
            visibleLocators(page.locator("form button:not([type])"))
        }
        val named = candidates.filter { SUBMIT_TEXT.containsMatchIn(controlLabel(it)) }
        val chosen = if (named.isNotEmpty()) named else candidates
        check(chosen.size == 1) {
            val labels = chosen.joinToString { controlLabel(it).take(40).ifBlank { "<unnamed>" } }
            "Expected exactly one visible submit control, found ${chosen.size}" +
                if (labels.isBlank()) "" else ": $labels"
        }
        return chosen.single()
    }

    private fun controlLabel(control: Locator): String = runCatching {
        control.innerText().trim().ifBlank { control.getAttribute("value").orEmpty().trim() }
    }.getOrDefault("")

    /** Text of a visible submit error banner, such as Lever's `.postings-btn-error`. */
    private fun submissionError(page: Page): String? = visibleLocators(page.locator(SUBMIT_ERROR_SELECTOR))
        .firstNotNullOfOrNull { banner ->
            runCatching { banner.innerText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        ?.take(200)

    /**
     * Lever renders the posting first and mounts the application form afterwards. Waiting for one
     * usable control keeps an inspection from reporting a form that is merely not mounted yet.
     */
    private fun waitForFormReady(page: Page) {
        runCatching {
            page.locator(FORM_READY_SELECTOR).first().waitFor(
                Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(properties.actionTimeoutMs)
            )
        }
    }

    /**
     * Waits out the upload round trip and the page update that follows it. Lever answers the upload
     * with the parsed resume and only then rewrites the contact fields, so returning earlier would
     * put the remaining actions in a race with that rewrite.
     */
    private fun waitForUpload(page: Page) {
        runCatching {
            page.waitForLoadState(
                LoadState.NETWORKIDLE,
                Page.WaitForLoadStateOptions().setTimeout(properties.actionTimeoutMs),
            )
        }
        runCatching {
            page.evaluate(
                DOM_QUIET_SCRIPT,
                mapOf("quietMs" to UPLOAD_QUIET_MS, "capMs" to properties.actionTimeoutMs),
            )
        }
    }

    private fun submissionConfirmed(page: Page, submit: Locator, beforeUrl: String): Boolean {
        if (page.url() != beforeUrl) return true
        if (!runCatching { submit.isVisible }.getOrDefault(false)) return true
        val body = runCatching { page.locator("body").innerText() }.getOrDefault("")
        return SUBMISSION_CONFIRMED_TEXT.containsMatchIn(body)
    }

    internal fun detectChallenges(page: Page): Set<BrowserChallenge> = buildSet {
        if (hasVisible(page, "iframe[src*='captcha' i], [class*='captcha' i], [id*='captcha' i]")) {
            add(BrowserChallenge.CAPTCHA)
        }
        if (hasVisible(page, "input[autocomplete='one-time-code'], input[name*='otp' i], input[id*='otp' i]")) {
            add(BrowserChallenge.OTP)
        }
        val path = runCatching { URI(page.url()).path.lowercase() }.getOrDefault("")
        if (("login" in path || "sign-in" in path || "signin" in path) && hasVisible(page, "input[type=password]")) {
            add(BrowserChallenge.REAUTHENTICATION)
        }
    }

    private fun hasVisible(page: Page, selector: String): Boolean =
        visibleLocators(page.locator(selector)).isNotEmpty()

    private fun visibleLocators(locator: Locator): List<Locator> =
        (0 until locator.count()).map { locator.nth(it) }.filter { runCatching { it.isVisible }.getOrDefault(false) }

    private fun requireCheckpoint(session: Session, expected: String) {
        if (session.checkpoint != expected) {
            throw BrowserCheckpointMismatchException(
                "Browser page changed after inspection; inspect it again before applying actions"
            )
        }
    }

    private fun session(draftId: UUID): Session = sessions[draftId]
        ?: error("No browser session exists for application draft $draftId")

    private fun checkpoint(url: String, fields: List<ObservedFormField>): String = sha256(buildString {
        append(url).append('\n')
        fields.forEach { append(it.fieldKey).append('|').append(it.type).append('|').append(it.locator).append('\n') }
    }.toByteArray())

    private fun fillFingerprint(command: BrowserFillCommand): String = sha256(buildString {
        append(command.expectedCheckpoint).append('\n')
        command.actions.forEach { action ->
            append(action.fieldKey).append('|').append(action.type).append('|')
            append(action.value.orEmpty()).append('|').append(action.checked).append('|')
            append(action.artifact?.artifactId).append('\n')
        }
    }.toByteArray())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> onDriver(action: () -> T): T = try {
        driver.submit(Callable { action() }).get()
    } catch (error: ExecutionException) {
        val cause = error.cause
        if (cause is RuntimeException) throw cause
        throw RuntimeException("Playwright driver failed", cause)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw RuntimeException("Playwright driver was interrupted", error)
    }

    override fun close() {
        runCatching {
            onDriver {
                sessions.values.forEach { runCatching { it.context.close() } }
                sessions.clear()
                browser?.close()
                playwright?.close()
            }
        }
        driver.shutdownNow()
    }

    private companion object {
        private val SUBMIT_TEXT = Regex(
            "(?i)^(submit( application)?|send( application)?|apply( now)?|отправить( отклик)?)$"
        )
        private val SUBMISSION_CONFIRMED_TEXT = Regex(
            "(?i)(thank you for applying|thanks for applying|application (was )?submitted|" +
                "successfully submitted|отклик (успешно )?отправлен)"
        )
        private const val SUBMIT_CONFIRMATION_POLLS = 10
        private const val SUBMIT_CONFIRMATION_POLL_MS = 250.0
        private const val AUTOCOMPLETE_POLL_MS = 100.0
        private const val UPLOAD_QUIET_MS = 400
        private const val LEVER_SUBMIT_SELECTOR = "#btn-submit, .template-btn-submit"
        private const val SUBMIT_ERROR_SELECTOR =
            ".postings-btn-error, .application-error, [class*='submit-error' i], [class*='form-error' i]"
        private const val FORM_READY_SELECTOR =
            "form input:not([type=hidden]), form textarea, form select"
        private const val INVALID_CONTROL_SELECTOR =
            "input:invalid, textarea:invalid, select:invalid, " +
                "input[aria-invalid=true], textarea[aria-invalid=true], select[aria-invalid=true]"
        private const val AUTOCOMPLETE_OPTION_SELECTOR =
            "[role='listbox'] [role='option'], [role='option'], [data-option-index], " +
                "[class*='autocomplete' i] [class*='option' i], [class*='suggestion' i], " +
                "[class*='dropdown-location' i] > *"
        /** Lever renders its own message next to a control the browser itself considers valid. */
        private val VALIDATION_MESSAGE_SCRIPT = """
            el => el.validationMessage ||
              el.closest('.application-question, [class*="field" i], li, fieldset')
                ?.querySelector('[class*="error" i], [role="alert"]')?.innerText || ''
        """.trimIndent()
        private val DOM_QUIET_SCRIPT = """
            options => new Promise(resolve => {
              let quiet;
              let cap;
              const finish = () => {
                clearTimeout(quiet);
                clearTimeout(cap);
                observer.disconnect();
                resolve(true);
              };
              const observer = new MutationObserver(() => {
                clearTimeout(quiet);
                quiet = setTimeout(finish, options.quietMs);
              });
              observer.observe(document.documentElement, {
                subtree: true, childList: true, attributes: true, characterData: true
              });
              quiet = setTimeout(finish, options.quietMs);
              cap = setTimeout(finish, options.capMs);
            })
        """.trimIndent()
        private val AUTOCOMPLETE_LOADING_TEXT = Regex("(?i)^(loading|загрузка|загружается)[.…]*$")
        private val FIELD_DISCOVERY_SCRIPT = """
            elements => {
              // A key from an earlier observation must not survive: locators are rebuilt on every
              // observation, and a stale attribute would keep pointing at a control that the page
              // may since have replaced or repurposed.
              document.querySelectorAll('[data-job-agent-field-key]')
                .forEach(el => el.removeAttribute('data-job-agent-field-key'));
              const isVisible = el => !!el && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
              const clean = value => (value || '').replace(/\s+/g, ' ').trim();
              const associatedLabels = el => Array.from(el.labels || []).filter(isVisible);
              // Lever hides the resume input behind a styled "Attach resume" label and hides its
              // consent checkboxes the same way; a control with a visible label is still usable.
              const isUsableControl = el => isVisible(el) ||
                ['checkbox', 'radio', 'file'].includes((el.getAttribute('type') || '').toLowerCase()) &&
                  associatedLabels(el).length > 0;
              const visible = elements.filter(isUsableControl);
              const seenRadio = new Set();
              const usedKeys = new Map();
              const labelledBy = el => (el.getAttribute('aria-labelledby') || '').split(/\s+/)
                .map(id => document.getElementById(id)?.innerText || '').join(' ');
              const optionText = el => clean(associatedLabels(el)[0]?.innerText || el.value || '');
              const questionContainerLabel = el => {
                const selectors = [
                  '.application-question', '[data-qa*="question" i]', '[data-testid*="question" i]',
                  '[class*="custom-question" i]', 'fieldset', 'li'
                ];
                for (const selector of selectors) {
                  const container = el.closest(selector);
                  if (!container) continue;
                  const candidate = container.querySelector(
                    ':scope > label, :scope > .application-label, :scope > [class*="question-label" i], ' +
                    ':scope > [class*="question-text" i], :scope > legend, :scope > h3, :scope > h4'
                  );
                  const text = clean(candidate?.innerText);
                  if (text) return text;
                }
                return '';
              };
              const precedingLabel = el => {
                let sibling = el.parentElement?.previousElementSibling;
                while (sibling) {
                  if (sibling.matches('label, legend, [class*="label" i], [class*="question" i]')) {
                    const text = clean(sibling.innerText);
                    if (text) return text;
                  }
                  sibling = sibling.previousElementSibling;
                }
                return '';
              };
              const fieldText = el => clean(labelledBy(el) || el.getAttribute('aria-label') ||
                el.closest('fieldset')?.querySelector('legend')?.innerText ||
                questionContainerLabel(el) || associatedLabels(el)[0]?.innerText || precedingLabel(el) ||
                el.getAttribute('placeholder') || el.getAttribute('name') || el.id || '');
              const key = (el, index) => {
                const base = (el.id || el.getAttribute('name') || `field-${'$'}{index}`)
                  .trim().replace(/[^a-zA-Z0-9_.:-]+/g, '-');
                const count = usedKeys.get(base) || 0;
                usedKeys.set(base, count + 1);
                return count === 0 ? base : `${'$'}{base}-${'$'}{count}`;
              };
              const quoteAttribute = value => `"${'$'}{String(value)
                .replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
              const stableLocator = (el, group, fieldKey) => {
                const tag = el.tagName.toLowerCase();
                const candidates = [];
                const dataQa = el.getAttribute('data-qa');
                if (dataQa) candidates.push(`${'$'}{tag}[data-qa=${'$'}{quoteAttribute(dataQa)}]`);
                if (el.id) candidates.push(`${'$'}{tag}[id=${'$'}{quoteAttribute(el.id)}]`);
                const name = el.getAttribute('name');
                if (name) {
                  const type = (el.getAttribute('type') || '').toLowerCase();
                  candidates.push(`${'$'}{tag}${'$'}{type ? `[type=${'$'}{quoteAttribute(type)}]` : ''}` +
                    `[name=${'$'}{quoteAttribute(name)}]`);
                }
                // Only return a semantic/stable selector when it resolves to exactly this control
                // (or this complete radio group). Lever may rebuild the DOM after resume parsing,
                // but keeps these attributes while dropping our temporary marker.
                const stable = candidates.find(selector => {
                  try {
                    const matches = Array.from(document.querySelectorAll(selector));
                    return matches.length === group.length && group.every(control => matches.includes(control));
                  } catch (_) {
                    return false;
                  }
                });
                return stable || `[data-job-agent-field-key="${'$'}{fieldKey}"]`;
              };
              return visible.flatMap((el, index) => {
                const inputType = (el.getAttribute('type') || 'text').toLowerCase();
                const radioGroup = inputType === 'radio' ? (el.getAttribute('name') || key(el, index)) : null;
                if (radioGroup && seenRadio.has(radioGroup)) return [];
                if (radioGroup) seenRadio.add(radioGroup);
                const group = radioGroup
                  ? visible.filter(candidate => (candidate.getAttribute('type') || '').toLowerCase() === 'radio' &&
                      (candidate.getAttribute('name') || '') === radioGroup)
                  : [el];
                const fieldKey = key(el, index);
                group.forEach(candidate => candidate.setAttribute('data-job-agent-field-key', fieldKey));
                const isCombobox = el.getAttribute('role') === 'combobox' ||
                  !!el.getAttribute('aria-autocomplete') || !!el.getAttribute('aria-controls');
                const type = el.tagName === 'TEXTAREA' ? 'TEXTAREA' :
                  el.tagName === 'SELECT' ? 'SELECT' : ({
                    email: 'EMAIL', tel: 'PHONE', number: 'NUMBER', date: 'DATE', url: 'URL',
                    radio: 'RADIO', checkbox: 'CHECKBOX', file: 'FILE'
                  }[inputType] || (isCombobox ? 'COMBOBOX' : 'TEXT'));
                const options = el.tagName === 'SELECT'
                  ? Array.from(el.options).map(option => option.label || option.textContent || option.value).filter(Boolean)
                  : radioGroup
                    ? group.map(option => optionText(option)).filter(Boolean)
                    : [];
                return [{
                  fieldKey,
                  label: fieldText(el),
                  type,
                  required: group.some(candidate => candidate.required ||
                    candidate.getAttribute('aria-required') === 'true') ||
                    /[✱*]\s*$/.test(fieldText(el)),
                  placeholder: el.getAttribute('placeholder'),
                  options,
                  maxLength: el.maxLength > 0 ? el.maxLength : null,
                  locator: stableLocator(el, group, fieldKey)
                }];
              });
            }
        """.trimIndent()
    }
}
