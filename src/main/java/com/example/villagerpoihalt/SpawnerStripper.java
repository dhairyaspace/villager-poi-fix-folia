package com.example.villagerpoihalt;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Removes vanilla "custom spawners" that scan the POI index from each world.
 *
 * <p><b>Why:</b> the villager-AI halt (v1.0.0) stops <i>villager brain</i>
 * POI lookups, but three WORLD-LEVEL spawners ticked from
 * {@code ServerLevel.tickCustomSpawners()} perform their own POI scans and
 * hit the exact same unsafe {@code PoiManager.getOrLoad()} sync-load path
 * (Folia#292), independent of any villager entity:</p>
 * <ul>
 *   <li>{@code CatSpawner} — every 1200 ticks scans a 48-block radius around
 *       a random player for bed (HOME) POIs before spawning village cats.
 *       Observed stalling a region thread for 135s in production.</li>
 *   <li>{@code VillageSiege} — checks "is this a village?" via POI counts
 *       before starting zombie sieges.</li>
 *   <li>{@code WanderingTraderSpawner} — searches for a MEETING (bell) POI
 *       within 48 blocks when picking a trader spawn point.</li>
 * </ul>
 *
 * <p><b>How:</b> there is no Bukkit/Paper API for these spawners and no
 * gamerule for CatSpawner/VillageSiege, so we reflectively replace the
 * {@code ServerLevel.customSpawners} list (Mojang mappings — Paper/Folia run
 * Mojang-mapped since 1.20.5) with a filtered copy. Removed spawner instances
 * are cached per world so a config change + {@code /vpoihalt reload} can
 * re-add them without a restart.</p>
 *
 * <p><b>Threading:</b> the swap is a single reference assignment performed on
 * the global region thread (world load / plugin enable). Region threads read
 * the field once per tick; a briefly-stale read is harmless (at worst one
 * more spawner tick). No locks needed.</p>
 *
 * <p><b>JDK note:</b> {@code customSpawners} is a final instance field.
 * Setting it via reflection works on JDK 17–25; JDK 26+ warns that final
 * field mutation will eventually be blocked — if that happens, add
 * {@code --enable-final-field-mutation=ALL-UNNAMED} to the server JVM flags.</p>
 */
public final class SpawnerStripper {

    /** Mojang-mapped simple class names of the POI-scanning spawners. */
    public static final String CAT_SPAWNER = "CatSpawner";
    public static final String VILLAGE_SIEGE = "VillageSiege";
    public static final String WANDERING_TRADER = "WanderingTraderSpawner";

    private final Plugin plugin;

    /**
     * Per-world cache of spawner instances we removed, keyed by simple class
     * name, so they can be restored if the config re-enables them.
     */
    private final Map<UUID, Map<String, Object>> removed = new ConcurrentHashMap<>();

    public SpawnerStripper(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Ensures the world's custom spawner list contains exactly the vanilla
     * spawners minus {@code toStrip}. Idempotent; safe to call repeatedly
     * (enable, world load, /vpoihalt reload).
     *
     * @param toStrip simple class names to remove (empty set = restore all)
     * @return names currently stripped from this world, or null on failure
     */
    public Set<String> apply(World world, Set<String> toStrip) {
        try {
            // CraftWorld#getHandle() -> net.minecraft.server.level.ServerLevel
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Field field = findCustomSpawnersField(level);
            if (field == null) {
                plugin.getLogger().warning("Could not locate ServerLevel.customSpawners for world '"
                        + world.getName() + "' — POI spawner stripping skipped (server version mismatch?).");
                return null;
            }
            field.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Object> current = (List<Object>) field.get(level);
            Map<String, Object> cache = removed.computeIfAbsent(world.getUID(), k -> new ConcurrentHashMap<>());

            List<Object> next = new ArrayList<>(current.size() + cache.size());
            boolean changed = false;
            for (Object spawner : current) {
                String name = spawner.getClass().getSimpleName();
                if (toStrip.contains(name)) {
                    cache.put(name, spawner); // remember it for potential restore
                    changed = true;
                } else {
                    next.add(spawner);
                }
            }
            // Restore previously-removed spawners that are no longer stripped.
            Iterator<Map.Entry<String, Object>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> entry = it.next();
                if (!toStrip.contains(entry.getKey())) {
                    next.add(entry.getValue());
                    it.remove();
                    changed = true;
                }
            }

            if (changed) {
                // Atomic reference swap; immutable copy matches vanilla's style.
                field.set(level, List.copyOf(next));
                plugin.getLogger().info("World '" + world.getName() + "': POI spawners stripped="
                        + cache.keySet() + ", active spawners=" + next.size());
            }
            return Set.copyOf(cache.keySet());
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Failed to strip POI spawners in world '" + world.getName() + "': " + ex
                    + " — if you are on JDK 26+, add --enable-final-field-mutation=ALL-UNNAMED to the JVM flags.");
            return null;
        }
    }

    /** Names stripped in the given world (for /vpoihalt status). */
    public Set<String> strippedIn(World world) {
        Map<String, Object> cache = removed.get(world.getUID());
        return cache == null ? Set.of() : Set.copyOf(cache.keySet());
    }

    /**
     * Locates {@code ServerLevel.customSpawners}. Tries the Mojang-mapped name
     * first, then falls back to scanning for a List field whose elements are
     * CustomSpawner implementations (defensive against future renames).
     */
    private static Field findCustomSpawnersField(Object level) throws IllegalAccessException {
        for (Class<?> cls = level.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                return cls.getDeclaredField("customSpawners");
            } catch (NoSuchFieldException ignored) {
                // fall through to heuristic scan of this class's fields
            }
            for (Field candidate : cls.getDeclaredFields()) {
                if (Modifier.isStatic(candidate.getModifiers())
                        || !List.class.isAssignableFrom(candidate.getType())) {
                    continue;
                }
                candidate.setAccessible(true);
                if (candidate.get(level) instanceof List<?> list && !list.isEmpty()
                        && list.stream().allMatch(SpawnerStripper::looksLikeCustomSpawner)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** True if the object implements an interface named CustomSpawner. */
    private static boolean looksLikeCustomSpawner(Object obj) {
        for (Class<?> cls = obj.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (Class<?> iface : cls.getInterfaces()) {
                if (iface.getSimpleName().equals("CustomSpawner")) {
                    return true;
                }
            }
        }
        return false;
    }
}
