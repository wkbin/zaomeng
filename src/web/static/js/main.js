(() => {
const existingMainModule = window.__ZAOMENG_MAIN_MODULE__;
if (existingMainModule?.initialized) {
  return;
}
let exportRunPackagePendingId = "";
let dialogueAssociationRequestId = 0;
let dialogueAssociationLastRequestKey = "";
const DIALOGUE_ASSOCIATION_ENABLED_KEY = "zaomeng:dialogue-associations-enabled";
const DIALOGUE_INNER_THOUGHT_ENABLED_KEY = "zaomeng:dialogue-inner-thoughts-enabled";
let dialogueAssociationsEnabled = (() => {
  try {
    return window.localStorage?.getItem(DIALOGUE_ASSOCIATION_ENABLED_KEY) !== "0";
  } catch (_error) {
    return true;
  }
})();
let dialogueInnerThoughtsEnabled = (() => {
  try {
    return window.localStorage?.getItem(DIALOGUE_INNER_THOUGHT_ENABLED_KEY) === "1";
  } catch (_error) {
    return false;
  }
})();
window.dialogueInnerThoughtsEnabled = dialogueInnerThoughtsEnabled;
let dialogueAssociationState = {
  sessionId: "",
  status: "idle",
  options: [],
  selectedLabel: "",
  error: "",
};

const UI_BRIDGE_TOOLS = window.__ZAOMENG_UI_BRIDGE_TOOLS__ || {};

function createFlowStatusActions(statusId, { affectsChatFlow: defaultAffectsChatFlow = false } = {}) {
  return {
    loading(message, nextStep = "") {
      setFlowLoadingStatus(statusId, message, nextStep);
    },
    success(message, nextStep = "") {
      setFlowSuccessStatus(statusId, message, nextStep);
    },
    failure(message, nextStep = "", affectsChatFlow = defaultAffectsChatFlow) {
      setFlowFailureStatus(statusId, message, nextStep, { affectsChatFlow });
    },
  };
}

const {
  loading: setDialogueSessionLoading,
  success: setDialogueSessionSuccess,
  failure: setDialogueSessionFailure,
} = createFlowStatusActions("dialogue-session-status", { affectsChatFlow: true });
const {
  loading: setOpeningPresetLoading,
  success: setOpeningPresetSuccess,
  failure: setOpeningPresetFailure,
} = createFlowStatusActions("opening-preset-status");
const {
  loading: setPersonaReviewLoading,
  success: setPersonaReviewSuccess,
  failure: setPersonaReviewFailure,
} = createFlowStatusActions("persona-review-status");
const {
  loading: setRelationDetailsLoading,
  failure: setRelationDetailsFailure,
} = createFlowStatusActions("relation-details-status");

async function requireWebUiApi(timeoutMs = 4000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt <= timeoutMs) {
    const api = window.__ZAOMENG_WEBUI_API__;
    if (api && typeof api.listOpeningPresets === "function") {
      return api;
    }
    await new Promise((resolve) => window.setTimeout(resolve, 16));
  }
  throw new Error("webui api is not ready.");
}

function applyRunViewSafely(run, options = {}) {
  if (typeof window.__ZAOMENG_APPLY_RUN_VIEW__ === "function") {
    window.__ZAOMENG_APPLY_RUN_VIEW__(run, options);
    return true;
  }
  if (typeof window.renderRun === "function") {
    window.renderRun(run, options);
    return true;
  }
  return false;
}

function readNamedActionBridge(name) {
  if (typeof UI_BRIDGE_TOOLS.readLegacyActionBridge === "function") {
    return UI_BRIDGE_TOOLS.readLegacyActionBridge(name);
  }
  return window[String(name || "").trim()] || {};
}

function characterOverviewActions() {
  return readNamedActionBridge("__ZAOMENG_CHARACTER_OVERVIEW_ACTIONS__");
}

function openCharacterOverviewViaBridge(characterName = "") {
  const target = String(characterName || "").trim();
  if (!target) {
    return Promise.resolve(null);
  }
  const actions = characterOverviewActions();
  if (typeof actions.openCharacterOverview === "function") {
    return Promise.resolve(actions.openCharacterOverview(target));
  }
  return Promise.reject(new Error("人物档案暂时没有载入。"));
}

function openCharacterOverviewIncrementalDistillViaBridge() {
  const actions = characterOverviewActions();
  if (typeof actions.openCharacterOverviewIncrementalDistill === "function") {
    actions.openCharacterOverviewIncrementalDistill();
    return true;
  }
  return false;
}

function openCharacterOverviewSessionModeViaBridge(mode) {
  const actions = characterOverviewActions();
  if (typeof actions.openCharacterOverviewSessionMode === "function") {
    return Promise.resolve(actions.openCharacterOverviewSessionMode(mode));
  }
  return Promise.reject(new Error("当前角色暂时无法直接入场。"));
}

function openCurrentCharacterProfileFileViaBridge() {
  const actions = characterOverviewActions();
  if (typeof actions.openCurrentCharacterProfileFile === "function") {
    return Boolean(actions.openCurrentCharacterProfileFile());
  }
  return false;
}

function openWorkSummaryExportFallback() {
  const target =
    currentRun?.file_urls?.manifest ||
    currentRun?.file_urls?.graph_relations_file ||
    currentRun?.file_urls?.graph_html ||
    currentRun?.file_urls?.graph_svg ||
    "";
  if (!target) {
    setStatus("bookshelf-status", "当前没有可导出的摘要文件。");
    return false;
  }
  window.open(target, "_blank", "noopener,noreferrer");
  return true;
}

function openWorkTimelineFallback() {
  const vueTimelineRoot = el("run-timeline-vue-root");
  const legacyEvents = el("events");
  const timelineSection = document.querySelector(".detail-section-timeline");
  const target =
    (vueTimelineRoot && !vueTimelineRoot.classList.contains("hidden") && vueTimelineRoot) ||
    (legacyEvents && !legacyEvents.classList.contains("hidden") && legacyEvents) ||
    timelineSection ||
    legacyEvents;
  target?.scrollIntoView({ behavior: "smooth", block: "start" });
}

function currentExportRunPackageId() {
  return String(exportRunPackagePendingId || "").trim();
}

function isRunPackageExportPending(runId = "") {
  const pendingId = currentExportRunPackageId();
  const targetId = String(runId || currentRunId || "").trim();
  return Boolean(pendingId) && Boolean(targetId) && pendingId === targetId;
}

function publishRunPackageExportUiState(source = "run-package-export") {
  if (typeof renderBookshelfDetail === "function") {
    renderBookshelfDetail(currentRun);
  }
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState(source, {});
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState(source, {});
  }
}

function setRunPackageExportPending(runId = "", pending = false) {
  const targetId = String(runId || currentRunId || "").trim();
  exportRunPackagePendingId = pending ? targetId : "";
  publishRunPackageExportUiState(pending ? "run-package-export-started" : "run-package-export-finished");
}

function resolveDownloadFilename(response, fallbackName = "zaomeng-run-package.zip") {
  const header = String(response?.headers?.get("content-disposition") || "").trim();
  if (!header) {
    return fallbackName;
  }
  const utf8Match = header.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]).trim() || fallbackName;
    } catch (_) {
      return utf8Match[1].trim() || fallbackName;
    }
  }
  const plainMatch = header.match(/filename="?([^";]+)"?/i);
  if (plainMatch?.[1]) {
    return plainMatch[1].trim() || fallbackName;
  }
  return fallbackName;
}

function downloadBlobFile(blob, filename) {
  const objectUrl = window.URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = filename;
  anchor.rel = "noopener";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => {
    window.URL.revokeObjectURL(objectUrl);
  }, 1500);
}

function buildChatSetupState() {
  const mode = valueOf("dialogue-mode", "observe");
  const isActMode = mode === "act";
  const isInsertMode = mode === "insert";
  return {
    mode,
    participants: String(valueOf("dialogue-participants", "")),
    participantList: charactersOf("dialogue-participants"),
    availableCharacters: getRunCharacterNames(currentRun),
    openingPresetId: selectedOpeningPresetId,
    openingPresets: Array.isArray(openingPresets) ? openingPresets.map((item) => ({
      card_id: item.card_id || "",
      preview: item.preview || {},
      fields: item.fields || {},
    })) : [],
    currentOpeningPreset,
    sceneCardId: selectedSceneCardId,
    currentSceneCard,
    sceneCards: Array.isArray(sceneCards) ? sceneCards.map((item) => ({
      card_id: item.card_id || "",
      preview: item.preview || {},
      fields: item.fields || {},
    })) : [],
    sceneCardRecommendation: currentSceneCardRecommendation,
    controlledCharacter: isActMode ? trimmedValue("dialogue-controlled", "") : "",
    canEditCurrentSceneCard: Boolean(currentSceneCard),
    selfCardId: isInsertMode ? selectedSelfCardId : "",
    currentSelfCard: isInsertMode ? currentSelfCard : null,
    selfCards: isInsertMode ? (Array.isArray(selfCards) ? selfCards.map((item) => ({
      card_id: item.card_id || "",
      preview: item.preview || {},
      fields: item.fields || {},
    })) : []) : [],
    selfName: isInsertMode ? trimmedValue("dialogue-self-name", "") : "",
    selfIdentity: isInsertMode ? trimmedValue("dialogue-self-identity", "") : "",
    selfStyle: isInsertMode ? trimmedValue("dialogue-self-style", "") : "",
    status: String(el("dialogue-session-status")?.textContent || "").trim(),
    canEditCurrentCard: isInsertMode && Boolean(currentSelfCard),
    canEditCurrentOpeningPreset: Boolean(currentOpeningPreset),
  };
}

window.__ZAOMENG_BUILD_CHAT_SETUP_STATE__ = buildChatSetupState;

function publishChatSetupState(source = "chat-setup") {
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState(source, { chatSetup: buildChatSetupState() });
  } else if (typeof UI_BRIDGE_TOOLS.publishLegacyStateSlice === "function") {
    UI_BRIDGE_TOOLS.publishLegacyStateSlice(source, "chatSetup", buildChatSetupState());
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState(source, { chatSetup: buildChatSetupState() });
  }
}

function humanizeChatMode(mode) {
  const value = String(mode || "observe").trim() || "observe";
  if (value === "act") return "化身书中人";
  if (value === "insert") return "以自己入场";
  return "旁观此局";
}

function buildCardSnapshot(card, fallbackCardId = "") {
  if (!card) {
    return {
      card_id: String(fallbackCardId || "").trim(),
      fields: {},
      preview: {},
    };
  }
  return {
    card_id: String(card.card_id || fallbackCardId || "").trim(),
    fields: { ...(card.fields || {}) },
    preview: { ...(card.preview || {}) },
  };
}

function collectOpeningPresetPayload(meta = {}) {
  const mode = valueOf("dialogue-mode", "observe");
  const sceneSnapshot = buildCardSnapshot(currentSceneCard, selectedSceneCardId);
  const selfSnapshot = buildCardSnapshot(currentSelfCard, selectedSelfCardId);
  return {
    title: String(meta.title || "").trim(),
    note: String(meta.note || "").trim(),
    mode,
    participants: charactersOf("dialogue-participants"),
    controlled_character: trimmedValue("dialogue-controlled", ""),
    scene_card_id: selectedSceneCardId,
    scene_card: sceneSnapshot,
    self_card_id: mode === "insert" ? selectedSelfCardId : "",
    self_card: mode === "insert" ? selfSnapshot : {},
    self_name: mode === "insert" ? trimmedValue("dialogue-self-name", "") : "",
    self_identity: mode === "insert" ? trimmedValue("dialogue-self-identity", "") : "",
    self_style: mode === "insert" ? trimmedValue("dialogue-self-style", "") : "",
  };
}

function syncModeFields() {
  const mode = valueOf("dialogue-mode", "observe");
  syncChoiceGroup("dialogue-mode-options", "dialogue-mode");
  if (mode !== "insert") {
    clearChatSetupSelfCardSelection();
  }
  if (el("dialogue-controlled")) el("dialogue-controlled").disabled = mode !== "act";
  if (el("dialogue-self-card")) el("dialogue-self-card").disabled = mode !== "insert";
  if (el("dialogue-self-name")) el("dialogue-self-name").disabled = mode !== "insert";
  if (el("dialogue-self-identity")) el("dialogue-self-identity").disabled = mode !== "insert";
  if (el("dialogue-self-style")) el("dialogue-self-style").disabled = mode !== "insert";
  toggle("controlled-field", mode === "act");
  toggle("self-card-field", mode === "insert");
  toggle("insert-self-fields", mode === "insert");
  toggle("self-card-preview-shell", mode === "insert");
  syncCustomSelect("dialogue-scene-card");
  syncCustomSelect("dialogue-self-card");
  renderSelectedSceneCardPreview(false);
  renderSelectedSelfCardPreview(false);
  if (typeof syncDialogueMessageKindVisibility === "function") {
    syncDialogueMessageKindVisibility({
      mode,
      session_card: { mode },
    });
  }
  publishChatSetupState("chat-setup-mode-updated");
}

function clearChatSetupSelfCardSelection() {
  selectedSelfCardId = "";
  currentSelfCard = null;
  const select = el("dialogue-self-card");
  if (select instanceof HTMLSelectElement) {
    select.value = "";
    syncCustomSelect("dialogue-self-card");
  }
  setValue("dialogue-self-name", "");
  setValue("dialogue-self-identity", "");
  setValue("dialogue-self-style", "");
  renderSelectedSelfCardPreview(false);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("self-card-selection-cleared", {
      selectedSelfCardId,
      currentSelfCard,
    });
  }
}

async function handleModelSettingsSubmit(event) {
  event.preventDefault();
  setFlowLoadingStatus("model-settings-status", "正在把故事声源接进来...");
  try {
    modelSettings = await apiJson(
      "/api/web/settings/model",
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          provider: valueOf("model-provider", ""),
          model: trimmedValue("model-name", ""),
          base_url: trimmedValue("model-base-url", ""),
          api_key: trimmedValue("model-api-key", ""),
          max_tokens: Math.max(0, numberValue("model-max-tokens", 0) || 0),
        }),
      },
      "保存失败。"
    );
    applyModelSettingsView();
    setFlowSuccessStatus("model-settings-status", "故事声源已经接通。", "现在可以开始新的一卷。");
    closeSettingsModal();
    updateWorkflowState();
  } catch (error) {
    setFlowFailureStatus(
      "model-settings-status",
      error.message || "这次连接没有成功。",
      "可以检查模型地址和密钥后重试。",
      { affectsChatFlow: true }
    );
  }
}

async function handleCreateRunSubmit(event) {
  event.preventDefault();
  if (!modelSettings.configured) {
    openSettingsModal();
    setFlowFailureStatus("form-status", "先把故事声源接进来，再开始这一卷。", "先完成模型配置后再发起蒸馏。", { affectsChatFlow: true });
    return;
  }
  const file = el("novel-file")?.files?.[0];
  if (!file) {
    setFlowFailureStatus("form-status", "先放入一本书，故事才会往下走。", "选择一本小说文件后再开始。", { affectsChatFlow: false });
    return;
  }
  const characters = charactersOf("characters");
  if (!characters.length) {
    setFlowFailureStatus("form-status", "至少写下一个你想遇见的人。", "先补一个角色名再开始。", { affectsChatFlow: false });
    return;
  }
  runCreationPending = true;
  updateWorkflowState();
  setButtonBusyState("submit-button", true, { idleText: "开始唤醒人物", busyText: "蒸馏中..." });
  setFlowLoadingStatus(
    "form-status",
    "正在翻检正文，替你把人物请出来...",
    "开始后你可以在书卷页继续看人物蒸馏和关系图进度。"
  );
  try {
    const run = await apiJson(
      "/api/web/runs",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          novel_name: file.name,
          novel_content_base64: await fileToBase64(file),
          characters,
          max_sentences: numberValue("max-sentences", 120),
          max_chars: numberValue("max-chars", 50000),
          auto_run: true,
        }),
      },
      "蒸馏失败。"
    );
    applyRunViewSafely(run);
    await loadRunsOverview();
    setFlowSuccessStatus(
      "form-status",
      "人物整理已经开始，进度会在这里慢慢往前走。",
      "接下来可以盯住书卷页，等人物先落稳几位。"
    );
  } catch (error) {
    runCreationPending = false;
    stopRunPolling();
    updateWorkflowState();
    setFlowFailureStatus(
      "form-status",
      error.message || "这一轮人物整理没有成功。",
      "可以调整正文片段或人物名单后再试。",
      { impact: "这不会影响你已有书架和已经在聊的会话。", affectsChatFlow: false }
    );
  } finally {
    setButtonBusyState("submit-button", false, { idleText: "开始唤醒人物", busyText: "蒸馏中..." });
  }
}

async function handleRedistill() {
  if (!currentRunId) {
    setDistillFlowStatus("redistill-status", {
      message: "先让这一卷成形，再继续补入人物。",
      nextStep: "先选中一卷书后，这里才能继续增量蒸馏。",
    });
    return;
  }
  const characters = charactersOf("redistill-characters");
  const file = el("redistill-novel-file")?.files?.[0];
  const selectedSegment = !file ? getSelectedRedistillSegment() : null;
  if (!characters.length) {
    setDistillFlowStatus("redistill-status", {
      message: "写下想继续补入的人物名字。",
      nextStep: "已有角色可以直接补稳，新角色也可以顺手补进来。",
    });
    return;
  }
  runCreationPending = true;
  updateWorkflowState();
  setButtonBusyState("redistill-button", true, { idleText: "继续整理", busyText: "继续蒸馏中..." });
  setFlowLoadingStatus(
    "redistill-status",
    file
      ? "正在换入新的书段，并继续整理人物..."
      : selectedSegment
        ? "正在切到推荐片段，并继续补稳这一位角色..."
        : "正在沿着这卷书继续往下整理...",
    file
      ? "这一轮会沿着新书段增量补稳人物，不会把已有成果推倒重来。"
      : selectedSegment
        ? "这次会优先补强当前命中的角色窗口。"
        : "这一轮会在已有人物基础上继续补稳，不会重头开始。"
  );
  try {
    const run = await apiJson(
      `/api/web/runs/${currentRunId}/redistill`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          characters,
          novel_name: file?.name || (selectedSegment ? `${String(redistillSuggestionState.character || "redistill").trim()}-推荐片段.txt` : ""),
          novel_content_base64: file ? await fileToBase64(file) : selectedSegment ? textToBase64(selectedSegment.full_text || "") : "",
          max_sentences: numberValue("max-sentences", 120),
          max_chars: numberValue("max-chars", 50000),
        }),
      },
      "继续蒸馏失败。"
    );
    applyRunViewSafely(run);
    await loadRunsOverview();
    if (el("redistill-novel-file")) {
      el("redistill-novel-file").value = "";
    }
    resetRedistillRecommendationState();
    updateRedistillFileView();
    setFlowSuccessStatus(
      "redistill-status",
      file
        ? "新的书段已经接入，这一轮增量整理开始了。"
        : selectedSegment
          ? "推荐片段已经接入，这一轮增量整理开始了。"
          : "新的整理已经开始，人物会陆续补进来。",
      file
        ? "你可以回到书卷页继续看人物和图谱怎么往前长。"
        : selectedSegment
          ? "这位角色会优先吃到这一段证据补料。"
          : "接下来适合继续盯住这卷的进度变化。"
    );
  } catch (error) {
    runCreationPending = false;
    stopRunPolling();
    updateWorkflowState();
    setFlowFailureStatus(
      "redistill-status",
      error.message || "这次继续整理没有接上。",
      "可以换一段更贴近角色的正文，或稍后再试。",
      { impact: "这不会影响这卷已落下的人物、校对结果和当前聊天。", affectsChatFlow: false }
    );
  } finally {
    setButtonBusyState("redistill-button", false, { idleText: "继续整理", busyText: "继续蒸馏中..." });
  }
}

async function handleRedistillRecommend() {
  const character = getRedistillRecommendationTarget();
  if (!currentRunId || !character) {
    setDistillFlowStatus("redistill-status", {
      message: "先只选中一位已有角色，再让我替你找更适合的正文片段。",
      nextStep: "推荐片段更适合做单角色补强，不适合多人一起混找。",
    });
    return;
  }
  redistillSuggestionState.loading = true;
  redistillSuggestionState.runId = currentRunId;
  redistillSuggestionState.character = character;
  redistillSuggestionState.items = [];
  redistillSuggestionState.selectedSegmentId = "";
  renderRedistillRecommendationState(character);
  setButtonBusyState("redistill-recommend-button", true, { idleText: "推荐片段", busyText: "推荐中..." });
  setFlowLoadingStatus(
    "redistill-status",
    `正在替「${character}」翻当前书段，挑适合补稳的正文片段...`,
    "挑完后你可以直接点用推荐片段继续增量蒸馏。"
  );
  try {
    const payload = await apiJson(
      `/api/web/runs/${currentRunId}/redistill/recommend`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ character, max_segments: 3 }),
      },
      "推荐片段失败。"
    );
    redistillSuggestionState.loading = false;
    redistillSuggestionState.runId = currentRunId;
    redistillSuggestionState.character = character;
    redistillSuggestionState.sourceName = String(payload?.source_name || "").trim();
    redistillSuggestionState.weakFieldLabels = Array.isArray(payload?.weak_field_labels) ? payload.weak_field_labels : [];
    redistillSuggestionState.items = Array.isArray(payload?.segments) ? payload.segments : [];
    redistillSuggestionState.selectedSegmentId = "";
    renderRedistillRecommendationState(character);
    setFlowSuccessStatus(
      "redistill-status",
      redistillSuggestionState.items.length
        ? `已经为「${character}」挑出 ${redistillSuggestionState.items.length} 段更适合补料的正文。`
        : `当前书段里暂时没找到更适合「${character}」的推荐窗口。`,
      redistillSuggestionState.items.length
        ? "选中其中一段后，就可以直接继续这位角色的增量蒸馏。"
        : "可以改用新书段，或直接沿用当前正文继续蒸馏。"
    );
  } catch (error) {
    redistillSuggestionState.loading = false;
    redistillSuggestionState.items = [];
    redistillSuggestionState.selectedSegmentId = "";
    renderRedistillRecommendationState(character);
    setFlowFailureStatus(
      "redistill-status",
      error.message || "这次推荐片段没有接上。",
      "可以稍后重试，或直接手动换入更贴近角色的正文。",
      { impact: "这不会影响这卷继续聊天或直接继续增量蒸馏。", affectsChatFlow: false }
    );
  } finally {
    setButtonBusyState("redistill-recommend-button", false, { idleText: "推荐片段", busyText: "推荐中..." });
  }
}

if (typeof UI_BRIDGE_TOOLS.mergeLegacyActionBridge === "function") {
  UI_BRIDGE_TOOLS.mergeLegacyActionBridge("__ZAOMENG_REDISTILL_ACTIONS__", {
    recommend: () => handleRedistillRecommend(),
  });
} else {
  window.__ZAOMENG_REDISTILL_ACTIONS__ = {
    ...(window.__ZAOMENG_REDISTILL_ACTIONS__ || {}),
    recommend: () => handleRedistillRecommend(),
  };
}

async function handleStopRun() {
  if (!currentRunId || !currentRun || currentRun.status !== "running") {
    return;
  }
  if (!window.confirm(`确定先停下《${runNovelTitle(currentRun)}》这一轮蒸馏吗？`)) {
    return;
  }
  setButtonBusyState("detail-stop-run-button", true, { idleText: "停止蒸馏", busyText: "正在停止..." });
  setText("detail-action-note", "正在收束当前步骤，很快就会停下来。", "");
  toggle("detail-action-note", true);
  try {
    const run = await apiJson(
      `/api/web/runs/${currentRunId}/stop`,
      {
        method: "POST",
      },
      "停止蒸馏失败。"
    );
    applyRunViewSafely(run, { preserveDialogue: true });
    setDistillFlowStatus("bookshelf-status", {
      message: `《${runNovelTitle(run)}》已经收到停止请求，正在收住当前步骤。`,
      nextStep: "收住后你可以继续蒸馏，或直接先去聊天和校对人物。",
    });
  } catch (error) {
    setButtonBusyState("detail-stop-run-button", false, { idleText: "停止蒸馏", busyText: "正在停止..." });
    setText("detail-action-note", error.message || "这次停止没有成功。", "");
    toggle("detail-action-note", true);
    setDistillFlowStatus("bookshelf-status", {
      message: error.message || "这次停止没有成功。",
      impact: "这不会影响这一轮继续运行和后续聊天。",
      nextStep: "可以稍后再试停止，或继续观察当前进度。",
    });
  }
}

function handleRedistillAdd() {
  setValue("redistill-characters", "");
  setDistillFlowStatus("redistill-status", {
    message: "写下新人物后，就可以继续整理。",
    nextStep: "这一档更适合把新人物顺手补进当前书卷。",
  });
  updateRedistillPillState();
}

function handleRedistillRefresh() {
  setValue("redistill-characters", joinCharacters(getRunCharacterNames(currentRun)));
  setDistillFlowStatus("redistill-status", {
    message: "当前人物已经带回来了，可以直接重新整理。",
    nextStep: "如果只是补薄弱角色，先只保留最需要补的那位会更稳。",
  });
  updateRedistillPillState();
}

async function handleDialogueSessionSubmit(event) {
  event.preventDefault();
  if (!currentRunId) {
    setDialogueSessionFailure("先让人物从书页里走出来，再进入这一幕。", "先完成一轮人物蒸馏后再开场。", true);
    publishChatSetupState("chat-setup-submit-blocked");
    return;
  }
  try {
    setDialogueMessageKind("dialogue");
    const mode = valueOf("dialogue-mode", "observe");
    const controlledCharacter = trimmedValue("dialogue-controlled", "");
    let participants = charactersOf("dialogue-participants");
    if (mode === "act") {
      if (!controlledCharacter) {
        setDialogueSessionFailure("先写下此刻由你扮演谁。", "填入扮演角色后再开始这一幕。", true);
        publishChatSetupState("chat-setup-submit-blocked");
        return;
      }
      participants = uniq([controlledCharacter, ...participants]);
      setValue("dialogue-participants", joinCharacters(participants));
      updateCharacterPillState();
    }
    if (mode === "observe" && participants.length < 2) {
      setDialogueSessionFailure("群聊至少要选择两位角色。", "请至少选择两位角色后再开始这一幕。", true);
      publishChatSetupState("chat-setup-submit-blocked");
      return;
    }
    if (mode === "act" && participants.length < 2) {
      setDialogueSessionFailure("扮演角色至少要选择两位角色。", "请至少选择两位角色后再开始这一幕。", true);
      publishChatSetupState("chat-setup-submit-blocked");
      return;
    }
    if (mode === "insert" && participants.length < 1) {
      setDialogueSessionFailure("以自己去代入至少要选择一位角色。", "请至少选择一位角色后再开始这一幕。", true);
      publishChatSetupState("chat-setup-submit-blocked");
      return;
    }
    sessionBooting = true;
    setComposerEnabled(false);
    renderSessionBooting(mode, participants);
    updateWorkflowState();
    setDialogueSessionLoading("正在替你铺开这一幕...", "铺开后你就可以继续对话推进。");
    publishChatSetupState("chat-setup-submitting");
    await renderDialogueSession(
      await apiJson(
        `/api/web/runs/${currentRunId}/dialogue/sessions`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            mode,
            participants,
            controlled_character: controlledCharacter,
            scene_card_id: selectedSceneCardId,
            scene_profile: currentSceneCard?.fields || {},
            self_card_id: mode === "insert" ? selectedSelfCardId : "",
            self_profile:
              mode === "insert"
                ? {
                    ...(currentSelfCard?.fields || {}),
                    display_name: trimmedValue("dialogue-self-name", ""),
                    scene_identity: trimmedValue("dialogue-self-identity", ""),
                    interaction_style: trimmedValue("dialogue-self-style", ""),
                  }
                : {},
          }),
        },
        "进入聊天失败。"
      )
    );
    setDialogueSessionSuccess("这一幕已经铺好，你可以继续说下去。", "直接发送下一句，或者先补一句场景提示。");
    publishChatSetupState("chat-setup-submitted");
  } catch (error) {
    sessionBooting = false;
    setComposerEnabled(Boolean(currentDialogueSessionId));
    updateWorkflowState();
    setDialogueSessionFailure(error.message || "这一幕暂时没有铺开。", "可以稍后重试，或先调整入场模式和参与人物。", true);
    publishChatSetupState("chat-setup-submit-failed");
  }
}

const EDITOR_SCHEMAS = window.__ZAOMENG_EDITOR_SCHEMAS__ || {};
const SCENE_CARD_FIELD_DEFINITIONS = Array.isArray(EDITOR_SCHEMAS.SCENE_CARD_FIELDS) ? EDITOR_SCHEMAS.SCENE_CARD_FIELDS : [];
const SCENE_CARD_FIELD_MAP = EDITOR_SCHEMAS.SCENE_CARD_FIELD_MAP instanceof Map ? EDITOR_SCHEMAS.SCENE_CARD_FIELD_MAP : new Map();
const SCENE_CARD_REQUIRED_FIELDS = Array.isArray(EDITOR_SCHEMAS.SCENE_CARD_REQUIRED_FIELDS) ? EDITOR_SCHEMAS.SCENE_CARD_REQUIRED_FIELDS : [];
const SELF_CARD_FIELD_DEFINITIONS = Array.isArray(EDITOR_SCHEMAS.SELF_CARD_ALL_FIELDS) ? EDITOR_SCHEMAS.SELF_CARD_ALL_FIELDS : [];
const SELF_CARD_FIELD_MAP = EDITOR_SCHEMAS.SELF_CARD_FIELD_MAP instanceof Map ? EDITOR_SCHEMAS.SELF_CARD_FIELD_MAP : new Map();
const SELF_CARD_REQUIRED_FIELDS = Array.isArray(EDITOR_SCHEMAS.SELF_CARD_REQUIRED_FIELDS) ? EDITOR_SCHEMAS.SELF_CARD_REQUIRED_FIELDS : [];

function emptyEditorFields(definitions) {
  return Object.fromEntries(definitions.map((item) => [item.field, ""]));
}

function validateSceneCardPayload(fields) {
  const missing = SCENE_CARD_REQUIRED_FIELDS.filter((field) => !String(fields?.[field] || "").trim());
  if (!missing.length) return "";
  const labels = missing.map((field) => SCENE_CARD_FIELD_MAP.get(field)?.label || field);
  return `请先补全这些必填项：${labels.join("、")}`;
}

function buildSceneCardEditorState(editor = currentSceneCardEditor) {
  return {
    cardId: String(editor?.cardId || "").trim(),
    status: String(editor?.status || "").trim(),
    deleteVisible: Boolean(editor?.cardId),
    modalOpen: !el("scene-card-modal")?.classList.contains("hidden"),
    fields: {
      ...emptyEditorFields(SCENE_CARD_FIELD_DEFINITIONS),
      ...(editor?.fields || {}),
    },
  };
}

window.__ZAOMENG_BUILD_SCENE_CARD_EDITOR_STATE__ = buildSceneCardEditorState;

function publishSceneCardEditorState(source = "scene-card-editor", editor = currentSceneCardEditor) {
  currentSceneCardEditor = buildSceneCardEditorState(editor);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState(source, { currentSceneCardEditor });
  } else if (typeof UI_BRIDGE_TOOLS.publishLegacyStateSlice === "function") {
    UI_BRIDGE_TOOLS.publishLegacyStateSlice(source, "currentSceneCardEditor", currentSceneCardEditor);
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState(source, { currentSceneCardEditor });
  }
}

function startSceneCardDraft(fields = {}, status = "") {
  currentSceneCard = { card_id: "", fields: { ...fields } };
  currentSceneCardEditor = buildSceneCardEditorState({ cardId: "", fields, status });
}

function openNewSceneCard() {
  startSceneCardDraft({
    title: trimmedValue("scene-card-preview-title", "") || "",
  }, "你可以手写，也可以让 AI 先随机搭一幕。");
  openSceneCardModal();
  publishSceneCardEditorState("scene-card-new-opened");
}

async function openExistingSceneCard(cardId) {
  if (!cardId) {
    openNewSceneCard();
    return;
  }
  publishSceneCardEditorState("scene-card-loading", {
    ...currentSceneCardEditor,
    status: "正在载入场景卡...",
  });
  try {
    const payload = await apiJson(`/api/web/scene-cards/${encodeURIComponent(cardId)}`, {}, "场景卡载入失败。");
    currentSceneCard = payload;
    currentSceneCardEditor = buildSceneCardEditorState({
      cardId: payload.card_id || "",
      fields: payload.fields || {},
      status: "场景卡已载入。",
    });
    openSceneCardModal();
    publishSceneCardEditorState("scene-card-loaded");
  } catch (error) {
    setDialogueSessionFailure(error.message || "场景卡载入失败。", "可以稍后重试，或先新建一张场景卡。", false);
    publishSceneCardEditorState("scene-card-load-failed");
  }
}

function renderSceneCardOptions(items = sceneCards) {
  const select = el("dialogue-scene-card");
  if (!(select instanceof HTMLSelectElement)) return;
  const trigger = el("dialogue-scene-card-trigger");
  const hint = el("dialogue-scene-card-hint");
  const previous = select.value || selectedSceneCardId || "";
  select.innerHTML = "";
  const blank = document.createElement("option");
  blank.value = "";
  blank.textContent = items.length ? "先挑一张场景卡" : "还没有场景卡，先新建一张";
  select.appendChild(blank);
  (items || []).forEach((item) => {
    const option = document.createElement("option");
    option.value = item.card_id || "";
    const title = item?.preview?.title || item?.fields?.title || item.card_id || "未命名场景卡";
    const location = item?.preview?.location || item?.fields?.location || "";
    option.textContent = location ? `${title} · ${location}` : title;
    select.appendChild(option);
  });
  if ((items || []).some((item) => item.card_id === previous)) {
    select.value = previous;
  } else {
    select.value = "";
  }
  if (trigger instanceof HTMLButtonElement) {
    trigger.disabled = items.length === 0;
  }
  if (hint) {
    hint.textContent = items.length
      ? "不选也能直接开聊，但选卡后会把地点、气氛和推进方向一起带进这一幕。"
      : "你还没有场景卡。先新建一张，后面就能直接把整幕氛围接进来。";
  }
  selectedSceneCardId = select.value;
  syncCustomSelect("dialogue-scene-card");
  syncSelectedSceneCardFromSelect();
}

async function loadSceneCards() {
  const payload = await apiJson("/api/web/scene-cards", {}, "场景卡列表载入失败。");
  sceneCards = Array.isArray(payload?.items) ? payload.items : [];
  renderSceneCardOptions(sceneCards);
  renderDialogueSceneSwitcher(currentDialogueSession);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("scene-cards-loaded", { sceneCards });
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState("scene-cards-loaded");
  }
  return sceneCards;
}

function syncSelectedSceneCardFromSelect() {
  const select = el("dialogue-scene-card");
  const nextId = select?.value || "";
  selectedSceneCardId = nextId;
  currentSceneCard = sceneCards.find((item) => item.card_id === nextId) || null;
  if (currentSceneCardRecommendation && currentSceneCardRecommendation.recommended_card_id === nextId) {
    currentSceneCardRecommendation = {
      ...currentSceneCardRecommendation,
      applied: true,
    };
  }
  renderSelectedSceneCardPreview(false);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("scene-card-selection-changed", {
      selectedSceneCardId,
      currentSceneCard,
      currentSceneCardRecommendation,
    });
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState("scene-card-selection-changed");
  }
  publishChatSetupState("chat-setup-scene-card-selection-changed");
}

function renderSelectedSceneCardPreview(shouldPublish = true) {
  const card = currentSceneCard;
  const hasCards = sceneCards.length > 0;
  const title = card?.preview?.title || card?.fields?.title || "";
  const copy =
    card?.preview?.opening_situation || card?.fields?.opening_situation || card?.preview?.scene_drive || "";
  setText("scene-card-preview-title", title || (hasCards ? "还没有选中场景卡" : "你还没有场景卡"), "");
  setText(
    "scene-card-preview-copy",
    card
      ? copy || "这张卡已经接上，会把这一幕的起势和推进方向一起带进聊天。"
      : hasCards
        ? "选一张卡后，这一幕的地点、气氛和推进方向都会先定下来。"
        : "先新建一张场景卡，后面就可以直接把整幕氛围带进场景。",
    ""
  );
  const root = el("scene-card-preview-pills");
  if (!root) return;
  root.innerHTML = "";
  const preview = card?.preview || {};
  [preview.time_hint, preview.location, preview.atmosphere, preview.scene_drive, preview.expected_rhythm]
    .filter(Boolean)
    .slice(0, 5)
    .forEach((value) => {
      const chip = document.createElement("span");
      chip.textContent = value;
      root.appendChild(chip);
    });
  const recommendationNote = el("scene-card-preview-recommendation");
  if (recommendationNote) {
    const top = Array.isArray(currentSceneCardRecommendation?.items) ? currentSceneCardRecommendation.items[0] : null;
    const topId = String(currentSceneCardRecommendation?.recommended_card_id || "").trim();
    const reasons = Array.isArray(top?.recommendation?.reasons) ? top.recommendation.reasons.filter(Boolean).slice(0, 3) : [];
    if (card && topId && card.card_id === topId && reasons.length) {
      recommendationNote.textContent = `推荐理由：${reasons.join("，")}。`;
      recommendationNote.classList.remove("hidden");
    } else {
      recommendationNote.textContent = "";
      recommendationNote.classList.add("hidden");
    }
  }
  const editButton = el("edit-scene-card-button");
  if (editButton) {
    editButton.disabled = !card;
    editButton.classList.toggle("hidden", !card);
  }
  if (shouldPublish) {
    publishChatSetupState("chat-setup-scene-card-preview-rendered");
  }
}

async function handleSceneCardSelectionChange() {
  syncSelectedSceneCardFromSelect();
}

async function handleOpenNewSceneCard(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  openNewSceneCard();
}

async function handleEditCurrentSceneCard(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  if (!selectedSceneCardId) {
    openNewSceneCard();
    return;
  }
  await openExistingSceneCard(selectedSceneCardId);
}

function renderDialogueSceneSwitcher(session = currentDialogueSession) {
  const shell = el("dialogue-scene-switcher");
  const select = el("dialogue-live-scene-card");
  const status = el("dialogue-live-scene-status");
  const context = el("dialogue-live-scene-context");
  const recommendButton = el("dialogue-live-scene-recommend");
  const shiftHint = el("dialogue-live-scene-shift-hint");
  const shiftCopy = el("dialogue-live-scene-shift-copy");
  const shiftRecommendButton = el("dialogue-live-scene-shift-recommend");
  if (!shell || !select) return;
  const hasSession = Boolean(session?.session_id) && Boolean(currentRunId);
  shell.classList.toggle("hidden", !hasSession);
  if (!hasSession) {
    select.innerHTML = "";
    if (status) status.textContent = "";
    if (context) {
      context.textContent = "";
      context.classList.add("hidden");
    }
    if (recommendButton) recommendButton.disabled = true;
    if (shiftHint) shiftHint.classList.add("hidden");
    if (shiftCopy) shiftCopy.textContent = "";
    if (shiftRecommendButton) shiftRecommendButton.disabled = true;
    renderDialogueSceneRecommendationSummary(null);
    renderDialogueSceneChainSuggestions([], "");
    return;
  }
  if (recommendButton) recommendButton.disabled = sceneCards.length < 2;
  if (shiftRecommendButton) shiftRecommendButton.disabled = sceneCards.length < 2;
  const currentSceneId = String(session?.session_card?.scene_card_id || "").trim();
  const overview = session?.runtime_state_overview || {};
  const shouldShift = Boolean(overview?.should_offer_scene_shift);
  const shiftReason = String(overview?.scene_shift_reason || "").trim();
  const nextHint = String(overview?.next_hint || "").trim();
  const timeHint = String(overview?.time_hint || "").trim();
  const location = String(overview?.location || "").trim();
  const present = Array.isArray(overview?.present) ? overview.present.filter(Boolean).slice(0, 3) : [];
  const offstage = Array.isArray(overview?.offstage) ? overview.offstage.filter(Boolean).slice(0, 2) : [];
  const previous = select.value || currentSceneId;
  select.innerHTML = "";
  const blank = document.createElement("option");
  blank.value = "";
  blank.textContent = sceneCards.length ? "先挑一张场景卡" : "还没有可用场景卡";
  select.appendChild(blank);
  (sceneCards || []).forEach((item) => {
    const option = document.createElement("option");
    option.value = item.card_id || "";
    const title = item?.preview?.title || item?.fields?.title || item.card_id || "未命名场景卡";
    const location = item?.preview?.location || item?.fields?.location || "";
    option.textContent = location ? `${title} · ${location}` : title;
    select.appendChild(option);
  });
  if ((sceneCards || []).some((item) => item.card_id === previous)) {
    select.value = previous;
  } else {
    select.value = currentSceneId;
  }
  if (shiftHint) {
    shiftHint.classList.toggle("hidden", !shouldShift);
  }
  if (shiftCopy) {
    shiftCopy.textContent = shouldShift
      ? (shiftReason || nextHint || "这一拍差不多收住了，可以顺势切到下一幕。")
      : "";
  }
  if (context) {
    const parts = [];
    if (location) parts.push(`地点：${location}`);
    if (timeHint) parts.push(`时间：${timeHint}`);
    if (present.length) parts.push(`在场：${present.join("、")}`);
    if (offstage.length) parts.push(`离场：${offstage.join("、")}`);
    context.textContent = parts.join(" · ");
    context.classList.toggle("hidden", parts.length === 0);
  }
  if (status && !String(status.textContent || "").trim()) {
    if (shouldShift) {
      status.textContent = "这一拍已经接近收束，可以顺势切一张场景卡。";
    } else {
      status.textContent = currentSceneId ? "当前会话已经挂载场景卡，你可以随时切到另一幕。" : "当前会话还没挂场景卡，也可以直接在这里接入一张。";
    }
  }
  renderDialogueSceneRecommendationSummary(session);
  renderDialogueSceneChainSuggestions(currentDialogueSceneChainSuggestions, session?.session_id || "");
}

async function handleApplyDialogueSceneCard(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  if (!currentRunId || !currentDialogueSessionId) return;
  await applySelectedDialogueSceneCard();
}

async function applySelectedDialogueSceneCard(options = {}) {
  if (!currentRunId || !currentDialogueSessionId) return null;
  const select = el("dialogue-live-scene-card");
  const transition = trimmedValue("dialogue-live-scene-transition", "");
  const status = el("dialogue-live-scene-status");
  const button = el("dialogue-live-scene-apply");
  const sceneCardId = String(select?.value || "").trim();
  const waitingText = String(options?.waitingText || "正在把这一幕转过去...").trim() || "正在把这一幕转过去...";
  const successText = String(options?.successText || "新的场景已经接上了。").trim() || "新的场景已经接上了。";
  const autoContinue = Boolean(options?.autoContinue);
  if (!sceneCardId) {
    if (status) status.textContent = "先挑一张要切进去的场景卡。";
    return null;
  }
  if (button) button.disabled = true;
  if (status) status.textContent = waitingText;
  try {
    const payload = await window.__ZAOMENG_WEBUI_API__.switchDialogueSceneCard(currentRunId, currentDialogueSessionId, {
      scene_card_id: sceneCardId,
      scene_profile: currentSceneCard?.card_id === sceneCardId ? (currentSceneCard?.fields || {}) : {},
      transition_message: transition,
      auto_continue: autoContinue,
    });
    clearDialogueSceneRecommendationCache();
    if (el("dialogue-live-scene-transition")) {
      setValue("dialogue-live-scene-transition", "");
    }
    if (status) status.textContent = successText;
    await renderDialogueSession(payload);
    return payload;
  } catch (error) {
    if (status) status.textContent = error.message || "切换场景失败。";
    throw error;
  } finally {
    if (button) button.disabled = false;
  }
}

async function handleRecommendDialogueSceneCard(event, options = {}) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  if (!currentRunId || !currentDialogueSessionId) return;
  const select = el("dialogue-live-scene-card");
  const status = el("dialogue-live-scene-status");
  const button = el("dialogue-live-scene-recommend");
  const shiftButton = el("dialogue-live-scene-shift-recommend");
  const autoApply = Boolean(options?.autoApply);
  const force = Boolean(options?.force);
  if (!sceneCards.length) {
    if (status) status.textContent = "你还没有场景卡，先新建一张再来转场。";
    return;
  }
  if (button) button.disabled = true;
  if (shiftButton) shiftButton.disabled = true;
  if (status) status.textContent = "正在按当前局势替你挑下一幕...";
  try {
    const payload = await fetchDialogueSceneRecommendation({ force });
    const recommendedCardId = String(payload?.recommended_card_id || "").trim();
    const recommendedTransition = String(payload?.recommended_transition_message || "").trim();
    currentDialogueSceneChainSuggestions = Array.isArray(payload?.chain_suggestions) ? payload.chain_suggestions : [];
    currentDialogueSceneChainSessionId = currentDialogueSessionId;
    const topItem = Array.isArray(payload?.items) ? payload.items[0] : null;
    const reasons = Array.isArray(topItem?.recommendation?.reasons) ? topItem.recommendation.reasons.filter(Boolean).slice(0, 3) : [];
    if (!recommendedCardId) {
      if (status) status.textContent = "这一拍暂时没挑出更合适的下一幕。";
      return;
    }
    if (select) {
      select.value = recommendedCardId;
    }
    if (recommendedTransition && el("dialogue-live-scene-transition")) {
      setValue("dialogue-live-scene-transition", recommendedTransition);
    }
    if (status) {
      const sourceLabel = currentDialogueSceneRecommendationMeta?.fromCache ? "（已从缓存取回）" : "（刚更新）";
      status.textContent = reasons.length ? `已替你挑好下一幕${sourceLabel}：${reasons.join("，")}。` : `已替你挑好一张更接戏的场景卡${sourceLabel}。`;
    }
    renderDialogueSceneRecommendationSummary(currentDialogueSession);
    renderDialogueSceneChainSuggestions(currentDialogueSceneChainSuggestions, currentDialogueSessionId);
    if (autoApply) {
      await applySelectedDialogueSceneCard({
        waitingText: "正在顺手把这一幕转到下一拍...",
        successText: "已经顺手切到下一幕并接起新一拍了。",
        autoContinue: true,
      });
    }
  } catch (error) {
    if (status) status.textContent = error.message || "下一幕推荐失败。";
  } finally {
    if (button) button.disabled = sceneCards.length < 2;
    if (shiftButton) shiftButton.disabled = sceneCards.length < 2;
  }
}

function renderDialogueSceneChainSuggestions(chains = [], sessionId = "") {
  const root = el("dialogue-scene-chain-suggestions");
  if (!root) return;
  const activeSessionId = String(sessionId || currentDialogueSessionId || "").trim();
  if (!activeSessionId || activeSessionId !== String(currentDialogueSceneChainSessionId || "").trim() || !Array.isArray(chains) || !chains.length) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  root.classList.remove("hidden");
  root.innerHTML = "";
  chains.slice(0, 3).forEach((chain) => {
    const card = document.createElement("article");
    card.className = "dialogue-scene-chain-card";
    const title = document.createElement("strong");
    const firstScene = Array.isArray(chain?.scenes) ? chain.scenes[0] : null;
    const firstTitle = String(firstScene?.title || "").trim() || "下一幕";
    title.textContent = `后续戏路：先接「${firstTitle}」`;
    card.appendChild(title);
    const copy = document.createElement("p");
    copy.textContent = String(chain?.reason || "").trim() || "这条线可以顺着往下接。";
    card.appendChild(copy);
    const tags = document.createElement("div");
    tags.className = "dialogue-scene-chain-tags";
    (Array.isArray(chain?.scenes) ? chain.scenes : []).slice(0, 3).forEach((scene, index) => {
      const chip = document.createElement("span");
      const label = String(scene?.title || "").trim() || `第 ${index + 1} 幕`;
      const location = String(scene?.location || "").trim();
      chip.textContent = location ? `${label} · ${location}` : label;
      tags.appendChild(chip);
    });
    card.appendChild(tags);
    const actions = document.createElement("div");
    actions.className = "dialogue-scene-chain-actions";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "soft-button";
    button.textContent = "接这条线并切幕";
    button.addEventListener("click", () => {
      applyDialogueSceneChain(chain).catch((error) => {
        setFlowFailureStatus("dialogue-live-scene-status", error.message || "这条线暂时没有接上。", "可以稍后重试，或手动切到目标场景卡。", { affectsChatFlow: false });
      });
    });
    actions.appendChild(button);
    card.appendChild(actions);
    root.appendChild(card);
  });
}

async function applyDialogueSceneChain(chain = {}) {
  const scenes = Array.isArray(chain?.scenes) ? chain.scenes : [];
  const first = scenes[0] || {};
  const sceneCardId = String(first?.card_id || "").trim();
  const transition = String(first?.transition_message || "").trim();
  const select = el("dialogue-live-scene-card");
  const status = el("dialogue-live-scene-status");
  if (!sceneCardId || !select) return false;
  select.value = sceneCardId;
  if (el("dialogue-live-scene-transition")) {
    setValue("dialogue-live-scene-transition", transition);
  }
  if (status) {
    const tailTitles = scenes.slice(1).map((item) => String(item?.title || "").trim()).filter(Boolean);
    status.textContent = tailTitles.length
      ? `正在替你接上这条线，后面还可以顺势转到：${tailTitles.join("、")}。`
      : "正在替你接上这条线。";
  }
  const tailTitles = scenes.slice(1).map((item) => String(item?.title || "").trim()).filter(Boolean);
  await applySelectedDialogueSceneCard({
    waitingText: "正在按这条戏路切到下一幕...",
    successText: tailTitles.length
      ? `这条线已经接上了，后面还可以顺势转到：${tailTitles.join("、")}。`
      : "这条线已经接上了。",
    autoContinue: true,
  });
  return true;
}

function applyDialogueSceneTimelineEntry(entry = {}) {
  const sceneCardId = String(entry?.scene_card_id || "").trim();
  const transitionMessage = String(entry?.transition_message || "").trim();
  const status = el("dialogue-live-scene-status");
  const select = el("dialogue-live-scene-card");
  if (!sceneCardId || !select) return false;
  const matched = sceneCards.find((item) => String(item?.card_id || "").trim() === sceneCardId);
  if (!matched) {
    if (status) status.textContent = "这幕对应的场景卡已经不在卡册里了。";
    return false;
  }
  select.value = sceneCardId;
  if (el("dialogue-live-scene-transition")) {
    setValue("dialogue-live-scene-transition", transitionMessage);
  }
  if (status) {
    const title = matched?.preview?.title || matched?.fields?.title || "这幕";
    status.textContent = transitionMessage ? `已回填「${title}」和当时那句转场提示。` : `已回填「${title}」，你可以再补一句转场提示。`;
  }
  return true;
}

async function branchDialogueSessionFromScene(sceneIndex) {
  const index = Number(sceneIndex);
  if (!currentRunId || !currentDialogueSessionId || !Number.isInteger(index) || index < 0) {
    return;
  }
  setFlowLoadingStatus("dialogue-live-scene-status", "正在从这一幕重新岔开一条新会话...");
  try {
    const api = await requireWebUiApi();
    const payload = await api.branchDialogueSession(currentRunId, currentDialogueSessionId, index);
    await renderDialogueSession(payload);
    setDialogueSessionSuccess("已经从这幕重新开出一条新分支。", "可以继续沿新分支推进。");
    setFlowSuccessStatus("dialogue-live-scene-status", "新的分支会话已经接上。", "可以继续说下一句。");
  } catch (error) {
    setFlowFailureStatus("dialogue-live-scene-status", error.message || "分支会话创建失败。", "可以稍后重试，或继续当前会话。", { affectsChatFlow: true });
  }
}

async function handleRecommendSceneCard(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  if (!sceneCards.length) {
    setDialogueSessionFailure("你还没有场景卡，先新建一张再让我替你挑。", "先新建场景卡后再让系统推荐。", false);
    return;
  }
  setDialogueSessionLoading("正在按这场的角色和入场方式替你挑更合适的场景卡...");
  try {
    const api = await requireWebUiApi();
    const payload = await api.recommendSceneCards({
      mode: valueOf("dialogue-mode", "observe"),
      participants: charactersOf("dialogue-participants"),
    });
    currentSceneCardRecommendation = payload;
    const recommendedId = String(payload?.recommended_card_id || "").trim();
    if (recommendedId) {
      const select = el("dialogue-scene-card");
      if (select) {
        select.value = recommendedId;
        syncCustomSelect("dialogue-scene-card");
      }
      syncSelectedSceneCardFromSelect();
      const top = Array.isArray(payload?.items) ? payload.items[0] : null;
      const reasons = Array.isArray(top?.recommendation?.reasons) ? top.recommendation.reasons.filter(Boolean).slice(0, 3) : [];
      setDialogueSessionSuccess(
        reasons.length ? `已替你挑好场景卡：${reasons.join("，")}。` : "已替你挑好一张更合适的场景卡。",
        "确认后可以直接开场。"
      );
    } else {
      setDialogueSessionSuccess("这一轮没挑出更明确的推荐，你也可以手动选。", "你可以直接手动选一张场景卡。");
    }
    if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
      UI_BRIDGE_TOOLS.syncLegacyUiState("scene-card-recommended", { currentSceneCardRecommendation });
    }
    publishChatSetupState("chat-setup-scene-card-recommended");
  } catch (error) {
    setDialogueSessionFailure(error.message || "场景卡推荐失败。", "可以稍后重试，或手动选择场景卡。", false);
  }
}

function validateSelfCardPayload(fields) {
  const missing = SELF_CARD_REQUIRED_FIELDS.filter((field) => !String(fields?.[field] || "").trim());
  if (!missing.length) return "";
  const labels = missing.map((field) => SELF_CARD_FIELD_MAP.get(field)?.label || field);
  return `请先补全这些必填项：${labels.join("、")}`;
}

function buildSelfCardEditorState(editor = currentSelfCardEditor) {
  return {
    cardId: String(editor?.cardId || "").trim(),
    status: String(editor?.status || "").trim(),
    deleteVisible: Boolean(editor?.cardId),
    modalOpen: !el("self-card-modal")?.classList.contains("hidden"),
    fields: {
      ...emptyEditorFields(SELF_CARD_FIELD_DEFINITIONS),
      ...(editor?.fields || {}),
    },
  };
}

window.__ZAOMENG_BUILD_SELF_CARD_EDITOR_STATE__ = buildSelfCardEditorState;

function publishSelfCardEditorState(source = "self-card-editor", editor = currentSelfCardEditor) {
  currentSelfCardEditor = buildSelfCardEditorState(editor);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState(source, { currentSelfCardEditor });
  } else if (typeof UI_BRIDGE_TOOLS.publishLegacyStateSlice === "function") {
    UI_BRIDGE_TOOLS.publishLegacyStateSlice(source, "currentSelfCardEditor", currentSelfCardEditor);
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState(source, { currentSelfCardEditor });
  }
}

function startSelfCardDraft(fields = {}, status = "") {
  currentSelfCard = { card_id: "", fields: { ...fields } };
  currentSelfCardEditor = buildSelfCardEditorState({ cardId: "", fields, status });
}

function openNewSelfCard() {
  startSelfCardDraft({
    display_name: trimmedValue("dialogue-self-name", "") || "你",
    scene_identity: trimmedValue("dialogue-self-identity", ""),
    interaction_style: trimmedValue("dialogue-self-style", ""),
  }, "你可以手写，也可以让 AI 先随机捏一张。");
  openSelfCardModal();
  publishSelfCardEditorState("self-card-new-opened");
}

async function openExistingSelfCard(cardId) {
  if (!cardId) {
    openNewSelfCard();
    return;
  }
  publishSelfCardEditorState("self-card-loading", {
    ...currentSelfCardEditor,
    status: "正在载入角色卡...",
  });
  try {
    const payload = await apiJson(`/api/web/self-cards/${encodeURIComponent(cardId)}`, {}, "角色卡载入失败。");
    currentSelfCard = payload;
    currentSelfCardEditor = buildSelfCardEditorState({
      cardId: payload.card_id || "",
      fields: payload.fields || {},
      status: "角色卡已载入。",
    });
    openSelfCardModal();
    publishSelfCardEditorState("self-card-loaded");
  } catch (error) {
    setDialogueSessionFailure(error.message || "角色卡载入失败。", "可以稍后重试，或先新建一张角色卡。", false);
    publishSelfCardEditorState("self-card-load-failed");
  }
}

function renderSelfCardOptions(items = selfCards) {
  const select = el("dialogue-self-card");
  if (!(select instanceof HTMLSelectElement)) return;
  const trigger = el("dialogue-self-card-trigger");
  const hint = el("dialogue-self-card-hint");
  const previous = select.value || selectedSelfCardId || "";
  select.innerHTML = "";
  const blank = document.createElement("option");
  blank.value = "";
  blank.textContent = items.length ? "先挑一张角色卡" : "还没有角色卡，先新建一张";
  select.appendChild(blank);
  (items || []).forEach((item) => {
    const option = document.createElement("option");
    option.value = item.card_id || "";
    const displayName = item?.preview?.display_name || item?.fields?.display_name || item.card_id || "未命名角色卡";
    const sceneIdentity = item?.preview?.scene_identity || item?.fields?.scene_identity || "";
    option.textContent = sceneIdentity ? `${displayName} · ${sceneIdentity}` : displayName;
    select.appendChild(option);
  });
  if ((items || []).some((item) => item.card_id === previous)) {
    select.value = previous;
  } else {
    select.value = "";
  }
  if (trigger instanceof HTMLButtonElement) {
    trigger.disabled = items.length === 0;
  }
  if (hint) {
    hint.textContent = items.length
      ? "不选也能手动写，但选卡后会把完整人设一起带进场景。"
      : "你还没有角色卡。先新建一张，后面就能直接选卡入场。";
  }
  selectedSelfCardId = select.value;
  syncCustomSelect("dialogue-self-card");
  syncSelectedSelfCardFromSelect();
}

async function loadSelfCards() {
  const payload = await apiJson("/api/web/self-cards", {}, "角色卡列表载入失败。");
  selfCards = Array.isArray(payload?.items) ? payload.items : [];
  renderSelfCardOptions(selfCards);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("self-cards-loaded", { selfCards });
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState("self-cards-loaded");
  }
  return selfCards;
}

function syncSelectedSelfCardFromSelect() {
  const select = el("dialogue-self-card");
  const nextId = select?.value || "";
  selectedSelfCardId = nextId;
  currentSelfCard = selfCards.find((item) => item.card_id === nextId) || null;
  if (currentSelfCard?.fields) {
    if (el("dialogue-self-name")) setValue("dialogue-self-name", currentSelfCard.fields.display_name || "");
    if (el("dialogue-self-identity")) {
      setValue("dialogue-self-identity", currentSelfCard.fields.scene_identity || currentSelfCard.fields.core_identity || "");
    }
    if (el("dialogue-self-style")) setValue("dialogue-self-style", currentSelfCard.fields.interaction_style || "");
  }
  renderSelectedSelfCardPreview(false);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("self-card-selection-changed", {
      selectedSelfCardId,
      currentSelfCard,
    });
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState("self-card-selection-changed");
  }
  publishChatSetupState("chat-setup-self-card-selection-changed");
}

function renderSelectedSelfCardPreview(shouldPublish = true) {
  const card = currentSelfCard;
  const hasCards = selfCards.length > 0;
  const title = card?.preview?.display_name || card?.fields?.display_name || "";
  const copy =
    card?.preview?.scene_identity || card?.fields?.scene_identity || card?.fields?.core_identity || "";
  setText("self-card-preview-title", title || (hasCards ? "还没有选中角色卡" : "你还没有角色卡"), "");
  setText(
    "self-card-preview-copy",
    card
      ? copy || "这张卡已经接上，会把完整人设带进这场聊天。"
      : hasCards
        ? "选一张卡后，你的身份、气质和说话方式都会一起带进这场聊天。"
        : "先新建一张角色卡，后面就可以直接把完整人设带进场景。",
    ""
  );
  const root = el("self-card-preview-pills");
  if (!root) return;
  root.innerHTML = "";
  const preview = card?.preview || {};
  [preview.core_identity, preview.story_role, preview.temperament_type, preview.speech_style, preview.soul_goal]
    .filter(Boolean)
    .slice(0, 5)
    .forEach((value) => {
      const chip = document.createElement("span");
      chip.textContent = value;
      root.appendChild(chip);
    });
  const editButton = el("edit-self-card-button");
  if (editButton) {
    editButton.disabled = !card;
    editButton.classList.toggle("hidden", !card);
  }
  if (shouldPublish) {
    publishChatSetupState("chat-setup-self-card-preview-rendered");
  }
}

async function handleSelfCardSelectionChange() {
  syncSelectedSelfCardFromSelect();
}

async function handleOpenNewSelfCard(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  openNewSelfCard();
}

async function handleEditCurrentSelfCard(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  if (!selectedSelfCardId) {
    openNewSelfCard();
    return;
  }
  await openExistingSelfCard(selectedSelfCardId);
}

function fillOpeningPresetMetaForm(preset = null) {
  const cardId = preset?.card_id || "";
  setValue("opening-preset-id", cardId);
  setValue("opening-preset-title", preset?.preview?.title || preset?.fields?.title || "");
  setValue("opening-preset-note", preset?.preview?.note || preset?.fields?.note || "");
  const deleteButton = el("delete-opening-preset-button");
  if (deleteButton) {
    deleteButton.disabled = !cardId;
    deleteButton.classList.toggle("hidden", !cardId);
  }
}

function renderOpeningPresetOptions(items = openingPresets) {
  const select = el("dialogue-opening-preset");
  if (!(select instanceof HTMLSelectElement)) return;
  const previous = select.value || selectedOpeningPresetId || "";
  select.innerHTML = "";
  const blank = document.createElement("option");
  blank.value = "";
  blank.textContent = items.length ? "先挑一套开局模板" : "还没有开局模板，先存一套";
  select.appendChild(blank);
  (items || []).forEach((item) => {
    const option = document.createElement("option");
    option.value = item.card_id || "";
    const preview = item.preview || {};
    const title = preview.title || item.fields?.title || item.card_id || "未命名模板";
    const modeLabel = humanizeChatMode(preview.mode || item.fields?.mode || "observe");
    option.textContent = `${title} · ${modeLabel}`;
    select.appendChild(option);
  });
  select.value = (items || []).some((item) => item.card_id === previous) ? previous : "";
  selectedOpeningPresetId = select.value;
  currentOpeningPreset = (items || []).find((item) => item.card_id === selectedOpeningPresetId) || null;
  renderOpeningPresetPreview(false);
}

function renderOpeningPresetPreview(shouldPublish = true) {
  const preset = currentOpeningPreset;
  const hasPresets = openingPresets.length > 0;
  const preview = preset?.preview || {};
  setText("opening-preset-preview-title", preview.title || preset?.fields?.title || (hasPresets ? "还没有选中开局模板" : "你还没有开局模板"), "");
  const summaryBits = [
    humanizeChatMode(preview.mode || preset?.fields?.mode || "observe"),
    Array.isArray(preview.participants) && preview.participants.length ? `同席：${preview.participants.join("、")}` : "",
    preview.scene_title ? `场景：${preview.scene_title}` : "",
    preview.self_name ? `入场：${preview.self_name}` : "",
  ].filter(Boolean);
  setText(
    "opening-preset-preview-copy",
    preset
      ? (preview.note || preset?.fields?.note || summaryBits.join(" · ") || "这套模板已经把入场方式、人物和场景打包好了。")
      : (hasPresets ? "选中一套模板后，就能把整套开局方式一键套进来。" : "先把你喜欢的角色卡、场景卡和入场方式搭好，再存成模板。"),
    ""
  );
  const root = el("opening-preset-preview-pills");
  if (root) {
    root.innerHTML = "";
    summaryBits.slice(0, 4).forEach((value) => {
      const chip = document.createElement("span");
      chip.textContent = value;
      root.appendChild(chip);
    });
  }
  const editButton = el("edit-opening-preset-button");
  const applyButton = el("apply-opening-preset-button");
  const startButton = el("start-opening-preset-button");
  if (editButton) {
    editButton.disabled = !preset;
    editButton.classList.toggle("hidden", !preset);
  }
  if (applyButton) {
    applyButton.disabled = !preset;
    applyButton.classList.toggle("hidden", !preset);
  }
  if (startButton) {
    startButton.disabled = !preset;
    startButton.classList.toggle("hidden", !preset);
  }
  if (shouldPublish) {
    publishChatSetupState("chat-setup-opening-preset-preview-rendered");
  }
}

function syncSelectedOpeningPresetFromSelect() {
  const select = el("dialogue-opening-preset");
  selectedOpeningPresetId = select?.value || "";
  currentOpeningPreset = openingPresets.find((item) => item.card_id === selectedOpeningPresetId) || null;
  renderOpeningPresetPreview(false);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("opening-preset-selection-changed", {
      openingPresets,
      selectedOpeningPresetId,
      currentOpeningPreset,
    });
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState("opening-preset-selection-changed");
  }
  publishChatSetupState("chat-setup-opening-preset-selection-changed");
}

async function loadOpeningPresets() {
  const api = await requireWebUiApi();
  const payload = await api.listOpeningPresets();
  openingPresets = Array.isArray(payload?.items) ? payload.items : [];
  renderOpeningPresetOptions(openingPresets);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("opening-presets-loaded", { openingPresets, currentOpeningPreset, selectedOpeningPresetId });
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState("opening-presets-loaded");
  }
  return openingPresets;
}

function applyPresetSceneCard(presetFields = {}) {
  const sceneId = String(presetFields.scene_card_id || "").trim();
  const sceneSnapshot = buildCardSnapshot(presetFields.scene_card || {}, sceneId);
  const matched = sceneId ? sceneCards.find((item) => item.card_id === sceneId) || null : null;
  const select = el("dialogue-scene-card");
  if (matched) {
    selectedSceneCardId = matched.card_id || "";
    currentSceneCard = matched;
    if (select) select.value = selectedSceneCardId;
    syncCustomSelect("dialogue-scene-card");
    syncSelectedSceneCardFromSelect();
    return;
  }
  selectedSceneCardId = "";
  currentSceneCard = sceneSnapshot.fields && Object.keys(sceneSnapshot.fields).length ? sceneSnapshot : null;
  if (select) {
    select.value = "";
    syncCustomSelect("dialogue-scene-card");
  }
  renderSelectedSceneCardPreview(false);
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("scene-card-selection-changed", {
      selectedSceneCardId,
      currentSceneCard,
      currentSceneCardRecommendation,
    });
  }
}

function applyPresetSelfCard(mode, presetFields = {}) {
  const selfId = mode === "insert" ? String(presetFields.self_card_id || "").trim() : "";
  const selfSnapshot = buildCardSnapshot(presetFields.self_card || {}, selfId);
  const matched = selfId ? selfCards.find((item) => item.card_id === selfId) || null : null;
  const select = el("dialogue-self-card");
  if (matched) {
    selectedSelfCardId = matched.card_id || "";
    currentSelfCard = matched;
    if (select) select.value = selectedSelfCardId;
    syncCustomSelect("dialogue-self-card");
    syncSelectedSelfCardFromSelect();
  } else {
    selectedSelfCardId = "";
    currentSelfCard = mode === "insert" && selfSnapshot.fields && Object.keys(selfSnapshot.fields).length ? selfSnapshot : null;
    if (select) {
      select.value = "";
      syncCustomSelect("dialogue-self-card");
    }
    renderSelectedSelfCardPreview(false);
    if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
      UI_BRIDGE_TOOLS.syncLegacyUiState("self-card-selection-changed", {
        selectedSelfCardId,
        currentSelfCard,
      });
    }
  }
  if (mode === "insert") {
    setValue("dialogue-self-name", presetFields.self_name || currentSelfCard?.fields?.display_name || "");
    setValue("dialogue-self-identity", presetFields.self_identity || currentSelfCard?.fields?.scene_identity || currentSelfCard?.fields?.core_identity || "");
    setValue("dialogue-self-style", presetFields.self_style || currentSelfCard?.fields?.interaction_style || "");
  } else {
    setValue("dialogue-self-name", "");
    setValue("dialogue-self-identity", "");
    setValue("dialogue-self-style", "");
  }
}

function applyOpeningPresetToChatSetup(preset) {
  const fields = preset?.fields || {};
  const mode = String(fields.mode || "observe").trim() || "observe";
  setValue("dialogue-mode", mode);
  setValue("dialogue-participants", Array.isArray(fields.participants) ? fields.participants.join("、") : "");
  setValue("dialogue-controlled", fields.controlled_character || "");
  syncModeFields();
  updateCharacterPillState();
  applyPresetSceneCard(fields);
  applyPresetSelfCard(mode, fields);
  setDialogueSessionSuccess(
    `已套用开局模板「${preset?.preview?.title || fields.title || "未命名模板"}」。`,
    "确认参与人物和模式后可以直接开场。"
  );
  publishChatSetupState("chat-setup-opening-preset-applied");
}

async function handleOpeningPresetSelectionChange() {
  syncSelectedOpeningPresetFromSelect();
}

function openNewOpeningPreset() {
  fillOpeningPresetMetaForm({
    preview: {
      title: "",
      note: "",
    },
  });
  setOpeningPresetSuccess("会把你当前这套模式、人物、角色卡和场景卡一起收成模板。", "补上模板标题后即可保存。");
  openOpeningPresetModal();
}

function openExistingOpeningPreset() {
  if (!currentOpeningPreset) {
    openNewOpeningPreset();
    return;
  }
  fillOpeningPresetMetaForm(currentOpeningPreset);
  setOpeningPresetSuccess("保存时会用你当前这套搭配覆盖模板内容。", "确认后保存即可覆盖这套模板。");
  openOpeningPresetModal();
}

async function handleOpeningPresetSubmit(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  const title = trimmedValue("opening-preset-title", "");
  const note = trimmedValue("opening-preset-note", "");
  const cardId = trimmedValue("opening-preset-id", "");
  setOpeningPresetLoading("正在保存开局模板...");
  try {
    const api = await requireWebUiApi();
    const payload = await api.saveOpeningPreset(cardId, collectOpeningPresetPayload({ title, note }));
    await loadOpeningPresets();
    const select = el("dialogue-opening-preset");
    if (select) {
      select.value = payload.card_id || "";
    }
    syncSelectedOpeningPresetFromSelect();
    setDialogueSessionSuccess("这套开局已经收成模板，下次可以一键套用。", "需要时可直接一键套用后开场。");
    setOpeningPresetSuccess("开局模板已保存。", "现在可以在开场器里直接选用。");
    closeOpeningPresetModal();
  } catch (error) {
    setOpeningPresetFailure(error.message || "开局模板保存失败。", "可以稍后重试，或先精简模板内容后再保存。");
  }
}

async function handleDeleteOpeningPreset(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  const cardId = trimmedValue("opening-preset-id", "");
  if (!cardId) return;
  if (!window.confirm("确定删除这套开局模板吗？")) return;
  setOpeningPresetLoading("正在删除开局模板...");
  try {
    const api = await requireWebUiApi();
    await api.deleteOpeningPreset(cardId);
    if (selectedOpeningPresetId === cardId) {
      selectedOpeningPresetId = "";
      currentOpeningPreset = null;
    }
    await loadOpeningPresets();
    renderOpeningPresetPreview();
    setDialogueSessionSuccess("这套开局模板已经删掉了。", "可以新建一套模板继续使用。");
    setOpeningPresetSuccess("开局模板已删除。", "现在可以创建新的模板。");
    closeOpeningPresetModal();
  } catch (error) {
    setOpeningPresetFailure(error.message || "开局模板删除失败。", "可以稍后重试，或先刷新模板列表后再试。");
  }
}

async function handleApplyOpeningPreset(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  if (!currentOpeningPreset) {
    setDialogueSessionFailure("先挑一套开局模板。", "先选中一套模板后再套用。", false);
    return;
  }
  applyOpeningPresetToChatSetup(currentOpeningPreset);
}

async function handleStartOpeningPreset(event) {
  if (event && typeof event.preventDefault === "function") event.preventDefault();
  if (!currentOpeningPreset) {
    setDialogueSessionFailure("先挑一套开局模板。", "先选中一套模板后再开场。", false);
    return;
  }
  applyOpeningPresetToChatSetup(currentOpeningPreset);
  await handleDialogueSessionSubmit({ preventDefault() {} });
}

async function openPersonaReviewForCharacter(characterName = "") {
  if (!currentRunId || !currentRun) return;
  fillPersonaReviewCharacterOptions(currentRun);
  const fallbackCharacter = getRunCharacterNames(currentRun)[0] || "";
  const character = String(characterName || "").trim() || valueOf("persona-review-character", fallbackCharacter) || fallbackCharacter;
  if (character && el("persona-review-character")) {
    setValue("persona-review-character", character);
  }
  if (!character) {
    setPersonaReviewFailure("这一卷里还没有可校对的人物。", "先完成人物蒸馏后再来校对。");
    return;
  }
  openPersonaReviewModal();
  const reviewActions = readNamedActionBridge("__ZAOMENG_PERSONA_REVIEW_ACTIONS__");
  if (typeof reviewActions.openForCharacter === "function") {
    reviewActions.openForCharacter(character);
    return;
  }
  setPersonaReviewLoading("正在载入人物档案...");
  try {
    renderPersonaReview(await apiJson(`/api/web/runs/${currentRunId}/personas/${encodeURIComponent(character)}`));
    renderPersonaAutofillReferences(null);
    setPersonaReviewSuccess("人物档案已载入。", "你可以直接修改并保存。");
  } catch (error) {
    setPersonaReviewFailure(error.message || "人物档案暂时没有载入。", "可以稍后重试，或先回到书卷页刷新。");
  }
}

async function openPersonaReview() {
  await openPersonaReviewForCharacter("");
}

async function openWorkCharacterReview() {
  if (!currentRunId || !currentRun) return;
  const names = getRunCharacterNames(currentRun);
  if (!names.length) {
    setFlowFailureStatus("bookshelf-status", "这一卷里还没有可校对的人物。", "先完成人物蒸馏后再来校对。", { affectsChatFlow: false });
    return;
  }

  let targetCharacter = "";
  if (typeof buildWorkPriorityReviewItems === "function") {
    const priority = buildWorkPriorityReviewItems(currentRun);
    targetCharacter = String(priority?.[0]?.name || "").trim();
  }
  if (!targetCharacter) {
    targetCharacter = names[0] || "";
  }
  if (!targetCharacter) {
    setFlowFailureStatus("bookshelf-status", "这一卷里还没有可校对的人物。", "先完成人物蒸馏后再来校对。", { affectsChatFlow: false });
    return;
  }

  try {
    await openCharacterOverviewViaBridge(targetCharacter);
  } catch (_error) {
    await openPersonaReviewForCharacter(targetCharacter);
  }
}

async function openQuickDialogueMode(mode) {
  await openNewDialogueSession();
  if (!currentRun || !el("dialogue-mode")) return;
  setValue("dialogue-mode", mode);
  syncModeFields();
  updateCharacterPillState();
}

async function handlePersonaCharacterChange() {
  const reviewActions = readNamedActionBridge("__ZAOMENG_PERSONA_REVIEW_ACTIONS__");
  if (typeof reviewActions.handleCharacterChange === "function") {
    reviewActions.handleCharacterChange(valueOf("persona-review-character", ""));
    return;
  }
  if (!currentRunId) return;
  const character = valueOf("persona-review-character", "");
  if (!character) return;
  setPersonaReviewLoading("正在切换人物...");
  try {
    renderPersonaReview(await apiJson(`/api/web/runs/${currentRunId}/personas/${encodeURIComponent(character)}`));
    renderPersonaAutofillReferences(null);
    setPersonaReviewSuccess("人物档案已切换。", "可以继续编辑当前人物。");
  } catch (error) {
    setPersonaReviewFailure(error.message || "人物档案暂时没有载入。", "可以稍后重试，或切换到其他人物。");
  }
}

function collectPersonaReviewPayload() {
  return Object.fromEntries(
    (PERSONA_REVIEW_FIELD_BINDINGS || []).map(([field, id]) => [field, trimmedValue(id, "")])
  );
}

async function handlePersonaFieldAutofill(event) {
  const reviewActions = readNamedActionBridge("__ZAOMENG_PERSONA_REVIEW_ACTIONS__");
  if (typeof reviewActions.handleLegacyAutofillEvent === "function") {
    const consumed = reviewActions.handleLegacyAutofillEvent(event);
    if (consumed) return;
  }
  const trigger = event.target instanceof HTMLElement ? event.target.closest("[data-persona-autofill-field]") : null;
  if (!(trigger instanceof HTMLButtonElement) || !currentRunId) return;
  const character = valueOf("persona-review-character", "");
  const field = trigger.getAttribute("data-persona-autofill-field") || "";
  if (!character || !field) {
    setPersonaReviewFailure("先选一个人物。", "选中人物后再进行字段补全。");
    return;
  }
  const labelText = trigger.closest(".field-card")?.querySelector(".field-card-head span, span")?.textContent || field;
  trigger.dataset.loading = "true";
  trigger.disabled = true;
  const originalText = trigger.textContent || "AI补全";
  trigger.textContent = "生成中...";
  setPersonaReviewFieldFeedback(field, "loading", "正在生成补全...");
  setPersonaReviewLoading(`正在生成「${labelText}」的补全内容...`);
  try {
    const payload = await apiJson(
      `/api/web/runs/${currentRunId}/personas/${encodeURIComponent(character)}/suggest-field`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ field }),
      },
      "人物信息补全失败。"
    );
    if (payload?.status === "filled" && payload?.value) {
      const targetId = personaReviewFieldId(field);
      if (targetId) {
        setValue(targetId, payload.value);
      }
      markPersonaReviewFieldAutofilled(field);
      renderPersonaAutofillReferences(payload);
      setPersonaReviewFieldFeedback(field, "success", "已生成补全内容，记得保存。");
      setPersonaReviewSuccess(payload.message || "已生成补全内容，请记得保存人物校对。", "确认后点保存写回这一卷。");
    } else {
      renderPersonaAutofillReferences(payload);
      setPersonaReviewFieldFeedback(field, "error", payload?.message || payload?.reason || "人物信息补全无法生成。");
      setPersonaReviewFailure(payload?.message || payload?.reason || "人物信息补全无法生成。", "可以换字段重试，或手动补全。");
    }
  } catch (error) {
    renderPersonaAutofillReferences(null);
    setPersonaReviewFieldFeedback(field, "error", error.message || "人物信息补全无法生成。");
    setPersonaReviewFailure(error.message || "人物信息补全无法生成。", "可以稍后重试，或手动补全该字段。");
  } finally {
    delete trigger.dataset.loading;
    trigger.disabled = false;
    trigger.textContent = originalText;
    syncPersonaReviewAutofillButtons();
  }
}

async function handlePersonaReviewSubmit(event) {
  event.preventDefault();
  const reviewActions = readNamedActionBridge("__ZAOMENG_PERSONA_REVIEW_ACTIONS__");
  if (typeof reviewActions.submit === "function") {
    reviewActions.submit();
    return;
  }
  if (!currentRunId) return;
  const character = valueOf("persona-review-character", "");
  if (!character) {
    setPersonaReviewFailure("先选一个人物。", "选中人物后再保存校对内容。");
    return;
  }
  setPersonaReviewLoading("正在写回人物校对...");
  try {
    renderPersonaReview(
      await apiJson(
        `/api/web/runs/${currentRunId}/personas/${encodeURIComponent(character)}`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(collectPersonaReviewPayload()),
        },
        "保存人物校对失败。"
      )
    );
    clearAllPersonaReviewAutofilledFields();
    clearAllPersonaReviewFieldFeedback();
    renderPersonaAutofillReferences(null);
    applyRunViewSafely(await apiJson(`/api/web/runs/${currentRunId}`));
    setPersonaReviewSuccess("人物校对已经写回这一卷。", "你可以继续校对下一个人物。");
  } catch (error) {
    setPersonaReviewFailure(error.message || "这次校对没有保存成功。", "可以稍后重试，或先复制修改内容。");
  }
}

async function openRelationDetails() {
  if (!currentRunId) return;
  openRelationDetailsModal();
  currentRelationDetails = null;
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState("relation-details-loading", { currentRelationDetails: null });
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState("relation-details-loading", { currentRelationDetails: null });
  }
  setRelationDetailsLoading("正在整理关系明细...");
  try {
    renderRelationDetails(await apiJson(`/api/web/runs/${currentRunId}/relations`));
  } catch (error) {
    setRelationDetailsFailure(error.message || "关系明细暂时没有载入。", "可以稍后重试，或先继续聊天推进。");
  }
}

function renderBuiltinNovelList(items) {
  const root = el("builtin-novel-list");
  if (!root) return;
  root.innerHTML = "";
  const entries = Array.isArray(items) ? items : [];
  entries.forEach((item) => {
    const card = document.createElement("article");
    card.className = "builtin-novel-card";

    const head = document.createElement("div");
    head.className = "builtin-novel-card-head";
    head.innerHTML = `
      <div>
        <strong>${escapeHtml(item.title || item.novel_id || item.package_id || "未命名书卷")}</strong>
        <p>${escapeHtml(item.novel_id || "")}</p>
      </div>
      <span class="builtin-novel-badge">${escapeHtml(item.status || "ready")}</span>
    `;

    const meta = document.createElement("p");
    meta.className = "builtin-novel-card-meta";
    const metaParts = [];
    if (Number(item.character_count || 0) > 0) {
      metaParts.push(`${Number(item.character_count || 0)} 位角色`);
    }
    if (item.has_relation_graph) {
      metaParts.push("含关系图谱");
    }
    if (item.updated_at) {
      metaParts.push(`更新于 ${escapeHtml(formatWeakTime(item.updated_at) || item.updated_at)}`);
    }
    meta.textContent = metaParts.join(" · ");

    const actions = document.createElement("div");
    actions.className = "card-actions";
    const startButton = document.createElement("button");
    startButton.type = "button";
    startButton.className = "primary-button";
    startButton.textContent = "复制到我的书架";
    startButton.dataset.idleText = "复制到我的书架";
    startButton.dataset.busyText = "复制中...";
    startButton.addEventListener("click", () => {
      handleCloneBuiltinNovel(item.package_id, item.title || item.novel_id || "", startButton);
    });
    actions.appendChild(startButton);

    card.appendChild(head);
    if (meta.textContent) {
      card.appendChild(meta);
    }
    card.appendChild(actions);
    root.appendChild(card);
  });
}

async function loadBuiltinNovels() {
  setButtonBusyState("refresh-builtin-novels-button", true, { idleText: "刷新列表", busyText: "刷新中..." });
  setFlowLoadingStatus(
    "builtin-novel-status",
    "正在翻出内置书卷...",
    "整理好后你可以直接复制一本到自己的书架。"
  );
  try {
    const payload = await apiJson("/api/web/builtin-novels", {}, "内置小说列表载入失败。");
    const items = Array.isArray(payload.items) ? payload.items : [];
    renderBuiltinNovelList(items);
    setFlowSuccessStatus(
      "builtin-novel-status",
      items.length ? `当前有 ${items.length} 卷可直接试玩的内置小说。` : "内置目录里暂时还没有小说包。",
      items.length ? "选一卷复制到书架后，就可以直接开聊。" : "你可以先导出一卷，再放进内置目录。"
    );
    return items;
  } catch (error) {
    renderBuiltinNovelList([]);
    setFlowFailureStatus(
      "builtin-novel-status",
      error.message || "内置小说列表暂时没有载入。",
      "可以稍后重试，或先从本地导入小说包。",
      { impact: "这不会影响你继续使用当前书架或聊天。", affectsChatFlow: false }
    );
    throw error;
  } finally {
    setButtonBusyState("refresh-builtin-novels-button", false, { idleText: "刷新列表", busyText: "刷新中..." });
  }
}

async function handleOpenBuiltinNovelModal() {
  if (!modelSettings.configured) {
    openSettingsModal();
    setFlowStatusMessage("builtin-novel-status", {
      message: "先把故事声源接进来，再直接试玩内置小说。",
      nextStep: "模型配置好后，这里就能直接试玩。",
    });
    return;
  }
  openBuiltinNovelModal();
  await loadBuiltinNovels().catch(() => {});
}

async function handleCloneBuiltinNovel(packageId, title = "", trigger = null) {
  const safeTitle = String(title || "").trim();
  setButtonBusyState(trigger, true, { idleText: "复制到我的书架", busyText: "复制中..." });
  setFlowLoadingStatus(
    "builtin-novel-status",
    safeTitle ? `正在把《${safeTitle}》复制到你的书架...` : "正在复制这卷书...",
    "复制完成后你就能直接进入这卷书。"
  );
  try {
    const run = await apiJson(
      `/api/web/builtin-novels/${encodeURIComponent(packageId)}/clone`,
      {
        method: "POST",
      },
      "复制内置小说失败。"
    );
    closeBuiltinNovelModal();
    applyRunViewSafely(run);
    await loadRunsOverview();
    setFlowSuccessStatus(
      "bookshelf-status",
      safeTitle ? `《${safeTitle}》已经落到你的书架里。` : "内置小说已经复制到你的书架里。",
      "现在可以直接开聊，或先看人物和关系整理情况。"
    );
  } catch (error) {
    setFlowFailureStatus(
      "builtin-novel-status",
      error.message || "这卷内置小说暂时没有复制成功。",
      "可以稍后再试，或先从本地导入小说包。",
      { impact: "这不会影响你现有书架和聊天。", affectsChatFlow: false }
    );
  } finally {
    setButtonBusyState(trigger, false, { idleText: "复制到我的书架", busyText: "复制中..." });
  }
}

function triggerImportRunPackage() {
  el("import-run-package-input")?.click();
}

async function handleImportRunPackage(event) {
  const input = event?.target;
  if (!(input instanceof HTMLInputElement)) return;
  const file = input.files?.[0];
  if (!file) return;
  setButtonBusyState("bookshelf-import-run-button", true, { idleText: "导入小说包", busyText: "导入中..." });
  setFlowLoadingStatus(
    "bookshelf-status",
    `正在导入 ${file.name}...`,
    "导入完成后会自动落到你的书架里。"
  );
  try {
    const run = await apiJson(
      "/api/web/runs/import",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          filename: file.name,
          content_base64: await fileToBase64(file),
        }),
      },
      "导入小说包失败。"
    );
    input.value = "";
    applyRunViewSafely(run);
    await loadRunsOverview();
    setFlowSuccessStatus(
      "bookshelf-status",
      `《${runNovelTitle(run)}》已经导入到你的书架。`,
      "现在可以直接开聊，或先校对人物信息。"
    );
  } catch (error) {
    input.value = "";
    setFlowFailureStatus(
      "bookshelf-status",
      error.message || "这次导入没有接上。",
      "可以检查小说包是否完整后再试。",
      { impact: "这不会影响你当前已经在聊的会话或已有书卷。", affectsChatFlow: false }
    );
  } finally {
    setButtonBusyState("bookshelf-import-run-button", false, { idleText: "导入小说包", busyText: "导入中..." });
  }
}

function openRunPackageShareModal() {
  if (!currentRunId) {
    setFlowStatusMessage("bookshelf-status", {
      message: "先选中一卷书，再分享小说包。",
      nextStep: "进入某一卷详情后，这里就可以直接分享。",
    });
    return false;
  }
  const run = currentRun || findRunById(currentRunId);
  if (String(run?.status || "").trim() === "running") {
    setFlowStatusMessage("bookshelf-status", {
      message: "这本书还在整理中，等这一轮结束后再分享。",
      impact: "这不会影响你继续看进度或直接聊天。",
      nextStep: "等整理结束后再分享即可。",
    });
    return false;
  }
  const includeDialogue = el("run-package-share-include-dialogue");
  if (includeDialogue) {
    includeDialogue.checked = true;
  }
  const status = el("run-package-share-status");
  if (status) {
    status.textContent = "";
  }
  toggle("run-package-share-modal", true);
  syncModalScrollLock();
  return true;
}

function closeRunPackageShareModal() {
  toggle("run-package-share-modal", false);
  syncModalScrollLock();
}

function handleExportRunPackage() {
  openRunPackageShareModal();
}

async function handleConfirmRunPackageShare() {
  if (!currentRunId) {
    closeRunPackageShareModal();
    return;
  }
  const runId = String(currentRunId || "").trim();
  if (isRunPackageExportPending(runId)) {
    return;
  }
  const run = currentRun || findRunById(runId);
  const includeDialogue = Boolean(el("run-package-share-include-dialogue")?.checked);
  const title = runNovelTitle(run);
  setRunPackageExportPending(runId, true);
  setButtonBusyState("run-package-share-confirm-button", true, {
    idleText: "生成分享包",
    busyText: "生成中...",
  });
  const statusNode = el("run-package-share-status");
  if (statusNode) {
    statusNode.textContent = `正在打包《${title}》...`;
  }
  try {
    const response = await fetch(
      `/api/web/runs/${encodeURIComponent(runId)}/share`,
      webAuthFetchOptions({
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ include_dialogue: includeDialogue }),
      })
    );
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.detail || "分享小说包失败。");
    }
    const blob = await response.blob();
    const fallbackName = `${String(run?.novel_id || title || "zaomeng-run").trim() || "zaomeng-run"}.zaomeng-run.zip`;
    downloadBlobFile(blob, resolveDownloadFilename(response, fallbackName));
    closeRunPackageShareModal();
    setFlowSuccessStatus(
      "bookshelf-status",
      `《${title}》的分享包已经准备好，正在开始下载。`,
      includeDialogue
        ? "包里已包含现有聊天记录，可以直接导入续用。"
        : "这次只分享素材，不包含聊天记录，适合公开试玩。"
    );
  } catch (error) {
    if (statusNode) {
      statusNode.textContent = error.message || "这次分享没有接上。";
    }
  } finally {
    setRunPackageExportPending(runId, false);
    setButtonBusyState("run-package-share-confirm-button", false, {
      idleText: "生成分享包",
      busyText: "生成中...",
    });
  }
}

const DIALOGUE_PLACEHOLDER_DEFAULT = "写一句你想让他们听见的话";
const DIALOGUE_PLACEHOLDER_NARRATION = "可写推进方向，留空则由系统主动推进";
const DIALOGUE_AUTO_PLOT_PUSH_MESSAGE = "请基于当前场景、未完成线索与人物关系，主动推进到一个新的有效剧情节点。";
const DIALOGUE_PLACEHOLDER_WAITING = "他们正在接住你的话。";
const DIALOGUE_SUGGESTION_WAITING = "正在生成中...";
const DIALOGUE_SUGGESTION_BUSY_LABEL = "…";
const DIALOGUE_RETRY_FEEDBACK_DELAY_MS = 4000;
const DIALOGUE_SEND_RETRY_MESSAGE = "这次响应稍慢，正在等待声源返回...";
const DIALOGUE_SUGGEST_RETRY_MESSAGE = "这次生成稍慢，正在等待结果返回...";
let currentDialogueSceneRecommendationCacheKey = "";
let currentDialogueSceneRecommendationCachePayload = null;
let currentDialogueSceneRecommendationMeta = null;
const OBSERVE_QUICK_REPLIES = [
  { label: "……", value: "……" },
  { label: "继续聊", value: "继续聊。" },
  { label: "别停", value: "别停，继续往下说。" },
  { label: "有人打断", value: "门外忽然传来一点动静，屋里的人都顿了一下。" },
  { label: "再逼近点", value: "这句话落下去以后，气氛反而更近了一步。" },
];
let currentDialogueMessageKind = "dialogue";
let observeAutoMode = false;
let observeAutoLoopBusy = false;
let observeAutoSessionId = "";
const OBSERVE_AUTO_LOOP_DELAY_MS = 480;
let observeAutoRecentPrompts = [];

function buildDialogueSceneRecommendationCacheKey(session = currentDialogueSession) {
  const target = session || {};
  const overview = target?.runtime_state_overview || {};
  return [
    String(currentRunId || "").trim(),
    String(target?.session_id || "").trim(),
    String(target?.updated_at || "").trim(),
    String(target?.session_card?.scene_card_id || "").trim(),
    String(overview?.status_line || "").trim(),
    String(overview?.next_hint || "").trim(),
  ].join("::");
}

function clearDialogueSceneRecommendationCache() {
  currentDialogueSceneRecommendationCacheKey = "";
  currentDialogueSceneRecommendationCachePayload = null;
  currentDialogueSceneRecommendationMeta = null;
}

async function fetchDialogueSceneRecommendation(options = {}) {
  const force = Boolean(options?.force);
  if (!currentRunId || !currentDialogueSessionId) {
    return null;
  }
  const cacheKey = buildDialogueSceneRecommendationCacheKey(currentDialogueSession);
  if (!force && cacheKey && cacheKey === currentDialogueSceneRecommendationCacheKey && currentDialogueSceneRecommendationCachePayload) {
    currentDialogueSceneRecommendationMeta = {
      sessionId: String(currentDialogueSessionId || "").trim(),
      fromCache: true,
      payload: currentDialogueSceneRecommendationCachePayload,
    };
    return currentDialogueSceneRecommendationCachePayload;
  }
  const payload = await window.__ZAOMENG_WEBUI_API__.recommendDialogueSceneCard(currentRunId, currentDialogueSessionId);
  currentDialogueSceneRecommendationCacheKey = cacheKey;
  currentDialogueSceneRecommendationCachePayload = payload;
  currentDialogueSceneRecommendationMeta = {
    sessionId: String(currentDialogueSessionId || "").trim(),
    fromCache: false,
    payload,
  };
  return payload;
}

function renderDialogueSceneRecommendationSummary(session = currentDialogueSession) {
  const root = el("dialogue-live-scene-recommendation");
  if (!root) return;
  const sessionId = String(session?.session_id || "").trim();
  const meta = currentDialogueSceneRecommendationMeta || null;
  const payload = meta?.payload || null;
  if (!sessionId || !payload || String(meta?.sessionId || "").trim() !== sessionId) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  const recommendedCardId = String(payload?.recommended_card_id || "").trim();
  const topItem = Array.isArray(payload?.items) ? payload.items.find((item) => String(item?.card_id || "").trim() === recommendedCardId) || payload.items[0] : null;
  if (!recommendedCardId || !topItem) {
    root.innerHTML = "";
    root.classList.add("hidden");
    return;
  }
  const title = String(topItem?.preview?.title || topItem?.fields?.title || recommendedCardId).trim();
  const location = String(topItem?.preview?.location || topItem?.fields?.location || "").trim();
  const transition = String(payload?.recommended_transition_message || "").trim();
  const autoContinueMessage = String(payload?.recommended_auto_continue_message || "").trim();
  const reasons = Array.isArray(topItem?.recommendation?.reasons) ? topItem.recommendation.reasons.filter(Boolean).slice(0, 4) : [];
  const firstChain = Array.isArray(payload?.chain_suggestions) ? payload.chain_suggestions[0] : null;
  const chainReason = String(firstChain?.reason || "").trim();
  const sourceLabel = meta?.fromCache ? "已缓存" : "刚更新";

  root.innerHTML = "";
  const head = document.createElement("div");
  head.className = "dialogue-live-scene-recommendation-head";
  const strong = document.createElement("strong");
  strong.textContent = location ? `推荐：${title} · ${location}` : `推荐：${title}`;
  const badge = document.createElement("span");
  badge.textContent = sourceLabel;
  head.appendChild(strong);
  head.appendChild(badge);
  root.appendChild(head);

  if (reasons.length) {
    const tags = document.createElement("div");
    tags.className = "dialogue-live-scene-recommendation-tags";
    reasons.forEach((reason) => {
      const chip = document.createElement("span");
      chip.textContent = String(reason || "").trim();
      tags.appendChild(chip);
    });
    root.appendChild(tags);
  }

  if (transition) {
    const transitionCopy = document.createElement("p");
    transitionCopy.textContent = `转场起句：${transition}`;
    root.appendChild(transitionCopy);
  }

  if (chainReason) {
    const chainCopy = document.createElement("p");
    chainCopy.textContent = `后续走势：${chainReason}`;
    root.appendChild(chainCopy);
  }

  if (autoContinueMessage) {
    const openingCopy = document.createElement("p");
    openingCopy.textContent = `自动起拍提示：${autoContinueMessage}`;
    root.appendChild(openingCopy);
  }

  const autoCopy = document.createElement("p");
  autoCopy.textContent = "顺手切到下一幕会直接把新一拍接起来，不会停在空场。";
  root.appendChild(autoCopy);
  root.classList.remove("hidden");
}

function buildObserveQuickReplies(session = currentDialogueSession) {
  const overview = session?.runtime_state_overview || {};
  const present = Array.isArray(overview?.present) ? overview.present.filter(Boolean) : [];
  const offstage = Array.isArray(overview?.offstage) ? overview.offstage.filter(Boolean) : [];
  const shouldShift = Boolean(overview?.should_offer_scene_shift);
  const nextHint = String(overview?.next_hint || "").trim();
  const tension = String(overview?.tension || "").trim();
  const eventRows = Array.isArray(overview?.event_rows) ? overview.event_rows : [];
  const dynamic = [];

  if (shouldShift) {
    dynamic.push({
      label: "转下一幕",
      value: nextHint || "这一拍差不多收住了，场面顺势往下一幕转过去。",
    });
  }
  if (offstage.length) {
    dynamic.push({
      label: `切回${String(offstage[0]).slice(0, 4)}`,
      value: `${offstage[0]}那边也有了新的动静，镜头顺势切过去。`,
    });
  }
  if (present.length >= 2) {
    dynamic.push({
      label: "只留他们",
      value: `旁的人都暂时退开，只剩${present.slice(0, 2).join("和")}把这句话接下去。`,
    });
  }
  if (tension) {
    dynamic.push({
      label: "顺着张力",
      value: "这股气氛没有散，反而又往前逼近了一步。",
    });
  }
  const lastEvent = eventRows.length ? eventRows[eventRows.length - 1] : null;
  if (lastEvent?.copy) {
    dynamic.push({
      label: "顺着波动",
      value: String(lastEvent.copy || "").trim(),
    });
  }

  const merged = [];
  const seen = new Set();
  [...dynamic, ...OBSERVE_QUICK_REPLIES].forEach((item) => {
    const label = String(item?.label || "").trim();
    const value = String(item?.value || "").trim();
    if (!label || !value) return;
    const key = `${label}::${value}`;
    if (seen.has(key)) return;
    seen.add(key);
    merged.push({ label, value });
  });
  return merged.slice(0, 6);
}

function buildComposerUiState() {
  const area = el("dialogue-message");
  const sendButton = el("prepare-turn-button");
  const suggestButton = el("suggest-turn-button");
  const mode = currentDialogueSession?.mode || currentDialogueSession?.session_card?.mode || "";
  const nextHint = mode === "observe" ? String(currentDialogueSession?.runtime_state_overview?.next_hint || "").trim() : "";
  return {
    mode,
    kind: normalizeDialogueMessageKind(currentDialogueMessageKind),
    message: String(area?.value || ""),
    placeholder: String(area?.placeholder || ""),
    disabled: Boolean(area?.disabled),
    suggestHidden: Boolean(suggestButton?.classList.contains("hidden")),
    suggestDisabled: Boolean(suggestButton?.disabled),
    sendDisabled: Boolean(sendButton?.disabled),
    quickReplies: [],
    quickHint: nextHint,
    observeAutoMode,
    associationEnabled: dialogueAssociationsEnabled,
    mentionCandidates: buildDialogueMentionCandidates(currentDialogueSession),
  };
}

function buildDialogueMentionCandidates(session = currentDialogueSession) {
  if (!session) return [];
  const participants = Array.isArray(session?.participants)
    ? session.participants
    : Array.isArray(session?.session_card?.participants)
      ? session.session_card.participants
      : [];
  const overview = session?.runtime_state_overview || {};
  const progress = session?.scene_progress || {};
  const present = Array.isArray(overview?.present) && overview.present.length
    ? overview.present
    : Array.isArray(progress?.present_participants)
      ? progress.present_participants
      : [];
  const offstage = Array.isArray(overview?.offstage) && overview.offstage.length
    ? overview.offstage
    : Array.isArray(progress?.offstage_participants)
      ? progress.offstage_participants
      : [];
  const presenceKnown = present.length > 0 || offstage.length > 0;
  const pool = presenceKnown ? present : participants;
  const mode = String(session?.mode || session?.session_card?.mode || "").trim();
  const currentSpeaker = mode === "act"
    ? String(session?.controlled_character || session?.session_card?.controlled_character || "").trim()
    : mode === "insert"
      ? String(session?.self_insert?.display_name || session?.session_card?.self_insert?.display_name || "").trim()
      : "";
  const allowed = new Set(participants.map((name) => String(name || "").trim()).filter(Boolean));
  const seen = new Set();
  return pool
    .map((name) => String(name || "").trim())
    .filter((name) => {
      if (!name || name === currentSpeaker || ["User", "旁白", "场景提示"].includes(name)) return false;
      if (allowed.size && !allowed.has(name)) return false;
      if (seen.has(name)) return false;
      seen.add(name);
      return true;
    });
}

function extractDialogueMentionContext(value, caretPosition) {
  const text = String(value || "");
  const caret = Math.max(0, Math.min(text.length, Number(caretPosition ?? text.length)));
  const beforeCaret = text.slice(0, caret);
  const start = beforeCaret.lastIndexOf("@");
  if (start < 0) return null;
  const query = beforeCaret.slice(start + 1);
  if (/[@\r\n\t，。！？；：、（）(),.!?;:]/u.test(query)) return null;
  return { start, end: caret, query };
}

function collectDialogueMentionNames(value, excludedContext = null, session = currentDialogueSession) {
  const text = String(value || "");
  const contextStart = Number(excludedContext?.start);
  const contextEnd = Number(excludedContext?.end);
  const masked = Number.isFinite(contextStart) && Number.isFinite(contextEnd)
    ? `${text.slice(0, contextStart)}${" ".repeat(Math.max(0, contextEnd - contextStart))}${text.slice(contextEnd)}`
    : text;
  const mentioned = new Set();
  buildDialogueMentionCandidates(session).forEach((name) => {
    const token = `@${name}`;
    let cursor = 0;
    while (cursor < masked.length) {
      const marker = masked.indexOf(token, cursor);
      if (marker < 0) break;
      const following = masked[marker + token.length] || "";
      if (!following || /[\s,，。！？；：、（）().!?;:]/u.test(following)) {
        mentioned.add(name);
        break;
      }
      cursor = marker + token.length;
    }
  });
  return mentioned;
}

function availableDialogueMentionCandidates(value = "", excludedContext = null, session = currentDialogueSession) {
  const mentioned = collectDialogueMentionNames(value, excludedContext, session);
  return buildDialogueMentionCandidates(session).filter((name) => !mentioned.has(name));
}

function syncDialogueMentionButton(session = currentDialogueSession) {
  const button = el("dialogue-mention-button");
  const area = el("dialogue-message");
  if (!button) return;
  const available = availableDialogueMentionCandidates(area?.value || "", null, session);
  button.disabled = available.length === 0;
  button.title = available.length ? "艾特在场人物" : "没有其他可艾特的在场人物";
}

function composerEditorText(editor = el("dialogue-message")) {
  return String(editor?.textContent || "").replace(/\r\n?/g, "\n");
}

function composerEditorPoint(editor, offset) {
  const target = Math.max(0, Number(offset) || 0);
  const walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT);
  let traversed = 0;
  let node = walker.nextNode();
  while (node) {
    const length = node.textContent?.length || 0;
    if (traversed + length >= target) {
      return { node, offset: target - traversed };
    }
    traversed += length;
    node = walker.nextNode();
  }
  return { node: editor, offset: editor.childNodes.length };
}

function setComposerEditorSelection(start, end = start) {
  const editor = el("dialogue-message");
  if (!editor) return;
  const selection = window.getSelection();
  const range = document.createRange();
  const startPoint = composerEditorPoint(editor, start);
  const endPoint = composerEditorPoint(editor, end);
  range.setStart(startPoint.node, startPoint.offset);
  range.setEnd(endPoint.node, endPoint.offset);
  selection?.removeAllRanges();
  selection?.addRange(range);
}

function composerEditorCaretOffset() {
  const editor = el("dialogue-message");
  const selection = window.getSelection();
  if (!editor || !selection?.rangeCount) return composerEditorText(editor).length;
  const range = selection.getRangeAt(0);
  if (!editor.contains(range.startContainer)) return composerEditorText(editor).length;
  const before = range.cloneRange();
  before.selectNodeContents(editor);
  before.setEnd(range.startContainer, range.startOffset);
  return before.toString().length;
}

function renderComposerEditor(value, options = {}) {
  const editor = el("dialogue-message");
  if (!editor) return;
  const text = String(value || "");
  editor.replaceChildren();
  const explicitName = String(options.mentionName || "").trim();
  const candidates = [...buildDialogueMentionCandidates(), explicitName]
    .filter(Boolean)
    .sort((left, right) => right.length - left.length);
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
    if (prefix) editor.appendChild(document.createTextNode(prefix));
    const token = document.createElement("span");
    token.className = "composer-mention-token";
    token.dataset.mentionName = name;
    token.contentEditable = "false";
    token.textContent = `@${name}`;
    editor.appendChild(token);
    emittedThrough = marker + name.length + 1;
    scanFrom = emittedThrough;
  }
  const remainder = text.slice(emittedThrough);
  if (remainder) editor.appendChild(document.createTextNode(remainder));
}

function insertComposerPlainText(text) {
  const editor = el("dialogue-message");
  const selection = window.getSelection();
  if (!editor || !selection?.rangeCount) return;
  const range = selection.getRangeAt(0);
  if (!editor.contains(range.startContainer)) return;
  range.deleteContents();
  const node = document.createTextNode(String(text || ""));
  range.insertNode(node);
  range.setStart(node, node.textContent.length);
  range.collapse(true);
  selection.removeAllRanges();
  selection.addRange(range);
  editor.dispatchEvent(new Event("input", { bubbles: true }));
}

function initializeDialogueComposerEditor() {
  const editor = el("dialogue-message");
  if (!editor || editor.dataset.composerReady === "true") return;
  editor.dataset.composerReady = "true";
  let placeholder = String(editor.dataset.placeholder || "");
  let disabled = false;
  Object.defineProperties(editor, {
    value: {
      configurable: true,
      get: () => composerEditorText(editor),
      set: (value) => renderComposerEditor(value),
    },
    placeholder: {
      configurable: true,
      get: () => placeholder,
      set: (value) => {
        placeholder = String(value || "");
        editor.dataset.placeholder = placeholder;
      },
    },
    disabled: {
      configurable: true,
      get: () => disabled,
      set: (value) => {
        disabled = Boolean(value);
        editor.contentEditable = disabled ? "false" : "true";
        editor.setAttribute("aria-disabled", disabled ? "true" : "false");
      },
    },
    selectionStart: {
      configurable: true,
      get: () => composerEditorCaretOffset(),
    },
  });
  editor.setSelectionRange = (start, end = start) => setComposerEditorSelection(start, end);
  editor.addEventListener("paste", (event) => {
    event.preventDefault();
    insertComposerPlainText(event.clipboardData?.getData("text/plain") || "");
  });
}

let dialogueMentionIndex = 0;

function closeDialogueMentionMenu() {
  const menu = el("dialogue-mention-menu");
  if (!menu) return;
  menu.innerHTML = "";
  menu.classList.add("hidden");
  dialogueMentionIndex = 0;
}

function renderDialogueMentionMenu() {
  const area = el("dialogue-message");
  const menu = el("dialogue-mention-menu");
  if (!area || !menu || area.disabled) return closeDialogueMentionMenu();
  const context = extractDialogueMentionContext(area.value, area.selectionStart);
  const candidates = availableDialogueMentionCandidates(area.value, context).filter((name) => (
    !context?.query || name.toLocaleLowerCase().includes(context.query.toLocaleLowerCase())
  ));
  if (!context || !candidates.length) return closeDialogueMentionMenu();
  dialogueMentionIndex = Math.min(dialogueMentionIndex, candidates.length - 1);
  menu.innerHTML = "";
  candidates.forEach((name, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "composer-mention-option";
    button.dataset.mentionName = name;
    button.setAttribute("role", "option");
    button.setAttribute("aria-selected", index === dialogueMentionIndex ? "true" : "false");
    button.classList.toggle("active", index === dialogueMentionIndex);
    button.textContent = `@${name}`;
    menu.appendChild(button);
  });
  menu.classList.remove("hidden");
}

function insertDialogueMention(name) {
  const area = el("dialogue-message");
  if (!area) return;
  const context = extractDialogueMentionContext(area.value, area.selectionStart);
  const target = String(name || "").trim();
  if (!context || !buildDialogueMentionCandidates().includes(target)) return;
  if (collectDialogueMentionNames(area.value, context).has(target)) {
    closeDialogueMentionMenu();
    return;
  }
  const nextValue = `${area.value.slice(0, context.start)}@${target} ${area.value.slice(context.end)}`;
  const nextCaret = context.start + target.length + 2;
  setComposerDraft(nextValue, { focus: true, mentionStart: context.start, mentionName: target });
  area.setSelectionRange(nextCaret, nextCaret);
  closeDialogueMentionMenu();
  syncDialogueMentionButton();
}

function openDialogueMentionPicker() {
  const area = el("dialogue-message");
  if (!area || area.disabled || !availableDialogueMentionCandidates(area.value).length) return;
  const caret = Number.isFinite(area.selectionStart) ? area.selectionStart : area.value.length;
  const value = area.value;
  const nextValue = `${value.slice(0, caret)}@${value.slice(caret)}`;
  setComposerDraft(nextValue, { focus: true });
  area.setSelectionRange(caret + 1, caret + 1);
  dialogueMentionIndex = 0;
  renderDialogueMentionMenu();
}

function removeAdjacentDialogueMention(event) {
  if (event.key !== "Backspace" && event.key !== "Delete") return false;
  const editor = el("dialogue-message");
  const selection = window.getSelection();
  if (!editor || !selection?.isCollapsed || !selection.rangeCount) return false;
  const range = selection.getRangeAt(0);
  let node = range.startContainer;
  const offset = range.startOffset;
  if (node.nodeType === Node.TEXT_NODE && event.key === "Backspace" && offset === 0) node = node.previousSibling;
  else if (node.nodeType === Node.TEXT_NODE && event.key === "Delete" && offset === (node.textContent?.length || 0)) node = node.nextSibling;
  else if (node === editor) node = editor.childNodes[event.key === "Backspace" ? offset - 1 : offset];
  if (!(node instanceof HTMLElement) || !node.matches(".composer-mention-token")) return false;
  event.preventDefault();
  const caret = composerEditorCaretOffset() - (event.key === "Backspace" ? (node.textContent?.length || 0) : 0);
  node.remove();
  setComposerEditorSelection(Math.max(0, caret));
  editor.dispatchEvent(new Event("input", { bubbles: true }));
  return true;
}

function handleDialogueMentionKeydown(event) {
  const menu = el("dialogue-mention-menu");
  if (!menu || menu.classList.contains("hidden")) return false;
  const options = Array.from(menu.querySelectorAll("[data-mention-name]"));
  if (!options.length) return false;
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    event.preventDefault();
    const step = event.key === "ArrowDown" ? 1 : -1;
    dialogueMentionIndex = (dialogueMentionIndex + step + options.length) % options.length;
    renderDialogueMentionMenu();
    return true;
  }
  if (event.key === "Enter" || event.key === "Tab") {
    event.preventDefault();
    insertDialogueMention(options[dialogueMentionIndex]?.dataset.mentionName || "");
    return true;
  }
  if (event.key === "Escape") {
    event.preventDefault();
    closeDialogueMentionMenu();
    return true;
  }
  return false;
}

window.__ZAOMENG_BUILD_COMPOSER_STATE__ = buildComposerUiState;

function publishComposerUiState(source = "composer") {
  if (typeof UI_BRIDGE_TOOLS.syncLegacyUiState === "function") {
    UI_BRIDGE_TOOLS.syncLegacyUiState(source, { composer: buildComposerUiState() });
  } else if (typeof UI_BRIDGE_TOOLS.publishLegacyStateSlice === "function") {
    UI_BRIDGE_TOOLS.publishLegacyStateSlice(source, "composer", buildComposerUiState());
  } else if (typeof publishLegacyUiState === "function") {
    publishLegacyUiState(source, { composer: buildComposerUiState() });
  }
}

function normalizeDialogueMessageKind(kind) {
  const value = String(kind || "").trim().toLowerCase();
  if (value === "plot") return "plot";
  return value === "narration" ? "narration" : "dialogue";
}

function readDialogueMessageKind() {
  const active = document.querySelector("#dialogue-message-kind .kind-chip.active");
  if (active instanceof HTMLElement) {
    return normalizeDialogueMessageKind(active.dataset.kind);
  }
  return normalizeDialogueMessageKind(currentDialogueMessageKind);
}

function updateDialogueMessagePlaceholder() {
  const area = el("dialogue-message");
  if (!area) return;
  const kind = readDialogueMessageKind();
  area.placeholder = kind === "plot" ? DIALOGUE_PLACEHOLDER_NARRATION : DIALOGUE_PLACEHOLDER_DEFAULT;
}

function setDialogueMessageKind(kind) {
  currentDialogueMessageKind = normalizeDialogueMessageKind(kind);
  document.querySelectorAll("#dialogue-message-kind .kind-chip").forEach((node) => {
    if (!(node instanceof HTMLElement)) return;
    node.classList.toggle("active", normalizeDialogueMessageKind(node.dataset.kind) === currentDialogueMessageKind);
  });
  updateDialogueMessagePlaceholder();
  const sendButton = el("prepare-turn-button");
  if (sendButton) {
    const isPlotPush = currentDialogueMessageKind === "plot";
    sendButton.textContent = isPlotPush ? "推进" : "送出";
    sendButton.setAttribute("aria-label", isPlotPush ? "推进剧情" : "送出");
  }
  publishComposerUiState("composer-kind-updated");
}

function syncDialogueMessageKindVisibility(session = currentDialogueSession) {
  const toggle = el("dialogue-message-kind");
  const mode = session?.mode || session?.session_card?.mode || "";
  const hide = mode === "observe";
  syncDialogueMentionButton(session);
  syncDialogueAssociationToggle();
  if (toggle) {
    toggle.classList.toggle("hidden", hide);
  }
  if (hide) {
    setDialogueMessageKind("narration");
  } else if (normalizeDialogueMessageKind(currentDialogueMessageKind) === "narration") {
    setDialogueMessageKind("dialogue");
  }
}

function setQuickRepliesEnabled(enabled) {
  document.querySelectorAll("#observe-quick-replies .quick-reply-chip").forEach((node) => {
    node.disabled = !enabled;
  });
}

function setObserveAutoUiState() {
  const mode = currentDialogueSession?.mode || currentDialogueSession?.session_card?.mode || "";
  const row = el("observe-auto-row");
  const toggleButton = el("observe-auto-toggle");
  const status = el("observe-auto-status");
  const quickReplyRoot = el("observe-quick-replies");
  const available = mode === "observe" && Boolean(currentDialogueSessionId);
  if (quickReplyRoot) {
    quickReplyRoot.innerHTML = "";
    quickReplyRoot.classList.add("hidden");
  }
  if (row) {
    row.classList.toggle("hidden", !available);
  }
  if (!toggleButton || !status) {
    publishComposerUiState("composer-observe-auto-ui");
    return;
  }
  if (!available) {
    observeAutoMode = false;
    observeAutoLoopBusy = false;
    observeAutoSessionId = "";
    toggleButton.disabled = true;
    toggleButton.textContent = "开启连续旁观";
    status.textContent = "仅旁观模式可用";
    publishComposerUiState("composer-observe-auto-ui");
    return;
  }
  toggleButton.disabled = false;
  toggleButton.textContent = observeAutoMode ? "停止连续旁观" : "开启连续旁观";
  status.textContent = observeAutoMode ? "开启：自动推进中，点停止即可收住" : "关闭：每轮手动推进";
  publishComposerUiState("composer-observe-auto-ui");
}

function stopObserveAutoLoop(options = {}) {
  observeAutoMode = false;
  observeAutoLoopBusy = false;
  observeAutoSessionId = "";
  observeAutoRecentPrompts = [];
  if (options.reason) {
    setDialogueSessionSuccess(options.reason, "你可以随时再次开启连续旁观。");
  }
  setObserveAutoUiState();
}

function syncSuggestButtonVisibility(session = currentDialogueSession) {
  const suggestButton = el("suggest-turn-button");
  if (!suggestButton) return;
  const mode = session?.mode || session?.session_card?.mode || "";
  const hidden = mode === "observe";
  suggestButton.classList.toggle("hidden", hidden);
  if (hidden) {
    suggestButton.disabled = true;
  }
  publishComposerUiState("composer-suggest-visibility-updated");
}

function setComposerWaiting(waiting, message = "") {
  const area = el("dialogue-message");
  const sendButton = el("prepare-turn-button");
  const suggestButton = el("suggest-turn-button");
  if (!area) return;
  if (waiting) {
    area.disabled = false;
    area.placeholder = message || DIALOGUE_PLACEHOLDER_WAITING;
    if (sendButton) sendButton.disabled = true;
    if (suggestButton) suggestButton.disabled = true;
  } else {
    area.disabled = false;
    if (sendButton) sendButton.disabled = false;
    if (suggestButton) suggestButton.disabled = false;
    updateDialogueMessagePlaceholder();
    if (message) {
      area.value = message;
    }
  }
  setQuickRepliesEnabled(!waiting);
  setObserveAutoUiState();
  resizeComposer();
  publishComposerUiState("composer-waiting-updated");
}

function setSuggestingState(waiting) {
  const area = el("dialogue-message");
  const sendButton = el("prepare-turn-button");
  const suggestButton = el("suggest-turn-button");
  if (area) area.disabled = waiting;
  if (sendButton) sendButton.disabled = waiting;
  if (suggestButton) {
    suggestButton.disabled = waiting;
    suggestButton.textContent = waiting ? DIALOGUE_SUGGESTION_BUSY_LABEL : "✨";
    suggestButton.setAttribute("aria-busy", waiting ? "true" : "false");
  }
  setQuickRepliesEnabled(!waiting);
  publishComposerUiState("composer-suggesting-updated");
}

function renderObserveQuickReplies(session = currentDialogueSession) {
  const root = el("observe-quick-replies");
  const kindToggle = el("dialogue-message-kind");
  if (!root) return;
  const mode = session?.mode || session?.session_card?.mode || "";
  if (mode !== "observe") {
    if (kindToggle) kindToggle.classList.remove("hidden");
    root.innerHTML = "";
    root.classList.add("hidden");
    stopObserveAutoLoop();
    publishComposerUiState("composer-quick-replies-hidden");
    return;
  }
  if (kindToggle) kindToggle.classList.add("hidden");
  root.innerHTML = "";
  root.classList.add("hidden");
  setObserveAutoUiState();
  publishComposerUiState("composer-quick-replies-hidden");
}

async function applyQuickReply(value) {
  const message = String(value || "").trim();
  const area = el("dialogue-message");
  if (!message || !area || area.disabled) return;
  publishComposerUiState("composer-quick-reply-picked");
  await handleSendTurn(message, "narration");
}

function setComposerDraft(value = "", options = {}) {
  const area = el("dialogue-message");
  if (!area) return;
  renderComposerEditor(value, options);
  syncDialogueMentionButton();
  resizeComposer();
  if (options.focus) {
    area.focus();
    area.setSelectionRange(area.value.length, area.value.length);
  }
  if (options.publish !== false) {
    publishComposerUiState("composer-draft-updated");
  }
}

function coerceMessageOverride(value) {
  if (value && typeof value === "object") {
    if (typeof value.preventDefault === "function") value.preventDefault();
    if (typeof value.stopPropagation === "function") value.stopPropagation();
    return "";
  }
  return String(value || "");
}

function dismissMobileDialogueKeyboard() {
  const mobileInput = window.matchMedia
    ? window.matchMedia("(max-width: 768px), (pointer: coarse)").matches
    : window.innerWidth <= 768;
  if (mobileInput) {
    el("dialogue-message")?.blur();
  }
}

function clearDialogueAssociations() {
  dialogueAssociationRequestId += 1;
  dialogueAssociationState = {
    sessionId: "",
    status: "idle",
    options: [],
    selectedLabel: "",
    error: "",
  };
  el("dialogue-association-panel")?.remove();
}

function syncDialogueAssociationToggle() {
  const toggle = el("dialogue-association-toggle");
  const row = el("dialogue-association-toggle-row");
  const mode = currentDialogueSession?.mode || currentDialogueSession?.session_card?.mode || "";
  if (row) {
    row.classList.toggle("hidden", mode === "observe");
  }
  if (toggle) {
    toggle.checked = dialogueAssociationsEnabled;
  }
}

function setDialogueAssociationsEnabled(enabled) {
  dialogueAssociationsEnabled = Boolean(enabled);
  try {
    window.localStorage?.setItem(
      DIALOGUE_ASSOCIATION_ENABLED_KEY,
      dialogueAssociationsEnabled ? "1" : "0"
    );
  } catch (_error) {
    // The in-memory preference still applies when storage is unavailable.
  }
  syncDialogueAssociationToggle();
  dialogueAssociationLastRequestKey = "";
  if (!dialogueAssociationsEnabled) {
    clearDialogueAssociations();
  } else {
    maybeRequestDialogueAssociations(currentDialogueSession);
  }
  publishComposerUiState("composer-association-toggle");
}

function syncDialogueInnerThoughtToggle() {
  const toggle = el("dialogue-inner-thought-toggle");
  if (toggle) {
    toggle.checked = dialogueInnerThoughtsEnabled;
  }
}

function setDialogueInnerThoughtsEnabled(enabled) {
  dialogueInnerThoughtsEnabled = Boolean(enabled);
  window.dialogueInnerThoughtsEnabled = dialogueInnerThoughtsEnabled;
  try {
    window.localStorage?.setItem(
      DIALOGUE_INNER_THOUGHT_ENABLED_KEY,
      dialogueInnerThoughtsEnabled ? "1" : "0"
    );
  } catch (_error) {
    // The in-memory preference still applies when storage is unavailable.
  }
  syncDialogueInnerThoughtToggle();
  if (currentDialogueSession) {
    renderDialogueTranscript(currentDialogueSession);
  }
}

function renderDialogueAssociations() {
  el("dialogue-association-panel")?.remove();
  if (!dialogueAssociationsEnabled) return;
  const mode = currentDialogueSession?.mode || currentDialogueSession?.session_card?.mode || "";
  if (mode === "observe") return;
  const root = el("dialogue-transcript");
  const state = dialogueAssociationState;
  if (!root || !state.sessionId || state.sessionId !== currentDialogueSessionId) return;
  if (state.status === "idle" && !state.options.length) return;

  const panel = document.createElement("aside");
  panel.id = "dialogue-association-panel";
  panel.className = "dialogue-association-panel";
  panel.setAttribute("aria-live", "polite");

  const header = document.createElement("div");
  header.className = "dialogue-association-head";
  const mark = document.createElement("span");
  mark.className = "dialogue-association-mark";
  mark.setAttribute("aria-hidden", "true");
  mark.textContent = "✦";
  const title = document.createElement("strong");
  title.textContent =
    state.status === "loading"
      ? "AI 正在联想情节分支"
      : state.status === "error"
        ? "AI 联想暂时没有生成"
        : "AI 联想 · 情节分支";
  header.append(mark, title);
  panel.appendChild(header);

  if (state.status === "loading") {
    const loading = document.createElement("div");
    loading.className = "dialogue-association-loading";
    loading.setAttribute("aria-label", "正在生成情节分支");
    for (let index = 0; index < 3; index += 1) {
      const placeholder = document.createElement("span");
      placeholder.className = "dialogue-association-placeholder";
      loading.appendChild(placeholder);
    }
    panel.appendChild(loading);
  } else {
    const choices = document.createElement("div");
    choices.className = "dialogue-association-options";
    state.options.forEach((option) => {
      const button = document.createElement("button");
      const selected = option.label === state.selectedLabel;
      button.type = "button";
      button.className = `dialogue-association-option${selected ? " is-selected" : ""}`;
      button.textContent = selected && state.status === "generating" ? `${option.label} · 正在构思` : option.label;
      button.disabled = state.status === "generating";
      button.addEventListener("click", () => {
        handleDialogueAssociationChoice(option).catch((error) => {
          console.warn("dialogue association choice failed", error);
        });
      });
      choices.appendChild(button);
    });
    panel.appendChild(choices);
    if (state.error) {
      const errorRow = document.createElement("div");
      errorRow.className = "dialogue-association-error-row";
      const error = document.createElement("small");
      error.className = "dialogue-association-error";
      error.textContent = state.error;
      errorRow.appendChild(error);
      if (state.status === "error") {
        const retry = document.createElement("button");
        retry.type = "button";
        retry.className = "dialogue-association-retry";
        retry.textContent = "重试";
        retry.addEventListener("click", () => {
          requestDialogueAssociations(currentDialogueSession).catch((retryError) => {
            console.warn("dialogue associations retry failed", retryError);
          });
        });
        errorRow.appendChild(retry);
      }
      panel.appendChild(errorRow);
    }
  }

  root.appendChild(panel);
  scrollTranscriptToBottom();
}

async function requestDialogueAssociations(session = currentDialogueSession) {
  if (!dialogueAssociationsEnabled) return;
  const mode = session?.mode || session?.session_card?.mode || "";
  if (mode === "observe") {
    clearDialogueAssociations();
    return;
  }
  const runId = String(currentRunId || "").trim();
  const sessionId = String(session?.session_id || currentDialogueSessionId || "").trim();
  if (!runId || !sessionId) return;
  const requestId = ++dialogueAssociationRequestId;
  dialogueAssociationState = {
    sessionId,
    status: "loading",
    options: [],
    selectedLabel: "",
    error: "",
  };
  renderDialogueAssociations();
  try {
    const payload = await apiJson(
      `/api/web/runs/${runId}/dialogue/sessions/${sessionId}/associations`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ option_count: 3 }),
      },
      "剧情联想生成失败。"
    );
    if (requestId !== dialogueAssociationRequestId || sessionId !== currentDialogueSessionId) return;
    const options = Array.isArray(payload?.options)
      ? payload.options
          .map((item) => ({
            label: String(item?.label || "").trim(),
            direction: String(item?.direction || "").trim(),
            suggestion: String(item?.suggestion || item?.draft || "").trim(),
          }))
          .filter((item) => item.label && item.direction)
          .slice(0, 4)
      : [];
    dialogueAssociationState = {
      sessionId,
      status: options.length ? "ready" : "idle",
      options,
      selectedLabel: "",
      error: "",
    };
    renderDialogueAssociations();
  } catch (error) {
    if (requestId !== dialogueAssociationRequestId || sessionId !== currentDialogueSessionId) return;
    dialogueAssociationState = {
      sessionId,
      status: "error",
      options: [],
      selectedLabel: "",
      error: error?.message || "剧情联想暂时没有生成成功。",
    };
    renderDialogueAssociations();
    console.warn("dialogue associations failed", error);
  }
}

function dialogueAssociationRequestKey(session = currentDialogueSession) {
  const sessionId = String(session?.session_id || "").trim();
  const transcript = Array.isArray(session?.transcript) ? session.transcript : [];
  const latest = transcript.length ? transcript[transcript.length - 1] || {} : {};
  return [
    sessionId,
    String(session?.updated_at || "").trim(),
    transcript.length,
    String(latest?.message || "").trim(),
  ].join("::");
}

function maybeRequestDialogueAssociations(session = currentDialogueSession) {
  const sessionId = String(session?.session_id || "").trim();
  const transcript = Array.isArray(session?.transcript) ? session.transcript : [];
  const mode = session?.mode || session?.session_card?.mode || "";
  if (!dialogueAssociationsEnabled || mode === "observe" || !sessionId || !transcript.length || observeAutoMode) return;
  const requestKey = dialogueAssociationRequestKey(session);
  if (!requestKey || requestKey === dialogueAssociationLastRequestKey) return;
  dialogueAssociationLastRequestKey = requestKey;
  return requestDialogueAssociations(session).catch((error) => {
    console.warn("dialogue associations failed", error);
  });
}

async function handleDialogueAssociationChoice(option) {
  const label = String(option?.label || "").trim();
  const direction = String(option?.direction || "").trim();
  const prefetchedSuggestion = String(option?.suggestion || option?.draft || "").trim();
  const sessionId = String(currentDialogueSessionId || "").trim();
  if (!label || !direction || !currentRunId || !sessionId || dialogueAssociationState.status === "generating") {
    return;
  }

  dialogueAssociationState = {
    ...dialogueAssociationState,
    status: "generating",
    selectedLabel: label,
    error: "",
  };
  renderDialogueAssociations();
  setSuggestingState(true);
  try {
    let suggestion = prefetchedSuggestion;
    if (!suggestion) {
      const payload = await apiJson(
        `/api/web/runs/${currentRunId}/dialogue/sessions/${sessionId}/suggest`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ seed_text: "", direction }),
        },
        "按这个方向生成文案失败。"
      );
      suggestion = String(payload?.suggestion || "").trim();
    }
    if (sessionId !== currentDialogueSessionId) {
      setSuggestingState(false);
      return;
    }
    if (!suggestion) throw new Error("模型没有返回可发送的文案。");
    setSuggestingState(false);
    clearDialogueAssociations();
    const mode = currentDialogueSession?.mode || currentDialogueSession?.session_card?.mode || "";
    const sent = await handleSendTurn(suggestion, mode === "observe" ? "narration" : "dialogue");
    if (!sent) {
      setComposerDraft(suggestion, { focus: true });
      setDialogueSessionFailure(
        "文案已经生成，但这次没有发送成功。",
        "内容已放回输入框，可以直接重试。",
        true
      );
    }
  } catch (error) {
    if (sessionId !== currentDialogueSessionId) {
      setSuggestingState(false);
      return;
    }
    setSuggestingState(false);
    dialogueAssociationState = {
      ...dialogueAssociationState,
      status: "ready",
      selectedLabel: "",
      error: error?.message || "这个方向暂时没有生成成功，请再试一次。",
    };
    renderDialogueAssociations();
  }
}

async function handleSendTurn(messageOverride = "", messageKindOverride = "", options = {}) {
  if (!currentRunId || !currentDialogueSessionId) {
    setComposerWaiting(false, "先进入这一幕，再把话递出去。");
    return false;
  }
  const messageKind = normalizeDialogueMessageKind(messageKindOverride || readDialogueMessageKind());
  const requestedMessage = coerceMessageOverride(messageOverride).trim() || trimmedValue("dialogue-message", "");
  const automaticPlotPush = messageKind === "plot" && !requestedMessage;
  const message = requestedMessage || (automaticPlotPush ? DIALOGUE_AUTO_PLOT_PUSH_MESSAGE : "");
  const silentOptimistic = Boolean(options?.silentOptimistic);
  const suppressTranscriptMessage = Boolean(options?.suppressTranscriptMessage) || messageKind === "plot";
  if (!message) {
    setComposerWaiting(false, "先写一句你想让他们听见的话。");
    return false;
  }

  dismissMobileDialogueKeyboard();
  clearDialogueAssociations();

  const sessionSnapshot = currentDialogueSession
    ? JSON.parse(
        JSON.stringify({
          ...currentDialogueSession,
          transcript: window.stripFailedSendTranscript(currentDialogueSession.transcript),
        })
      )
    : null;
  setComposerDraft("", { publish: true });
  const retryFeedbackTimer = window.setTimeout(() => {
    setComposerWaiting(true, DIALOGUE_SEND_RETRY_MESSAGE);
  }, DIALOGUE_RETRY_FEEDBACK_DELAY_MS);
  setComposerWaiting(true, DIALOGUE_PLACEHOLDER_WAITING);

  if (currentDialogueSession && !silentOptimistic) {
    currentDialogueSession = {
      ...currentDialogueSession,
      transcript: window.buildOptimisticTranscript(currentDialogueSession, message, messageKind),
    };
    renderDialogueTranscript(currentDialogueSession);
    if (typeof UI_BRIDGE_TOOLS?.syncLegacyUiState === "function") {
      UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-optimistic", {
        currentDialogueSessionId,
        currentDialogueSession,
      });
    } else if (typeof publishLegacyUiState === "function") {
      publishLegacyUiState("dialogue-session-optimistic", {
        currentDialogueSessionId,
        currentDialogueSession,
      });
    }
  }

  try {
    const session = await apiJson(
      `/api/web/runs/${currentRunId}/dialogue/sessions/${currentDialogueSessionId}/reply`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message,
          message_kind: messageKind,
          suppress_transcript_message: suppressTranscriptMessage,
          include_inner_thoughts: dialogueInnerThoughtsEnabled,
        }),
      },
      "发送失败。"
    );
    await renderDialogueSession(session);
    window.clearTimeout(retryFeedbackTimer);
    setComposerWaiting(false, "");
    setComposerDraft("", { publish: true });
    return true;
  } catch (error) {
    window.clearTimeout(retryFeedbackTimer);
    const errorText = String(error?.message || "").trim() || "这句话暂时没有送达。";
    if (sessionSnapshot && !silentOptimistic) {
      if (typeof UI_BRIDGE_TOOLS?.syncLegacyUiState === "function") {
        UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-restore", {
          currentDialogueSessionId,
          currentDialogueSession: sessionSnapshot,
        });
      } else if (typeof publishLegacyUiState === "function") {
        publishLegacyUiState("dialogue-session-restore", {
          currentDialogueSessionId,
          currentDialogueSession: sessionSnapshot,
        });
      }
      const failedSession = {
        ...sessionSnapshot,
        transcript: window.buildFailedSendTranscript(sessionSnapshot, message, messageKind, errorText),
      };
      currentDialogueSession = failedSession;
      renderDialogueTranscript(failedSession);
      if (typeof UI_BRIDGE_TOOLS?.syncLegacyUiState === "function") {
        UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-failed", {
          currentDialogueSessionId,
          currentDialogueSession: failedSession,
        });
      } else if (typeof publishLegacyUiState === "function") {
        publishLegacyUiState("dialogue-session-failed", {
          currentDialogueSessionId,
          currentDialogueSession: failedSession,
        });
      }
    }
    if (silentOptimistic && requestedMessage) {
      setComposerDraft(requestedMessage, { publish: true });
    }
    setComposerWaiting(false, "");
    return false;
  }
}

function buildObserveAutoContinueMessage() {
  const hint = String(currentDialogueSession?.runtime_state_overview?.next_hint || "").trim();
  const dynamic = buildObserveQuickReplies(currentDialogueSession)
    .map((item) => String(item?.value || "").trim())
    .filter(Boolean);
  const pool = uniq([hint, ...dynamic].filter(Boolean));
  const transcript = Array.isArray(currentDialogueSession?.transcript) ? currentDialogueSession.transcript : [];
  const lastUserLike = [...transcript].reverse().find((item) => {
    const role = String(item?.role || "").trim();
    return role === "user" || role === "director" || role === "scene";
  });
  const lastSent = String(lastUserLike?.message || "").trim();
  const recent = new Set([lastSent, ...observeAutoRecentPrompts].filter(Boolean));
  const candidate = pool.find((text) => !recent.has(text)) || pool.find((text) => text !== lastSent) || pool[0] || "继续聊。";
  observeAutoRecentPrompts = [candidate, ...observeAutoRecentPrompts.filter((item) => item !== candidate)].slice(0, 4);
  return candidate;
}

async function runObserveAutoLoop() {
  if (!observeAutoMode || observeAutoLoopBusy) return;
  if (!currentDialogueSessionId || (currentDialogueSession?.mode || "") !== "observe") {
    stopObserveAutoLoop();
    return;
  }
  observeAutoLoopBusy = true;
  observeAutoSessionId = currentDialogueSessionId;
  setObserveAutoUiState();
  try {
    while (observeAutoMode) {
      if (!currentDialogueSessionId || currentDialogueSessionId !== observeAutoSessionId) {
        stopObserveAutoLoop();
        break;
      }
      if ((currentDialogueSession?.mode || "") !== "observe") {
        stopObserveAutoLoop();
        break;
      }
      const ok = await handleSendTurn(buildObserveAutoContinueMessage(), "narration", {
        silentOptimistic: true,
        suppressTranscriptMessage: true,
      });
      if (!ok) {
        stopObserveAutoLoop({
          reason: "连续旁观已暂停：刚才这一轮发送失败。",
        });
        break;
      }
      await new Promise((resolve) => window.setTimeout(resolve, OBSERVE_AUTO_LOOP_DELAY_MS));
    }
  } finally {
    observeAutoLoopBusy = false;
    setObserveAutoUiState();
  }
}

function toggleObserveAutoMode() {
  const mode = currentDialogueSession?.mode || currentDialogueSession?.session_card?.mode || "";
  if (mode !== "observe" || !currentDialogueSessionId) {
    setDialogueSessionFailure("先进入旁观模式会话，再开启连续旁观。", "切到旁观后再打开这个开关。", true);
    return;
  }
  if (observeAutoMode) {
    stopObserveAutoLoop({
      reason: "已停止连续旁观。",
    });
    return;
  }
  observeAutoMode = true;
  observeAutoSessionId = currentDialogueSessionId;
  setDialogueSessionSuccess("连续旁观已开启。", "系统会自动一轮接一轮推进，直到你手动停止。");
  setObserveAutoUiState();
  runObserveAutoLoop().catch((error) => {
    stopObserveAutoLoop({
      reason: error?.message || "连续旁观已暂停：发生异常。",
    });
  });
}

async function handleSuggestTurn(event) {
  if (event && typeof event.preventDefault === "function") {
    event.preventDefault();
  }
  console.log("[dialogue suggest] click", {
    runId: currentRunId,
    sessionId: currentDialogueSessionId,
  });
  if (!currentRunId || !currentDialogueSessionId) {
    return;
  }

  const area = el("dialogue-message");
  if (!area) return;

  const draftText = String(area.value || "");
  const seedText = draftText.trim();
  area.value = DIALOGUE_SUGGESTION_WAITING;
  resizeComposer();
  setSuggestingState(true);
  const retryFeedbackTimer = window.setTimeout(() => {
    area.value = DIALOGUE_SUGGEST_RETRY_MESSAGE;
    resizeComposer();
    publishComposerUiState("composer-suggest-retrying");
  }, DIALOGUE_RETRY_FEEDBACK_DELAY_MS);

  try {
    console.log("[dialogue suggest] request", { seedText });
    const payload = await apiJson(
      `/api/web/runs/${currentRunId}/dialogue/sessions/${currentDialogueSessionId}/suggest`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ seed_text: seedText }),
      },
      "续写建议生成失败。"
    );
    console.log("[dialogue suggest] success", payload);
    window.clearTimeout(retryFeedbackTimer);
    area.value = payload.suggestion || "";
    area.focus();
    area.setSelectionRange(area.value.length, area.value.length);
    resizeComposer();
  } catch (error) {
    console.log("[dialogue suggest] error", error);
    window.clearTimeout(retryFeedbackTimer);
    area.value = draftText;
    resizeComposer();
  } finally {
    setSuggestingState(false);
  }
}

function bindEvents() {
  initializeDialogueComposerEditor();
  if (typeof initAppConfirmModal === "function") {
    initAppConfirmModal();
  }
  bind("open-bookshelf-button", "click", showBookshelfHome);
  bind("open-settings-button", "click", openSettingsModal);
  bind("open-settings-primary", "click", openSettingsModal);
  bind("close-settings-button", "click", closeSettingsModal);
  bind("close-persona-review-button", "click", closePersonaReviewModal);
  bind("close-relation-details-button", "click", closeRelationDetailsModal);
  bind("close-scene-card-button", "click", closeSceneCardModal);
  bind("close-self-card-button", "click", closeSelfCardModal);
  bind("close-opening-preset-button", "click", closeOpeningPresetModal);
  bind("close-builtin-novel-button", "click", closeBuiltinNovelModal);
  bind("close-app-update-button", "click", dismissAppUpdateModal);
  bind("dismiss-app-update-button", "click", dismissAppUpdateModal);
  bind("confirm-app-update-button", "click", handleConfirmAppUpdate);
  bind("toggle-sidebar-button", "click", () => {
    if (typeof toggleMobileSessionDrawer === "function") {
      toggleMobileSessionDrawer();
      return;
    }
    sidebarCollapsed = !sidebarCollapsed;
    applySidebarState();
  });
  bind("new-dialogue-session-button", "click", openNewDialogueSession);
  bind("bookshelf-open-builtin-button", "click", () => {
    handleOpenBuiltinNovelModal().catch((error) =>
      setFlowFailureStatus("builtin-novel-status", error.message || "内置小说列表暂时没有载入。", "可以稍后重试，或先从本地导入小说包。", { affectsChatFlow: false })
    );
  });
  bind("bookshelf-import-run-button", "click", triggerImportRunPackage);
  bind("refresh-builtin-novels-button", "click", () => {
    loadBuiltinNovels().catch(() => {});
  });
  bind("bookshelf-new-run-button", "click", startNewRunFlow);
  bind("back-from-distill-button", "click", showBookshelfHome);
  bind("import-run-package-input", "change", handleImportRunPackage);
  bind("detail-start-chat-button", "click", openNewDialogueSession);
  bind("quick-open-observe-button", "click", () => {
    openQuickDialogueMode("observe").catch((error) =>
      setDialogueSessionFailure(error.message || "这一幕暂时没有铺开。", "可以稍后重试，或先检查人物与场景卡。", true)
    );
  });
  bind("quick-open-act-button", "click", () => {
    openQuickDialogueMode("act").catch((error) =>
      setDialogueSessionFailure(error.message || "这一幕暂时没有铺开。", "可以稍后重试，或先检查人物与场景卡。", true)
    );
  });
  bind("quick-open-insert-button", "click", () => {
    openQuickDialogueMode("insert").catch((error) =>
      setDialogueSessionFailure(error.message || "这一幕暂时没有铺开。", "可以稍后重试，或先检查人物与场景卡。", true)
    );
  });
  bind("detail-stop-run-button", "click", handleStopRun);
  bind("open-persona-review-button", "click", () => {
    openWorkCharacterReview().catch((error) => {
      setFlowFailureStatus("bookshelf-status", error.message || "人物档案暂时没有载入。", "可以稍后重试，或先回到书卷页刷新。", { affectsChatFlow: false });
    });
  });
  bind("open-relation-details-button", "click", openRelationDetails);
  bind("detail-export-summary-button", "click", () => {
    if (typeof openWorkSummaryExport === "function") {
      openWorkSummaryExport();
      return;
    }
    openWorkSummaryExportFallback();
  });
  bind("detail-export-package-button", "click", handleExportRunPackage);
  bind("close-run-package-share-button", "click", closeRunPackageShareModal);
  bind("run-package-share-cancel-button", "click", closeRunPackageShareModal);
  bind("run-package-share-confirm-button", "click", handleConfirmRunPackageShare);
  bind("detail-view-timeline-button", "click", () => {
    if (typeof openWorkTimeline === "function") {
      openWorkTimeline();
      return;
    }
    openWorkTimelineFallback();
  });
  bind("back-to-work-overview-button", "click", () => {
    characterOverviewOpen = false;
    updateWorkflowState();
  });
  bind("character-overview-review-button", "click", () => {
    if (!currentCharacterOverview?.character) return;
    openPersonaReviewForCharacter(currentCharacterOverview.character).catch((error) =>
      setPersonaReviewFailure(error.message || "人物档案暂时没有载入。", "可以稍后重试，或先回到书卷页刷新。")
    );
  });
  bind("character-overview-redistill-button", "click", () => {
    if (typeof openCharacterOverviewIncrementalDistill === "function") {
      openCharacterOverviewIncrementalDistill();
      return;
    }
    if (!openCharacterOverviewIncrementalDistillViaBridge()) {
      setFlowFailureStatus("redistill-status", "角色增量能力暂时没有载入。", "可以稍后重试，或回到书卷页继续蒸馏。", { affectsChatFlow: false });
    }
  });
  bind("character-overview-act-button", "click", () => {
    if (typeof openCharacterOverviewSessionMode === "function") {
      openCharacterOverviewSessionMode("act").catch((error) =>
        setDialogueSessionFailure(error.message || "这一幕暂时没有铺开。", "可以稍后重试，或先检查人物与场景卡。", true)
      );
      return;
    }
    openCharacterOverviewSessionModeViaBridge("act").catch((error) =>
      setDialogueSessionFailure(error.message || "这一幕暂时没有铺开。", "可以稍后重试，或先检查人物与场景卡。", true)
    );
  });
  bind("character-overview-insert-button", "click", () => {
    if (typeof openCharacterOverviewSessionMode === "function") {
      openCharacterOverviewSessionMode("insert").catch((error) =>
        setDialogueSessionFailure(error.message || "这一幕暂时没有铺开。", "可以稍后重试，或先检查人物与场景卡。", true)
      );
      return;
    }
    openCharacterOverviewSessionModeViaBridge("insert").catch((error) =>
      setDialogueSessionFailure(error.message || "这一幕暂时没有铺开。", "可以稍后重试，或先检查人物与场景卡。", true)
    );
  });
  bind("character-overview-export-button", "click", () => {
    if (typeof openCurrentCharacterProfileFile === "function") {
      openCurrentCharacterProfileFile();
      return;
    }
    if (!openCurrentCharacterProfileFileViaBridge()) {
      setFlowFailureStatus("character-overview-status", "当前人物原档暂时不可用。", "可以稍后重试，或先完成人物校对。", { affectsChatFlow: false });
    }
  });
  el("character-overview-key-fields")?.addEventListener("click", (event) => {
    if (typeof handleCharacterOverviewFieldAutofill === "function") {
      handleCharacterOverviewFieldAutofill(event);
    }
    if (typeof handleCharacterOverviewFieldSave === "function") {
      handleCharacterOverviewFieldSave(event);
    }
  });
  el("character-overview-key-fields")?.addEventListener("input", (event) => {
    if (typeof handleCharacterOverviewFieldInput === "function") {
      handleCharacterOverviewFieldInput(event);
    }
  });
  el("character-overview-advanced-groups")?.addEventListener("click", (event) => {
    if (typeof handleCharacterOverviewAdvancedGroupToggle === "function") {
      handleCharacterOverviewAdvancedGroupToggle(event);
    }
  });
  window.addEventListener("resize", () => {
    if (typeof syncViewportHeightVar === "function") {
      syncViewportHeightVar();
    }
    if (typeof applySessionListViewportLock === "function") {
      applySessionListViewportLock();
    }
  });
  bind("detail-redistill-button", "click", () => {
    if (!currentRunId) return;
    redistillPanelOpen = !redistillPanelOpen;
    renderBookshelfDetail(currentRun);
    updateWorkflowState();
    if (redistillPanelOpen) {
      el("redistill-panel")?.scrollIntoView({ behavior: "smooth", block: "nearest" });
      el("redistill-characters")?.focus();
    }
  });
  bind("source-history-toggle", "click", () => {
    sourceHistoryExpanded = !sourceHistoryExpanded;
    if (currentRun) {
      renderSourceHistory(currentRun);
    }
  });
  bind("run-character-readiness-toggle", "click", () => {
    characterReadinessExpanded = !characterReadinessExpanded;
    if (currentRun) {
      renderCharacterReadiness(currentRun);
    }
  });
  bind("work-session-preview-toggle", "click", () => {
    workSessionPreviewExpanded = !workSessionPreviewExpanded;
    if (currentRun) {
      renderWorkSessionPreview(currentRun);
    }
  });
  bind("back-to-bookshelf-button", "click", showBookshelfHome);
  bind("back-to-detail-button", "click", () => {
    chatModePickerOpen = false;
    updateWorkflowState();
  });

  bind("model-settings-form", "submit", handleModelSettingsSubmit);
  bind("persona-review-form", "submit", handlePersonaReviewSubmit);
  bind("opening-preset-form", "submit", handleOpeningPresetSubmit);
  bind("create-run-form", "submit", handleCreateRunSubmit);
  bind("redistill-button", "click", handleRedistill);
  bind("redistill-add-button", "click", handleRedistillAdd);
  bind("redistill-refresh-button", "click", handleRedistillRefresh);
  bind("redistill-recommend-button", "click", handleRedistillRecommend);
  bind("dialogue-session-form", "submit", handleDialogueSessionSubmit);
  bind("create-opening-preset-button", "click", () => openNewOpeningPreset());
  bind("edit-opening-preset-button", "click", () => openExistingOpeningPreset());
  bind("apply-opening-preset-button", "click", handleApplyOpeningPreset);
  bind("start-opening-preset-button", "click", handleStartOpeningPreset);
  bind("delete-opening-preset-button", "click", handleDeleteOpeningPreset);
  bind("recommend-scene-card-button", "click", handleRecommendSceneCard);
  bind("dialogue-live-scene-recommend", "click", (event) => {
    handleRecommendDialogueSceneCard(event).catch((error) => {
      setFlowFailureStatus("dialogue-live-scene-status", error.message || "下一幕推荐失败。", "可以稍后重试，或手动切换场景卡。", { affectsChatFlow: false });
    });
  });
  bind("dialogue-live-scene-shift-recommend", "click", (event) => {
    handleRecommendDialogueSceneCard(event, { autoApply: true }).catch((error) => {
      setFlowFailureStatus("dialogue-live-scene-status", error.message || "顺手切幕失败。", "可以稍后重试，或手动切换场景卡。", { affectsChatFlow: false });
    });
  });
  bind("dialogue-live-scene-apply", "click", handleApplyDialogueSceneCard);
  bind("create-scene-card-button", "click", handleOpenNewSceneCard);
  bind("edit-scene-card-button", "click", handleEditCurrentSceneCard);
  bind("create-self-card-button", "click", handleOpenNewSelfCard);
  bind("edit-self-card-button", "click", handleEditCurrentSelfCard);
  bind("suggest-turn-button", "click", handleSuggestTurn);
  bind("prepare-turn-button", "click", handleSendTurn);
  el("dialogue-association-toggle")?.addEventListener("change", (event) => {
    setDialogueAssociationsEnabled(Boolean(event.target?.checked));
  });
  el("dialogue-inner-thought-toggle")?.addEventListener("change", (event) => {
    setDialogueInnerThoughtsEnabled(Boolean(event.target?.checked));
  });
  syncDialogueAssociationToggle();
  syncDialogueInnerThoughtToggle();
  el("dialogue-message-kind")?.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    if (!target.classList.contains("kind-chip")) return;
    setDialogueMessageKind(target.dataset.kind || "dialogue");
  });
  bind("dialogue-memory-copy-button", "click", () => {
    if (typeof window.copyDialogueMemorySummary === "function") {
      window.copyDialogueMemorySummary();
    }
  });
  bind("dialogue-memory-toggle-button", "click", () => {
    if (typeof window.toggleDialogueMemory === "function") {
      window.toggleDialogueMemory();
    }
  });
  bind("dialogue-consistency-button", "click", () => {
    if (typeof window.openDialogueConsistencyModal === "function") {
      window.openDialogueConsistencyModal();
    }
  });
  bind("close-dialogue-consistency-modal-button", "click", () => {
    if (typeof window.closeDialogueConsistencyModal === "function") {
      window.closeDialogueConsistencyModal();
    }
  });
  bind("dialogue-consistency-modal-backdrop", "click", () => {
    if (typeof window.closeDialogueConsistencyModal === "function") {
      window.closeDialogueConsistencyModal();
    }
  });
  bind("observe-auto-toggle", "click", toggleObserveAutoMode);
  bind("close-dialogue-memory-modal-button", "click", () => {
    if (typeof window.closeDialogueMemoryModal === "function") {
      window.closeDialogueMemoryModal();
    }
  });
  bind("dialogue-memory-modal-backdrop", "click", () => {
    if (typeof window.closeDialogueMemoryModal === "function") {
      window.closeDialogueMemoryModal();
    }
  });

  bind("dialogue-mode", "change", syncModeFields);
  bind("dialogue-opening-preset", "change", handleOpeningPresetSelectionChange);
  bind("dialogue-scene-card", "change", handleSceneCardSelectionChange);
  bind("dialogue-self-card", "change", handleSelfCardSelectionChange);
  bind("persona-review-character", "change", handlePersonaCharacterChange);
  el("persona-review-form")?.addEventListener("input", (event) => {
    const target = event.target;
    if (target instanceof HTMLElement) {
      const field = PERSONA_REVIEW_FIELD_BINDINGS.find(([, id]) => id === target.id)?.[0];
      if (field) {
        clearPersonaReviewFieldAutofilled(field);
        setPersonaReviewFieldFeedback(field, "", "");
      }
    }
    syncPersonaReviewAutofillButtons();
  });
  el("persona-review-form")?.addEventListener("click", handlePersonaFieldAutofill);
  bind("dialogue-mode", "change", updateCharacterPillState);
  bind("dialogue-participants", "input", updateCharacterPillState);
  bind("dialogue-participants", "input", () => publishChatSetupState("chat-setup-participants-input"));
  bind("dialogue-controlled", "input", () => publishChatSetupState("chat-setup-controlled-input"));
  bind("dialogue-self-name", "input", () => publishChatSetupState("chat-setup-self-name-input"));
  bind("dialogue-self-identity", "input", () => publishChatSetupState("chat-setup-self-identity-input"));
  bind("dialogue-self-style", "input", () => publishChatSetupState("chat-setup-self-style-input"));
  bind("redistill-characters", "input", updateRedistillPillState);
  bind("dialogue-message", "input", () => {
    resizeComposer();
    dialogueMentionIndex = 0;
    renderDialogueMentionMenu();
    syncDialogueMentionButton();
    publishComposerUiState("composer-input");
  });
  el("dialogue-mention-menu")?.addEventListener("click", (event) => {
    const target = event.target instanceof HTMLElement ? event.target.closest("[data-mention-name]") : null;
    if (!(target instanceof HTMLElement)) return;
    insertDialogueMention(target.dataset.mentionName || "");
  });
  el("dialogue-mention-menu")?.addEventListener("mousedown", (event) => {
    if (event.target instanceof HTMLElement && event.target.closest("[data-mention-name]")) {
      event.preventDefault();
    }
  });
  el("dialogue-mention-button")?.addEventListener("mousedown", (event) => event.preventDefault());
  el("dialogue-mention-button")?.addEventListener("click", openDialogueMentionPicker);
  bind("novel-file", "change", updateNovelFileView);
  bind("characters", "input", refreshSamplingHintEstimate);
  bind("max-sentences", "input", refreshSamplingHintEstimate);
  bind("max-chars", "input", refreshSamplingHintEstimate);
  bind("redistill-novel-file", "change", updateRedistillFileView);
  bind("dialogue-message", "keydown", (event) => {
    if (removeAdjacentDialogueMention(event)) return;
    if (handleDialogueMentionKeydown(event)) return;
    if (event.key === "Enter" && event.shiftKey) {
      event.preventDefault();
      insertComposerPlainText("\n");
      return;
    }
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      el("prepare-turn-button")?.click();
    }
  });
  bind("dialogue-mode", "change", () => {
    stopObserveAutoLoop();
    setObserveAutoUiState();
  });

  bindChoiceGroup("dialogue-mode-options", "dialogue-mode", syncModeFields);
  bindChoiceGroup("dialogue-mode-options", "dialogue-mode", updateCharacterPillState);
  bindChoiceGroup("model-provider-options", "model-provider");

  document.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    if (target.dataset.closeModal === "true") {
      const modalId = target.dataset.modalId || "settings-modal";
      if (modalId === "persona-review-modal") {
        closePersonaReviewModal();
      } else if (modalId === "scene-card-modal") {
        closeSceneCardModal();
      } else if (modalId === "self-card-modal") {
        closeSelfCardModal();
      } else if (modalId === "dialogue-memory-modal") {
        if (typeof window.closeDialogueMemoryModal === "function") {
          window.closeDialogueMemoryModal();
        }
      } else if (modalId === "relation-details-modal") {
        closeRelationDetailsModal();
      } else if (modalId === "builtin-novel-modal") {
        closeBuiltinNovelModal();
      } else if (modalId === "app-update-modal") {
        dismissAppUpdateModal();
      } else if (modalId === "run-package-share-modal") {
        closeRunPackageShareModal();
      } else {
        closeSettingsModal();
      }
    }
  });
}

async function boot() {
  if (typeof syncViewportHeightVar === "function") {
    syncViewportHeightVar();
  }
  ensureConnectionDetailsVisible();
  syncModeFields();
  syncChoiceGroup("dialogue-mode-options", "dialogue-mode");
  syncChoiceGroup("model-provider-options", "model-provider");
  updateNovelFileView();
  updateRedistillFileView();
  resizeComposer();
  setDialogueMessageKind(currentDialogueMessageKind);
  applySidebarState();
  bindMobileShellDismiss();
  if (typeof bindWorkDetailNav === "function") {
    bindWorkDetailNav();
  }
  window.addEventListener("resize", () => {
    if (typeof syncMobileShellLayout === "function") {
      syncMobileShellLayout(window.__ZAOMENG_WORKFLOW_STATE__ || {});
    }
  });
  initCustomSelect("dialogue-scene-card");
  initCustomSelect("dialogue-self-card");
  try {
    await Promise.all([
      loadModelSettings().catch((error) => console.warn("loadModelSettings failed", error)),
      loadOpeningPresets().catch((error) => console.warn("loadOpeningPresets failed", error)),
      loadSceneCards().catch((error) => console.warn("loadSceneCards failed", error)),
      loadSelfCards().catch((error) => console.warn("loadSelfCards failed", error)),
      loadRecentSessions().catch((error) => console.warn("loadRecentSessions failed", error)),
      loadRunsOverview().catch((error) => console.warn("loadRunsOverview failed", error)),
    ]);
    await checkAppUpdateOnBoot();
  } finally {
    workflowBootPending = false;
    updateWorkflowState();
  }
}

bindEvents();
const chatSetupActions = {
  setMode(mode) {
    setValue("dialogue-mode", mode);
    syncModeFields();
    updateCharacterPillState();
  },
  setParticipants(value) {
    setValue("dialogue-participants", value);
    updateCharacterPillState();
    publishChatSetupState("chat-setup-participants-updated");
  },
  toggleParticipant(name) {
    toggleNameInInput("dialogue-participants", name);
    if (valueOf("dialogue-mode", "observe") === "act" && el("dialogue-controlled")) {
      setValue("dialogue-controlled", name);
    }
    updateCharacterPillState();
    publishChatSetupState("chat-setup-participant-toggled");
  },
  setControlledCharacter(value) {
    setValue("dialogue-controlled", value);
    publishChatSetupState("chat-setup-controlled-updated");
  },
  setOpeningPresetId(cardId) {
    const select = el("dialogue-opening-preset");
    if (select) {
      select.value = cardId;
    }
    syncSelectedOpeningPresetFromSelect();
  },
  applyOpeningPreset() {
    return handleApplyOpeningPreset({ preventDefault() {} });
  },
  applyOpeningPresetAndSubmit() {
    return handleStartOpeningPreset({ preventDefault() {} });
  },
  setSceneCardId(cardId) {
    const select = el("dialogue-scene-card");
    if (select) {
      select.value = cardId;
      syncCustomSelect("dialogue-scene-card");
    }
    syncSelectedSceneCardFromSelect();
  },
  setSelfCardId(cardId) {
    const select = el("dialogue-self-card");
    if (select) {
      select.value = cardId;
      syncCustomSelect("dialogue-self-card");
    }
    syncSelectedSelfCardFromSelect();
  },
  setSelfProfileField(field, value) {
    const idMap = {
      display_name: "dialogue-self-name",
      scene_identity: "dialogue-self-identity",
      interaction_style: "dialogue-self-style",
    };
    const id = idMap[field] || "";
    if (id) setValue(id, value);
    publishChatSetupState("chat-setup-self-profile-updated");
  },
  submit() {
    return handleDialogueSessionSubmit({ preventDefault() {} });
  },
  openNewOpeningPreset() {
    return openNewOpeningPreset();
  },
  editCurrentOpeningPreset() {
    return openExistingOpeningPreset();
  },
  openNewSceneCard() {
    return openNewSceneCard();
  },
  editCurrentSceneCard() {
    return selectedSceneCardId ? openExistingSceneCard(selectedSceneCardId) : openNewSceneCard();
  },
  recommendSceneCard() {
    return handleRecommendSceneCard({ preventDefault() {} });
  },
  openNewSelfCard() {
    return openNewSelfCard();
  },
  editCurrentSelfCard() {
    return selectedSelfCardId ? openExistingSelfCard(selectedSelfCardId) : openNewSelfCard();
  },
};

const composerActions = {
  send(message = "", kind = "") {
    return handleSendTurn(message, kind);
  },
  suggest() {
    return handleSuggestTurn();
  },
  setKind(kind) {
    setDialogueMessageKind(kind);
  },
  setDraft(value, options = {}) {
    setComposerDraft(value, options);
  },
  quickReply(value) {
    return applyQuickReply(value);
  },
  setAssociationEnabled(enabled) {
    setDialogueAssociationsEnabled(enabled);
  },
};

if (typeof UI_BRIDGE_TOOLS.mergeLegacyActionBridge === "function") {
  UI_BRIDGE_TOOLS.mergeLegacyActionBridge("__ZAOMENG_CHAT_SETUP_ACTIONS__", chatSetupActions);
  UI_BRIDGE_TOOLS.mergeLegacyActionBridge("__ZAOMENG_COMPOSER_ACTIONS__", composerActions);
} else {
  window.__ZAOMENG_CHAT_SETUP_ACTIONS__ = chatSetupActions;
  window.__ZAOMENG_COMPOSER_ACTIONS__ = composerActions;
}
window.handleSuggestTurn = handleSuggestTurn;
window.applyQuickReply = applyQuickReply;
window.syncSuggestButtonVisibility = syncSuggestButtonVisibility;
window.syncDialogueMessageKindVisibility = syncDialogueMessageKindVisibility;
syncSuggestButtonVisibility(null);
console.log("[zaomeng web] main.js loaded", window.__ZAOMENG_WEB_UI_VERSION__ || "unknown");
boot();
window.applyRunViewSafely = applyRunViewSafely;
window.readNamedActionBridge = readNamedActionBridge;
window.characterOverviewActions = characterOverviewActions;
window.openCharacterOverviewViaBridge = openCharacterOverviewViaBridge;
window.openCharacterOverviewIncrementalDistillViaBridge = openCharacterOverviewIncrementalDistillViaBridge;
window.openCharacterOverviewSessionModeViaBridge = openCharacterOverviewSessionModeViaBridge;
window.openCurrentCharacterProfileFileViaBridge = openCurrentCharacterProfileFileViaBridge;
window.openWorkSummaryExportFallback = openWorkSummaryExportFallback;
window.openWorkTimelineFallback = openWorkTimelineFallback;
window.buildChatSetupState = buildChatSetupState;
window.publishChatSetupState = publishChatSetupState;
window.syncModeFields = syncModeFields;
window.handleModelSettingsSubmit = handleModelSettingsSubmit;
window.handleCreateRunSubmit = handleCreateRunSubmit;
window.handleRedistill = handleRedistill;
window.handleRedistillRecommend = handleRedistillRecommend;
window.handleStopRun = handleStopRun;
window.handleRedistillAdd = handleRedistillAdd;
window.handleRedistillRefresh = handleRedistillRefresh;
window.handleDialogueSessionSubmit = handleDialogueSessionSubmit;
window.renderDialogueAssociations = renderDialogueAssociations;
window.requestDialogueAssociations = requestDialogueAssociations;
window.maybeRequestDialogueAssociations = maybeRequestDialogueAssociations;
window.setDialogueAssociationsEnabled = setDialogueAssociationsEnabled;
window.validateSceneCardPayload = validateSceneCardPayload;
window.buildSceneCardEditorState = buildSceneCardEditorState;
window.publishSceneCardEditorState = publishSceneCardEditorState;
window.openNewSceneCard = openNewSceneCard;
window.openExistingSceneCard = openExistingSceneCard;
window.renderSceneCardOptions = renderSceneCardOptions;
window.loadSceneCards = loadSceneCards;
window.syncSelectedSceneCardFromSelect = syncSelectedSceneCardFromSelect;
window.renderSelectedSceneCardPreview = renderSelectedSceneCardPreview;
window.handleSceneCardSelectionChange = handleSceneCardSelectionChange;
window.handleOpenNewSceneCard = handleOpenNewSceneCard;
window.handleEditCurrentSceneCard = handleEditCurrentSceneCard;
window.renderDialogueSceneSwitcher = renderDialogueSceneSwitcher;
window.handleApplyDialogueSceneCard = handleApplyDialogueSceneCard;
window.handleRecommendDialogueSceneCard = handleRecommendDialogueSceneCard;
window.applyDialogueSceneTimelineEntry = applyDialogueSceneTimelineEntry;
window.branchDialogueSessionFromScene = branchDialogueSessionFromScene;
window.handleRecommendSceneCard = handleRecommendSceneCard;
window.validateSelfCardPayload = validateSelfCardPayload;
window.buildSelfCardEditorState = buildSelfCardEditorState;
window.publishSelfCardEditorState = publishSelfCardEditorState;
window.openNewSelfCard = openNewSelfCard;
window.openExistingSelfCard = openExistingSelfCard;
window.renderSelfCardOptions = renderSelfCardOptions;
window.loadSelfCards = loadSelfCards;
window.syncSelectedSelfCardFromSelect = syncSelectedSelfCardFromSelect;
window.renderSelectedSelfCardPreview = renderSelectedSelfCardPreview;
window.handleSelfCardSelectionChange = handleSelfCardSelectionChange;
window.handleOpenNewSelfCard = handleOpenNewSelfCard;
window.handleEditCurrentSelfCard = handleEditCurrentSelfCard;
window.openPersonaReviewForCharacter = openPersonaReviewForCharacter;
window.openPersonaReview = openPersonaReview;
window.openWorkCharacterReview = openWorkCharacterReview;
window.openQuickDialogueMode = openQuickDialogueMode;
window.handlePersonaCharacterChange = handlePersonaCharacterChange;
window.collectPersonaReviewPayload = collectPersonaReviewPayload;
window.handlePersonaFieldAutofill = handlePersonaFieldAutofill;
window.handlePersonaReviewSubmit = handlePersonaReviewSubmit;
window.openRelationDetails = openRelationDetails;
window.renderBuiltinNovelList = renderBuiltinNovelList;
window.loadBuiltinNovels = loadBuiltinNovels;
window.handleOpenBuiltinNovelModal = handleOpenBuiltinNovelModal;
window.handleCloneBuiltinNovel = handleCloneBuiltinNovel;
window.triggerImportRunPackage = triggerImportRunPackage;
window.handleImportRunPackage = handleImportRunPackage;
window.isRunPackageExportPending = isRunPackageExportPending;
window.handleExportRunPackage = handleExportRunPackage;
window.openRunPackageShareModal = openRunPackageShareModal;
window.closeRunPackageShareModal = closeRunPackageShareModal;
window.handleConfirmRunPackageShare = handleConfirmRunPackageShare;
window.buildComposerUiState = buildComposerUiState;
window.publishComposerUiState = publishComposerUiState;
window.normalizeDialogueMessageKind = normalizeDialogueMessageKind;
window.readDialogueMessageKind = readDialogueMessageKind;
window.updateDialogueMessagePlaceholder = updateDialogueMessagePlaceholder;
window.setDialogueMessageKind = setDialogueMessageKind;
window.setQuickRepliesEnabled = setQuickRepliesEnabled;
window.setObserveAutoUiState = setObserveAutoUiState;
window.stopObserveAutoLoop = stopObserveAutoLoop;
window.syncSuggestButtonVisibility = syncSuggestButtonVisibility;
window.setComposerWaiting = setComposerWaiting;
window.setSuggestingState = setSuggestingState;
window.renderObserveQuickReplies = renderObserveQuickReplies;
window.applyQuickReply = applyQuickReply;
window.setComposerDraft = setComposerDraft;
window.coerceMessageOverride = coerceMessageOverride;
window.handleSendTurn = handleSendTurn;
window.toggleObserveAutoMode = toggleObserveAutoMode;
window.handleSuggestTurn = handleSuggestTurn;
window.bindEvents = bindEvents;
window.boot = boot;
window.__ZAOMENG_MAIN_MODULE__ = {
  initialized: true,
  version: String(window.__ZAOMENG_WEB_UI_VERSION__ || ""),
};
})();
