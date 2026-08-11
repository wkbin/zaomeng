<p align="center">
  <img src="docs/images/zaomeng_logo.png" alt="Zaomeng" width="120">
</p>

# Zaomeng

> “Some characters were not fully written away. They were only never truly awakened.”

Distill Chinese novel characters into reusable persona bundles, extract relationship graphs, and let characters speak again with their own personality, stance, bonds, and memory.

[![License: AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-8A2BE2.svg)](LICENSE) · [Website](https://wkbin.github.io/zaomeng/) · [中文](README.md)

## Project layout

| Path | Description | Status |
| --- | --- | --- |
| [`kmp/`](kmp/README.md) | Compose Multiplatform client: shared UI across Android / Desktop / iOS with an embedded Ktor + Room backend | ✅ actively maintained |
| `zaomeng-skill/` | OpenClaw / ClawHub skill bundle (CLI / agent environments) | ✅ kept |
| `src/`, `src/web/` | Early Python backend and Web UI | ⛔ **no longer maintained**; kept as a behavioral reference and test baseline |

## Install

### Clients

Android / Desktop / iOS installation and build instructions live in [`kmp/README.md`](kmp/README.md).

> ⚠️ Upgrading from the legacy Android build (1.5.0 and earlier) to **2.0.0** is a **breaking change**: local data is incompatible and is not migrated automatically. **Export your book bundles (`.zaomeng-run.zip`) as backups in the old version before upgrading.**

### Skill bundle

```bash
# OpenClaw
openclaw skills install wkbin/zaomeng-skill

# ClawHub
npx clawhub@latest install zaomeng-skill

# Local skills directory
python scripts/install_skill.py --skills-dir <your-skills-root>
```

### Web UI (legacy, no longer maintained, still installable)

```bash
# One-click install (Linux / macOS / WSL2 / Termux)
curl -fsSL https://raw.githubusercontent.com/wkbin/zaomeng/main/scripts/install.sh | bash
source ~/.bashrc
zaomeng
```

Common commands after install: `zaomeng web --reload` (starts the Web UI at `http://127.0.0.1:8000`), `zaomeng uninstall`, `zaomeng update`. Manual setup: `pip install -r requirements.runtime.txt` then `python scripts/run_webui.py --reload`. Dependencies: `requirements.txt` (full dev/test), `requirements.termux.txt` (Termux), including `httpx2` for web tests; EPUB parsing is optional.

## Screenshots

| Mobile | Desktop |
| --- | --- |
| <img src="docs/images/mobile.jpg" width="240" alt="Mobile"> | <img src="docs/images/desktop.png" width="480" alt="Desktop"> |

## Community

- QQ group: **1090225658**

<p>
  <img src="docs/assets/qq-group.png" alt="Zaomeng QQ group QR code, group 1090225658" width="360">
</p>

- Submit book bundles: [zaomeng-library](https://github.com/wkbin/zaomeng-library/issues)

## License

[AGPL-3.0](LICENSE)
