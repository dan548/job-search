package th.sibraine.jobagent.applying.infrastructure

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.FilePayload
import com.microsoft.playwright.options.SelectOption
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

        val applied = mutableListOf<String>()
        command.actions.forEach { action ->
            apply(session.page, action)
            applied += action.fieldKey
        }
        val fields = observeFields(session.page)
        val result = BrowserFillResult(
            checkpoint = checkpoint(session.page.url(), fields),
            appliedFieldKeys = applied,
            validationErrors = validationErrors(session.page),
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
        submit.click(Locator.ClickOptions().setTimeout(properties.actionTimeoutMs))
        runCatching { session.page.waitForLoadState(LoadState.DOMCONTENTLOADED) }
        var confirmationPoll = 0
        while (!submissionConfirmed(page, submit, beforeUrl) && confirmationPoll < SUBMIT_CONFIRMATION_POLLS) {
            page.waitForTimeout(SUBMIT_CONFIRMATION_POLL_MS)
            confirmationPoll++
        }
        urlPolicy.requireAllowed(page.url())
        val validation = validationErrors(page)
        check(validation.isEmpty()) {
            "The form still has ${validation.size} invalid field(s) after Submit was pressed"
        }
        check(submissionConfirmed(page, submit, beforeUrl)) {
            "The page did not confirm that the application was submitted"
        }
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
        context.route("**/*") { route ->
            if (urlPolicy.allows(route.request().url())) route.resume() else route.abort()
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
        return Session(command.formUrl, context, page, checkpoint(page.url(), emptyList()))
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

    private fun apply(page: Page, action: BrowserFillAction) {
        val locator = page.locator(action.locator)
        check(locator.count() > 0) { "Control ${action.fieldKey} is no longer present" }
        when (action.type) {
            FormFieldType.FILE -> {
                val file = requireNotNull(action.artifact) { "File action is missing its artifact" }
                locator.first().setInputFiles(
                    FilePayload(file.fileName, file.contentType, file.content),
                    Locator.SetInputFilesOptions().setTimeout(properties.actionTimeoutMs),
                )
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
            else -> locator.first().fill(
                requireNotNull(action.value),
                Locator.FillOptions().setTimeout(properties.actionTimeoutMs),
            )
        }
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
        visibleLocators(page.locator("input:invalid, textarea:invalid, select:invalid"))
            .mapIndexed { index, locator ->
                index to BrowserValidationError(
                    fieldKey = locator.getAttribute("data-job-agent-field-key"),
                    message = (locator.evaluate("el => el.validationMessage") as? String)
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
        val explicit = visibleLocators(page.locator("form button[type=submit], form input[type=submit]"))
        val candidates = if (explicit.isNotEmpty()) explicit else {
            visibleLocators(page.locator("form button:not([type])")).filter { control ->
                val text = runCatching { control.innerText().trim() }.getOrDefault("")
                SUBMIT_TEXT.containsMatchIn(text)
            }
        }
        check(candidates.size == 1) {
            "Expected exactly one visible submit control, found ${candidates.size}"
        }
        return candidates.single()
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
        private val FIELD_DISCOVERY_SCRIPT = """
            elements => {
              const isVisible = el => !!el && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
              const clean = value => (value || '').replace(/\s+/g, ' ').trim();
              const associatedLabels = el => Array.from(el.labels || []).filter(isVisible);
              const isUsableControl = el => isVisible(el) ||
                ['checkbox', 'radio'].includes((el.getAttribute('type') || '').toLowerCase()) &&
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
                const type = el.tagName === 'TEXTAREA' ? 'TEXTAREA' :
                  el.tagName === 'SELECT' ? 'SELECT' : ({
                    email: 'EMAIL', tel: 'PHONE', number: 'NUMBER', date: 'DATE', url: 'URL',
                    radio: 'RADIO', checkbox: 'CHECKBOX', file: 'FILE'
                  }[inputType] || 'TEXT');
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
                  locator: `[data-job-agent-field-key="${'$'}{fieldKey}"]`
                }];
              });
            }
        """.trimIndent()
    }
}
