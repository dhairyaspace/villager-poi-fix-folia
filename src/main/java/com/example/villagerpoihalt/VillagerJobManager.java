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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li><b>One workstation per villager:</b> each workstation block can only
 *       be claimed by ONE villager. Already-claimed blocks are skipped during
 *       the employment scan, preventing multiple villagers from taking the
 *       same workstation.</li>
 *   <li><b>Re-employment on workstation break:</b> if a villager's workstation
 *       block is broken and its trades are not locked (level 1, never traded),
 *       the profession is reset so the next cycle can assign a new workstation.
 *       Villagers with locked trades keep their profession (matching vanilla).</li>
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

    /**
     * Tracks which workstation block location is claimed by which villager UUID.
     * Ensures only ONE villager per workstation block. Entries are cleaned up
     * when the owning villager is removed or the workstation block is broken.
     */
    private final ConcurrentHashMap<String, UUID> workstationClaims = new ConcurrentHashMap<>();

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
                    .runAtFixedRate(plugin, t -> {
                        cleanupStaleClaims();
                        forEachManagedVillager(this::tryEmploy);
                    }, period, period);
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

    /**
     * Removes workstation claims for villagers that no longer exist in any
     * loaded world. Called at the start of each employment cycle (global
     * region thread — safe for ConcurrentHashMap reads).
     */
    private void cleanupStaleClaims() {
        // Collect all loaded villager UUIDs.
        java.util.Set<UUID> loaded = new java.util.HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            for (Villager v : world.getEntitiesByClass(Villager.class)) {
                loaded.add(v.getUniqueId());
            }
        }
        // Remove claims for villagers no longer loaded.
        workstationClaims.values().removeIf(uuid -> !loaded.contains(uuid));
    }

    /**
     * Region-thread only. Handles two cases:
     * <ol>
     *   <li><b>Unemployed villagers</b> (profession NONE): scan for an unclaimed
     *       workstation block and assign the matching profession + trades.</li>
     *   <li><b>Employed villagers whose workstation was broken</b>: if trades are
     *       not locked (level 1, never traded), clear the profession and seek a
     *       new workstation.</li>
     * </ol>
     * One workstation block = one villager. Already-claimed blocks are skipped.
     */
    private void tryEmploy(Villager villager) {
        if (!villager.isAdult()) {
            return;
        }
        if (villager.getProfession() == Villager.Profession.NITWIT) {
            return;
        }

        Villager.Profession current = villager.getProfession();

        if (current == Villager.Profession.NONE) {
            // Unemployed — try to find an unclaimed workstation.
            tryAssignNewJob(villager);
        } else {
            // Employed — check if the workstation block still exists.
            verifyWorkstation(villager, current);
        }
    }

    /**
     * Unemployed villager: scan for an unclaimed workstation block and assign
     * the matching profession. Only reads loaded chunks, never forces a load.
     */
    private void tryAssignNewJob(Villager villager) {
        Location jobSite = findUnclaimedJobSite(villager);
        if (jobSite == null) {
            return;
        }

        Villager.Profession prof = JOB_SITES.get(jobSite.getBlock().getType());
        if (prof == null) {
            return;
        }

        // Claim the workstation for this villager.
        claimWorkstation(jobSite, villager);

        villager.setProfession(prof);
        if (villager.getVillagerLevel() < 1) {
            villager.setVillagerLevel(1);
        }
        generateTrades(villager);
        plugin.getLogger().fine("Employed a villager as " + prof + " at " + villager.getLocation());
    }

    /**
     * Employed villager: verify the workstation block still exists. If it was
     * broken and trades are not locked, reset profession so the next cycle
     * can assign a new workstation.
     */
    private void verifyWorkstation(Villager villager, Villager.Profession profession) {
        String claimKey = getClaimKey(villager);
        if (claimKey == null) {
            // No tracked workstation — let it keep its job (legacy or external).
            return;
        }

        // Check if the workstation block is still a valid job-site of the right type.
        if (isWorkstationStillValid(claimKey, profession)) {
            return; // workstation intact, all good
        }

        // Workstation is gone. Check if trades are locked.
        if (hasLockedTrades(villager)) {
            // Trades are locked — villager keeps the profession even without
            // a workstation (matches vanilla behavior after first trade).
            plugin.getLogger().fine("Villager's workstation broken but trades locked, keeping profession: "
                    + villager.getLocation());
            return;
        }

        // Trades not locked — free the claim, reset profession so next cycle
        // can assign a new workstation.
        workstationClaims.remove(claimKey);
        villager.setProfession(Villager.Profession.NONE);
        plugin.getLogger().fine("Villager's workstation broken, resetting profession for re-employment: "
                + villager.getLocation());
    }

    /**
     * Returns the claim key (world:x:y:z) for the workstation this villager
     * is tracked against, or null if no claim exists.
     */
    private String getClaimKey(Villager villager) {
        String uuid = villager.getUniqueId().toString();
        for (Map.Entry<String, UUID> entry : workstationClaims.entrySet()) {
            if (entry.getValue().equals(villager.getUniqueId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Checks if the workstation block at the claim key still exists and is the
     * expected profession block type. Returns false if the block was broken or
     * changed.
     */
    private boolean isWorkstationStillValid(String claimKey, Villager.Profession expectedProf) {
        String[] parts = claimKey.split(":");
        if (parts.length != 4) {
            return false;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return false;
        }
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return true; // chunk unloaded — assume still valid
        }
        try {
            Block block = world.getBlockAt(x, y, z);
            Villager.Profession actual = JOB_SITES.get(block.getType());
            return actual == expectedProf;
        } catch (RuntimeException e) {
            return true; // cross-region — assume valid
        }
    }

    /**
     * Checks if the villager has locked trades (has traded at least once).
     * Uses reflection to call NMS Villager.hasLockedTrades().
     */
    private boolean hasLockedTrades(Villager villager) {
        try {
            Object handle = villager.getClass().getMethod("getHandle").invoke(villager);
            Method method = resolveHasLockedTrades(handle.getClass());
            if (method != null) {
                return (boolean) method.invoke(handle);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        // Fallback: check if villager level > 1 or has non-empty recipes with uses
        if (villager.getVillagerLevel() > 1) {
            return true;
        }
        for (MerchantRecipe recipe : villager.getRecipes()) {
            if (recipe.getUses() > 0) {
                return true;
            }
        }
        return false;
    }

    /** Caches reflection handle for Villager.hasLockedTrades(). */
    private volatile Method hasLockedTradesMethod;

    private Method resolveHasLockedTrades(Class<?> handleClass) {
        Method cached = hasLockedTradesMethod;
        if (cached != null) {
            return cached;
        }
        for (Class<?> cls = handleClass; cls != null; cls = cls.getSuperclass()) {
            try {
                Method m = cls.getDeclaredMethod("hasLockedTrades");
                m.setAccessible(true);
                hasLockedTradesMethod = m;
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /**
     * Registers a workstation claim: the block location (world:x:y:z) maps to
     * the villager's UUID. Only one villager can claim each workstation.
     */
    private void claimWorkstation(Location loc, Villager villager) {
        workstationClaims.put(locationKey(loc), villager.getUniqueId());
    }

    /** Removes all claims for a given villager UUID. */
    private void releaseClaim(UUID villagerUuid) {
        workstationClaims.values().removeIf(uuid -> uuid.equals(villagerUuid));
    }

    /** Unique string key for a block location. */
    private static String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    /**
     * Scans a small cuboid around the villager for the first unclaimed
     * recognised job-site block. Returns the Location if found, null otherwise.
     * Only reads blocks in loaded chunks — never forces a load.
     */
    private Location findUnclaimedJobSite(Villager villager) {
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
                        if (JOB_SITES.containsKey(block.getType())) {
                            String key = world.getName() + ":" + x + ":" + y + ":" + z;
                            if (!workstationClaims.containsKey(key)) {
                                return block.getLocation();
                            }
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
