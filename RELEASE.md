# Release Flow

This file describes the repository-level release path for `clawhub-zaomeng-skill`.

The skill package itself keeps user-facing usage docs under `clawhub-zaomeng-skill/`.
This file is only for maintainers preparing a new published build.

## Single Version Source

The canonical version source is:

- `clawhub-zaomeng-skill/.metadata.json`

Everything else should be synchronized from that value.

## Recommended Sequence

Preferred one-command path:

```bash
py -3 scripts/release_skill.py --version <version>
```

This will:

- sync the version across metadata and release-facing docs
- run release guardrails
- package the distributable archive

Manual equivalent:

1. Sync the target version:

```bash
py -3 scripts/sync_skill_version.py --version <version>
```

2. Run release guardrails:

```bash
py -3 scripts/dev_checks.py
```

3. Build the distributable archive:

```bash
py -3 scripts/package_skill.py
```

4. Confirm the output archive name matches:

```text
zaomeng-<version>.skill.zip
```

## What Gets Synchronized

`scripts/sync_skill_version.py` updates:

- `clawhub-zaomeng-skill/.metadata.json`
- `clawhub-zaomeng-skill/SKILL.md`
- `clawhub-zaomeng-skill/README.md`
- `clawhub-zaomeng-skill/README_EN.md`
- `clawhub-zaomeng-skill/PUBLISH.md`
- `clawhub-zaomeng-skill/examples/test-prompts.json`

## What Gets Validated

`scripts/dev_checks.py` covers:

- packaging docs guardrails
- version sync regression checks
- package archive regression checks
- installable skill workflow checks
- mypy on the release helper scripts and prompt-first guardrail modules
- full unit test suite

## Packaging Output

`scripts/package_skill.py` produces:

- `dist/zaomeng-<version>.skill.zip`

The archive filename is derived from the version in `.metadata.json` unless an explicit override is provided.

`scripts/release_skill.py` uses the same archive builder after sync/check steps complete.
