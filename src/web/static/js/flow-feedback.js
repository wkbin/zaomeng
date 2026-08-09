(() => {
  if (window.__ZAOMENG_FLOW_STATUS__) return;

  const feedbackTools = window.__ZAOMENG_FLOW_FEEDBACK__ || {};

  function setFlowStatusMessage(statusId, options = {}) {
    const payload = {
      ...options,
      phase: options.phase || (options.impact ? "failure" : ""),
    };
    if (typeof feedbackTools.setFlowStatus === "function") {
      feedbackTools.setFlowStatus(statusId, payload);
      return;
    }
    setStatus(statusId, String(payload.message || "").trim());
  }

  function setButtonBusyState(target, pending, options = {}) {
    if (typeof feedbackTools.setButtonBusy === "function") {
      feedbackTools.setButtonBusy(target, pending, options);
      return;
    }
    const node = typeof target === "string" ? el(target) : target;
    if (!node) return;
    node.disabled = Boolean(pending);
    if (pending && options.busyText) {
      node.textContent = String(options.busyText).trim();
      return;
    }
    if (!pending && options.idleText) {
      node.textContent = String(options.idleText).trim();
    }
  }

  function setDistillFlowStatus(statusId, options = {}) {
    setFlowStatusMessage(statusId, options);
  }

  function setFlowLoadingStatus(statusId, message, nextStep = "") {
    setFlowStatusMessage(statusId, { phase: "loading", message, nextStep });
  }

  function setFlowSuccessStatus(statusId, message, nextStep = "") {
    setFlowStatusMessage(statusId, { phase: "success", message, nextStep });
  }

  function setFlowFailureStatus(statusId, message, nextStep = "", options = {}) {
    setFlowStatusMessage(statusId, {
      phase: "failure",
      message,
      impact: options.impact || "",
      affectsChatFlow: Boolean(options.affectsChatFlow),
      nextStep,
    });
  }

  window.setFlowStatusMessage = setFlowStatusMessage;
  window.setButtonBusyState = setButtonBusyState;
  window.setDistillFlowStatus = setDistillFlowStatus;
  window.setFlowLoadingStatus = setFlowLoadingStatus;
  window.setFlowSuccessStatus = setFlowSuccessStatus;
  window.setFlowFailureStatus = setFlowFailureStatus;
  window.__ZAOMENG_FLOW_STATUS__ = { initialized: true };
})();
