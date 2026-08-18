const state = { profile: null, identities: [], preview: null, vacancies: [], vacancy: null, analysis: null, variant: null, draft: null, resumeSelection: null, resumePhotoDataUri: null, settings: null, catalog: [], browserSession: null, browserAudit: [], browserDiagnostics: [], vacancySelection: 0, profileSkillsDraft: [] };
let analysisInFlight = null;

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
    <div class="skill-list profile-skill-list">${skills.map((skill) => `<span class="skill">${escapeHtml(skill)}</span>`).join("") || '<span class="skill muted-skill">Навыки ещё не добавлены</span>'}</div>
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
  state.profileSkillsDraft = [...(state.profile.skills || [])];
  renderProfileSkillEditor();
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

function renderProfileSkillEditor() {
  const list = $("#profile-skill-list");
  if (!list) return;
  list.innerHTML = state.profileSkillsDraft.length
    ? state.profileSkillsDraft.map((skill, index) => `<span class="editable-skill"><span>${escapeHtml(skill)}</span><button type="button" data-remove-profile-skill="${index}" aria-label="Удалить навык ${escapeHtml(skill)}" title="Удалить">×</button></span>`).join("")
    : '<span class="skill-editor-empty">Навыки пока не добавлены.</span>';
  list.querySelectorAll("[data-remove-profile-skill]").forEach((button) => button.addEventListener("click", () => {
    state.profileSkillsDraft.splice(Number(button.dataset.removeProfileSkill), 1);
    renderProfileSkillEditor();
  }));
}

function addProfileSkills() {
  const input = $("#profile-skill-input");
  const candidates = input.value.split(/[,\n]/).map((value) => value.trim()).filter(Boolean);
  if (!candidates.length) { input.focus(); return; }
  const known = new Set(state.profileSkillsDraft.map((skill) => skill.toLocaleLowerCase()));
  candidates.forEach((skill) => {
    const key = skill.toLocaleLowerCase();
    if (!known.has(key)) { state.profileSkillsDraft.push(skill); known.add(key); }
  });
  input.value = "";
  renderProfileSkillEditor();
  input.focus();
}

function reviewElements(resume) {
  const result = [];
  const add = (section, kind, element, title, detail = "") => element && result.push({ section, kind, element, title, detail });
  add("Личность", "identity", resume.identity, resume.identity?.fullName, resume.identity?.headline);
  add("Профессиональное резюме", "text", resume.summary, resume.summary?.text);
  (resume.contacts || []).forEach((item) => add("Контакты", "contact", item, item.value, item.type));
  (resume.experiences || []).forEach((item) => {
    add("Опыт", "experience", item, item.role, item.company);
    (item.achievements || []).forEach((achievement) => add("Достижения", "text", achievement, achievement.text, `${item.role} · ${item.company}`));
  });
  (resume.projects || []).forEach((item) => {
    add("Проекты", "project", item, item.name, item.description);
    (item.achievements || []).forEach((achievement) => add("Достижения проектов", "text", achievement, achievement.text, item.name));
  });
  (resume.education || []).forEach((item) => add("Образование", "education", item, item.institution, [item.degree, item.fieldOfStudy].filter(Boolean).join(" · ")));
  (resume.certifications || []).forEach((item) => add("Сертификаты", "certification", item, item.name, item.issuer));
  (resume.languages || []).forEach((item) => add("Языки", "language", item, item.name, item.proficiency));
  (resume.skills || []).forEach((item) => add("Навыки", "skill", item, item.name, item.category));
  return result;
}

function provenanceMarkup(element) {
  const provenance = element.metadata?.provenance;
  if (!provenance) return '<div class="provenance">Источник не указан распознавателем</div>';
  const box = provenance.boundingBox;
  const location = [
    provenance.pageNumber ? `страница ${provenance.pageNumber}` : null,
    box ? `x ${box.x.toFixed(1)}, y ${box.y.toFixed(1)}, ${box.width.toFixed(1)} × ${box.height.toFixed(1)}` : null
  ].filter(Boolean).join(" · ");
  return `<details class="provenance"><summary>Источник${location ? ` · ${escapeHtml(location)}` : ""}</summary><p>${escapeHtml(provenance.sourceText)}</p></details>`;
}

function renderResumeReview(scroll = true) {
  const panel = $("#resume-review");
  const list = $("#review-list");
  const items = reviewElements(state.preview.structuredResume);
  let lastSection = "";
  list.innerHTML = items.map(({ section, element, title, detail }) => {
    const heading = section !== lastSection ? `<div class="review-section">${escapeHtml(section)}</div>` : "";
    lastSection = section;
    const inputId = `review-${element.elementId.replace(/[^a-zA-Z0-9_-]/g, "-")}`;
    const checked = element.metadata?.reviewStatus === "CONFIRMED" ? " checked" : "";
    return `${heading}<div class="review-item"><input id="${escapeHtml(inputId)}" type="checkbox" data-element-id="${escapeHtml(element.elementId)}"${checked}><div class="review-content"><label for="${escapeHtml(inputId)}"><strong>${escapeHtml(title)}</strong>${detail ? `<small>${escapeHtml(detail)}</small>` : ""}</label>${provenanceMarkup(element)}</div><button class="review-edit" type="button" data-edit-resume-element="${escapeHtml(element.elementId)}">Исправить</button></div>`;
  }).join("") || '<div class="empty-state full">Распознанных элементов нет. Проверьте настройки AI/OCR или загрузите другой PDF.</div>';
  panel.classList.remove("hidden");
  panel.querySelectorAll("input[type=checkbox]").forEach((input) => input.addEventListener("change", updateReviewCount));
  panel.querySelectorAll("[data-edit-resume-element]").forEach((button) => button.addEventListener("click", () => editResumeElement(button.dataset.editResumeElement)));
  updateReviewCount();
  if (scroll) panel.scrollIntoView({ behavior: "smooth", block: "start" });
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

const editField = (name, label, options = {}) => ({ name, label, type: "text", ...options });

function resumeEditFields(item) {
  const fields = {
    identity: [editField("fullName", "Имя", { required: true }), editField("headline", "Позиционирование")],
    text: [editField("text", "Текст", { type: "textarea", required: true, full: true })],
    contact: [editField("type", "Тип", { type: "select", options: ["EMAIL", "PHONE", "LOCATION", "WEBSITE", "LINKEDIN", "GITHUB", "OTHER"] }), editField("value", "Значение", { required: true }), editField("label", "Подпись")],
    experience: [editField("company", "Компания", { required: true }), editField("role", "Роль", { required: true }), editField("location", "Локация"), editField("startDate", "Начало", { type: "month" }), editField("endDate", "Окончание", { type: "month" }), editField("current", "Работаю сейчас", { type: "checkbox", full: true }), editField("description", "Описание", { type: "textarea", full: true }), editField("technologies", "Технологии через запятую", { full: true })],
    project: [editField("name", "Название", { required: true }), editField("url", "Ссылка", { type: "url" }), editField("description", "Описание", { type: "textarea", full: true })],
    education: [editField("institution", "Учебное заведение", { required: true }), editField("degree", "Степень"), editField("fieldOfStudy", "Специальность"), editField("startDate", "Начало", { type: "month" }), editField("endDate", "Окончание", { type: "month" }), editField("description", "Описание", { type: "textarea", full: true })],
    certification: [editField("name", "Сертификат", { required: true }), editField("issuer", "Организация"), editField("issuedAt", "Дата выдачи", { type: "month" }), editField("expiresAt", "Действует до", { type: "month" }), editField("credentialUrl", "Ссылка", { type: "url", full: true })],
    language: [editField("name", "Язык", { required: true }), editField("proficiency", "Уровень")],
    skill: [editField("name", "Навык", { required: true }), editField("category", "Категория")]
  };
  return fields[item.kind] || [];
}

function editValue(element, field) {
  const value = element[field.name];
  if (field.type === "month") return resumeDateInput(value);
  if (field.name === "technologies") return (value || []).join(", ");
  return value ?? "";
}

function editFieldMarkup(element, field) {
  const full = field.full ? "full" : "";
  if (field.type === "checkbox") return `<label class="${full} checkbox-line"><input name="${field.name}" type="checkbox"${element[field.name] ? " checked" : ""}><span>${escapeHtml(field.label)}</span></label>`;
  if (field.type === "select") return `<label class="${full}"><span>${escapeHtml(field.label)}</span><select name="${field.name}">${field.options.map((option) => `<option value="${option}"${element[field.name] === option ? " selected" : ""}>${option}</option>`).join("")}</select></label>`;
  const required = field.required ? " required" : "";
  const value = editValue(element, field);
  if (field.type === "textarea") return `<label class="${full}"><span>${escapeHtml(field.label)}</span><textarea name="${field.name}" rows="4"${required}>${escapeHtml(value)}</textarea></label>`;
  return `<label class="${full}"><span>${escapeHtml(field.label)}</span><input name="${field.name}" type="${field.type}" value="${escapeHtml(value)}"${required}></label>`;
}

function editResumeElement(elementId) {
  applyReviewDecisions(state.preview.structuredResume);
  const item = reviewElements(state.preview.structuredResume).find(({ element }) => element.elementId === elementId);
  if (!item) return;
  const form = $("#resume-edit-form");
  form.dataset.elementId = elementId;
  $("#resume-edit-title").textContent = item.section;
  $("#resume-edit-fields").innerHTML = resumeEditFields(item).map((field) => editFieldMarkup(item.element, field)).join("");
  $("#resume-edit-dialog").showModal();
}

function saveResumeElement(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const item = reviewElements(state.preview.structuredResume).find(({ element }) => element.elementId === form.dataset.elementId);
  if (!item) return;
  const values = Object.fromEntries(new FormData(form));
  resumeEditFields(item).forEach((field) => {
    if (field.type === "checkbox") item.element[field.name] = form.elements[field.name].checked;
    else if (field.type === "month") item.element[field.name] = parseResumeDate(values[field.name]);
    else if (field.name === "technologies") item.element[field.name] = values[field.name].split(",").map((value) => value.trim()).filter(Boolean);
    else item.element[field.name] = values[field.name]?.trim() || null;
  });
  if (item.kind === "experience" && item.element.current) item.element.endDate = null;
  item.element.metadata ||= {};
  item.element.metadata.reviewStatus = "CONFIRMED";
  $("#resume-edit-dialog").close();
  renderResumeReview(false);
  toast("Исправление сохранено и отмечено как подтверждённое");
}

function renderVacancies() {
  $("#vacancy-count").textContent = state.vacancies.length;
  const list = $("#vacancy-list");
  list.innerHTML = state.vacancies.length ? state.vacancies.map((vacancy) => `
    <div class="vacancy-row ${state.vacancy?.id === vacancy.id ? "active" : ""}">
      <button class="vacancy-item" type="button" data-vacancy-id="${vacancy.id}">
        <strong>${escapeHtml(vacancy.title)}</strong><span>${escapeHtml([vacancy.company, vacancy.location, employmentTypeLabel(vacancy.employmentType)].filter(Boolean).join(" · "))}</span>
      </button>
      <button class="vacancy-delete" type="button" data-delete-vacancy-id="${vacancy.id}" aria-label="Удалить вакансию ${escapeHtml(vacancy.title)}" title="Удалить вакансию">×</button>
    </div>`).join("") : '<div class="empty-state compact">Сохранённых вакансий пока нет.</div>';
  list.querySelectorAll("[data-vacancy-id]").forEach((button) => button.addEventListener("click", () => selectVacancy(button.dataset.vacancyId)));
  list.querySelectorAll("[data-delete-vacancy-id]").forEach((button) => button.addEventListener("click", () => deleteVacancy(button.dataset.deleteVacancyId)));
}

function employmentTypeLabel(value) {
  return ({ FULL_TIME: "Полная занятость", PART_TIME: "Частичная занятость", CONTRACT: "Контракт", INTERNSHIP: "Стажировка", TEMPORARY: "Временная работа" })[value] || "";
}

function fillVacancyForm(vacancy) {
  const form = $("#vacancy-form");
  const values = vacancy || {};
  form.elements.company.value = values.company || "";
  form.elements.title.value = values.title || "";
  form.elements.location.value = values.location || "";
  form.elements.employmentType.value = values.employmentType || "";
  form.elements.url.value = values.url || "";
  form.elements.description.value = values.description || "";
  form.dataset.sourceVacancyId = vacancy?.id || "";
  $("#vacancy-form-title").textContent = vacancy ? "Данные выбранной вакансии" : "Новая вакансия";
  $("#clear-vacancy-form").classList.toggle("hidden", !vacancy);
  const submit = form.querySelector("button[type=submit]");
  submit.textContent = vacancy ? "Сохранить как новую и проанализировать" : "Сохранить и проанализировать";
  submit.dataset.label = submit.textContent;
}

function recommendationLabel(value) {
  return ({ PRIORITY: "Приоритетный отклик", APPLY: "Стоит откликнуться", MAYBE: "Нужна оценка", REJECT: "Лучше пропустить" })[value] || value;
}

function displayResumeDate(value) {
  if (!value?.year) return "";
  return value.month ? `${String(value.month).padStart(2, "0")}.${value.year}` : String(value.year);
}

function resumePreviewMarkup(resume) {
  const blocks = [];
  const section = (title, content) => content && blocks.push(`<section class="preview-section"><h4>${escapeHtml(title)}</h4>${content}</section>`);
  if (resume.identity) section("Заголовок", `<h3>${escapeHtml(resume.identity.fullName)}</h3>${resume.identity.headline ? `<p>${escapeHtml(resume.identity.headline)}</p>` : ""}`);
  if (resume.summary) section("Профессиональное резюме", `<p>${escapeHtml(resume.summary.text)}</p>`);
  section("Контакты", (resume.contacts || []).map((item) => `<p><b>${escapeHtml(item.type)}</b> · ${escapeHtml(item.value)}</p>`).join(""));
  section("Опыт работы", (resume.experiences || []).map((item) => {
    const dates = [displayResumeDate(item.startDate), item.current ? "сейчас" : displayResumeDate(item.endDate)].filter(Boolean).join(" — ");
    return `<article><h5>${escapeHtml(item.role)} · ${escapeHtml(item.company)}</h5><small>${escapeHtml([dates, item.location].filter(Boolean).join(" · "))}</small>${item.description ? `<p>${escapeHtml(item.description)}</p>` : ""}<ul>${(item.achievements || []).map((achievement) => `<li>${escapeHtml(achievement.text)}</li>`).join("")}</ul></article>`;
  }).join(""));
  section("Проекты", (resume.projects || []).map((item) => `<article><h5>${escapeHtml(item.name)}</h5>${item.description ? `<p>${escapeHtml(item.description)}</p>` : ""}<ul>${(item.achievements || []).map((achievement) => `<li>${escapeHtml(achievement.text)}</li>`).join("")}</ul></article>`).join(""));
  section("Образование", (resume.education || []).map((item) => `<p><b>${escapeHtml(item.institution)}</b>${item.degree || item.fieldOfStudy ? ` · ${escapeHtml([item.degree, item.fieldOfStudy].filter(Boolean).join(", "))}` : ""}</p>`).join(""));
  section("Сертификаты", (resume.certifications || []).map((item) => `<p><b>${escapeHtml(item.name)}</b>${item.issuer ? ` · ${escapeHtml(item.issuer)}` : ""}</p>`).join(""));
  section("Языки", (resume.languages || []).map((item) => `<p>${escapeHtml(item.name)}${item.proficiency ? ` · ${escapeHtml(item.proficiency)}` : ""}</p>`).join(""));
  section("Навыки", (resume.skills || []).map((item) => `<span class="skill">${escapeHtml(item.name)}</span>`).join(" "));
  return blocks.join("") || '<div class="empty-state compact">В варианте нет содержимого.</div>';
}

function variantDiffMarkup(diff) {
  const changeLabel = { ADDED: "Добавлено", REMOVED: "Исключено", MODIFIED: "Переформулировано" };
  return (diff || []).map((change) => `<article class="diff-item diff-${change.changeType}"><div class="diff-heading"><b>${escapeHtml(changeLabel[change.changeType] || change.changeType)}</b><small>${escapeHtml(change.section)}</small></div>${change.previousText != null ? `<div><span>Было</span><p>${escapeHtml(change.previousText)}</p></div>` : ""}${change.currentText != null ? `<div><span>Стало</span><p>${escapeHtml(change.currentText)}</p></div>` : ""}</article>`).join("") || '<div class="empty-state compact">Текст не изменён — использована подтверждённая версия.</div>';
}

function coverLetterMarkup(coverLetter) {
  const generated = coverLetter
    ? `<textarea id="cover-letter-text" rows="12" readonly>${escapeHtml(coverLetter.text)}</textarea>
       <div class="cover-letter-meta"><small>Создано ${escapeHtml(new Date(coverLetter.generatedAt).toLocaleString("ru-RU"))}</small><button id="copy-cover-letter" class="button secondary" type="button">Копировать текст</button></div>`
    : '<div class="empty-state compact">Сгенерируйте короткое письмо на основе этой версии резюме и выбранной вакансии.</div>';
  return `<section class="cover-letter-panel">
    <div class="subsection-title"><div><h3>Cover letter</h3><small>Только факты из подготовленного резюме</small></div><button id="generate-cover-letter" class="button primary" type="button">${coverLetter ? "Сгенерировать заново" : "Сгенерировать текст"}</button></div>
    ${generated}
  </section>`;
}

function analysisChangeMarkup(change) {
  if (!change) return "";
  const delta = change.scoreAfter - change.scoreBefore;
  const deltaLabel = delta > 0 ? `+${delta}` : String(delta);
  const confirmed = (change.newlyConfirmed || []).map((item) => {
    const evidence = (item.evidence || []).map((fact) => fact.text).filter(Boolean);
    return `<li><b>${escapeHtml(item.requirement)}</b>${evidence.length ? `<small>Подтверждение: ${escapeHtml(evidence.join(" · "))}</small>` : ""}</li>`;
  }).join("");
  const remaining = (change.stillWithoutEvidence || []).map((requirement) => `<li>${escapeHtml(requirement)}</li>`).join("");
  return `<section class="analysis-change">
    <div class="analysis-change-score"><span>Оценка до и после</span><b>${change.scoreBefore} → ${change.scoreAfter}</b><strong class="${delta > 0 ? "positive" : delta < 0 ? "negative" : ""}">${escapeHtml(deltaLabel)}</strong></div>
    <div class="analysis-change-columns">
      <div><h4>Стало подтверждено</h4>${confirmed ? `<ul>${confirmed}</ul>` : '<p>Ни одно новое требование пока не получило достаточного подтверждения.</p>'}</div>
      <div><h4>Всё ещё без доказательств</h4>${remaining ? `<details><summary>${escapeHtml(countLabel(change.stillWithoutEvidence.length, ["требование", "требования", "требований"]))}</summary><ul>${remaining}</ul></details>` : '<p>Требований без доказательств не осталось.</p>'}</div>
    </div>
  </section>`;
}

function renderAnalysis() {
  const panel = $("#analysis-panel");
  if (!state.analysis) { panel.classList.add("hidden"); renderVariant(); return; }
  const match = state.analysis.match;
  const matrix = match.requirementEvidenceMatrix || [];
  const matched = matrix.filter((row) => row.status === "MATCHED").length;
  const missing = matrix.filter((row) => row.status === "MISSING" || row.status === "BLOCKED").length;
  const unassessed = matrix.filter((row) => row.status === "UNASSESSED").length;
  const matrixRow = (row) => {
    const related = row.relatedRequirements || [];
    const originals = related.length > 1 ? `<details><summary>Исходные формулировки (${related.length})</summary><ul>${related.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul></details>` : "";
    return `<div class="matrix-row"><div><span>${escapeHtml(row.requirement)}</span>${originals}</div><b class="status-${row.status}">${escapeHtml(requirementStatusLabel(row.status))}</b></div>`;
  };
  const visibleRows = matrix.slice(0, 12);
  const remainingRows = matrix.slice(12);
  panel.innerHTML = `<div class="analysis-top">
    <div class="score"><b>${match.score}</b><small>из 100</small></div>
    <div><p class="eyebrow">${escapeHtml(state.analysis.analysis.role || state.vacancy.title)}</p><div class="recommendation">${escapeHtml(recommendationLabel(match.recommendation))}</div><p class="analysis-copy">${escapeHtml(match.reasoningSummary)}</p></div>
    <button id="reanalyze" class="button secondary" type="button">Обновить анализ</button>
  </div>
  <div class="analysis-summary"><span>${escapeHtml(countLabel(matched, ["требование подтверждено", "требования подтверждены", "требований подтверждено"]))}</span><span class="${missing ? "summary-danger" : ""}">${escapeHtml(countLabel(missing, ["требование не подтверждено", "требования не подтверждены", "требований не подтверждено"]))}</span><span>${escapeHtml(countLabel(unassessed, ["требование не оценено", "требования не оценены", "требований не оценено"]))}</span></div>
  ${analysisChangeMarkup(state.analysis.change)}
  <div class="matrix">${visibleRows.map(matrixRow).join("")}</div>${remainingRows.length ? `<details class="matrix-more"><summary>Показать остальные требования (${remainingRows.length})</summary><div class="matrix">${remainingRows.map(matrixRow).join("")}</div></details>` : ""}`;
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
  $("#tailor-state").className = state.variant.reviewedAt ? "pill" : "pill orange";
  $("#tailor-state").textContent = state.variant.reviewedAt ? "Проверено" : "Нужна проверка";
  $("#tailor-title").textContent = `Проверьте резюме для ${state.vacancy?.company || "выбранной вакансии"}`;
  $("#tailor-copy").textContent = "Просмотрите итоговый текст, все изменения и пробелы. Без вашего подтверждения версия не попадёт в отклик.";
  button.textContent = "Пересобрать резюме";
  download.href = `/api/v1/resume-variants/${state.variant.variantId}/pdf`;
  download.classList.remove("hidden");
  const plan = state.variant.plan;
  const gapGroups = plan.gapGroups?.length ? plan.gapGroups : (plan.gaps || []).map((gap, index) => ({ groupId: `legacy-${index}`, title: gap.requirement, importance: gap.importance, status: gap.status, kind: "EVIDENCE", requirements: [gap.requirement] }));
  const gapRequirementCount = plan.gaps?.length || 0;
  details.innerHTML = `<div class="variant-stats"><span><b>${plan.skillElementIds?.length || 0}</b> навыков выбрано</span><span><b>${gapGroups.length}</b> тем с пробелами<small>${gapGroups.length !== gapRequirementCount ? ` · ${gapRequirementCount} требований` : ""}</small></span><span><b>${plan.questions?.length || 0}</b> тем для уточнения</span><span><b>${state.variant.diff?.length || 0}</b> изменений</span></div>
    <div class="variant-review-grid">
      <section><div class="subsection-title"><h3>Итоговое резюме</h3><small>Полный текст версии</small></div><div class="resume-preview">${resumePreviewMarkup(state.variant.resume)}</div></section>
      <section><div class="subsection-title"><h3>Что изменилось</h3><small>Полный diff относительно выбранного подтверждённого содержания</small></div><div class="diff-list">${variantDiffMarkup(state.variant.diff)}</div></section>
    </div>
    <div class="variant-risks">
      <section><h3>Пробелы по темам</h3><p class="risk-explanation">Близкие требования объединены. Раскройте тему, чтобы увидеть исходные формулировки вакансии.</p>${gapGroups.map(tailoringGapGroupMarkup).join("") || '<div class="empty-state compact">Критичных пробелов не найдено.</div>'}</section>
      <section><h3>Что можно уточнить</h3><p class="risk-explanation">Близкие требования объединены по темам. Для опыта добавляйте только подтверждаемые факты; предпочтения меняются в настройках отклика.</p>${(plan.questions || []).map(tailoringQuestionMarkup).join("") || '<div class="empty-state compact">Дополнительных вопросов нет.</div>'}</section>
    </div>
    <div class="variant-approval ${state.variant.reviewedAt ? "approved" : ""}">${state.variant.reviewedAt ? `<div><b>Версия подтверждена</b><small>${escapeHtml(new Date(state.variant.reviewedAt).toLocaleString("ru-RU"))}</small></div>` : '<label class="checkbox-line"><input id="variant-review-check" type="checkbox"><span>Я проверил итоговый текст и принимаю показанные AI-переформулировки</span></label><button id="approve-variant" class="button primary" type="button" disabled>Подтвердить версию</button>'}</div>
    ${coverLetterMarkup(state.variant.coverLetter)}`;
  details.classList.remove("hidden");
  $("#prepare-application").disabled = !state.variant.reviewedAt;
  if (!state.variant.reviewedAt) {
    $("#variant-review-check").addEventListener("change", (event) => $("#approve-variant").disabled = !event.target.checked);
    $("#approve-variant").addEventListener("click", approveVariantReview);
  }
  details.querySelectorAll("[data-gap-answer]").forEach((form) => form.addEventListener("submit", saveGapFact));
  details.querySelectorAll("[data-gap-decision]").forEach((button) => button.addEventListener("click", saveGapDecision));
  $("#generate-cover-letter").addEventListener("click", generateCoverLetter);
  $("#copy-cover-letter")?.addEventListener("click", copyCoverLetter);
  renderResumeSelection();
}

function requirementStatusLabel(status) {
  return ({ MATCHED: "Подтверждено", MISSING: "Не подтверждено", BLOCKED: "Блокирует отклик", UNASSESSED: "Недостаточно данных" })[status] || status;
}

function requirementImportanceLabel(importance) {
  return ({ HARD_REQUIREMENT: "обязательное требование", SOFT_REQUIREMENT: "желательное требование", NICE_TO_HAVE: "будет преимуществом" })[importance] || importance;
}

function tailoringGapGroupMarkup(group) {
  const requirements = group.requirements || [];
  const details = requirements.length > 1 ? `<details class="question-requirements"><summary>Исходные требования (${requirements.length})</summary><ul>${requirements.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul></details>` : "";
  const action = group.kind === "PREFERENCE" ? '<small class="risk-group-action">Проверяется в настройках отклика</small>' : "";
  const decision = group.decision ? `<div class="gap-decision-summary"><b>${escapeHtml(gapDecisionLabel(group.decision.type))}</b><span>${escapeHtml(group.decision.explanation)}</span></div>` : "";
  return `<article class="risk-group"><div><b>${escapeHtml(group.title)}</b><span class="status-${group.status}">${escapeHtml(requirementStatusLabel(group.status))} · ${escapeHtml(requirementImportanceLabel(group.importance))}</span></div>${action}${decision}${details}</article>`;
}

function tailoringQuestionMarkup(question) {
  const related = question.relatedRequirements || [];
  const details = related.length > 1 ? `<details class="question-requirements"><summary>Какие требования объединены (${related.length})</summary><ul>${related.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul></details>` : "";
  if (question.kind === "PREFERENCE") {
    return `<article class="gap-answer preference-question" data-gap-question="${escapeHtml(question.questionId)}"><b>${escapeHtml(question.requirement)}</b><small>${escapeHtml(requirementImportanceLabel(question.importance))}</small><p>${escapeHtml(question.question)}</p>${details}${gapDecisionControls(question)}<a class="button secondary" href="#application-settings-form">Проверить настройки отклика</a></article>`;
  }
  return `<form class="gap-answer" data-gap-answer="${escapeHtml(question.questionId)}" data-gap-question="${escapeHtml(question.questionId)}"><b>${escapeHtml(question.requirement)}</b><small>${escapeHtml(requirementImportanceLabel(question.importance))}</small><p>${escapeHtml(question.question)}</p>${details}${gapDecisionControls(question)}<textarea name="text" rows="3" maxlength="4000" required placeholder="Например: проект, период, ваша роль, технология и измеримый результат"></textarea><div><select name="type" aria-label="Тип факта"><option value="EXPERIENCE">Опыт</option><option value="SKILL">Навык</option><option value="PROJECT">Проект</option><option value="EDUCATION">Образование</option><option value="CERTIFICATION">Сертификация</option><option value="OTHER">Другое</option></select><button class="button secondary" type="submit">Добавить подтверждаемый факт</button></div></form>`;
}

function gapDecisionLabel(type) {
  return ({ CONFIRMED_FACT_ADDED: "Добавлен подтверждённый факт", CANNOT_CONFIRM: "Не могу подтвердить", NOT_APPLICABLE: "Не относится ко мне", ACCEPT_RISK: "Риск принят" })[type] || type;
}

function gapDecisionControls(question) {
  const current = question.decision ? `<div class="gap-decision-summary"><b>${escapeHtml(gapDecisionLabel(question.decision.type))}</b><span>${escapeHtml(question.decision.explanation)}</span></div>` : "";
  return `${current}<div class="gap-decision-controls"><input name="decisionExplanation" maxlength="2000" placeholder="Комментарий к решению (необязательно)"><div><button class="button secondary" type="button" data-gap-decision="CANNOT_CONFIRM">Не могу подтвердить</button><button class="button secondary" type="button" data-gap-decision="NOT_APPLICABLE">Не относится ко мне</button><button class="button secondary" type="button" data-gap-decision="ACCEPT_RISK">Принять риск</button></div></div>`;
}

async function saveGapFact(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const button = form.querySelector("button[type=submit]");
  const values = Object.fromEntries(new FormData(form));
  busy(button, true, "Сохраняем…");
  try {
    await request(`/api/v1/resume-variants/${state.variant.variantId}/gap-decisions/${encodeURIComponent(form.dataset.gapAnswer)}`, {
      method: "PUT",
      body: { type: "CONFIRMED_FACT_ADDED", factType: values.type, factText: values.text.trim(), explanation: values.decisionExplanation?.trim() || null },
    });
    state.profile = await request("/api/v1/candidate-profile");
    renderProfile();
    await analyzeSelectedVacancy();
    toast("Факт сохранён в профиле, анализ обновлён — создайте новую версию резюме");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
}

async function saveGapDecision(event) {
  const button = event.currentTarget;
  const card = button.closest("[data-gap-question]");
  const questionId = card?.dataset.gapQuestion;
  if (!state.variant || !questionId) return;
  busy(button, true, "Сохраняем…");
  try {
    const explanation = card.querySelector('[name="decisionExplanation"]')?.value.trim() || null;
    const decision = await request(`/api/v1/resume-variants/${state.variant.variantId}/gap-decisions/${encodeURIComponent(questionId)}`, {
      method: "PUT",
      body: { type: button.dataset.gapDecision, explanation },
    });
    state.variant.plan.questions = state.variant.plan.questions.map((item) => item.questionId === questionId ? { ...item, decision } : item);
    const groupId = state.variant.plan.questions.find((item) => item.questionId === questionId)?.groupId;
    state.variant.plan.gapGroups = (state.variant.plan.gapGroups || []).map((item) => item.groupId === groupId ? { ...item, decision } : item);
    renderVariant();
    toast("Решение сохранено для следующих повторных анализов");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
}

async function generateCoverLetter() {
  if (!state.variant) return;
  const variantId = state.variant.variantId;
  const button = $("#generate-cover-letter");
  busy(button, true, "Генерируем…");
  try {
    const coverLetter = await request(`/api/v1/resume-variants/${variantId}/cover-letter`, { method: "POST" });
    if (state.variant?.variantId !== variantId) return;
    state.variant.coverLetter = coverLetter;
    renderVariant();
    toast("Текст cover letter готов");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
}

async function copyCoverLetter() {
  const text = state.variant?.coverLetter?.text;
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    toast("Cover letter скопирован");
  } catch (_) {
    const field = $("#cover-letter-text");
    field.focus(); field.select();
    toast("Текст выделен — скопируйте его вручную");
  }
}

async function approveVariantReview() {
  const button = $("#approve-variant");
  busy(button, true, "Подтверждаем…");
  try {
    state.variant = await request(`/api/v1/resume-variants/${state.variant.variantId}/review-approval`, { method: "POST" });
    renderVariant();
    renderDraft();
    toast("Версия резюме подтверждена — теперь её можно использовать в отклике");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
}

function renderResumeSelection() {
  const panel = $("#resume-selection");
  if (!state.analysis || !state.resumeSelection) { panel.classList.add("hidden"); return; }
  const groups = [
    ["Контакты", "contact", state.resumeSelection.contacts || []],
    ["Опыт работы", "experience", state.resumeSelection.experiences || []],
    ["Образование", "education", state.resumeSelection.education || []],
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
    educationElementIds: selected("education"),
    skillElementIds: selected("skill"),
    photoDataUri: state.resumePhotoDataUri
  };
}

function clearResumePhoto() {
  state.resumePhotoDataUri = null;
  $("#resume-photo").value = "";
  $("#resume-photo-name").textContent = "Без фото";
  $("#remove-resume-photo").classList.add("hidden");
}

function selectResumePhoto(event) {
  const file = event.target.files?.[0];
  if (!file) { clearResumePhoto(); return; }
  if (!["image/jpeg", "image/png"].includes(file.type) || file.size > 2 * 1024 * 1024) {
    clearResumePhoto();
    toast("Выберите JPEG или PNG размером до 2 МБ", true);
    return;
  }
  const reader = new FileReader();
  reader.onload = () => {
    state.resumePhotoDataUri = reader.result;
    $("#resume-photo-name").textContent = file.name;
    $("#remove-resume-photo").classList.remove("hidden");
  };
  reader.onerror = () => { clearResumePhoto(); toast("Не удалось прочитать фото", true); };
  reader.readAsDataURL(file);
}

function statusLabel(status) {
  return ({ DRAFT: "Черновик", FILLING: "Заполнение", NEEDS_INPUT: "Нужны ответы", READY_TO_SUBMIT: "Готов к отправке", SUBMITTED: "Отправлен", FAILED: "Ошибка" })[status] || status;
}

function observedField(fieldKey) {
  return state.draft?.draft?.observedFields?.find((field) => field.fieldKey === fieldKey);
}

function fieldDisplayName(fieldKey, fallback = "Поле формы") {
  const field = observedField(fieldKey);
  return field?.label && field.label !== field.fieldKey ? field.label : fallback;
}

function questionReasonLabel(reason) {
  return ({
    NO_EXPLICIT_ANSWER: "Нужен ваш ответ",
    SENSITIVE_TOPIC: "Ответ можно использовать только с вашего явного согласия",
    UNKNOWN_FIELD: "Система не может безопасно подобрать ответ",
    OPTION_MISMATCH: "Сохранённый ответ не совпадает с вариантами формы",
    VALUE_TOO_LONG: "Ответ превышает ограничение формы",
    MISSING_ARTIFACT: "Нужно выбрать файл",
  })[reason] || "Нужен ваш ответ";
}

function approvalControlMarkup(approval) {
  const field = observedField(approval.fieldKey) || {};
  const options = approval.options || field.options || [];
  if (options.length) {
    return `<select data-value-for="${approval.approvalId}"><option value="">Выберите ответ</option>${options.map((option) => `<option value="${escapeHtml(option)}">${escapeHtml(option)}</option>`).join("")}</select>`;
  }
  if (field.type === "CHECKBOX") {
    return `<select data-value-for="${approval.approvalId}"><option value="">Выберите ответ</option><option value="true">Да, подтверждаю</option><option value="false">Нет</option></select>`;
  }
  if (field.type === "FILE") {
    return '<div class="approval-control-note">Этот вопрос требует отдельного файла. Добавьте его в форме браузера.</div>';
  }
  if (field.type === "TEXTAREA") {
    return `<textarea data-value-for="${approval.approvalId}" rows="4" placeholder="Ваш ответ"></textarea>`;
  }
  const type = ({ EMAIL: "email", PHONE: "tel", NUMBER: "number", DATE: "date", URL: "url" })[field.type] || "text";
  return `<input data-value-for="${approval.approvalId}" type="${type}" placeholder="Ваш ответ">`;
}

function renderDraft() {
  const panel = $("#application-status");
  const approvals = $("#approval-list");
  if (!state.draft) {
    panel.className = "empty-state";
    panel.textContent = state.variant ? "Укажите ссылку на форму и подготовьте отклик." : "Сначала создайте вариант резюме под вакансию.";
    approvals.innerHTML = "";
    $("#start-browser").classList.add("hidden");
    $("#record-manual-submission").classList.add("hidden");
    $("#submit-review").classList.add("hidden");
    renderBrowserWorkflow();
    return;
  }
  const draft = state.draft.draft;
  panel.className = "empty-state";
  panel.innerHTML = `<strong>${escapeHtml(statusLabel(draft.status))}</strong><br><span>${draft.answers?.length || 0} полей подготовлено · ${state.draft.artifacts?.length || 0} вложений</span>${draft.submission ? `<div class="submission-result"><b>${escapeHtml(draft.submission.mode === "MANUAL" ? "Отправлено вручную" : "Отправлено управляемым браузером")}</b><span>${escapeHtml(new Date(draft.submission.submittedAt).toLocaleString("ru-RU"))}</span>${draft.submission.reference ? `<span>Подтверждение: ${escapeHtml(draft.submission.reference)}</span>` : ""}${draft.submission.note ? `<small>${escapeHtml(draft.submission.note)}</small>` : ""}</div>` : ""}`;
  $("#start-browser").classList.toggle("hidden", draft.status === "SUBMITTED" || draft.status === "FAILED");
  $("#record-manual-submission").classList.toggle("hidden", draft.status !== "READY_TO_SUBMIT");
  renderSubmitReview();
  const pending = state.draft.pendingApprovals || [];
  const orderedPending = [...pending].sort((left, right) => Number(right.required) - Number(left.required));
  approvals.innerHTML = orderedPending.map((approval) => {
    if (approval.type === "SUBMIT") return `<div class="approval"><div><p>${escapeHtml(approval.question)}</p><small>Будет отправлено только текущее подтверждённое состояние.</small></div><button class="button primary" data-submit-approval="${approval.approvalId}">Подтвердить и отправить</button></div>`;
    const control = approvalControlMarkup(approval);
    const technicalKey = observedField(approval.fieldKey)?.label === approval.fieldKey ? `<small class="technical-field-key">${escapeHtml(approval.fieldKey)}</small>` : "";
    return `<div class="approval ${approval.required ? "approval-required" : ""}"><div><div class="approval-heading"><p>${escapeHtml(approval.question)}</p>${approval.required ? '<span class="pill orange">Обязательный вопрос</span>' : '<span class="pill muted">Необязательно</span>'}</div><small>${escapeHtml(questionReasonLabel(approval.reason))}</small>${technicalKey}</div><div class="approval-controls">${control}<label class="remember-answer"><input type="checkbox" data-save-for="${approval.approvalId}"><span>Запомнить для следующих откликов</span></label><button class="button primary" data-answer-approval="${approval.approvalId}"${observedField(approval.fieldKey)?.type === "FILE" ? " disabled" : ""}>Сохранить ответ</button></div></div>`;
  }).join("");
  if (draft.status === "READY_TO_SUBMIT" && !pending.some((item) => item.type === "SUBMIT")) {
    approvals.insertAdjacentHTML("beforeend", '<div class="approval"><div><p>Всё готово к финальной проверке</p><small>Сначала создайте отдельное подтверждение отправки.</small></div><button id="request-submit" class="button secondary">Проверить перед отправкой</button></div>');
    $("#request-submit").addEventListener("click", requestSubmitApproval);
  }
  approvals.querySelectorAll("[data-answer-approval]").forEach((button) => button.addEventListener("click", () => answerApproval(button.dataset.answerApproval)));
  approvals.querySelectorAll("[data-submit-approval]").forEach((button) => button.addEventListener("click", () => approveAndSubmit(button.dataset.submitApproval)));
  renderBrowserWorkflow();
}

function answerSourceLabel(source) {
  return ({ RESUME: "подтверждённое резюме", PROFILE: "профиль", SETTINGS: "настройки", CATALOG: "каталог ответов", USER: "ваш ответ", DECLINED_BY_USER: "вы отказались отвечать", ARTIFACT: "вложение" })[source] || source || "нет источника";
}

function artifactTypeLabel(type) {
  return ({ RESUME_PDF: "Подготовленное резюме", COVER_LETTER: "Сопроводительное письмо", SCREENSHOT: "Снимок формы", SUBMISSION_RECEIPT: "Подтверждение отправки" })[type] || "Вложение";
}

function browserValidationMessage(error) {
  return ({
    VALUE_MISSING: "Заполните обязательное поле",
    TYPE_MISMATCH: "Проверьте формат значения",
    PATTERN_MISMATCH: "Значение не соответствует формату формы",
    TOO_LONG: "Сократите значение",
    TOO_SHORT: "Добавьте недостающие данные",
    RANGE_UNDERFLOW: "Значение меньше разрешённого",
    RANGE_OVERFLOW: "Значение больше разрешённого",
    STEP_MISMATCH: "Выберите допустимое значение",
    BAD_INPUT: "Форма не может прочитать значение",
  })[error.code] || "Проверьте значение в форме браузера";
}

function safeDisplayUrl(value) {
  if (!value) return "Не указан";
  try {
    const url = new URL(value);
    return `${url.origin}${url.pathname}`;
  } catch (_) { return "Некорректный URL"; }
}

function countLabel(count, forms) {
  const mod10 = count % 10;
  const mod100 = count % 100;
  const form = mod10 === 1 && mod100 !== 11 ? forms[0] : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? forms[1] : forms[2];
  return `${count} ${form}`;
}

function renderSubmitReview() {
  const panel = $("#submit-review");
  if (!state.draft) { panel.classList.add("hidden"); return; }
  const draft = state.draft.draft;
  const answers = draft.answers || [];
  const answerByKey = new Map(answers.map((answer) => [answer.fieldKey, answer]));
  const fieldByKey = new Map((draft.observedFields || []).map((field) => [field.fieldKey, field]));
  answers.forEach((answer) => { if (!fieldByKey.has(answer.fieldKey)) fieldByKey.set(answer.fieldKey, { fieldKey: answer.fieldKey, label: answer.question, required: false }); });
  const rows = [...fieldByKey.values()].map((field) => ({ field, answer: answerByKey.get(field.fieldKey) }));
  const missing = rows.filter(({ field, answer }) => field.required && !answer?.value);
  const declined = answers.filter((answer) => answer.source === "DECLINED_BY_USER");
  const pending = (state.draft.pendingApprovals || []).filter((approval) => approval.type === "ANSWER");
  const validation = state.browserSession?.validationErrors || [];
  const formUrl = draft.formUrl || state.browserSession?.formUrl || $("#application-url").value.trim();
  const blockingPending = pending.filter((approval) => approval.required);
  const optionalPending = pending.filter((approval) => !approval.required);
  const answeredCount = rows.filter(({ answer }) => answer?.value != null).length;
  const requiredRows = rows.filter(({ field }) => field.required);
  const unresolvedAnswerKeys = new Set([
    ...missing.map(({ field }) => field.fieldKey),
    ...blockingPending.map((approval) => approval.fieldKey),
  ]);
  const blockingKeys = new Set([
    ...unresolvedAnswerKeys,
    ...validation.map((error) => error.fieldKey || `form:${error.code}`),
    ...(!formUrl ? ["form-url"] : []),
  ]);
  const validationOnly = validation.filter((error) => !error.fieldKey || !unresolvedAnswerKeys.has(error.fieldKey));
  const warnings = [
    !formUrl ? "Не сохранён URL формы" : null,
    missing.length ? `${missing.length} обязательных полей без значения` : null,
    validationOnly.length ? `${validationOnly.length} заполненных полей не прошли проверку формы` : null,
    optionalPending.length ? `${optionalPending.length} необязательных вопросов оставлены без ответа` : null,
    declined.length ? `${declined.length} полей сознательно пропущено` : null,
    !(state.draft.artifacts || []).length ? "Нет вложений" : null,
  ].filter(Boolean);
  panel.innerHTML = `<div class="submit-review-heading"><div><p class="eyebrow">Финальная проверка</p><h3>${blockingKeys.size ? "Что нужно исправить перед отправкой" : "Что будет отправлено"}</h3></div><span class="pill ${blockingKeys.size ? "orange" : ""}">${blockingKeys.size ? escapeHtml(countLabel(blockingKeys.size, ["блокирующий пункт", "блокирующих пункта", "блокирующих пунктов"])) : warnings.length ? escapeHtml(countLabel(warnings.length, ["замечание", "замечания", "замечаний"])) : "Без предупреждений"}</span></div>
    <div class="review-progress-summary"><span><b>${answeredCount}/${rows.length}</b> полей подготовлено</span><span><b>${requiredRows.length - missing.length}/${requiredRows.length}</b> обязательных заполнено</span><span class="${blockingKeys.size ? "summary-danger" : "summary-ready"}"><b>${blockingKeys.size}</b> требуют действия</span></div>
    <div class="submit-review-url"><span>Форма отклика · параметры ссылки скрыты</span><strong>${escapeHtml(safeDisplayUrl(formUrl))}</strong></div>
    <p class="privacy-note">На этом экране значения показаны для вашей проверки. В техническом журнале вместо них сохраняется только отметка о выполненном действии.</p>
    <div class="submit-review-fields"><h4>Все поля и источники</h4>${rows.map(({ field, answer }) => `<div class="submit-field ${field.required && !answer?.value ? "missing" : !answer?.value ? "skipped" : ""}" title="Технический ключ: ${escapeHtml(field.fieldKey)}"><div><b>${escapeHtml(field.label || "Неизвестное поле")}</b>${field.required ? "<small>Обязательное</small>" : ""}</div><p>${answer?.value != null ? escapeHtml(answer.value) : "— пропущено —"}</p><span>${escapeHtml(answerSourceLabel(answer?.source))}</span></div>`).join("") || '<div class="empty-state compact">Поля формы ещё не были просканированы.</div>'}</div>
    <div class="submit-review-bottom"><section><h4>Вложения</h4>${(state.draft.artifacts || []).map((artifact) => `<a class="artifact-row" href="/api/v1/application-drafts/${draft.draftId}/artifacts/${artifact.artifactId}" title="${escapeHtml(artifact.fileName)}"><b>${escapeHtml(artifactTypeLabel(artifact.type))}</b><span>${Math.ceil(artifact.byteSize / 1024)} КБ</span><small>Контрольная сумма ${escapeHtml(artifact.sha256.slice(0, 12))}…</small></a>`).join("") || '<div class="empty-state compact">Вложений нет.</div>'}</section><section><h4>Пропуски и предупреждения</h4>${warnings.map((warning) => `<div class="submit-warning">${escapeHtml(warning)}</div>`).join("") || '<div class="empty-state compact">Критичных предупреждений нет.</div>'}</section></div>`;
  panel.classList.remove("hidden");
}

function browserStopCopy(session) {
  const challenges = (session.challenges || []).map((value) => ({ CAPTCHA: "CAPTCHA", OTP: "одноразовый код", REAUTHENTICATION: "повторный вход" })[value] || value).join(", ");
  return {
    CHALLENGE: { title: `Нужно пройти: ${challenges || "проверку на странице"}`, action: "Завершите действие в открытом окне браузера, затем нажмите «Проверить форму ещё раз»." },
    VALIDATION_ERRORS: { title: "Некоторые заполненные значения не прошли проверку", action: "Исправьте отмеченные поля в окне браузера, затем повторите проверку." },
    PENDING_ANSWERS: { title: "Нужны ответы на обязательные вопросы", action: "Сохраните ответы в блоке выше. После этого продолжите заполнение формы." },
    RESCAN_LIMIT: { title: "Форма несколько раз изменилась", action: "Проверьте текущий шаг в браузере и запустите ещё одно пересканирование." },
    RUNNER_ERROR: { title: "Управляемый браузер остановился", action: "Проверьте, что окно браузера открыто, затем попробуйте продолжить." },
    SUBMIT_ERROR: { title: "Не удалось отправить форму", action: "Проверьте ошибки на странице. Повторная отправка потребует актуального подтверждения." }
  }[session.stopReason] || { title: session.status === "ACTIVE" ? "Доступные поля заполнены" : "Сессия приостановлена", action: "Проверьте открытую форму перед следующим действием." };
}

function browserAuditLabel(event) {
  return ({
    RUN_PAUSED: "Заполнение приостановлено", RUN_REPLAYED: "Повторный запуск распознан",
    RUN_FAILED: "Заполнение завершилось ошибкой", FIELD_PLANNED: "Поле подготовлено",
    FIELD_APPLIED: "Поле заполнено", FIELD_SKIPPED: "Поле пропущено",
    VALIDATION_ERROR: "Поле не прошло проверку", FORM_STABLE: "Форма больше не меняется",
    RESCAN_LIMIT: "Достигнут предел повторных проверок", SUBMIT_CONFIRMED: "Отправка подтверждена",
    SUBMIT_FAILED: "Не удалось отправить", SUBMIT_REFUSED: "Отправка остановлена безопасно",
  })[event] || "Техническое событие";
}

function browserDetailLabel(code) {
  return ({
    VALUE_REDACTED: "значение применено и скрыто", NO_APPROVED_ANSWER: "нет подтверждённого ответа",
    PENDING_ANSWER: "ожидается ответ", PENDING_ANSWERS: "ожидаются обязательные ответы",
    VALUE_MISSING: "обязательное поле пустое", TYPE_MISMATCH: "неверный формат",
    OPTION_MISMATCH: "ответ не совпадает с вариантами", VALIDATION_ERRORS: "есть ошибки формы",
    NO_NEW_FIELDS: "новых полей нет", NO_FIELDS: "полей на текущем шаге нет",
  })[code] || code?.replaceAll("_", " ").toLowerCase() || "";
}

function renderBrowserWorkflow() {
  const panel = $("#browser-workflow");
  if (!state.draft) { panel.classList.add("hidden"); return; }
  panel.classList.remove("hidden");
  const session = state.browserSession;
  if (!session) {
    $("#browser-session-summary").innerHTML = '<div class="empty-state compact">Управляемый браузер ещё не запускался. Укажите HTTPS-ссылку выше и откройте форму.</div>';
    $("#browser-session-actions").innerHTML = "";
    $("#browser-field-progress").innerHTML = "";
    $("#browser-audit").innerHTML = "";
    $("#browser-diagnostics").innerHTML = "";
    return;
  }
  const copy = browserStopCopy(session);
  const terminal = session.status === "SUBMITTED" || session.status === "CLOSED";
  $("#application-url").value = session.formUrl || $("#application-url").value;
  $("#start-browser").classList.add("hidden");
  const sessionStatus = ({ ACTIVE: "Форма заполнена", PAUSED: "Нужны действия", SUBMITTED: "Отправлено", CLOSED: "Сессия закрыта" })[session.status] || session.status;
  const blockingApprovals = (state.draft.pendingApprovals || []).filter((approval) => approval.type === "ANSWER" && approval.required);
  const validation = session.validationErrors || [];
  const fields = session.fieldStates || [];
  const appliedCount = fields.filter((field) => field.status === "APPLIED").length;
  const actionKeys = new Set([...blockingApprovals.map((approval) => approval.fieldKey), ...validation.map((error) => error.fieldKey || `form:${error.code}`)]);
  $("#browser-session-summary").innerHTML = `<div class="browser-progress-summary"><span><b>${appliedCount}/${fields.length}</b> заполнено в браузере</span><span><b>${blockingApprovals.length}</b> обязательных ответов осталось</span><span class="${actionKeys.size ? "summary-danger" : "summary-ready"}"><b>${actionKeys.size}</b> требуют действия</span></div><div class="browser-stop browser-stop-${escapeHtml(session.stopReason || session.status)}"><div><span class="pill ${session.status === "ACTIVE" || session.status === "SUBMITTED" ? "" : "orange"}">${escapeHtml(sessionStatus)}</span><h4>${escapeHtml(copy.title)}</h4><p>${escapeHtml(copy.action)}</p></div><dl><div><dt>Текущая страница</dt><dd>${escapeHtml(safeDisplayUrl(session.currentUrl))}</dd></div><div><dt>Повторных проверок</dt><dd>${session.resumeCount || 0}</dd></div><div><dt>Продолжение</dt><dd>${session.restorable ? "сессия сохранена" : "оставьте окно браузера открытым"}</dd></div>${session.failureCode ? `<div><dt>Причина остановки</dt><dd>${escapeHtml(browserDetailLabel(session.failureCode))}</dd></div>` : ""}</dl></div>`;
  $("#browser-session-actions").innerHTML = terminal ? "" : `<button id="resume-browser" class="button primary" type="button"${blockingApprovals.length ? " disabled" : ""}>${blockingApprovals.length ? "Сначала ответьте на обязательные вопросы" : "Проверить форму ещё раз"}</button><small>${blockingApprovals.length ? `Осталось обязательных вопросов: ${blockingApprovals.length}. Они находятся выше.` : "Повторно читается текущий шаг формы; уже применённые поля не заполняются второй раз."}</small>`;
  $("#resume-browser")?.addEventListener("click", resumeBrowserRun);
  const fieldStatusLabel = (status) => ({ APPLIED: "Заполнено", PENDING_INPUT: "Нужен ответ", VALIDATION_ERROR: "Нужно исправить", SKIPPED: "Пропущено", PLANNED: "Подготовлено", OBSERVED: "Обнаружено" })[status] || status;
  const orderedFields = [...fields].sort((left, right) => Number(actionKeys.has(right.fieldKey)) - Number(actionKeys.has(left.fieldKey)));
  $("#browser-field-progress").innerHTML = `${validation.length ? `<section class="validation-errors"><h4>Поля, требующие внимания</h4>${validation.map((error) => `<div title="${escapeHtml(error.code)}"><b>${escapeHtml(fieldDisplayName(error.fieldKey, "Форма"))}</b><span>${escapeHtml(browserValidationMessage(error))}</span></div>`).join("")}</section>` : ""}<section class="field-progress"><h4>Состояние полей</h4>${orderedFields.map((field) => `<div title="${escapeHtml([field.fieldKey, field.detailCode].filter(Boolean).join(" · "))}"><span>${escapeHtml(fieldDisplayName(field.fieldKey, "Неизвестное поле"))}</span><b class="browser-field-${field.status}">${escapeHtml(fieldStatusLabel(field.status))}</b></div>`).join("") || '<div class="empty-state compact">Поля ещё не обнаружены.</div>'}</section>`;
  const audit = state.browserAudit.slice(-30).reverse();
  $("#browser-audit").innerHTML = `<h4>Последние события</h4>${audit.map((item) => `<div class="audit-row" title="${escapeHtml([item.event, item.fieldKey, item.detailCode].filter(Boolean).join(" · "))}"><time>${escapeHtml(new Date(item.recordedAt).toLocaleTimeString("ru-RU"))}</time><b>${escapeHtml(browserAuditLabel(item.event))}</b><span>${escapeHtml([item.fieldKey ? fieldDisplayName(item.fieldKey, "Неизвестное поле") : null, browserDetailLabel(item.detailCode)].filter(Boolean).join(" · "))}</span></div>`).join("") || '<div class="empty-state compact">Событий пока нет.</div>'}`;
  const diagnostic = state.browserDiagnostics.at(-1);
  $("#browser-diagnostics").innerHTML = diagnostic ? `<h4>Последний технический снимок</h4><div class="diagnostic-summary"><span>${escapeHtml(diagnostic.origin)}</span><span>${escapeHtml(countLabel(diagnostic.fields?.length || 0, ["поле", "поля", "полей"]))}</span><span>${(diagnostic.challenges || []).length ? escapeHtml(countLabel(diagnostic.challenges.length, ["проверка доступа", "проверки доступа", "проверок доступа"])) : "нет CAPTCHA или OTP"}</span><span>${(diagnostic.validationErrorCodes || []).length ? escapeHtml(countLabel(diagnostic.validationErrorCodes.length, ["ошибка формы", "ошибки формы", "ошибок формы"])) : "ошибок формы нет"}</span><span>${escapeHtml(new Date(diagnostic.recordedAt).toLocaleString("ru-RU"))}</span></div>` : "";
}

function resetBrowserWorkflow() {
  state.browserSession = null;
  state.browserAudit = [];
  state.browserDiagnostics = [];
}

async function loadBrowserWorkflow() {
  if (!state.draft) { resetBrowserWorkflow(); renderBrowserWorkflow(); return; }
  const draftId = state.draft.draft.draftId;
  const [session, audit, diagnostics] = await Promise.all([
    optional(`/api/v1/application-drafts/${draftId}/browser-session`),
    request(`/api/v1/application-drafts/${draftId}/browser-audit`),
    request(`/api/v1/application-drafts/${draftId}/browser-diagnostics`)
  ]);
  if (state.draft?.draft?.draftId !== draftId) return;
  state.browserSession = session;
  state.browserAudit = audit;
  state.browserDiagnostics = diagnostics;
  renderBrowserWorkflow();
  renderSubmitReview();
}

function booleanSelectValue(value) {
  return value == null ? "" : String(value);
}

function renderApplicationSettings() {
  const settings = state.settings || {};
  const form = $("#application-settings-form");
  form.elements.salaryAmount.value = settings.desiredSalary?.amount ?? "";
  form.elements.salaryCurrency.value = settings.desiredSalary?.currency || "";
  form.elements.salaryPeriod.value = settings.desiredSalary?.period || "YEAR";
  form.elements.salaryNegotiable.checked = Boolean(settings.desiredSalary?.negotiable);
  form.elements.workAuthorizations.value = (settings.workAuthorizations || []).map((item) => `${item.country} | ${item.status}`).join("\n");
  form.elements.requiresSponsorship.value = booleanSelectValue(settings.requiresSponsorship);
  form.elements.relocationWilling.value = booleanSelectValue(settings.relocation?.willing);
  form.elements.relocationLocations.value = (settings.relocation?.locations || []).join(", ");
  form.elements.relocationNotes.value = settings.relocation?.notes || "";
  form.elements.remotePreference.value = settings.remotePreference || "";
  form.elements.noticePeriod.value = settings.noticePeriod || "";
  form.elements.earliestStartDate.value = settings.earliestStartDate || "";
  const configured = Boolean(settings.desiredSalary || settings.workAuthorizations?.length || settings.requiresSponsorship != null || settings.relocation || settings.remotePreference || settings.noticePeriod || settings.earliestStartDate);
  $("#settings-state").className = configured ? "pill" : "pill muted";
  $("#settings-state").textContent = configured ? "Сохранено" : "Не заполнено";
}

function renderAnswerCatalog() {
  $("#catalog-count").textContent = state.catalog.length;
  const list = $("#answer-catalog-list");
  list.innerHTML = state.catalog.length ? state.catalog.map((entry) => `<div class="catalog-item"><div><strong>${escapeHtml(entry.question || "Сохранённый ответ")}</strong><p>${escapeHtml(entry.value)}</p><small>${escapeHtml(catalogTopicLabel(entry.topic))}</small></div><div class="editable-actions"><button type="button" data-edit-answer="${escapeHtml(entry.key)}">Изменить</button><button type="button" data-delete data-delete-answer="${escapeHtml(entry.key)}">Удалить</button></div></div>`).join("") : '<div class="empty-state compact">Сохранённых ответов пока нет.</div>';
  list.querySelectorAll("[data-edit-answer]").forEach((button) => button.addEventListener("click", () => editCatalogEntry(button.dataset.editAnswer)));
  list.querySelectorAll("[data-delete-answer]").forEach((button) => button.addEventListener("click", () => deleteCatalogEntry(button.dataset.deleteAnswer)));
}

function catalogTopicLabel(topic) {
  return ({ UNKNOWN: "Другое", COVER_LETTER: "Сопроводительное письмо", DESIRED_SALARY: "Желаемая зарплата", WORK_AUTHORIZATION: "Разрешение на работу", VISA_SPONSORSHIP: "Sponsorship", RELOCATION: "Переезд", REMOTE_PREFERENCE: "Формат работы", NOTICE_PERIOD: "Notice period", START_DATE: "Дата выхода", YEARS_OF_EXPERIENCE: "Опыт в годах", BACKGROUND_CHECK: "Background check", REFERENCES: "Рекомендации" })[topic] || "Другое";
}

function catalogKey(topic, question) {
  if (topic !== "UNKNOWN") return topic;
  const normalized = question.toLocaleLowerCase("ru-RU").replace(/[^\p{L}\p{N}]+/gu, " ").trim().split(/\s+/).join("-").slice(0, 120);
  return `QUESTION:${normalized}`;
}

async function loadApplicationPreferences() {
  [state.settings, state.catalog] = await Promise.all([
    request("/api/v1/application-settings"),
    request("/api/v1/application-answers")
  ]);
  renderApplicationSettings();
  renderAnswerCatalog();
}

function parseWorkAuthorizations(value) {
  return value.split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
    const separator = line.indexOf("|");
    if (separator < 1 || separator === line.length - 1) throw new Error(`Проверьте строку разрешения на работу: «${line}»`);
    return { country: line.slice(0, separator).trim(), status: line.slice(separator + 1).trim() };
  });
}

function optionalBoolean(value) {
  return value === "" ? null : value === "true";
}

async function saveApplicationSettings(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const button = form.querySelector("button[type=submit]");
  busy(button, true, "Сохраняем…");
  try {
    const values = Object.fromEntries(new FormData(form));
    const salaryAmount = values.salaryAmount?.trim();
    const relocationWilling = optionalBoolean(values.relocationWilling);
    const body = {
      desiredSalary: salaryAmount ? { amount: Number(salaryAmount), currency: values.salaryCurrency.trim().toUpperCase(), period: values.salaryPeriod, negotiable: form.elements.salaryNegotiable.checked } : null,
      workAuthorizations: parseWorkAuthorizations(values.workAuthorizations || ""),
      requiresSponsorship: optionalBoolean(values.requiresSponsorship),
      relocation: relocationWilling == null ? null : { willing: relocationWilling, locations: values.relocationLocations.split(",").map((item) => item.trim()).filter(Boolean), notes: values.relocationNotes.trim() || null },
      remotePreference: values.remotePreference.trim() || null,
      noticePeriod: values.noticePeriod.trim() || null,
      earliestStartDate: values.earliestStartDate || null
    };
    if (salaryAmount && body.desiredSalary.currency.length !== 3) throw new Error("Для зарплаты укажите трёхбуквенный код валюты, например USD");
    state.settings = await request("/api/v1/application-settings", { method: "PUT", body });
    renderApplicationSettings();
    toast("Настройки отклика сохранены");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
}

function editCatalogEntry(key) {
  const entry = state.catalog.find((item) => item.key === key);
  if (!entry) return;
  const form = $("#answer-catalog-form");
  form.elements.key.value = entry.key;
  form.elements.question.value = entry.question;
  form.elements.value.value = entry.value;
  form.elements.topic.value = entry.topic;
  form.querySelector("button").textContent = "Сохранить ответ";
  form.scrollIntoView({ behavior: "smooth", block: "center" });
}

async function saveCatalogEntry(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const values = Object.fromEntries(new FormData(form));
  const button = form.querySelector("button");
  busy(button, true, "Сохраняем…");
  try {
    const key = values.key || catalogKey(values.topic, values.question);
    await request(`/api/v1/application-answers/${encodeURIComponent(key)}`, { method: "PUT", body: { question: values.question.trim(), value: values.value.trim(), topic: values.topic } });
    state.catalog = await request("/api/v1/application-answers");
    form.reset(); form.elements.key.value = ""; button.textContent = "Добавить ответ";
    renderAnswerCatalog(); toast("Ответ сохранён для следующих откликов");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
}

async function deleteCatalogEntry(key) {
  if (!window.confirm("Удалить сохранённый ответ?")) return;
  try {
    await request(`/api/v1/application-answers/${encodeURIComponent(key)}`, { method: "DELETE" });
    state.catalog = state.catalog.filter((item) => item.key !== key);
    renderAnswerCatalog(); toast("Ответ удалён");
  } catch (error) { toast(error.message, true); }
}

async function loadInitialState() {
  const status = $("#service-status");
  try {
    const [profile, identities, confirmed, vacancies, settings, catalog] = await Promise.all([
      optional("/api/v1/candidate-profile"),
      request("/api/v1/candidate-identities"),
      optional("/api/v1/candidate-profile/resume-imports/confirmed/latest"),
      request("/api/v1/vacancies"),
      request("/api/v1/application-settings"),
      request("/api/v1/application-answers")
    ]);
    state.profile = profile;
    state.identities = identities;
    state.vacancies = vacancies;
    state.settings = settings;
    state.catalog = catalog;
    if (confirmed) { $("#resume-state").className = "pill"; $("#resume-state").textContent = "Резюме подтверждено"; }
    renderProfile(); renderIdentities(); renderProfileEditor(); renderVacancies(); renderApplicationSettings(); renderAnswerCatalog();
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
  fillVacancyForm(vacancy);
  state.analysis = null; state.variant = null; state.draft = null; state.resumeSelection = null; clearResumePhoto();
  resetBrowserWorkflow();
  renderVacancies(); renderAnalysis(); renderVariant(); renderDraft();
  const [analysis, variant, draft] = await Promise.all([
    optional(`/api/v1/vacancies/${id}/analysis`),
    optional(`/api/v1/vacancies/${id}/resume-variants/latest`),
    optional(`/api/v1/vacancies/${id}/application-drafts/latest`)
  ]);
  if (selection !== state.vacancySelection || state.vacancy?.id !== id) return;
  state.analysis = analysis; state.variant = variant; state.draft = draft;
  renderAnalysis(); renderVariant(); renderDraft();
  await loadBrowserWorkflow();
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
      resetBrowserWorkflow();
      fillVacancyForm(null);
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
  if (analysisInFlight?.vacancyId === vacancyId) return analysisInFlight.promise;
  const promise = runVacancyAnalysis(vacancyId);
  analysisInFlight = { vacancyId, promise };
  try { return await promise; }
  finally { if (analysisInFlight?.promise === promise) analysisInFlight = null; }
}

async function runVacancyAnalysis(vacancyId) {
  const button = $("#reanalyze") || $("#vacancy-form button[type=submit]");
  busy(button, true, "Анализируем…");
  try {
    const analysis = await request(`/api/v1/vacancies/${vacancyId}/analyze`, { method: "POST" });
    if (state.vacancy?.id !== vacancyId) return;
    state.analysis = analysis;
    state.variant = null; state.draft = null; state.resumeSelection = null;
    resetBrowserWorkflow();
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
    state.draft = null; resetBrowserWorkflow(); renderVariant(); renderDraft(); toast("Новая версия резюме готова");
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
    resetBrowserWorkflow(); renderDraft(); await loadBrowserWorkflow(); toast("Черновик отклика создан");
  } catch (error) {
    if (error.code === "APPLICATION_DRAFT_ALREADY_OPEN") {
      const draft = await request(`/api/v1/vacancies/${vacancyId}/application-drafts/latest`);
      if (state.vacancy?.id !== vacancyId) return;
      state.draft = draft;
      await loadBrowserWorkflow();
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
    state.browserSession = result.session;
    renderDraft(); await loadBrowserWorkflow();
    toast(result.outcome === "PAUSED" ? browserStopCopy(result.session).title : "Доступные поля заполнены");
  } catch (error) {
    const message = error.code === "BROWSER_RUNNER_DISABLED" ? "Браузерное заполнение выключено. Запустите приложение с PLAYWRIGHT_ENABLED=true." : error.message;
    toast(message, true);
  } finally { busy(button, false); }
}

async function resumeBrowserRun() {
  const button = $("#resume-browser");
  busy(button, true, "Пересканируем…");
  try {
    const draftId = state.draft.draft.draftId;
    const result = await request(`/api/v1/application-drafts/${draftId}/browser-runs/resume`, { method: "POST" });
    state.draft = result.application.draft;
    state.browserSession = result.session;
    renderDraft(); await loadBrowserWorkflow();
    const message = result.outcome === "PAUSED" ? browserStopCopy(result.session).title : "Форма пересканирована, доступные поля заполнены";
    toast(message);
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
}

async function answerApproval(approvalId) {
  const input = document.querySelector(`[data-value-for="${approvalId}"]`);
  const saveToCatalog = document.querySelector(`[data-save-for="${approvalId}"]`)?.checked || false;
  const value = input.value.trim();
  if (!value) { toast("Введите или выберите ответ", true); return; }
  try {
    state.draft = await request(`/api/v1/application-drafts/${state.draft.draft.draftId}/approvals/${approvalId}/decision`, { method: "POST", body: { approved: true, value, saveToCatalog } });
    if (saveToCatalog) {
      state.catalog = await request("/api/v1/application-answers");
      renderAnswerCatalog();
    }
    renderDraft(); toast(saveToCatalog ? "Ответ сохранён и добавлен в каталог" : "Ответ сохранён");
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
    renderDraft(); await loadBrowserWorkflow(); toast("Отклик отправлен");
  } catch (error) { toast(error.message, true); }
}

function localDateTimeValue(date = new Date()) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function openManualSubmissionDialog() {
  if (state.draft?.draft?.status !== "READY_TO_SUBMIT") {
    toast("Сначала завершите ответы и проверьте отклик", true);
    return;
  }
  const form = $("#manual-submission-form");
  form.reset();
  form.elements.submittedAt.value = localDateTimeValue();
  $("#manual-submission-dialog").showModal();
}

async function recordManualSubmission(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const values = Object.fromEntries(new FormData(form));
  const submittedAt = new Date(values.submittedAt);
  if (Number.isNaN(submittedAt.getTime())) { toast("Укажите корректную дату отправки", true); return; }
  if (submittedAt > new Date(Date.now() + 5 * 60_000)) { toast("Дата отправки не может быть в будущем", true); return; }
  if (submittedAt < new Date(state.draft.draft.createdAt)) { toast("Дата отправки не может быть раньше создания черновика", true); return; }
  const button = form.querySelector("button[type=submit]");
  busy(button, true, "Сохраняем…");
  try {
    const draftId = state.draft.draft.draftId;
    const approval = await request(`/api/v1/application-drafts/${draftId}/submit-approval`, { method: "POST" });
    await request(`/api/v1/application-drafts/${draftId}/approvals/${approval.approvalId}/decision`, { method: "POST", body: { approved: true, note: "Confirmed as already submitted manually" } });
    state.draft = await request(`/api/v1/application-drafts/${draftId}/manual-submission`, {
      method: "POST",
      body: { submittedAt: submittedAt.toISOString(), reference: values.reference.trim(), note: values.note.trim() || null }
    });
    $("#manual-submission-dialog").close();
    renderDraft();
    toast("Ручная отправка сохранена в истории");
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
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
    resetBrowserWorkflow();
    await loadApplicationPreferences();
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
    await loadApplicationPreferences();
    state.preview = null; state.analysis = null; state.variant = null; state.draft = null; state.resumeSelection = null;
    resetBrowserWorkflow();
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

$("#resume-edit-form").addEventListener("submit", saveResumeElement);
$("#close-resume-edit").addEventListener("click", () => $("#resume-edit-dialog").close());
$("#cancel-resume-edit").addEventListener("click", () => $("#resume-edit-dialog").close());

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
    const vacancy = await request("/api/v1/vacancies", { method: "POST", body: { source: "MANUAL", ...values, employmentType: values.employmentType || null, externalId: null } });
    state.vacancies.unshift(vacancy); state.vacancy = vacancy; state.analysis = null; state.variant = null; state.draft = null; state.resumeSelection = null;
    resetBrowserWorkflow();
    renderVacancies(); fillVacancyForm(vacancy); await analyzeSelectedVacancy();
  } catch (error) { toast(error.message, true); }
  finally { busy(button, false); }
});

$("#clear-vacancy-form").addEventListener("click", () => {
  fillVacancyForm(null);
  $("#vacancy-form").elements.company.focus();
});

$("#create-variant").addEventListener("click", createVariant);
$("#prepare-application").addEventListener("click", prepareApplication);
$("#start-browser").addEventListener("click", startBrowserRun);
$("#record-manual-submission").addEventListener("click", openManualSubmissionDialog);
$("#manual-submission-form").addEventListener("submit", recordManualSubmission);
$("#close-manual-submission").addEventListener("click", () => $("#manual-submission-dialog").close());
$("#cancel-manual-submission").addEventListener("click", () => $("#manual-submission-dialog").close());
$("#refresh-browser-state").addEventListener("click", async () => {
  try { await loadBrowserWorkflow(); toast("Состояние управляемого браузера обновлено"); }
  catch (error) { toast(error.message, true); }
});
$("#application-settings-form").addEventListener("submit", saveApplicationSettings);
$("#answer-catalog-form").addEventListener("submit", saveCatalogEntry);
$("#identity-select").addEventListener("change", (event) => switchIdentity(event.target.value));
$("#new-identity").addEventListener("click", createIdentity);
$("#edit-profile").addEventListener("click", () => { renderProfileEditor(); $("#profile-editor").classList.remove("hidden"); });
$("#close-profile-editor").addEventListener("click", () => $("#profile-editor").classList.add("hidden"));
$("#add-profile-skill").addEventListener("click", addProfileSkills);
$("#profile-skill-input").addEventListener("keydown", (event) => {
  if (event.key === "Enter") { event.preventDefault(); addProfileSkills(); }
});
$("#selection-defaults").addEventListener("click", () => {
  document.querySelectorAll("[data-selection-kind]").forEach((input) => input.checked = input.dataset.default === "true");
  clearResumePhoto();
});
$("#resume-photo").addEventListener("change", selectResumePhoto);
$("#remove-resume-photo").addEventListener("click", clearResumePhoto);
$("#profile-details-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget; const button = form.querySelector("button[type=submit]"); busy(button, true, "Сохраняем…");
  try {
    const values = Object.fromEntries(new FormData(form));
    const profile = await request("/api/v1/candidate-profile/details", { method: "PUT", body: { ...values, skills: state.profileSkillsDraft } });
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
