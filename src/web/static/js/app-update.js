(() => {
  if (window.__ZAOMENG_APP_UPDATE__) return;

  const DISMISS_PREFIX = "zaomeng:update-dismissed:";
  let statusSnapshot = null;
  let pollTimer = 0;

  function openAppUpdateModal() {
    toggle("app-update-modal", true);
    if (typeof syncModalScrollLock === "function") syncModalScrollLock();
  }

  function closeAppUpdateModal() {
    toggle("app-update-modal", false);
    if (typeof syncModalScrollLock === "function") syncModalScrollLock();
  }

  function appUpdateDismissKey(currentVersion, remoteVersion) {
    return `${DISMISS_PREFIX}${String(currentVersion || "").trim()}->${String(remoteVersion || "").trim()}`;
  }

  function rememberDismissedAppUpdate(status = statusSnapshot) {
    const currentVersion = String(status?.current_version || "").trim();
    const remoteVersion = String(status?.remote_version || "").trim();
    if (!currentVersion || !remoteVersion || !window.localStorage) return;
    window.localStorage.setItem(appUpdateDismissKey(currentVersion, remoteVersion), "1");
  }

  function wasAppUpdateDismissed(status = statusSnapshot) {
    const currentVersion = String(status?.current_version || "").trim();
    const remoteVersion = String(status?.remote_version || "").trim();
    if (!currentVersion || !remoteVersion || !window.localStorage) return false;
    return window.localStorage.getItem(appUpdateDismissKey(currentVersion, remoteVersion)) === "1";
  }

  function clearAppUpdatePolling() {
    if (!pollTimer) return;
    window.clearTimeout(pollTimer);
    pollTimer = 0;
  }

  function renderAppUpdateStatus(status) {
    statusSnapshot = status || null;
    setText("app-update-current-version", status?.current_version || "-", "");
    setText("app-update-remote-version", status?.remote_version || "-", "");
    setFlowStatusMessage("app-update-status", {
      message: status?.message || "",
      nextStep: String(status?.status || "") === "completed" && status?.reload_required ? "页面很快会自动刷新。" : "",
    });
    const confirmButton = el("confirm-app-update-button");
    const closeButton = el("close-app-update-button");
    const dismissButton = el("dismiss-app-update-button");
    const updating = String(status?.status || "") === "updating";
    if (confirmButton) {
      confirmButton.disabled = updating || !status?.update_available;
      confirmButton.textContent = updating ? "更新中..." : "现在更新";
    }
    if (closeButton) closeButton.disabled = updating;
    if (dismissButton) dismissButton.disabled = updating;
  }

  async function fetchAppUpdateStatus(force = false) {
    const suffix = force ? "?force=true" : "";
    const status = await apiJson(`/api/web/settings/update${suffix}`, {}, "检查更新失败。");
    renderAppUpdateStatus(status);
    return status;
  }

  function scheduleAppUpdatePolling() {
    clearAppUpdatePolling();
    pollTimer = window.setTimeout(async () => {
      try {
        const status = await fetchAppUpdateStatus(false);
        if (status?.status === "updating") {
          scheduleAppUpdatePolling();
          return;
        }
        if (status?.status === "completed" && status?.reload_required) {
          window.setTimeout(() => window.location.reload(), 900);
        }
      } catch (error) {
        setFlowFailureStatus(
          "app-update-status",
          error.message || "刚才那次更新状态暂时没取到。",
          "稍后可以再手动检查一次更新。",
          { impact: "这不会影响你继续使用当前版本。", affectsChatFlow: false }
        );
      }
    }, 1200);
  }

  async function checkAppUpdateOnBoot() {
    try {
      const status = await fetchAppUpdateStatus(true);
      if (!status?.supported || !status?.update_available || wasAppUpdateDismissed(status)) return;
      openAppUpdateModal();
    } catch (error) {
      console.warn("checkAppUpdateOnBoot failed", error);
    }
  }

  function dismissAppUpdateModal() {
    rememberDismissedAppUpdate(statusSnapshot);
    closeAppUpdateModal();
  }

  async function handleConfirmAppUpdate() {
    setButtonBusyState("confirm-app-update-button", true, { idleText: "现在更新", busyText: "更新中..." });
    setFlowLoadingStatus("app-update-status", "正在替你接上更新...", "更新完成后会自动刷新当前页面。");
    try {
      const status = await apiJson(
        "/api/web/settings/update",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ confirm: "update" }),
        },
        "开始更新失败。"
      );
      renderAppUpdateStatus(status);
      openAppUpdateModal();
      scheduleAppUpdatePolling();
    } catch (error) {
      setButtonBusyState("confirm-app-update-button", false, { idleText: "现在更新", busyText: "更新中..." });
      setFlowFailureStatus(
        "app-update-status",
        error.message || "这次更新没有接上。",
        "可以稍后再试更新。",
        { impact: "这不会影响你继续使用当前版本聊天。", affectsChatFlow: false }
      );
    }
  }

  Object.assign(window, {
    openAppUpdateModal,
    closeAppUpdateModal,
    appUpdateDismissKey,
    rememberDismissedAppUpdate,
    wasAppUpdateDismissed,
    clearAppUpdatePolling,
    renderAppUpdateStatus,
    fetchAppUpdateStatus,
    scheduleAppUpdatePolling,
    checkAppUpdateOnBoot,
    dismissAppUpdateModal,
    handleConfirmAppUpdate,
  });
  window.__ZAOMENG_APP_UPDATE__ = { initialized: true };
})();
