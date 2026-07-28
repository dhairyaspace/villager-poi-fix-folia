package com.example.villagerpoihalt;

import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Immutable, parsed view of config.yml.
 *
 * <p>Supported {@code disabled-regions} entry formats:</p>
 * <ul>
 *   <li>{@code radius:<world>:<x>:<z>:<radius>} — 2D circle around x,z (y ignored)</li>
 *   <li>{@code wg:<world>:<regionName>} — WorldGuard region (requires WorldGuard)</li>
 * </ul>
 */
public record Settings(boolean disableAiGlobally, List<Area> areas,
                       HaltMethod haltMethod,
                       boolean stripSpawnersEnabled, Set<String> stripTargets,
                       boolean employmentEnabled, int employmentSearchRadius, long employmentIntervalTicks,
                       boolean restockEnabled, long restockIntervalTicks,
                       int restockTimesPerCycle, int restockCycleMinutes) {

    /**
     * How a villager's brain is stopped.
     *
     * <p>Both prevent {@code Brain.tick()} — and therefore the
     * AcquirePoi/YieldJobSite/PoiCompetitorScan POI lookups (Folia#292) —
     * but differ in what physics remain:</p>
     * <ul>
     *   <li>{@link #AWARE} — {@code Mob.setAware(false)}: skips the AI step
     *       (brain + goal selectors) but the entity still has gravity, falls,
     *       gets pushed by entities/pistons/water and takes hit knockback.
     *       Looks natural; recommended.</li>
     *   <li>{@link #NO_AI} — {@code setAI(false)}: fully frozen statue.
     *       No gravity, no knockback, cannot be pushed.</li>
     * </ul>
     */
    public enum HaltMethod { AWARE, NO_AI }

    /** A scoped area in which villager AI should be halted. */
    public sealed interface Area permits RadiusArea, WorldGuardArea {
        boolean contains(Location loc);
        String describe();
    }

    /** World + 2D radius area ("radius:world:x:z:r"). */
    public record RadiusArea(String world, double x, double z, double radius) implements Area {
        @Override
        public boolean contains(Location loc) {
            if (loc.getWorld() == null || !loc.getWorld().getName().equalsIgnoreCase(world)) {
                return false;
            }
            double dx = loc.getX() - x;
            double dz = loc.getZ() - z;
            return (dx * dx + dz * dz) <= radius * radius;
        }

        @Override
        public String describe() {
            return "radius:" + world + ":" + x + ":" + z + ":" + radius;
        }
    }

    /** WorldGuard region area ("wg:world:regionName"). */
    public record WorldGuardArea(String world, String regionName) implements Area {
        @Override
        public boolean contains(Location loc) {
            if (loc.getWorld() == null || !loc.getWorld().getName().equalsIgnoreCase(world)) {
                return false;
            }
            // Only touch the WorldGuard hook class when the plugin is present,
            // otherwise its classes would fail to link.
            return WorldGuardHook.isAvailable() && WorldGuardHook.contains(loc, regionName);
        }

        @Override
        public String describe() {
            return "wg:" + world + ":" + regionName;
        }
    }

    /** True if the location falls under the halt policy. */
    public boolean applies(Location loc) {
        if (disableAiGlobally) {
            return true;
        }
        for (Area area : areas) {
            if (area.contains(loc)) {
                return true;
            }
        }
        return false;
    }

    public boolean usesWorldGuard() {
        return areas.stream().anyMatch(a -> a instanceof WorldGuardArea);
    }

    public static Settings load(JavaPlugin plugin) {
        Logger log = plugin.getLogger();
        boolean global = plugin.getConfig().getBoolean("disable-ai-globally", true);

        // --- Halt method (v1.3.0): "aware" keeps physics/knockback ----------
        String methodRaw = plugin.getConfig().getString("halt-method", "aware");
        HaltMethod haltMethod;
        switch (methodRaw.trim().toLowerCase(Locale.ROOT)) {
            case "no-ai", "noai", "no_ai" -> haltMethod = HaltMethod.NO_AI;
            case "aware" -> haltMethod = HaltMethod.AWARE;
            default -> {
                log.warning("Unknown halt-method '" + methodRaw + "', falling back to 'aware'.");
                haltMethod = HaltMethod.AWARE;
            }
        }

        List<Area> areas = new ArrayList<>();

        for (String raw : plugin.getConfig().getStringList("disabled-regions")) {
            String[] parts = raw.trim().split(":");
            try {
                switch (parts[0].toLowerCase(Locale.ROOT)) {
                    case "radius" -> {
                        if (parts.length != 5) {
                            throw new IllegalArgumentException("expected radius:<world>:<x>:<z>:<radius>");
                        }
                        areas.add(new RadiusArea(parts[1],
                                Double.parseDouble(parts[2]),
                                Double.parseDouble(parts[3]),
                                Double.parseDouble(parts[4])));
                    }
                    case "wg" -> {
                        if (parts.length != 3) {
                            throw new IllegalArgumentException("expected wg:<world>:<regionName>");
                        }
                        areas.add(new WorldGuardArea(parts[1], parts[2]));
                    }
                    default -> throw new IllegalArgumentException("unknown area type '" + parts[0] + "'");
                }
            } catch (RuntimeException ex) {
                log.warning("Ignoring invalid disabled-regions entry \"" + raw + "\": " + ex.getMessage());
            }
        }

        if (!global && areas.isEmpty()) {
            log.warning("disable-ai-globally is false and disabled-regions is empty — "
                    + "no villagers will be halted automatically. Use /vpoihalt toggle for manual control.");
        }

        // --- POI-scanning world spawners (CatSpawner stall etc., see SpawnerStripper) ---
        boolean stripEnabled = plugin.getConfig().getBoolean("strip-poi-spawners.enabled", true);
        Set<String> targets = new HashSet<>();
        if (plugin.getConfig().getBoolean("strip-poi-spawners.cat-spawner", true)) {
            targets.add(SpawnerStripper.CAT_SPAWNER);
        }
        if (plugin.getConfig().getBoolean("strip-poi-spawners.village-siege", true)) {
            targets.add(SpawnerStripper.VILLAGE_SIEGE);
        }
        if (plugin.getConfig().getBoolean("strip-poi-spawners.wandering-trader", true)) {
            targets.add(SpawnerStripper.WANDERING_TRADER);
        }

        // --- Managed employment (v1.2.0): re-give jobs without POI lookups ---
        boolean employmentEnabled = plugin.getConfig().getBoolean("managed-employment.enabled", true);
        int searchRadius = Math.max(1, Math.min(8,
                plugin.getConfig().getInt("managed-employment.search-radius", 4)));
        long employmentInterval = Math.max(20L,
                plugin.getConfig().getLong("managed-employment.check-interval-seconds", 10) * 20L);

        // --- Managed restock (v1.2.0): "N times per cycle-minutes" ----------
        boolean restockEnabled = plugin.getConfig().getBoolean("managed-restock.enabled", true);
        int timesPerCycle = Math.max(1, plugin.getConfig().getInt("managed-restock.times-per-cycle", 4));
        int cycleMinutes = Math.max(1, plugin.getConfig().getInt("managed-restock.cycle-minutes", 20));
        // e.g. 4 times / 20 min => every 5 min => 6000 ticks.
        long restockInterval = Math.max(20L, (long) cycleMinutes * 60L * 20L / timesPerCycle);

        return new Settings(global, List.copyOf(areas), haltMethod, stripEnabled, Set.copyOf(targets),
                employmentEnabled, searchRadius, employmentInterval,
                restockEnabled, restockInterval, timesPerCycle, cycleMinutes);
    }

    /** Spawner class names that should currently be stripped (empty when disabled). */
    public Set<String> effectiveStripTargets() {
        return stripSpawnersEnabled ? stripTargets : Set.of();
    }
}
