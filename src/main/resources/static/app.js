(() => {
  const POST_METHOD = "POST";
  const VIEW_OVERVIEW = "overview";
  const VIEW_TASKS = "tasks";
  const AUTH_CLASSES = Object.freeze({ guest: "auth-guest", ready: "auth-ready" });
  const DOM_ID = Object.freeze({
    activeAccount: "activeAccount",
    activeRole: "activeRole",
    app: "app",
    assistantDrawer: "assistantDrawer",
    assistantInput: "assistantInput",
    backToProjects: "backToProjects",
    createProjectForm: "createProjectForm",
    loginStage: "loginStage",
    openAssistant: "openAssistant",
    password: "password",
    projectObjective: "projectObjective",
    projectPicker: "projectPicker",
    projectTitle: "projectTitle",
    promptModalConfirm: "promptModalConfirm",
    refreshWorkspace: "refreshWorkspace",
    workspaceRoot: "workspaceRoot"
  });
  const TASK_STATUS = Object.freeze({
    PENDING: "PENDING",
    RUNNING: "RUNNING",
    WAITING_FOR_CONFIRMATION: "WAITING_FOR_CONFIRMATION",
    SUCCEEDED: "SUCCEEDED",
    FAILED: "FAILED",
    CANCELLED: "CANCELLED"
  });

  const ENDPOINTS = Object.freeze({
    status: "/api/agent/status",
    profile: "/api/profile",
    projects: "/api/projects",
    workspace: (projectId) => `/api/projects/${projectId}/workspace`,
    sources: (projectId) => `/api/projects/${projectId}/sources`,
    tasks: (projectId) => `/api/projects/${projectId}/tasks`,
    task: (projectId, publicId) => `/api/projects/${projectId}/tasks/${publicId}`,
    taskRetry: (projectId, publicId) => `/api/projects/${projectId}/tasks/${publicId}/retry`,
    decisions: (projectId) => `/api/projects/${projectId}/decisions`,
    decisionFromTask: (projectId, taskId) => `/api/projects/${projectId}/decisions/from-task/${taskId}`,
    confirmDecision: (projectId, decisionId) => `/api/projects/${projectId}/decisions/${decisionId}/confirm`,
    discardDecision: (projectId, decisionId) => `/api/projects/${projectId}/decisions/${decisionId}/discard`,
    confirmReview: (projectId, decisionId, reviewTaskId) =>
      `/api/projects/${projectId}/decisions/${decisionId}/reviews/${reviewTaskId}`,
    experiments: (projectId) => `/api/projects/${projectId}/experiments`,
    completeExperiment: (projectId, experimentId) => `/api/projects/${projectId}/experiments/${experimentId}/complete`,
    assistantStream: "/api/research/assistant/stream",
    assistantDecision: "/api/research/assistant/decision-tasks"
  });

  const TASK_TYPE = Object.freeze({
    DECISION: "DECISION",
    RESULT_REVIEW: "RESULT_REVIEW",
    SOURCE_INGESTION: "SOURCE_INGESTION"
  });

  const MESSAGES = Object.freeze({
    loading: "加载中…",
    emptyProjects: "还没有项目。先创建一个研究项目。",
    emptyDecisions: "暂无决策记录。",
    emptyExperiments: "暂无实验记录。",
    emptySources: "暂无资料。上传 PDF / Markdown / TXT 建立证据库。",
    emptyTasks: "暂无任务轨迹。",
    disconnected: "连接断开，请刷新后重试。",
    error: "请求失败，请稍后重试。",
    researcher: "研究者",
    noProject: "未选择项目"
  });

  const LEGACY_STORAGE_KEY = "evidencelab.session.v1";

  const state = {
    auth: null,
    username: null,
    roleLabel: null,
    projects: [],
    projectId: null,
    workspace: null,
    view: VIEW_OVERVIEW,
    loading: false,
    assistantSessionId: null
  };

  const el = (id) => document.getElementById(id);

  function clearLegacySession() {
    try { localStorage.removeItem(LEGACY_STORAGE_KEY); } catch (_) { /* ignore */ }
    try { sessionStorage.removeItem(LEGACY_STORAGE_KEY); } catch (_) { /* ignore */ }
  }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (state.auth) headers.set("Authorization", `Basic ${state.auth}`);
    if (options.body && !(options.body instanceof FormData)) {
      headers.set("Content-Type", "application/json");
    }
    const response = await fetch(path, { ...options, headers });
    if (!response.ok) {
      let message = MESSAGES.error;
      try {
        const payload = await response.json();
        message = payload.message || message;
      } catch (_) { /* ignore */ }
      if (response.status === 401 || response.status === 403) {
        message = `登录已失效(${response.status})`;
      }
      throw new Error(message);
    }
    if (response.status === 204) return null;
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  }

  function showBanner(message, isError = false) {
    const banner = el("workspaceBanner");
    if (!banner) return;
    if (!message) {
      banner.hidden = true;
      banner.textContent = "";
      banner.className = "banner";
      return;
    }
    banner.hidden = false;
    banner.textContent = message;
    banner.className = `banner ${isError ? "error" : "info"}`;
  }

  function setStatusText(serviceText, modelText) {
    const service = el("serviceState");
    const model = el("modelState");
    const serviceLive = el("serviceStateLive");
    const modelLive = el("modelStateLive");
    if (service) service.textContent = serviceText;
    if (model) model.textContent = modelText;
    if (serviceLive) serviceLive.textContent = serviceText;
    if (modelLive) modelLive.textContent = modelText;
  }

  async function refreshStatus() {
    try {
      const status = await fetch(ENDPOINTS.status).then((r) => r.json());
      setStatusText(status.product || "EvidenceLab", `模型：${status.model || "unknown"}`);
    } catch (_) {
      setStatusText(MESSAGES.disconnected, "模型：不可用");
    }
  }

  function setLoggedIn(username, password, roleLabel) {
    state.username = username;
    state.roleLabel = roleLabel || MESSAGES.researcher;
    state.auth = btoa(`${username}:${password}`);
    document.body.classList.remove(AUTH_CLASSES.guest);
    document.body.classList.add(AUTH_CLASSES.ready);
    el(DOM_ID.loginStage).hidden = true;
    el(DOM_ID.app).hidden = false;
    el(DOM_ID.activeAccount).textContent = username;
    el(DOM_ID.activeRole).textContent = state.roleLabel;
    el(DOM_ID.openAssistant).hidden = false;
    el(DOM_ID.refreshWorkspace).hidden = false;
    el(DOM_ID.backToProjects).hidden = !state.projectId;
  }

  function showProjectPicker() {
    state.projectId = null;
    state.workspace = null;
    el(DOM_ID.workspaceRoot).hidden = true;
    el(DOM_ID.projectPicker).hidden = false;
    el(DOM_ID.backToProjects).hidden = true;
    el(DOM_ID.assistantDrawer).hidden = true;
    el(DOM_ID.projectTitle).textContent = MESSAGES.noProject;
    el(DOM_ID.projectObjective).textContent = "选择已有项目，或新建一个研究项目。";
    renderProjects();
  }

  function logout() {
    state.auth = null;
    state.username = null;
    state.roleLabel = null;
    state.projectId = null;
    state.workspace = null;
    state.assistantSessionId = null;
    state.view = VIEW_OVERVIEW;
    clearLegacySession();
    document.body.classList.add(AUTH_CLASSES.guest);
    document.body.classList.remove(AUTH_CLASSES.ready);
    el(DOM_ID.loginStage).hidden = false;
    el(DOM_ID.app).hidden = true;
    el(DOM_ID.workspaceRoot).hidden = true;
    el(DOM_ID.projectPicker).hidden = false;
    el(DOM_ID.openAssistant).hidden = true;
    el(DOM_ID.refreshWorkspace).hidden = true;
    el(DOM_ID.backToProjects).hidden = true;
    el(DOM_ID.assistantDrawer).hidden = true;
    el(DOM_ID.projectTitle).textContent = MESSAGES.noProject;
    el(DOM_ID.projectObjective).textContent = "登录后创建或选择研究项目。";
    el(DOM_ID.activeAccount).textContent = "已登录";
    el(DOM_ID.activeRole).textContent = "";
  }

  async function loadProjects() {
    state.projects = await api(ENDPOINTS.projects);
    renderProjects();
  }

  function renderProjects() {
    const root = el("projectList");
    root.innerHTML = "";
    if (!state.projects.length) {
      root.innerHTML = `<p class="muted">${MESSAGES.emptyProjects}</p>`;
      return;
    }
    state.projects.forEach((project) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "card-item";
      btn.innerHTML = `<h4>${escapeHtml(project.name)}</h4><p class="muted">${escapeHtml(project.objective || "")}</p>`;
      btn.onclick = () => selectProject(project.id);
      root.appendChild(btn);
    });
  }

  async function selectProject(projectId) {
    const nextId = Number(projectId);
    if (state.projectId !== nextId) {
      state.assistantSessionId = null;
    }
    state.projectId = nextId;
    el(DOM_ID.projectPicker).hidden = true;
    el(DOM_ID.workspaceRoot).hidden = false;
    el(DOM_ID.backToProjects).hidden = false;
    await refreshWorkspace();
  }

  async function refreshWorkspace() {
    if (!state.projectId) return;
    showBanner(MESSAGES.loading);
    try {
      state.workspace = await api(ENDPOINTS.workspace(state.projectId));
      const project = state.workspace.project;
      el(DOM_ID.projectTitle).textContent = project.name;
      el(DOM_ID.projectObjective).textContent = project.objective || "";
      renderWorkspace();
      showBanner("");
    } catch (error) {
      showBanner(error.message || MESSAGES.error, true);
    }
  }

  function renderWorkspace() {
    renderActiveDecision();
    renderDecisions();
    renderExperiments();
    renderSources();
    renderTasks();
  }

  function renderActiveDecision() {
    const card = el("activeDecisionCard");
    const active = state.workspace?.activeDecision;
    const gaps = el("evidenceGaps");
    const next = el("nextExperiment");
    if (!active) {
      card.className = "decision-hero empty";
      card.innerHTML = `<p class="label">Active Decision</p><h3>暂无已确认决策</h3><p class="muted">发起正式决策任务后，确认的推荐会出现在这里。</p>`;
      next.textContent = "确认决策后显示。";
      gaps.innerHTML = `<li class="muted">无</li>`;
      return;
    }
    card.className = "decision-hero";
    card.innerHTML = `
      <p class="label">Active Decision · v${active.version}</p>
      <h3>${escapeHtml(active.recommendation)}</h3>
      <p>${escapeHtml(active.question || "")}</p>
      <div class="decision-meta">
        <span class="chip ok">${escapeHtml(active.status)}</span>
        <span class="chip">置信度 ${(active.confidence * 100).toFixed(0)}%</span>
        <span class="chip">支持 ${active.supportingEvidenceCount}</span>
        <span class="chip warn">反对 ${active.opposingEvidenceCount}</span>
        ${active.experimentStatus ? `<span class="chip">${escapeHtml(active.experimentStatus)}</span>` : ""}
      </div>
      <p class="muted" style="margin-top:12px">${escapeHtml(active.successCriteria || "")}</p>
    `;
    next.textContent = active.minimumExperiment || "未指定";
    gaps.innerHTML = (active.evidenceGaps || []).length
      ? active.evidenceGaps.map((gap) => `<li>${escapeHtml(gap)}</li>`).join("")
      : `<li class="muted">无显式缺口</li>`;
  }

  function renderDecisions() {
    const root = el("decisionList");
    const decisions = state.workspace?.decisions || [];
    if (!decisions.length) {
      root.innerHTML = `<p class="muted">${MESSAGES.emptyDecisions}</p>`;
      return;
    }
    root.innerHTML = decisions.map((decision) => `
      <article class="list-row">
        <h4>v${decision.version} · ${escapeHtml(decision.recommendation)}</h4>
        <p class="muted">${escapeHtml(decision.status)} · 置信度 ${(decision.confidence * 100).toFixed(0)}%</p>
        <p>${escapeHtml(decision.question || "")}</p>
        ${decision.status === "DRAFT" ? `
          <div class="row row-actions">
            <button type="button" class="btn btn-ghost" data-discard="${decision.id}">放弃草稿</button>
            <button type="button" class="btn btn-primary" data-confirm="${decision.id}">确认决策</button>
          </div>
        ` : ""}
      </article>
    `).join("");
    root.querySelectorAll("[data-confirm]").forEach((button) => {
      button.onclick = async () => {
        const decisionId = button.dataset.confirm;
        button.disabled = true;
        try {
          const ok = await askConfirm({
            kicker: "Decision",
            title: "确认这条决策？",
            hint: "确认后决策正文不可改，后续只能通过再生版本迭代。",
            confirmText: "确认决策",
            cancelText: "再想想"
          });
          if (!ok) return;
          await api(ENDPOINTS.confirmDecision(state.projectId, decisionId), {
            method: POST_METHOD,
            body: JSON.stringify({})
          });
          showBanner("决策已确认。");
          await refreshWorkspace();
        } catch (error) {
          showBanner(error.message || MESSAGES.error, true);
        } finally {
          button.disabled = false;
        }
      };
    });
    root.querySelectorAll("[data-discard]").forEach((button) => {
      button.onclick = async () => {
        const decisionId = button.dataset.discard;
        button.disabled = true;
        try {
          const ok = await askConfirm({
            kicker: "Decision",
            title: "放弃这份草稿？",
            hint: "草稿会标记为已放弃，不会进入已确认决策；可重新发起决策任务。",
            confirmText: "放弃草稿",
            cancelText: "保留"
          });
          if (!ok) return;
          await api(ENDPOINTS.discardDecision(state.projectId, decisionId), {
            method: POST_METHOD
          });
          showBanner("草稿已放弃。");
          await refreshWorkspace();
        } catch (error) {
          showBanner(error.message || MESSAGES.error, true);
        } finally {
          button.disabled = false;
        }
      };
    });
  }

  function renderExperiments() {
    const root = el("experimentList");
    const experiments = state.workspace?.experiments || [];
    if (!experiments.length) {
      root.innerHTML = `<p class="muted">${MESSAGES.emptyExperiments}</p>`;
      return;
    }
    root.innerHTML = experiments.map((experiment) => `
      <article class="list-row">
        <h4>${escapeHtml(experiment.title)}</h4>
        <p class="muted">${escapeHtml(experiment.status)} · decision #${experiment.decisionId}</p>
        <p>${escapeHtml(experiment.resultSummary || experiment.hypothesis || "")}</p>
        ${experiment.status !== "COMPLETED" ? `<button type="button" class="btn btn-ghost" data-complete="${experiment.id}">提交结果</button>` : ""}
      </article>
    `).join("");
    root.querySelectorAll("[data-complete]").forEach((button) => {
      button.onclick = async () => {
        const resultSummary = await askPrompt({
          kicker: "Experiment",
          title: "提交实验结果",
          hint: "用一两句话概括观测结果，便于后续复核决策。",
          label: "结果摘要",
          placeholder: "例如：QLoRA 在 12GB 下可稳定跑通，峰值显存约 10.2GB…",
          confirmText: "提交结果"
        });
        if (!resultSummary) return;
        try {
          await api(ENDPOINTS.completeExperiment(state.projectId, button.dataset.complete), {
            method: POST_METHOD,
            body: JSON.stringify({ resultSummary, metricsJson: "{}" })
          });
          showBanner("实验结果已提交，已自动创建结果复核任务，请到 Agent 轨迹查看。");
          switchView(VIEW_TASKS);
          await refreshWorkspace();
        } catch (error) {
          showBanner(error.message || MESSAGES.error, true);
        }
      };
    });
  }

  function renderSources() {
    const root = el("sourceList");
    const sources = state.workspace?.sources || [];
    if (!sources.length) {
      root.innerHTML = `<p class="muted">${MESSAGES.emptySources}</p>`;
      return;
    }
    root.innerHTML = sources.map((source) => `
      <article class="list-row">
        <h4>${escapeHtml(source.filename || "source")}</h4>
        <p class="muted">${escapeHtml(source.status || "")}</p>
      </article>
    `).join("");
  }

  function renderTasks() {
    const root = el("taskList");
    const tasks = state.workspace?.recentTasks || [];
    if (!tasks.length) {
      root.innerHTML = `<p class="muted">${MESSAGES.emptyTasks}</p>`;
      return;
    }
    root.innerHTML = tasks.map((task) => {
      let action = "";
      if (task.status === TASK_STATUS.WAITING_FOR_CONFIRMATION && task.type === TASK_TYPE.DECISION) {
        action = `<button type="button" class="btn btn-primary" data-draft="${task.id}">生成决策草稿</button>`;
      } else if (task.status === TASK_STATUS.WAITING_FOR_CONFIRMATION && task.type === TASK_TYPE.RESULT_REVIEW) {
        const decisionId = task.decisionId || state.workspace?.activeDecision?.id;
        if (decisionId) {
          action = `<button type="button" class="btn btn-primary" data-review-decision="${decisionId}" data-review-task="${task.id}">确认复核结论</button>`;
        }
      } else if (task.status === TASK_STATUS.FAILED) {
        action = `<button type="button" class="btn btn-ghost" data-retry="${task.publicId}">重试</button>`;
      }
      return `
      <article class="list-row">
        <h4>${escapeHtml(task.type)} · ${escapeHtml(task.status)}</h4>
        <p class="muted">${escapeHtml(task.publicId)} · ${task.progressPercent || 0}%</p>
        <p>${escapeHtml(task.question || task.errorMessage || "")}</p>
        ${action}
      </article>`;
    }).join("");
    root.querySelectorAll("[data-draft]").forEach((button) => {
      button.onclick = async () => {
        try {
          await api(ENDPOINTS.decisionFromTask(state.projectId, button.dataset.draft), { method: POST_METHOD });
          showBanner("已生成决策草稿，请到决策账本确认。");
          await refreshWorkspace();
        } catch (error) {
          showBanner(error.message || MESSAGES.error, true);
        }
      };
    });
    root.querySelectorAll("[data-review-task]").forEach((button) => {
      button.onclick = async () => {
        try {
          await api(ENDPOINTS.confirmReview(
            state.projectId,
            button.dataset.reviewDecision,
            button.dataset.reviewTask
          ), { method: POST_METHOD });
          showBanner("复核已确认，决策进入 REVIEWED。");
          await refreshWorkspace();
        } catch (error) {
          showBanner(error.message || MESSAGES.error, true);
        }
      };
    });
    root.querySelectorAll("[data-retry]").forEach((button) => {
      button.onclick = async () => {
        try {
          await api(ENDPOINTS.taskRetry(state.projectId, button.dataset.retry), { method: POST_METHOD });
          await refreshWorkspace();
        } catch (error) {
          showBanner(error.message || MESSAGES.error, true);
        }
      };
    });
  }

  function switchView(view) {
    state.view = view;
    document.querySelectorAll(".nav-item").forEach((item) => {
      item.classList.toggle("active", item.dataset.view === view);
    });
    [VIEW_OVERVIEW, "decisions", "experiments", "sources", VIEW_TASKS].forEach((name) => {
      el(`view-${name}`).hidden = name !== view;
    });
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  function askPrompt({
    title,
    kicker = "Input",
    hint = "",
    label = "内容",
    defaultValue = "",
    placeholder = "",
    confirmText = "确定",
    cancelText = "取消",
    multiline = true,
    requireInput = true
  } = {}) {
    const modal = el("promptModal");
    const form = el("promptModalForm");
    const input = el("promptModalInput");
    const field = el("promptModalField");
    const hintEl = el("promptModalHint");
    const cancelBtn = el("promptModalCancel");

    el("promptModalKicker").textContent = kicker;
    el("promptModalTitle").textContent = title;
    el("promptModalLabel").textContent = label;
    el(DOM_ID.promptModalConfirm).textContent = confirmText;
    cancelBtn.textContent = cancelText;
    input.value = defaultValue;
    input.placeholder = placeholder;
    input.rows = multiline ? 4 : 2;
    input.required = Boolean(requireInput);
    if (requireInput) {
      input.setAttribute("required", "required");
      field.hidden = false;
      field.removeAttribute("hidden");
    } else {
      input.removeAttribute("required");
      field.hidden = true;
      field.setAttribute("hidden", "");
    }

    if (hint) {
      hintEl.hidden = false;
      hintEl.textContent = hint;
    } else {
      hintEl.hidden = true;
      hintEl.textContent = "";
    }

    modal.hidden = false;
    requestAnimationFrame(() => {
      if (requireInput) {
        input.focus();
        input.select();
      } else {
        el(DOM_ID.promptModalConfirm).focus();
      }
    });

    return new Promise((resolve) => {
      const cleanup = () => {
        modal.hidden = true;
        form.removeEventListener("submit", onSubmit);
        modal.querySelectorAll("[data-modal-dismiss]").forEach((node) => {
          node.removeEventListener("click", onCancel);
        });
        document.removeEventListener("keydown", onKeydown);
      };

      const onCancel = () => {
        cleanup();
        resolve(null);
      };

      const onSubmit = (event) => {
        event.preventDefault();
        if (!requireInput) {
          cleanup();
          resolve(true);
          return;
        }
        const value = input.value.trim();
        if (!value) {
          input.focus();
          return;
        }
        cleanup();
        resolve(value);
      };

      const onKeydown = (event) => {
        if (event.key === "Escape") {
          event.preventDefault();
          onCancel();
        }
      };

      form.addEventListener("submit", onSubmit);
      modal.querySelectorAll("[data-modal-dismiss]").forEach((node) => {
        node.addEventListener("click", onCancel);
      });
      document.addEventListener("keydown", onKeydown);
    });
  }

  function askConfirm({
    title,
    kicker = "Confirm",
    hint = "",
    confirmText = "确定",
    cancelText = "取消"
  } = {}) {
    return askPrompt({
      title,
      kicker,
      hint,
      confirmText,
      cancelText,
      requireInput: false
    }).then((value) => value === true);
  }

  async function streamAssistant(message) {
    const log = el("assistantMessages");
    const userBubble = document.createElement("div");
    userBubble.className = "bubble user";
    userBubble.textContent = message;
    log.appendChild(userBubble);
    const botBubble = document.createElement("div");
    botBubble.className = "bubble";
    botBubble.textContent = "";
    log.appendChild(botBubble);

    const response = await fetch(ENDPOINTS.assistantStream, {
      method: POST_METHOD,
      headers: {
        Authorization: `Basic ${state.auth}`,
        "Content-Type": "application/json",
        Accept: "text/event-stream"
      },
      body: JSON.stringify({
        message,
        projectId: state.projectId,
        sessionId: state.assistantSessionId
      })
    });
    if (!response.ok || !response.body) {
      botBubble.textContent = MESSAGES.error;
      return;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const chunks = buffer.split("\n\n");
      buffer = chunks.pop() || "";
      chunks.forEach((chunk) => {
        const dataLine = chunk.split("\n").find((line) => line.startsWith("data:"));
        if (!dataLine) return;
        try {
          const payload = JSON.parse(dataLine.slice(5).trim());
          if (payload.type === "meta" && payload.sessionId) {
            state.assistantSessionId = payload.sessionId;
          }
          if (payload.type === "token" && payload.content) botBubble.textContent += payload.content;
          if (payload.type === "error" && payload.content) {
            botBubble.textContent = payload.content;
          }
        } catch (_) { /* ignore partial */ }
      });
    }
    if (!botBubble.textContent) botBubble.textContent = "（无内容）";
    log.scrollTop = log.scrollHeight;
  }

  el("loginForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const username = el("username").value.trim();
    const password = el(DOM_ID.password).value;
    state.auth = btoa(`${username}:${password}`);
    try {
      const profile = await api(ENDPOINTS.profile);
      await loadProjects();
      const isAdmin = (profile.roles || []).some((role) => role.authority === "ROLE_ADMIN");
      setLoggedIn(username, password, isAdmin ? "管理员" : MESSAGES.researcher);
      el(DOM_ID.password).value = "";
      showBanner("");
    } catch (error) {
      state.auth = null;
      el("loginState").textContent = error.message || "登录失败";
    }
  });

  el("switchAccount").onclick = logout;
  el(DOM_ID.refreshWorkspace).onclick = refreshWorkspace;
  el(DOM_ID.backToProjects).onclick = async () => {
    showProjectPicker();
    try {
      await loadProjects();
    } catch (error) {
      showBanner(error.message || MESSAGES.error, true);
    }
  };
  el(DOM_ID.openAssistant).onclick = () => { el(DOM_ID.assistantDrawer).hidden = false; };
  el("closeAssistant").onclick = () => { el(DOM_ID.assistantDrawer).hidden = true; };

  document.querySelectorAll(".nav-item").forEach((item) => {
    item.onclick = () => switchView(item.dataset.view);
  });

  el("createProjectBtn").onclick = () => {
    el(DOM_ID.createProjectForm).hidden = false;
  };
  el("cancelCreateProject").onclick = () => {
    el(DOM_ID.createProjectForm).hidden = true;
  };
  el(DOM_ID.createProjectForm).addEventListener("submit", async (event) => {
    event.preventDefault();
    const project = await api(ENDPOINTS.projects, {
      method: POST_METHOD,
      body: JSON.stringify({
        name: el("projectName").value.trim(),
        objective: el("projectObjectiveInput").value.trim(),
        constraints: el("projectConstraints").value.trim() || null
      })
    });
    el(DOM_ID.createProjectForm).reset();
    el(DOM_ID.createProjectForm).hidden = true;
    await loadProjects();
    await selectProject(project.id);
  });

  el("startDecisionTask").onclick = async () => {
    const question = await askPrompt({
      kicker: "Decision",
      title: "发起决策任务",
      hint: "描述需要取舍的研究问题与关键约束，系统会走证据检索与审查链路。",
      label: "决策问题",
      defaultValue: "12GB 显存该选 LoRA 还是 QLoRA？",
      placeholder: "例如：在 12GB 显存约束下，该选 LoRA 还是 QLoRA？",
      confirmText: "发起任务"
    });
    if (!question) return;
    await api(ENDPOINTS.assistantDecision, {
      method: POST_METHOD,
      body: JSON.stringify({ projectId: state.projectId, question })
    });
    switchView(VIEW_TASKS);
    await refreshWorkspace();
  };

  el("createExperimentBtn").onclick = async () => {
    const active = state.workspace?.activeDecision;
    if (!active || active.status === "DRAFT") {
      showBanner("请先确认一个决策，再创建实验。", true);
      return;
    }
    const title = await askPrompt({
      kicker: "Experiment",
      title: "新建实验",
      hint: "为当前已确认决策登记一次最小验证实验。",
      label: "实验标题",
      defaultValue: "最小验证实验",
      placeholder: "例如：12GB 下 QLoRA 显存峰值验证",
      confirmText: "创建实验",
      multiline: false
    });
    if (!title) return;
    await api(ENDPOINTS.experiments(state.projectId), {
      method: POST_METHOD,
      body: JSON.stringify({
        decisionId: active.id,
        title,
        hypothesis: active.recommendation,
        setupNotes: active.minimumExperiment
      })
    });
    await refreshWorkspace();
  };

  el("sourceFile").onchange = async (event) => {
    const file = event.target.files?.[0];
    if (!file || !state.projectId) return;
    const form = new FormData();
    form.append("file", file);
    await api(ENDPOINTS.sources(state.projectId), { method: POST_METHOD, body: form });
    event.target.value = "";
    await refreshWorkspace();
  };

  el("assistantForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const message = el(DOM_ID.assistantInput).value.trim();
    if (!message) return;
    el(DOM_ID.assistantInput).value = "";
    try {
      await streamAssistant(message);
    } catch (error) {
      showBanner(error.message || MESSAGES.error, true);
    }
  });

  el("assistantDecisionBtn").onclick = async () => {
    const question = el(DOM_ID.assistantInput).value.trim();
    if (!question || !state.projectId) return;
    await api(ENDPOINTS.assistantDecision, {
      method: POST_METHOD,
      body: JSON.stringify({ projectId: state.projectId, question })
    });
    el(DOM_ID.assistantInput).value = "";
    el(DOM_ID.assistantDrawer).hidden = true;
    switchView(VIEW_TASKS);
    await refreshWorkspace();
  };

  el("retryFailedTask").onclick = async () => {
    const failed = (state.workspace?.recentTasks || []).find((task) => task.status === TASK_STATUS.FAILED);
    if (!failed) {
      showBanner("没有失败任务可重试。");
      return;
    }
    await api(ENDPOINTS.taskRetry(state.projectId, failed.publicId), { method: POST_METHOD });
    await refreshWorkspace();
  };

  clearLegacySession();
  refreshStatus();
})();
