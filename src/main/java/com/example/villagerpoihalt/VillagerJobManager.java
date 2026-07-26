package com.example.villagerpoihalt;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Gives AI-disabled villagers back their two most-wanted job functions —
 * <b>without</b> ever touching {@code PoiManager} (which is the whole point of
 * this plugin; see Folia#292).
 *
 * <p>Because {@code setAI(false)} stops {@code Brain.tick()}, villagers can no
 * longer:</p>
 * <ul>
 *   <li>acquire a profession from a nearby job-site block (vanilla does this
 *       via the {@code AcquirePoi} behavior — the exact POI lookup we ban), or</li>
 *   <li>restock their trades on their work schedule (also brain-driven).</li>
 * </ul>
 *
 * <p>This manager reimplements both safely:</p>
 * <ol>
 *   <li><b>Employment:</b> we run our OWN small block scan around each halted,
 *       unemployed adult villager. We only read blocks in already-loaded
 *       chunks, on the villager's own region thread, so no chunk/POI load is
 *       ever forced. If a job-site block is found we call
 *       {@link Villager#setProfession} and then generate the villager's trade
 *       offers directly (reflective call to the vanilla
 *       {@code Villager.updateTrades()}), bypassing the brain entirely.</li>
 *   <li><b>Restock:</b> on a fixed schedule we reset every recipe's use count
 *       to 0 (pure Bukkit API), which is exactly what "restock" means — the
 *       trades become available again. Default cadence: 4 times per 20 minutes
 *       (once every 5 minutes).</li>
 * </ol>
 *
 * <h2>Folia threading</h2>
 * <p>Two repeating tasks run on the {@code GlobalRegionScheduler}. Each tick
 * they snapshot the villager list per world (safe, concurrent) and then hop to
 * every villager's {@code EntityScheduler} to do the actual work on the region
 * thread that owns it. Block reads and entity mutations therefore always run
 * on the correct thread. Block access is additionally guarded by
 * {@code isChunkLoaded} and wrapped in try/catch so a villager sitting on a
 * region border can never trigger a cross-region access or a chunk load.</p>
 */
public final class VillagerJobManager {

    /** Vanilla job-site block -> profession mapping. */
    private static final Map<Material, Villager.Profession> JOB_SITES = Map.ofEntries(
            Map.entry(Material.BLAST_FURNACE, Villager.Profession.ARMORER),
            Map.entry(Material.SMOKER, Villager.Profession.BUTCHER),
            Map.entry(Material.CARTOGRAPHY_TABLE, Villager.Profession.CARTOGRAPHER),
            Map.entry(Material.BREWING_STAND, Villager.Profession.CLERIC),
            Map.entry(Material.COMPOSTER, Villager.Profession.FARMER),
            Map.entry(Material.BARREL, Villager.Profession.FISHERMAN),
            Map.entry(Material.FLETCHING_TABLE, Villager.Profession.FLETCHER),
            Map.entry(Material.CAULDRON, Villager.Profession.LEATHERWORKER),
            Map.entry(Material.LECTERN, Villager.Profession.LIBRARIAN),
            Map.entry(Material.STONECUTTER, Villager.Profession.MASON),
            Map.entry(Material.LOOM, Villager.Profession.SHEPHERD),
            Map.entry(Material.SMITHING_TABLE, Villager.Profession.TOOLSMITH),
            Map.entry(Material.GRINDSTONE, Villager.Profession.WEAPONSMITH)
    );

    private final VillagerPoiHaltPlugin plugin;
    private final HaltManager haltManager;

    /** Cached reflective handle to Villager.updateTrades() (Mojang-mapped). */
    private volatile Method updateTradesMethod;
    private volatile boolean updateTradesUnavailable;

    private ScheduledTask employmentTask;
    private ScheduledTask restockTask;

    private volatile Settings settings;

    public VillagerJobManager(VillagerPoiHaltPlugin plugin, HaltManager haltManager, Settings settings) {
        this.plugin = plugin;
        this.haltManager = haltManager;
        this.settings = settings;
    }

    /** (Re)starts the scheduled tasks according to the current settings. */
    public void start(Settings settings) {
        this.settings = settings;
        stop();

        // FOLIA: repeating tasks live on the global region scheduler; per-entity
        // work is dispatched onto each villager's own EntityScheduler below.
        if (settings.employmentEnabled()) {
            long period = Math.max(20L, settings.employmentIntervalTicks());
            employmentTask = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, t -> forEachManagedVillager(this::tryEmploy), period, period);
        }
        if (settings.restockEnabled()) {
            long period = Math.max(20L, settings.restockIntervalTicks());
            restockTask = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, t -> forEachManagedVillager(this::restock), period, period);
        }
    }

    /** Cancels both repeating tasks (reload / disable). */
    public void stop() {
        if (employmentTask != null) {
            employmentTask.cancel();
            employmentTask = null;
        }
        if (restockTask != null) {
            restockTask.cancel();
            restockTask = null;
        }
    }

    /**
     * Snapshots every world's villagers and dispatches {@code action} for each
     * one that this plugin manages (halted) onto its owning region thread.
     */
    private void forEachManagedVillager(java.util.function.Consumer<Villager> action) {
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                villager.getScheduler().run(plugin, t -> {
                    // Re-check on the region thread — snapshot may be stale.
                    if (villager.isValid() && haltManager.isHalted(villager)) {
                        action.accept(villager);
                    }
                }, null);
            }
        }
    }

    // ------------------------------------------------------------------ employment

    /** Region-thread only. Assigns a profession if an unemployed adult sits by a job site. */
    private void tryEmploy(Villager villager) {
        if (!villager.isAdult()) {
            return;
        }
        Villager.Profession current = villager.getProfession();
        // Only employ the jobless. Leave NITWIT and already-employed alone.
        if (current != Villager.Profession.NONE) {
            return;
        }

        Villager.Profession found = scanForJobSite(villager);
        if (found == null) {
            return;
        }

        villager.setProfession(found);
        if (villager.getVillagerLevel() < 1) {
            villager.setVillagerLevel(1); // tier 1 (Novice) so trades can generate
        }
        generateTrades(villager);
        plugin.getLogger().fine("Employed a villager as " + found + " at " + villager.getLocation());
    }

    /**
     * Region-thread only. Scans a small cuboid around the villager for the
     * first recognised job-site block. Only reads blocks in loaded chunks and
     * swallows any thread-ownership error, so it can never force a load or
     * cross a region boundary unsafely.
     */
    private Villager.Profession scanForJobSite(Villager villager) {
        Location base = villager.getLocation();
        World world = villager.getWorld();
        int r = settings.employmentSearchRadius();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = bx + dx;
                int z = bz + dz;
                // Guard: never touch an unloaded chunk (would trigger a load).
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    int y = by + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    try {
                        Block block = world.getBlockAt(x, y, z);
                        Villager.Profession prof = JOB_SITES.get(block.getType());
                        if (prof != null) {
                            return prof;
                        }
                    } catch (RuntimeException ignored) {
                        // Cross-region / thread-check: skip this block safely.
                    }
                }
            }
        }
        return null;
    }

    /**
     * Populates the villager's trade offers for its current profession/level by
     * invoking vanilla {@code Villager.updateTrades()} reflectively. This is the
     * same routine the game calls on level-up, but here we call it directly so
     * no brain tick (and therefore no POI lookup) is needed.
     */
    private void generateTrades(Villager villager) {
        if (updateTradesUnavailable) {
            return;
        }
        try {
            Object handle = villager.getClass().getMethod("getHandle").invoke(villager);
            Method method = resolveUpdateTrades(handle.getClass());
            if (method == null) {
                updateTradesUnavailable = true;
                plugin.getLogger().warning("Could not find Villager.updateTrades(); managed employment will "
                        + "assign professions but not auto-generate trades (server mapping mismatch?).");
                return;
            }
            method.invoke(handle);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            updateTradesUnavailable = true;
            plugin.getLogger().warning("Failed to generate villager trades reflectively: " + ex);
        }
    }

    private Method resolveUpdateTrades(Class<?> handleClass) {
        Method cached = updateTradesMethod;
        if (cached != null) {
            return cached;
        }
        for (Class<?> cls = handleClass; cls != null; cls = cls.getSuperclass()) {
            try {
                Method m = cls.getDeclaredMethod("updateTrades");
                m.setAccessible(true);
                updateTradesMethod = m;
                return m;
            } catch (NoSuchMethodException ignored) {
                // keep walking up the hierarchy
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ restock

    /**
     * Region-thread only. Resets every trade's use counter to 0 so out-of-stock
     * trades become available again. Pure Bukkit API — no reflection, no POI.
     */
    private void restock(Villager villager) {
        List<MerchantRecipe> recipes = villager.getRecipes();
        if (recipes.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (MerchantRecipe recipe : recipes) {
            if (recipe.getUses() > 0) {
                recipe.setUses(0);
                changed = true;
            }
        }
        if (changed) {
            // setRecipes writes the mutated list back onto the merchant.
            villager.setRecipes(recipes);
        }
    }
}
