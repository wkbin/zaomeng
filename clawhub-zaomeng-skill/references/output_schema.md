# Output Schema

## Character Profile

```json
{
  "name": "角色名",
  "core_traits": ["性格1", "性格2"],
  "values": {
    "勇气": 0,
    "智慧": 0,
    "善良": 0,
    "忠诚": 0,
    "野心": 0,
    "正义": 0,
    "自由": 0,
    "责任": 0
  },
  "speech_style": "语言风格描述",
  "typical_lines": ["台词1"],
  "decision_rules": ["条件->反应"],
  "arc": {
    "start": {"勇气": 5},
    "mid": {"勇气": 6, "trigger_event": "事件"},
    "end": {"勇气": 7, "final_state": "状态"}
  },
  "novel_id": "sample_novel",
  "source_path": "data/sample_novel.txt",
  "evidence": {
    "description_count": 1,
    "dialogue_count": 2,
    "thought_count": 0,
    "chunk_count": 1
  }
}
```

Rules:

- `core_traits` max 10 unique items
- `typical_lines` max 8 unique items
- `decision_rules` max 8 unique items
- `values` all integers in `[0,10]`
- `evidence` stores counts, not raw text arrays

## Relations Graph

```json
{
  "林黛玉_贾宝玉": {
    "trust": 8,
    "affection": 9,
    "power_gap": 1,
    "conflict_point": "表达方式差异",
    "typical_interaction": "黛玉质问->宝玉安抚->短暂缓和"
  }
}
```

Rules:

- Relation key must be sorted lexicographically
- `trust` and `affection` in `[0,10]`
- `power_gap` in `[-5,5]`

## Chat Constraints (Optional)

```json
{
  "character": "林黛玉",
  "must_follow": [
    "语气克制但可反讽",
    "冲突时先防御再观察"
  ],
  "must_avoid": [
    "无证据的极端背叛表述",
    "与高忠诚值冲突的抛弃宣言"
  ],
  "fallback_action": "rewrite_once_then_needs_revision"
}
```
