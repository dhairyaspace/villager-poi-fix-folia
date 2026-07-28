package com.example.villagerpoihalt;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VillagerPoiHalt
 *
 * <p>Workaround for the confirmed, unresolved Folia bug
 * <a href="https://github.com/PaperMC/Folia/issues/292">PaperMC/Folia#292</a>:
 * villager brain behaviors ({@code AcquirePoi}, {@code YieldJobSite},
 * {@code PoiCompetitorScan}) call {@code PoiManager.getOrLoad()} while ticking.
 * When the POI index for a needed chunk is not already cached in the current
 * region, that call synchronously force-loads POI data and stalls the region's
 * tick thread. There is no server config to prevent it, so this plugin stops
 * the behaviors from ever running by calling {@link Villager#setAI(boolean)}
 * with {@code false}, which prevents {@code Brain.tick()} entirely.</p>
 *
 * <p>Right-click trading still works on AI-disabled villagers (the merchant
 * GUI does not depend on brain ticking), but halted villagers will not move,
 * path, work, restock on schedule, or breed.</p>
 *
 * <h2>Folia threading rules honored by this plugin</h2>
 * <ul>
 *   <li>Every entity mutation ({@code setAI}) happens on the entity's owning
 *       region thread, either inside an event handler (Folia fires entity
 *       events on the owning region thread) or via
 *       {@code entity.getScheduler().run(...)} — never via the legacy
 *       {@code Bukkit.getScheduler()}.</li>
 *   <li>Cross-world sweeps take a thread-safe snapshot of the entity list
 *       (Folia's entity lookup is concurrent) and then hop to each entity's
 *       own scheduler before touching it.</li>
 * </ul>
 */
public final class VillagerPoiHaltPlugin extends JavaPlugin {

    private Settings settings;
    private HaltManager haltManager;
    private SpawnerStripper spawnerStripper;
    private VillagerJobManager jobManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = Settings.load(this);
        this.haltManager = new HaltManager(this, settings);
        this.spawnerStripper = new SpawnerStripper(this);
        this.jobManager = new VillagerJobManager(this, haltManager, settings);

        getServer().getPluginManager().registerEvents(new VillagerListener(this, haltManager), this);

        PluginCommand command = getCommand("vpoihalt");
        if (command != null) {
            VpoiHaltCommand executor = new VpoiHaltCommand(this, haltManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        if (settings.usesWorldGuard() && !WorldGuardHook.isAvailable()) {
            getLogger().warning("config.yml lists 'wg:' regions but WorldGuard is not installed; "
                    + "those entries will be ignored.");
        }

        // --- Startup sweep over villagers that are ALREADY loaded, plus ----
        // --- stripping of POI-scanning world spawners (CatSpawner etc.). ---
        // FOLIA: we schedule the sweep on the GlobalRegionScheduler so it runs
        // once the server is fully ticking. Reading the per-world entity list
        // is safe from any thread on Folia (the entity lookup is a concurrent
        // snapshot), but MUTATING an entity is not — so for every villager we
        // hop onto its own EntityScheduler, which is guaranteed to execute on
        // the region thread that owns the entity (or not at all if the entity
        // is removed first). The spawner-list swap is a single reference
        // assignment (see SpawnerStripper) and is done from the global thread.
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            sweepLoadedVillagers();
            applySpawnerStripAll();
        });

        // Start the managed employment + restock schedulers (v1.2.0). These
        // give AI-disabled villagers back job-taking and trade restocking
        // WITHOUT any POI lookups. See VillagerJobManager.
        jobManager.start(settings);

        getLogger().info("VillagerPoiHalt enabled. Mode: "
                + (settings.disableAiGlobally() ? "GLOBAL" : "scoped to " + settings.areas().size() + " area(s)")
                + ". (Workaround for PaperMC/Folia#292)");
    }

    @Override
    public void onDisable() {
        // Stop the repeating employment/restock tasks cleanly.
        if (jobManager != null) {
            jobManager.stop();
        }
        // Intentionally do NOT re-enable AI here: onDisable cannot safely hop
        // to entity region threads on Folia (the plugin's schedulers are shut
        // down), and leaving NoAI set is exactly what we want across restarts.
        // Use "/vpoihalt restore ..." to revert villagers explicitly.
        getLogger().info("VillagerPoiHalt disabled. Halted villagers keep NoAI until restored via /vpoihalt restore.");
    }

    /**
     * Applies the configured policy to every currently-loaded villager.
     * Villagers in not-yet-loaded chunks are handled later by
     * {@link VillagerListener} when their entities are added to a world.
     */
    private void sweepLoadedVillagers() {
        int scheduled = 0;
        for (World world : Bukkit.getWorlds()) {
            // Snapshot list; safe to read on Folia. Do not mutate entities here!
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                // FOLIA: EntityScheduler#run executes on the entity's owning
                // region thread. The 'retired' callback (last arg) fires if the
                // entity is removed before the task can run.
                villager.getScheduler().run(this,
                        t -> haltManager.applyPolicy(villager),
                        null);
                scheduled++;
            }
        }
        getLogger().info("Startup sweep: scheduled policy check for " + scheduled + " loaded villager(s).");
    }

    public Settings settings() {
        return settings;
    }

    public SpawnerStripper spawnerStripper() {
        return spawnerStripper;
    }

    public VillagerJobManager jobManager() {
        return jobManager;
    }

    /**
     * Applies the configured spawner-strip policy to one world.
     * Called on plugin enable and from WorldLoadEvent (both on the global
     * region thread — the only place the spawner list swap should happen).
     */
    public void applySpawnerStrip(World world) {
        spawnerStripper.apply(world, settings.effectiveStripTargets());
    }

    private void applySpawnerStripAll() {
        for (World world : Bukkit.getWorlds()) {
            applySpawnerStrip(world);
        }
    }

    /** Re-reads config.yml (used by /vpoihalt reload). */
    public void reloadSettings() {
        reloadConfig();
        this.settings = Settings.load(this);
        this.haltManager.updateSettings(this.settings);
        // Re-apply spawner stripping: newly-enabled targets get removed and
        // cached instances of no-longer-stripped spawners get restored.
        // Also re-sweep loaded villagers so a changed halt-method migrates
        // already-halted villagers immediately (not just on next spawn/load).
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            applySpawnerStripAll();
            sweepLoadedVillagers();
        });
        // Restart the employment/restock schedulers with the new intervals.
        this.jobManager.start(this.settings);
    }
}
