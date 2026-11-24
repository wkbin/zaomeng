#!/usr/bin/env bash
set -euo pipefail

REPO_SLUG="${ZAOMENG_REPO_SLUG:-wkbin/zaomeng}"
REPO_REF="${ZAOMENG_REF:-main}"
INSTALL_ROOT="${ZAOMENG_INSTALL_DIR:-$HOME/.local/share/zaomeng}"
STORAGE_ROOT="${ZAOMENG_STORAGE_DIR:-$HOME/.local/share/zaomeng-data}"
BIN_DIR="${ZAOMENG_BIN_DIR:-$HOME/.local/bin}"
PYTHON_BIN="${ZAOMENG_PYTHON:-}"
RUNTIME_REQUIREMENTS_FILE="${ZAOMENG_REQUIREMENTS_FILE:-}"
TMP_DIR=""
INSTALL_BACKUP=""
INSTALL_SWAP_ACTIVE=0

cleanup() {
  local exit_code=$?
  trap - EXIT
  if [ "${INSTALL_SWAP_ACTIVE:-0}" -eq 1 ]; then
    rm -rf "$INSTALL_ROOT"
    if [ -n "${INSTALL_BACKUP:-}" ] && [ -d "${INSTALL_BACKUP:-}" ]; then
      mv "$INSTALL_BACKUP" "$INSTALL_ROOT"
      echo "Installation failed; previous runtime restored. / 安装失败，已恢复原有运行环境。" >&2
    else
      echo "Installation failed; incomplete runtime removed. / 安装失败，已清理不完整的运行环境。" >&2
    fi
  fi
  if [ -n "${TMP_DIR:-}" ] && [ -d "${TMP_DIR:-}" ]; then
    rm -rf "$TMP_DIR"
  fi
  exit "$exit_code"
}

# zaomeng uses PEP 604 union type syntax (e.g. str | Path), which requires Python >= 3.10
MIN_PYTHON_MAJOR=3
MIN_PYTHON_MINOR=10

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command / 缺少必要命令: $1" >&2
    exit 1
  fi
}

get_python_version() {
  local py="$1"
  "$py" -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>/dev/null || echo "0.0"
}

check_python_version() {
  local py="$1"
  local version major minor
  version="$(get_python_version "$py")"
  major="${version%%.*}"
  minor="${version##*.}"
  if [ "$major" -gt "$MIN_PYTHON_MAJOR" ] ||
    { [ "$major" -eq "$MIN_PYTHON_MAJOR" ] && [ "$minor" -ge "$MIN_PYTHON_MINOR" ]; }; then
    return 0
  fi
  return 1
}

print_python_version_help() {
  local current_version="$1"
  echo ""
  echo "=== Python version requirement / Python 版本要求 ===" >&2
  echo "" >&2
  echo "zaomeng requires Python >= ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR} (uses modern type syntax)." >&2
  echo "zaomeng 需要 Python >= ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}（使用了新式类型注解语法）。" >&2
  if [ -n "$current_version" ] && [ "$current_version" != "0.0" ]; then
    echo "Detected Python version / 检测到的 Python 版本: ${current_version}  ✗" >&2
  fi
  echo "" >&2
  echo "How to install Python ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}+ / 如何安装 Python ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}+:" >&2
  echo "" >&2

  if is_termux; then
    echo "  Termux:" >&2
    echo "    pkg install python" >&2
    echo ""
  elif command -v apt-get >/dev/null 2>&1 || command -v apt >/dev/null 2>&1; then
    echo "  Ubuntu / Debian:" >&2
    echo "    If the distribution provides Python 3.10+: sudo apt-get install python3 python3-venv" >&2
    echo "    sudo apt-get update && sudo apt-get install -y software-properties-common" >&2
    echo "    sudo add-apt-repository -y ppa:deadsnakes/ppa" >&2
    echo "    sudo apt-get install -y python${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR} python${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}-venv" >&2
    echo ""
    echo "  Or use pyenv / 或使用 pyenv:" >&2
    echo "    curl https://pyenv.run | bash" >&2
    echo "    pyenv install ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}.0" >&2
    echo "    pyenv global ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}.0" >&2
    echo ""
  elif command -v dnf >/dev/null 2>&1; then
    echo "  Fedora:" >&2
    echo "    sudo dnf install -y python${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}" >&2
    echo ""
  elif command -v yum >/dev/null 2>&1; then
    echo "  CentOS / RHEL:" >&2
    echo "    sudo yum install -y epel-release" >&2
    echo "    sudo yum install -y python${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}" >&2
    echo ""
  elif command -v pacman >/dev/null 2>&1; then
    echo "  Arch Linux:" >&2
    echo "    sudo pacman -Sy --noconfirm python" >&2
    echo ""
  elif command -v brew >/dev/null 2>&1; then
    echo "  macOS (Homebrew):" >&2
    echo "    brew install python@${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}" >&2
    echo ""
  else
    echo "  Please install Python ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}+ manually from:" >&2
    echo "  https://www.python.org/downloads/" >&2
    echo ""
    echo "  Or use pyenv:" >&2
    echo "    curl https://pyenv.run | bash" >&2
    echo "    pyenv install ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}.0" >&2
    echo "    pyenv global ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}.0" >&2
    echo ""
  fi

  echo "After installing, you can specify the Python binary explicitly / 安装后可以显式指定 Python:" >&2
  echo "  curl -fsSL https://raw.githubusercontent.com/${REPO_SLUG}/${REPO_REF}/scripts/install.sh | ZAOMENG_PYTHON=python${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR} bash" >&2
  echo ""
}

is_termux() {
  [ -n "${TERMUX_VERSION:-}" ] || [ -d "/data/data/com.termux/files/usr" ]
}

select_runtime_requirements() {
  if is_termux && {
    [ -z "$RUNTIME_REQUIREMENTS_FILE" ] ||
      [ "$RUNTIME_REQUIREMENTS_FILE" = "requirements.runtime.txt" ]
  }; then
    RUNTIME_REQUIREMENTS_FILE="requirements.termux.txt"
    return
  fi
  if [ -z "$RUNTIME_REQUIREMENTS_FILE" ]; then
    RUNTIME_REQUIREMENTS_FILE="requirements.runtime.txt"
  fi
}

install_system_packages() {
  if [ "$#" -eq 0 ]; then
    return 0
  fi

  if is_termux; then
    if command -v pkg >/dev/null 2>&1; then
      echo "Installing packages in Termux via pkg / 正在通过 pkg 安装依赖: $*" >&2
      pkg update -y >&2
      pkg install -y "$@" >&2
      return
    fi
    echo "Termux was detected but pkg is unavailable. / 检测到 Termux，但没有找到 pkg。" >&2
    return 1
  fi

  if command -v apt-get >/dev/null 2>&1; then
    echo "Installing packages via apt-get / 正在通过 apt-get 安装依赖: $*" >&2
    run_pkg_manager apt-get update -y && run_pkg_manager apt-get install -y "$@"
    return
  fi
  if command -v apt >/dev/null 2>&1; then
    echo "Installing packages via apt / 正在通过 apt 安装依赖: $*" >&2
    run_pkg_manager apt update -y && run_pkg_manager apt install -y "$@"
    return
  fi
  if command -v dnf >/dev/null 2>&1; then
    echo "Installing packages via dnf / 正在通过 dnf 安装依赖: $*" >&2
    run_pkg_manager dnf install -y "$@"
    return
  fi
  if command -v yum >/dev/null 2>&1; then
    echo "Installing packages via yum / 正在通过 yum 安装依赖: $*" >&2
    run_pkg_manager yum install -y "$@"
    return
  fi
  if command -v pacman >/dev/null 2>&1; then
    echo "Installing packages via pacman / 正在通过 pacman 安装依赖: $*" >&2
    run_pkg_manager pacman -Sy --noconfirm "$@"
    return
  fi
  if command -v zypper >/dev/null 2>&1; then
    echo "Installing packages via zypper / 正在通过 zypper 安装依赖: $*" >&2
    run_pkg_manager zypper --non-interactive install "$@"
    return
  fi
  if command -v apk >/dev/null 2>&1; then
    echo "Installing packages via apk / 正在通过 apk 安装依赖: $*" >&2
    run_pkg_manager apk add --no-cache "$@"
    return
  fi

  echo "No supported package manager was found for: $* / 未找到可用于安装以下依赖的包管理器: $*" >&2
  return 1
}

auto_install_base_tools() {
  local missing_tools=()

  if ! command -v tar >/dev/null 2>&1; then
    missing_tools+=("tar")
  fi
  if ! command -v mktemp >/dev/null 2>&1 || ! command -v chmod >/dev/null 2>&1; then
    missing_tools+=("coreutils")
  fi
  if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
    missing_tools+=("curl" "wget")
  fi
  if ! command -v git >/dev/null 2>&1; then
    missing_tools+=("git")
  fi

  if [ "${#missing_tools[@]}" -eq 0 ]; then
    return 0
  fi

  echo "Trying to install base CLI tools... / 正在尝试自动安装基础命令行工具..." >&2
  install_system_packages "${missing_tools[@]}"
}

run_pkg_manager() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
    return
  fi
  if command -v sudo >/dev/null 2>&1; then
    sudo "$@"
    return
  fi
  echo "Need elevated privileges to install dependencies / 安装依赖需要更高权限: $*" >&2
  return 1
}

auto_install_python() {
  echo "Python 3 was not found. Trying to install it automatically... / 未检测到 Python 3，正在尝试自动安装..." >&2

  if is_termux; then
    install_system_packages python
    return
  fi

  if command -v apt-get >/dev/null 2>&1 || command -v apt >/dev/null 2>&1; then
    install_system_packages python3 python3-venv
    return
  fi
  if command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1 || command -v zypper >/dev/null 2>&1; then
    install_system_packages python3
    return
  fi
  if command -v pacman >/dev/null 2>&1; then
    install_system_packages python
    return
  fi
  if command -v apk >/dev/null 2>&1; then
    install_system_packages python3 py3-pip
    return
  fi

  echo "Python 3 is required but no supported package manager was found. / 需要 Python 3，但没有找到可自动安装的包管理器。" >&2
  return 1
}

choose_python() {
  local candidate=""
  local version=""

  # 1) User-specified Python binary takes highest priority
  if [ -n "$PYTHON_BIN" ]; then
    if command -v "$PYTHON_BIN" >/dev/null 2>&1; then
      version="$(get_python_version "$PYTHON_BIN")"
      if check_python_version "$PYTHON_BIN"; then
        echo "$PYTHON_BIN"
        return
      fi
      echo "Specified Python ($PYTHON_BIN) is version ${version}, but Python >= ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR} is required." >&2
      echo "指定的 Python ($PYTHON_BIN) 版本为 ${version}，但需要 Python >= ${MIN_PYTHON_MAJOR}.${MIN_PYTHON_MINOR}。" >&2
      print_python_version_help "$version"
      exit 1
    fi
    echo "Specified Python not found / 指定的 Python 未找到: $PYTHON_BIN" >&2
    exit 1
  fi

  # 2) Try system python3
  if command -v python3 >/dev/null 2>&1; then
    version="$(get_python_version python3)"
    if check_python_version python3; then
      echo python3
      return
    fi
    candidate="python3"
  fi

  # 3) Try system python
  if command -v python >/dev/null 2>&1; then
    version="$(get_python_version python)"
    if check_python_version python; then
      echo python
      return
    fi
    if [ -z "$candidate" ]; then
      candidate="python"
    fi
  fi

  # 4) Try auto-installing Python through the system package manager
  if auto_install_python; then
    if command -v python3 >/dev/null 2>&1; then
      version="$(get_python_version python3)"
      if check_python_version python3; then
        echo python3
        return
      fi
    fi
    if command -v python >/dev/null 2>&1; then
      version="$(get_python_version python)"
      if check_python_version python; then
        echo python
        return
      fi
    fi
  fi

  # 5) No suitable Python found — print detailed help
  print_python_version_help "$version"
  exit 1
}

choose_fetch() {
  if command -v curl >/dev/null 2>&1; then
    echo curl
    return
  fi
  if command -v wget >/dev/null 2>&1; then
    echo wget
    return
  fi
  echo "curl or wget was not found. Trying to install a downloader... / 未检测到 curl 或 wget，正在尝试自动安装下载工具..." >&2
  if auto_install_base_tools; then
    if command -v curl >/dev/null 2>&1; then
      echo curl
      return
    fi
    if command -v wget >/dev/null 2>&1; then
      echo wget
      return
    fi
  fi
  echo "curl or wget is required. / 需要安装 curl 或 wget。" >&2
  exit 1
}

detect_rc_file() {
  if [ -n "${ZDOTDIR:-}" ] && [ -f "${ZDOTDIR}/.zshrc" ]; then
    echo "${ZDOTDIR}/.zshrc"
    return
  fi
  if [ -n "${SHELL:-}" ] && [[ "${SHELL}" == *zsh ]]; then
    echo "$HOME/.zshrc"
    return
  fi
  if [ -f "$HOME/.bashrc" ]; then
    echo "$HOME/.bashrc"
    return
  fi
  echo "$HOME/.profile"
}

append_path_line() {
  local rc_file="$1"
  local path_line
  if [ "$BIN_DIR" = "$HOME/.local/bin" ]; then
    path_line='export PATH="$HOME/.local/bin:$PATH"'
  else
    local escaped
    escaped="$(printf "%s" "$BIN_DIR" | sed "s/'/'\\\\''/g")"
    path_line="export PATH='$escaped':\$PATH"
  fi
  mkdir -p "$(dirname "$rc_file")"
  touch "$rc_file"
  if ! grep -Fq "$path_line" "$rc_file"; then
    printf '\n%s\n' "$path_line" >>"$rc_file"
  fi
}

fetch_archive() {
  local url="$1"
  local output="$2"
  local fetcher="$3"
  if [ "$fetcher" = "curl" ]; then
    curl --fail --silent --show-error --location \
      --retry 3 --retry-delay 2 --retry-all-errors \
      "$url" -o "$output"
  else
    wget --tries=3 --waitretry=2 -O "$output" "$url"
  fi
}

verify_archive() {
  local archive="$1"
  local expected="${ZAOMENG_ARCHIVE_SHA256:-}"
  local result
  [ -n "$expected" ] || return 0
  if [[ ! "$expected" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "ZAOMENG_ARCHIVE_SHA256 must contain 64 hex digits. / SHA-256 必须为 64 位十六进制。" >&2
    return 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    result="$(sha256sum < "$archive")" || return 1
  elif command -v shasum >/dev/null 2>&1; then
    result="$(shasum -a 256 < "$archive")" || return 1
  else
    echo "sha256sum or shasum is required to verify the archive. / 缺少 SHA-256 校验工具。" >&2
    return 1
  fi
  if [ "${result%% *}" != "$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')" ]; then
    echo "Archive SHA-256 mismatch. / 安装包 SHA-256 校验失败。" >&2
    return 1
  fi
}

main() {
  auto_install_base_tools || true
  need_cmd tar
  need_cmd mktemp
  need_cmd chmod
  select_runtime_requirements

  local python_cmd
  python_cmd="$(choose_python)"
  # Final safeguard: ensure the chosen Python meets the minimum version requirement
  if ! check_python_version "$python_cmd"; then
    local detected_ver
    detected_ver="$(get_python_version "$python_cmd")"
    echo "Internal error: Python version check failed / 内部错误: Python 版本检查未通过 (${python_cmd} = ${detected_ver})" >&2
    print_python_version_help "$detected_ver"
    exit 1
  fi
  local detected_version
  detected_version="$(get_python_version "$python_cmd")"
  echo "Python ${detected_version} detected / 检测到 Python ${detected_version} ✓"
  local fetcher
  fetcher="$(choose_fetch)"

  TMP_DIR="$(mktemp -d)"
  trap cleanup EXIT

  local archive_url="https://github.com/${REPO_SLUG}/archive/${REPO_REF}.tar.gz"
  local archive_path="${TMP_DIR}/zaomeng.tar.gz"
  local extract_root="${TMP_DIR}/extract"
  local venv_dir="${INSTALL_ROOT}/.venv"
  local launcher_path="${BIN_DIR}/zaomeng"
  local staged_launcher_path="${TMP_DIR}/zaomeng-launcher"
  local requirements_path="${INSTALL_ROOT}/${RUNTIME_REQUIREMENTS_FILE}"
  local staged_requirements_path
  local extracted_dir
  local rc_file
  rc_file="$(detect_rc_file)"

  mkdir -p "$extract_root" "$BIN_DIR" "$(dirname "$INSTALL_ROOT")" "$(dirname "$STORAGE_ROOT")"

  echo "Downloading / 正在下载: ${archive_url}"
  if ! fetch_archive "$archive_url" "$archive_path" "$fetcher"; then
    echo "Failed to download ${archive_url}. Please check your network connection and try again. / 下载失败，请检查网络后重试。" >&2
    exit 1
  fi

  verify_archive "$archive_path"
  tar -xzf "$archive_path" -C "$extract_root"
  extracted_dir="$(find "$extract_root" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  if [ -z "$extracted_dir" ]; then
    echo "Failed to locate extracted repository directory. / 未找到解压后的仓库目录。" >&2
    exit 1
  fi
  staged_requirements_path="${extracted_dir}/${RUNTIME_REQUIREMENTS_FILE}"
  if [ ! -f "$staged_requirements_path" ]; then
    echo "Missing runtime requirements file / 缺少运行时依赖文件: ${staged_requirements_path}" >&2
    exit 1
  fi

  INSTALL_BACKUP="${INSTALL_ROOT}.backup.$$"
  if [ -e "$INSTALL_BACKUP" ]; then
    echo "Install backup path already exists / 安装备份目录已存在: ${INSTALL_BACKUP}" >&2
    exit 1
  fi
  if [ -e "$INSTALL_ROOT" ]; then
    mv "$INSTALL_ROOT" "$INSTALL_BACKUP"
  else
    INSTALL_BACKUP=""
  fi
  INSTALL_SWAP_ACTIVE=1
  mv "$extracted_dir" "$INSTALL_ROOT"

  if [ ! -f "$requirements_path" ]; then
    echo "Missing runtime requirements file / 缺少运行时依赖文件: ${requirements_path}" >&2
    exit 1
  fi

  echo "Creating virtual environment / 正在创建虚拟环境"
  "$python_cmd" -m venv "$venv_dir"
  "$venv_dir/bin/python" -m pip install --upgrade pip setuptools wheel
  "$venv_dir/bin/python" -m pip install -r "$requirements_path"

  cat >"$staged_launcher_path" <<EOF
#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT=$(printf '%q' "$INSTALL_ROOT")
STORAGE_ROOT=$(printf '%q' "$STORAGE_ROOT")
BUILTIN_NOVELS_ROOT="\${INSTALL_ROOT}/builtin_novels"
PYTHON_BIN="\${INSTALL_ROOT}/.venv/bin/python"
INSTALL_PYTHON=$(printf '%q' "$python_cmd")
REPO_SLUG=$(printf '%q' "$REPO_SLUG")
REPO_REF=$(printf '%q' "$REPO_REF")
BIN_DIR=$(printf '%q' "$BIN_DIR")
RC_FILE=$(printf '%q' "$rc_file")
RUNTIME_REQUIREMENTS_FILE=$(printf '%q' "$RUNTIME_REQUIREMENTS_FILE")
VERSION_FILE_RELATIVE="src/web/static/version.txt"

if [ ! -x "\${PYTHON_BIN}" ]; then
  echo "zaomeng runtime is missing / 缺少 zaomeng 运行时: \${PYTHON_BIN}" >&2
  exit 1
fi

run_webui() {
  env ZAOMENG_WEB_BUILTIN_NOVELS_ROOT="\${BUILTIN_NOVELS_ROOT}" \
    "\${PYTHON_BIN}" "\${INSTALL_ROOT}/scripts/run_webui.py" --storage-root "\${STORAGE_ROOT}" "\$@"
}

current_version() {
  local version_file="\${INSTALL_ROOT}/\${VERSION_FILE_RELATIVE}"
  if [ ! -f "\${version_file}" ]; then
    return 1
  fi
  tr -d '\r' < "\${version_file}" | head -n 1
}

download_text() {
  local url="\${1:-}"
  if [ -z "\${url}" ]; then
    return 1
  fi
  if command -v curl >/dev/null 2>&1; then
    curl --fail --silent --show-error --location "\${url}"
    return
  fi
  if command -v wget >/dev/null 2>&1; then
    wget -qO- "\${url}"
    return
  fi
  return 1
}

fetch_remote_version() {
  local target_ref="\${1:-\${REPO_REF}}"
  local version_url="https://raw.githubusercontent.com/\${REPO_SLUG}/\${target_ref}/\${VERSION_FILE_RELATIVE}"
  download_text "\${version_url}" | tr -d '\r' | head -n 1
}

run_update() {
  local target_ref="\${1:-\${REPO_REF}}"
  local installer="\${INSTALL_ROOT}/scripts/install.sh"
  local local_version=""
  local remote_version=""

  local_version="\$(current_version || true)"
  remote_version="\$(fetch_remote_version "\${target_ref}" || true)"

  if [ -n "\${local_version}" ] && [ -n "\${remote_version}" ]; then
    echo "Local version / 本地版本:  \${local_version}"
    echo "Remote version / 远端版本: \${remote_version}"
    if [ "\${local_version}" = "\${remote_version}" ]; then
      echo "Update skipped / 跳过更新: zaomeng is already up to date."
      echo "zaomeng 已是最新版本，无需更新。"
      return 0
    fi
    echo "Update required / 需要更新: \${local_version} -> \${remote_version}"
    echo "Updating zaomeng / 正在更新 zaomeng: \${local_version} -> \${remote_version} (\${REPO_SLUG}@\${target_ref})"
  else
    echo "Updating zaomeng / 正在更新 zaomeng: \${REPO_SLUG}@\${target_ref}"
    echo "Version check unavailable, proceeding with update. / 暂时无法比对版本，继续执行更新。"
  fi

  if [ ! -f "\${installer}" ]; then
    echo "Installed updater is missing. / 本地安装脚本缺失。" >&2
    return 1
  fi
  env \
    ZAOMENG_REPO_SLUG="\${REPO_SLUG}" \
    ZAOMENG_REF="\${target_ref}" \
    ZAOMENG_INSTALL_DIR="\${INSTALL_ROOT}" \
    ZAOMENG_STORAGE_DIR="\${STORAGE_ROOT}" \
    ZAOMENG_BIN_DIR="\${BIN_DIR}" \
    ZAOMENG_PYTHON="\${INSTALL_PYTHON}" \
    ZAOMENG_REQUIREMENTS_FILE="\${RUNTIME_REQUIREMENTS_FILE}" \
    bash "\${installer}"
}

remove_path_line() {
  local rc_target="\${1:-}"
  local path_line
  if [ "\$BIN_DIR" = "\$HOME/.local/bin" ]; then
    path_line='export PATH="\$HOME/.local/bin:\$PATH"'
  else
    local escaped
    escaped="\$(printf "%s" "\$BIN_DIR" | sed "s/'/'\\\\\\\\''/g")"
    path_line="export PATH='\$escaped':\\\$PATH"
  fi
  [ -n "\${rc_target}" ] || return 0
  [ -f "\${rc_target}" ] || return 0
  local temp_file
  temp_file="\$(mktemp)"
  if ! grep -Fvx "\${path_line}" "\${rc_target}" > "\${temp_file}"; then
    : > "\${temp_file}"
  fi
  mv "\${temp_file}" "\${rc_target}"
}

run_uninstall() {
  local assume_yes=0
  local purge_data=0
  while [ \$# -gt 0 ]; do
    case "\$1" in
      -y|--yes)
        assume_yes=1
        ;;
      --purge-data)
        purge_data=1
        ;;
      -h|--help)
        cat <<'USAGE'
Usage: zaomeng uninstall [--yes] [--purge-data]

  --yes         Skip the confirmation prompt / 跳过确认
  --purge-data  Also delete the data root, including novels, sessions and personas
                同时删除数据目录（书卷、会话、人物档案）
USAGE
        return 0
        ;;
      *)
        echo "Unknown option / 未知参数: \$1" >&2
        exit 2
        ;;
    esac
    shift
  done

  local install_root="\${INSTALL_ROOT}"
  local storage_root="\${STORAGE_ROOT}"
  local launcher="\${BIN_DIR}/zaomeng"

  # 防御：空路径、根目录、家目录本身一旦传给 rm -rf 就是灾难，直接拒绝。
  local resolved_home
  resolved_home="\$(cd "\$HOME" 2>/dev/null && pwd -P || printf '%s' "\$HOME")"
  local target resolved
  for target in "\$install_root" "\$storage_root"; do
    resolved="\$(cd "\$target" 2>/dev/null && pwd -P || printf '%s' "\$target")"
    if [ -z "\$resolved" ] || [ "\$resolved" = "/" ] || [ "\$resolved" = "\$resolved_home" ]; then
      echo "Refusing to uninstall: unsafe path / 拒绝卸载：目录不安全: \$target" >&2
      exit 1
    fi
  done

  echo "The following will be removed / 将执行以下删除:"
  echo "  install root / 安装目录: \$install_root"
  echo "  launcher / 启动命令:     \$launcher"
  if [ "\$purge_data" -eq 1 ]; then
    echo "  data root / 数据目录:    \$storage_root  (--purge-data)"
  else
    echo "  data root / 数据目录:    \$storage_root  (kept / 保留，需 --purge-data 才删除)"
  fi

  if [ "\$assume_yes" -ne 1 ]; then
    if [ -t 0 ]; then
      printf 'Continue? [y/N] '
      read -r answer
      case "\$answer" in
        y|Y|yes|YES) ;;
        *)
          echo "Aborted / 已取消。"
          exit 1
          ;;
      esac
    else
      echo "Refusing to uninstall non-interactively; pass --yes / 非交互卸载需要 --yes。" >&2
      exit 1
    fi
  fi

  remove_path_line "\${RC_FILE}"
  rm -rf "\$install_root"
  rm -f "\$launcher"
  if [ "\$purge_data" -eq 1 ]; then
    rm -rf "\$storage_root"
  fi
  cat <<MSG
zaomeng has been removed.
zaomeng 已卸载完成。

Removed install root / 已删除安装目录: \$install_root
Removed launcher / 已删除启动命令: \$launcher
Updated shell rc / 已更新 shell 配置: \${RC_FILE}
MSG
  if [ "\$purge_data" -eq 1 ]; then
    echo "Removed data root / 已删除数据目录: \$storage_root"
  else
    echo "Data root kept / 数据目录保留: \$storage_root"
  fi
  echo
  echo "If your current shell still has the old PATH cached, open a new shell."
  echo "如果你当前 shell 里还保留旧 PATH，重新打开一个 shell 即可。"
}

if [ \$# -eq 0 ]; then
  run_webui
fi

case "\$1" in
  uninstall)
    shift
    run_uninstall "\$@"
    ;;
  update)
    shift
    run_update "\${1:-}"
    ;;
  web)
    shift
    run_webui "\$@"
    ;;
  bump-web-assets)
    shift
    if [ \$# -eq 0 ]; then
      exec "\${PYTHON_BIN}" "\${INSTALL_ROOT}/scripts/web_asset_version.py" --bump
    fi
    exec "\${PYTHON_BIN}" "\${INSTALL_ROOT}/scripts/web_asset_version.py" --version "\$1"
    ;;
  install-skill)
    shift
    exec "\${PYTHON_BIN}" "\${INSTALL_ROOT}/scripts/install_skill.py" "\$@"
    ;;
  version)
    exec "\${PYTHON_BIN}" - <<PY
from pathlib import Path
print(Path("${INSTALL_ROOT}/src/web/static/version.txt").read_text(encoding="utf-8").strip())
PY
    ;;
  help|-h|--help)
    cat <<'HELP'
zaomeng commands / 可用命令:
  zaomeng                Start the Web UI on 127.0.0.1:8000 / 启动 Web UI
  zaomeng uninstall [--yes] [--purge-data]
                         Remove the runtime and launcher; data is kept unless --purge-data
                         卸载运行环境与启动命令；默认保留数据，--purge-data 时一并删除
  zaomeng update [ref]   Reinstall from the current source or target ref / 从当前来源或指定 ref 重新安装更新
  zaomeng web [args]     Forward args to scripts/run_webui.py / 转发参数给 run_webui.py
  zaomeng bump-web-assets [version]
                         Bump or explicitly sync the static asset version / 更新或同步静态资源版本号
  zaomeng install-skill [args]
                         Forward args to scripts/install_skill.py / 转发参数给 install_skill.py
  zaomeng version        Print the current web static asset version / 输出当前静态资源版本
HELP
    ;;
  *)
    run_webui "\$@"
    ;;
esac
EOF

  chmod +x "$staged_launcher_path"
  if [ ! -x "$staged_launcher_path" ]; then
    echo "Launcher creation failed / 启动命令创建失败: ${staged_launcher_path}" >&2
    exit 1
  fi
  mv "$staged_launcher_path" "$launcher_path"

  INSTALL_SWAP_ACTIVE=0
  if [ -n "$INSTALL_BACKUP" ]; then
    if [ -d "${INSTALL_BACKUP}/.zaomeng-web" ] && [ ! -e "$STORAGE_ROOT" ]; then
      mv "${INSTALL_BACKUP}/.zaomeng-web" "$STORAGE_ROOT"
    fi
    rm -rf "$INSTALL_BACKUP"
    INSTALL_BACKUP=""
  fi
  mkdir -p "$STORAGE_ROOT"
  append_path_line "$rc_file"

cat <<EOF

zaomeng is installed.
zaomeng 已安装完成。

Install root / 安装目录: ${INSTALL_ROOT}
Data root / 数据目录:   ${STORAGE_ROOT}
Launcher / 启动命令:     ${launcher_path}
Requirements / 依赖文件: ${requirements_path}
Shell rc / Shell 配置:  ${rc_file}

Next / 下一步:
  Open a new shell, or run / 打开一个新的 shell，或执行：
  export PATH="$HOME/.local/bin:$PATH"
  zaomeng

If your shell rc already contains unrelated broken lines and "source ${rc_file}" reports errors,
如果你的 shell 配置里本来就有其他错误，导致 "source ${rc_file}" 报错，
you can still start zaomeng right away with / 你仍然可以直接这样启动：
  ${launcher_path}

Useful / 常用命令:
  zaomeng uninstall
  zaomeng update
  zaomeng web --reload
  zaomeng bump-web-assets
  zaomeng install-skill --skills-dir <your-skills-root>
EOF
}

main "$@"
