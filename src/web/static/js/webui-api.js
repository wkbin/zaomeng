(() => {
  function requireApiJson() {
    if (typeof apiJson !== "function") {
      throw new Error("apiJson is not ready.");
    }
    return apiJson;
  }

  async function getPersonaReview(runId, character) {
    return requireApiJson()(`/api/web/runs/${runId}/personas/${encodeURIComponent(character)}`);
  }

  async function getPersonaQualityReport(runId, character) {
    return requireApiJson()(
      `/api/web/runs/${runId}/personas/${encodeURIComponent(character)}/quality-report`,
      {},
      "人物质量报告暂时没有载入。"
    );
  }

  async function saveModelSettings(payload) {
    return requireApiJson()(
      "/api/web/settings/model",
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload || {}),
      },
      "保存失败。"
    );
  }

  async function savePersonaReview(runId, character, fields) {
    return requireApiJson()(
      `/api/web/runs/${runId}/personas/${encodeURIComponent(character)}`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(fields || {}),
      },
      "保存人物校对失败。"
    );
  }

  async function suggestPersonaField(runId, character, field) {
    return requireApiJson()(
      `/api/web/runs/${runId}/personas/${encodeURIComponent(character)}/suggest-field`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ field }),
      },
      "人物信息补全失败。"
    );
  }

  async function restartRedistill(runId, payload) {
    return requireApiJson()(
      `/api/web/runs/${runId}/redistill`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload || {}),
      },
      "继续蒸馏失败。"
    );
  }

  async function recommendRedistillSegments(runId, character, maxSegments = 3) {
    return requireApiJson()(
      `/api/web/runs/${runId}/redistill/recommend`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ character, max_segments: maxSegments }),
      },
      "推荐片段失败。"
    );
  }

  async function getRun(runId) {
    return requireApiJson()(`/api/web/runs/${runId}`);
  }

  async function deleteRun(runId) {
    return requireApiJson()(
      `/api/web/runs/${runId}`,
      { method: "DELETE" },
      "删除失败。"
    );
  }

  async function getRelationDetails(runId) {
    return requireApiJson()(`/api/web/runs/${runId}/relations`, {}, "关系明细暂时没有载入。");
  }

  async function saveRelationDetail(runId, pairKey, fields) {
    return requireApiJson()(
      `/api/web/runs/${runId}/relations/${encodeURIComponent(pairKey)}`,
      {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(fields || {}),
      },
      "关系保存失败。"
    );
  }

  async function listSelfCards() {
    return requireApiJson()("/api/web/self-cards", {}, "角色卡列表载入失败。");
  }

  async function listSceneCards() {
    return requireApiJson()("/api/web/scene-cards", {}, "场景卡列表载入失败。");
  }

  async function listOpeningPresets() {
    return requireApiJson()("/api/web/opening-presets", {}, "开局模板列表载入失败。");
  }

  async function getOpeningPreset(cardId) {
    return requireApiJson()(`/api/web/opening-presets/${encodeURIComponent(cardId)}`, {}, "开局模板载入失败。");
  }

  async function saveOpeningPreset(cardId, fields) {
    return requireApiJson()(
      cardId ? `/api/web/opening-presets/${encodeURIComponent(cardId)}` : "/api/web/opening-presets",
      {
        method: cardId ? "PUT" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(fields || {}),
      },
      "开局模板保存失败。"
    );
  }

  async function deleteOpeningPreset(cardId) {
    return requireApiJson()(
      `/api/web/opening-presets/${encodeURIComponent(cardId)}`,
      { method: "DELETE" },
      "开局模板删除失败。"
    );
  }

  async function getSceneCard(cardId) {
    return requireApiJson()(`/api/web/scene-cards/${encodeURIComponent(cardId)}`, {}, "场景卡载入失败。");
  }

  async function generateSceneCard() {
    return requireApiJson()("/api/web/scene-cards/generate", { method: "POST" }, "场景卡生成失败。");
  }

  async function recommendSceneCards(payload) {
    return requireApiJson()(
      "/api/web/scene-cards/recommend",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload || {}),
      },
      "场景卡推荐失败。"
    );
  }

  async function saveSceneCard(cardId, fields) {
    return requireApiJson()(
      cardId ? `/api/web/scene-cards/${encodeURIComponent(cardId)}` : "/api/web/scene-cards",
      {
        method: cardId ? "PUT" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(fields || {}),
      },
      "场景卡保存失败。"
    );
  }

  async function deleteSceneCard(cardId) {
    return requireApiJson()(
      `/api/web/scene-cards/${encodeURIComponent(cardId)}`,
      { method: "DELETE" },
      "场景卡删除失败。"
    );
  }

  async function switchDialogueSceneCard(runId, sessionId, payload) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/scene-card`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload || {}),
      },
      "切换场景失败。"
    );
  }

  async function recommendDialogueSceneCard(runId, sessionId) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/scene-card/recommend`,
      {
        method: "POST",
      },
      "下一幕推荐失败。"
    );
  }

  async function branchDialogueSession(runId, sessionId, sceneIndex) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/branch`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ scene_index: sceneIndex }),
      },
      "分支会话创建失败。"
    );
  }

  async function branchDialogueSessionFromTurn(runId, sessionId, turnId) {
    const controller = typeof AbortController === "function" ? new AbortController() : null;
    const timeoutId = controller
      ? window.setTimeout(() => controller.abort(), 15000)
      : null;
    try {
      return await requireApiJson()(
        `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/branch-turn`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ turn_id: turnId }),
          ...(controller ? { signal: controller.signal } : {}),
        },
        "剧情节点回溯失败。"
      );
    } catch (error) {
      if (controller?.signal.aborted) {
        throw new Error("剧情节点回溯请求超时，请稍后重试。");
      }
      throw error;
    } finally {
      if (timeoutId !== null) window.clearTimeout(timeoutId);
    }
  }

  async function updateDialogueBranchMeta(runId, sessionId, payload) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/branch-meta`,
      {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload || {}),
      },
      "剧情分支信息更新失败。"
    );
  }

  async function updateDialogueSessionTitle(runId, sessionId, title) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/title`,
      {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: String(title || "") }),
      },
      "会话标题更新失败。"
    );
  }

  async function updateDialogueRelationLock(runId, sessionId, pairKey, locked) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/relation-lock`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pair_key: pairKey, locked: Boolean(locked) }),
      },
      "关系锁定状态更新失败。"
    );
  }

  async function createDialogueMemory(runId, sessionId, payload) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/memories`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload || {}),
      },
      "记忆保存失败。"
    );
  }

  async function updateDialogueMemory(runId, sessionId, memoryId, payload) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/memories/${encodeURIComponent(memoryId)}`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload || {}),
      },
      "记忆更新失败。"
    );
  }

  async function deleteDialogueMemory(runId, sessionId, memoryId) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/memories/${encodeURIComponent(memoryId)}`,
      { method: "DELETE" },
      "记忆删除失败。"
    );
  }

  async function generateDialogueDirectorOptions(runId, sessionId, payload) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/director-options`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload || {}),
      },
      "导演方案生成失败。"
    );
  }

  async function suggestDialogueTurn(runId, sessionId, direction) {
    return requireApiJson()(
      `/api/web/runs/${encodeURIComponent(runId)}/dialogue/sessions/${encodeURIComponent(sessionId)}/suggest`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ seed_text: "", direction: String(direction || "") }),
      },
      "导演方案落地失败。"
    );
  }

  async function getSelfCard(cardId) {
    return requireApiJson()(`/api/web/self-cards/${encodeURIComponent(cardId)}`, {}, "角色卡载入失败。");
  }

  async function generateSelfCard() {
    return requireApiJson()("/api/web/self-cards/generate", { method: "POST" }, "角色卡生成失败。");
  }

  async function saveSelfCard(cardId, fields) {
    return requireApiJson()(
      cardId ? `/api/web/self-cards/${encodeURIComponent(cardId)}` : "/api/web/self-cards",
      {
        method: cardId ? "PUT" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(fields || {}),
      },
      "角色卡保存失败。"
    );
  }

  async function deleteSelfCard(cardId) {
    return requireApiJson()(
      `/api/web/self-cards/${encodeURIComponent(cardId)}`,
      { method: "DELETE" },
      "角色卡删除失败。"
    );
  }

  window.__ZAOMENG_WEBUI_API__ = {
    saveModelSettings,
    getPersonaReview,
    getPersonaQualityReport,
    savePersonaReview,
    suggestPersonaField,
    restartRedistill,
    recommendRedistillSegments,
    getRun,
    deleteRun,
    getRelationDetails,
    saveRelationDetail,
    listOpeningPresets,
    getOpeningPreset,
    saveOpeningPreset,
    deleteOpeningPreset,
    listSceneCards,
    getSceneCard,
    generateSceneCard,
    recommendSceneCards,
    saveSceneCard,
    deleteSceneCard,
    switchDialogueSceneCard,
    recommendDialogueSceneCard,
    branchDialogueSession,
    branchDialogueSessionFromTurn,
    updateDialogueBranchMeta,
    updateDialogueSessionTitle,
    updateDialogueRelationLock,
    createDialogueMemory,
    updateDialogueMemory,
    deleteDialogueMemory,
    generateDialogueDirectorOptions,
    suggestDialogueTurn,
    listSelfCards,
    getSelfCard,
    generateSelfCard,
    saveSelfCard,
    deleteSelfCard,
  };
})();
