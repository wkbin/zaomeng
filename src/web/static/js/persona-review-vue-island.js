(() => {
  const bridge = window.__ZAOMENG_LEGACY_BRIDGE__;
  const webuiApi = window.__ZAOMENG_WEBUI_API__;
  const vue = window.Vue;
  const host = document.getElementById("persona-review-vue-root");
  const modal = document.getElementById("persona-review-modal");
  if (!bridge || !vue || !host || !modal || !webuiApi) {
    return;
  }

  const { createApp, computed, onBeforeUnmount, onMounted, reactive, ref } = vue;
  const schemas = window.__ZAOMENG_EDITOR_SCHEMAS__ || {};
  const editorComponents = window.__ZAOMENG_EDITOR_VUE_COMPONENTS__ || {};
  const bridgeTools = window.__ZAOMENG_UI_BRIDGE_TOOLS__ || {};
  const KEY_FIELDS = Array.isArray(schemas.PERSONA_KEY_FIELDS) ? schemas.PERSONA_KEY_FIELDS : [];
  const ADVANCED_GROUPS = Array.isArray(schemas.PERSONA_ADVANCED_GROUPS) ? schemas.PERSONA_ADVANCED_GROUPS : [];
  const ALL_FIELDS = Array.isArray(schemas.PERSONA_ALL_FIELDS) ? schemas.PERSONA_ALL_FIELDS : [];

  function emptyFieldMap() {
    const fields = {};
    ALL_FIELDS.forEach((item) => {
      fields[item.field] = "";
    });
    return fields;
  }

  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function runDetailActions() {
    if (typeof bridgeTools.readLegacyActionBridge === "function") {
      return bridgeTools.readLegacyActionBridge("__ZAOMENG_RUN_DETAIL_ACTIONS__");
    }
    return window.__ZAOMENG_RUN_DETAIL_ACTIONS__ || {};
  }

  function syncPersonaBridgeState(source, overrides) {
    if (typeof bridgeTools.syncLegacyUiState === "function") {
      bridgeTools.syncLegacyUiState(source, overrides);
    } else if (typeof publishLegacyUiState === "function") {
      publishLegacyUiState(source, overrides);
    }
  }

  createApp({
    components: {
      SchemaFieldCard: editorComponents.SchemaFieldCard,
    },
    setup() {
      const snapshot = ref(bridge.getSnapshot ? bridge.getSnapshot() : {});
      const modalVisible = ref(!modal.classList.contains("hidden"));
      const state = reactive({
        loading: false,
        qualityLoading: false,
        saving: false,
        autofillField: "",
        character: "",
        status: "",
        fields: emptyFieldMap(),
        references: [],
        referenceSummary: "网页摘要参考",
        advancedOpen: false,
        feedback: {},
        qualityReport: null,
      });

      const unsubscribe = bridge.subscribe((nextSnapshot) => {
        snapshot.value = nextSnapshot || {};
      });

      function setStatus(message = "") {
        state.status = String(message || "").trim();
      }

      function applyReviewPayload(payload) {
        state.character = String(payload?.character || "").trim();
        state.fields = {
          ...emptyFieldMap(),
          ...(payload?.fields || {}),
        };
        state.feedback = {};
        state.references = [];
        state.referenceSummary = "网页摘要参考";
      }

      function applyQualityPayload(payload) {
        const report = payload && typeof payload === "object" ? payload : null;
        state.qualityReport = report;
        if (!report) {
          state.feedback = {};
          return;
        }
        const issues = Array.isArray(report.issues) ? report.issues : [];
        const issueByField = new Map();
        issues.forEach((issue) => {
          const fields = Array.isArray(issue?.fields) ? issue.fields : [];
          fields.forEach((field) => {
            if (!issueByField.has(field)) issueByField.set(field, issue);
          });
        });
        const feedback = {};
        const results = Array.isArray(report.field_results) ? report.field_results : [];
        results.forEach((result) => {
          const field = String(result?.field || "").trim();
          if (!field || result?.status === "ready") return;
          const issue = issueByField.get(field);
          feedback[field] = {
            kind: "error",
            message: issue?.suggestion || "这个字段仍需要更具体、可验证的内容。",
          };
        });
        state.feedback = feedback;
      }

      async function loadQualityReport(runId, character) {
        state.qualityLoading = true;
        try {
          const report = await webuiApi.getPersonaQualityReport(runId, character);
          applyQualityPayload(report);
          return true;
        } catch (_error) {
          applyQualityPayload(null);
          return false;
        } finally {
          state.qualityLoading = false;
        }
      }

      function applyAutofillReferences(payload) {
        const refs = Array.isArray(payload?.references) ? payload.references : [];
        state.references = refs;
        state.referenceSummary = refs.length ? `${refs.length} 条网页摘要参考` : "网页摘要参考";
      }

      async function loadCharacter(characterName) {
        const runId = String(snapshot.value.currentRunId || "").trim();
        const character = String(characterName || "").trim();
        if (!runId || !character) return;
          state.loading = true;
          setStatus("正在载入人物档案...");
        try {
          const payload = await webuiApi.getPersonaReview(runId, character);
          applyReviewPayload(payload);
          const qualityLoaded = await loadQualityReport(runId, character);
          setStatus(qualityLoaded ? "" : "人物档案已载入，质量报告暂时不可用。");
          syncPersonaBridgeState("persona-review-vue-loaded", {
            currentPersonaReview: payload,
            currentPersonaAutofill: null,
          });
        } catch (error) {
          setStatus(error.message || "人物档案暂时没有载入。");
        } finally {
          state.loading = false;
        }
      }

      async function saveReview() {
        const runId = String(snapshot.value.currentRunId || "").trim();
        const character = String(state.character || "").trim();
        if (!runId || !character || state.saving) return;
        state.saving = true;
        setStatus("正在写回人物校对...");
        try {
          const saved = await webuiApi.savePersonaReview(runId, character, clone(state.fields));
          applyReviewPayload(saved);
          await loadQualityReport(runId, character);
          applyAutofillReferences(null);
          syncPersonaBridgeState("persona-review-vue-saved", {
            currentPersonaReview: saved,
            currentPersonaAutofill: null,
          });
          setStatus("人物校对已经写回这一卷。");
          const actions = runDetailActions();
          if (typeof actions.refreshRunView === "function") {
            await actions.refreshRunView(runId);
          } else {
            const run = await webuiApi.getRun(runId);
            if (typeof window.__ZAOMENG_APPLY_RUN_VIEW__ === "function") {
              window.__ZAOMENG_APPLY_RUN_VIEW__(run);
            } else if (typeof actions.renderRunView === "function") {
              actions.renderRunView(run);
            } else if (typeof window.renderRun === "function") {
              window.renderRun(run);
            }
          }
        } catch (error) {
          setStatus(error.message || "这次校对没有保存成功。");
        } finally {
          state.saving = false;
        }
      }

      async function autofillField(field) {
        const runId = String(snapshot.value.currentRunId || "").trim();
        const character = String(state.character || "").trim();
        const fieldName = String(field || "").trim();
        if (!runId || !character || !fieldName || state.autofillField) return;
        state.autofillField = fieldName;
        state.feedback[fieldName] = { kind: "loading", message: "正在生成补全..." };
        setStatus(`正在生成「${fieldLabel(fieldName)}」的补全内容...`);
        try {
          const payload = await webuiApi.suggestPersonaField(runId, character, fieldName);
          applyAutofillReferences(payload);
          syncPersonaBridgeState("persona-review-vue-autofill", {
            currentPersonaReview,
            currentPersonaAutofill: payload || null,
          });
          if (payload?.status === "filled" && payload?.value) {
            state.fields[fieldName] = payload.value;
            state.feedback[fieldName] = { kind: "success", message: "已生成补全内容，记得保存。" };
            setStatus(payload.message || "已生成补全内容，请记得保存人物校对。");
          } else {
            state.feedback[fieldName] = { kind: "error", message: payload?.message || payload?.reason || "人物信息补全无法生成。" };
            setStatus(payload?.message || payload?.reason || "人物信息补全无法生成。");
          }
        } catch (error) {
          applyAutofillReferences(null);
          syncPersonaBridgeState("persona-review-vue-autofill-cleared", {
            currentPersonaReview,
            currentPersonaAutofill: null,
          });
          state.feedback[fieldName] = { kind: "error", message: error.message || "人物信息补全无法生成。" };
          setStatus(error.message || "人物信息补全无法生成。");
        } finally {
          state.autofillField = "";
        }
      }

      function fieldLabel(field) {
        const item = ALL_FIELDS.find((entry) => entry.field === field);
        return item ? item.label : field;
      }

      function needsAutofill(field) {
        const value = String(state.fields[field] || "").trim();
        if (!value) return true;
        const normalized = value.replace(/\s+/g, "");
        return ["证据不足", "资料不足", "信息不足", "暂无资料", "暂缺", "待补充"].includes(normalized);
      }

      const availableCharacters = computed(() => {
        const items = Array.isArray(snapshot.value.currentRun?.artifact_index?.characters) ? snapshot.value.currentRun.artifact_index.characters : [];
        return items.map((item) => String(item?.name || "").trim()).filter(Boolean);
      });

      const visible = computed(() => modalVisible.value);
      const visibleQualityIssues = computed(() => {
        const issues = Array.isArray(state.qualityReport?.issues) ? state.qualityReport.issues : [];
        return issues.filter((item) => item?.severity !== "low").slice(0, 5);
      });
      const visibleEvidenceReferences = computed(() => {
        const references = Array.isArray(state.qualityReport?.evidence?.references) ? state.qualityReport.evidence.references : [];
        return references.slice(0, 8);
      });

      onMounted(() => {
        modal.classList.add("has-vue-island");
        host.classList.remove("hidden");
        const observer = new MutationObserver(() => {
          modalVisible.value = !modal.classList.contains("hidden");
        });
        observer.observe(modal, { attributes: true, attributeFilter: ["class"] });
        host.__zaomengModalObserver = observer;
      });

      onBeforeUnmount(() => {
        host.__zaomengModalObserver?.disconnect?.();
        delete host.__zaomengModalObserver;
        unsubscribe();
      });

      const personaReviewActions = {
        openForCharacter(character) {
          state.character = String(character || "").trim();
          if (state.character) {
            loadCharacter(state.character);
          }
        },
        handleCharacterChange(character) {
          const target = String(character || "").trim();
          if (!target || target === state.character) return true;
          state.character = target;
          loadCharacter(target);
          return true;
        },
        submit() {
          saveReview();
          return true;
        },
        handleLegacyAutofillEvent(event) {
          const trigger = event?.target instanceof HTMLElement ? event.target.closest("[data-persona-autofill-field]") : null;
          if (!(trigger instanceof HTMLButtonElement)) return false;
          const field = trigger.getAttribute("data-persona-autofill-field") || "";
          if (!field) return false;
          autofillField(field);
          return true;
        },
      };

      if (typeof bridgeTools.mergeLegacyActionBridge === "function") {
        bridgeTools.mergeLegacyActionBridge("__ZAOMENG_PERSONA_REVIEW_ACTIONS__", personaReviewActions);
      } else {
        window.__ZAOMENG_PERSONA_REVIEW_ACTIONS__ = personaReviewActions;
      }

      return {
        state,
        visible,
        availableCharacters,
        keyFields: KEY_FIELDS,
        advancedGroups: ADVANCED_GROUPS,
        visibleEvidenceReferences,
        visibleQualityIssues,
        needsAutofill,
        fieldLabel,
        loadCharacter,
        saveReview,
        autofillField,
      };
    },
    template: `
      <form v-if="visible" class="stack-form persona-review-vue-form" @submit.prevent="saveReview">
        <label class="field-card">
          <span>当前人物</span>
          <select v-model="state.character" class="native-hidden" @change="loadCharacter(state.character)">
            <option v-for="name in availableCharacters" :key="name" :value="name">{{ name }}</option>
          </select>
          <div class="pill-row persona-pill-row modal-pill-row">
            <button
              v-for="name in availableCharacters"
              :key="'pill-' + name"
              type="button"
              class="pill persona-pill"
              :class="{ active: name === state.character }"
              @click="state.character = name; loadCharacter(name)"
            >
              {{ name }}
            </button>
            <span v-if="!availableCharacters.length" class="pill hint-pill">请先选择一卷已完成的人物</span>
          </div>
        </label>

        <section
          v-if="state.qualityReport"
          class="persona-quality-panel"
          :data-grade="state.qualityReport.grade"
          aria-label="人物质量报告"
        >
          <div class="persona-quality-head">
            <div>
              <strong>人物质量</strong>
              <p>{{ state.qualityReport.verdict }}</p>
            </div>
            <div class="persona-quality-score" aria-label="总分">
              <strong>{{ state.qualityReport.score }}</strong>
              <span>/ 100</span>
            </div>
          </div>
          <div class="persona-quality-dimensions">
            <div v-for="item in state.qualityReport.dimensions" :key="item.id" class="persona-quality-dimension">
              <div><span>{{ item.label }}</span><b>{{ item.score }} / {{ item.max_score }}</b></div>
              <progress :value="item.score" :max="item.max_score"></progress>
            </div>
          </div>
          <p class="persona-quality-metrics">
            38 项中 {{ state.qualityReport.metrics.ready_field_count }} 项可用，
            {{ state.qualityReport.metrics.missing_field_count }} 项缺失；
            证据含 {{ state.qualityReport.evidence.description_count }} 条描写、
            {{ state.qualityReport.evidence.dialogue_count }} 条对白、
            {{ state.qualityReport.evidence.thought_count }} 条心理活动。
          </p>
          <details v-if="visibleEvidenceReferences.length" class="persona-quality-evidence-library">
            <summary>查看原文证据（{{ state.qualityReport.evidence.reference_count }}）</summary>
            <ol>
              <li v-for="reference in visibleEvidenceReferences" :key="reference.id">
                <small>{{ reference.stage_label }} · {{ reference.kind_label }}</small>
                <blockquote>{{ reference.quote }}</blockquote>
              </li>
            </ol>
          </details>
          <ol v-if="visibleQualityIssues.length" class="persona-quality-issues">
            <li v-for="item in visibleQualityIssues" :key="item.code" :data-severity="item.severity">
              <strong>{{ item.message }}</strong>
              <span>{{ item.suggestion }}</span>
              <details v-if="item.evidence?.length" class="persona-quality-issue-evidence">
                <summary>查看相关原文</summary>
                <blockquote v-for="reference in item.evidence" :key="item.code + '-' + reference.id">
                  <small>{{ reference.stage_label }} · {{ reference.kind_label }}</small>
                  {{ reference.quote }}
                </blockquote>
              </details>
            </li>
          </ol>
          <a
            v-if="state.qualityReport.artifact?.file_url"
            class="persona-quality-download"
            :href="state.qualityReport.artifact.file_url"
            target="_blank"
            rel="noreferrer"
          >下载 JSON 报告</a>
        </section>
        <p v-else-if="state.qualityLoading" class="card-note">正在计算人物质量...</p>

        <section class="review-group">
          <div class="review-group-head">
            <strong>关键字段</strong>
            <p>先抓住身份、外显辨识、关系和说话味道。只要这一层站稳，聊天里的这个人就先活起来了。</p>
          </div>
          <div class="mini-grid persona-vue-grid">
            <schema-field-card
              v-for="item in keyFields"
              :key="item.field"
              :item="item"
              :model-value="state.fields[item.field]"
              :feedback="state.feedback[item.field] || null"
              :autofill-enabled="true"
              :autofill-field="state.autofillField"
              :needs-autofill="needsAutofill"
              @update:model-value="state.fields[item.field] = $event"
              @autofill="autofillField"
            />
          </div>
        </section>

        <details class="review-advanced-shell" :open="state.advancedOpen" @toggle="state.advancedOpen = $event.target.open">
          <summary class="review-advanced-trigger">
            <span>继续细调更多字段</span>
            <small>这里会把容易互相重叠的细字段拆开，你可以按分工慢慢修，不必一口气全写满。</small>
          </summary>
          <section v-for="group in advancedGroups" :key="group.title" class="review-group review-advanced-panel">
            <div class="review-group-head">
              <strong>{{ group.title }}</strong>
              <p>{{ group.copy }}</p>
            </div>
            <div class="mini-grid persona-vue-grid">
              <schema-field-card
                v-for="item in group.fields"
                :key="item.field"
                :item="item"
                :model-value="state.fields[item.field]"
                :feedback="state.feedback[item.field] || null"
                :autofill-enabled="true"
                :autofill-field="state.autofillField"
                :needs-autofill="needsAutofill"
                @update:model-value="state.fields[item.field] = $event"
                @autofill="autofillField"
              />
            </div>
          </section>
        </details>

        <div class="card-actions">
          <button type="submit" class="primary-button" :disabled="state.saving || state.loading">
            {{ state.saving ? '正在保存...' : '保存校对' }}
          </button>
        </div>

        <p class="card-note">{{ state.status }}</p>

        <details v-if="state.references.length" class="persona-reference-panel">
          <summary class="persona-reference-trigger">
            <span>看看这次补全参考了什么</span>
            <small>{{ state.referenceSummary }}</small>
          </summary>
          <div class="persona-reference-list">
            <article v-for="(item, index) in state.references" :key="'ref-' + index" class="persona-reference-card">
              <div class="persona-reference-head">
                <strong>{{ item.title || ('参考 ' + (index + 1)) }}</strong>
                <span v-if="item.source">{{ item.source }}</span>
              </div>
              <p v-if="item.query" class="persona-reference-query">检索词：{{ item.query }}</p>
              <p v-if="item.snippet" class="persona-reference-snippet">{{ item.snippet }}</p>
            </article>
          </div>
        </details>
      </form>
    `,
  }).mount(host);
})();
