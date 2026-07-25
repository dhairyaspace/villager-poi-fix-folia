# VillagerPoiHalt

A Paper/Folia plugin that stops villagers from triggering synchronous POI
(Point of Interest) loads, working around the confirmed, unresolved Folia
engine bug [PaperMC/Folia#292](https://github.com/PaperMC/Folia/issues/292).

## The problem

On [Folia](https://github.com/PaperMC/Folia) (PaperMC's regionized,
multithreaded fork), villager AI brain behaviors — `AcquirePoi`,
`YieldJobSite`, and `PoiCompetitorScan` — periodically search for nearby
job-site blocks, beds, and bells via `PoiManager.getOrLoad()`.

When that lookup needs a chunk whose POI index is **not** already
loaded/cached in the current thread's region, it force-loads the POI data
**synchronously**, blocking the region's tick thread. Folia's lead developer
has confirmed that POI data loading does not safely route through the tick
scheduler. There is no server config option that prevents it — the only fix
is to avoid triggering the behavior at the plugin level.

## The workaround

This plugin calls `villager.setAI(false)` on villagers in scope. With NoAI
set, the server never runs `Brain.tick()` for that entity, so the POI-scanning
behaviors never execute and `PoiManager.getOrLoad()` is never reached.

**What still works / what stops:**

| Feature                         | Halted villager |
| ------------------------------- | --------------- |
| Right-click trading GUI         | ✅ Works (trading does not depend on AI ticking) |
| Existing trade offers & pricing | ✅ Works |
| Movement / pathfinding          | ❌ Stops |
| Working at job site / restock   | ❌ Stops (restock is brain-schedule driven) |
| Breeding                        | ❌ Stops |
| Sleeping / bell gathering       | ❌ Stops |

> Tip: for trading halls this is usually acceptable — trades keep functioning
> at current stock. Use scoped mode (below) if you need free-roaming or quest
> villagers elsewhere to behave normally.

## Requirements

- **Server:** Folia (or Paper) 1.21.x — declares `folia-supported: true`
- **Java:** 21+
- **Optional:** [WorldGuard](https://enginehub.org/worldguard) 7.x, only if
  you want region-scoped halting (`wg:` entries)

## Building

```bash
mvn package
```

Output: `target/VillagerPoiHalt-1.0.0.jar` — drop it into your server's
`plugins/` folder.

## Configuration (`config.yml`)

```yaml
# If true, EVERY villager in EVERY world gets AI disabled on spawn/load.
# The scoping list below is ignored when this is true.
disable-ai-globally: true

# Only used when disable-ai-globally is false.
# Villagers are halted only when they spawn/load INSIDE one of these areas.
disabled-regions: []
```

### Scoped area formats

Set `disable-ai-globally: false` and add entries in either format:

**1. World + radius** — a 2D circle around `x,z` (y is ignored):

```yaml
disabled-regions:
  - "radius:world:100:-250:64"     # 64-block circle around 100,-250 in "world"
```

**2. WorldGuard region** — requires the WorldGuard plugin:

```yaml
disabled-regions:
  - "wg:world:trading_hall"        # region "trading_hall" in world "world"
```

Villagers outside all listed areas are left completely untouched.

## Commands

Permission: `vpoihalt.admin` (default: op)

| Command | Description |
| ------- | ----------- |
| `/vpoihalt status` | Shows how many currently-loaded villagers have AI disabled by this plugin, plus the active mode and configured areas. |
| `/vpoihalt toggle <world> <x> <z> <radius>` | Halts (or un-halts, if already halted by this plugin) each villager within the radius. Great for runtime testing without a restart. |
| `/vpoihalt restore <world> <x> <z> <radius>` | Cleanly re-enables AI for villagers **this plugin** halted within the radius. Villagers set to NoAI by other plugins/map-makers are never touched. |
| `/vpoihalt reload` | Reloads `config.yml`. |

## How it works

### When villagers are halted

- **Fresh spawns** — `CreatureSpawnEvent` (breeding, eggs, curing, natural spawns, commands)
- **Chunk loads** — `EntityAddToWorldEvent` covers pre-existing villagers as their chunks load
- **Plugin enable** — a startup sweep applies the policy to all already-loaded villagers

### Persistence & safety

Halted villagers are marked with a `PersistentDataContainer` flag
(`vpoihalt:halted`). This means:

- The "halted by this plugin" state survives chunk unloads and server restarts.
- `restore` only ever reverts villagers *we* halted — a villager that was NoAI
  for any other reason (another plugin, a map-maker) is never modified.
- `onDisable` intentionally does **not** re-enable AI (Folia schedulers are
  shut down at that point, and keeping NoAI across restarts is the desired
  behavior). Use `/vpoihalt restore` to revert explicitly.

### Folia threading model

All entity mutations run on the correct region thread — violating this throws
Folia's `TickThread` / "failed main thread check" errors:

1. Entity event handlers (`CreatureSpawnEvent`, `EntityAddToWorldEvent`) fire
   on the entity's owning region thread, so direct mutation there is safe.
2. Everything else (startup sweep, commands) takes a **read-only snapshot** of
   the world's villagers — `World#getEntitiesByClass` is backed by Folia's
   concurrent entity lookup — then hops onto each villager's own
   `EntityScheduler` (`villager.getScheduler().run(...)`), which Folia
   guarantees to execute on the region thread that currently owns the entity.
3. Inside the region-thread task, validity and radius are **re-checked**
   (the snapshot may be stale — the villager can move or despawn in between).
4. Command results are aggregated with atomics and a completion countdown that
   also counts `retired` callbacks (entity removed before the task ran), so
   the summary message always fires.
5. The legacy `Bukkit.getScheduler()` is never used for entity work.

## Project layout

```
├─ pom.xml                                    Maven, Java 21, Paper API 1.21.4
└─ src/main/
   ├─ resources/
   │  ├─ plugin.yml                           folia-supported: true, command, permissions
   │  └─ config.yml                           disable-ai-globally + disabled-regions
   └─ java/com/example/villagerpoihalt/
      ├─ VillagerPoiHaltPlugin.java           main class, startup sweep
      ├─ HaltManager.java                     halt/restore/policy, PDC marker, live counter
      ├─ Settings.java                        config parsing (radius: / wg: formats)
      ├─ VillagerListener.java                spawn / chunk-load / unload hooks
      ├─ VpoiHaltCommand.java                 status / toggle / restore / reload
      └─ WorldGuardHook.java                  optional WG integration (soft dependency)
```

## License

Do whatever you want with it. Provided as-is as a workaround until
[Folia#292](https://github.com/PaperMC/Folia/issues/292) is fixed upstream.
