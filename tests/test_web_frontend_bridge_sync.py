from __future__ import annotations

import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
JS_ROOT = REPO_ROOT / "src" / "web" / "static" / "js"
FRAGMENT_ROOT = REPO_ROOT / "src" / "web" / "static" / "fragments"


def read_js(name: str) -> str:
    return (JS_ROOT / name).read_text(encoding="utf-8")


def read_fragment(name: str) -> str:
    return (FRAGMENT_ROOT / name).read_text(encoding="utf-8")


class WebFrontendBridgeSyncTests(unittest.TestCase):
    def test_bootstrap_loads_webui_api_before_bookshelf_island(self):
        content = read_js("bootstrap.js")
        api_index = content.index('/web/js/webui-api.js?v=${version}')
        main_index = content.index('/web/js/main.js?v=${version}')
        island_index = content.index('/web/js/bookshelf-vue-island.js?v=${version}')
        self.assertLess(api_index, main_index)
        self.assertLess(api_index, island_index)

    def test_bootstrap_loads_feedback_and_update_modules_before_main(self):
        content = read_js("bootstrap.js")
        feedback_index = content.index('/web/js/flow-feedback.js?v=${version}')
        update_index = content.index('/web/js/app-update.js?v=${version}')
        main_index = content.index('/web/js/main.js?v=${version}')
        self.assertLess(feedback_index, update_index)
        self.assertLess(update_index, main_index)

        main_content = read_js("main.js")
        update_content = read_js("app-update.js")
        self.assertNotIn("function openAppUpdateModal()", main_content)
        self.assertIn("function openAppUpdateModal()", update_content)
        self.assertIn("window.__ZAOMENG_APP_UPDATE__", update_content)

    def test_bootstrap_keeps_optional_islands_non_fatal(self):
        content = read_js("bootstrap.js")
        self.assertIn("const coreScripts = [", content)
        self.assertIn("const optionalScripts = [", content)
        self.assertIn("await loadScriptBatch(coreScripts);", content)
        self.assertIn("await loadScriptBatch(optionalScripts, { continueOnError: true });", content)
        self.assertIn("renderBootFailure(error);", content)

    def test_scene_and_self_card_islands_are_required_after_main(self):
        content = read_js("bootstrap.js")
        core_scripts = content.split("const coreScripts = [", 1)[1].split("];", 1)[0]
        optional_scripts = content.split("const optionalScripts = [", 1)[1].split("];", 1)[0]
        main_index = core_scripts.index('/web/js/main.js?v=${version}')
        scene_index = core_scripts.index('/web/js/scene-card-vue-island.js?v=${version}')
        self_index = core_scripts.index('/web/js/self-card-vue-island.js?v=${version}')
        self.assertLess(main_index, scene_index)
        self.assertLess(scene_index, self_index)
        self.assertNotIn("scene-card-vue-island.js", optional_scripts)
        self.assertNotIn("self-card-vue-island.js", optional_scripts)

    def test_card_modals_only_keep_vue_editor_surfaces(self):
        fragment = read_fragment("settings-modal.html")
        main_content = read_js("main.js")
        scene_content = read_js("scene-card-vue-island.js")
        self_content = read_js("self-card-vue-island.js")

        self.assertIn('id="scene-card-vue-root"', fragment)
        self.assertIn('id="self-card-vue-root"', fragment)
        self.assertNotIn('id="scene-card-form"', fragment)
        self.assertNotIn('id="self-card-form"', fragment)
        self.assertNotIn('id="scene-card-status"', fragment)
        self.assertNotIn('id="self-card-status"', fragment)

        for handler in (
            "handleGenerateSceneCard",
            "handleSceneCardSubmit",
            "handleDeleteSceneCard",
            "handleDuplicateSceneCard",
            "handleGenerateSelfCard",
            "handleSelfCardSubmit",
            "handleDeleteSelfCard",
        ):
            self.assertNotIn(handler, main_content)

        self.assertIn("publishSceneCardEditorState(source, {", scene_content)
        self.assertIn("publishSelfCardEditorState(source, {", self_content)
        self.assertNotIn("syncLegacyFields", scene_content)
        self.assertNotIn("syncLegacyFields", self_content)

    def test_dialogue_publish_includes_session_payloads_for_bridge_sync(self):
        content = read_js("dialogue.js")
        self.assertIn("const UI_BRIDGE_TOOLS = window.__ZAOMENG_UI_BRIDGE_TOOLS__ || {};", content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-rendered", {', content)
        self.assertIn("currentDialogueSessionId,", content)
        self.assertIn("currentDialogueSession: session,", content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-booting", {', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-restore", {', content)
        self.assertIn('window.applyDialogueSceneTimelineEntry(item);', content)
        self.assertIn('window.branchDialogueSessionFromScene(index);', content)

    def test_dialogue_renders_generation_cache_hit_rates_in_composer_utility_row(self):
        content = read_js("dialogue.js")
        fragment = read_fragment("main-shell.html")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")
        memory_meta = fragment.split('<div class="dialogue-memory-meta">', 1)[1].split("</div>", 1)[0]
        utility_row = fragment.split('id="dialogue-association-toggle-row"', 1)[1].split("</div>", 1)[0]

        self.assertIn("function normalizeGenerationCacheMetric(source) {", content)
        self.assertIn('source.observed === false || unsupportedStatuses.has(status)', content)
        self.assertIn('if (!metric?.observed || metric.hitRate === null) return "-";', content)
        self.assertIn('root.textContent = `本次命中 ${latestRate} ｜ 平均命中 ${sessionRate}`;', content)
        self.assertIn("缓存读取 ${formatGenerationCacheTokenCount(metric.cacheReadTokens)} / 输入", content)
        self.assertIn("renderDialogueGenerationCacheStats(session);", content)
        self.assertNotIn('id="dialogue-cache-stats"', memory_meta)
        self.assertIn('id="dialogue-cache-stats" class="dialogue-cache-stats"', utility_row)
        self.assertIn("本次命中 - ｜ 平均命中 -", fragment)
        self.assertNotIn('id="dialogue-cache-latest"', fragment)
        self.assertNotIn('id="dialogue-cache-session"', fragment)
        self.assertIn(".dialogue-cache-stats {", styles)
        self.assertNotIn(".dialogue-cache-stat {", styles)
        self.assertIn("#dialogue-association-toggle-row", styles)

    def test_dialogue_renders_consistency_quality_history(self):
        content = read_js("dialogue.js")
        main = read_js("main.js")
        shell = (REPO_ROOT / "src" / "web" / "static" / "fragments" / "main-shell.html").read_text(encoding="utf-8")
        modals = (REPO_ROOT / "src" / "web" / "static" / "fragments" / "settings-modal.html").read_text(encoding="utf-8")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn("function appendConsistencyQualityPanel(card, monitor) {", content)
        self.assertIn("function consistencyMetricCategoryLabel(category) {", content)
        self.assertIn('strip.className = "consistency-quality-strip";', content)
        self.assertIn('details.className = "consistency-quality-details";', content)
        self.assertIn(".consistency-score-trend {", styles)
        self.assertIn(".consistency-category-counts {", styles)
        self.assertIn("async function deepReviewLatestConsistencyTurn(button) {", content)
        self.assertIn('reviewButton.dataset.consistencyReview = "true";', content)
        self.assertIn(".consistency-review-button {", styles)
        self.assertIn('id="dialogue-consistency-button"', shell)
        self.assertIn('id="dialogue-consistency-modal"', modals)
        self.assertIn("function renderDialogueConsistencyMonitor(monitor) {", content)
        self.assertIn("window.openDialogueConsistencyModal = openDialogueConsistencyModal;", content)
        self.assertIn('bind("dialogue-consistency-button", "click"', main)
        transcript_renderer = content.split("function renderTranscript(items) {", 1)[1].split("function renderSessionBooting", 1)[0]
        self.assertNotIn("appendConsistencyMonitor(root", transcript_renderer)
        self.assertIn(".dialogue-consistency-launcher {", styles)

    def test_dialogue_event_timeline_and_turn_branch_are_wired(self):
        dialogue = read_js("dialogue.js")
        api = read_js("webui-api.js")
        shell = (REPO_ROOT / "src" / "web" / "static" / "fragments" / "main-shell.html").read_text(encoding="utf-8")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn('id="dialogue-event-timeline"', shell)
        self.assertIn("function renderDialogueEventTimeline(session)", dialogue)
        self.assertIn("branchDialogueSessionFromTurn", dialogue)
        self.assertIn("/branch-turn", api)
        self.assertIn(".dialogue-event-timeline {", styles)
        branch_handler = dialogue.split(
            "async function branchDialogueSessionFromTurn(turnId, button) {", 1
        )[1].split("function createRelationChartNode", 1)[0]
        self.assertIn("branchSessionId === sourceSessionId", branch_handler)
        self.assertIn("closeDialogueMemoryModal();", branch_handler)
        self.assertIn("已从“${branchTitle}”切换到新分支", branch_handler)
        self.assertIn('button.textContent === "正在创建分支..."', branch_handler)
        self.assertIn("button.disabled = false;", branch_handler)
        self.assertIn("controller.abort()", api)
        self.assertIn("剧情节点回溯请求超时", api)
        memory_renderer = dialogue.split("function renderDialogueMemory(session) {", 1)[
            1
        ].split("function directorActionLabel", 1)[0]
        self.assertIn(
            "branchOrigin?.scene_title || branchOrigin?.event_title",
            memory_renderer,
        )

    def test_dialogue_relation_evolution_and_lock_are_wired(self):
        dialogue = read_js("dialogue.js")
        api = read_js("webui-api.js")
        shell = (REPO_ROOT / "src" / "web" / "static" / "fragments" / "main-shell.html").read_text(encoding="utf-8")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn('id="dialogue-relation-evolution"', shell)
        self.assertIn("function renderDialogueRelationEvolution(session)", dialogue)
        self.assertIn("function updateDialogueRelationLock(timeline, button)", dialogue)
        self.assertIn("createRelationChartNode", dialogue)
        self.assertIn("/relation-lock", api)
        self.assertIn(".dialogue-relation-evolution {", styles)

    def test_dialogue_controlled_memory_and_context_usage_are_wired(self):
        dialogue = read_js("dialogue.js")
        api = read_js("webui-api.js")
        shell = (REPO_ROOT / "src" / "web" / "static" / "fragments" / "main-shell.html").read_text(encoding="utf-8")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn('id="dialogue-memory-control"', shell)
        self.assertIn('id="dialogue-context-usage-list"', shell)
        self.assertIn("function renderDialogueControlledMemories(session)", dialogue)
        self.assertIn("function renderDialogueContextUsage(session)", dialogue)
        self.assertIn("createDialogueMemory", api)
        self.assertIn("updateDialogueMemory", api)
        self.assertIn("deleteDialogueMemory", api)
        self.assertIn(".dialogue-memory-control {", styles)

    def test_dialogue_speaker_balance_panel_is_wired(self):
        dialogue = read_js("dialogue.js")
        shell = (REPO_ROOT / "src" / "web" / "static" / "fragments" / "main-shell.html").read_text(encoding="utf-8")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn('id="dialogue-speaker-balance"', shell)
        self.assertIn("function renderDialogueSpeakerBalance(session)", dialogue)
        self.assertIn("dialogueSpeakerStatusLabel", dialogue)
        self.assertIn(".dialogue-speaker-balance {", styles)

    def test_dialogue_director_panel_and_apply_flow_are_wired(self):
        dialogue = read_js("dialogue.js")
        api = read_js("webui-api.js")
        shell = (REPO_ROOT / "src" / "web" / "static" / "fragments" / "main-shell.html").read_text(encoding="utf-8")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn('id="dialogue-director-panel"', shell)
        self.assertIn('data-director-action="slow_emotion"', shell)
        self.assertIn("function renderDialogueDirectorPanel(session)", dialogue)
        self.assertIn("function applyDialogueDirectorOption(option, button)", dialogue)
        self.assertIn('await window.handleSendTurn(plotCue, "plot")', dialogue)
        self.assertNotIn("api.suggestDialogueTurn(currentRunId, currentDialogueSessionId, direction)", dialogue)
        self.assertIn("generateDialogueDirectorOptions", api)
        self.assertIn("suggestDialogueTurn", api)
        self.assertIn(".dialogue-director-panel {", styles)

    def test_dialogue_branch_manager_and_mainline_event_lock_are_wired(self):
        dialogue = read_js("dialogue.js")
        api = read_js("webui-api.js")
        shell = read_fragment("main-shell.html")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn('id="dialogue-branch-manager"', shell)
        self.assertIn("function renderDialogueBranchManager(session)", dialogue)
        self.assertIn("function toggleDialogueMainlineEvent(turnId, locked, button)", dialogue)
        self.assertIn("updateDialogueBranchMeta", api)
        self.assertIn("/branch-meta", api)
        self.assertIn(".dialogue-branch-manager {", styles)

    def test_dialogue_chapter_outline_and_scene_reopen_are_wired(self):
        dialogue = read_js("dialogue.js")
        shell = read_fragment("main-shell.html")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn('id="dialogue-chapter-outline"', shell)
        self.assertIn("function renderDialogueChapterOutline(session)", dialogue)
        self.assertIn("function focusDialogueChapterEvent(turnId)", dialogue)
        self.assertIn("window.branchDialogueSessionFromScene", dialogue)
        self.assertIn(".dialogue-chapter-outline {", styles)

    def test_dialogue_character_growth_timeline_is_wired(self):
        dialogue = read_js("dialogue.js")
        shell = read_fragment("main-shell.html")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn('id="dialogue-character-growth"', shell)
        self.assertIn("function renderDialogueCharacterGrowth(session)", dialogue)
        self.assertIn("selectedDialogueCharacterArc", dialogue)
        self.assertIn("focusDialogueChapterEvent(turnId)", dialogue)
        self.assertIn(".dialogue-character-growth {", styles)

    def test_dialogue_generation_metrics_panel_is_wired(self):
        dialogue = read_js("dialogue.js")
        shell = read_fragment("main-shell.html")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn('id="dialogue-generation-metrics"', shell)
        self.assertIn("function renderDialogueGenerationMetrics(session)", dialogue)
        self.assertIn("formatDialogueGenerationDuration", dialogue)
        self.assertIn("formatDialogueGenerationCost", dialogue)
        self.assertIn(".dialogue-generation-metrics {", styles)

    def test_main_publishes_optimistic_dialogue_and_suggest_retry_state(self):
        content = read_js("main.js")
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-optimistic", {', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("dialogue-session-restore", {', content)
        self.assertIn('publishComposerUiState("composer-suggest-retrying");', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("relation-details-loading", { currentRelationDetails: null });', content)
        self.assertIn("window.applyDialogueSceneTimelineEntry = applyDialogueSceneTimelineEntry;", content)
        self.assertIn("window.branchDialogueSessionFromScene = branchDialogueSessionFromScene;", content)
        self.assertIn("function renderDialogueSceneChainSuggestions(chains = [], sessionId = \"\") {", content)
        self.assertIn("function applyDialogueSceneChain(chain = {}) {", content)
        self.assertIn("function renderDialogueAssociations() {", content)
        self.assertIn("function requestDialogueAssociations(session = currentDialogueSession) {", content)
        self.assertIn("function maybeRequestDialogueAssociations(session = currentDialogueSession) {", content)
        self.assertIn("async function handleDialogueAssociationChoice(option) {", content)
        self.assertIn("window.requestDialogueAssociations = requestDialogueAssociations;", content)
        self.assertIn("window.maybeRequestDialogueAssociations = maybeRequestDialogueAssociations;", content)
        self.assertIn("/associations`", content)
        self.assertIn('body: JSON.stringify({ seed_text: "", direction })', content)
        self.assertIn('status: "error",', content)
        self.assertIn('retry.textContent = "重试";', content)

    def test_plot_push_can_advance_without_a_manual_cue(self):
        main_content = read_js("main.js")
        fragment_content = read_fragment("main-shell.html")

        self.assertIn("const DIALOGUE_AUTO_PLOT_PUSH_MESSAGE =", main_content)
        self.assertIn('const automaticPlotPush = messageKind === "plot" && !requestedMessage;', main_content)
        self.assertIn("const silentOptimistic = Boolean(options?.silentOptimistic);", main_content)
        self.assertIn('const suppressTranscriptMessage = Boolean(options?.suppressTranscriptMessage) || messageKind === "plot";', main_content)
        self.assertIn("if (silentOptimistic && requestedMessage) {", main_content)
        self.assertIn("留空则由系统主动推进", main_content)
        self.assertIn('normalizeDialogueMessageKind(currentDialogueMessageKind) === "narration"', main_content)
        self.assertIn('sendButton.textContent = isPlotPush ? "推进" : "送出";', main_content)
        self.assertIn('data-kind="plot"', fragment_content)
        self.assertIn(">推进剧情</button>", fragment_content)

    def test_mobile_dialogue_keeps_speaker_labels_and_does_not_refocus_after_send(self):
        main_content = read_js("main.js")
        dialogue_content = read_js("dialogue.js")
        app_styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "app.css").read_text(encoding="utf-8")

        self.assertIn("function shouldAutoFocusDialogueComposer()", dialogue_content)
        self.assertIn('window.matchMedia("(pointer: coarse)").matches', dialogue_content)
        self.assertIn("if (shouldAutoFocusDialogueComposer()) {", dialogue_content)
        self.assertIn("function dismissMobileDialogueKeyboard()", main_content)
        self.assertIn('el("dialogue-message")?.blur();', main_content)
        self.assertIn("dismissMobileDialogueKeyboard();", main_content)
        self.assertNotIn('setComposerDraft("", { publish: true, focus: true });', main_content)
        self.assertIn(".speaker-name {\n    display: inline-flex;", app_styles)

    def test_plot_push_renders_an_immediate_progress_state(self):
        main_content = read_js("main.js")
        dialogue_content = read_js("dialogue.js")

        self.assertIn("const silentOptimistic = Boolean(options?.silentOptimistic);", main_content)
        self.assertIn("transcript: window.buildOptimisticTranscript(currentDialogueSession, message, messageKind)", main_content)
        self.assertIn('String(messageKind || "").trim() === "plot"', dialogue_content)
        self.assertIn("正在按这个方向推进剧情...", dialogue_content)

    def test_composer_at_mentions_only_offer_present_characters(self):
        main_content = read_js("main.js")
        dialogue_content = read_js("dialogue.js")
        bootstrap_content = read_js("bootstrap.js")
        fragment_content = read_fragment("main-shell.html")
        styles = (REPO_ROOT / "src" / "web" / "static" / "styles" / "dialogue.css").read_text(encoding="utf-8")

        self.assertIn("function buildDialogueMentionCandidates(session = currentDialogueSession) {", main_content)
        self.assertIn("runtime_state_overview", main_content)
        self.assertIn("present_participants", main_content)
        self.assertIn("function extractDialogueMentionContext(value, caretPosition) {", main_content)
        self.assertIn("function collectDialogueMentionNames(value, excludedContext = null", main_content)
        self.assertIn("function availableDialogueMentionCandidates(value = \"\"", main_content)
        self.assertIn("mentionCandidates: buildDialogueMentionCandidates", main_content)
        self.assertIn("function initializeDialogueComposerEditor() {", main_content)
        self.assertIn("function renderComposerEditor(value, options = {}) {", main_content)
        self.assertIn('token.className = "composer-mention-token";', main_content)
        self.assertIn("function removeAdjacentDialogueMention(event) {", main_content)
        self.assertIn('class="composer-rich-editor"', fragment_content)
        self.assertIn('class="composer-mention-button"', fragment_content)
        self.assertIn('id="dialogue-mention-button"', fragment_content)
        self.assertIn('id="dialogue-mention-menu"', fragment_content)
        self.assertEqual(fragment_content.count('id="dialogue-message"'), 1)
        self.assertNotIn("composer-vue-island.js", bootstrap_content)
        self.assertNotIn('id="composer-vue-root"', fragment_content)
        self.assertIn(".composer-mention-menu {", styles)
        self.assertIn(".composer-mention-token {", styles)
        self.assertIn("function appendMessageTextWithMentions(target, value) {", dialogue_content)
        self.assertIn('mention.className = "message-mention";', dialogue_content)
        self.assertIn(".message-mention {", styles)

    def test_dialogue_association_toggle_persists_and_gates_requests(self):
        main_content = read_js("main.js")
        fragment_content = read_fragment("main-shell.html")

        self.assertIn(
            'const DIALOGUE_ASSOCIATION_ENABLED_KEY = "zaomeng:dialogue-associations-enabled";',
            main_content,
        )
        self.assertIn("associationEnabled: dialogueAssociationsEnabled", main_content)
        self.assertIn("function setDialogueAssociationsEnabled(enabled) {", main_content)
        self.assertIn("if (!dialogueAssociationsEnabled) return;", main_content)
        self.assertIn('el("dialogue-association-toggle")?.addEventListener("change"', main_content)
        self.assertIn('class="dialogue-association-toggle-control"', fragment_content)
        self.assertIn('id="dialogue-association-toggle"', fragment_content)

    def test_main_uses_shared_bridge_sync_for_self_card_state(self):
        content = read_js("main.js")
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("opening-presets-loaded", { openingPresets, currentOpeningPreset, selectedOpeningPresetId });', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("opening-preset-selection-changed", {', content)
        self.assertIn("selectedOpeningPresetId,", content)
        self.assertIn("currentOpeningPreset,", content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState(source, { currentSceneCardEditor });', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("scene-cards-loaded", { sceneCards });', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("scene-card-selection-changed", {', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("scene-card-recommended", { currentSceneCardRecommendation });', content)
        self.assertIn("selectedSceneCardId,", content)
        self.assertIn("currentSceneCard,", content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState(source, { currentSelfCardEditor });', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("self-cards-loaded", { selfCards });', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("self-card-selection-changed", {', content)
        self.assertIn("selectedSelfCardId,", content)
        self.assertIn("currentSelfCard,", content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState(source, { chatSetup: buildChatSetupState() });', content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState(source, { composer: buildComposerUiState() });', content)
        self.assertIn('const isInsertMode = mode === "insert";', content)
        self.assertIn('selfCardId: isInsertMode ? selectedSelfCardId : "",', content)
        self.assertIn('currentSelfCard: isInsertMode ? currentSelfCard : null,', content)
        self.assertIn('if (mode !== "insert") {', content)
        self.assertIn("clearChatSetupSelfCardSelection();", content)
        self.assertIn('UI_BRIDGE_TOOLS.syncLegacyUiState("self-card-selection-cleared", {', content)

    def test_persona_review_vue_publishes_bridge_updates_after_load_save_and_autofill(self):
        content = read_js("persona-review-vue-island.js")
        self.assertIn("bridgeTools.syncLegacyUiState(source, overrides);", content)
        self.assertIn('syncPersonaBridgeState("persona-review-vue-loaded", {', content)
        self.assertIn('syncPersonaBridgeState("persona-review-vue-saved", {', content)
        self.assertIn('syncPersonaBridgeState("persona-review-vue-autofill", {', content)
        self.assertIn('syncPersonaBridgeState("persona-review-vue-autofill-cleared", {', content)

    def test_relation_details_vue_publishes_saved_payload_back_to_bridge(self):
        content = read_js("relation-details-vue-island.js")
        self.assertIn("bridgeTools.syncLegacyUiState(source, overrides);", content)
        self.assertIn('syncRelationBridgeState("relation-details-vue-saved", { currentRelationDetails: refreshed });', content)

    def test_character_overview_actions_use_shared_bridge_sync_helper(self):
        content = read_js("character-overview-actions.js")
        self.assertIn('bridgeTools.syncLegacyUiState(source, { currentCharacterOverview });', content)

    def test_workflow_and_model_settings_use_shared_bridge_sync_helper(self):
        content = read_js("workflow.js")
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("model-settings-view", { modelSettings });', content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("workflow-update", { workflow: state });', content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("model-settings-loaded", { modelSettings });', content)
        self.assertIn("const workflowState = typeof buildWorkflowVisibilityState === \"function\"", content)
        self.assertIn("window.__ZAOMENG_WORKFLOW_STATE__ = workflowState;", content)
        self.assertIn("workflow: workflowState,", content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("dialogue-reset", {', content)

    def test_core_uses_shared_bridge_sync_for_redistill_and_self_card_modal(self):
        content = read_js("core.js")
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("redistill-file-view-updated", {', content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("redistill-pill-state-updated", {', content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("redistill-segment-selected", {', content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("redistill-recommendation-rendered", {', content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("scene-card-modal-opened", { currentSceneCardEditor });', content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("scene-card-modal-closed", { currentSceneCardEditor });', content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("self-card-modal-opened", { currentSelfCardEditor });', content)
        self.assertIn('window.__ZAOMENG_UI_BRIDGE_TOOLS__.syncLegacyUiState("self-card-modal-closed", { currentSelfCardEditor });', content)

    def test_chat_setup_vue_island_only_exposes_self_card_picker_in_insert_mode(self):
        content = read_js("chat-setup-vue-island.js")
        self.assertIn('const isInsertMode = computed(() => mode.value === "insert");', content)
        self.assertIn("if (!isInsertMode.value) return null;", content)
        self.assertIn('<template v-if=\"isInsertMode\">', content)
        self.assertIn("chat-setup-optional-section", content)
        self.assertIn("chat-setup-curation-stack", content)
        self.assertIn("openingPresetEntries", content)
        self.assertIn("sceneCardEntries", content)
        self.assertIn("selfCardEntries", content)

    def test_workflow_fragment_keeps_chat_setup_as_vue_surface_with_hidden_state_cache(self):
        content = read_fragment("workflow-strip.html")
        self.assertIn('<div id="chat-setup-vue-root" class="chat-setup-vue-root hidden" tabindex="-1"></div>', content)
        self.assertIn('<div id="chat-setup-state-cache" class="hidden" aria-hidden="true">', content)
        self.assertNotIn('<form id="dialogue-session-form" class="stack-form">', content)

    def test_workspace_styles_define_unified_chat_setup_curation_layout(self):
        content = (REPO_ROOT / "src" / "web" / "static" / "styles" / "workspace.css").read_text(encoding="utf-8")
        self.assertIn(".chat-setup-optional-section {", content)
        self.assertIn(".chat-setup-curation-stack {", content)
        self.assertIn(".chat-setup-curation-card {", content)
        self.assertIn(".chat-setup-option-card.active {", content)

    def test_editor_schema_exposes_embodiment_fields_in_persona_core(self):
        content = read_js("editor-schemas.js")
        self.assertIn('{ field: "gender", label: "性别"', content)
        self.assertIn('{ field: "age_stage", label: "年龄阶段"', content)
        self.assertIn('{ field: "appearance_feature", label: "外貌辨识"', content)
        self.assertIn('{ field: "habit_action", label: "习惯动作"', content)
        self.assertIn('{ field: "preference_like", label: "偏好喜好"', content)
        self.assertIn('{ field: "dislike_hate", label: "明显厌恶"', content)
        self.assertIn('hint: "只写正文能稳定判断的性别或呈现。"', content)
        self.assertIn('hint: "优先写年龄感和阶段，不强求具体岁数。"', content)
        self.assertIn('hint: "写客观身份和社会定位，不写剧情职能。"', content)
        self.assertIn('hint: "写他主观上怎么定义自己、怎么站位。"', content)

    def test_editor_schema_exposes_self_card_entry_hints(self):
        content = read_js("editor-schemas.js")
        self.assertIn('hint: "别人会怎么称呼你，尽量简短好叫。"', content)
        self.assertIn('hint: "写你在这场故事里以什么身份走进来。"', content)

    def test_editor_schema_exposes_overlap_hints_for_redundant_persona_fields(self):
        content = read_js("editor-schemas.js")
        self.assertIn('hint: "写他在剧情里承担什么职能，不是身份头衔。"', content)
        self.assertIn('hint: "只写拉扯和矛盾，不写自评和隐藏面。"', content)
        self.assertIn('hint: "只写他怎么看自己，可与他人观感形成反差。"', content)
        self.assertIn('hint: "写不对外展示的一面，不要重复内在冲突。"', content)

    def test_run_package_share_modal_exposes_include_dialogue_option(self):
        fragment = read_fragment("settings-modal.html")
        main_content = read_js("main.js")
        self.assertIn('id="run-package-share-modal"', fragment)
        self.assertIn('id="run-package-share-include-dialogue"', fragment)
        self.assertIn("function openRunPackageShareModal()", main_content)
        self.assertIn("function closeRunPackageShareModal()", main_content)
        self.assertIn("function handleConfirmRunPackageShare()", main_content)
        self.assertIn("/api/web/runs/${encodeURIComponent(runId)}/share", main_content)
        self.assertIn("JSON.stringify({ include_dialogue: includeDialogue })", main_content)

    def test_core_exposes_shared_bridge_sync_helper(self):
        content = read_js("core.js")
        self.assertIn("function syncLegacyUiState(source = \"legacy\", overrides = {}) {", content)
        self.assertIn("modelSettings = nextState.modelSettings || { configured: false, provider: \"\", model: \"\", base_url: \"\", max_tokens: 0, api_key_configured: false };", content)
        self.assertIn("currentPersonaReview = nextState.currentPersonaReview || null;", content)
        self.assertIn("currentRelationDetails = nextState.currentRelationDetails || null;", content)
        self.assertIn("currentDialogueSession = nextState.currentDialogueSession || null;", content)
        self.assertIn("window.__ZAOMENG_WORKFLOW_STATE__ = nextState.workflow || {};", content)
        self.assertIn("syncLegacyUiState,", content)


if __name__ == "__main__":
    unittest.main()
