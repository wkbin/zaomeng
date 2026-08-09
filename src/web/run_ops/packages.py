from __future__ import annotations

import json
import stat
import shutil
import tempfile
import zipfile
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any, Callable

from src.web.manifest.compat import apply_imported_run_semantics, rewrite_run_root_paths
from src.web.run_ops.state import derive_summary_graph_status, derive_summary_status_text
from src.utils.file_utils import safe_filename

PACKAGE_KIND = "zaomeng_web_run_package"
PACKAGE_SCHEMA_VERSION = 2
PACKAGE_LEGACY_SCHEMA_VERSION = 0
SUPPORTED_PACKAGE_SCHEMA_VERSIONS = {PACKAGE_LEGACY_SCHEMA_VERSION, 1, PACKAGE_SCHEMA_VERSION}
PACKAGE_SUFFIX = ".zaomeng-run.zip"
PACKAGE_ROOT = "run"
PACKAGE_MANIFEST_NAME = "package_manifest.json"

MAX_PACKAGE_MEMBER_COUNT = 10_000
MAX_PACKAGE_DIRECTORY_COUNT = 10_000
MAX_PACKAGE_MEMBER_SIZE = 64 * 1024 * 1024
MAX_PACKAGE_TOTAL_UNCOMPRESSED_SIZE = 512 * 1024 * 1024
MAX_PACKAGE_COMPRESSION_RATIO = 200
MAX_PACKAGE_MANIFEST_SIZE = 256 * 1024
MAX_PACKAGE_MEMBER_PATH_LENGTH = 1_024
MAX_PACKAGE_MEMBER_PATH_DEPTH = 32
MAX_PACKAGE_PATH_COMPONENT_BYTES = 255
_PACKAGE_COPY_CHUNK_SIZE = 1024 * 1024
_SUPPORTED_PACKAGE_COMPRESSION = {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}
_WINDOWS_RESERVED_FILE_STEMS = {
    "aux",
    "con",
    "nul",
    "prn",
    *(f"com{index}" for index in range(1, 10)),
    *(f"lpt{index}" for index in range(1, 10)),
}


def package_filename_slug(title: str, *, fallback: str) -> str:
    slug = safe_filename(str(title or "").strip()) or safe_filename(fallback) or "novel"
    return slug[:80]


def build_package_filename(*, title: str, novel_id: str, run_id: str) -> str:
    slug = package_filename_slug(title or novel_id, fallback=run_id)
    return f"{slug}{PACKAGE_SUFFIX}"


def export_run_package(
    *,
    run_id: str,
    run_dir: Path,
    manifest: dict[str, Any],
    builtin: bool,
    utc_now: Callable[[], str],
    include_dialogue: bool | None = None,
) -> tuple[Path, str]:
    status = str(manifest.get("status", "")).strip()
    if status == "running":
        raise ValueError("这本书还在整理中，等这一轮结束后再导出小说包。")

    dialogue_exists = (run_dir / "dialogue").exists()
    includes_dialogue = (
        include_dialogue if include_dialogue is not None else (not builtin and dialogue_exists)
    )

    exports_dir = run_dir / "exports"
    exports_dir.mkdir(parents=True, exist_ok=True)
    title = _package_title(manifest)
    filename = build_package_filename(
        title=title,
        novel_id=str(manifest.get("novel_id", "")).strip() or run_id,
        run_id=run_id,
    )
    package_path = exports_dir / filename

    with tempfile.TemporaryDirectory(prefix="zaomeng-export-") as tmpdir:
        staging_root = Path(tmpdir)
        staged_run_dir = staging_root / PACKAGE_ROOT
        _copy_tree_without_metadata(
            run_dir,
            staged_run_dir,
            ignore=lambda relative: _is_export_copy_ignored(
                relative,
                include_dialogue=includes_dialogue,
            ),
        )
        _strip_export_only_paths(staged_run_dir, include_dialogue=includes_dialogue)
        package_manifest = _build_package_manifest(
            manifest=manifest,
            builtin=builtin,
            exported_at=utc_now(),
            includes_dialogue=includes_dialogue and dialogue_exists,
            includes_chapters=(run_dir / "chapters").exists(),
        )
        (staging_root / PACKAGE_MANIFEST_NAME).write_text(
            json.dumps(package_manifest, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        try:
            with zipfile.ZipFile(package_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.write(staging_root / PACKAGE_MANIFEST_NAME, PACKAGE_MANIFEST_NAME)
                for path in sorted(staged_run_dir.rglob("*")):
                    if path.is_dir():
                        continue
                    archive.write(path, path.relative_to(staging_root).as_posix())
            with zipfile.ZipFile(package_path) as archive:
                _read_package_manifest(archive)
        except Exception:
            package_path.unlink(missing_ok=True)
            raise
    return package_path, filename


def list_run_packages(packages_root: Path) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    if not packages_root.exists():
        return items
    for package_path in sorted(packages_root.glob(f"*{PACKAGE_SUFFIX}"), reverse=True):
        metadata = read_run_package_metadata(package_path)
        if not metadata:
            continue
        items.append(metadata)
    items.sort(key=lambda item: str(item.get("updated_at", "")), reverse=True)
    return items


def read_run_package_metadata(package_path: Path) -> dict[str, Any] | None:
    if not package_path.exists() or not package_path.is_file():
        return None
    try:
        with zipfile.ZipFile(package_path) as archive:
            manifest = _read_package_manifest(archive)
    except (OSError, ValueError, zipfile.BadZipFile):
        return None
    if not manifest:
        return None
    package_id = str(manifest.get("package_id", "")).strip() or package_path.stem
    title = str(manifest.get("title", "")).strip() or package_id
    return {
        "package_id": package_id,
        "title": title,
        "novel_id": str(manifest.get("novel_id", "")).strip(),
        "status": str(manifest.get("status", "")).strip(),
        "character_count": int(manifest.get("character_count", 0) or 0),
        "has_relation_graph": bool(manifest.get("has_relation_graph", False)),
        "includes_dialogue": bool(manifest.get("includes_dialogue", False)),
        "includes_chapters": bool(manifest.get("includes_chapters", False)),
        "updated_at": str(manifest.get("exported_at", "")).strip() or str(manifest.get("updated_at", "")).strip(),
        "filename": package_path.name,
        "package_path": str(package_path.resolve()),
        "builtin": bool(manifest.get("builtin", False)),
        "summary": dict(manifest.get("summary", {}) or {}),
    }


def import_run_package(
    *,
    package_path: Path,
    runs_root: Path,
    new_run_id: str,
    builtin_source: bool,
    utc_now: Callable[[], str],
    load_manifest: Callable[[Path], dict[str, Any] | None],
    write_json: Callable[[Path, dict[str, Any]], None],
    discover_artifacts: Callable[[dict[str, Any]], dict[str, Any]],
    serialize_manifest: Callable[[dict[str, Any]], dict[str, Any]],
    library_package: dict[str, str] | None = None,
) -> dict[str, Any]:
    target_run_dir = runs_root / new_run_id
    if target_run_dir.exists():
        raise ValueError("新书卷目录已经存在，请稍后再试。")

    try:
        with tempfile.TemporaryDirectory(prefix="zaomeng-import-") as tmpdir:
            extract_root = Path(tmpdir)
            with zipfile.ZipFile(package_path) as archive:
                _extract_package_archive(archive, extract_root)
            source_run_dir = extract_root / PACKAGE_ROOT
            if not source_run_dir.exists():
                raise ValueError("小说包缺少 run 数据目录。")
            _copy_tree_without_metadata(source_run_dir, target_run_dir)
            # ZIP archives don't preserve empty directories. Every imported run
            # must still have a dialogue root so it can create new local sessions
            # even when the exported book had no chat history yet.
            (target_run_dir / "dialogue").mkdir(parents=True, exist_ok=True)

        manifest_path = target_run_dir / "run_manifest.json"
        manifest = load_manifest(manifest_path)
        if not manifest:
            raise ValueError("小说包缺少有效的 run_manifest.json。")

        source_run_dir = Path(str(manifest.get("webui", {}).get("run_dir", "")).strip() or target_run_dir)
        _rewrite_imported_dialogue_sessions(
            target_run_dir=target_run_dir,
            source_run_dir=source_run_dir,
            new_run_id=new_run_id,
            write_json=write_json,
        )
        rewritten = rewrite_imported_run_manifest(
            manifest,
            source_run_dir=source_run_dir,
            target_run_dir=target_run_dir,
            new_run_id=new_run_id,
            imported_at=utc_now(),
            package_filename=package_path.name,
            builtin_source=builtin_source,
            library_package=library_package,
        )
        refreshed = discover_artifacts(rewritten)
        write_json(manifest_path, refreshed)
        return serialize_manifest(refreshed)
    except Exception:
        shutil.rmtree(target_run_dir, ignore_errors=True)
        raise


def rewrite_imported_run_manifest(
    manifest: dict[str, Any],
    *,
    source_run_dir: Path,
    target_run_dir: Path,
    new_run_id: str,
    imported_at: str,
    package_filename: str,
    builtin_source: bool,
    library_package: dict[str, str] | None = None,
) -> dict[str, Any]:
    source_root = source_run_dir.resolve(strict=False)
    target_root = target_run_dir.resolve(strict=False)
    rewritten = rewrite_run_root_paths(manifest, source_root=source_root, target_root=target_root)
    return apply_imported_run_semantics(
        rewritten,
        target_root=target_root,
        new_run_id=new_run_id,
        imported_at=imported_at,
        package_filename=package_filename,
        builtin_source=builtin_source,
        library_package=library_package,
    )


def _build_package_manifest(
    *,
    manifest: dict[str, Any],
    builtin: bool,
    exported_at: str,
    includes_dialogue: bool,
    includes_chapters: bool,
) -> dict[str, Any]:
    title = _package_title(manifest)
    package_id = package_filename_slug(title, fallback=str(manifest.get("run_id", "run")).strip() or "run")
    relation_graph = dict(manifest.get("artifact_index", {}).get("relation_graph", {}) or {})
    characters = list(manifest.get("artifact_index", {}).get("characters", []) or [])
    return {
        "kind": PACKAGE_KIND,
        "schema_version": PACKAGE_SCHEMA_VERSION,
        "package_id": package_id,
        "title": title,
        "novel_id": str(manifest.get("novel_id", "")).strip(),
        "original_run_id": str(manifest.get("run_id", "")).strip(),
        "status": str(manifest.get("status", "")).strip(),
        "character_count": len(characters),
        "has_relation_graph": bool(relation_graph.get("relations_file")),
        "summary": {
            "status_text": derive_summary_status_text(manifest),
            "graph_status": derive_summary_graph_status(manifest),
        },
        "exported_at": exported_at,
        "updated_at": str(manifest.get("updated_at", "")).strip(),
        "builtin": builtin,
        "includes_dialogue": includes_dialogue,
        "includes_chapters": includes_chapters,
    }


def _package_title(manifest: dict[str, Any]) -> str:
    novel_id = str(manifest.get("novel_id", "")).strip()
    return novel_id or str(manifest.get("run_id", "")).strip() or "未命名书卷"


def _read_package_manifest(
    archive: zipfile.ZipFile,
    *,
    members: list[tuple[zipfile.ZipInfo, PurePosixPath, bool]] | None = None,
) -> dict[str, Any]:
    validated_members = _validate_package_archive(archive) if members is None else members
    manifest_info = next(
        (
            info
            for info, relative, is_directory in validated_members
            if relative.as_posix() == PACKAGE_MANIFEST_NAME and not is_directory
        ),
        None,
    )
    if manifest_info is None:
        raise ValueError("小说包缺少 package_manifest.json。")
    try:
        payload = json.loads(
            _read_archive_member_bytes(
                archive,
                manifest_info,
                max_bytes=MAX_PACKAGE_MANIFEST_SIZE,
            ).decode("utf-8")
        )
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exc:
        raise ValueError("小说包元数据格式不正确。") from exc
    if not isinstance(payload, dict):
        raise ValueError("小说包元数据格式不正确。")
    if str(payload.get("kind", "")).strip() != PACKAGE_KIND:
        raise ValueError("不是可识别的造梦小说包。")
    return _normalize_package_manifest(payload)


def _extract_package_archive(archive: zipfile.ZipFile, extract_root: Path) -> None:
    members = _validate_package_archive(archive)
    _read_package_manifest(archive, members=members)
    extracted_total = 0
    root = extract_root.resolve(strict=False)

    for info, relative, is_directory in members:
        target = extract_root.joinpath(*relative.parts)
        try:
            target.resolve(strict=False).relative_to(root)
        except ValueError as exc:  # pragma: no cover - validation is the primary guard
            raise ValueError(f"小说包成员路径越界：{info.filename}") from exc

        if is_directory:
            target.mkdir(parents=True, exist_ok=True)
            continue

        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(info, "r") as source, target.open("xb") as destination:
            copied = 0
            while True:
                chunk = source.read(_PACKAGE_COPY_CHUNK_SIZE)
                if not chunk:
                    break
                copied += len(chunk)
                extracted_total += len(chunk)
                if copied > MAX_PACKAGE_MEMBER_SIZE:
                    raise ValueError(f"小说包成员展开后过大：{info.filename}")
                if extracted_total > MAX_PACKAGE_TOTAL_UNCOMPRESSED_SIZE:
                    raise ValueError("小说包展开后的总大小超过限制。")
                destination.write(chunk)
        if copied != info.file_size:
            raise ValueError(f"小说包成员大小与目录记录不一致：{info.filename}")


def _validate_package_archive(
    archive: zipfile.ZipFile,
) -> list[tuple[zipfile.ZipInfo, PurePosixPath, bool]]:
    infos = archive.infolist()
    if len(infos) > MAX_PACKAGE_MEMBER_COUNT:
        raise ValueError("小说包包含的文件数量超过限制。")

    members: list[tuple[zipfile.ZipInfo, PurePosixPath, bool]] = []
    seen: dict[str, tuple[PurePosixPath, bool]] = {}
    directories: set[str] = set()
    total_size = 0
    manifest_count = 0

    for info in infos:
        original_filename = getattr(info, "orig_filename", info.filename)
        relative = _validated_package_member_path(original_filename)
        is_directory = _package_member_is_directory(info)
        canonical = relative.as_posix().casefold()
        if canonical in seen:
            raise ValueError(f"小说包包含重复成员：{info.filename}")
        seen[canonical] = (relative, is_directory)

        directory_depth = len(relative.parts) if is_directory else len(relative.parts) - 1
        for parent_length in range(1, directory_depth + 1):
            directory = PurePosixPath(*relative.parts[:parent_length]).as_posix().casefold()
            directories.add(directory)
        if len(directories) > MAX_PACKAGE_DIRECTORY_COUNT:
            raise ValueError("小说包展开后的目录数量超过限制。")

        if relative.as_posix() == PACKAGE_MANIFEST_NAME:
            if is_directory:
                raise ValueError("package_manifest.json 必须是普通文件。")
            manifest_count += 1
        elif relative.parts[0] != PACKAGE_ROOT:
            raise ValueError(f"小说包包含 run 目录之外的成员：{info.filename}")
        elif len(relative.parts) == 1 and not is_directory:
            raise ValueError("小说包中的 run 必须是目录。")

        if info.flag_bits & 0x1:
            raise ValueError(f"小说包不能包含加密成员：{info.filename}")
        if is_directory:
            if info.file_size != 0:
                raise ValueError(f"小说包目录成员大小非法：{info.filename}")
        else:
            if info.compress_type not in _SUPPORTED_PACKAGE_COMPRESSION:
                raise ValueError(f"小说包使用了不支持的压缩方式：{info.filename}")
            if info.file_size < 0 or info.compress_size < 0:
                raise ValueError(f"小说包成员大小非法：{info.filename}")
            if info.file_size > MAX_PACKAGE_MEMBER_SIZE:
                raise ValueError(f"小说包成员展开后过大：{info.filename}")
            if relative.as_posix() == PACKAGE_MANIFEST_NAME and info.file_size > MAX_PACKAGE_MANIFEST_SIZE:
                raise ValueError("package_manifest.json 超过大小限制。")
            if info.file_size > 0 and info.compress_size == 0:
                raise ValueError(f"小说包成员压缩大小非法：{info.filename}")
            if info.file_size > info.compress_size * MAX_PACKAGE_COMPRESSION_RATIO:
                raise ValueError(f"小说包成员压缩比过高：{info.filename}")
            total_size += info.file_size
            if total_size > MAX_PACKAGE_TOTAL_UNCOMPRESSED_SIZE:
                raise ValueError("小说包展开后的总大小超过限制。")

        members.append((info, relative, is_directory))

    if manifest_count != 1:
        raise ValueError("小说包缺少 package_manifest.json。")

    for canonical, (relative, _) in seen.items():
        for parent_length in range(1, len(relative.parts)):
            parent_key = PurePosixPath(*relative.parts[:parent_length]).as_posix().casefold()
            parent = seen.get(parent_key)
            if parent is not None and not parent[1]:
                raise ValueError(
                    f"小说包成员路径与普通文件冲突：{relative.as_posix()}"
                )
        if canonical == PACKAGE_MANIFEST_NAME.casefold() and len(relative.parts) != 1:
            raise ValueError("package_manifest.json 路径非法。")
    return members


def _validated_package_member_path(filename: str) -> PurePosixPath:
    if not filename or "\x00" in filename:
        raise ValueError("小说包包含空路径或非法路径。")
    if len(filename) > MAX_PACKAGE_MEMBER_PATH_LENGTH:
        raise ValueError("小说包成员路径过长。")

    normalized = filename.replace("\\", "/")
    windows_path = PureWindowsPath(filename)
    if normalized.startswith("/") or windows_path.is_absolute() or windows_path.drive:
        raise ValueError(f"小说包不能包含绝对路径：{filename}")

    parts = normalized.split("/")
    if parts and parts[-1] == "":
        parts.pop()
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise ValueError(f"小说包成员路径包含穿越段：{filename}")
    if len(parts) > MAX_PACKAGE_MEMBER_PATH_DEPTH:
        raise ValueError(f"小说包成员路径层级过深：{filename}")
    if any(len(part.encode("utf-8")) > MAX_PACKAGE_PATH_COMPONENT_BYTES for part in parts):
        raise ValueError(f"小说包成员路径段过长：{filename}")
    if any(":" in part or part.rstrip(" .") != part for part in parts):
        raise ValueError(f"小说包成员路径不适用于本地存储：{filename}")
    if any(_is_windows_reserved_filename(part) for part in parts):
        raise ValueError(f"小说包成员路径使用了系统保留名称：{filename}")
    return PurePosixPath(*parts)


def _is_windows_reserved_filename(component: str) -> bool:
    stem = component.split(".", 1)[0].rstrip(" ").casefold()
    return stem in _WINDOWS_RESERVED_FILE_STEMS


def _package_member_is_directory(info: zipfile.ZipInfo) -> bool:
    file_type = stat.S_IFMT((info.external_attr >> 16) & 0xFFFF)
    if file_type == stat.S_IFLNK:
        raise ValueError(f"小说包不能包含符号链接：{info.filename}")
    if file_type not in {0, stat.S_IFREG, stat.S_IFDIR}:
        raise ValueError(f"小说包包含不支持的特殊成员：{info.filename}")
    if file_type == stat.S_IFDIR and not info.is_dir():
        raise ValueError(f"小说包目录成员格式非法：{info.filename}")
    if info.is_dir() and file_type == stat.S_IFREG:
        raise ValueError(f"小说包目录成员格式非法：{info.filename}")
    return info.is_dir()


def _read_archive_member_bytes(
    archive: zipfile.ZipFile,
    info: zipfile.ZipInfo,
    *,
    max_bytes: int,
) -> bytes:
    chunks: list[bytes] = []
    size = 0
    with archive.open(info, "r") as source:
        while True:
            chunk = source.read(min(_PACKAGE_COPY_CHUNK_SIZE, max_bytes + 1 - size))
            if not chunk:
                break
            size += len(chunk)
            if size > max_bytes:
                raise ValueError(f"小说包成员超过读取限制：{info.filename}")
            chunks.append(chunk)
    if size != info.file_size:
        raise ValueError(f"小说包成员大小与目录记录不一致：{info.filename}")
    return b"".join(chunks)


def _normalize_package_manifest(payload: dict[str, Any]) -> dict[str, Any]:
    schema_version = _coerce_schema_version(payload.get("schema_version"), default=PACKAGE_LEGACY_SCHEMA_VERSION)
    if schema_version not in SUPPORTED_PACKAGE_SCHEMA_VERSIONS:
        raise ValueError(
            f"小说包 schema_version={schema_version} 暂不支持（当前支持：{sorted(SUPPORTED_PACKAGE_SCHEMA_VERSIONS)}）。"
        )
    normalized = _migrate_legacy_package_manifest(payload, schema_version=schema_version)
    summary_payload = dict(normalized.get("summary", {}) or {})
    status = str(normalized.get("status", "")).strip()
    graph_status = str(summary_payload.get("graph_status", "")).strip()
    if not graph_status:
        graph_status = "complete" if bool(normalized.get("has_relation_graph", False)) else "pending"
    normalized["summary"] = {
        "status_text": str(summary_payload.get("status_text", "")).strip() or status,
        "graph_status": graph_status,
    }
    normalized["builtin"] = bool(normalized.get("builtin", False))
    normalized["character_count"] = _coerce_non_negative_int(normalized.get("character_count"), default=0)
    normalized["has_relation_graph"] = bool(normalized.get("has_relation_graph", False))
    normalized["includes_dialogue"] = bool(normalized.get("includes_dialogue", False))
    normalized["includes_chapters"] = bool(normalized.get("includes_chapters", False))
    normalized["schema_version"] = PACKAGE_SCHEMA_VERSION
    return normalized


def _migrate_legacy_package_manifest(payload: dict[str, Any], *, schema_version: int) -> dict[str, Any]:
    normalized = dict(payload)
    if schema_version == PACKAGE_LEGACY_SCHEMA_VERSION:
        relation_graph = bool(normalized.get("has_relation_graph", False))
        summary_payload = dict(normalized.get("summary", {}) or {})
        normalized["summary"] = {
            "status_text": str(summary_payload.get("status_text", "")).strip() or str(normalized.get("status", "")).strip(),
            "graph_status": str(summary_payload.get("graph_status", "")).strip() or ("complete" if relation_graph else "pending"),
        }
        normalized["builtin"] = bool(normalized.get("builtin", False))
        normalized["character_count"] = _coerce_non_negative_int(normalized.get("character_count"), default=0)
        normalized["has_relation_graph"] = relation_graph
    return normalized


def _coerce_schema_version(value: Any, *, default: int) -> int:
    if value is None:
        return default
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, (int, float)):
        return int(value)
    text = str(value).strip()
    if not text:
        return default
    try:
        return int(text)
    except ValueError as exc:
        raise ValueError(f"小说包 schema_version 非法：{value!r}") from exc


def _coerce_non_negative_int(value: Any, *, default: int) -> int:
    if value is None:
        return default
    if isinstance(value, bool):
        coerced = int(value)
    elif isinstance(value, (int, float)):
        coerced = int(value)
    else:
        text = str(value).strip()
        if not text:
            return default
        try:
            coerced = int(text)
        except ValueError:
            return default
    return max(coerced, 0)


def _copy_tree_without_metadata(
    source_root: Path,
    target_root: Path,
    *,
    ignore: Callable[[Path], bool] | None = None,
) -> None:
    """Copy package data without propagating host xattrs or ownership metadata."""
    if target_root.exists():
        raise FileExistsError(target_root)
    target_root.mkdir(parents=True)
    try:
        for source in sorted(source_root.rglob("*")):
            relative = source.relative_to(source_root)
            if ignore is not None and ignore(relative):
                continue
            if source.is_symlink():
                raise ValueError("书卷目录不能包含符号链接。")
            target = target_root / relative
            if source.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            elif source.is_file():
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
            else:
                raise ValueError(f"书卷目录包含不支持的文件类型：{relative.as_posix()}")
    except Exception:
        shutil.rmtree(target_root, ignore_errors=True)
        raise


def _rewrite_imported_dialogue_sessions(
    *,
    target_run_dir: Path,
    source_run_dir: Path,
    new_run_id: str,
    write_json: Callable[[Path, dict[str, Any]], None],
) -> None:
    dialogue_dir = target_run_dir / "dialogue"
    if not dialogue_dir.exists():
        return
    for session_path in dialogue_dir.glob("*/session.json"):
        try:
            payload = json.loads(session_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(payload, dict):
            continue
        rewritten = rewrite_run_root_paths(
            payload,
            source_root=source_run_dir.resolve(strict=False),
            target_root=target_run_dir.resolve(strict=False),
        )
        rewritten["run_id"] = new_run_id
        write_json(session_path, rewritten)


def _is_export_copy_ignored(relative: Path, *, include_dialogue: bool) -> bool:
    ignored_directories = {"exports", "__pycache__"}
    if not include_dialogue:
        ignored_directories.add("dialogue")
    return any(part in ignored_directories for part in relative.parts) or relative.suffix == ".pyc"


def _strip_export_only_paths(run_dir: Path, *, include_dialogue: bool) -> None:
    dialogue_dir = run_dir / "dialogue"
    if not include_dialogue and dialogue_dir.exists():
        shutil.rmtree(dialogue_dir, ignore_errors=False)
