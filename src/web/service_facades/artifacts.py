from __future__ import annotations

from pathlib import Path
from typing import Any

from src.web.persona_avatars import avatar_path, avatar_version
from src.utils.file_utils import save_markdown_data
from src.web.time_utils import utc_now as _utc_now
from src.web.review.persona_quality import evaluate_persona_quality
from src.web.review.profile_evidence import PERSONA_EVIDENCE_FILENAME
from src.web.artifacts import (
    export_relations_source,
    load_profile_source,
    load_relations_source,
    materialize_profile_source,
    resolve_persona_dir,
    resolve_relations_file,
    resolve_run_file,
    write_persona_profile,
)
from src.web.artifacts import (
    coerce_relation_evidence,
    relation_type_label,
    split_relation_pair,
)
from src.web.artifacts import list_relation_details as build_relation_details_payload
from src.web.artifacts import update_relation_detail as apply_relation_detail_update
from src.web.artifacts import (
    ingest_character_result as apply_character_ingest,
    ingest_relation_result as apply_relation_ingest,
)
from src.web.review import (
    PERSONA_AUTOFILLABLE_FIELDS,
    apply_persona_review_updates,
    collect_persona_web_references,
    get_persona_review_payload,
    read_persona_review_fields,
    resolve_persona_review_source,
    save_persona_review_payload,
    suggest_redistill_segments_payload,
    suggest_persona_field_payload,
)



class ArtifactServiceMixin:
    def save_persona_avatar(self, run_id: str, character: str, content: bytes) -> dict[str, str]:
        manifest = self._require_manifest(run_id)
        resolve_persona_dir(manifest, character)
        if not content:
            raise ValueError("头像文件为空。")
        target = avatar_path(self.runs_root / run_id, character)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(".tmp")
        temporary.write_bytes(content)
        temporary.replace(target)
        return self.persona_avatar_metadata(run_id, character)

    def persona_avatar_metadata(self, run_id: str, character: str) -> dict[str, str]:
        manifest = self._require_manifest(run_id)
        resolve_persona_dir(manifest, character)
        path = avatar_path(self.runs_root / run_id, character)
        return {
            "character": str(character).strip(),
            "avatar_version": avatar_version(path),
        }

    def persona_avatar_path(self, run_id: str, character: str) -> Path:
        manifest = self._require_manifest(run_id)
        resolve_persona_dir(manifest, character)
        path = avatar_path(self.runs_root / run_id, character)
        if not path.exists():
            raise FileNotFoundError(character)
        return path

    def ingest_character_result(
        self,
        run_id: str,
        *,
        character: str,
        content_base64: str,
        filename: str = "PROFILE.generated.md",
    ) -> dict[str, Any]:
        manifest_path = self._manifest_path(run_id)
        refreshed = self._update_manifest(
            manifest_path,
            lambda current: apply_character_ingest(
                run_id=run_id,
                runs_root=self.runs_root,
                manifest=current,
                character=character,
                content_base64=content_base64,
                filename=filename,
                materialize_profile_source=materialize_profile_source,
                discover_artifacts=self._discover_artifacts,
                utc_now=_utc_now,
            ),
        )
        return self._serialize_manifest(refreshed)

    def ingest_relation_result(
        self,
        run_id: str,
        *,
        content_base64: str,
        filename: str = "relations.md",
    ) -> dict[str, Any]:
        manifest_path = self._manifest_path(run_id)
        refreshed = self._update_manifest(
            manifest_path,
            lambda current: apply_relation_ingest(
                run_id=run_id,
                runs_root=self.runs_root,
                manifest_path=manifest_path,
                manifest=current,
                content_base64=content_base64,
                filename=filename,
                export_relations_source=lambda relation_source: export_relations_source(
                    relation_source,
                    novel_id=str(current.get("novel_id", "")).strip() or run_id,
                    manifest_path=manifest_path,
                ),
                discover_artifacts=self._discover_artifacts,
                utc_now=_utc_now,
            ),
        )
        return self._serialize_manifest(refreshed)

    def get_persona_review(self, run_id: str, character: str) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        persona_dir = resolve_persona_dir(manifest, character)
        return get_persona_review_payload(
            run_id=run_id,
            character=character,
            persona_dir=persona_dir,
            resolve_persona_review_source=resolve_persona_review_source,
            load_profile_source=load_profile_source,
            read_persona_review_fields=read_persona_review_fields,
        )

    def get_persona_quality_report(self, run_id: str, character: str) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        persona_dir = resolve_persona_dir(manifest, character)
        _, _, source_path = resolve_persona_review_source(persona_dir)
        if not source_path.exists():
            raise FileNotFoundError(character)
        evidence_path = persona_dir / PERSONA_EVIDENCE_FILENAME
        evidence_bundle = self._load_json_file(evidence_path) or {}
        report = evaluate_persona_quality(
            load_profile_source(source_path),
            character=character,
            evidence_bundle=evidence_bundle,
        )
        report_path = persona_dir / "QUALITY_REPORT.json"
        relative_path = report_path.resolve().relative_to((self.runs_root / run_id).resolve()).as_posix()
        report["artifact"] = {
            "relative_path": relative_path,
            "file_url": f"/api/web/runs/{run_id}/files/{relative_path}",
        }
        if evidence_path.exists():
            evidence_relative_path = evidence_path.resolve().relative_to((self.runs_root / run_id).resolve()).as_posix()
            report["evidence_artifact"] = {
                "relative_path": evidence_relative_path,
                "file_url": f"/api/web/runs/{run_id}/files/{evidence_relative_path}",
            }
        self._write_json(report_path, report)
        return report

    def save_persona_review(self, run_id: str, character: str, fields: dict[str, str]) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        persona_dir = resolve_persona_dir(manifest, character)
        result = save_persona_review_payload(
            run_id=run_id,
            character=character,
            fields=fields,
            manifest=manifest,
            persona_dir=persona_dir,
            resolve_persona_review_source=resolve_persona_review_source,
            load_profile_source=load_profile_source,
            read_persona_review_fields=read_persona_review_fields,
            apply_persona_review_updates=apply_persona_review_updates,
            write_persona_profile=write_persona_profile,
            discover_artifacts=self._discover_artifacts,
            get_persona_review=self.get_persona_review,
            utc_now=_utc_now,
        )
        self._write_json(self._manifest_path(run_id), result["manifest"])
        return result["payload"]

    def suggest_persona_field(self, run_id: str, character: str, field: str) -> dict[str, Any]:
        if not self.model_is_configured():
            raise ValueError("Model is not configured yet.")
        manifest = self._require_manifest(run_id)
        persona_dir = resolve_persona_dir(manifest, character)
        config = self._build_runtime_config_for_run(run_dir=self.runs_root / run_id)
        parts = self._build_runtime_parts(config)
        return suggest_persona_field_payload(
            run_id=run_id,
            character=character,
            field=field,
            persona_dir=persona_dir,
            manifest=manifest,
            resolve_persona_review_source=resolve_persona_review_source,
            load_profile_source=load_profile_source,
            read_persona_review_fields=read_persona_review_fields,
            collect_references=lambda current_character, novel_title: collect_persona_web_references(
                character=current_character,
                novel_title=novel_title,
            ),
            chat_completion=lambda messages, temperature, max_tokens: parts.llm.chat_completion(
                messages,
                temperature=temperature,
                max_tokens=max_tokens,
            ),
        )

    def suggest_redistill_segments(self, run_id: str, character: str, max_segments: int = 3) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        current_fields: dict[str, str] = {}
        try:
            persona_dir = resolve_persona_dir(manifest, character)
            _, _, source_path = resolve_persona_review_source(persona_dir)
            if source_path.exists():
                current_fields = read_persona_review_fields(load_profile_source(source_path))
        except FileNotFoundError:
            current_fields = {}
        return suggest_redistill_segments_payload(
            manifest,
            character=character,
            current_fields=current_fields,
            max_segments=max_segments,
        )

    def list_relation_details(self, run_id: str) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        relations_file = resolve_relations_file(manifest)
        payload = load_relations_source(relations_file)
        return build_relation_details_payload(
            run_id=run_id,
            manifest=manifest,
            relations_file=relations_file,
            payload=payload,
            split_relation_pair=split_relation_pair,
            relation_type_label=relation_type_label,
            coerce_relation_evidence=coerce_relation_evidence,
        )

    def update_relation_detail(self, run_id: str, pair_key: str, updates: dict[str, Any]) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        relations_file = resolve_relations_file(manifest)
        payload = load_relations_source(relations_file)
        refreshed_payload = apply_relation_detail_update(
            run_id=run_id,
            manifest=manifest,
            relations_file=relations_file,
            payload=payload,
            pair_key=pair_key,
            updates=updates,
            save_relations=lambda path, data: save_markdown_data(
                path,
                data,
                title="RELATION_GRAPH",
                summary=[
                    f"- novel_id: {data.get('novel_id', '')}",
                    f"- relation_count: {len(dict(data.get('relations', {}) or {}))}",
                    f"- conflict_count: {len(list(data.get('conflicts', []) or []))}",
                ],
            ),
            detect_conflicts=self._detect_relation_conflicts,
        )
        return build_relation_details_payload(
            run_id=run_id,
            manifest=manifest,
            relations_file=relations_file,
            payload=refreshed_payload,
            split_relation_pair=split_relation_pair,
            relation_type_label=relation_type_label,
            coerce_relation_evidence=coerce_relation_evidence,
        )

    def resolve_run_file(self, run_id: str, relative_path: str) -> Path:
        return resolve_run_file(
            runs_root=self.runs_root,
            run_id=run_id,
            relative_path=relative_path,
        )

    @staticmethod
    def _detect_relation_conflicts(relations: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
        conflicts: list[dict[str, Any]] = []
        for pair_key, relation in (relations or {}).items():
            if not isinstance(relation, dict):
                continue
            trust = int(relation.get("trust", 5) or 5)
            affection = int(relation.get("affection", 5) or 5)
            hostility = int(relation.get("hostility", 0) or 0)
            ambiguity = int(relation.get("ambiguity", 3) or 3)
            tags = []
            if trust >= 8 and hostility >= 6:
                tags.append("high_trust_high_hostility")
            if affection >= 8 and hostility >= 6:
                tags.append("high_affection_high_hostility")
            if ambiguity >= 8 and max(trust, affection, hostility) >= 8:
                tags.append("high_ambiguity_with_extreme_signal")
            if tags:
                conflicts.append(
                    {
                        "pair_key": pair_key,
                        "tags": tags,
                        "trust": trust,
                        "affection": affection,
                        "hostility": hostility,
                        "ambiguity": ambiguity,
                    }
                )
        return conflicts
