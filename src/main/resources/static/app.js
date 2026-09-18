(() => {
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
    projects: "/api/projects",
    workspace: (projectId) => `/api/projects/${projectId}/workspace`,
    sources: (projectId) => `/api/projects/${projectId}/sources`,
    tasks: (projectId) => `/api/projects/${projectId}/tasks`,
    task: (projectId, publicId) => `/api/projects/${projectId}/tasks/${publicId}`,
    taskRetry: (projectId, publicId) => `/api/projects/${projectId}/tasks/${publicId}/retry`,
    decisions: (projectId) => `/api/projects/${projectId}/decisions`,
    decisionFromTask: (projectId, taskId) => `/api/projects/${projectId}/decisions/from-task/${taskId}`,
    confirmDecision: (projectId, decisionId) => `/api/projects/${projectId}/decisions/${decisionId}/confirm`,
    experiments: (projectId) => `/api/projects/${projectId}/experiments`,
    completeExperiment: (projectId, experimentId) => `/api/projects/${projectId}/experiments/${experimentId}/complete`,
    assistantStream: "/api/research/assistant/stream",
    assistantDecision: "/api/research/assistant/decision-tasks"
  });

  const MESSAGES = Object.freeze({
    loading: "加载中…",
    emptyProjects: "还没有项目。先创建一个研究项目。",
    emptyDecisions: "暂无决策记录。",
    emptyExperiments: "暂无实验记录。",
    emptySources: "暂无资料。上传 PDF / Markdown / TXT 建立证据库。",
    emptyTasks: "暂无任务轨迹。",
    disconnected: "连接断开，请刷新后重试。",
    error: "请求失败，请稍后重试。"
  });

  const state = {
    auth: null,
    projects: [],
    projectId: null,
    workspace: null,
    view: "overview",
    loading: false
  };

  const el = (id) => document.getElementById(id);

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
      throw new Error(message);
    }
    if (response.status === 204) return null;
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  }

  function showBanner(message, isError = false) {
    const banner = el("workspaceBanner");
    if (!message) {
      banner.hidden = true;
      banner.textContent = "";
      return;
    }
    banner.hidden = false;
    banner.textContent = message;
    banner.style.background = isError ? "#f8e4e4" : "#fff4e5";
    banner.style.color = isError ? "#8f2d2d" : "#8a4b12";
  }

  async function refreshStatus() {
    try {
      const status = await fetch(ENDPOINTS.status).then((r) => r.json());
      el("serviceState").textContent = status.product || "EvidenceLab";
      el("modelState").textContent = `模型：${status.model || "unknown"}`;
    } catch (_) {
      el("serviceState").textContent = MESSAGES.disconnected;
    }
  }

  function setLoggedIn(username, password, roleLabel) {
    state.auth = btoa(`${username}:${password}`);
    el("loginForm").hidden = true;
    el("accountPanel").hidden = false;
    el("projectNav").hidden = false;
    el("activeAccount").textContent = username;
    el("activeRole").textContent = roleLabel || "已登录";
    el("openAssistant").hidden = false;
    el("refreshWorkspace").hidden = false;
  }

  function logout() {
    state.auth = null;
    state.projectId = null;
    state.workspace = null;
    el("loginForm").hidden = false;
    el("accountPanel").hidden = true;
    el("projectNav").hidden = true;
    el("workspaceRoot").hidden = true;
    el("openAssistant").hidden = true;
    el("refreshWorkspace").hidden = true;
    el("assistantDrawer").hidden = true;
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
      btn.className = "card-item panel";
      btn.innerHTML = `<h4>${escapeHtml(project.name)}</h4><p class="muted">${escapeHtml(project.objective || "")}</p>`;
      btn.onclick = () => selectProject(project.id);
      root.appendChild(btn);
    });
  }

  async function selectProject(projectId) {
    state.projectId = projectId;
    el("projectPicker").hidden = true;
    el("workspaceRoot").hidden = false;
    await refreshWorkspace();
  }

  async function refreshWorkspace() {
    if (!state.projectId) return;
    showBanner(MESSAGES.loading);
    try {
      state.workspace = await api(ENDPOINTS.workspace(state.projectId));
      const project = state.workspace.project;
      el("projectTitle").textContent = project.name;
      el("projectObjective").textContent = project.objective || "";
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
      card.className = "decision-card empty";
      card.innerHTML = `<p class="eyebrow">ACTIVE DECISION</p><h3>暂无已确认决策</h3><p class="muted">发起正式决策任务后，确认的推荐会出现在这里。</p>`;
      next.textContent = "确认决策后显示。";
      gaps.innerHTML = `<li class="muted">无</li>`;
      return;
    }
    card.className = "decision-card";
    card.innerHTML = `
      <p class="eyebrow">ACTIVE DECISION · v${active.version}</p>
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
      <article class="panel card-item">
        <h4>v${decision.version} · ${escapeHtml(decision.recommendation)}</h4>
        <p class="muted">${escapeHtml(decision.status)} · 置信度 ${(decision.confidence * 100).toFixed(0)}%</p>
        <p>${escapeHtml(decision.question || "")}</p>
        ${decision.status === "DRAFT" ? `<button type="button" class="primary" data-confirm="${decision.id}">确认决策</button>` : ""}
      </article>
    `).join("");
    root.querySelectorAll("[data-confirm]").forEach((button) => {
      button.onclick = async () => {
        await api(ENDPOINTS.confirmDecision(state.projectId, button.dataset.confirm), {
          method: "POST",
          body: JSON.stringify({})
        });
        await refreshWorkspace();
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
      <article class="panel card-item">
        <h4>${escapeHtml(experiment.title)}</h4>
        <p class="muted">${escapeHtml(experiment.status)} · decision #${experiment.decisionId}</p>
        <p>${escapeHtml(experiment.resultSummary || experiment.hypothesis || "")}</p>
        ${experiment.status !== "COMPLETED" ? `<button type="button" class="ghost" data-complete="${experiment.id}">提交结果</button>` : ""}
      </article>
    `).join("");
    root.querySelectorAll("[data-complete]").forEach((button) => {
      button.onclick = async () => {
        const resultSummary = prompt("实验结果摘要");
        if (!resultSummary) return;
        await api(ENDPOINTS.completeExperiment(state.projectId, button.dataset.complete), {
          method: "POST",
          body: JSON.stringify({ resultSummary, metricsJson: "{}" })
        });
        await refreshWorkspace();
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
      <article class="panel card-item">
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
    root.innerHTML = tasks.map((task) => `
      <article class="panel card-item">
        <h4>${escapeHtml(task.type)} · ${escapeHtml(task.status)}</h4>
        <p class="muted">${escapeHtml(task.publicId)} · ${task.progressPercent || 0}%</p>
        <p>${escapeHtml(task.question || task.errorMessage || "")}</p>
        ${task.status === TASK_STATUS.WAITING_FOR_CONFIRMATION ? `<button type="button" class="primary" data-draft="${task.id}">生成决策草稿</button>` : ""}
        ${task.status === TASK_STATUS.FAILED ? `<button type="button" class="ghost" data-retry="${task.publicId}">重试</button>` : ""}
      </article>
    `).join("");
    root.querySelectorAll("[data-draft]").forEach((button) => {
      button.onclick = async () => {
        await api(ENDPOINTS.decisionFromTask(state.projectId, button.dataset.draft), { method: "POST" });
        await refreshWorkspace();
      };
    });
    root.querySelectorAll("[data-retry]").forEach((button) => {
      button.onclick = async () => {
        await api(ENDPOINTS.taskRetry(state.projectId, button.dataset.retry), { method: "POST" });
        await refreshWorkspace();
      };
    });
  }

  function switchView(view) {
    state.view = view;
    document.querySelectorAll(".nav-item").forEach((item) => {
      item.classList.toggle("active", item.dataset.view === view);
    });
    ["overview", "decisions", "experiments", "sources", "tasks"].forEach((name) => {
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
      method: "POST",
      headers: {
        Authorization: `Basic ${state.auth}`,
        "Content-Type": "application/json",
        Accept: "text/event-stream"
      },
      body: JSON.stringify({ message })
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
          if (payload.type === "token" && payload.content) botBubble.textContent += payload.content;
        } catch (_) { /* ignore partial */ }
      });
    }
    if (!botBubble.textContent) botBubble.textContent = "（无内容）";
    log.scrollTop = log.scrollHeight;
  }

  el("loginForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const username = el("username").value.trim();
    const password = el("password").value;
    state.auth = btoa(`${username}:${password}`);
    try {
      await loadProjects();
      setLoggedIn(username, password, username === "admin" ? "管理员" : "研究者");
      showBanner("");
    } catch (error) {
      state.auth = null;
      el("loginState").textContent = error.message || "登录失败";
    }
  });

  el("switchAccount").onclick = logout;
  el("refreshWorkspace").onclick = refreshWorkspace;
  el("openAssistant").onclick = () => { el("assistantDrawer").hidden = false; };
  el("closeAssistant").onclick = () => { el("assistantDrawer").hidden = true; };

  document.querySelectorAll(".nav-item").forEach((item) => {
    item.onclick = () => switchView(item.dataset.view);
  });

  el("createProjectBtn").onclick = () => {
    el("createProjectForm").hidden = false;
  };
  el("cancelCreateProject").onclick = () => {
    el("createProjectForm").hidden = true;
  };
  el("createProjectForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const project = await api(ENDPOINTS.projects, {
      method: "POST",
      body: JSON.stringify({
        name: el("projectName").value.trim(),
        objective: el("projectObjectiveInput").value.trim(),
        constraints: el("projectConstraints").value.trim() || null
      })
    });
    el("createProjectForm").reset();
    el("createProjectForm").hidden = true;
    await loadProjects();
    await selectProject(project.id);
  });

  el("startDecisionTask").onclick = async () => {
    const question = prompt("决策问题", "12GB 显存该选 LoRA 还是 QLoRA？");
    if (!question) return;
    await api(ENDPOINTS.assistantDecision, {
      method: "POST",
      body: JSON.stringify({ projectId: state.projectId, question })
    });
    switchView("tasks");
    await refreshWorkspace();
  };

  el("createExperimentBtn").onclick = async () => {
    const active = state.workspace?.activeDecision;
    if (!active || active.status === "DRAFT") {
      showBanner("请先确认一个决策，再创建实验。", true);
      return;
    }
    const title = prompt("实验标题", "最小验证实验");
    if (!title) return;
    await api(ENDPOINTS.experiments(state.projectId), {
      method: "POST",
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
    await api(ENDPOINTS.sources(state.projectId), { method: "POST", body: form });
    event.target.value = "";
    await refreshWorkspace();
  };

  el("assistantForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const message = el("assistantInput").value.trim();
    if (!message) return;
    el("assistantInput").value = "";
    try {
      await streamAssistant(message);
    } catch (error) {
      showBanner(error.message || MESSAGES.error, true);
    }
  });

  el("assistantDecisionBtn").onclick = async () => {
    const question = el("assistantInput").value.trim();
    if (!question || !state.projectId) return;
    await api(ENDPOINTS.assistantDecision, {
      method: "POST",
      body: JSON.stringify({ projectId: state.projectId, question })
    });
    el("assistantInput").value = "";
    el("assistantDrawer").hidden = true;
    switchView("tasks");
    await refreshWorkspace();
  };

  el("retryFailedTask").onclick = async () => {
    const failed = (state.workspace?.recentTasks || []).find((task) => task.status === TASK_STATUS.FAILED);
    if (!failed) {
      showBanner("没有失败任务可重试。");
      return;
    }
    await api(ENDPOINTS.taskRetry(state.projectId, failed.publicId), { method: "POST" });
    await refreshWorkspace();
  };

  refreshStatus();
})();
