from __future__ import annotations

from typing import Any, Iterable


MAX_RECORDED_TURNS = 200


def empty_generation_cache_stats() -> dict[str, Any]:
    return {
        "latest": {},
        "turns": [],
        "session": {
            "observed": False,
            "status": "unsupported",
            "input_tokens": 0,
            "cache_read_tokens": 0,
            "cache_write_tokens": 0,
            "cache_miss_tokens": 0,
            "hit_rate": None,
            "observed_turns": 0,
            "total_turns": 0,
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
            "elapsed_seconds": 0.0,
            "cost_usd": 0.0,
            "attempt_count": 0,
            "retry_count": 0,
            "average_elapsed_seconds": 0.0,
            "models": {},
        },
    }


def summarize_completion_results(results: Iterable[dict[str, Any]]) -> dict[str, Any]:
    completions = [dict(item or {}) for item in results]
    provider = ""
    model = ""
    observed = False
    input_tokens = 0
    cache_read_tokens = 0
    cache_write_tokens = 0
    cache_miss_tokens = 0
    prompt_tokens = 0
    completion_tokens = 0
    elapsed_seconds = 0.0
    cost_usd = 0.0

    for completion in completions:
        provider = str(completion.get("provider", provider)).strip() or provider
        model = str(completion.get("model", model)).strip() or model
        usage_payload = dict(completion.get("usage", {}) or {})
        prompt_tokens += _non_negative_int(
            completion.get(
                "prompt_tokens",
                usage_payload.get("prompt_tokens", usage_payload.get("input_tokens", 0)),
            )
        )
        completion_tokens += _non_negative_int(
            completion.get(
                "completion_tokens",
                usage_payload.get("completion_tokens", usage_payload.get("output_tokens", 0)),
            )
        )
        elapsed_seconds += _non_negative_float(
            completion.get("elapsed_time", completion.get("elapsed_seconds", 0.0))
        )
        cost_usd += _non_negative_float(
            completion.get("cost", completion.get("cost_usd", 0.0))
        )
        usage = dict(completion.get("cache_usage", {}) or {})
        if not bool(usage.get("observable", False)):
            continue
        observed = True
        current_input = _non_negative_int(usage.get("input_tokens", 0))
        current_read = min(
            current_input,
            _non_negative_int(usage.get("hit_tokens", 0)),
        )
        current_write = _non_negative_int(usage.get("creation_tokens", 0))
        current_miss = _non_negative_int(usage.get("miss_tokens", 0))
        if current_miss <= 0 and current_input > current_read:
            current_miss = current_input - current_read
        input_tokens += current_input
        cache_read_tokens += current_read
        cache_write_tokens += current_write
        cache_miss_tokens += current_miss

    hit_rate = (
        round(cache_read_tokens / input_tokens, 6)
        if observed and input_tokens > 0
        else (0.0 if observed else None)
    )
    return {
        "provider": provider,
        "model": model,
        "observed": observed,
        "status": _cache_status(
            observed=observed,
            cache_read_tokens=cache_read_tokens,
            cache_write_tokens=cache_write_tokens,
        ),
        "input_tokens": input_tokens,
        "cache_read_tokens": cache_read_tokens,
        "cache_write_tokens": cache_write_tokens,
        "cache_miss_tokens": cache_miss_tokens,
        "hit_rate": hit_rate,
        "attempt_count": len(completions),
        "retry_count": max(0, len(completions) - 1),
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "total_tokens": prompt_tokens + completion_tokens,
        "elapsed_seconds": round(elapsed_seconds, 4),
        "cost_usd": round(cost_usd, 8),
    }


def record_generation_cache_observation(
    session: dict[str, Any],
    observation: dict[str, Any] | None,
    *,
    turn_id: str,
    updated_at: str,
) -> dict[str, Any]:
    if observation is None:
        return dict(session.get("generation_cache_stats", {}) or {})

    existing = dict(session.get("generation_cache_stats", {}) or {})
    summary = {
        **dict(empty_generation_cache_stats()["session"]),
        **dict(existing.get("session", {}) or {}),
    }
    item = _normalize_observation(
        observation,
        turn_id=str(turn_id or "").strip(),
        updated_at=str(updated_at or "").strip(),
    )
    previous_turns = [
        dict(entry)
        for entry in list(existing.get("turns", []) or [])
        if isinstance(entry, dict)
    ]
    replaced = [
        entry
        for entry in previous_turns
        if str(entry.get("turn_id", "")).strip() == item["turn_id"]
    ]
    turns = [
        entry
        for entry in previous_turns
        if str(entry.get("turn_id", "")).strip() != item["turn_id"]
    ]
    turns.append(item)
    turns = turns[-MAX_RECORDED_TURNS:]

    # 同一 turn_id 重复上报时，turns 列表按 id 去重覆盖，累计计数也必须先把旧值扣回，
    # 否则 total_turns/tokens 会随重试次数翻倍，与 turns 条目数不一致。
    for old in replaced:
        summary["total_turns"] = _non_negative_int(summary.get("total_turns", 0)) - 1
        for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
            summary[key] = _non_negative_int(summary.get(key, 0)) - _non_negative_int(old.get(key, 0))
        summary["elapsed_seconds"] = round(
            _non_negative_float(summary.get("elapsed_seconds", 0.0))
            - _non_negative_float(old.get("elapsed_seconds", 0.0)),
            4,
        )
        summary["cost_usd"] = round(
            _non_negative_float(summary.get("cost_usd", 0.0))
            - _non_negative_float(old.get("cost_usd", 0.0)),
            8,
        )
        summary["attempt_count"] = (
            _non_negative_int(summary.get("attempt_count", 0)) - _non_negative_int(old.get("attempt_count", 0))
        )
        summary["retry_count"] = (
            _non_negative_int(summary.get("retry_count", 0)) - _non_negative_int(old.get("retry_count", 0))
        )
        old_models = dict(summary.get("models", {}) or {})
        old_model = str(old.get("model", "")).strip() or "unknown"
        old_models[old_model] = _non_negative_int(old_models.get(old_model, 0)) - _non_negative_int(old.get("attempt_count", 0))
        if old_models[old_model] <= 0:
            old_models.pop(old_model, None)
        summary["models"] = old_models
        if old.get("observed"):
            summary["observed_turns"] = _non_negative_int(summary.get("observed_turns", 0)) - 1
            for key in (
                "input_tokens",
                "cache_read_tokens",
                "cache_write_tokens",
                "cache_miss_tokens",
            ):
                summary[key] = _non_negative_int(summary.get(key, 0)) - _non_negative_int(old.get(key, 0))

    summary["total_turns"] = _non_negative_int(summary.get("total_turns", 0)) + 1
    for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
        summary[key] = _non_negative_int(summary.get(key, 0)) + item[key]
    summary["elapsed_seconds"] = round(
        _non_negative_float(summary.get("elapsed_seconds", 0.0))
        + item["elapsed_seconds"],
        4,
    )
    summary["cost_usd"] = round(
        _non_negative_float(summary.get("cost_usd", 0.0)) + item["cost_usd"],
        8,
    )
    summary["attempt_count"] = (
        _non_negative_int(summary.get("attempt_count", 0)) + item["attempt_count"]
    )
    summary["retry_count"] = (
        _non_negative_int(summary.get("retry_count", 0)) + item["retry_count"]
    )
    models = dict(summary.get("models", {}) or {})
    model_key = str(item.get("model", "")).strip() or "unknown"
    models[model_key] = _non_negative_int(models.get(model_key, 0)) + item["attempt_count"]
    summary["models"] = models
    summary["average_elapsed_seconds"] = round(
        summary["elapsed_seconds"] / max(1, summary["total_turns"]), 4
    )
    if item["observed"]:
        summary["observed_turns"] = (
            _non_negative_int(summary.get("observed_turns", 0)) + 1
        )
        for key in (
            "input_tokens",
            "cache_read_tokens",
            "cache_write_tokens",
            "cache_miss_tokens",
        ):
            summary[key] = _non_negative_int(summary.get(key, 0)) + item[key]

    observed_turns = _non_negative_int(summary.get("observed_turns", 0))
    total_turns = _non_negative_int(summary.get("total_turns", 0))
    total_input = _non_negative_int(summary.get("input_tokens", 0))
    total_read = _non_negative_int(summary.get("cache_read_tokens", 0))
    total_write = _non_negative_int(summary.get("cache_write_tokens", 0))
    summary["observed"] = observed_turns > 0
    summary["hit_rate"] = (
        round(total_read / total_input, 6)
        if observed_turns > 0 and total_input > 0
        else (0.0 if observed_turns > 0 else None)
    )
    summary["status"] = _cache_status(
        observed=observed_turns > 0,
        cache_read_tokens=total_read,
        cache_write_tokens=total_write,
    )
    if 0 < observed_turns < total_turns:
        summary["status"] = "partial"

    stats = {"latest": item, "turns": turns, "session": summary}
    session["generation_cache_stats"] = stats
    return stats


def _normalize_observation(
    observation: dict[str, Any], *, turn_id: str, updated_at: str
) -> dict[str, Any]:
    observed = bool(observation.get("observed", False))
    input_tokens = _non_negative_int(observation.get("input_tokens", 0))
    cache_read_tokens = min(
        input_tokens,
        _non_negative_int(observation.get("cache_read_tokens", 0)),
    )
    cache_write_tokens = _non_negative_int(
        observation.get("cache_write_tokens", 0)
    )
    cache_miss_tokens = _non_negative_int(
        observation.get("cache_miss_tokens", 0)
    )
    if observed and cache_miss_tokens <= 0 and input_tokens > cache_read_tokens:
        cache_miss_tokens = input_tokens - cache_read_tokens
    hit_rate = (
        round(cache_read_tokens / input_tokens, 6)
        if observed and input_tokens > 0
        else (0.0 if observed else None)
    )
    attempt_count = max(
        1, _non_negative_int(observation.get("attempt_count", 1))
    )
    return {
        "turn_id": turn_id,
        "provider": str(observation.get("provider", "")).strip(),
        "model": str(observation.get("model", "")).strip(),
        "observed": observed,
        "status": _cache_status(
            observed=observed,
            cache_read_tokens=cache_read_tokens,
            cache_write_tokens=cache_write_tokens,
        ),
        "input_tokens": input_tokens,
        "cache_read_tokens": cache_read_tokens,
        "cache_write_tokens": cache_write_tokens,
        "cache_miss_tokens": cache_miss_tokens,
        "hit_rate": hit_rate,
        "attempt_count": attempt_count,
        "retry_count": _non_negative_int(
            observation.get("retry_count", max(0, attempt_count - 1))
        ),
        "prompt_tokens": _non_negative_int(observation.get("prompt_tokens", 0)),
        "completion_tokens": _non_negative_int(
            observation.get("completion_tokens", 0)
        ),
        "total_tokens": _non_negative_int(
            observation.get(
                "total_tokens",
                _non_negative_int(observation.get("prompt_tokens", 0))
                + _non_negative_int(observation.get("completion_tokens", 0)),
            )
        ),
        "elapsed_seconds": round(
            _non_negative_float(observation.get("elapsed_seconds", 0.0)), 4
        ),
        "cost_usd": round(
            _non_negative_float(observation.get("cost_usd", 0.0)), 8
        ),
        "updated_at": updated_at,
    }


def _cache_status(
    *, observed: bool, cache_read_tokens: int, cache_write_tokens: int
) -> str:
    if not observed:
        return "unsupported"
    if cache_read_tokens > 0:
        return "hit"
    if cache_write_tokens > 0:
        return "write"
    return "miss"


def _non_negative_int(value: Any) -> int:
    try:
        return max(0, int(value or 0))
    except (TypeError, ValueError):
        return 0


def _non_negative_float(value: Any) -> float:
    try:
        return max(0.0, float(value or 0.0))
    except (TypeError, ValueError):
        return 0.0


__all__ = [
    "empty_generation_cache_stats",
    "record_generation_cache_observation",
    "summarize_completion_results",
]
