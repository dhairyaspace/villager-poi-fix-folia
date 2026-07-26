# Versioning Policy

VillagerPoiHalt uses a strict `vMAJOR.MINOR.PATCH` scheme.

## Rules

| Change type | Version bump | Example |
| --- | --- | --- |
| **Minor fix** (bugfix, typo, small tweak, no behavior change) | `PATCH` +1 | `v1.0.0` → `v1.0.1` |
| **Major fix / feature** (new feature, behavior change, significant fix) | `MINOR` +1, `PATCH` resets to 0 | `v1.0.4` → `v1.1.0` |
| **Minor cap reached** — after any `v1.9.x`, the next major fix rolls the major version | `MAJOR` +1, rest resets | `v1.9.3` → `v2.0.0` |

Notes:

- `MINOR` never exceeds **9**. There is no `v1.10.0`; the next step after
  `v1.9.x` is `v2.0.0`.
- Patch releases can continue on `v1.9.x` (e.g. `v1.9.4`, `v1.9.5`) — only the
  next *major fix* triggers the rollover to `v2.0.0`.

## Release checklist (every version)

1. **Bump the version** in `pom.xml` (`<version>` tag). `plugin.yml` picks it
   up automatically via Maven resource filtering (`${project.version}`).
2. **Create the release notes file**: `releases/vX.Y.Z.md`
   - One file per release, named exactly after the version.
   - Include: release date, type (patch/minor/major), jar name, and a
     `Changes` section listing every fix/feature with a short explanation.
3. **Build**: `mvn package` → `target/VillagerPoiHalt-X.Y.Z.jar`
4. **Update the release history table** in this file (below).

## Release notes template (`releases/vX.Y.Z.md`)

```markdown
# VillagerPoiHalt vX.Y.Z — <Short Title>

**Release date:** YYYY-MM-DD · **Type:** patch|minor|major · **Jar:** `VillagerPoiHalt-X.Y.Z.jar`

## 🔧 Changes

- Fixed: <what was broken and what changed>
- Added: <new feature>
- Changed: <behavior change and why>

## ⬆️ Upgrade notes

<config changes, migration steps, or "drop-in replacement, no config changes">
```

## Release history

| Version | Type | Date | Notes |
| --- | --- | --- | --- |
| [v1.2.0](releases/v1.2.0.md) | Minor (major feature) | 2025 | Managed employment (POI-free job assignment) + managed restock (4x/20min) |
| [v1.1.0](releases/v1.1.0.md) | Minor (major fix) | 2025 | Strip POI-scanning world spawners (CatSpawner 135s stall, VillageSiege, WanderingTraderSpawner) |
| [v1.0.0](releases/v1.0.0.md) | Initial release | 2025 | First public release — Folia#292 workaround |
