package com.example.villagerpoihalt;

import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
public record Settings(boolean disableAiGlobally, List<Area> areas) {

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
        return new Settings(global, List.copyOf(areas));
    }
}
