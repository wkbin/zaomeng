---
name: zaomeng-skill
description: 面向中文小说人物蒸馏、关系抽取、关系图谱和角色对话的 ClawHub skill
---

# zaomeng-skill

| 项目 | 内容 |
| --- | --- |
| 名称 | `zaomeng-skill` |
| 版本 | `4.1.3` |
| 类型 | ClawHub / Host-managed skill |
| 核心模式 | LLM-first |
| 适用场景 | 人物蒸馏、人物包物化、关系图谱导出、角色 act / observe |
| 宿主职责 | 调用宿主 LLM，负责最终生成 |
| skill 职责 | 准备 prompt payload、物化人物包、导出图谱、校验产物、维护运行状态 |

## 1. 定位

- 这是一个宿主驱动的 prompt-first skill。
- 宿主负责实际调用 LLM；skill 负责把任务整理成标准输入、标准产物和标准状态。
- skill 的主路径不是内嵌 CLI，而是 `prompts + helper tools + run_manifest.json`。

## 2. 宿主能力契约

宿主侧只需要理解四个能力：

| 能力 | 入口 | 作用 | 标准成功标记 |
| --- | --- | --- | --- |
| `distill` | `tools/build_prompt_payload.py --mode distill` | 生成蒸馏 payload，等待宿主 LLM 产出 `PROFILE.generated.md` | capability status `status=ready, success=true` |
| `materialize` | `tools/materialize_persona_bundle.py` | 把 `PROFILE.generated.md` 物化为完整人物包 | `ARTIFACT_STATUS.generated.json` + capability status |
| `export_graph` | `tools/export_relation_graph.py` | 导出人物关系图谱 HTML / Mermaid / SVG | `<relations>.status.json` + capability status |
| `verify_workflow` | `tools/verify_host_workflow.py` | 校验整条宿主工作流产物是否完整 | capability status `status=complete, success=true` |

所有能力都应该满足：

- 有明确输入
- 有 JSON 输出
- 有 sidecar status 文件
- 有 `success` 布尔值
- 可选更新 `run_manifest.json`

## 3. 标准运行状态

宿主如果要跑完整蒸馏链路，先初始化一个 `run_manifest.json`：

```bash
py -3 tools/init_host_run.py --novel <路径> --characters A,B,C --output <run_manifest.json>
```

`run_manifest.json` 是宿主侧的统一索引，记录：

- 当前阶段
- 已锁定角色
- 当前正在处理的角色
- 已完成数量
- 关系图导出状态
- 各能力 status 文件
- 人物目录
- 关系图 HTML / SVG / Mermaid 路径
- 最终 workflow 校验结果

## 4. 标准进度阶段

宿主侧统一使用这些阶段名：

- `characters_locked`
- `distill_payload_ready`
- `relation_payload_ready`
- `character_started`
- `character_completed`
- `graph_export_started`
- `graph_export_completed`
- `workflow_verified`

如果宿主要主动播报当前角色和进度，直接调用：

```bash
py -3 tools/update_run_progress.py --run-manifest <run_manifest.json> --stage character_started --character 林黛玉 --message "正在蒸馏林黛玉"
```

## 5. 标准流程

1. 初始化 `run_manifest.json`
2. 运行 `distill` 能力，生成蒸馏 payload
3. 宿主 LLM 为每个角色生成 `PROFILE.generated.md`
4. 每个角色生成后立刻运行 `materialize`
5. 宿主 LLM 生成关系结果后运行 `export_graph`
6. 运行 `verify_workflow`
7. 宿主向用户展示：
   - 人物目录
   - 关系图 HTML / SVG
   - 状态摘要
   - 可进入 `act` / `observe`

## 6. 推荐宿主串联方式

### A. 初始化运行

```bash
py -3 tools/init_host_run.py --novel <路径> --characters A,B --output <run_manifest.json>
```

### B. 准备 distill payload

```bash
py -3 tools/build_prompt_payload.py --mode distill --novel <路径> --characters A,B --output <distill_payload.json> --run-manifest <run_manifest.json>
```

### C. 角色开始 / 完成进度

```bash
py -3 tools/update_run_progress.py --run-manifest <run_manifest.json> --stage character_started --character A
py -3 tools/update_run_progress.py --run-manifest <run_manifest.json> --stage character_completed --character A
```

### D. 物化人物包

```bash
py -3 tools/materialize_persona_bundle.py --profile-file <character-dir/PROFILE.generated.md> --run-manifest <run_manifest.json>
```

### E. 导出关系图谱

```bash
py -3 tools/export_relation_graph.py --relations-file <relations.md> --run-manifest <run_manifest.json>
```

### F. 校验工作流

```bash
py -3 tools/verify_host_workflow.py --characters-root <characters/<novel_id>> --relations-file <relations.md> --run-manifest <run_manifest.json>
```

## 7. 最终产物

完整 run 结束后，宿主应该直接拿到：

- `run_manifest.json`
- 每个角色的人物目录
- 每个角色的 `ARTIFACT_STATUS.generated.json`
- 关系图：
  - `*_relations.html`
  - `*_relations.svg`
  - `*_relations.mermaid.md`
  - `*.status.json`
- workflow 校验 JSON

## 8. act / observe

当人物包和关系图准备完成后，宿主即可进入：

- `act`：指定角色进行单聊 / 对话演绎
- `observe`：进入群聊模式，观察多角色对话推进

宿主结束提示建议直接说清楚：

- 人物档案已完成
- 关系图谱已生成
- 可以查看图谱
- 可以进入 `act`
- 可以进入 `observe`
