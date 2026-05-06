async function fileToBase64(file) {
  const buffer = await file.arrayBuffer();
  let binary = "";
  const bytes = new Uint8Array(buffer);
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

let currentRunId = "";
let currentRun = null;
let currentDialogueSessionId = "";
let currentDialogueSession = null;
let modelSettings = { configured: false, provider: "", model: "", base_url: "", api_key_configured: false };
let runCreationPending = false;
let runPollTimer = null;
let chatSetupPrefilledForRunId = "";
let sidebarCollapsed = false;
let sessionBooting = false;
let recentSessionsRequestId = 0;

function el(id) {
  return document.getElementById(id);
}

function bind(id, eventName, handler) {
  const node = el(id);
  if (node) {
    node.addEventListener(eventName, handler);
  }
}

function setText(id, value, fallback = "-") {
  const node = el(id);
  if (!node) return;
  node.textContent = value || fallback;
}

function toggle(id, visible) {
  const node = el(id);
  if (!node) return;
  node.classList.toggle("hidden", !visible);
}

function uniq(values) {
  return [...new Set((values || []).map((item) => String(item || "").trim()).filter(Boolean))];
}

function joinCharacters(values) {
  return uniq(values).join("、");
}

function parseCharacters(value) {
  return uniq(String(value || "").split(/[\n,，、]+/));
}

function applySidebarState() {
  const shell = el("app-shell");
  if (shell) {
    shell.classList.toggle("sidebar-collapsed", sidebarCollapsed);
  }
  const button = el("toggle-sidebar-button");
  if (button) {
    button.textContent = "☰";
    button.setAttribute("aria-label", sidebarCollapsed ? "展开会话栏" : "收起会话栏");
    button.title = sidebarCollapsed ? "展开会话栏" : "收起会话栏";
  }
}

function stopRunPolling() {
  if (runPollTimer) {
    clearTimeout(runPollTimer);
    runPollTimer = null;
  }
}

function resizeComposer() {
  const area = el("dialogue-message");
  if (!area) return;
  area.style.height = "auto";
  area.style.height = `${Math.min(area.scrollHeight, 160)}px`;
}

function setComposerEnabled(enabled) {
  const area = el("dialogue-message");
  const sendButton = el("prepare-turn-button");
  if (area) area.disabled = !enabled;
  if (sendButton) sendButton.disabled = !enabled;
}

function maybePrefillChatSetup(run) {
  if (!run || !run.run_id) return;
  if (chatSetupPrefilledForRunId === run.run_id) return;
  const characters = run.artifact_index?.characters?.map((item) => item.name).filter(Boolean) || run.locked_characters || [];
  if (!characters.length) return;

  if (el("dialogue-participants")) {
    el("dialogue-participants").value = joinCharacters(characters);
  }
  if (el("dialogue-mode")) {
    el("dialogue-mode").value = "insert";
  }
  if (el("dialogue-controlled")) {
    el("dialogue-controlled").value = characters[0] || "";
  }
  if (el("dialogue-self-name") && !el("dialogue-self-name").value.trim()) {
    el("dialogue-self-name").value = "你";
  }
  if (el("dialogue-self-identity") && !el("dialogue-self-identity").value.trim()) {
    el("dialogue-self-identity").value = "误入此间的来客";
  }
  if (el("dialogue-self-style") && !el("dialogue-self-style").value.trim()) {
    el("dialogue-self-style").value = "自然进入场景";
  }

  chatSetupPrefilledForRunId = run.run_id;
  syncModeFields();
  updateCharacterPillState();
}

function getRunCharacterNames(run) {
  return run?.artifact_index?.characters?.map((item) => item.name).filter(Boolean) || run?.locked_characters || [];
}

function renderCharacterPills(run) {
  const root = el("dialogue-character-pills");
  if (!root) return;
  const characters = getRunCharacterNames(run);
  root.innerHTML = "";
  characters.forEach((name) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "pill";
    button.textContent = name;
    button.addEventListener("click", () => {
      const participants = parseCharacters(el("dialogue-participants")?.value || "");
      const exists = participants.includes(name);
      const nextParticipants = exists ? participants.filter((item) => item !== name) : [...participants, name];
      if (el("dialogue-participants")) {
        el("dialogue-participants").value = joinCharacters(nextParticipants);
      }
      if ((el("dialogue-mode")?.value || "observe") === "act" && el("dialogue-controlled")) {
        el("dialogue-controlled").value = name;
      }
      updateCharacterPillState();
    });
    root.appendChild(button);
  });
  updateCharacterPillState();
}

function updateCharacterPillState() {
  const selected = new Set(parseCharacters(el("dialogue-participants")?.value || ""));
  document.querySelectorAll("#dialogue-character-pills .pill").forEach((node) => {
    node.classList.toggle("active", selected.has(node.textContent || ""));
  });
}

function renderRedistillPills(run) {
  const root = el("redistill-character-pills");
  if (!root) return;
  const characters = getRunCharacterNames(run);
  root.innerHTML = "";
  characters.forEach((name) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "pill";
    button.textContent = name;
    button.addEventListener("click", () => {
      const selected = parseCharacters(el("redistill-characters")?.value || "");
      const exists = selected.includes(name);
      const next = exists ? selected.filter((item) => item !== name) : [...selected, name];
      if (el("redistill-characters")) {
        el("redistill-characters").value = joinCharacters(next);
      }
      updateRedistillPillState();
    });
    root.appendChild(button);
  });
  root.classList.toggle("hidden", characters.length === 0);
  updateRedistillPillState();
}

function updateRedistillPillState() {
  const selected = new Set(parseCharacters(el("redistill-characters")?.value || ""));
  document.querySelectorAll("#redistill-character-pills .pill").forEach((node) => {
    node.classList.toggle("active", selected.has(node.textContent || ""));
  });
}

function createActionLink(entry) {
  const link = document.createElement("a");
  link.href = entry.url || "#";
  link.textContent = entry.label || "打开";
  link.target = "_blank";
  link.rel = "noreferrer";
  return link;
}

function renderLinks(rootId, entries) {
  const root = el(rootId);
  if (!root) return;
  root.innerHTML = "";
  entries.forEach((entry) => root.appendChild(createActionLink(entry)));
  root.classList.toggle("hidden", entries.length === 0);
}

function humanizeSummary(summary) {
  const mapping = {
    waiting_for_payloads: "等待处理",
    waiting_for_host_generation: "处理中",
    graph_pending: "关系图谱生成中",
    graph_ready: "图谱已生成",
    waiting_for_verification: "收尾中",
    workflow_complete: "已完成",
    failed: "失败",
  };
  return mapping[summary] || summary || "未开始";
}

function humanizeSessionStatus(status) {
  const mapping = {
    ready: "可继续",
    waiting_for_host_reply: "生成中",
  };
  return mapping[status] || status || "未知";
}

function formatWeakTime(value) {
  const text = String(value || "").trim();
  if (!text) return "";
  const date = new Date(text);
  if (Number.isNaN(date.getTime())) return "";
  const now = new Date();
  if (now.toDateString() === date.toDateString()) {
    return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
  }
  return date.toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" });
}

function openSettingsModal() {
  toggle("settings-modal", true);
}

function closeSettingsModal() {
  toggle("settings-modal", false);
}

function syncTopbar() {
  if (!modelSettings.configured) {
    setText("topbar-title", "先连接模型");
    setText("topbar-subtitle", "还没有可用模型");
    return;
  }
  if (runCreationPending) {
    setText("topbar-title", "正在蒸馏");
    setText("topbar-subtitle", "系统正在自动处理小说和人物");
    return;
  }
  if (currentRun) {
    setText("topbar-title", currentRun.novel_id || "当前小说");
    setText("topbar-subtitle", humanizeSummary(currentRun.summary?.status_text));
    return;
  }
  setText("topbar-title", "模型已连接");
  setText("topbar-subtitle", `${modelSettings.provider || "provider"} · ${modelSettings.model || "model"}`);
}

function applyModelSettingsView() {
  if (el("model-provider")) el("model-provider").value = modelSettings.provider || "openai-compatible";
  if (el("model-name")) el("model-name").value = modelSettings.model || "";
  if (el("model-base-url")) el("model-base-url").value = modelSettings.base_url || "";
  if (el("model-api-key")) el("model-api-key").value = "";
  setText(
    "sidebar-status",
    modelSettings.configured
      ? `${modelSettings.provider || "provider"} · ${modelSettings.model || "model"}`
      : "未配置模型"
  );
  setText("model-provider-view", modelSettings.provider);
  setText("model-name-view", modelSettings.model);
  setText("model-base-url-view", modelSettings.base_url || "默认");
  toggle("model-summary", modelSettings.configured);
  syncTopbar();
}

function updateWorkflowState() {
  const configured = Boolean(modelSettings.configured);
  const hasRun = Boolean(currentRunId && currentRun);
  const hasCharacters = Boolean(currentRun?.artifact_index?.characters?.length);
  const hasSession = Boolean(currentDialogueSessionId) || sessionBooting;
  const failed = currentRun?.status === "failed";

  toggle("step-model", !configured || !hasRun);
  toggle("step-distill", configured && !runCreationPending && !hasRun);
  toggle("step-progress", configured && (runCreationPending || (hasRun && (!hasCharacters || failed))));
  toggle("redistill-panel", configured && hasRun && !runCreationPending);
  toggle("step-chat-setup", configured && hasCharacters && !hasSession);
  toggle("turn-stage", configured && hasSession && !sessionBooting);
  toggle("dialogue-empty", !hasSession);
  toggle("dialogue-detail", hasSession);

  if (!configured) {
    setText("next-step-title", "先配置模型");
    setText("next-step-body", "填入模型地址、模型名称和密钥，保存后才能继续。", "");
  } else if (!hasRun && !runCreationPending) {
    setText("next-step-title", "选择小说和人物");
    setText("next-step-body", "上传小说，填入人物名称，然后点一次开始蒸馏。", "");
  } else if (runCreationPending || (hasRun && !hasCharacters)) {
    setText("next-step-title", failed ? "处理失败" : "正在蒸馏人物");
    setText(
      "next-step-body",
      failed ? currentRun?.progress?.message || "请检查模型配置后重试。" : "系统正在自动拆分小说、按人物顺序蒸馏，并生成关系图谱。",
      ""
    );
  } else if (sessionBooting) {
    setText("next-step-title", "正在铺开场景");
    setText("next-step-body", "正在生成旁白和第一轮对白，很快就能开始聊天。", "");
  } else if (hasCharacters && !hasSession) {
    setText("next-step-title", "开始聊天");
    setText("next-step-body", "选好模式和目标角色，就可以进入聊天。", "");
  } else {
    setText("next-step-title", "继续聊天");
    setText("next-step-body", "直接在下方输入你想说的话。", "");
  }

  syncTopbar();
}

function openNewDialogueSession() {
  if (!currentRunId || !currentRun || !currentRun?.artifact_index?.characters?.length) return;
  resetDialogueView();
  maybePrefillChatSetup(currentRun);
  updateWorkflowState();
  el("dialogue-participants")?.focus();
}

async function loadModelSettings() {
  const response = await fetch("/api/web/settings/model");
  modelSettings = await response.json();
  applyModelSettingsView();
  updateWorkflowState();
  if (!modelSettings.configured) {
    openSettingsModal();
  }
}

function renderEvents(run) {
  const root = el("events");
  if (!root) return;
  root.innerHTML = "";
  (run.events || []).slice(-8).forEach((event) => {
    const item = document.createElement("li");
    item.textContent = event.message || event.stage || "";
    root.appendChild(item);
  });
}

function renderGraphLinks(run) {
  const entries = [];
  if (run.file_urls?.graph_html) entries.push({ url: run.file_urls.graph_html, label: "查看关系图谱" });
  if (run.file_urls?.graph_svg) entries.push({ url: run.file_urls.graph_svg, label: "查看 SVG" });
  renderLinks("graph-links", entries);
}

function resetDialogueView() {
  currentDialogueSessionId = "";
  currentDialogueSession = null;
  sessionBooting = false;
  setText("dialogue-session-id", "-");
  if (el("dialogue-transcript")) el("dialogue-transcript").innerHTML = "";
  if (el("dialogue-message")) el("dialogue-message").value = "";
  resizeComposer();
  setComposerEnabled(false);
}

function renderRun(run) {
  currentRunId = run.run_id || "";
  currentRun = run;
  runCreationPending = run.status === "running" && run.summary?.status_text !== "workflow_complete";
  if (el("redistill-characters")) {
    el("redistill-characters").value = joinCharacters(getRunCharacterNames(run));
  }
  if (el("redistill-status")) {
    el("redistill-status").textContent = run.redistill?.summary || "";
  }
  setText("run-novel", run.novel_id);
  setText("run-characters", joinCharacters(run.locked_characters || []));
  setText("run-summary", humanizeSummary(run.summary?.status_text));
  setText("progress-copy", run.progress?.message || "系统正在自动蒸馏人物并生成关系图谱。", "");
  renderEvents(run);
  renderGraphLinks(run);
  renderCharacterPills(run);
  renderRedistillPills(run);
  resetDialogueView();
  if (run.artifact_index?.characters?.length) {
    maybePrefillChatSetup(run);
  }
  loadRecentSessions().catch((error) => console.warn("loadRecentSessions failed", error));
  updateWorkflowState();
  if (run.status === "running") {
    scheduleRunPolling();
  } else {
    stopRunPolling();
  }
}

function scheduleRunPolling() {
  stopRunPolling();
  if (!currentRunId) return;
  runPollTimer = window.setTimeout(async () => {
    try {
      const response = await fetch(`/api/web/runs/${currentRunId}`);
      const data = await response.json();
      if (response.ok) {
        renderRun(data);
      }
    } catch (error) {
      console.warn("poll run failed", error);
    }
  }, 1800);
}

function scrollTranscriptToBottom() {
  const root = el("dialogue-transcript");
  if (!root) return;
  requestAnimationFrame(() => {
    root.scrollTop = root.scrollHeight;
  });
}

function createMessageBubble(role, message) {
  const bubble = document.createElement("div");
  bubble.className = `message-bubble ${role}`;
  const body = document.createElement("p");
  body.textContent = message || "";
  bubble.appendChild(body);
  return bubble;
}

function buildSessionMetaMessage({ mode = "", participants = [], controlledCharacter = "", selfInsert = {} }) {
  const lines = [];
  if (mode) lines.push(`模式：${mode}`);
  if ((participants || []).length) lines.push(`目标：${joinCharacters(participants)}`);
  if (controlledCharacter) lines.push(`我代入：${controlledCharacter}`);
  if (selfInsert?.display_name) lines.push(`我的名字：${selfInsert.display_name}`);
  if (selfInsert?.scene_identity) lines.push(`我的身份：${selfInsert.scene_identity}`);
  if (!lines.length) return null;
  return { role: "scene", message: lines.join("\n\n") };
}

function renderDialogueTranscript(session) {
  const card = session?.session_card || {};
  const metaMessage = buildSessionMetaMessage({
    mode: card.mode_display || session?.mode || "",
    participants: card.participants || [],
    controlledCharacter: card.controlled_character || "",
    selfInsert: card.self_insert || {},
  });
  const items = metaMessage ? [metaMessage, ...(session?.transcript || [])] : session?.transcript || [];
  renderTranscript(items);
}

function renderTranscript(items) {
  const root = el("dialogue-transcript");
  if (!root) return;
  root.innerHTML = "";

  (items || []).forEach((item) => {
    const role = item.role || "character";
    const row = document.createElement("article");
    row.className = `transcript-item ${role}`;

    if (role === "scene" || role === "director" || role === "loading") {
      row.appendChild(createMessageBubble(role, item.message || ""));
      root.appendChild(row);
      return;
    }

    const inline = document.createElement("div");
    inline.className = `message-inline ${role}`;

    const name = document.createElement("span");
    name.className = "speaker-name";
    name.textContent = item.speaker || (role === "user" ? "你" : "角色");

    const bubble = createMessageBubble(role, item.message || "");
    if (role === "user") {
      inline.appendChild(bubble);
      inline.appendChild(name);
    } else {
      inline.appendChild(name);
      inline.appendChild(bubble);
    }

    row.appendChild(inline);
    root.appendChild(row);
  });

  scrollTranscriptToBottom();
}

function renderSessionBooting(mode, participants) {
  const items = [];
  const meta = buildSessionMetaMessage({ mode, participants });
  if (meta) items.push(meta);
  items.push({ role: "loading", message: "正在生成开场场景和第一轮对白..." });
  setText("dialogue-session-id", "启动中");
  renderTranscript(items);
}

function buildOptimisticTranscript(session, message) {
  const transcript = Array.isArray(session?.transcript) ? [...session.transcript] : [];
  const mode = session?.mode || session?.session_card?.mode || "observe";
  const selfInsert = session?.session_card?.self_insert || {};
  const speaker =
    mode === "act"
      ? session?.session_card?.controlled_character || "你"
      : mode === "insert"
        ? selfInsert.display_name || "你"
        : "你";
  const role = mode === "observe" ? "director" : "user";
  transcript.push({ speaker, message, role });
  transcript.push({ speaker: "", message: "正在生成回复...", role: "loading" });
  return transcript;
}

async function renderDialogueSession(session) {
  currentDialogueSessionId = session.session_id || "";
  currentDialogueSession = session;
  sessionBooting = false;
  setComposerEnabled(true);
  setText("dialogue-session-id", session.session_id);
  renderDialogueTranscript(session);
  await loadRecentSessions();
  updateWorkflowState();
  el("dialogue-message")?.focus();
}

function refreshSidebarSessionSelection() {
  document.querySelectorAll("#sidebar-session-list .session-item").forEach((node) => {
    const runId = node.getAttribute("data-run-id") || "";
    const sessionId = node.getAttribute("data-session-id") || "";
    node.classList.toggle("active", runId === currentRunId && sessionId === currentDialogueSessionId);
  });
}

async function loadRecentSessions() {
  const root = el("sidebar-session-list");
  if (!root) return;
  const requestId = ++recentSessionsRequestId;
  const response = await fetch("/api/web/sessions");
  const data = await response.json();
  if (requestId !== recentSessionsRequestId) return;

  const deduped = [];
  const seen = new Set();
  for (const item of data.items || []) {
    const key = `${item.run_id || ""}::${item.session_id || ""}`;
    if (seen.has(key)) continue;
    seen.add(key);
    deduped.push(item);
  }

  root.innerHTML = "";
  if (!deduped.length) {
    root.innerHTML = '<p class="sidebar-text">还没有会话</p>';
    return;
  }

  const grouped = new Map();
  deduped.slice(0, 24).forEach((item) => {
    const novelId = item.novel_id || "未命名小说";
    if (!grouped.has(novelId)) grouped.set(novelId, []);
    grouped.get(novelId).push(item);
  });

  const fragment = document.createDocumentFragment();
  grouped.forEach((sessions, novelId) => {
    const section = document.createElement("section");
    section.className = "session-group";

    const title = document.createElement("div");
    title.className = "session-group-title";
    title.textContent = novelId;
    section.appendChild(title);

    sessions.forEach((item) => {
      const row = document.createElement("div");
      row.className = "session-row";

      const button = document.createElement("button");
      button.className = "session-item";
      button.type = "button";
      button.setAttribute("data-run-id", item.run_id || "");
      button.setAttribute("data-session-id", item.session_id || "");
      button.innerHTML = `
        <strong>${joinCharacters(item.participants || []) || "未命名会话"}</strong>
        <em>${item.mode_display || item.mode || "-"}</em>
        <small>${humanizeSessionStatus(item.status)}${formatWeakTime(item.updated_at) ? ` · ${formatWeakTime(item.updated_at)}` : ""}</small>
      `;
      button.addEventListener("click", async () => {
        const runResponse = await fetch(`/api/web/runs/${item.run_id}`);
        renderRun(await runResponse.json());
        const sessionResponse = await fetch(`/api/web/runs/${item.run_id}/dialogue/sessions/${item.session_id}`);
        await renderDialogueSession(await sessionResponse.json());
      });

      const removeButton = document.createElement("button");
      removeButton.type = "button";
      removeButton.className = "session-delete-button";
      removeButton.textContent = "×";
      removeButton.title = "删除会话";
      removeButton.setAttribute("aria-label", "删除会话");
      removeButton.addEventListener("click", async (event) => {
        event.stopPropagation();
        if (!window.confirm("确定删除这个会话吗？")) return;
        try {
          const deleteResponse = await fetch(`/api/web/runs/${item.run_id}/dialogue/sessions/${item.session_id}`, {
            method: "DELETE",
          });
          const payload = await deleteResponse.json().catch(() => ({}));
          if (!deleteResponse.ok) throw new Error(payload.detail || "删除失败。");
          if (currentRunId === item.run_id && currentDialogueSessionId === item.session_id) {
            resetDialogueView();
            refreshSidebarSessionSelection();
            updateWorkflowState();
          }
          await loadRecentSessions();
        } catch (error) {
          window.alert(error.message || "删除失败。");
        }
      });

      row.appendChild(button);
      row.appendChild(removeButton);
      section.appendChild(row);
    });

    fragment.appendChild(section);
  });

  if (requestId !== recentSessionsRequestId) return;
  root.replaceChildren(fragment);
  refreshSidebarSessionSelection();
}

function syncModeFields() {
  const mode = el("dialogue-mode")?.value || "observe";
  if (el("dialogue-controlled")) el("dialogue-controlled").disabled = mode !== "act";
  if (el("dialogue-self-name")) el("dialogue-self-name").disabled = mode !== "insert";
  if (el("dialogue-self-identity")) el("dialogue-self-identity").disabled = mode !== "insert";
  if (el("dialogue-self-style")) el("dialogue-self-style").disabled = mode !== "insert";
  toggle("controlled-field", mode === "act");
  toggle("self-name-field", mode === "insert");
  toggle("self-identity-field", mode === "insert");
  toggle("self-style-field", mode === "insert");
}

bind("open-settings-button", "click", openSettingsModal);
bind("open-settings-primary", "click", openSettingsModal);
bind("close-settings-button", "click", closeSettingsModal);
bind("toggle-sidebar-button", "click", () => {
  sidebarCollapsed = !sidebarCollapsed;
  applySidebarState();
});
bind("new-dialogue-session-button", "click", openNewDialogueSession);

bind("model-settings-form", "submit", async (event) => {
  event.preventDefault();
  const status = el("model-settings-status");
  if (status) status.textContent = "正在保存模型配置...";
  try {
    const response = await fetch("/api/web/settings/model", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        provider: el("model-provider")?.value || "",
        model: el("model-name")?.value?.trim() || "",
        base_url: el("model-base-url")?.value?.trim() || "",
        api_key: el("model-api-key")?.value?.trim() || "",
      }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "保存失败。");
    modelSettings = data;
    applyModelSettingsView();
    if (status) status.textContent = "模型配置已保存。";
    closeSettingsModal();
    updateWorkflowState();
  } catch (error) {
    if (status) status.textContent = error.message || "保存失败。";
  }
});

bind("create-run-form", "submit", async (event) => {
  event.preventDefault();
  const status = el("form-status");
  if (!modelSettings.configured) {
    openSettingsModal();
    if (status) status.textContent = "请先完成模型配置。";
    return;
  }
  const file = el("novel-file")?.files?.[0];
  if (!file) {
    if (status) status.textContent = "请先选择小说文件。";
    return;
  }
  const characters = parseCharacters(el("characters")?.value || "");
  if (!characters.length) {
    if (status) status.textContent = "请至少填写一个人物名称。";
    return;
  }
  runCreationPending = true;
  updateWorkflowState();
  if (status) status.textContent = "正在开始蒸馏，请稍等...";
  try {
    const response = await fetch("/api/web/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        novel_name: file.name,
        novel_content_base64: await fileToBase64(file),
        characters,
        max_sentences: Number(el("max-sentences")?.value || 120),
        max_chars: Number(el("max-chars")?.value || 50000),
        auto_run: true,
      }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "蒸馏失败。");
    renderRun(data);
    if (status) status.textContent = "蒸馏已开始，正在持续更新进度。";
  } catch (error) {
    runCreationPending = false;
    stopRunPolling();
    updateWorkflowState();
    if (status) status.textContent = error.message || "蒸馏失败。";
  }
});

bind("redistill-button", "click", async () => {
  const status = el("redistill-status");
  if (!currentRunId) {
    if (status) status.textContent = "请先完成一次蒸馏。";
    return;
  }
  const characters = parseCharacters(el("redistill-characters")?.value || "");
  if (!characters.length) {
    if (status) status.textContent = "请填写想继续蒸馏的人物。";
    return;
  }
  runCreationPending = true;
  updateWorkflowState();
  if (status) status.textContent = "正在继续蒸馏...";
  try {
    const response = await fetch(`/api/web/runs/${currentRunId}/redistill`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        characters,
        max_sentences: Number(el("max-sentences")?.value || 120),
        max_chars: Number(el("max-chars")?.value || 50000),
      }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "继续蒸馏失败。");
    renderRun(data);
    if (status) status.textContent = "已开始继续蒸馏，正在更新进度。";
  } catch (error) {
    runCreationPending = false;
    stopRunPolling();
    updateWorkflowState();
    if (status) status.textContent = error.message || "继续蒸馏失败。";
  }
});

bind("redistill-add-button", "click", () => {
  if (el("redistill-characters")) {
    el("redistill-characters").value = "";
  }
  if (el("redistill-status")) {
    el("redistill-status").textContent = "填入新人物后即可继续蒸馏。";
  }
  updateRedistillPillState();
});

bind("redistill-refresh-button", "click", () => {
  if (el("redistill-characters")) {
    el("redistill-characters").value = joinCharacters(getRunCharacterNames(currentRun));
  }
  if (el("redistill-status")) {
    el("redistill-status").textContent = "已填入当前人物，可直接重新蒸馏。";
  }
  updateRedistillPillState();
});

bind("dialogue-session-form", "submit", async (event) => {
  event.preventDefault();
  const status = el("dialogue-session-status");
  if (!currentRunId) {
    if (status) status.textContent = "请先完成蒸馏。";
    return;
  }
  try {
    const mode = el("dialogue-mode")?.value || "observe";
    const participants = parseCharacters(el("dialogue-participants")?.value || "");
    sessionBooting = true;
    setComposerEnabled(false);
    renderSessionBooting(mode, participants);
    updateWorkflowState();
    if (status) status.textContent = "正在进入聊天并生成开场...";
    const response = await fetch(`/api/web/runs/${currentRunId}/dialogue/sessions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        mode,
        participants,
        controlled_character: el("dialogue-controlled")?.value?.trim() || "",
        self_profile: {
          display_name: el("dialogue-self-name")?.value?.trim() || "",
          scene_identity: el("dialogue-self-identity")?.value?.trim() || "",
          interaction_style: el("dialogue-self-style")?.value?.trim() || "",
        },
      }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "进入聊天失败。");
    await renderDialogueSession(data);
    if (status) status.textContent = "开场已生成，可以继续聊天了。";
  } catch (error) {
    sessionBooting = false;
    setComposerEnabled(Boolean(currentDialogueSessionId));
    updateWorkflowState();
    if (status) status.textContent = error.message || "进入聊天失败。";
  }
});

bind("prepare-turn-button", "click", async () => {
  const status = el("dialogue-turn-status");
  if (!currentRunId || !currentDialogueSessionId) {
    if (status) status.textContent = "请先进入聊天。";
    return;
  }
  const message = el("dialogue-message")?.value?.trim() || "";
  if (!message) {
    if (status) status.textContent = "请输入你想说的话。";
    return;
  }

  const sessionSnapshot = currentDialogueSession ? JSON.parse(JSON.stringify(currentDialogueSession)) : null;
  if (el("dialogue-message")) el("dialogue-message").value = "";
  resizeComposer();
  setComposerEnabled(false);
  if (status) status.textContent = "正在生成回复...";

  if (currentDialogueSession) {
    currentDialogueSession = {
      ...currentDialogueSession,
      transcript: buildOptimisticTranscript(currentDialogueSession, message),
    };
    renderDialogueTranscript(currentDialogueSession);
  }

  try {
    const response = await fetch(`/api/web/runs/${currentRunId}/dialogue/sessions/${currentDialogueSessionId}/reply`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "发送失败。");
    await renderDialogueSession(data);
    if (status) status.textContent = "";
  } catch (error) {
    if (sessionSnapshot) {
      currentDialogueSession = sessionSnapshot;
      renderDialogueTranscript(sessionSnapshot);
    }
    setComposerEnabled(true);
    if (status) status.textContent = error.message || "发送失败。";
  }
});

bind("dialogue-mode", "change", syncModeFields);
bind("dialogue-mode", "change", updateCharacterPillState);
bind("dialogue-participants", "input", updateCharacterPillState);
bind("redistill-characters", "input", updateRedistillPillState);
bind("dialogue-message", "input", resizeComposer);
bind("dialogue-message", "keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    el("prepare-turn-button")?.click();
  }
});

document.addEventListener("click", (event) => {
  const target = event.target;
  if (!(target instanceof HTMLElement)) return;
  if (target.dataset.closeModal === "true") {
    closeSettingsModal();
  }
});

syncModeFields();
resizeComposer();
applySidebarState();
loadModelSettings();
loadRecentSessions();
