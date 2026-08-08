(() => {
const existingDialogueModule = window.__ZAOMENG_DIALOGUE_MODULE__;
if (existingDialogueModule?.initialized) {
  return;
}
const UI_BRIDGE_TOOLS = window.__ZAOMENG_UI_BRIDGE_TOOLS__ || {};
const DIALOGUE_STATE_TOOLS = window.__ZAOMENG_DIALOGUE_STATE_TOOLS__ || {};
const trimInlineMessage = typeof DIALOGUE_STATE_TOOLS.trimInlineMessage === "function"
  ? DIALOGUE_STATE_TOOLS.trimInlineMessage
  : (value) => {
      const text = String(value || "").replace(/\s+/g, " ").trim();
      if (!text) return "";
      return text.length > 88 ? `${text.slice(0, 88)}...` : text;
    };
let lastAutoSceneRecommendationKey = "";
let sessionSelectionMode = false;
const selectedSessionKeys = new Set();
const dialogueEventTimelineFilters = {
  participant: "",
  location: "",
  eventType: "",
};
let selectedDialogueRelationPair = "";
let selectedDialogueCharacterArc = "";
let editingDialogueMemoryId = "";
let editingDialogueMemoryEnabled = true;
const dialogueDirectorState = {
  action: "advance",
  options: [],
  sessionId: "",
  loading: false,
};

function scrollTranscriptToBottom() {
  const root = el("dialogue-transcript");
  if (!root) return;
  const apply = () => {
    root.scrollTop = root.scrollHeight;
    const last = root.lastElementChild;
    if (last instanceof HTMLElement) {
      last.scrollIntoView({ block: "end" });
    }
  };
  requestAnimationFrame(() => {
    apply();
    requestAnimationFrame(apply);
  });
  window.setTimeout(apply, 0);
  window.setTimeout(apply, 60);
  window.setTimeout(apply, 180);
}

function applySessionListViewportLock() {
  const root = el("sidebar-session-list");
  if (!root) return;
  const rect = root.getBoundingClientRect();
  const bottomGap = 28;
  const available = Math.max(180, Math.floor(window.innerHeight - rect.top - bottomGap));
  root.style.overflowY = "auto";
  root.style.overflowX = "hidden";
  root.style.maxHeight = `${available}px`;
  root.style.height = "auto";
}

function dialogueTranscriptMentionNames() {
  const participants = Array.isArray(currentDialogueSession?.participants)
    ? currentDialogueSession.participants
    : Array.isArray(currentDialogueSession?.session_card?.participants)
      ? currentDialogueSession.session_card.participants
      : [];
  return [...new Set(participants.map((name) => String(name || "").trim()).filter(Boolean))]
    .sort((left, right) => right.length - left.length);
}

function appendMessageTextWithMentions(target, value) {
  const text = String(value || "");
  const candidates = dialogueTranscriptMentionNames();
  let scanFrom = 0;
  let emittedThrough = 0;
  while (scanFrom < text.length) {
    const marker = text.indexOf("@", scanFrom);
    if (marker < 0) break;
    const name = candidates.find((candidate) => {
      if (!text.startsWith(`@${candidate}`, marker)) return false;
      const following = text[marker + candidate.length + 1] || "";
      return !following || /[\s,，。！？；：、（）().!?;:]/u.test(following);
    });
    if (!name) {
      scanFrom = marker + 1;
      continue;
    }
    const prefix = text.slice(emittedThrough, marker);
    if (prefix) target.appendChild(document.createTextNode(prefix));
    const mention = document.createElement("span");
    mention.className = "message-mention";
    mention.textContent = `@${name}`;
    target.appendChild(mention);
    emittedThrough = marker + name.length + 1;
    scanFrom = emittedThrough;
  }
  const remainder = text.slice(emittedThrough);
  if (remainder) target.appendChild(document.createTextNode(remainder));
}

function appendStyledMessageContent(target, message) {
  const text = String(message || "");
  const pattern = /([（(][^（）()\n]*[）)])/g;
  let lastIndex = 0;
  for (const match of text.matchAll(pattern)) {
    const start = match.index ?? 0;
    if (start > lastIndex) {
      appendMessageTextWithMentions(target, text.slice(lastIndex, start));
    }
    const aside = document.createElement("span");
    aside.className = "message-aside";
    appendMessageTextWithMentions(aside, match[0] || "");
    target.appendChild(aside);
    lastIndex = start + String(match[0] || "").length;
  }
  if (lastIndex < text.length) {
    appendMessageTextWithMentions(target, text.slice(lastIndex));
  }
}

function createMessageBubble(role, message) {
  const bubble = document.createElement("div");
  bubble.className = `message-bubble ${role}`;
  const body = document.createElement("p");
  appendStyledMessageContent(body, message);
  bubble.appendChild(body);
  return bubble;
}

function buildSessionMetaMessage({ mode = "", participants = [], controlledCharacter = "", selfInsert = {}, sceneCard = {} }) {
  const lines = [];
  if (mode) lines.push(`今夜入场：${humanizeMode(mode)}`);
  if ((participants || []).length) lines.push(`与你同席：${joinCharacters(participants)}`);
  if (controlledCharacter) lines.push(`此刻你是：${controlledCharacter}`);
  if (selfInsert?.display_name) lines.push(`他们会称呼你：${selfInsert.display_name}`);
  if (selfInsert?.scene_identity) lines.push(`旁人眼中的你：${selfInsert.scene_identity}`);
  if (sceneCard?.title || sceneCard?.location || sceneCard?.atmosphere) {
    const sceneBits = [sceneCard?.title, sceneCard?.location, sceneCard?.atmosphere].filter(Boolean);
    lines.push(`当前挂载场景：${sceneBits.join(" / ")}`);
  }
  if (!lines.length) return null;
  return { role: "scene", message: lines.join("\n\n") };
}

function renderDialogueTranscript(session) {
  const card = session?.session_card || {};
  const metaMessage = buildSessionMetaMessage({
    mode: card.mode_display || session?.mode || "",
    participants: card.participants || [],
    controlledCharacter: card.controlled_character || "",
    sceneCard: card.scene_card || {},
    selfInsert: card.self_insert || {},
  });
  const items = metaMessage ? [metaMessage, ...(session?.transcript || [])] : session?.transcript || [];
  renderDialogueConsistencyMonitor(session?.consistency_monitor || null);
  renderTranscript(items);
}

function renderDialogueConsistencyMonitor(monitor) {
  const button = el("dialogue-consistency-button");
  const mount = el("dialogue-consistency-modal-mount");
  const latest = monitor?.latest || {};
  const checkedTurns = Number(monitor?.checked_turns || 0) || 0;
  const available = Boolean(checkedTurns && latest?.status);
  if (button) {
    button.classList.toggle("hidden", !available);
  }
  if (!available) {
    if (mount) mount.innerHTML = "";
    closeDialogueConsistencyModal();
    return;
  }

  const issues = Array.isArray(latest.issues) ? latest.issues : [];
  const score = Math.max(0, Math.min(100, Number(latest.score || 0)));
  if (button) {
    button.classList.toggle("warning", issues.length > 0);
    button.textContent = issues.length ? `一致性提醒 ${issues.length}` : `✓ 一致性 ${score}`;
    button.setAttribute(
      "aria-label",
      issues.length ? `人物一致性发现 ${issues.length} 个提醒，点击查看` : `人物一致性得分 ${score}，点击查看`
    );
  }
  if (!mount) return;
  mount.innerHTML = "";
  appendConsistencyMonitor(mount, monitor);
  bindTranscriptSendActions(mount);
}

function appendConsistencyMonitor(root, monitor) {
  const latest = monitor?.latest || {};
  const checkedTurns = Number(monitor?.checked_turns || 0) || 0;
  if (!checkedTurns || !latest?.status) return;

  const issues = Array.isArray(latest.issues) ? latest.issues : [];
  const card = document.createElement("aside");
  card.className = `consistency-monitor ${issues.length ? "warning" : "pass"}`;

  const header = document.createElement("div");
  header.className = "consistency-monitor-header";
  const title = document.createElement("strong");
  title.textContent = issues.length ? `人物一致性提醒 · ${issues.length}` : "人物一致性检查通过";
  const score = document.createElement("span");
  score.textContent = `${Math.max(0, Math.min(100, Number(latest.score || 0)))} / 100`;
  header.appendChild(title);
  header.appendChild(score);
  card.appendChild(header);

  const summary = document.createElement("p");
  summary.textContent = String(latest.summary || "").trim();
  card.appendChild(summary);

  appendConsistencyQualityPanel(card, monitor);

  if (issues.length) {
    const list = document.createElement("div");
    list.className = "consistency-monitor-issues";
    issues.slice(0, 4).forEach((issue) => {
      const item = document.createElement("div");
      item.className = `consistency-monitor-issue ${String(issue?.severity || "warning").trim()}`;
      const issueTitle = document.createElement("strong");
      const speaker = String(issue?.speaker || "").trim();
      const category = consistencyIssueCategory(issue?.code);
      issueTitle.textContent = `${category} · ${speaker ? `${speaker} · ` : ""}${String(issue?.title || "潜在异常").trim()}`;
      const detail = document.createElement("p");
      detail.textContent = String(issue?.detail || "").trim();
      item.appendChild(issueTitle);
      item.appendChild(detail);
      list.appendChild(item);
    });
    card.appendChild(list);
    const actions = document.createElement("div");
    actions.className = "consistency-monitor-actions";
    const correctButton = document.createElement("button");
    correctButton.type = "button";
    correctButton.className = "consistency-correct-button";
    correctButton.dataset.consistencyCorrect = "true";
    correctButton.textContent = "生成修正版";
    actions.appendChild(correctButton);
    card.appendChild(actions);
  }
  let actions = card.querySelector(".consistency-monitor-actions");
  if (!(actions instanceof HTMLElement)) {
    actions = document.createElement("div");
    actions.className = "consistency-monitor-actions";
    card.appendChild(actions);
  }
  const reviewButton = document.createElement("button");
  reviewButton.type = "button";
  reviewButton.className = "consistency-review-button";
  reviewButton.dataset.consistencyReview = "true";
  reviewButton.textContent = latest?.coverage?.semantic_review ? "重新深度复核" : "深度复核";
  actions.appendChild(reviewButton);
  root.appendChild(card);
}

function appendConsistencyQualityPanel(card, monitor) {
  const metrics = monitor?.metrics || {};
  const history = Array.isArray(monitor?.history) ? monitor.history : [];
  const checkedTurns = Number(metrics.checked_turns ?? monitor?.checked_turns ?? history.length) || 0;
  if (!checkedTurns) return;

  const strip = document.createElement("div");
  strip.className = "consistency-quality-strip";
  [
    ["平均分", `${Number(metrics.average_score ?? 0) || 0}`],
    ["通过率", `${Number(metrics.pass_rate ?? 0) || 0}%`],
    ["连续通过", `${Number(metrics.current_pass_streak ?? 0) || 0} 轮`],
  ].forEach(([label, value]) => {
    const item = document.createElement("div");
    const strong = document.createElement("strong");
    strong.textContent = value;
    const span = document.createElement("span");
    span.textContent = label;
    item.appendChild(strong);
    item.appendChild(span);
    strip.appendChild(item);
  });
  card.appendChild(strip);

  if (history.length <= 1) return;
  const details = document.createElement("details");
  details.className = "consistency-quality-details";
  const summary = document.createElement("summary");
  summary.textContent = `查看本会话 ${checkedTurns} 轮质量记录`;
  details.appendChild(summary);

  const categoryCounts = metrics.category_counts || {};
  const categories = Object.entries(categoryCounts).filter(([, count]) => Number(count || 0) > 0);
  if (categories.length) {
    const categoryRoot = document.createElement("div");
    categoryRoot.className = "consistency-category-counts";
    categories.forEach(([category, count]) => {
      const chip = document.createElement("span");
      chip.textContent = `${consistencyMetricCategoryLabel(category)} ${Number(count || 0)}`;
      categoryRoot.appendChild(chip);
    });
    details.appendChild(categoryRoot);
  }

  const trend = Array.isArray(metrics.score_trend) ? metrics.score_trend : [];
  if (trend.length) {
    const trendRoot = document.createElement("div");
    trendRoot.className = "consistency-score-trend";
    trend.slice(-8).forEach((entry, index) => {
      const row = document.createElement("div");
      row.className = "consistency-score-row";
      const label = document.createElement("span");
      label.textContent = `近 ${trend.length - index} 轮`;
      const track = document.createElement("div");
      const bar = document.createElement("i");
      const score = Math.max(0, Math.min(100, Number(entry?.score || 0)));
      bar.style.width = `${score}%`;
      if (String(entry?.status || "").trim() !== "pass") bar.classList.add("warning");
      track.appendChild(bar);
      const value = document.createElement("strong");
      value.textContent = String(score);
      row.appendChild(label);
      row.appendChild(track);
      row.appendChild(value);
      trendRoot.appendChild(row);
    });
    details.appendChild(trendRoot);
  }
  card.appendChild(details);
}

function consistencyMetricCategoryLabel(category) {
  const labels = {
    role_boundary: "角色边界",
    scene_continuity: "场景连续",
    persona_taboo: "人设禁忌",
    knowledge_boundary: "知识边界",
    relationship_attitude: "关系态度",
    other: "其他",
  };
  return labels[String(category || "").trim()] || "其他";
}

function consistencyIssueCategory(code) {
  const normalized = String(code || "").trim();
  if (["knowledge_boundary_violation", "semantic_knowledge_drift"].includes(normalized)) return "知识边界";
  if (["forbidden_behavior_overlap", "semantic_voice_drift", "semantic_motivation_drift"].includes(normalized)) return "人设语义";
  if (normalized === "semantic_relationship_drift") return "关系态度";
  if (["offstage_character_spoke", "snapshot_marks_character_offstage", "character_spoke_after_exit_event", "character_location_mismatch", "time_regression_claim"].includes(normalized)) {
    return "场景连续";
  }
  if (["speaker_out_of_scope", "controlled_character_overwritten"].includes(normalized)) {
    return "角色边界";
  }
  return "一致性";
}

async function correctLatestConsistencyTurn(button) {
  if (!currentRunId || !currentDialogueSessionId || button?.disabled) return;
  const originalText = String(button.textContent || "生成修正版");
  button.disabled = true;
  button.textContent = "正在修正...";
  if (typeof setComposerWaiting === "function") {
    setComposerWaiting(true, "正在按人物与场景约束重写这一轮...");
  }
  try {
    const session = await apiJson(
      `/api/web/runs/${encodeURIComponent(currentRunId)}/dialogue/sessions/${encodeURIComponent(currentDialogueSessionId)}/correct-latest`,
      { method: "POST" },
      "生成修正版失败。"
    );
    await renderDialogueSession(session);
    if (typeof setComposerWaiting === "function") setComposerWaiting(false, "");
  } catch (error) {
    button.disabled = false;
    button.textContent = originalText;
    if (typeof setComposerWaiting === "function") setComposerWaiting(false, "");
    showDialogueSendErrorModal(error?.message || "生成修正版失败。");
  }
}

async function deepReviewLatestConsistencyTurn(button) {
  if (!currentRunId || !currentDialogueSessionId || button?.disabled) return;
  const originalText = String(button.textContent || "深度复核");
  button.disabled = true;
  button.textContent = "正在复核...";
  if (typeof setComposerWaiting === "function") {
    setComposerWaiting(true, "正在检查语气、动机、关系态度和知识边界...");
  }
  try {
    const session = await apiJson(
      `/api/web/runs/${encodeURIComponent(currentRunId)}/dialogue/sessions/${encodeURIComponent(currentDialogueSessionId)}/deep-review`,
      { method: "POST" },
      "深度复核失败。"
    );
    await renderDialogueSession(session);
    if (typeof setComposerWaiting === "function") setComposerWaiting(false, "");
  } catch (error) {
    button.disabled = false;
    button.textContent = originalText;
    if (typeof setComposerWaiting === "function") setComposerWaiting(false, "");
    showDialogueSendErrorModal(error?.message || "深度复核失败。");
  }
}

function buildDialogueMemorySnapshot(session) {
  const summary = session?.session_memory_summary || {};
  const summaryMode = String(summary.mode || "").trim();
  const summaryModeLabel = String(summary.mode_display || "").trim();
  const summaryRecap = String(summary.recap || "").trim();
  const summaryCast = String(summary.cast || "").trim();
  const summaryRelation = String(summary.relation_drift || "").trim();
  const summaryPerspective = String(summary.perspective || "").trim();
  const summaryScene = String(summary.scene_frame || "").trim();
  const summaryLocation = String(summary.current_location || "").trim();
  const summaryCompanions = String(summary.current_companions || "").trim();
  const summaryCommitments = String(summary.pending_commitments || "").trim();
  const summaryWorld = String(summary.world || "").trim();
  const summaryUpdated = String(summary.updated_at || "").trim();

  if (summaryRecap || summaryCast || summaryRelation || summaryPerspective || summaryScene || summaryLocation || summaryCompanions || summaryCommitments || summaryWorld) {
    return {
      modeLabel: summaryModeLabel || humanizeMode(summaryMode || session?.mode || session?.session_card?.mode || "observe"),
      recap: summaryRecap || "这局刚开场，回顾会在这里滚动更新。",
      cast: summaryCast || "人物发言次序会在这里收住。",
      relation: summaryRelation || "关系线会在这里滚动提示。",
      perspective: summaryPerspective || "你当前的入场方式会在这里提示。",
      scene: summaryScene || "当前这幕的地点、气氛与推进方向会在这里提醒你。",
      location: summaryLocation || "当前落点会在这里提醒你。",
      companions: summaryCompanions || "现在与你同场的人会在这里提醒你。",
      commitments: summaryCommitments || "还没收口的承诺或待推进事项会在这里提醒你。",
      world: summaryWorld || "当前局势里的动作与情绪线会在这里提醒你。",
      updated: formatWeakTime(summaryUpdated) || formatWeakTime(session?.updated_at) || "刚刚更新",
    };
  }

  const mode = String(session?.mode || session?.session_card?.mode || "observe").trim() || "observe";
  const modeLabel = humanizeMode(mode) || mode;
  const transcript = Array.isArray(session?.transcript) ? session.transcript : [];
  const castRows = transcript.filter((item) => item?.role === "character");
  const worldRows = transcript.filter((item) => item?.role === "scene" || item?.role === "director");
  const lastRows = transcript.slice(-6);
  const lastCharacter = castRows.length ? castRows[castRows.length - 1] : null;
  const lastWorld = worldRows.length ? worldRows[worldRows.length - 1] : null;
  const speakerOrder = [];
  const seen = new Set();
  castRows.forEach((item) => {
    const speaker = String(item?.speaker || "").trim();
    if (!speaker || seen.has(speaker)) return;
    seen.add(speaker);
    speakerOrder.push(speaker);
  });
  const lastBeatMessages = lastRows
    .filter((item) => String(item?.message || "").trim())
    .map((item) => trimInlineMessage(item.message))
    .slice(-3);

  let recap = "这局刚开场，回顾会在这里滚动更新。";
  if (lastBeatMessages.length) {
    recap = `最近一拍：${lastBeatMessages.join(" / ")}`;
  }

  let cast = "人物发言次序会在这里收住。";
  if (speakerOrder.length) {
    cast = `当前主要在场：${speakerOrder.slice(0, 5).join("、")}${speakerOrder.length > 5 ? "..." : ""}`;
  } else if (lastCharacter?.speaker) {
    cast = `${lastCharacter.speaker} 刚刚接话：${trimInlineMessage(lastCharacter.message)}`;
  }

  let relation = "关系线会在这里滚动提示。";
  if (castRows.length >= 2) {
    const recent = castRows
      .slice(-4)
      .map((item) => String(item?.speaker || "").trim())
      .filter(Boolean);
    if (recent.length >= 2) {
      relation = `最近接话链：${recent.join(" → ")}`;
    }
  } else if (speakerOrder.length) {
    relation = `本局关键人物：${speakerOrder.slice(0, 4).join("、")}`;
  }

  let perspective = "你当前的入场方式会在这里提示。";
  if (mode === "act") {
    const controlled = String(session?.session_card?.controlled_character || "").trim() || "该角色";
    perspective = `你正以「${controlled}」发言，其他人会按角色关系回应。`;
  } else if (mode === "insert") {
    const selfName = String(session?.session_card?.self_insert?.display_name || "").trim() || "你";
    const identity = String(session?.session_card?.self_insert?.scene_identity || "").trim();
    perspective = identity ? `你以「${selfName}」入场（${identity}）。` : `你以「${selfName}」入场，直接参与这幕。`;
  } else {
    perspective = "你在旁观推进模式里，主要作用是推动局势进入下一拍。";
  }

  let world = "当前局势里的动作与情绪线会在这里提醒你。";
  let scene = "当前这幕的地点、气氛与推进方向会在这里提醒你。";
  let locationSummary = "";
  let companions = cast;
  let commitments = "";
  const sceneCard = session?.session_card?.scene_card || {};
  if (sceneCard && (sceneCard.title || sceneCard.location || sceneCard.atmosphere || sceneCard.scene_drive)) {
    const sceneBits = [sceneCard.title, sceneCard.location, sceneCard.atmosphere].filter(Boolean);
    const drive = trimInlineMessage(sceneCard.scene_drive || sceneCard.opening_situation || "");
    scene = sceneBits.length ? `挂载场景：${sceneBits.join(" / ")}${drive ? ` · ${drive}` : ""}` : drive || scene;
  }
  const overview = session?.runtime_state_overview || {};
  const overviewLocation = trimInlineMessage(String(overview.current_location || "").trim());
  const overviewCompanions = trimInlineMessage(String(overview.current_companions || "").trim());
  const overviewCommitments = trimInlineMessage(String(overview.pending_commitments || "").trim());
  if (overviewLocation) {
    locationSummary = overviewLocation;
  } else if (sceneCard?.location) {
    locationSummary = trimInlineMessage(String(sceneCard.location || "").trim());
  }
  if (overviewCompanions) {
    companions = overviewCompanions;
  }
  if (overviewCommitments) {
    commitments = overviewCommitments;
  }
  if (lastWorld?.message) {
    world = trimInlineMessage(lastWorld.message);
  } else if (lastCharacter?.message) {
    world = `人物最新情绪线：${trimInlineMessage(lastCharacter.message)}`;
  }

  return {
    modeLabel,
    recap,
    cast,
    relation,
    perspective,
    scene,
    location: locationSummary || "当前落点会在这里提醒你。",
    companions: companions || "现在与你同场的人会在这里提醒你。",
    commitments: commitments || "还没收口的承诺或待推进事项会在这里提醒你。",
    world,
    updated: formatWeakTime(session?.updated_at) || "刚刚更新",
  };
}

function renderDialogueStatePills(root, items) {
  if (!root) return;
  root.innerHTML = "";
  (Array.isArray(items) ? items : []).forEach((item) => {
    const text = String(item?.text || "").trim();
    if (!text) return;
    const chip = document.createElement("span");
    chip.className = `dialogue-state-pill${item?.faint ? " is-faint" : ""}`;
    chip.textContent = text;
    root.appendChild(chip);
  });
}

function renderDialogueStateChipList(root, items, emptyText = "暂时还没有明显变化。") {
  if (!root) return;
  root.innerHTML = "";
  const values = Array.isArray(items) ? items.filter(Boolean) : [];
  if (!values.length) {
    const chip = document.createElement("span");
    chip.className = "dialogue-state-chip is-faint";
    chip.textContent = emptyText;
    root.appendChild(chip);
    return;
  }
  values.forEach((value) => {
    const chip = document.createElement("span");
    chip.className = "dialogue-state-chip";
    chip.textContent = String(value || "").trim();
    root.appendChild(chip);
  });
}

function renderDialogueStateMiniList(root, items, emptyText = "这一栏还没有收出明显变化。") {
  if (!root) return;
  root.innerHTML = "";
  const rows = Array.isArray(items) ? items.filter(Boolean) : [];
  if (!rows.length) {
    const item = document.createElement("div");
    item.className = "dialogue-state-mini-item";
    const copy = document.createElement("p");
    copy.textContent = emptyText;
    item.appendChild(copy);
    root.appendChild(item);
    return;
  }
  rows.forEach((row) => {
    const item = document.createElement("div");
    item.className = "dialogue-state-mini-item";
    const title = document.createElement("strong");
    title.textContent = String(row?.title || "").trim() || "未命名";
    item.appendChild(title);
    const copy = document.createElement("p");
    copy.textContent = String(row?.copy || "").trim() || emptyText;
    item.appendChild(copy);
    root.appendChild(item);
  });
}

function buildDialogueStateSnapshot(session) {
  if (typeof DIALOGUE_STATE_TOOLS.buildDialogueStateSnapshot === "function") {
    return DIALOGUE_STATE_TOOLS.buildDialogueStateSnapshot(session);
  }
  const progress = session?.scene_progress || {};
  return {
    present: Array.isArray(progress.present_participants) ? progress.present_participants.filter(Boolean) : [],
    offstage: Array.isArray(progress.offstage_participants) ? progress.offstage_participants.filter(Boolean) : [],
    pills: [progress.location, progress.time_hint, progress.atmosphere_summary]
      .map((text) => ({ text: trimInlineMessage(text) }))
      .filter((item) => item.text),
    tension: trimInlineMessage(progress.world_tension_summary || session?.session_memory_summary?.world) || "这一拍的情绪和冲突会收在这里。",
    characterRows: [],
    relationRows: [],
    eventRows: [],
    statusLine: "",
    nextHint: "",
  };
}

function renderDialogueStateOverview(session) {
  const root = el("dialogue-state-overview");
  if (!root || !session) return;
  const snapshot = buildDialogueStateSnapshot(session);
  const hasContent = Boolean(
    snapshot.pills.length || snapshot.present.length || snapshot.offstage.length || snapshot.characterRows.length || snapshot.relationRows.length || snapshot.eventRows?.length || snapshot.tension
  );
  root.classList.toggle("hidden", !hasContent);
  if (!hasContent) return;
  renderDialogueStatePills(el("dialogue-state-pills"), snapshot.pills);
  renderDialogueStateChipList(el("dialogue-state-present"), snapshot.present, "这会儿还没有明确在场名单。");
  renderDialogueStateChipList(el("dialogue-state-offstage"), snapshot.offstage, "暂时没人明确离场。");
  setText("dialogue-state-tension", snapshot.tension, "这一拍的情绪和冲突会收在这里。");
  renderDialogueStateMiniList(el("dialogue-state-characters"), snapshot.characterRows, "角色快照会在聊出状态差后收进来。");
  renderDialogueStateMiniList(el("dialogue-state-relations"), snapshot.relationRows, "关系要聊出明显变化，才会在这里留下痕迹。");
  renderDialogueStateMiniList(el("dialogue-state-events"), snapshot.eventRows || [], "最近还没有收出更明确的事件波动。");
}

function buildDialogueSessionStatusLine(session) {
  const snapshot = buildDialogueStateSnapshot(session);
  if (snapshot.statusLine) {
    return snapshot.statusLine;
  }
  const bits = [];
  const pillTexts = Array.isArray(snapshot.pills)
    ? snapshot.pills.map((item) => String(item?.text || "").trim()).filter(Boolean)
    : [];
  if (pillTexts.length) {
    bits.push(pillTexts.slice(0, 3).join(" · "));
  }
  if (Array.isArray(snapshot.present) && snapshot.present.length) {
    bits.push(`在场：${snapshot.present.slice(0, 3).join("、")}`);
  }
  if (Array.isArray(snapshot.offstage) && snapshot.offstage.length) {
    bits.push(`离场：${snapshot.offstage.slice(0, 2).join("、")}`);
  }
  const tension = trimInlineMessage(snapshot.tension || "");
  if (tension) {
    bits.push(`张力：${tension}`);
  }
  return bits.filter(Boolean).join(" ｜ ");
}

function generationCacheNumber(source, keys) {
  if (!source || typeof source !== "object") return null;
  for (const key of keys) {
    const value = source[key];
    if (value === null || value === undefined || value === "") continue;
    const parsed = typeof value === "number" ? value : Number(value);
    if (Number.isFinite(parsed) && parsed >= 0) return parsed;
  }
  return null;
}

function normalizeGenerationCacheMetric(source) {
  if (!source || typeof source !== "object" || Array.isArray(source)) {
    return { observed: false, status: "unsupported", hitRate: null };
  }
  const status = String(source.status || "").trim().toLowerCase();
  const unsupportedStatuses = new Set(["unsupported", "not_supported", "unavailable", "unobserved"]);
  if (source.observed === false || unsupportedStatuses.has(status)) {
    return { observed: false, status: status || "unsupported", hitRate: null };
  }

  const inputTokens = generationCacheNumber(source, ["input_tokens", "prompt_tokens", "total_input_tokens"]);
  const cacheReadTokens = generationCacheNumber(source, [
    "cache_read_tokens",
    "cached_tokens",
    "cached_input_tokens",
    "prompt_cache_hit_tokens",
  ]);
  const cacheWriteTokens = generationCacheNumber(source, [
    "cache_write_tokens",
    "cache_creation_input_tokens",
    "prompt_cache_miss_tokens",
  ]);
  const cacheMissTokens = generationCacheNumber(source, ["cache_miss_tokens", "uncached_input_tokens"]);
  let hitRate = generationCacheNumber(source, ["hit_rate", "cache_hit_rate", "hit_ratio", "cache_hit_ratio"]);
  const percent = generationCacheNumber(source, ["hit_percent", "cache_hit_percent"]);
  if (hitRate === null && percent !== null) hitRate = percent / 100;
  if (hitRate !== null && hitRate > 1 && hitRate <= 100) hitRate /= 100;
  if (hitRate === null && inputTokens !== null && inputTokens > 0 && cacheReadTokens !== null) {
    hitRate = cacheReadTokens / inputTokens;
  }
  if (hitRate !== null) hitRate = Math.min(1, Math.max(0, hitRate));

  const hasObservedValue = hitRate !== null || inputTokens !== null || cacheReadTokens !== null;
  const observed = source.observed === true || hasObservedValue;
  return {
    observed,
    status: status || (observed ? "observed" : "unsupported"),
    hitRate: observed ? hitRate : null,
    inputTokens,
    cacheReadTokens,
    cacheWriteTokens,
    cacheMissTokens,
    observedTurns: generationCacheNumber(source, ["observed_turns"]),
    totalTurns: generationCacheNumber(source, ["total_turns"]),
  };
}

function aggregateGenerationCacheTurns(turns) {
  if (!Array.isArray(turns) || !turns.length) return null;
  const observedTurns = turns.map(normalizeGenerationCacheMetric).filter((item) => item.observed);
  if (!observedTurns.length) return null;
  const countableTurns = observedTurns.filter(
    (item) => item.inputTokens !== null && item.cacheReadTokens !== null
  );
  if (!countableTurns.length) return null;
  const inputTokens = countableTurns.reduce((sum, item) => sum + item.inputTokens, 0);
  const cacheReadTokens = countableTurns.reduce((sum, item) => sum + item.cacheReadTokens, 0);
  const cacheWriteTokens = countableTurns.reduce((sum, item) => sum + (item.cacheWriteTokens || 0), 0);
  return {
    observed: true,
    status: "observed",
    input_tokens: inputTokens,
    cache_read_tokens: cacheReadTokens,
    cache_write_tokens: cacheWriteTokens,
    hit_rate: inputTokens > 0 ? cacheReadTokens / inputTokens : null,
    observed_turns: observedTurns.length,
    total_turns: turns.length,
  };
}

function buildGenerationCacheSnapshot(session) {
  const stats = session?.generation_cache_stats;
  if (!stats || typeof stats !== "object" || Array.isArray(stats)) {
    const unsupported = normalizeGenerationCacheMetric(null);
    return { latest: unsupported, session: unsupported };
  }
  const turns = Array.isArray(stats.turns) ? stats.turns : [];
  const latestSource = stats.latest || stats.turn || stats.current || turns[turns.length - 1] || null;
  const sessionSource = stats.session || stats.total || stats.aggregate || aggregateGenerationCacheTurns(turns);
  return {
    latest: normalizeGenerationCacheMetric(latestSource),
    session: normalizeGenerationCacheMetric(sessionSource),
  };
}

function formatGenerationCacheRate(metric) {
  if (!metric?.observed || metric.hitRate === null) return "-";
  const percent = Math.round(metric.hitRate * 1000) / 10;
  return `${Number.isInteger(percent) ? percent.toFixed(0) : percent.toFixed(1)}%`;
}

function formatGenerationCacheTokenCount(value) {
  if (!Number.isFinite(value)) return "";
  return Math.round(value).toLocaleString("zh-CN");
}

function generationCacheMetricTitle(label, metric) {
  const parts = [`${label}：${formatGenerationCacheRate(metric)}`];
  if (Number.isFinite(metric?.cacheReadTokens) && Number.isFinite(metric?.inputTokens)) {
    parts.push(`缓存读取 ${formatGenerationCacheTokenCount(metric.cacheReadTokens)} / 输入 ${formatGenerationCacheTokenCount(metric.inputTokens)} tokens`);
  }
  if (Number.isFinite(metric?.cacheWriteTokens) && metric.cacheWriteTokens > 0) {
    parts.push(`缓存写入 ${formatGenerationCacheTokenCount(metric.cacheWriteTokens)} tokens`);
  }
  if (Number.isFinite(metric?.observedTurns) && Number.isFinite(metric?.totalTurns)) {
    parts.push(`${formatGenerationCacheTokenCount(metric.observedTurns)} / ${formatGenerationCacheTokenCount(metric.totalTurns)} 轮可观测`);
  }
  return parts.join("；");
}

function renderDialogueGenerationCacheStats(session) {
  const snapshot = buildGenerationCacheSnapshot(session);
  const root = el("dialogue-cache-stats");
  if (!root) return;
  const latestRate = formatGenerationCacheRate(snapshot.latest);
  const sessionRate = formatGenerationCacheRate(snapshot.session);
  root.textContent = `本次命中 ${latestRate} ｜ 平均命中 ${sessionRate}`;
  root.title = [
    generationCacheMetricTitle("本次命中", snapshot.latest),
    generationCacheMetricTitle("平均命中", snapshot.session),
  ].join("；");
  root.setAttribute("aria-label", `${root.textContent}。${root.title}`);
}

function formatDialogueGenerationDuration(value) {
  const seconds = Number(value || 0);
  if (!Number.isFinite(seconds) || seconds <= 0) return "-";
  return seconds < 1 ? `${Math.round(seconds * 1000)} ms` : `${seconds.toFixed(2)} s`;
}

function formatDialogueGenerationCost(value) {
  const amount = Number(value || 0);
  if (!Number.isFinite(amount) || amount <= 0) return "未提供";
  return `$${amount.toFixed(amount < 0.01 ? 5 : 3)}`;
}

function renderDialogueGenerationMetrics(session) {
  const root = el("dialogue-generation-metrics");
  if (!root) return;
  const stats = session?.generation_cache_stats || {};
  const turns = Array.isArray(stats?.turns) ? stats.turns : [];
  const latest = stats?.latest || turns[turns.length - 1] || {};
  const aggregate = stats?.session || {};
  if (!turns.length && !Number(aggregate?.total_turns || 0)) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  root.innerHTML = "";

  const head = document.createElement("div");
  head.className = "dialogue-generation-metrics-head";
  const heading = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = "模型与成本";
  const note = document.createElement("small");
  note.textContent = "统计主回复生成的 token、耗时、模型与失败重试。";
  heading.append(title, note);
  const provider = document.createElement("span");
  provider.textContent = [String(latest?.provider || "").trim(), String(latest?.model || "").trim()]
    .filter(Boolean)
    .join(" · ") || "模型信息未提供";
  head.append(heading, provider);
  root.appendChild(head);

  const cards = document.createElement("div");
  cards.className = "dialogue-generation-metric-cards";
  [
    ["本轮 Token", formatGenerationCacheTokenCount(Number(latest?.total_tokens || 0)), `输入 ${formatGenerationCacheTokenCount(Number(latest?.prompt_tokens || 0))} / 输出 ${formatGenerationCacheTokenCount(Number(latest?.completion_tokens || 0))}`],
    ["本轮耗时", formatDialogueGenerationDuration(latest?.elapsed_seconds), `${Number(latest?.attempt_count || 1)} 次模型调用`],
    ["累计 Token", formatGenerationCacheTokenCount(Number(aggregate?.total_tokens || 0)), `${Number(aggregate?.total_turns || 0)} 轮主回复`],
    ["平均耗时", formatDialogueGenerationDuration(aggregate?.average_elapsed_seconds), `共重试 ${Number(aggregate?.retry_count || 0)} 次`],
    ["累计费用", formatDialogueGenerationCost(aggregate?.cost_usd), "由模型服务 usage 返回"],
  ].forEach(([label, value, detail]) => {
    const card = document.createElement("article");
    const cardLabel = document.createElement("span");
    cardLabel.textContent = label;
    const cardValue = document.createElement("strong");
    cardValue.textContent = value || "0";
    const cardDetail = document.createElement("small");
    cardDetail.textContent = detail;
    card.append(cardLabel, cardValue, cardDetail);
    cards.appendChild(card);
  });
  root.appendChild(cards);

  const models = Object.entries(aggregate?.models || {});
  const recent = turns.slice(-8).reverse();
  const details = document.createElement("details");
  details.className = "dialogue-generation-history";
  const summary = document.createElement("summary");
  summary.textContent = `查看最近 ${recent.length} 轮明细`;
  details.appendChild(summary);
  if (models.length) {
    const modelLine = document.createElement("p");
    modelLine.className = "dialogue-generation-models";
    modelLine.textContent = `模型调用：${models.map(([model, count]) => `${model} × ${Number(count || 0)}`).join("；")}`;
    details.appendChild(modelLine);
  }
  recent.forEach((item, index) => {
    const row = document.createElement("article");
    const rowTitle = document.createElement("strong");
    rowTitle.textContent = `最近第 ${index + 1} 轮 · ${String(item?.model || "未知模型")}`;
    const rowCopy = document.createElement("p");
    rowCopy.textContent = [
      `${formatGenerationCacheTokenCount(Number(item?.total_tokens || 0))} tokens`,
      formatDialogueGenerationDuration(item?.elapsed_seconds),
      Number(item?.retry_count || 0) ? `重试 ${Number(item.retry_count)} 次` : "未重试",
      `缓存 ${formatGenerationCacheRate(normalizeGenerationCacheMetric(item))}`,
    ].join(" · ");
    row.append(rowTitle, rowCopy);
    details.appendChild(row);
  });
  root.appendChild(details);
}

function renderDialogueMemory(session) {
  const root = el("dialogue-memory");
  if (!root) return;
  if (!session) {
    closeDialogueMemoryModal({ silent: true });
    root.classList.add("hidden");
    return;
  }
  root.classList.add("is-collapsed");
  const snapshot = buildDialogueMemorySnapshot(session);
  const modalOpen = isDialogueMemoryModalOpen();
  root.classList.remove("hidden");
  renderDialogueGenerationCacheStats(session);
  renderDialogueGenerationMetrics(session);
  renderDialogueControlledMemories(session);
  setText("dialogue-memory-recap", snapshot.recap, "");
  setText("dialogue-memory-cast", snapshot.cast, "");
  setText("dialogue-memory-relation", snapshot.relation, "");
  setText("dialogue-memory-perspective", snapshot.perspective, "");
  setText("dialogue-memory-scene", snapshot.scene, "");
  setText("dialogue-memory-location", snapshot.location, "");
  setText("dialogue-memory-companions", snapshot.companions, "");
  setText("dialogue-memory-commitments", snapshot.commitments, "");
  setText("dialogue-memory-world", snapshot.world, "");
  setText("dialogue-memory-mode", `模式：${snapshot.modeLabel}`, "");
  const branchNote = el("dialogue-memory-branch");
  const branchOrigin = session?.branch_origin || {};
  const branchTitle = String(branchOrigin?.scene_title || branchOrigin?.event_title || "").trim();
  if (branchNote) {
    branchNote.textContent = branchTitle ? `分支自：${branchTitle}` : "";
    branchNote.classList.toggle("hidden", !branchTitle);
  }
  setText("dialogue-memory-updated", `更新于 ${snapshot.updated}`, "");
  setText("dialogue-memory-modal-updated", `更新于 ${snapshot.updated}`, "");
  const toggle = el("dialogue-memory-toggle-button");
  if (toggle) {
    toggle.textContent = modalOpen ? "关闭弹窗" : "弹窗查看";
  }
  const body = el("dialogue-memory-body");
  if (body) {
    body.classList.toggle("hidden", body.parentElement === root);
  }
  renderDialogueStateOverview(session);
  renderDialogueBranchManager(session);
  renderDialogueChapterOutline(session);
  renderDialogueEventTimeline(session);
  renderDialogueDirectorPanel(session);
  renderDialogueRelationEvolution(session);
  renderDialogueCharacterGrowth(session);
  renderDialogueSpeakerBalance(session);
  renderDialogueSceneTimeline(session);
  if (typeof window.renderDialogueSceneSwitcher === "function") {
    window.renderDialogueSceneSwitcher(session);
  }
}

function directorActionLabel(action) {
  const labels = {
    advance: "推进剧情",
    slow_emotion: "放慢情绪",
    conflict: "引入冲突",
    viewpoint: "切换视角",
  };
  return labels[String(action || "").trim()] || "推进剧情";
}

function renderDialogueDirectorPanel(session) {
  const root = el("dialogue-director-panel");
  if (!root) return;
  if (!session) {
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  const sessionId = String(session?.session_id || "").trim();
  if (dialogueDirectorState.sessionId !== sessionId) {
    dialogueDirectorState.sessionId = sessionId;
    dialogueDirectorState.options = [];
    dialogueDirectorState.loading = false;
  }
  root.querySelectorAll("[data-director-action]").forEach((button) => {
    button.classList.toggle("is-active", button.dataset.directorAction === dialogueDirectorState.action);
    if (!button.dataset.bound) {
      button.dataset.bound = "true";
      button.addEventListener("click", () => {
        dialogueDirectorState.action = String(button.dataset.directorAction || "advance");
        renderDialogueDirectorPanel(session);
      });
    }
  });
  const generate = el("dialogue-director-generate");
  if (generate) {
    generate.disabled = dialogueDirectorState.loading;
    generate.textContent = dialogueDirectorState.loading ? "正在构思..." : "生成候选方案";
    if (!generate.dataset.bound) {
      generate.dataset.bound = "true";
      generate.addEventListener("click", () => generateDialogueDirectorOptions(generate));
    }
  }
  const optionsRoot = el("dialogue-director-options");
  if (!optionsRoot) return;
  optionsRoot.innerHTML = "";
  dialogueDirectorState.options.forEach((option, index) => {
    const card = document.createElement("article");
    card.className = "dialogue-director-option";
    const head = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = String(option?.title || `方案 ${index + 1}`);
    const focus = document.createElement("span");
    focus.textContent = String(option?.focus || directorActionLabel(dialogueDirectorState.action));
    head.append(title, focus);
    const beat = document.createElement("p");
    beat.textContent = String(option?.beat || "");
    card.append(head, beat);
    if (String(option?.expected_effect || "").trim()) {
      const effect = document.createElement("small");
      effect.textContent = `效果：${String(option.expected_effect)}`;
      card.appendChild(effect);
    }
    if (String(option?.risk || "").trim()) {
      const risk = document.createElement("small");
      risk.className = "is-risk";
      risk.textContent = `风险：${String(option.risk)}`;
      card.appendChild(risk);
    }
    const apply = document.createElement("button");
    apply.type = "button";
    apply.className = "soft-button";
    apply.textContent = "选择并开始演绎";
    apply.addEventListener("click", () => applyDialogueDirectorOption(option, apply));
    card.appendChild(apply);
    optionsRoot.appendChild(card);
  });
}

async function generateDialogueDirectorOptions(button) {
  const goal = valueOf("dialogue-director-goal", "").trim();
  if (!currentRunId || !currentDialogueSessionId || !goal || dialogueDirectorState.loading) {
    if (!goal) setText("dialogue-director-status", "请先填写导演目标。", "");
    return;
  }
  dialogueDirectorState.loading = true;
  dialogueDirectorState.options = [];
  setText("dialogue-director-status", `正在按“${directorActionLabel(dialogueDirectorState.action)}”构思...`, "");
  renderDialogueDirectorPanel(currentDialogueSession);
  try {
    const api = await requireWebUiApi();
    const payload = await api.generateDialogueDirectorOptions(currentRunId, currentDialogueSessionId, {
      goal,
      action: dialogueDirectorState.action,
      option_count: 3,
    });
    dialogueDirectorState.options = Array.isArray(payload?.options) ? payload.options : [];
    setText("dialogue-director-status", `已生成 ${dialogueDirectorState.options.length} 个方案。`, "");
  } catch (error) {
    setText("dialogue-director-status", error.message || "导演方案生成失败。", "");
  } finally {
    dialogueDirectorState.loading = false;
    renderDialogueDirectorPanel(currentDialogueSession);
    if (button) button.disabled = false;
  }
}

async function applyDialogueDirectorOption(option, button) {
  const direction = String(option?.direction || "").trim();
  if (!direction || !currentRunId || !currentDialogueSessionId) return;
  const originalText = button?.textContent || "选择并开始演绎";
  if (button) {
    button.disabled = true;
    button.textContent = "正在落地...";
  }
  try {
    const beat = String(option?.beat || "").trim();
    const plotCue = beat && !direction.includes(beat)
      ? `${beat}\n推进要求：${direction}`
      : direction;
    dialogueDirectorState.options = [];
    const sent = typeof window.handleSendTurn === "function"
      ? await window.handleSendTurn(plotCue, "plot")
      : false;
    if (!sent) {
      if (typeof window.setComposerDraft === "function") {
        window.setComposerDraft(plotCue, { focus: true });
      }
      if (typeof window.setDialogueMessageKind === "function") {
        window.setDialogueMessageKind("plot");
      }
        setDialogueSessionFailure("方案已经写成文案，但这次没有发送成功。", "内容已放回输入框，可以直接重试。", true);
    }
  } catch (error) {
    setDialogueSessionFailure(error.message || "导演方案落地失败。", "可以选择其他方案，或稍后重试。", false);
    if (button) {
      button.disabled = false;
      button.textContent = originalText;
    }
  }
}

function dialogueSpeakerStatusLabel(status) {
  const labels = {
    new: "等待首次发言",
    active: "近期活跃",
    due: "可以介入",
    silent: "沉默较久",
  };
  return labels[String(status || "").trim()] || "状态未知";
}

function renderDialogueSpeakerBalance(session) {
  const root = el("dialogue-speaker-balance");
  if (!root) return;
  const activity = Array.isArray(session?.speaker_activity) ? session.speaker_activity : [];
  if (activity.length < 2) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  root.innerHTML = "";
  const head = document.createElement("div");
  head.className = "dialogue-speaker-balance-head";
  const titleWrap = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = "群聊发言平衡";
  const note = document.createElement("small");
  note.textContent = "沉默角色会被优先考虑，但不会为了平均而强行插话。";
  titleWrap.append(title, note);
  head.appendChild(titleWrap);
  root.appendChild(head);

  const plan = session?.speaker_balance || {};
  const recommended = Array.isArray(plan?.recommended_speakers) ? plan.recommended_speakers : [];
  if (recommended.length) {
    const next = document.createElement("p");
    next.className = "dialogue-speaker-next";
    next.textContent = `下一轮优先考虑：${recommended.join(" → ")}`;
    root.appendChild(next);
  }
  const grid = document.createElement("div");
  grid.className = "dialogue-speaker-grid";
  activity.forEach((item) => {
    const card = document.createElement("article");
    card.className = `dialogue-speaker-item is-${String(item?.status || "new")}`;
    const cardHead = document.createElement("div");
    const name = document.createElement("strong");
    name.textContent = String(item?.name || "角色");
    const status = document.createElement("span");
    status.textContent = dialogueSpeakerStatusLabel(item?.status);
    cardHead.append(name, status);
    const rate = Math.round(Number(item?.participation_rate || 0) * 100);
    const copy = document.createElement("p");
    const silence = Number(item?.turns_since_spoke || 0);
    copy.textContent = `${Number(item?.spoken_turns || 0)} / ${Number(item?.total_turns || 0)} 轮参与 · ${rate}%${silence ? ` · ${silence} 轮未发言` : ""}`;
    const bar = document.createElement("div");
    bar.className = "dialogue-speaker-rate";
    const fill = document.createElement("i");
    fill.style.width = `${Math.max(0, Math.min(100, rate))}%`;
    bar.appendChild(fill);
    card.append(cardHead, copy, bar);
    grid.appendChild(card);
  });
  root.appendChild(grid);
}

function dialogueMemoryCategoryLabel(category) {
  const labels = {
    short_term: "短期",
    long_term: "长期",
    story: "剧情",
    relationship: "关系",
  };
  return labels[String(category || "").trim()] || "剧情";
}

function resetDialogueMemoryEditor() {
  editingDialogueMemoryId = "";
  editingDialogueMemoryEnabled = true;
  setValue("dialogue-memory-input", "");
  setValue("dialogue-memory-category", "story");
  const pinned = el("dialogue-memory-pinned");
  if (pinned) pinned.checked = false;
  const save = el("dialogue-memory-save");
  if (save) save.textContent = "添加记忆";
  el("dialogue-memory-cancel-edit")?.classList.add("hidden");
}

function renderDialogueContextUsage(session) {
  const root = el("dialogue-context-usage-list");
  if (!root) return;
  root.innerHTML = "";
  const sources = Array.isArray(session?.latest_context_usage?.sources)
    ? session.latest_context_usage.sources
    : [];
  if (!sources.length) {
    const empty = document.createElement("p");
    empty.textContent = "完成一轮对话后，这里会列出实际送入模型的上下文。";
    root.appendChild(empty);
    return;
  }
  sources.forEach((source) => {
    const item = document.createElement("article");
    const title = document.createElement("strong");
    title.textContent = `${String(source?.label || "上下文")} · ${Number(source?.count || 0)} 项`;
    item.appendChild(title);
    const values = Array.isArray(source?.items) ? source.items.filter(Boolean) : [];
    if (values.length) {
      const copy = document.createElement("p");
      copy.textContent = values.join("；");
      item.appendChild(copy);
    }
    root.appendChild(item);
  });
}

function renderDialogueControlledMemories(session) {
  const root = el("dialogue-memory-ledger");
  if (!root) return;
  root.innerHTML = "";
  const memories = Array.isArray(session?.memory_ledger) ? [...session.memory_ledger] : [];
  memories.sort((left, right) => Number(Boolean(right?.pinned)) - Number(Boolean(left?.pinned)));
  if (!memories.length) {
    const empty = document.createElement("p");
    empty.className = "dialogue-memory-ledger-empty";
    empty.textContent = "还没有手动管理的记忆。";
    root.appendChild(empty);
  }
  memories.forEach((memory) => {
    const card = document.createElement("article");
    card.className = "dialogue-memory-ledger-item";
    if (!memory?.enabled) card.classList.add("is-disabled");
    const body = document.createElement("div");
    const meta = document.createElement("span");
    meta.textContent = `${dialogueMemoryCategoryLabel(memory?.category)}记忆${memory?.pinned ? " · 必须记住" : ""}${memory?.enabled ? "" : " · 已停用"}`;
    const copy = document.createElement("p");
    copy.textContent = String(memory?.text || "");
    body.append(meta, copy);
    const actions = document.createElement("div");
    actions.className = "dialogue-memory-ledger-actions";
    const edit = document.createElement("button");
    edit.type = "button";
    edit.className = "soft-button";
    edit.textContent = "编辑";
    edit.addEventListener("click", () => {
      editingDialogueMemoryId = String(memory?.memory_id || "");
      editingDialogueMemoryEnabled = Boolean(memory?.enabled);
      setValue("dialogue-memory-input", String(memory?.text || ""));
      setValue("dialogue-memory-category", String(memory?.category || "story"));
      const pinned = el("dialogue-memory-pinned");
      if (pinned) pinned.checked = Boolean(memory?.pinned);
      const save = el("dialogue-memory-save");
      if (save) save.textContent = "保存修改";
      el("dialogue-memory-cancel-edit")?.classList.remove("hidden");
      el("dialogue-memory-input")?.focus();
    });
    const toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = "soft-button";
    toggle.textContent = memory?.enabled ? "停用" : "启用";
    toggle.addEventListener("click", () => saveDialogueMemoryEntry(memory, { enabled: !memory?.enabled }, toggle));
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "soft-button is-danger";
    remove.textContent = "删除";
    remove.addEventListener("click", () => deleteDialogueMemoryEntry(memory, remove));
    actions.append(edit, toggle, remove);
    card.append(body, actions);
    root.appendChild(card);
  });
  const saveButton = el("dialogue-memory-save");
  if (saveButton && !saveButton.dataset.bound) {
    saveButton.dataset.bound = "true";
    saveButton.addEventListener("click", () => submitDialogueMemoryEditor(saveButton));
  }
  const cancelButton = el("dialogue-memory-cancel-edit");
  if (cancelButton && !cancelButton.dataset.bound) {
    cancelButton.dataset.bound = "true";
    cancelButton.addEventListener("click", resetDialogueMemoryEditor);
  }
  renderDialogueContextUsage(session);
}

async function submitDialogueMemoryEditor(button) {
  const text = valueOf("dialogue-memory-input", "").trim();
  if (!text || !currentRunId || !currentDialogueSessionId) return;
  const payload = {
    text,
    category: valueOf("dialogue-memory-category", "story"),
    pinned: Boolean(el("dialogue-memory-pinned")?.checked),
    enabled: editingDialogueMemoryEnabled,
  };
  if (button) button.disabled = true;
  try {
    const api = await requireWebUiApi();
    const session = editingDialogueMemoryId
      ? await api.updateDialogueMemory(currentRunId, currentDialogueSessionId, editingDialogueMemoryId, payload)
      : await api.createDialogueMemory(currentRunId, currentDialogueSessionId, payload);
    resetDialogueMemoryEditor();
    await renderDialogueSession(session);
    setDialogueSessionSuccess("记忆已保存。", payload.pinned ? "这条设定会在后续每轮作为必须遵守的上下文。" : "这条设定已加入当前会话记忆。 ");
  } catch (error) {
    setDialogueSessionFailure(error.message || "记忆保存失败。", "当前记忆没有变化。", false);
  } finally {
    if (button) button.disabled = false;
  }
}

async function saveDialogueMemoryEntry(memory, overrides, button) {
  if (!currentRunId || !currentDialogueSessionId) return;
  if (button) button.disabled = true;
  try {
    const api = await requireWebUiApi();
    const session = await api.updateDialogueMemory(
      currentRunId,
      currentDialogueSessionId,
      String(memory?.memory_id || ""),
      {
        text: String(memory?.text || ""),
        category: String(memory?.category || "story"),
        pinned: Boolean(memory?.pinned),
        enabled: Object.prototype.hasOwnProperty.call(overrides || {}, "enabled") ? Boolean(overrides.enabled) : Boolean(memory?.enabled),
      }
    );
    await renderDialogueSession(session);
  } catch (error) {
    setDialogueSessionFailure(error.message || "记忆状态更新失败。", "当前记忆没有变化。", false);
    if (button) button.disabled = false;
  }
}

async function deleteDialogueMemoryEntry(memory, button) {
  const memoryId = String(memory?.memory_id || "").trim();
  if (!currentRunId || !currentDialogueSessionId || !memoryId) return;
  if (!window.confirm("确定删除这条记忆吗？删除后无法从当前会话恢复。")) return;
  if (button) button.disabled = true;
  try {
    const api = await requireWebUiApi();
    const session = await api.deleteDialogueMemory(currentRunId, currentDialogueSessionId, memoryId);
    if (editingDialogueMemoryId === memoryId) resetDialogueMemoryEditor();
    await renderDialogueSession(session);
  } catch (error) {
    setDialogueSessionFailure(error.message || "记忆删除失败。", "当前记忆没有变化。", false);
    if (button) button.disabled = false;
  }
}

function dialogueEventTypeLabel(kind) {
  const labels = {
    dialogue: "对话推进",
    cast_enter: "角色入场",
    cast_exit: "角色离场",
    scene_transition: "场景转换",
    time_change: "时间变化",
    environment_change: "环境变化",
    atmosphere_shift: "气氛变化",
    promise: "承诺",
    secret: "秘密",
    conflict: "冲突",
    relation_shift: "关系变化",
    micro_action: "角色动作",
    beat_complete: "段落完成",
    focus_shift: "关系聚焦",
  };
  return labels[String(kind || "").trim()] || String(kind || "剧情事件").trim();
}

function buildDialogueTimelineSelect(labelText, values, selectedValue, onChange) {
  const label = document.createElement("label");
  label.className = "dialogue-event-filter";
  const span = document.createElement("span");
  span.textContent = labelText;
  const select = document.createElement("select");
  const all = document.createElement("option");
  all.value = "";
  all.textContent = "全部";
  select.appendChild(all);
  values.forEach(({ value, label: optionLabel }) => {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = optionLabel;
    select.appendChild(option);
  });
  select.value = selectedValue;
  select.addEventListener("change", () => onChange(select.value));
  label.append(span, select);
  return label;
}

function dialogueBranchMetricLabel(metric) {
  const labels = {
    trust: "信任",
    affection: "好感",
    hostility: "敌意",
    ambiguity: "暧昧",
  };
  return labels[String(metric || "").trim()] || String(metric || "关系");
}

async function updateDialogueBranchMetadata(payload, button) {
  if (!currentRunId || !currentDialogueSessionId) return;
  if (button) button.disabled = true;
  try {
    const api = await requireWebUiApi();
    const session = await api.updateDialogueBranchMeta(
      currentRunId,
      currentDialogueSessionId,
      payload
    );
    await renderDialogueSession(session);
    if (typeof loadRecentSessions === "function") await loadRecentSessions();
  } catch (error) {
    setDialogueSessionFailure(
      error.message || "剧情分支信息更新失败。",
      "当前剧情内容没有变化，可以稍后重试。",
      false
    );
    if (button) button.disabled = false;
  }
}

async function openDialogueBranchSession(sessionId, button) {
  const normalizedId = String(sessionId || "").trim();
  if (!currentRunId || !normalizedId || normalizedId === currentDialogueSessionId) return;
  if (button) button.disabled = true;
  try {
    const session = await apiJson(
      `/api/web/runs/${encodeURIComponent(currentRunId)}/dialogue/sessions/${encodeURIComponent(normalizedId)}`
    );
    await renderDialogueSession(session);
  } catch (error) {
    setDialogueSessionFailure(
      error.message || "剧情分支载入失败。",
      "仍停留在当前分支。",
      false
    );
    if (button) button.disabled = false;
  }
}

function renderDialogueBranchManager(session) {
  const root = el("dialogue-branch-manager");
  if (!root) return;
  const graph = session?.branch_graph || {};
  const nodes = Array.isArray(graph?.nodes) ? graph.nodes : [];
  if (!nodes.length) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  root.innerHTML = "";

  const head = document.createElement("div");
  head.className = "dialogue-branch-head";
  const heading = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = "多分支剧情";
  const note = document.createElement("small");
  note.textContent = "命名分支、指定主线，并比较不同选择造成的关系变化。";
  heading.append(title, note);
  const count = document.createElement("span");
  count.textContent = `${nodes.length} 条分支`;
  head.append(heading, count);
  root.appendChild(head);

  const meta = session?.branch_meta || {};
  const currentNode = nodes.find((item) => item?.is_current) || {};
  const editor = document.createElement("div");
  editor.className = "dialogue-branch-editor";
  const input = document.createElement("input");
  input.type = "text";
  input.maxLength = 80;
  input.value = String(meta?.label || "").trim();
  input.placeholder = String(currentNode?.label || "给当前分支命名");
  input.setAttribute("aria-label", "当前剧情分支名称");
  const save = document.createElement("button");
  save.type = "button";
  save.className = "soft-button";
  save.textContent = "保存名称";
  save.addEventListener("click", () => updateDialogueBranchMetadata({ label: input.value }, save));
  const mainlineLabel = document.createElement("label");
  mainlineLabel.className = "dialogue-branch-mainline-toggle";
  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.checked = Boolean(meta?.is_mainline);
  checkbox.addEventListener("change", () => updateDialogueBranchMetadata({ is_mainline: checkbox.checked }, checkbox));
  const mainlineText = document.createElement("span");
  mainlineText.textContent = "设为主线分支";
  mainlineLabel.append(checkbox, mainlineText);
  editor.append(input, save, mainlineLabel);
  root.appendChild(editor);

  const list = document.createElement("div");
  list.className = "dialogue-branch-list";
  nodes.forEach((node) => {
    const card = document.createElement("article");
    card.className = "dialogue-branch-item";
    if (node?.is_current) card.classList.add("is-current");
    if (node?.is_mainline) card.classList.add("is-mainline");
    const top = document.createElement("div");
    top.className = "dialogue-branch-item-top";
    const label = document.createElement("strong");
    label.textContent = String(node?.label || "未命名分支");
    const badges = document.createElement("span");
    badges.textContent = [node?.is_current ? "当前" : "", node?.is_mainline ? "主线" : ""]
      .filter(Boolean)
      .join(" · ");
    top.append(label, badges);
    card.appendChild(top);
    const origin = document.createElement("small");
    const originTitle = String(node?.origin_title || "").trim();
    origin.textContent = originTitle
      ? `从“${originTitle}”产生 · ${Number(node?.event_count || 0)} 个事件`
      : `${Number(node?.event_count || 0)} 个剧情事件`;
    card.appendChild(origin);

    const changes = Array.isArray(node?.relation_changes) ? node.relation_changes : [];
    if (!node?.is_current) {
      const comparison = document.createElement("p");
      comparison.className = "dialogue-branch-comparison";
      comparison.textContent = changes.length
        ? changes.slice(0, 4).map((item) => {
            const delta = Number(item?.delta || 0);
            return `${String(item?.pair_key || "人物关系")} ${dialogueBranchMetricLabel(item?.metric)} ${delta > 0 ? "+" : ""}${delta}`;
          }).join("；")
        : "与当前分支相比，人物关系数值暂无差异。";
      card.appendChild(comparison);
      const open = document.createElement("button");
      open.type = "button";
      open.className = "soft-button";
      open.textContent = "切换到此分支";
      open.addEventListener("click", () => openDialogueBranchSession(String(node?.session_id || ""), open));
      card.appendChild(open);
    }
    list.appendChild(card);
  });
  root.appendChild(list);
}

async function toggleDialogueMainlineEvent(turnId, locked, button) {
  const normalizedId = String(turnId || "").trim();
  if (!normalizedId || !currentDialogueSession) return;
  const currentIds = Array.isArray(currentDialogueSession?.branch_meta?.locked_event_ids)
    ? currentDialogueSession.branch_meta.locked_event_ids.map((item) => String(item || "").trim()).filter(Boolean)
    : [];
  const nextIds = locked
    ? currentIds.filter((item) => item !== normalizedId)
    : [...new Set([...currentIds, normalizedId])];
  await updateDialogueBranchMetadata({ locked_event_ids: nextIds }, button);
}

function focusDialogueChapterEvent(turnId) {
  const normalizedId = String(turnId || "").trim();
  if (!normalizedId) return;
  const target = Array.from(document.querySelectorAll(".dialogue-event-item")).find(
    (item) => String(item.getAttribute("data-turn-id") || "").trim() === normalizedId
  );
  if (!target) return;
  target.scrollIntoView({ behavior: "smooth", block: "center" });
  target.classList.add("is-focused");
  window.setTimeout(() => target.classList.remove("is-focused"), 1800);
}

function renderDialogueChapterOutline(session) {
  const root = el("dialogue-chapter-outline");
  if (!root) return;
  const outline = session?.chapter_outline || {};
  const chapters = Array.isArray(outline?.chapters) ? outline.chapters : [];
  if (!chapters.length) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  root.innerHTML = "";

  const head = document.createElement("div");
  head.className = "dialogue-chapter-head";
  const heading = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = "章节与场景目录";
  const note = document.createElement("small");
  note.textContent = "聊天后自动归档本幕摘要、出场人物与待续伏笔。";
  heading.append(title, note);
  const stats = document.createElement("span");
  stats.textContent = `${Number(outline?.chapter_count || chapters.length)} 幕 · ${Number(outline?.unresolved_hook_count || 0)} 条伏笔`;
  head.append(heading, stats);
  root.appendChild(head);

  const list = document.createElement("div");
  list.className = "dialogue-chapter-list";
  chapters.forEach((chapter) => {
    const details = document.createElement("details");
    details.className = "dialogue-chapter-item";
    details.open = Boolean(chapter?.is_current);
    const summary = document.createElement("summary");
    const summaryTitle = document.createElement("strong");
    summaryTitle.textContent = `第 ${Number(chapter?.chapter_number || 0)} 幕 · ${String(chapter?.title || "未命名场景")}`;
    const count = document.createElement("span");
    count.textContent = `${Number(chapter?.event_count || 0)} 个事件${chapter?.is_current ? " · 当前" : ""}`;
    summary.append(summaryTitle, count);
    details.appendChild(summary);

    const body = document.createElement("div");
    body.className = "dialogue-chapter-body";
    const meta = document.createElement("p");
    meta.className = "dialogue-chapter-meta";
    meta.textContent = [
      String(chapter?.time_hint || "").trim(),
      String(chapter?.location || "").trim(),
      (chapter?.participants || []).join("、"),
    ].filter(Boolean).join(" · ") || "场景信息待补充";
    const recap = document.createElement("p");
    recap.className = "dialogue-chapter-recap";
    recap.textContent = String(chapter?.summary || "本幕尚未发生明确事件。");
    body.append(meta, recap);

    const hooks = Array.isArray(chapter?.hooks) ? chapter.hooks : [];
    if (hooks.length) {
      const hookBox = document.createElement("div");
      hookBox.className = "dialogue-chapter-hooks";
      const hookTitle = document.createElement("strong");
      hookTitle.textContent = "待续伏笔";
      hookBox.appendChild(hookTitle);
      hooks.forEach((hook) => {
        const item = document.createElement("p");
        item.textContent = String(hook || "");
        hookBox.appendChild(item);
      });
      body.appendChild(hookBox);
    }

    const actions = document.createElement("div");
    actions.className = "dialogue-chapter-actions";
    const endTurnId = String(chapter?.end_turn_id || "").trim();
    if (endTurnId) {
      const locate = document.createElement("button");
      locate.type = "button";
      locate.className = "soft-button";
      locate.textContent = "定位到本幕末尾";
      locate.addEventListener("click", () => focusDialogueChapterEvent(endTurnId));
      actions.appendChild(locate);
    }
    const reopen = document.createElement("button");
    reopen.type = "button";
    reopen.className = "soft-button";
    reopen.textContent = "从本幕重新演绎";
    reopen.addEventListener("click", () => {
      if (typeof window.branchDialogueSessionFromScene === "function") {
        window.branchDialogueSessionFromScene(Number(chapter?.scene_index || 0));
      }
    });
    actions.appendChild(reopen);
    body.appendChild(actions);
    details.appendChild(body);
    list.appendChild(details);
  });
  root.appendChild(list);
}

function renderDialogueEventTimeline(session) {
  const root = el("dialogue-event-timeline");
  if (!root) return;
  const items = Array.isArray(session?.event_timeline) ? session.event_timeline : [];
  if (!items.length) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  root.innerHTML = "";

  const head = document.createElement("div");
  head.className = "dialogue-event-timeline-head";
  const heading = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = "剧情事件时间线";
  const note = document.createElement("small");
  note.textContent = "按人物、地点或事件类型筛选；回溯会保留原会话并创建新分支。";
  heading.append(title, note);
  const count = document.createElement("span");
  count.className = "dialogue-event-count";
  head.append(heading, count);
  root.appendChild(head);

  const participants = [...new Set(items.flatMap((item) => item?.participants || []).map((item) => String(item || "").trim()).filter(Boolean))];
  const locations = [...new Set(items.map((item) => String(item?.location || "").trim()).filter(Boolean))];
  const eventTypes = [...new Set(items.flatMap((item) => item?.event_types || []).map((item) => String(item || "").trim()).filter(Boolean))];
  const filters = document.createElement("div");
  filters.className = "dialogue-event-filters";
  filters.append(
    buildDialogueTimelineSelect("人物", participants.map((value) => ({ value, label: value })), dialogueEventTimelineFilters.participant, (value) => {
      dialogueEventTimelineFilters.participant = value;
      renderDialogueEventTimeline(session);
    }),
    buildDialogueTimelineSelect("地点", locations.map((value) => ({ value, label: value })), dialogueEventTimelineFilters.location, (value) => {
      dialogueEventTimelineFilters.location = value;
      renderDialogueEventTimeline(session);
    }),
    buildDialogueTimelineSelect("类型", eventTypes.map((value) => ({ value, label: dialogueEventTypeLabel(value) })), dialogueEventTimelineFilters.eventType, (value) => {
      dialogueEventTimelineFilters.eventType = value;
      renderDialogueEventTimeline(session);
    })
  );
  root.appendChild(filters);

  const visible = items.filter((item) => {
    const itemParticipants = Array.isArray(item?.participants) ? item.participants : [];
    const itemTypes = Array.isArray(item?.event_types) ? item.event_types : [];
    return (!dialogueEventTimelineFilters.participant || itemParticipants.includes(dialogueEventTimelineFilters.participant))
      && (!dialogueEventTimelineFilters.location || String(item?.location || "").trim() === dialogueEventTimelineFilters.location)
      && (!dialogueEventTimelineFilters.eventType || itemTypes.includes(dialogueEventTimelineFilters.eventType));
  });
  count.textContent = `${visible.length} / ${items.length}`;

  const list = document.createElement("div");
  list.className = "dialogue-event-list";
  if (!visible.length) {
    const empty = document.createElement("p");
    empty.className = "dialogue-event-empty";
    empty.textContent = "当前筛选条件下没有事件。";
    list.appendChild(empty);
  }
  visible.forEach((item) => {
    const card = document.createElement("article");
    card.className = "dialogue-event-item";
    card.setAttribute("data-turn-id", String(item?.turn_id || ""));
    if (String(item?.consistency_status || "") === "issue") card.classList.add("has-issue");
    const marker = document.createElement("span");
    marker.className = "dialogue-event-marker";
    marker.textContent = String(item?.turn_number || "•");
    const body = document.createElement("div");
    body.className = "dialogue-event-body";
    const itemTitle = document.createElement("strong");
    itemTitle.textContent = String(item?.title || "剧情推进");
    const meta = document.createElement("small");
    const metaParts = [String(item?.time_hint || "").trim(), String(item?.location || "").trim()].filter(Boolean);
    const typeLabels = (item?.event_types || []).map(dialogueEventTypeLabel).join(" · ");
    meta.textContent = [...metaParts, typeLabels].filter(Boolean).join(" · ");
    body.append(itemTitle);
    if (meta.textContent) body.appendChild(meta);
    const replies = Array.isArray(item?.responses) ? item.responses : [];
    if (replies.length) {
      const details = document.createElement("details");
      const summary = document.createElement("summary");
      summary.textContent = `查看这一轮的 ${replies.length} 条角色回复`;
      details.appendChild(summary);
      replies.forEach((reply) => {
        const line = document.createElement("p");
        line.textContent = `${String(reply?.speaker || "角色")}：${String(reply?.message || "")}`;
        details.appendChild(line);
      });
      body.appendChild(details);
    }
    const actions = document.createElement("div");
    actions.className = "dialogue-event-actions";
    const branchButton = document.createElement("button");
    branchButton.type = "button";
    branchButton.className = "soft-button";
    branchButton.textContent = "回到这里重新演绎";
    branchButton.disabled = !item?.can_branch;
    branchButton.addEventListener("click", () => branchDialogueSessionFromTurn(String(item?.turn_id || ""), branchButton));
    actions.appendChild(branchButton);
    const lockButton = document.createElement("button");
    lockButton.type = "button";
    lockButton.className = "soft-button dialogue-event-mainline-button";
    lockButton.textContent = item?.is_mainline_anchor ? "解除主线锁定" : "锁定为主线事件";
    lockButton.addEventListener("click", () => toggleDialogueMainlineEvent(
      String(item?.turn_id || ""),
      Boolean(item?.is_mainline_anchor),
      lockButton
    ));
    actions.appendChild(lockButton);
    body.appendChild(actions);
    card.append(marker, body);
    list.appendChild(card);
  });
  root.appendChild(list);
}

async function branchDialogueSessionFromTurn(turnId, button) {
  const normalizedTurnId = String(turnId || "").trim();
  if (!currentRunId || !currentDialogueSessionId || !normalizedTurnId) return;
  const sourceSessionId = currentDialogueSessionId;
  const originalText = button?.textContent || "回到这里重新演绎";
  if (button) {
    button.disabled = true;
    button.textContent = "正在创建分支...";
  }
  try {
    const api = await requireWebUiApi();
    const payload = await api.branchDialogueSessionFromTurn(currentRunId, sourceSessionId, normalizedTurnId);
    const branchSessionId = String(payload?.session_id || "").trim();
    if (!branchSessionId || branchSessionId === sourceSessionId) {
      throw new Error("新分支没有创建成功，请稍后重试。");
    }
    closeDialogueMemoryModal();
    await renderDialogueSession(payload);
    const branchTitle = String(payload?.branch_origin?.event_title || "").trim();
    setDialogueSessionSuccess(
      branchTitle ? `已从“${branchTitle}”切换到新分支。` : "已切换到新分支。",
      "原会话保持不变；接下来发送的内容只会推进当前新分支。"
    );
  } catch (error) {
    setDialogueSessionFailure(error.message || "剧情节点回溯失败。", "原会话没有变化，可以稍后重试。", false);
  } finally {
    if (button?.isConnected && button.textContent === "正在创建分支...") {
      button.disabled = false;
      button.textContent = originalText;
    }
  }
}

function createRelationChartNode(name, attributes = {}) {
  const node = document.createElementNS("http://www.w3.org/2000/svg", name);
  Object.entries(attributes).forEach(([key, value]) => node.setAttribute(key, String(value)));
  return node;
}

function renderDialogueRelationEvolution(session) {
  const root = el("dialogue-relation-evolution");
  if (!root) return;
  const timelines = Array.isArray(session?.relation_timeline) ? session.relation_timeline : [];
  if (!timelines.length) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  root.innerHTML = "";
  if (!timelines.some((item) => String(item?.pair_key || "") === selectedDialogueRelationPair)) {
    selectedDialogueRelationPair = String(timelines[0]?.pair_key || "");
  }
  const timeline = timelines.find((item) => String(item?.pair_key || "") === selectedDialogueRelationPair) || timelines[0];

  const head = document.createElement("div");
  head.className = "dialogue-relation-evolution-head";
  const heading = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = "关系演化";
  const note = document.createElement("small");
  note.textContent = "查看关系如何变化，以及每个转折由哪句互动触发。";
  heading.append(title, note);
  const controls = document.createElement("div");
  controls.className = "dialogue-relation-controls";
  const select = document.createElement("select");
  timelines.forEach((item) => {
    const option = document.createElement("option");
    option.value = String(item?.pair_key || "");
    option.textContent = String(item?.label || item?.pair_key || "人物关系");
    select.appendChild(option);
  });
  select.value = String(timeline?.pair_key || "");
  select.addEventListener("change", () => {
    selectedDialogueRelationPair = select.value;
    renderDialogueRelationEvolution(session);
  });
  const lockButton = document.createElement("button");
  lockButton.type = "button";
  lockButton.className = "soft-button dialogue-relation-lock";
  lockButton.textContent = timeline?.locked ? "解除锁定" : "锁定关系";
  lockButton.title = timeline?.locked ? "允许后续剧情继续改变这组关系" : "后续剧情仍会记录，但不再自动修改关系数值";
  lockButton.addEventListener("click", () => updateDialogueRelationLock(timeline, lockButton));
  controls.append(select, lockButton);
  head.append(heading, controls);
  root.appendChild(head);

  const metricConfig = [
    ["trust", "信任", "#6688a6"],
    ["affection", "好感", "#bb7e79"],
    ["hostility", "敌意", "#9b625c"],
    ["ambiguity", "摇摆", "#9a83a7"],
  ];
  const current = timeline?.current || {};
  const metrics = document.createElement("div");
  metrics.className = "dialogue-relation-metrics";
  metricConfig.forEach(([field, label, color]) => {
    const item = document.createElement("span");
    item.style.setProperty("--relation-color", color);
    item.textContent = `${label} ${Number(current?.[field] || 0)}`;
    metrics.appendChild(item);
  });
  if (timeline?.locked) {
    const locked = document.createElement("span");
    locked.className = "is-locked";
    locked.textContent = "已锁定";
    metrics.appendChild(locked);
  }
  root.appendChild(metrics);

  const points = Array.isArray(timeline?.points) ? timeline.points : [];
  const width = 640;
  const height = 190;
  const padding = { left: 30, right: 16, top: 14, bottom: 26 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const svg = createRelationChartNode("svg", {
    class: "dialogue-relation-chart",
    viewBox: `0 0 ${width} ${height}`,
    role: "img",
    "aria-label": `${timeline?.label || "人物关系"}变化曲线`,
  });
  [0, 5, 10].forEach((value) => {
    const y = padding.top + plotHeight - (value / 10) * plotHeight;
    svg.appendChild(createRelationChartNode("line", { x1: padding.left, y1: y, x2: width - padding.right, y2: y, class: "relation-grid-line" }));
    const label = createRelationChartNode("text", { x: padding.left - 7, y: y + 3, class: "relation-axis-label", "text-anchor": "end" });
    label.textContent = String(value);
    svg.appendChild(label);
  });
  metricConfig.forEach(([field, , color]) => {
    const coordinates = points.map((point, index) => {
      const x = padding.left + (points.length <= 1 ? 0 : (index / (points.length - 1)) * plotWidth);
      const value = Math.max(0, Math.min(10, Number(point?.values?.[field] || 0)));
      const y = padding.top + plotHeight - (value / 10) * plotHeight;
      return { x, y };
    });
    if (coordinates.length) {
      svg.appendChild(createRelationChartNode("polyline", {
        points: coordinates.map((point) => `${point.x},${point.y}`).join(" "),
        fill: "none",
        stroke: color,
        "stroke-width": 2.4,
        "stroke-linecap": "round",
        "stroke-linejoin": "round",
      }));
      coordinates.forEach((point) => svg.appendChild(createRelationChartNode("circle", { cx: point.x, cy: point.y, r: 2.5, fill: color })));
    }
  });
  points.forEach((point, index) => {
    const x = padding.left + (points.length <= 1 ? 0 : (index / (points.length - 1)) * plotWidth);
    const label = createRelationChartNode("text", { x, y: height - 8, class: "relation-axis-label", "text-anchor": "middle" });
    label.textContent = index === 0 ? "初始" : String(point?.turn_number || index);
    svg.appendChild(label);
  });
  root.appendChild(svg);

  const changePoints = points.filter((point, index) => index > 0 && Object.values(point?.changes || {}).some((value) => Number(value || 0) !== 0));
  const reasons = document.createElement("div");
  reasons.className = "dialogue-relation-reasons";
  const reasonTitle = document.createElement("strong");
  reasonTitle.textContent = "关键转折";
  reasons.appendChild(reasonTitle);
  if (!changePoints.length) {
    const stable = document.createElement("p");
    stable.textContent = "目前关系数值保持稳定，还没有形成明显转折。";
    reasons.appendChild(stable);
  }
  changePoints.slice(-6).reverse().forEach((point) => {
    const row = document.createElement("article");
    const changes = metricConfig.map(([field, label]) => {
      const value = Number(point?.changes?.[field] || 0);
      return value ? `${label}${value > 0 ? "+" : ""}${value}` : "";
    }).filter(Boolean);
    const rowTitle = document.createElement("b");
    rowTitle.textContent = `第 ${point?.turn_number || "?"} 轮 · ${changes.join(" / ")}`;
    const reason = document.createElement("p");
    reason.textContent = String(point?.reason || "本轮互动推动了关系变化");
    row.append(rowTitle, reason);
    if (String(point?.evidence || "").trim()) {
      const evidence = document.createElement("small");
      evidence.textContent = `依据：${String(point.evidence)}`;
      row.appendChild(evidence);
    }
    reasons.appendChild(row);
  });
  root.appendChild(reasons);
}

function renderDialogueCharacterGrowth(session) {
  const root = el("dialogue-character-growth");
  if (!root) return;
  const arcs = Array.isArray(session?.character_arcs)
    ? session.character_arcs.filter((item) => Array.isArray(item?.points) && item.points.length)
    : [];
  if (!arcs.length) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  root.innerHTML = "";
  if (!arcs.some((item) => String(item?.name || "") === selectedDialogueCharacterArc)) {
    selectedDialogueCharacterArc = String(arcs[0]?.name || "");
  }
  const arc = arcs.find((item) => String(item?.name || "") === selectedDialogueCharacterArc) || arcs[0];

  const head = document.createElement("div");
  head.className = "dialogue-character-growth-head";
  const heading = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = "人物成长轨迹";
  const note = document.createElement("small");
  note.textContent = "追踪目标、立场和情绪为何发生变化。";
  heading.append(title, note);
  const select = document.createElement("select");
  arcs.forEach((item) => {
    const option = document.createElement("option");
    option.value = String(item?.name || "");
    option.textContent = String(item?.name || "人物");
    select.appendChild(option);
  });
  select.value = String(arc?.name || "");
  select.addEventListener("change", () => {
    selectedDialogueCharacterArc = select.value;
    renderDialogueCharacterGrowth(session);
  });
  head.append(heading, select);
  root.appendChild(head);

  const current = arc?.current || {};
  const state = document.createElement("div");
  state.className = "dialogue-character-current-state";
  [
    ["情绪", current?.mood],
    ["立场", current?.interaction_state],
    ["当前目标", current?.focus],
    ["关注", current?.last_target],
  ].forEach(([label, value]) => {
    const normalized = String(value || "").trim();
    if (!normalized) return;
    const chip = document.createElement("span");
    chip.textContent = `${label} · ${normalized}`;
    state.appendChild(chip);
  });
  if (!state.childElementCount) {
    const empty = document.createElement("span");
    empty.textContent = "人物状态仍在形成中";
    state.appendChild(empty);
  }
  root.appendChild(state);

  const summary = document.createElement("p");
  summary.className = "dialogue-character-growth-summary";
  summary.textContent = String(arc?.growth_summary || "尚未记录到明显变化。");
  root.appendChild(summary);

  const timeline = document.createElement("div");
  timeline.className = "dialogue-character-growth-timeline";
  const points = Array.isArray(arc?.points) ? arc.points : [];
  points.slice(-8).forEach((point, index) => {
    const row = document.createElement("article");
    row.className = "dialogue-character-growth-point";
    const marker = document.createElement("span");
    marker.className = "dialogue-character-growth-marker";
    marker.textContent = String(Math.max(1, points.length - Math.min(8, points.length) + index + 1));
    const body = document.createElement("div");
    const rowHead = document.createElement("div");
    const rowTitle = document.createElement("strong");
    const changes = Array.isArray(point?.changes) ? point.changes : [];
    rowTitle.textContent = changes.length
      ? changes.map((item) => String(item?.label || "状态")).join("、")
      : "初始状态";
    const inherited = document.createElement("small");
    inherited.textContent = point?.inherited ? "继承自上游分支" : `第 ${point?.turn_number || "?"} 个状态节点`;
    rowHead.append(rowTitle, inherited);
    body.appendChild(rowHead);
    if (changes.length) {
      const changeList = document.createElement("p");
      changeList.className = "dialogue-character-growth-changes";
      changeList.textContent = changes.map((item) => {
        const before = String(item?.before || "未记录");
        const after = String(item?.after || "未记录");
        return `${String(item?.label || "状态")}：${before} → ${after}`;
      }).join("；");
      body.appendChild(changeList);
    }
    const reason = document.createElement("p");
    reason.textContent = `变化原因：${String(point?.reason || "这一轮互动推动了人物状态。")}`;
    body.appendChild(reason);
    const turnId = String(point?.turn_id || "").trim();
    if (turnId) {
      const locate = document.createElement("button");
      locate.type = "button";
      locate.className = "soft-button";
      locate.textContent = "定位剧情事件";
      locate.addEventListener("click", () => focusDialogueChapterEvent(turnId));
      body.appendChild(locate);
    }
    row.append(marker, body);
    timeline.appendChild(row);
  });
  root.appendChild(timeline);
}

async function updateDialogueRelationLock(timeline, button) {
  const pairKey = String(timeline?.pair_key || "").trim();
  if (!currentRunId || !currentDialogueSessionId || !pairKey) return;
  if (button) button.disabled = true;
  try {
    const api = await requireWebUiApi();
    const payload = await api.updateDialogueRelationLock(currentRunId, currentDialogueSessionId, pairKey, !timeline?.locked);
    await renderDialogueSession(payload);
    setDialogueSessionSuccess(timeline?.locked ? "关系已解除锁定。" : "关系已锁定。", timeline?.locked ? "后续剧情可以继续改变关系数值。" : "后续剧情仍会记录，但不会自动改变这组关系数值。");
  } catch (error) {
    setDialogueSessionFailure(error.message || "关系锁定状态更新失败。", "当前关系状态没有变化。", false);
    if (button) button.disabled = false;
  }
}

function renderDialogueSceneTimeline(session) {
  const root = el("dialogue-scene-timeline");
  if (!root) return;
  const items = Array.isArray(session?.scene_history) ? session.scene_history : [];
  if (!items.length) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  root.innerHTML = "";

  const head = document.createElement("div");
  head.className = "dialogue-scene-timeline-head";
  const title = document.createElement("strong");
  title.textContent = "场景时间线";
  const note = document.createElement("small");
  note.textContent = "这一局从哪一幕走到哪一幕，会在这里顺着记下来。";
  head.appendChild(title);
  head.appendChild(note);
  root.appendChild(head);

  const list = document.createElement("div");
  list.className = "dialogue-scene-timeline-list";
  items.forEach((item, index) => {
    const card = document.createElement("article");
    card.className = "dialogue-scene-timeline-item";
    card.tabIndex = 0;
    if (String(item?.is_current || "").trim()) {
      card.classList.add("is-current");
    }
    const strong = document.createElement("strong");
    const titleText = String(item?.title || "").trim() || `第 ${index + 1} 幕`;
    const location = String(item?.location || "").trim();
    strong.textContent = location ? `${titleText} · ${location}` : titleText;
    card.appendChild(strong);
    const atmosphere = String(item?.atmosphere || "").trim();
    if (atmosphere) {
      const copy = document.createElement("p");
      copy.textContent = atmosphere;
      card.appendChild(copy);
    }
    const transition = String(item?.transition_message || "").trim();
    if (transition) {
      const transitionNode = document.createElement("small");
      transitionNode.textContent = `转场提示：${transition}`;
      card.appendChild(transitionNode);
    }
    const actions = document.createElement("div");
    actions.className = "dialogue-scene-timeline-actions";
    const branchButton = document.createElement("button");
    branchButton.type = "button";
    branchButton.className = "soft-button";
    branchButton.textContent = "从这里重开";
    branchButton.addEventListener("click", (event) => {
      event.stopPropagation();
      if (typeof window.branchDialogueSessionFromScene === "function") {
        window.branchDialogueSessionFromScene(index);
      }
    });
    actions.appendChild(branchButton);
    card.appendChild(actions);
    card.addEventListener("click", () => {
      if (typeof window.applyDialogueSceneTimelineEntry === "function") {
        window.applyDialogueSceneTimelineEntry(item);
      }
    });
    card.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      event.preventDefault();
      if (typeof window.applyDialogueSceneTimelineEntry === "function") {
        window.applyDialogueSceneTimelineEntry(item);
      }
    });
    list.appendChild(card);
  });
  root.appendChild(list);
}

function buildDialogueMemoryClipboardText(session) {
  if (!session) return "";
  const storyRecap = String(session?.story_recap?.share_text || "").trim();
  if (storyRecap) return storyRecap;
  const snapshot = buildDialogueMemorySnapshot(session);
  const participants = Array.isArray(session?.session_card?.participants) ? session.session_card.participants : [];
  const participantText = participants.length ? joinCharacters(participants) : "未记录";
  return [
    `【本局记忆】`,
    `模式：${snapshot.modeLabel}`,
    `同席：${participantText}`,
    `本局状态：${buildDialogueStateSnapshot(session).pills.map((item) => item.text).join(" / ") || "暂无"}`,
    `场景回顾：${snapshot.recap}`,
    `人物动向：${snapshot.cast}`,
    `关系变化：${snapshot.relation}`,
    `你的位置：${snapshot.perspective}`,
    `场景框架：${snapshot.scene}`,
    `当前地点：${snapshot.location}`,
    `当前同行：${snapshot.companions}`,
    `待完成承诺：${snapshot.commitments}`,
    `世界状态：${snapshot.world}`,
    `更新时间：${snapshot.updated}`,
  ].join("\n");
}

async function copyDialogueMemorySummary() {
  if (!currentDialogueSession) return;
  const button = el("dialogue-memory-copy-button");
  const status = el("dialogue-memory-copy-status");
  const original = button?.textContent || "复制摘要";
  const text = buildDialogueMemoryClipboardText(currentDialogueSession);
  if (!text) return;
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const textarea = document.createElement("textarea");
      textarea.value = text;
      textarea.setAttribute("readonly", "readonly");
      textarea.style.position = "fixed";
      textarea.style.left = "-9999px";
      document.body.appendChild(textarea);
      textarea.select();
      const ok = document.execCommand("copy");
      textarea.remove();
      if (!ok) {
        throw new Error("copy_failed");
      }
    }
    if (button) {
      button.textContent = "已复制";
      window.setTimeout(() => {
        if (button) button.textContent = original;
      }, 1200);
    }
    if (status) status.textContent = "已复制";
    window.setTimeout(() => {
      if (status) status.textContent = "";
    }, 1600);
  } catch (_error) {
    if (button) {
      button.textContent = "复制失败";
      window.setTimeout(() => {
        if (button) button.textContent = original;
      }, 1400);
    }
    if (status) status.textContent = "复制失败";
    window.setTimeout(() => {
      if (status) status.textContent = "";
    }, 1600);
  }
}

function toggleDialogueMemory() {
  if (isDialogueMemoryModalOpen()) {
    closeDialogueMemoryModal();
    return;
  }
  openDialogueMemoryModal();
}

function isDialogueMemoryModalOpen() {
  const modal = el("dialogue-memory-modal");
  return Boolean(modal && !modal.classList.contains("hidden"));
}

function openDialogueMemoryModal() {
  const modal = el("dialogue-memory-modal");
  const mount = el("dialogue-memory-modal-mount");
  const root = el("dialogue-memory");
  const body = el("dialogue-memory-body");
  if (!modal || !mount || !root || !body) return;
  root.classList.add("is-collapsed");
  body.classList.remove("hidden");
  if (body.parentElement !== mount) {
    mount.appendChild(body);
  }
  toggle("dialogue-memory-modal", true);
  if (typeof syncModalScrollLock === "function") {
    syncModalScrollLock();
  }
  const toggleButton = el("dialogue-memory-toggle-button");
  if (toggleButton) {
    toggleButton.textContent = "关闭弹窗";
  }
}

function closeDialogueMemoryModal(options = {}) {
  const silent = Boolean(options && options.silent);
  const modal = el("dialogue-memory-modal");
  const root = el("dialogue-memory");
  const body = el("dialogue-memory-body");
  if (root && body && body.parentElement !== root) {
    root.appendChild(body);
    if (!root.classList.contains("is-collapsed")) {
      body.classList.remove("hidden");
    } else {
      body.classList.add("hidden");
    }
  }
  if (modal) {
    toggle("dialogue-memory-modal", false);
  }
  if (typeof syncModalScrollLock === "function") {
    syncModalScrollLock();
  }
  if (!silent) {
    const toggleButton = el("dialogue-memory-toggle-button");
    if (toggleButton) {
      toggleButton.textContent = "弹窗查看";
    }
  }
}

function isDialogueConsistencyModalOpen() {
  const modal = el("dialogue-consistency-modal");
  return Boolean(modal && !modal.classList.contains("hidden"));
}

function openDialogueConsistencyModal() {
  const modal = el("dialogue-consistency-modal");
  if (!modal || !el("dialogue-consistency-modal-mount")?.children.length) return;
  toggle("dialogue-consistency-modal", true);
  if (typeof syncModalScrollLock === "function") syncModalScrollLock();
}

function closeDialogueConsistencyModal() {
  const modal = el("dialogue-consistency-modal");
  if (modal) toggle("dialogue-consistency-modal", false);
  if (typeof syncModalScrollLock === "function") syncModalScrollLock();
}

document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  if (isDialogueConsistencyModalOpen()) {
    closeDialogueConsistencyModal();
    return;
  }
  if (!isDialogueMemoryModalOpen()) return;
  closeDialogueMemoryModal();
});

function buildOptimisticUserTranscriptEntry(session, message, messageKind = "dialogue") {
  const mode = session?.mode || session?.session_card?.mode || "observe";
  const selfInsert = session?.session_card?.self_insert || {};
  const isNarration = ["narration", "plot"].includes(String(messageKind || "").trim());
  const speaker = isNarration
    ? "场景提示"
    : mode === "act"
      ? session?.session_card?.controlled_character || "你"
      : mode === "insert"
        ? selfInsert.display_name || "你"
        : "你";
  const role = isNarration ? "scene" : mode === "observe" ? "director" : "user";
  return { speaker, message, role, messageKind: isNarration ? String(messageKind || "narration").trim() : "dialogue" };
}

function buildFailedSendTranscript(session, message, messageKind, errorMessage) {
  const transcript = stripFailedSendTranscript(Array.isArray(session?.transcript) ? session.transcript : []);
  const entry = buildOptimisticUserTranscriptEntry(session, message, messageKind);
  entry.sendState = "failed";
  entry.errorMessage = String(errorMessage || "").trim() || "发送失败。";
  transcript.push(entry);
  return transcript;
}

function stripFailedSendTranscript(transcript) {
  return (transcript || []).filter((item) => String(item?.sendState || "").trim() !== "failed");
}

function showDialogueSendErrorModal(message) {
  const modal = el("dialogue-send-error-modal");
  const text = el("dialogue-send-error-text");
  if (!modal || !text) {
    window.alert(String(message || "").trim() || "发送失败。");
    return;
  }
  text.textContent = String(message || "").trim() || "发送失败。";
  if (typeof modal.showModal === "function") {
    modal.showModal();
  } else {
    window.alert(text.textContent);
  }
}

function createTranscriptActionButton(className, label, attrs = {}) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `transcript-action-button ${className}`.trim();
  button.setAttribute("aria-label", label);
  button.title = label;
  Object.entries(attrs).forEach(([key, value]) => {
    if (value == null || value === "") return;
    button.setAttribute(key, String(value));
  });
  return button;
}

function bindTranscriptSendActions(root) {
  if (!root || root.dataset.sendActionsBound === "true") return;
  root.dataset.sendActionsBound = "true";
  root.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    const reviewButton = target.closest("[data-consistency-review]");
    if (reviewButton instanceof HTMLButtonElement) {
      event.preventDefault();
      deepReviewLatestConsistencyTurn(reviewButton);
      return;
    }
    const correctionButton = target.closest("[data-consistency-correct]");
    if (correctionButton instanceof HTMLButtonElement) {
      event.preventDefault();
      correctLatestConsistencyTurn(correctionButton);
      return;
    }
    const retryButton = target.closest("[data-transcript-retry]");
    if (retryButton instanceof HTMLElement) {
      event.preventDefault();
      const message = String(retryButton.dataset.message || "").trim();
      const messageKind = String(retryButton.dataset.messageKind || "dialogue").trim() || "dialogue";
      if (!message) return;
      if (typeof window.handleSendTurn === "function") {
        window.handleSendTurn(message, messageKind);
      }
      return;
    }
    const errorButton = target.closest("[data-transcript-error]");
    if (errorButton instanceof HTMLElement) {
      event.preventDefault();
      showDialogueSendErrorModal(errorButton.dataset.errorMessage || "");
    }
  });
}

function renderTranscript(items) {
  const root = el("dialogue-transcript");
  if (!root) return;
  bindTranscriptSendActions(root);
  root.innerHTML = "";

  (items || []).forEach((item) => {
    const role = item.role || "character";
    const row = document.createElement("article");
    row.className = `transcript-item ${role}`;
    if (String(item.sendState || "").trim() === "failed") {
      row.classList.add("send-failed");
    }

    if (role === "scene" || role === "director" || role === "loading") {
      row.appendChild(createMessageBubble(role, item.message || ""));
      if (String(item.sendState || "").trim() === "failed" && role !== "loading") {
        const actions = document.createElement("div");
        actions.className = "transcript-send-actions";
        const retryButton = createTranscriptActionButton("retry", "重试发送", {
          "data-transcript-retry": "true",
          "data-message": String(item.message || "").trim(),
          "data-message-kind": String(item.messageKind || "dialogue").trim() || "dialogue",
        });
        retryButton.textContent = "↻";
        const errorButton = createTranscriptActionButton("error", "查看失败原因", {
          "data-transcript-error": "true",
          "data-error-message": String(item.errorMessage || "").trim() || "发送失败。",
        });
        errorButton.textContent = "!";
        actions.appendChild(retryButton);
        actions.appendChild(errorButton);
        row.appendChild(actions);
      }
      root.appendChild(row);
      return;
    }

    const inline = document.createElement("div");
    inline.className = `message-inline ${role}`;

    const name = document.createElement("span");
    name.className = "speaker-name";
    name.textContent = item.speaker || (role === "user" ? "你" : "角色");
    name.title = name.textContent;

    const bubble = createMessageBubble(role, item.message || "");
    if (role === "user") {
      inline.appendChild(bubble);
      inline.appendChild(name);
    } else {
      inline.appendChild(name);
      inline.appendChild(bubble);
    }

    row.appendChild(inline);
    if (
      role !== "user" &&
      window.dialogueInnerThoughtsEnabled &&
      String(item.inner_thought || "").trim()
    ) {
      const inner = document.createElement("div");
      inner.className = "message-inner-thought";
      inner.textContent = `内心独白：${String(item.inner_thought || "").trim()}`;
      bubble.appendChild(inner);
    }

    if (String(item.sendState || "").trim() === "failed") {
      const actions = document.createElement("div");
      actions.className = "transcript-send-actions";
      const retryButton = createTranscriptActionButton(
        "retry",
        "重试发送",
        {
          "data-transcript-retry": "true",
          "data-message": String(item.message || "").trim(),
          "data-message-kind": String(item.messageKind || "dialogue").trim() || "dialogue",
        }
      );
      retryButton.textContent = "↻";
      const errorButton = createTranscriptActionButton(
        "error",
        "查看失败原因",
        {
          "data-transcript-error": "true",
          "data-error-message": String(item.errorMessage || "").trim() || "发送失败。",
        }
      );
      errorButton.textContent = "!";
      actions.appendChild(retryButton);
      actions.appendChild(errorButton);
      row.appendChild(actions);
    }

    root.appendChild(row);
  });

  if (typeof window.renderDialogueAssociations === "function") {
    window.renderDialogueAssociations();
  }

  scrollTranscriptToBottom();
}

function renderSessionBooting(mode, participants) {
  const items = [];
  const meta = buildSessionMetaMessage({ mode, participants });
  if (meta) items.push(meta);
  items.push({ role: "loading", message: "正在替你铺开场景与第一轮对白..." });
  setSessionBadge("入场中");
  renderDialogueConsistencyMonitor(null);
  renderTranscript(items);
}

function runDetailActionsForDialogue() {
  const tools = window.__ZAOMENG_UI_BRIDGE_TOOLS__ || {};
  if (typeof tools.readLegacyActionBridge === "function") {
    return tools.readLegacyActionBridge("__ZAOMENG_RUN_DETAIL_ACTIONS__");
  }
  return window.__ZAOMENG_RUN_DETAIL_ACTIONS__ || {};
}

function renderRunFallbackForDialogue(run) {
  if (!run || typeof run !== "object") {
    return null;
  }
  currentRunId = String(run.run_id || currentRunId || "").trim();
  currentRun = run;
  newRunFlowOpen = false;
  characterOverviewOpen = false;
  currentCharacterOverview = null;
  redistillPanelOpen = false;
  sourceHistoryExpanded = false;
  characterReadinessExpanded = false;
  workSessionPreviewExpanded = false;
  runCreationPending = run.status === "running" && !isRunWorkflowComplete(run);
  if (typeof renderBookshelfDetail === "function") {
    renderBookshelfDetail(run);
  }
  if (typeof syncBookshelfSelection === "function") {
    syncBookshelfSelection();
  }
  if (typeof updateWorkflowState === "function") {
    updateWorkflowState();
  }
  if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState("dialogue-run-rendered-fallback");
  }
  return run;
}

function ensureRunReadyForDialogue(run, options = {}) {
  if (typeof window.__ZAOMENG_APPLY_RUN_VIEW__ === "function") {
    window.__ZAOMENG_APPLY_RUN_VIEW__(run, options);
    return true;
  }
  const actions = runDetailActionsForDialogue();
  if (typeof actions.renderRunView === "function") {
    actions.renderRunView(run, options);
    return true;
  }
  if (typeof window.renderRun === "function") {
    window.renderRun(run, options);
    return true;
  }
  renderRunFallbackForDialogue(run);
  return false;
}

function buildOptimisticTranscript(session, message, messageKind = "dialogue") {
  const transcript = stripFailedSendTranscript(Array.isArray(session?.transcript) ? session.transcript : []);
  transcript.push(buildOptimisticUserTranscriptEntry(session, message, messageKind));
  const loadingMessage = String(messageKind || "").trim() === "plot"
    ? "正在按这个方向推进剧情..."
    : "正在生成回复...";
  transcript.push({ speaker: "", message: loadingMessage, role: "loading" });
  return transcript;
}

function latestSessionSnippetFromTranscript(items) {
  const rows = Array.isArray(items) ? items : [];
  for (let index = rows.length - 1; index >= 0; index -= 1) {
    const entry = rows[index] || {};
    const role = String(entry.role || "").trim();
    const message = String(entry.message || "").trim();
    if (!message) continue;
    if (role === "loading") continue;
    return message;
  }
  return "";
}

async function maybeAutoRecommendNextScene(session) {
  const progress = session?.runtime_state_overview || session?.scene_progress || {};
  const sessionId = String(session?.session_id || "").trim();
  if (!sessionId || !progress?.should_offer_scene_shift) return;
  const button = el("dialogue-live-scene-recommend");
  const select = el("dialogue-live-scene-card");
  if (!button || button.disabled) return;
  if ((select?.options?.length || 0) < 3) return;
  const marker = [
    sessionId,
    String(progress.updated_at || session?.updated_at || "").trim(),
    String(progress.time_hint || "").trim(),
    String(progress.location || "").trim(),
    String(progress.scene_shift_reason || progress.next_hint || "").trim(),
  ].join("::");
  if (!marker || marker === lastAutoSceneRecommendationKey) return;
  lastAutoSceneRecommendationKey = marker;
  try {
    if (typeof window.handleRecommendDialogueSceneCard === "function") {
      await window.handleRecommendDialogueSceneCard();
    }
  } catch (error) {
    lastAutoSceneRecommendationKey = "";
  }
}

function shouldAutoFocusDialogueComposer() {
  const narrowViewport = window.matchMedia
    ? window.matchMedia("(max-width: 768px)").matches
    : window.innerWidth <= 768;
  const coarsePointer = window.matchMedia
    ? window.matchMedia("(pointer: coarse)").matches
    : false;
  return !narrowViewport && !coarsePointer;
}

async function renderDialogueSession(session) {
  if (typeof UI_BRIDGE_TOOLS?.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-local", {
      currentDialogueSessionId: session.session_id || "",
      currentDialogueSession: session,
    });
  } else {
    currentDialogueSessionId = session.session_id || "";
    currentDialogueSession = session;
  }
  const latestSnippet = latestSessionSnippetFromTranscript(session?.transcript);
  if (latestSnippet) {
    rememberRecentSessionSnippet(currentRunId, currentDialogueSessionId, latestSnippet);
  }
  sessionBooting = false;
  setComposerEnabled(true);
  if (typeof syncSuggestButtonVisibility === "function") {
    syncSuggestButtonVisibility(session);
  }
  if (typeof window.syncDialogueMessageKindVisibility === "function") {
    window.syncDialogueMessageKindVisibility(session);
  }
  if (typeof renderObserveQuickReplies === "function") {
    renderObserveQuickReplies(session);
  }
  const statusLine = buildDialogueSessionStatusLine(session);
  setSessionBadge(session.title || "对话中");
  if (typeof setStatus === "function") {
    setStatus("dialogue-session-status", statusLine || "这一幕已经铺好，你可以继续说下去。");
  }
  renderDialogueMemory(session);
  renderDialogueTranscript(session);
  let associationRequest = null;
  if (typeof window.maybeRequestDialogueAssociations === "function") {
    associationRequest = window.maybeRequestDialogueAssociations(session);
  }
  updateWorkflowState();
  if (typeof UI_BRIDGE_TOOLS?.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-rendered", {
      currentDialogueSessionId,
      currentDialogueSession: session,
    });
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState("dialogue-session-rendered", {
      currentDialogueSessionId,
      currentDialogueSession: session,
    });
  }
  scrollTranscriptToBottom();
  void loadRecentSessions()
    .then(() => updateWorkflowState())
    .catch((error) => console.warn("loadRecentSessions failed", error));
  const sceneRecommendationRequest = associationRequest
    ? Promise.resolve(associationRequest).then(() => maybeAutoRecommendNextScene(session))
    : maybeAutoRecommendNextScene(session);
  void sceneRecommendationRequest.catch((error) => {
    console.warn("automatic scene recommendation failed", error);
  });
  if (shouldAutoFocusDialogueComposer()) {
    el("dialogue-message")?.focus();
  }
}

function sessionListItemKey(item) {
  const runId = String(item?.run_id || "").trim();
  const sessionId = String(item?.session_id || "").trim();
  if (!runId || !sessionId) return "";
  return `${runId}::${sessionId}`;
}

function updateSessionSelectionToolbar() {
  const toolbar = el("sidebar-session-toolbar");
  const deleteButton = el("sidebar-session-delete-selected-button");
  const selectButton = el("sidebar-session-select-button");
  const selectAllButton = el("sidebar-session-select-all-button");
  const count = selectedSessionKeys.size;
  if (toolbar) {
    toolbar.hidden = !sessionSelectionMode;
    toolbar.classList.toggle("hidden", !sessionSelectionMode);
  }
  if (deleteButton) {
    deleteButton.disabled = count === 0;
    deleteButton.textContent = count > 0 ? `删除所选 (${count})` : "删除所选";
  }
  if (selectButton) {
    selectButton.textContent = sessionSelectionMode ? "完成" : "选择";
  }
  if (selectAllButton) {
    const visibleKeys = getVisibleSessionKeys();
    const allSelected = visibleKeys.length > 0 && visibleKeys.every((key) => selectedSessionKeys.has(key));
    selectAllButton.textContent = allSelected ? "取消全选" : "全选";
  }
}

function setSessionSelectionMode(enabled) {
  sessionSelectionMode = Boolean(enabled);
  if (!sessionSelectionMode) {
    selectedSessionKeys.clear();
  }
  updateSessionSelectionToolbar();
  loadRecentSessions().catch((error) => console.warn("loadRecentSessions failed", error));
}

function toggleSessionSelectionMode() {
  setSessionSelectionMode(!sessionSelectionMode);
}

function getVisibleSessionKeys() {
  const keys = [];
  document.querySelectorAll("#sidebar-session-list .session-row").forEach((row) => {
    const key = String(row.getAttribute("data-session-key") || "").trim();
    if (key) keys.push(key);
  });
  return keys;
}

function toggleSessionSelection(key) {
  const normalized = String(key || "").trim();
  if (!normalized) return;
  if (selectedSessionKeys.has(normalized)) {
    selectedSessionKeys.delete(normalized);
  } else {
    selectedSessionKeys.add(normalized);
  }
  updateSessionSelectionToolbar();
  document.querySelectorAll("#sidebar-session-list .session-row").forEach((row) => {
    const rowKey = String(row.getAttribute("data-session-key") || "").trim();
    const checkbox = row.querySelector(".session-select-checkbox");
    if (checkbox instanceof HTMLInputElement) {
      checkbox.checked = selectedSessionKeys.has(rowKey);
    }
    row.classList.toggle("session-row-selected", selectedSessionKeys.has(rowKey));
  });
}

function toggleAllVisibleSessions() {
  const visibleKeys = getVisibleSessionKeys();
  if (!visibleKeys.length) return;
  const allSelected = visibleKeys.every((key) => selectedSessionKeys.has(key));
  if (allSelected) {
    visibleKeys.forEach((key) => selectedSessionKeys.delete(key));
  } else {
    visibleKeys.forEach((key) => selectedSessionKeys.add(key));
  }
  updateSessionSelectionToolbar();
  document.querySelectorAll("#sidebar-session-list .session-row").forEach((row) => {
    const rowKey = String(row.getAttribute("data-session-key") || "").trim();
    const checkbox = row.querySelector(".session-select-checkbox");
    if (checkbox instanceof HTMLInputElement) {
      checkbox.checked = selectedSessionKeys.has(rowKey);
    }
    row.classList.toggle("session-row-selected", selectedSessionKeys.has(rowKey));
  });
}

async function deleteSelectedSessions() {
  if (!selectedSessionKeys.size) return;
  const count = selectedSessionKeys.size;
  const confirmed = await (typeof showAppConfirm === "function"
    ? showAppConfirm({
        title: "删除会话",
        message: `确定删除选中的 ${count} 个会话吗？删除后无法恢复。`,
        confirmText: "删除",
        cancelText: "取消",
        danger: true,
      })
    : Promise.resolve(window.confirm(`确定删除选中的 ${count} 个会话吗？`)));
  if (!confirmed) return;
  const items = [...selectedSessionKeys].map((key) => {
    const [run_id, session_id] = key.split("::");
    return { run_id, session_id };
  });
  const currentKey = sessionListItemKey({
    run_id: currentRunId,
    session_id: currentDialogueSessionId,
  });
  try {
    await apiJson(
      "/api/web/sessions",
      {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ items }),
      },
      "删除会话失败。"
    );
    if (currentKey && selectedSessionKeys.has(currentKey)) {
      resetDialogueView();
      updateWorkflowState();
    }
    selectedSessionKeys.clear();
    sessionSelectionMode = false;
    updateSessionSelectionToolbar();
    await loadRecentSessions();
  } catch (error) {
    window.alert(error.message || "删除会话失败。");
  }
}

function bindSessionSelectionControls() {
  const selectButton = el("sidebar-session-select-button");
  if (selectButton && !selectButton.dataset.bound) {
    selectButton.dataset.bound = "1";
    selectButton.addEventListener("click", () => {
      setSessionSelectionMode(!sessionSelectionMode);
    });
  }
  const selectAllButton = el("sidebar-session-select-all-button");
  if (selectAllButton && !selectAllButton.dataset.bound) {
    selectAllButton.dataset.bound = "1";
    selectAllButton.addEventListener("click", () => toggleAllVisibleSessions());
  }
  const deleteButton = el("sidebar-session-delete-selected-button");
  if (deleteButton && !deleteButton.dataset.bound) {
    deleteButton.dataset.bound = "1";
    deleteButton.addEventListener("click", () => {
      deleteSelectedSessions().catch((error) => console.warn("deleteSelectedSessions failed", error));
    });
  }
  const cancelButton = el("sidebar-session-cancel-select-button");
  if (cancelButton && !cancelButton.dataset.bound) {
    cancelButton.dataset.bound = "1";
    cancelButton.addEventListener("click", () => setSessionSelectionMode(false));
  }
  updateSessionSelectionToolbar();
}

async function loadRecentSessions() {
  const root = el("sidebar-session-list");
  if (!root) return;
  bindSessionSelectionControls();
  const requestId = ++recentSessionsRequestId;
  const data = await apiJson("/api/web/sessions");
  if (requestId !== recentSessionsRequestId) return;

  const deduped = [];
  const seen = new Set();
  for (const item of data.items || []) {
    const key = `${item.run_id || ""}::${item.session_id || ""}`;
    if (seen.has(key)) continue;
    seen.add(key);
    deduped.push(item);
  }
  recentSessionsCache = deduped;
  if (currentRun && typeof renderWorkSessionPreview === "function") {
    renderWorkSessionPreview(currentRun);
  }

  root.innerHTML = "";
  if (!deduped.length) {
    root.innerHTML = '<p class="sidebar-text">还没有停留下来的篇章。</p>';
    updateSessionSelectionToolbar();
    return;
  }

  const grouped = new Map();
  deduped.slice(0, 24).forEach((item) => {
    const novelId = normalizeNovelTitle(item.novel_id) || "未命名小说";
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
      const itemKey = sessionListItemKey(item);
      const row = document.createElement("div");
      row.className = "session-row";
      if (sessionSelectionMode && selectedSessionKeys.has(itemKey)) {
        row.classList.add("session-row-selected");
      }
      row.setAttribute("data-session-key", itemKey);
      row.style.position = "relative";
      row.style.display = "block";
      row.style.minWidth = "0";

      const button = document.createElement("button");
      button.className = "session-item";
      button.type = "button";
      button.setAttribute("data-run-id", item.run_id || "");
      button.setAttribute("data-session-id", item.session_id || "");
      button.style.display = "grid";
      button.style.gap = "0.25rem";
      button.style.width = "100%";
      button.style.minWidth = "0";
      button.style.padding = "0.8rem 0.9rem";
      button.style.paddingRight = sessionSelectionMode ? "0.9rem" : "2.8rem";
      button.style.paddingLeft = sessionSelectionMode ? "2.2rem" : "0.9rem";
      button.style.textAlign = "left";
      button.style.overflow = "hidden";
      const title = document.createElement("span");
      title.className = "session-title";
      title.textContent = item.title || "未命名会话";
      title.style.display = "block";
      title.style.width = "100%";
      title.style.maxWidth = "100%";
      title.style.whiteSpace = "nowrap";
      title.style.overflow = "hidden";
      title.style.textOverflow = "ellipsis";
      title.style.color = "var(--ink)";
      title.style.fontSize = "0.84rem";
      title.style.fontWeight = "700";
      title.style.lineHeight = "1.42";

      const mode = document.createElement("span");
      mode.className = "session-mode";
      mode.textContent = item.mode_display || humanizeMode(item.mode) || "-";
      mode.style.display = "block";
      mode.style.maxWidth = "100%";
      mode.style.whiteSpace = "nowrap";
      mode.style.overflow = "hidden";
      mode.style.textOverflow = "ellipsis";
      mode.style.color = "var(--accent-strong)";
      mode.style.fontSize = "0.7rem";
      mode.style.fontWeight = "500";
      mode.style.lineHeight = "1.35";

      const meta = document.createElement("span");
      meta.className = "session-meta";
      meta.textContent = `${humanizeSessionStatus(item.status)}${formatWeakTime(item.updated_at) ? ` · ${formatWeakTime(item.updated_at)}` : ""}`;
      meta.style.display = "block";
      meta.style.maxWidth = "100%";
      meta.style.whiteSpace = "nowrap";
      meta.style.overflow = "hidden";
      meta.style.textOverflow = "ellipsis";
      meta.style.color = "var(--ink-faint)";
      meta.style.fontSize = "0.7rem";
      meta.style.fontWeight = "400";
      meta.style.lineHeight = "1.35";
      meta.style.opacity = "0.92";

      button.appendChild(title);
      button.appendChild(mode);
      button.appendChild(meta);
      button.addEventListener("click", async () => {
        if (sessionSelectionMode) {
          toggleSessionSelection(itemKey);
          return;
        }
        const previousRunId = currentRunId;
        const previousRun = currentRun;
        const previousSessionId = currentDialogueSessionId;
        const previousSession = currentDialogueSession;
        currentRunId = item.run_id || currentRunId;
        if (typeof UI_BRIDGE_TOOLS?.syncLegacyUiState === "function") {
          UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-selecting", {
            currentRunId,
            currentDialogueSessionId: item.session_id || "",
            currentDialogueSession: null,
          });
        } else {
          currentDialogueSessionId = item.session_id || "";
          currentDialogueSession = null;
        }
        sessionBooting = true;
        if (typeof closeMobileSessionDrawer === "function") {
          closeMobileSessionDrawer();
        }
        setComposerEnabled(false);
        setSessionBadge("入场中");
        renderSessionBooting(item.mode, item.participants || []);
        updateWorkflowState();
        if (typeof UI_BRIDGE_TOOLS?.syncLegacyUiState === "function") {
          UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-booting", {
            currentRunId,
            currentDialogueSessionId,
            currentDialogueSession: null,
          });
        } else if (typeof publishLegacyUiState === "function") {
          publishLegacyUiState("dialogue-session-booting", {
            currentRunId,
            currentDialogueSessionId,
            currentDialogueSession: null,
          });
        }
        try {
          const [run, session] = await Promise.all([
            apiJson(`/api/web/runs/${item.run_id}`),
            apiJson(`/api/web/runs/${item.run_id}/dialogue/sessions/${item.session_id}`),
          ]);
          ensureRunReadyForDialogue(run, { preserveDialogue: true, suppressWorkflowUpdate: true });
          await renderDialogueSession(session);
        } catch (error) {
          currentRunId = previousRunId;
          currentRun = previousRun;
          if (typeof UI_BRIDGE_TOOLS?.syncLegacyUiState === "function") {
            UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-restore-local", {
              currentRunId,
              currentDialogueSessionId: previousSessionId,
              currentDialogueSession: previousSession,
            });
          } else {
            currentDialogueSessionId = previousSessionId;
            currentDialogueSession = previousSession;
          }
          sessionBooting = false;
          if (previousSession) {
            renderDialogueMemory(previousSession);
            renderDialogueTranscript(previousSession);
            setComposerEnabled(true);
            setSessionBadge("对话中");
          } else if (typeof resetDialogueView === "function") {
            resetDialogueView();
          }
          if (typeof updateWorkflowState === "function") {
            updateWorkflowState();
          }
          if (typeof UI_BRIDGE_TOOLS?.syncLegacyUiState === "function") {
            UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-restore", {
              currentRunId,
              currentDialogueSessionId,
              currentDialogueSession: previousSession,
            });
          } else if (typeof publishLegacyUiState === "function") {
            publishLegacyUiState("dialogue-session-restore", {
              currentRunId,
              currentDialogueSessionId,
              currentDialogueSession: previousSession,
            });
          }
          setStatus("dialogue-session-status", error.message || "这段会话暂时没有载入成功。");
        }
      });

      if (sessionSelectionMode) {
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.className = "session-select-checkbox";
        checkbox.checked = selectedSessionKeys.has(itemKey);
        checkbox.setAttribute("aria-label", "选择会话");
        checkbox.addEventListener("click", (event) => {
          event.stopPropagation();
          toggleSessionSelection(itemKey);
        });
        row.appendChild(checkbox);
        row.appendChild(button);
        section.appendChild(row);
        return;
      }

      const removeButton = document.createElement("button");
      removeButton.type = "button";
      removeButton.className = "session-delete-button";
      removeButton.textContent = "×";
      removeButton.title = "删除会话";
      removeButton.setAttribute("aria-label", "删除会话");
      removeButton.style.position = "absolute";
      removeButton.style.top = "0.55rem";
      removeButton.style.right = "0.55rem";
      removeButton.style.minHeight = "28px";
      removeButton.style.width = "28px";
      removeButton.style.padding = "0";
      removeButton.style.opacity = "0";
      removeButton.style.pointerEvents = "none";
      removeButton.style.transform = "translateY(-2px)";
      removeButton.style.transition = "opacity 160ms ease, transform 160ms ease";
      removeButton.addEventListener("click", async (event) => {
        event.stopPropagation();
        const confirmed = await (typeof showAppConfirm === "function"
          ? showAppConfirm({
              title: "删除会话",
              message: "确定删除这个会话吗？删除后无法恢复。",
              confirmText: "删除",
              cancelText: "取消",
              danger: true,
            })
          : Promise.resolve(window.confirm("确定删除这个会话吗？")));
        if (!confirmed) return;
        try {
          await apiJson(
            `/api/web/runs/${item.run_id}/dialogue/sessions/${item.session_id}`,
            { method: "DELETE" },
            "删除失败。"
          );
          if (currentRunId === item.run_id && currentDialogueSessionId === item.session_id) {
            resetDialogueView();
            updateWorkflowState();
          }
          await loadRecentSessions();
        } catch (error) {
          window.alert(error.message || "删除失败。");
        }
      });

      const renameButton = document.createElement("button");
      renameButton.type = "button";
      renameButton.className = "session-rename-button";
      renameButton.textContent = "编辑";
      renameButton.title = "修改会话标题";
      renameButton.setAttribute("aria-label", "修改会话标题");
      renameButton.style.position = "absolute";
      renameButton.style.top = "0.55rem";
      renameButton.style.right = "2.45rem";
      renameButton.style.minHeight = "28px";
      renameButton.style.padding = "0 0.35rem";
      renameButton.style.opacity = "0";
      renameButton.style.pointerEvents = "none";
      renameButton.style.transform = "translateY(-2px)";
      renameButton.style.transition = "opacity 160ms ease, transform 160ms ease";
      renameButton.addEventListener("click", async (event) => {
        event.stopPropagation();
        const titleValue = window.prompt("会话标题", String(item.title || "").trim());
        if (titleValue === null) return;
        const nextTitle = titleValue.trim();
        if (!nextTitle) {
          window.alert("会话标题不能为空。");
          return;
        }
        try {
          const updated = await window.__ZAOMENG_WEBUI_API__.updateDialogueSessionTitle(
            item.run_id,
            item.session_id,
            nextTitle
          );
          if (currentRunId === item.run_id && currentDialogueSessionId === item.session_id) {
            currentDialogueSession = updated;
            setSessionBadge(updated.title || "对话中");
          }
          await loadRecentSessions();
        } catch (error) {
          window.alert(error.message || "会话标题更新失败。");
        }
      });

      const revealDelete = () => {
        removeButton.style.opacity = "1";
        removeButton.style.pointerEvents = "auto";
        removeButton.style.transform = "translateY(0)";
        renameButton.style.opacity = "1";
        renameButton.style.pointerEvents = "auto";
        renameButton.style.transform = "translateY(0)";
      };
      const hideDelete = () => {
        removeButton.style.opacity = "0";
        removeButton.style.pointerEvents = "none";
        removeButton.style.transform = "translateY(-2px)";
        renameButton.style.opacity = "0";
        renameButton.style.pointerEvents = "none";
        renameButton.style.transform = "translateY(-2px)";
      };
      row.addEventListener("mouseenter", revealDelete);
      row.addEventListener("mouseleave", hideDelete);
      row.addEventListener("focusin", revealDelete);
      row.addEventListener("focusout", hideDelete);

      row.appendChild(button);
      row.appendChild(renameButton);
      row.appendChild(removeButton);
      section.appendChild(row);
    });

    fragment.appendChild(section);
  });

  if (requestId !== recentSessionsRequestId) return;
  root.replaceChildren(fragment);
  applySessionListViewportLock();
  syncSidebarSelection();
  updateSessionSelectionToolbar();
}

async function loadLatestRun() {
  const items = allRuns.length ? allRuns : await loadRunsOverview();
  if (!items.length) return null;
  const preferred =
    items.find((item) => (item.artifact_index?.characters || []).length) ||
    items.find((item) => item.run_id) ||
    null;
  if (!preferred?.run_id) return null;
  return apiJson(`/api/web/runs/${preferred.run_id}`);
}
window.scrollTranscriptToBottom = scrollTranscriptToBottom;
window.applySessionListViewportLock = applySessionListViewportLock;
window.appendStyledMessageContent = appendStyledMessageContent;
window.createMessageBubble = createMessageBubble;
window.buildSessionMetaMessage = buildSessionMetaMessage;
window.renderDialogueTranscript = renderDialogueTranscript;
window.trimInlineMessage = trimInlineMessage;
window.buildDialogueMemorySnapshot = buildDialogueMemorySnapshot;
window.normalizeGenerationCacheMetric = normalizeGenerationCacheMetric;
window.buildGenerationCacheSnapshot = buildGenerationCacheSnapshot;
window.formatGenerationCacheRate = formatGenerationCacheRate;
window.renderDialogueGenerationCacheStats = renderDialogueGenerationCacheStats;
window.renderDialogueMemory = renderDialogueMemory;
window.renderDialogueEventTimeline = renderDialogueEventTimeline;
window.branchDialogueSessionFromTurn = branchDialogueSessionFromTurn;
window.renderDialogueRelationEvolution = renderDialogueRelationEvolution;
window.updateDialogueRelationLock = updateDialogueRelationLock;
window.renderDialogueControlledMemories = renderDialogueControlledMemories;
window.renderDialogueSpeakerBalance = renderDialogueSpeakerBalance;
window.renderDialogueDirectorPanel = renderDialogueDirectorPanel;
window.buildDialogueMemoryClipboardText = buildDialogueMemoryClipboardText;
window.copyDialogueMemorySummary = copyDialogueMemorySummary;
window.openDialogueMemoryModal = openDialogueMemoryModal;
window.closeDialogueMemoryModal = closeDialogueMemoryModal;
window.toggleDialogueMemory = toggleDialogueMemory;
window.openDialogueConsistencyModal = openDialogueConsistencyModal;
window.closeDialogueConsistencyModal = closeDialogueConsistencyModal;
window.renderTranscript = renderTranscript;
window.renderSessionBooting = renderSessionBooting;
window.runDetailActionsForDialogue = runDetailActionsForDialogue;
window.renderRunFallbackForDialogue = renderRunFallbackForDialogue;
window.ensureRunReadyForDialogue = ensureRunReadyForDialogue;
window.buildOptimisticTranscript = buildOptimisticTranscript;
window.buildFailedSendTranscript = buildFailedSendTranscript;
window.stripFailedSendTranscript = stripFailedSendTranscript;
window.showDialogueSendErrorModal = showDialogueSendErrorModal;
window.latestSessionSnippetFromTranscript = latestSessionSnippetFromTranscript;
window.renderDialogueSession = renderDialogueSession;
window.loadRecentSessions = loadRecentSessions;
window.loadLatestRun = loadLatestRun;
window.bindSessionSelectionControls = bindSessionSelectionControls;
window.setSessionSelectionMode = setSessionSelectionMode;
window.toggleSessionSelectionMode = toggleSessionSelectionMode;
window.deleteSelectedSessions = deleteSelectedSessions;
window.toggleAllVisibleSessions = toggleAllVisibleSessions;
bindSessionSelectionControls();
window.__ZAOMENG_DIALOGUE_MODULE__ = {
  initialized: true,
  version: String(window.__ZAOMENG_WEB_UI_VERSION__ || ""),
};
})();

