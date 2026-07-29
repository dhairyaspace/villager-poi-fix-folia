package com.example.villagerpoihalt;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-enables villager breeding for AI-disabled villagers — <b>without</b>
 * ever touching {@code PoiManager} (Folia#292 workaround).
 *
 * <p>When the villager brain is halted, the vanilla {@code BreedActivity}
 * behavior never runs, so villagers never breed even if they are willing
 * and nearby beds exist. This manager reimplements the core checks:</p>
 * <ol>
 *   <li><b>Willingness:</b> check if the villager has food in inventory
 *       (bread, carrot, potato, beetroot) — same criteria vanilla uses.</li>
 *   <li><b>Bed availability:</b> scan nearby loaded blocks for bed-type
 *       blocks ({@code Material.BED} family). Only loaded chunks are
 *       read — no POI/chunk load is ever forced.</li>
 *   <li><b>Partner:</b> find another willing adult villager within 8 blocks
 *       (vanilla breeding range).</li>
 *   <li><b>Spawn baby:</b> spawn a baby villager at the midpoint via the
 *       Bukkit API, consume one food item from each parent.</li>
 * </ol>
 *
 * <h2>Folia threading</h2>
 * <p>A single repeating task runs on the {@code GlobalRegionScheduler}. Each
 * tick it snapshots the villager list per world (concurrent, safe) and hops
 * onto each entity's {@code EntityScheduler} for region-thread-safe mutation.</p>
 */
public final class VillagerBreedingManager {

    private static final double BREED_RANGE = 8.0;
    private static final int BABY_AGE = -24000;

    /** Food items villagers use for breeding (1 item consumed per parent). */
    private static final Material[] FOOD_ITEMS = {
            Material.BREAD,
            Material.CARROT,
            Material.POTATO,
            Material.BEETROOT
    };

    private final VillagerPoiHaltPlugin plugin;
    private final HaltManager haltManager;

    /** Tracks last-bred timestamps to enforce vanilla 5-minute cooldown. */
    private final ConcurrentHashMap<UUID, Long> lastBred = new ConcurrentHashMap<>();

    /** Cached reflection: Villager.isWillingToBreed() (optional, may not exist). */
    private volatile Method isWillingMethod;
    private volatile boolean isWillingChecked;

    /** Cached reflection: NMS Villager.spawnChildFromBreeding(ServerLevel, Villager). */
    private volatile Method spawnChildMethod;

    /** Cached reflection: NMS Villager.setWantToSpawnBaby(boolean). */
    private volatile Method setWantToSpawnBabyMethod;

    private ScheduledTask breedingTask;
    private volatile Settings settings;

    public VillagerBreedingManager(VillagerPoiHaltPlugin plugin, HaltManager haltManager, Settings settings) {
        this.plugin = plugin;
        this.haltManager = haltManager;
        this.settings = settings;
    }

    /** (Re)starts the scheduled breeding task according to current settings. */
    public void start(Settings settings) {
        this.settings = settings;
        stop();

        if (settings.breedingEnabled()) {
            long period = Math.max(20L, settings.breedingIntervalTicks());
            breedingTask = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, t -> forEachManagedVillager(this::tryBreed), period, period);
        }
    }

    /** Cancels the repeating breeding task (reload / disable). */
    public void stop() {
        if (breedingTask != null) {
            breedingTask.cancel();
            breedingTask = null;
        }
    }

    /**
     * Snapshots every world's villagers and dispatches {@code action} for each
     * halted villager onto its owning region thread.
     */
    private void forEachManagedVillager(java.util.function.Consumer<Villager> action) {
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                villager.getScheduler().run(plugin, t -> {
                    if (villager.isValid() && haltManager.isHalted(villager)) {
                        action.accept(villager);
                    }
                }, null);
            }
        }
    }

    // ------------------------------------------------------------------ breeding

    /**
     * Region-thread only. Attempts to breed a single halted villager.
     * Checks: adult, not nitwit, willing (has food), cooldown elapsed,
     * nearby beds, nearby partner.
     */
    private void tryBreed(Villager villager) {
        if (!villager.isAdult()) {
            return;
        }
        if (villager.getProfession() == Villager.Profession.NITWIT) {
            return;
        }

        // Enforce 5-minute cooldown (vanilla uses game-time ticks, we use wall-clock).
        UUID uuid = villager.getUniqueId();
        Long lastTime = lastBred.get(uuid);
        long now = System.currentTimeMillis();
        if (lastTime != null && (now - lastTime) < 300_000L) {
            return;
        }

        // Check willingness (has food in inventory).
        if (!isWilling(villager)) {
            return;
        }

        // Check bed availability — only in loaded chunks (no POI load).
        int beds = countNearbyBeds(villager);
        if (beds < settings.breedingMinBeds()) {
            return;
        }

        // Find a willing partner within range.
        Villager partner = findPartner(villager);
        if (partner == null) {
            return;
        }

        // All conditions met — breed.
        spawnBaby(villager, partner);
        consumeFood(villager);
        consumeFood(partner);
        resetWillingness(villager);
        resetWillingness(partner);
        lastBred.put(uuid, now);
        lastBred.put(partner.getUniqueId(), now);
    }

    /**
     * Finds a willing adult villager partner within {@link #BREED_RANGE} blocks.
     * Returns the closest eligible partner, or null if none found.
     */
    private Villager findPartner(Villager villager) {
        Location loc = villager.getLocation();
        double rangeSq = BREED_RANGE * BREED_RANGE;
        Villager best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity entity : loc.getWorld().getNearbyEntities(loc, BREED_RANGE, BREED_RANGE, BREED_RANGE)) {
            if (!(entity instanceof Villager partner)) {
                continue;
            }
            if (partner == villager) {
                continue;
            }
            if (!partner.isValid() || !partner.isAdult()) {
                continue;
            }
            if (partner.getProfession() == Villager.Profession.NITWIT) {
                continue;
            }
            if (!isWilling(partner)) {
                continue;
            }
            double distSq = loc.distanceSquared(partner.getLocation());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = partner;
            }
        }
        return best;
    }

    /**
     * Checks if a villager is willing to breed.
     * Tries the Bukkit API method first; falls back to inventory food check.
     */
    private boolean isWilling(Villager villager) {
        // Try Paper/Vanilla isWillingToBreed() if available (1.20.2+).
        if (!isWillingChecked) {
            try {
                isWillingMethod = villager.getClass().getMethod("isWillingToBreed");
                isWillingChecked = true;
            } catch (NoSuchMethodException e) {
                isWillingChecked = true;
                isWillingMethod = null;
            }
        }
        if (isWillingMethod != null) {
            try {
                return (boolean) isWillingMethod.invoke(villager);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        // Fallback: villager is willing if it has any food item.
        return hasFood(villager);
    }

    /** Checks if the villager's inventory contains any breeding food item. */
    private boolean hasFood(Villager villager) {
        Inventory inv = villager.getInventory();
        for (Material food : FOOD_ITEMS) {
            if (inv.contains(food)) {
                return true;
            }
        }
        return false;
    }

    /** Consumes one food item from the villager's inventory. */
    private void consumeFood(Villager villager) {
        Inventory inv = villager.getInventory();
        for (Material food : FOOD_ITEMS) {
            if (inv.contains(food)) {
                inv.removeItemAnySlot(new ItemStack(food, 1));
                return;
            }
        }
    }

    /**
     * Resets the villager's breeding willingness by setting
     * {@code wantToSpawnBaby} to {@code false} via NMS reflection.
     * This matches vanilla behavior after breeding.
     */
    private void resetWillingness(Villager villager) {
        try {
            Object handle = villager.getClass().getMethod("getHandle").invoke(villager);
            Method setter = resolveSetWantToSpawnBaby(handle.getClass());
            if (setter != null) {
                setter.invoke(handle, false);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /**
     * Counts nearby bed blocks (any color) in loaded chunks.
     * Only reads blocks in already-loaded chunks — never forces a chunk/POI load.
     */
    private int countNearbyBeds(Villager villager) {
        Location base = villager.getLocation();
        World world = villager.getWorld();
        int r = settings.breedingSearchRadius();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        int count = 0;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = bx + dx;
                int z = bz + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -2; dy <= 3; dy++) {
                    int y = by + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    try {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType().name().endsWith("_BED")) {
                            count++;
                        }
                    } catch (RuntimeException ignored) {
                        // Cross-region / thread-check error — skip safely.
                    }
                }
            }
        }
        return count;
    }

    /**
     * Spawns a baby villager at the midpoint of the two parents.
     * Uses the Bukkit {@code spawn()} API — no POI involvement.
     */
    private void spawnBaby(Villager parent1, Villager parent2) {
        Location midpoint = parent1.getLocation().add(parent2.getLocation()).multiply(0.5);
        midpoint.setWorld(parent1.getWorld());
        midpoint.getWorld().spawn(midpoint, Villager.class, baby -> {
            baby.setBaby();
            baby.setAge(BABY_AGE);
        });
    }

    // ------------------------------------------------------------------ reflection

    /**
     * Resolves {@code NMS Villager.spawnChildFromBreeding(ServerLevel, Villager)}
     * via reflection, walking the class hierarchy. Caches the result.
     */
    private Method resolveSpawnChild(Class<?> handleClass) {
        Method cached = spawnChildMethod;
        if (cached != null) {
            return cached;
        }
        for (Class<?> cls = handleClass; cls != null; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("spawnChildFromBreeding") && m.getParameterCount() == 2) {
                    m.setAccessible(true);
                    spawnChildMethod = m;
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * Resolves {@code NMS Villager.setWantToSpawnBaby(boolean)} via reflection.
     * Caches the result.
     */
    private Method resolveSetWantToSpawnBaby(Class<?> handleClass) {
        Method cached = setWantToSpawnBabyMethod;
        if (cached != null) {
            return cached;
        }
        for (Class<?> cls = handleClass; cls != null; cls = cls.getSuperclass()) {
            try {
                Method m = cls.getDeclaredMethod("setWantToSpawnBaby", boolean.class);
                m.setAccessible(true);
                setWantToSpawnBabyMethod = m;
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
