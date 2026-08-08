from __future__ import annotations

import shutil
import tempfile
from pathlib import Path
from typing import Any

from src.web.time_utils import utc_now as _utc_now
from src.web.run_ops.packages import export_run_package, import_run_package, list_run_packages


class PackageServiceMixin:
    def list_builtin_novels(self) -> list[dict[str, Any]]:
        return list_run_packages(self.builtin_novels_root)

    def clone_builtin_novel(self, package_id: str) -> dict[str, Any]:
        target = self._resolve_builtin_package(package_id)
        return import_run_package(
            package_path=target,
            runs_root=self.runs_root,
            new_run_id=self._new_run_id(),
            builtin_source=True,
            utc_now=_utc_now,
            load_manifest=self._load_manifest,
            write_json=self._write_json,
            discover_artifacts=self._discover_artifacts,
            serialize_manifest=self._serialize_manifest,
        )

    def import_run_package(
        self,
        *,
        filename: str,
        content_base64: str,
        library_package: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        data = self._decode_base64(content_base64)
        if not data:
            raise ValueError("导入的小说包内容为空。")
        safe_name = Path(str(filename or "").strip() or "imported-run-package.zip").name
        tmp_dir = self.storage_root / "tmp-imports"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="upload-", dir=tmp_dir) as request_tmp:
            package_path = Path(request_tmp) / safe_name
            package_path.write_bytes(data)
            return import_run_package(
                package_path=package_path,
                runs_root=self.runs_root,
                new_run_id=self._new_run_id(),
                builtin_source=False,
                utc_now=_utc_now,
                load_manifest=self._load_manifest,
                write_json=self._write_json,
                discover_artifacts=self._discover_artifacts,
                serialize_manifest=self._serialize_manifest,
                library_package=library_package,
            )

    def export_run_package(
        self,
        run_id: str,
        *,
        builtin: bool = False,
        include_dialogue: bool | None = None,
    ) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        package_path, filename = export_run_package(
            run_id=run_id,
            run_dir=self.runs_root / run_id,
            manifest=manifest,
            builtin=builtin,
            utc_now=_utc_now,
            include_dialogue=include_dialogue,
        )
        return {
            "run_id": run_id,
            "filename": filename,
            "path": package_path,
            "include_dialogue": (
                include_dialogue if include_dialogue is not None else (not builtin and (self.runs_root / run_id / "dialogue").exists())
            ),
        }

    def publish_run_as_builtin(self, run_id: str) -> dict[str, Any]:
        exported = self.export_run_package(run_id, builtin=True)
        target = self.builtin_novels_root / str(exported.get("filename", "")).strip()
        if not target.name:
            raise ValueError("导出的内置小说包文件名无效。")
        shutil.copy2(exported["path"], target)
        items = self.list_builtin_novels()
        published = next(
            (item for item in items if Path(str(item.get("package_path", "")).strip()) == target),
            None,
        )
        return {
            "run_id": run_id,
            "filename": target.name,
            "package_path": str(target.resolve()),
            "builtin_item": published or {},
        }

    def _resolve_builtin_package(self, package_id: str) -> Path:
        target_id = str(package_id or "").strip()
        if not target_id:
            raise ValueError("内置小说包标识不能为空。")
        for item in self.list_builtin_novels():
            if str(item.get("package_id", "")).strip() != target_id:
                continue
            package_path = Path(str(item.get("package_path", "")).strip())
            if package_path.exists():
                return package_path
        raise FileNotFoundError(target_id)
