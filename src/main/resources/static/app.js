const state = { profile: null, identities: [], preview: null, vacancies: [], vacancy: null, analysis: null, variant: null, draft: null, resumeSelection: null, vacancySelection: 0 };

const $ = (selector) => document.querySelector(selector);
const escapeHtml = (value = "") => String(value).replace(/[&<>'"]/g, (char) => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
}[char]));

async function request(path, options = {}) {
  const config = { ...options, headers: { ...(options.headers || {}) } };
  if (config.body && !(config.body instanceof FormData) && typeof config.body !== "string") {
    config.headers["Content-Type"] = "application/json";
    config.body = JSON.stringify(config.body);
  }
  const response = await fetch(path, config);
  const type = response.headers.get("content-type") || "";
  const payload = type.includes("json") ? await response.json() : await response.text();
  if (!response.ok) {
    const error = new Error(payload?.message || payload || `Ошибка ${response.status}`);
    error.status = response.status;
    error.code = payload?.code;
    throw error;
  }
  return payload;
}

async function optional(path) {
  try { return await request(path); }
  catch (error) { if (error.status === 404) return null; throw error; }
}

function toast(message, isError = false) {
  const element = $("#toast");
  element.textContent = message;
  element.className = `toast visible${isError ? " error" : ""}`;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => element.className = "toast", 4200);
}

function busy(button, active, label = "Работаем…") {
  if (!button) return;
  if (active) { button.dataset.label = button.textContent; button.textContent = label; button.disabled = true; }
  else { button.textContent = button.dataset.label || button.textContent; button.disabled = false; }
}

function renderProfile() {
  const status = $("#profile-state");
  const panel = $("#profile-summary");
  if (!state.profile) {
    status.className = "pill muted";
    status.textContent = "Пусто";
    panel.className = "empty-state";
    panel.textContent = "После проверки резюме здесь появятся имя, роль, навыки и подтверждённые факты.";
    return;
  }
  status.className = "pill";
  status.textContent = "Готов";
  panel.className = "";
  const skills = [...(state.profile.skills || [])];
  const verified = (state.profile.facts || []).filter((fact) => fact.verified).length;
  panel.innerHTML = `
    <div class="profile-name">${escapeHtml(state.profile.generalInfo.displayName)}</div>
    <p class="profile-headline">${escapeHtml(state.profile.generalInfo.headline || state.profile.roles?.[0] || "Профиль кандидата")}</p>
    <div class="skill-list">${skills.slice(0, 12).map((skill) => `<span class="skill">${escapeHtml(skill)}</span>`).join("") || '<span class="skill">Навыки ещё не добавлены</span>'}</div>
    <div class="profile-stats"><div><b>${skills.length}</b><span>навыков</span></div><div><b>${state.profile.experiences?.length || 0}</b><span>мест работы</span></div><div><b>${state.profile.contacts?.length || 0}</b><span>контактов</span></div><div><b>${verified}</b><span>фактов</span></div></div>`;
}

function renderIdentities() {
  const select = $("#identity-select");
  select.innerHTML = state.identities.map((identity) =>
    `<option value="${identity.id}" ${identity.active ? "selected" : ""}>${escapeHtml(identity.label)}${identity.headline ? ` · ${escapeHtml(identity.headline)}` : ""}</option>`
  ).join("");
}

function resumeDateInput(value) {
  if (!value?.year) return "";
  return `${value.year}-${String(value.month || 1).padStart(2, "0")}`;
}

function parseResumeDate(value) {
  if (!value) return null;
  const [year, month] = value.split("-").map(Number);
  return { year, month };
}

function experienceTechnologies(experience) {
  if (experience.technologies?.length) return experience.technologies.join(", ");
  const source = experience.metadata?.provenance?.sourceText || "";
  return source.includes("Technologies:") ? source.split("Technologies:").pop().split("\n")[0].trim() : "";
}

function renderProfileEditor() {
  if (!state.profile) return;
  const form = $("#profile-details-form");
  form.elements.label.value = state.profile.label || state.profile.generalInfo.displayName;
  form.elements.displayName.value = state.profile.generalInfo.displayName;
  form.elements.headline.value = state.profile.generalInfo.headline || "";
  form.elements.skills.value = [...(state.profile.skills || [])].join(", ");
  $("#contact-list").innerHTML = (state.profile.contacts || []).map((contact) => `
    <div class="editable-item"><div><strong>${escapeHtml(contact.value)}</strong><small>${escapeHtml(contact.type)}${contact.label ? ` · ${escapeHtml(contact.label)}` : ""}</small></div>
    <div class="editable-actions"><button type="button" data-edit-contact="${escapeHtml(contact.elementId)}">Изменить</button><button type="button" data-delete data-delete-contact="${escapeHtml(contact.elementId)}">Удалить</button></div></div>`
  ).join("") || '<div class="empty-state compact">Контактов пока нет.</div>';
  $("#experience-list").innerHTML = (state.profile.experiences || []).map((experience) => `
    <div class="editable-item"><div><strong>${escapeHtml(experience.role)} · ${escapeHtml(experience.company)}</strong><small>${escapeHtml([resumeDateInput(experience.startDate), experience.current ? "сейчас" : resumeDateInput(experience.endDate)].filter(Boolean).join(" — "))}${experience.description ? ` · ${escapeHtml(experience.description)}` : ""}</small></div>
    <div class="editable-actions"><button type="button" data-edit-experience="${escapeHtml(experience.elementId)}">Изменить</button><button type="button" data-delete data-delete-experience="${escapeHtml(experience.elementId)}">Удалить</button></div></div>`
  ).join("") || '<div class="empty-state compact">Опыт работы пока не добавлен.</div>';
  $("#contact-list").querySelectorAll("[data-edit-contact]").forEach((button) => button.addEventListener("click", () => editContact(button.dataset.editContact)));
  $("#contact-list").querySelectorAll("[data-delete-contact]").forEach((button) => button.addEventListener("click", () => deleteProfileItem("contacts", button.dataset.deleteContact)));
  $("#experience-list").querySelectorAll("[data-edit-experience]").forEach((button) => button.addEventListener("click", () => editExperience(button.dataset.editExperience)));
  $("#experience-list").querySelectorAll("[data-delete-experience]").forEach((button) => button.addEventListener("click", () => deleteProfileItem("experiences", button.dataset.deleteExperience)));
}

function reviewElements(resume) {
  const result = [];
  const add = (section, element, title, detail = "") => element && result.push({ section, element, title, detail });
  add("Личность", resume.identity, resume.identity?.fullName, resume.identity?.headline);
  add("Профессиональное резюме", resume.summary, resume.summary?.text);
  (resume.contacts || []).forEach((item) => add("Контакты", item, item.value, item.type));
  (resume.experiences || []).forEach((item) => {
    add("Опыт", item, item.role, item.company);
    (item.achievements || []).forEach((achievement) => add("Достижения", achievement, achievement.text, `${item.role} · ${item.company}`));
  });
  (resume.projects || []).forEach((item) => {
    add("Проекты", item, item.name, item.description);
    (item.achievements || []).forEach((achievement) => add("Достижения проектов", achievement, achievement.text, item.name));
  });
  (resume.education || []).forEach((item) => add("Образование", item, item.institution, [item.degree, item.fieldOfStudy].filter(Boolean).join(" · ")));
  (resume.certifications || []).forEach((item) => add("Сертификаты", item, item.name, item.issuer));
  (resume.languages || []).forEach((item) => add("Языки", item, item.name, item.proficiency));
  (resume.skills || []).forEach((item) => add("Навыки", item, item.name, item.category));
  return result;
}

function renderResumeReview() {
  const panel = $("#resume-review");
  const list = $("#review-list");
  const items = reviewElements(state.preview.structuredResume);
  let lastSection = "";
  list.innerHTML = items.map(({ section, element, title, detail }) => {
    const heading = section !== lastSection ? `<div class="review-section">${escapeHtml(section)}</div>` : "";
    lastSection = section;
    return `${heading}<label class="review-item"><input type="checkbox" data-element-id="${escapeHtml(element.elementId)}"><span><strong>${escapeHtml(title)}</strong>${detail ? `<small>${escapeHtml(detail)}</small>` : ""}</span></label>`;
  }).join("") || '<div class="empty-state full">Распознанных элементов нет. Проверьте настройки AI/OCR или загрузите другой PDF.</div>';
  panel.classList.remove("hidden");
  panel.querySelectorAll("input[type=checkbox]").forEach((input) => input.addEventListener("change", updateReviewCount));
  updateReviewCount();
  panel.scrollIntoView({ behavior: "smooth", block: "start" });
}

function updateReviewCount() {
  const all = $("#review-list").querySelectorAll("input[type=checkbox]");
  const checked = $("#review-list").querySelectorAll("input[type=checkbox]:checked");
  $("#review-count").textContent = `${checked.length} из ${all.length}`;
}

function applyReviewDecisions(resume) {
  const selected = new Set([...$("#review-list").querySelectorAll("input:checked")].map((input) => input.dataset.elementId));
  reviewElements(resume).forEach(({ element }) => {
    element.metadata ||= {};
    element.metadata.reviewStatus = selected.has(element.elementId) ? "CONFIRMED" : "REJECTED";
  });
  return resume;
}

function renderVacancies() {
  $("#vacancy-count").textContent = state.vacancies.length;
  const list = $("#vacancy-list");
  list.innerHTML = state.vacancies.length ? state.vacancies.map((vacancy) => `
    <div class="vacancy-row ${state.vacancy?.id === vacancy.id ? "active" : ""}">
      <button class="vacancy-item" type="button" data-vacancy-id="${vacancy.id}">
        <strong>${escapeHtml(vacancy.title)}</strong><span>${escapeHtml(vacancy.company)}${vacancy.location ? ` · ${escapeHtml(vacancy.location)}` : ""}</span>
      </button>
      <button class="vacancy-delete" type="button" data-delete-vacancy-id="${vacancy.id}" aria-label="Удалить вакансию ${escapeHtml(vacancy.title)}" title="Удалить вакансию">×</button>
    </div>`).join("") : '<div class="empty-state compact">Сохранённых вакансий пока нет.</div>';
  list.querySelectorAll("[data-vacancy-id]").forEach((button) => button.addEventListener("click", () => selectVacancy(button.dataset.vacancyId)));
  list.querySelectorAll("[data-delete-vacancy-id]").forEach((button) => button.addEventListener("click", () => deleteVacancy(button.dataset.deleteVacancyId)));
}

function recommendationLabel(value) {
  return ({ PRIORITY: "Приоритетный отклик", APPLY: "Стоит откликнуться", MAYBE: "Нужна оценка", REJECT: "Лучше пропустить" })[value] || value;
}

function renderAnalysis() {
  const panel = $("#analysis-panel");
  if (!state.analysis) { panel.classList.add("hidden"); renderVariant(); return; }
  const match = state.analysis.match;
  const matrix = match.requirementEvidenceMatrix || [];
  panel.innerHTML = `<div class="analysis-top">
    <div class="score"><b>${match.score}</b><small>из 100</small></div>
    <div><p class="eyebrow">${escapeHtml(state.analysis.analysis.role || state.vacancy.title)}</p><div class="recommendation">${escapeHtml(recommendationLabel(match.recommendation))}</div><p class="analysis-copy">${escapeHtml(match.reasoningSummary)}</p></div>
    <button id="reanalyze" class="button secondary" type="button">Обновить анализ</button>
  </div>
  <div class="matrix">${matrix.slice(0, 12).map((row) => `<div class="matrix-row"><span>${escapeHtml(row.requirement)}</span><b class="status-${row.status}">${escapeHtml(row.status)}</b></div>`).join("")}</div>`;
  panel.classList.remove("hidden");
  $("#reanalyze").addEventListener("click", analyzeSelectedVacancy);
  $("#create-variant").disabled = false;
  $("#tailor-state").className = "pill";
  $("#tailor-state").textContent = "Анализ готов";
}

function renderVariant() {
  const button = $("#create-variant");
  const download = $("#download-pdf");
  const details = $("#variant-details");
  if (!state.variant) {
    download.classList.add("hidden");
    details.classList.add("hidden");
    $("#prepare-application").disabled = true;
    button.textContent = "Создать вариант резюме";
    button.disabled = !state.analysis;
    if (!state.vacancy) {
      $("#tailor-state").className = "pill muted";
      $("#tailor-state").textContent = "Выберите вакансию";
      $("#tailor-title").textContent = "Подготовьте вариант под выбранную роль";
      $("#tailor-copy").textContent = "Выберите сохранённую вакансию или добавьте новую, чтобы начать.";
    } else if (!state.analysis) {
      $("#tailor-state").className = "pill muted";
      $("#tailor-state").textContent = "Сначала проанализируйте вакансию";
      $("#tailor-title").textContent = `Резюме для ${state.vacancy.company}`;
      $("#tailor-copy").textContent = "После анализа здесь появятся пробелы, вопросы и готовый PDF.";
    } else {
      $("#tailor-state").className = "pill";
      $("#tailor-state").textContent = "Анализ готов";
      $("#tailor-title").textContent = `Создайте резюме для ${state.vacancy.company}`;
      $("#tailor-copy").textContent = "Будут использованы только подтверждённые сведения, подходящие для этой роли.";
    }
    renderResumeSelection();
    return;
  }
  $("#tailor-state").className = "pill";
  $("#tailor-state").textContent = "Резюме готово";
  $("#tailor-title").textContent = `Резюме для ${state.vacancy?.company || "выбранной вакансии"} готово`;
  $("#tailor-copy").textContent = "Версия собрана из подтверждённых сведений и настроена под требования выбранной роли.";
  button.textContent = "Пересобрать резюме";
  download.href = `/api/v1/resume-variants/${state.variant.variantId}/pdf`;
  download.classList.remove("hidden");
  const plan = state.variant.plan;
  details.innerHTML = `<div class="variant-stats"><span><b>${plan.skillElementIds?.length || 0}</b> навыков выбрано</span><span><b>${plan.gaps?.length || 0}</b> пробелов отмечено</span><span><b>${plan.questions?.length || 0}</b> вопросов к вам</span><span><b>${state.variant.diff?.length || 0}</b> изменений</span></div>`;
  details.classList.remove("hidden");
  $("#prepare-application").disabled = false;
  renderResumeSelection();
}

function renderResumeSelection() {
  const panel = $("#resume-selection");
  if (!state.analysis || !state.resumeSelection) { panel.classList.add("hidden"); return; }
  const groups = [
    ["Контакты", "contact", state.resumeSelection.contacts || []],
    ["Опыт работы", "experience", state.resumeSelection.experiences || []],
    ["Навыки", "skill", state.resumeSelection.skills || []]
  ];
  $("#selection-groups").innerHTML = groups.map(([title, kind, options]) => `
    <section class="selection-group"><h4>${title}</h4>${options.map((option) => `
      <label class="selection-option"><input type="checkbox" data-selection-kind="${kind}" value="${escapeHtml(option.key)}" ${option.selectedByDefault ? "checked" : ""} data-default="${option.selectedByDefault}"><span><strong>${escapeHtml(option.title)}</strong>${option.detail ? `<small>${escapeHtml(option.detail)}</small>` : ""}</span></label>`
    ).join("") || '<small>Нет данных для выбора</small>'}</section>`).join("");
  panel.classList.remove("hidden");
}

async function loadResumeSelection() {
  if (!state.vacancy || !state.analysis) { state.resumeSelection = null; renderResumeSelection(); return; }
  const vacancyId = state.vacancy.id;
  try {
    const selection = await request(`/api/v1/vacancies/${vacancyId}/resume-selection`);
    if (state.vacancy?.id !== vacancyId) return;
    state.resumeSelection = selection;
    renderResumeSelection();
  } catch (error) { toast(error.message, true); }
}

function selectedResumeContent() {
  const selected = (kind) => [...document.querySelectorAll(`[data-selection-kind="${kind}"]:checked`)].map((input) => input.value);
  return {
    contactElementIds: selected("contact"),
    experienceElementIds: selected("experience"),
    skillElementIds: selected("skill")
  };
}

function statusLabel(status) {
  return ({ DRAFT: "Черновик", FILLING: "Заполнение", NEEDS_INPUT: "Нужны ответы", READY_TO_SUBMIT: "Готов к отправке", SUBMITTED: "Отправлен", FAILED: "Ошибка" })[status] || status;
}

function renderDraft() {
  const panel = $("#application-status");
  const approvals = $("#approval-list");
  if (!state.draft) {
    panel.className = "empty-state";
    panel.textContent = state.variant ? "Укажите ссылку на форму и подготовьте отклик." : "Сначала создайте вариант резюме под вакансию.";
    approvals.innerHTML = "";
    return;
  }
  const draft = state.draft.draft;
  panel.className = "empty-state";
  panel.innerHTML = `<strong>${escapeHtml(statusLabel(draft.status))}</strong><br><span>${draft.answers?.length || 0} полей подготовлено · ${state.draft.artifacts?.length || 0} вложений</span>`;
  $("#start-browser").classList.toggle("hidden", draft.status === "SUBMITTED" || draft.status === "FAILED");
  const pending = state.draft.pendingApprovals || [];
  approvals.innerHTML = pending.map((approval) => {
    if (approval.type === "SUBMIT") return `<div class="approval"><div><p>${escapeHtml(approval.question)}</p><small>Будет отправлено только текущее подтверждённое состояние.</small></div><button class="button primary" data-submit-approval="${approval.approvalId}">Подтвердить и отправить</button></div>`;
    const control = approval.options?.length
      ? `<select data-value-for="${approval.approvalId}"><option value="">Выберите ответ</option>${approval.options.map((option) => `<option>${escapeHtml(option)}</option>`).join("")}</select>`
      : `<input data-value-for="${approval.approvalId}" placeholder="Ваш ответ">`;
    return `<div class="approval"><div><p>${escapeHtml(approval.question)}</p><small>${escapeHtml(approval.reason || "Требуется ответ")}${approval.required ? " · обязательный" : ""}</small></div><div class="approval-controls">${control}<button class="button primary" data-answer-approval="${approval.approvalId}">Сохранить</button></div></div>`;
  }).join("");
  if (draft.status === "READY_TO_SUBMIT" && !pending.some((item) => item.type === "SUBMIT")) {
    approvals.insertAdjacentHTML("beforeend", '<div class="approval"><div><p>Всё готово к финальной проверке</p><small>Сначала создайте отдельное подтверждение отправки.</small></div><button id="request-submit" class="button secondary">Проверить перед отправкой</button></div>');
    $("#request-submit").addEventListener("click", requestSubmitApproval);
  }
  approvals.querySelectorAll("[data-answer-approval]").forEach((button) => button.addEventListener("click", () => answerApproval(button.dataset.answerApproval)));
  approvals.querySelectorAll("[data-submit-approval]").forEach((button) => button.addEventListener("click", () => approveAndSubmit(button.dataset.submitApproval)));
}

async function loadInitialState() {
  const status = $("#service-status");
  try {
    const [profile, identities, confirmed, vacancies] = await Promise.all([
      optional("/api/v1/candidate-profile"),
      request("/api/v1/candidate-identities"),
      optional("/api/v1/candidate-profile/resume-imports/confirmed/latest"),
      request("/api/v1/vacancies")
    ]);
    state.profile = profile;
    state.identities = identities;
    state.vacancies = vacancies;
    if (confirmed) { $("#resume-state").className = "pill"; $("#resume-state").textContent = "Резюме подтверждено"; }
    renderProfile(); renderIdentities(); renderProfileEditor(); renderVacancies();
    status.classList.add("online"); status.lastChild.textContent = " Сервис готов";
    if (vacancies.length) await selectVacancy(vacancies[0].id, false);
  } catch (error) {
    status.lastChild.textContent = " Сервис недоступен";
    toast(error.message, true);
  }
}

async function selectVacancy(id, scroll = true) {
  const selection = ++state.vacancySelection;
  const vacancy = state.vacancies.find((item) => item.id === id) || await request(`/api/v1/vacancies/${id}`);
  if (selection !== state.vacancySelection) return;
  state.vacancy = vacancy;
  state.analysis = null; state.variant = null; state.draft = null; state.resumeSelection = null;
  renderVacancies(); renderAnalysis(); renderVariant(); renderDraft();
  const [analysis, variant, draft] = await Promise.all([
    optional(`/api/v1/vacancies/${id}/analysis`),
    optional(`/api/v1/vacancies/${id}/resume-variants/latest`),
    optional(`/api/v1/vacancies/${id}/application-drafts/latest`)
  ]);
  if (selection !== state.vacancySelection || state.vacancy?.id !== id) return;
  state.analysis = analysis; state.variant = variant; state.draft = draft;
  renderAnalysis(); renderVariant(); renderDraft();
  if (analysis) await loadResumeSelection();
  if (scroll) $("#analysis-panel").scrollIntoView({ behavior: "smooth", block: "center" });
}

async function deleteVacancy(id) {
  const vacancy = state.vacancies.find((item) => item.id === id);
  if (!vacancy || !window.confirm(`Удалить вакансию «${vacancy.title}» в ${vacancy.company}?`)) return;
  try {
    await request(`/api/v1/vacancies/${id}`, { method: "DELETE" });
    const wasSelected = state.vacancy?.id === id;
    state.vacancies = state.vacancies.filter((item) => item.id !== id);
    if (wasSelected) {
      state.vacancySelection += 1;
      state.vacancy = null; state.analysis = null; state.variant = null; state.draft = null; state.resumeSelection = null;
      renderAnalysis(); renderDraft();
    }
    renderVacancies();
    if (wasSelected && state.vacancies.length) await selectVacancy(state.vacancies[0].id, false);
    toast("Вакансия удалена");
  } catch (error) { toast(error.message, true); }
}

async function analyzeSelectedVacancy() {
  if (!state.vacancy) return;
  const vacancyId = state.vacancy.id;
  const button = $("#reanalyze") || $("#vacancy-form button");
  busy(button, true, "Анализируем…");
  try {
    const analysis = await request(`/api/v1/vacancies/${vacancyId}/analyze`, { method: "POST" });
    if (state.vacancy?.id !== vacancyId) return;
    state.analysis = analysis;
    state.variant = null; state.draft = null; state.resumeSelection = null;
    renderAnalysis(); renderVariant(); renderDraft();
    await loadResumeSelection();
    $("#analysis-panel").scrollIntoView({ behavior: "smooth", block: "center" });
    toast("Анализ вакансии готов");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
}

async function createVariant() {
  if (!state.vacancy) return;
  const vacancyId = state.vacancy.id;
  const button = $("#create-variant"); busy(button, true, "Готовим PDF…");
  try {
    const variant = await request(`/api/v1/vacancies/${vacancyId}/resume-variants`, { method: "POST", body: selectedResumeContent() });
    if (state.vacancy?.id !== vacancyId) return;
    state.variant = variant;
    state.draft = null; renderVariant(); renderDraft(); toast("Новая версия резюме готова");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
}

async function prepareApplication() {
  if (!state.vacancy || !state.variant) return;
  const vacancyId = state.vacancy.id;
  const variantId = state.variant.variantId;
  const button = $("#prepare-application"); busy(button, true, "Создаём черновик…");
  try {
    const draft = await request(`/api/v1/vacancies/${vacancyId}/application-drafts`, { method: "POST", body: { resumeVariantId: variantId } });
    if (state.vacancy?.id !== vacancyId) return;
    state.draft = draft;
    renderDraft(); toast("Черновик отклика создан");
  } catch (error) {
    if (error.code === "APPLICATION_DRAFT_ALREADY_OPEN") {
      const draft = await request(`/api/v1/vacancies/${vacancyId}/application-drafts/latest`);
      if (state.vacancy?.id !== vacancyId) return;
      state.draft = draft;
    }
    else toast(error.message, true);
    renderDraft();
  } finally { busy(button, false); }
}

async function startBrowserRun() {
  const url = $("#application-url").value.trim();
  if (!url) { toast("Укажите ссылку на форму отклика", true); return; }
  const button = $("#start-browser"); busy(button, true, "Заполняем…");
  try {
    const result = await request(`/api/v1/application-drafts/${state.draft.draft.draftId}/browser-runs`, { method: "POST", body: { formUrl: url, idempotencyKey: `ui-${Date.now()}` } });
    state.draft = result.application.draft;
    renderDraft(); toast(result.outcome === "PAUSED" ? "Заполнение приостановлено — нужен ваш ответ" : "Доступные поля заполнены");
  } catch (error) {
    const message = error.code === "BROWSER_RUNNER_DISABLED" ? "Браузерное заполнение выключено. Запустите приложение с PLAYWRIGHT_ENABLED=true." : error.message;
    toast(message, true);
  } finally { busy(button, false); }
}

async function answerApproval(approvalId) {
  const input = document.querySelector(`[data-value-for="${approvalId}"]`);
  const value = input.value.trim();
  if (!value) { toast("Введите или выберите ответ", true); return; }
  try {
    state.draft = await request(`/api/v1/application-drafts/${state.draft.draft.draftId}/approvals/${approvalId}/decision`, { method: "POST", body: { approved: true, value } });
    renderDraft(); toast("Ответ сохранён");
  } catch (error) { toast(error.message, true); }
}

async function requestSubmitApproval() {
  try {
    await request(`/api/v1/application-drafts/${state.draft.draft.draftId}/submit-approval`, { method: "POST" });
    state.draft = await request(`/api/v1/application-drafts/${state.draft.draft.draftId}`);
    renderDraft();
  } catch (error) { toast(error.message, true); }
}

async function approveAndSubmit(approvalId) {
  try {
    const draftId = state.draft.draft.draftId;
    await request(`/api/v1/application-drafts/${draftId}/approvals/${approvalId}/decision`, { method: "POST", body: { approved: true } });
    state.draft = await request(`/api/v1/application-drafts/${draftId}/submit`, { method: "POST", body: {} });
    renderDraft(); toast("Отклик отправлен");
  } catch (error) { toast(error.message, true); }
}

async function refreshProfile(profile) {
  state.profile = profile;
  state.identities = await request("/api/v1/candidate-identities");
  renderProfile(); renderIdentities(); renderProfileEditor();
}

async function switchIdentity(id) {
  if (!id || state.profile?.id === id) return;
  try {
    await request(`/api/v1/candidate-identities/${id}/activate`, { method: "POST" });
    state.profile = await request("/api/v1/candidate-profile");
    state.identities = await request("/api/v1/candidate-identities");
    const confirmed = await optional("/api/v1/candidate-profile/resume-imports/confirmed/latest");
    $("#resume-state").className = confirmed ? "pill" : "pill muted";
    $("#resume-state").textContent = confirmed ? "Резюме подтверждено" : "Можно загрузить резюме";
    state.preview = null; state.analysis = null; state.variant = null; state.draft = null; state.resumeSelection = null;
    renderProfile(); renderIdentities(); renderProfileEditor(); renderAnalysis(); renderDraft();
    if (state.vacancy) await selectVacancy(state.vacancy.id, false);
    toast("Identity переключена");
  } catch (error) { toast(error.message, true); }
}

async function createIdentity() {
  const label = window.prompt("Название новой identity (например, Backend / международный)");
  if (!label?.trim()) return;
  const displayName = window.prompt("Имя, которое будет указано в резюме", state.profile?.generalInfo?.displayName || "");
  if (!displayName?.trim()) return;
  try {
    const profile = await request("/api/v1/candidate-identities", { method: "POST", body: { label: label.trim(), displayName: displayName.trim() } });
    await refreshProfile(profile);
    state.preview = null; state.analysis = null; state.variant = null; state.draft = null; state.resumeSelection = null;
    $("#resume-state").className = "pill muted"; $("#resume-state").textContent = "Чистый профиль";
    renderAnalysis(); renderDraft();
    $("#profile-editor").classList.remove("hidden");
    toast("Новая identity создана");
  } catch (error) { toast(error.message, true); }
}

function editContact(id) {
  const contact = state.profile.contacts.find((item) => item.elementId === id);
  if (!contact) return;
  const form = $("#contact-form");
  form.dataset.editingId = id;
  form.elements.type.value = contact.type;
  form.elements.value.value = contact.value;
  form.elements.label.value = contact.label || "";
  form.querySelector("button").textContent = "Сохранить контакт";
}

function editExperience(id) {
  const experience = state.profile.experiences.find((item) => item.elementId === id);
  if (!experience) return;
  const form = $("#experience-form");
  form.dataset.editingId = id;
  form.elements.company.value = experience.company;
  form.elements.role.value = experience.role;
  form.elements.startDate.value = resumeDateInput(experience.startDate);
  form.elements.endDate.value = resumeDateInput(experience.endDate);
  form.elements.current.checked = Boolean(experience.current);
  form.elements.description.value = experience.description || "";
  form.elements.technologies.value = experienceTechnologies(experience);
  form.querySelector("button").textContent = "Сохранить опыт";
  form.scrollIntoView({ behavior: "smooth", block: "center" });
}

async function deleteProfileItem(kind, id) {
  if (!window.confirm(kind === "contacts" ? "Удалить этот контакт?" : "Удалить этот опыт работы?")) return;
  try {
    const profile = await request(`/api/v1/candidate-profile/${kind}/${encodeURIComponent(id)}`, { method: "DELETE" });
    await refreshProfile(profile); toast("Изменения сохранены");
  } catch (error) { toast(error.message, true); }
}

$("#resume-file").addEventListener("change", (event) => {
  const file = event.target.files[0];
  $("#upload-resume").disabled = !file;
  $("#resume-file-name").textContent = file ? `${file.name} · ${(file.size / 1024 / 1024).toFixed(1)} МБ` : "";
});

$("#upload-resume").addEventListener("click", async () => {
  const button = $("#upload-resume"); const file = $("#resume-file").files[0];
  if (!file) return;
  busy(button, true, "Разбираем PDF…");
  try {
    const form = new FormData(); form.append("file", file);
    state.preview = await request("/api/v1/candidate-profile/resume-imports", { method: "POST", body: form });
    $("#resume-state").className = "pill orange"; $("#resume-state").textContent = "Нужна проверка";
    renderResumeReview(); toast("Резюме разобрано — проверьте элементы");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
});

$("#select-all").addEventListener("click", () => {
  const boxes = [...$("#review-list").querySelectorAll("input[type=checkbox]")];
  const select = boxes.some((box) => !box.checked);
  boxes.forEach((box) => box.checked = select); updateReviewCount();
  $("#select-all").textContent = select ? "Снять всё" : "Выбрать всё";
});

$("#confirm-resume").addEventListener("click", async () => {
  const button = $("#confirm-resume"); busy(button, true, "Сохраняем…");
  try {
    const structuredResume = applyReviewDecisions(structuredClone(state.preview.structuredResume));
    const enriching = Boolean(state.profile);
    const confirmed = await request(`/api/v1/candidate-profile/resume-imports/${state.preview.importId}/confirm`, {
      method: "POST",
      body: { structuredResume, mode: enriching ? "ENRICH" : "REPLACE" }
    });
    state.profile = await request("/api/v1/candidate-profile");
    $("#resume-state").className = "pill"; $("#resume-state").textContent = "Резюме подтверждено";
    $("#resume-review").classList.add("hidden"); renderProfile(); toast(enriching ? "Профиль дополнен подтверждёнными данными" : "Профиль создан");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
});

$("#vacancy-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  // `currentTarget` is cleared by the browser once this async listener yields, so retain the
  // concrete form before the first request. Otherwise the vacancy is saved, `reset()` throws,
  // and analysis is never started.
  const form = event.currentTarget;
  const button = form.querySelector("button"); busy(button, true, "Сохраняем…");
  const values = Object.fromEntries(new FormData(form));
  try {
    const vacancy = await request("/api/v1/vacancies", { method: "POST", body: { source: "MANUAL", ...values, externalId: null } });
    state.vacancies.unshift(vacancy); state.vacancy = vacancy; state.analysis = null; state.variant = null; state.draft = null; state.resumeSelection = null;
    renderVacancies(); form.reset(); await analyzeSelectedVacancy();
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
});

$("#create-variant").addEventListener("click", createVariant);
$("#prepare-application").addEventListener("click", prepareApplication);
$("#start-browser").addEventListener("click", startBrowserRun);
$("#identity-select").addEventListener("change", (event) => switchIdentity(event.target.value));
$("#new-identity").addEventListener("click", createIdentity);
$("#edit-profile").addEventListener("click", () => { renderProfileEditor(); $("#profile-editor").classList.remove("hidden"); });
$("#close-profile-editor").addEventListener("click", () => $("#profile-editor").classList.add("hidden"));
$("#selection-defaults").addEventListener("click", () => {
  document.querySelectorAll("[data-selection-kind]").forEach((input) => input.checked = input.dataset.default === "true");
});
$("#profile-details-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget; const button = form.querySelector("button[type=submit]"); busy(button, true, "Сохраняем…");
  try {
    const values = Object.fromEntries(new FormData(form));
    const profile = await request("/api/v1/candidate-profile/details", { method: "PUT", body: { ...values, skills: values.skills.split(",").map((item) => item.trim()).filter(Boolean) } });
    await refreshProfile(profile); toast("Профиль обновлён");
  } catch (error) { toast(error.message, true); } finally { busy(button, false); }
});
$("#contact-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget; const values = Object.fromEntries(new FormData(form)); const id = form.dataset.editingId;
  try {
    const profile = await request(id ? `/api/v1/candidate-profile/contacts/${encodeURIComponent(id)}` : "/api/v1/candidate-profile/contacts", { method: id ? "PUT" : "POST", body: values });
    form.reset(); delete form.dataset.editingId; form.querySelector("button").textContent = "Добавить контакт";
    await refreshProfile(profile); toast("Контакт сохранён");
  } catch (error) { toast(error.message, true); }
});
$("#experience-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget; const values = Object.fromEntries(new FormData(form)); const id = form.dataset.editingId;
  const body = {
    company: values.company, role: values.role, description: values.description || null,
    startDate: parseResumeDate(values.startDate), endDate: form.elements.current.checked ? null : parseResumeDate(values.endDate),
    current: form.elements.current.checked,
    technologies: values.technologies.split(",").map((item) => item.trim()).filter(Boolean)
  };
  try {
    const profile = await request(id ? `/api/v1/candidate-profile/experiences/${encodeURIComponent(id)}` : "/api/v1/candidate-profile/experiences", { method: id ? "PUT" : "POST", body });
    form.reset(); delete form.dataset.editingId; form.querySelector("button").textContent = "Добавить опыт";
    await refreshProfile(profile); toast("Опыт работы сохранён");
  } catch (error) { toast(error.message, true); }
});
loadInitialState();
