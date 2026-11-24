from __future__ import annotations

import unittest
from pathlib import Path


class InstallScriptTests(unittest.TestCase):
    def test_install_script_can_try_auto_install_base_tools(self):
        repo_root = Path(__file__).resolve().parents[1]
        script_text = (repo_root / "scripts" / "install.sh").read_text(encoding="utf-8")

        self.assertIn("install_system_packages()", script_text)
        self.assertIn("auto_install_base_tools()", script_text)
        self.assertIn('missing_tools+=("curl" "wget")', script_text)
        self.assertIn('missing_tools+=("git")', script_text)
        self.assertIn('missing_tools+=("tar")', script_text)
        self.assertIn('missing_tools+=("coreutils")', script_text)
        self.assertIn("auto_install_base_tools || true", script_text)
        self.assertIn("Trying to install base CLI tools", script_text)
        self.assertIn("Trying to install a downloader", script_text)

    def test_install_script_can_try_auto_install_python_with_platform_package_manager(self):
        repo_root = Path(__file__).resolve().parents[1]
        script_text = (repo_root / "scripts" / "install.sh").read_text(encoding="utf-8")

        self.assertIn("is_termux()", script_text)
        self.assertIn("auto_install_python()", script_text)
        self.assertIn("install_system_packages python", script_text)
        self.assertIn("install_system_packages python3 python3-venv", script_text)
        self.assertIn('pkg install -y "$@"', script_text)
        self.assertIn('run_pkg_manager apt-get update -y && run_pkg_manager apt-get install -y "$@"', script_text)
        self.assertNotIn('sh -c "$install_cmd"', script_text)
        self.assertIn('if auto_install_python; then', script_text)
        self.assertIn('pkg install python', script_text)
        self.assertIn('sudo apt-get install python3 python3-venv', script_text)

    def test_install_script_selects_termux_requirements(self):
        repo_root = Path(__file__).resolve().parents[1]
        script_text = (repo_root / "scripts" / "install.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("select_runtime_requirements()", script_text)
        self.assertIn(
            'RUNTIME_REQUIREMENTS_FILE="requirements.termux.txt"', script_text
        )
        self.assertIn(
            '[ "$RUNTIME_REQUIREMENTS_FILE" = "requirements.runtime.txt" ]',
            script_text,
        )

    def test_install_script_restores_previous_runtime_after_failure(self):
        repo_root = Path(__file__).resolve().parents[1]
        script_text = (repo_root / "scripts" / "install.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("INSTALL_SWAP_ACTIVE=0", script_text)
        self.assertIn('INSTALL_BACKUP="${INSTALL_ROOT}.backup.$$"', script_text)
        self.assertIn('mv "$INSTALL_BACKUP" "$INSTALL_ROOT"', script_text)
        self.assertIn("previous runtime restored", script_text)
        self.assertIn('cat >"$staged_launcher_path"', script_text)
        self.assertIn('mv "$staged_launcher_path" "$launcher_path"', script_text)

    def test_install_launcher_checks_remote_version_before_update(self):
        repo_root = Path(__file__).resolve().parents[1]
        script_text = (repo_root / "scripts" / "install.sh").read_text(encoding="utf-8")

        self.assertIn('VERSION_FILE_RELATIVE="src/web/static/version.txt"', script_text)
        self.assertIn('STORAGE_ROOT="${ZAOMENG_STORAGE_DIR:-$HOME/.local/share/zaomeng-data}"', script_text)
        self.assertIn('fetch_remote_version()', script_text)
        self.assertIn('local_version="\\$(current_version || true)"', script_text)
        self.assertIn('remote_version="\\$(fetch_remote_version "\\${target_ref}" || true)"', script_text)
        self.assertIn('Local version / 本地版本:  \\${local_version}', script_text)
        self.assertIn('Remote version / 远端版本: \\${remote_version}', script_text)
        self.assertIn('if [ "\\${local_version}" = "\\${remote_version}" ]; then', script_text)
        self.assertIn('Update skipped / 跳过更新: zaomeng is already up to date.', script_text)
        self.assertIn('Update required / 需要更新: \\${local_version} -> \\${remote_version}', script_text)
        self.assertIn('Version check unavailable, proceeding with update.', script_text)
        self.assertIn('if [ ! -x "$staged_launcher_path" ]; then', script_text)
        self.assertIn('Launcher creation failed / 启动命令创建失败', script_text)
        self.assertIn('export PATH="$HOME/.local/bin:$PATH"', script_text)
        self.assertIn('Data root / 数据目录:   ${STORAGE_ROOT}', script_text)
        self.assertIn('ZAOMENG_STORAGE_DIR="\\${STORAGE_ROOT}"', script_text)
        self.assertIn('BUILTIN_NOVELS_ROOT="\\${INSTALL_ROOT}/builtin_novels"', script_text)
        self.assertIn('env ZAOMENG_WEB_BUILTIN_NOVELS_ROOT="\\${BUILTIN_NOVELS_ROOT}"', script_text)
        self.assertIn('run_webui()', script_text)
        self.assertIn('run_webui() {', script_text)
        self.assertIn('"\\${PYTHON_BIN}" "\\${INSTALL_ROOT}/scripts/run_webui.py"', script_text)
        self.assertNotIn('env ZAOMENG_WEB_BUILTIN_NOVELS_ROOT="\\${BUILTIN_NOVELS_ROOT}"     exec "\\${PYTHON_BIN}"', script_text)

    def test_update_uses_local_installer_and_checks_supplied_archive_digest_before_extraction(self):
        script = (Path(__file__).resolve().parents[1] / "scripts" / "install.sh").read_text(encoding="utf-8")
        update = script.split("run_update() {", 1)[1].split("remove_path_line() {", 1)[0]
        self.assertIn('local installer="\\${INSTALL_ROOT}/scripts/install.sh"', update)
        self.assertIn('bash "\\${installer}"', update)
        self.assertNotIn("raw.githubusercontent.com", update)
        self.assertLess(script.index('verify_archive "$archive_path"'), script.index('tar -xzf "$archive_path"'))


if __name__ == "__main__":
    unittest.main()
