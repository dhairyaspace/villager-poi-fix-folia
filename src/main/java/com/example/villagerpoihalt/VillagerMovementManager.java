package com.example.villagerpoihalt;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Gives halted villagers limited, POI-free movement. It never enables AI and
 * never accesses the POI API: targets are selected by direct reads of loaded
 * bed/workstation blocks, then the villager is moved one small step at a time.
 */
public final class VillagerMovementManager {
    private final VillagerPoiHaltPlugin plugin;
    private final HaltManager haltManager;
    private volatile Settings settings;
    private ScheduledTask movementTask;

    public VillagerMovementManager(VillagerPoiHaltPlugin plugin, HaltManager haltManager, Settings settings) {
        this.plugin = plugin;
        this.haltManager = haltManager;
        this.settings = settings;
    }

    public void start(Settings settings) {
        this.settings = settings;
        stop();
        if (!settings.movementEnabled()) return;
        long period = Math.max(20L, settings.movementIntervalTicks());
        movementTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> forEachVillager(this::moveOneStep), period, period);
    }

    public void stop() {
        if (movementTask != null) {
            movementTask.cancel();
            movementTask = null;
        }
    }

    private void forEachVillager(java.util.function.Consumer<Villager> action) {
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                villager.getScheduler().run(plugin, task -> {
                    if (villager.isValid() && villager.isAdult() && haltManager.isHalted(villager)) {
                        action.accept(villager);
                    }
                }, null);
            }
        }
    }

    private void moveOneStep(Villager villager) {
        Location target = findTarget(villager);
        if (target == null) return;

        Location current = villager.getLocation();
        double dx = target.getX() - current.getX();
        double dz = target.getZ() - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.7) return;

        // Small steps look less like teleporting and avoid crossing large gaps.
        double step = Math.min(0.8, distance);
        Location next = current.clone().add(dx / distance * step, 0, dz / distance * step);
        Location safe = findSafeLocation(next);
        if (safe == null) return;
        safe.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        villager.teleport(safe);
    }

    private Location findTarget(Villager villager) {
        Location base = villager.getLocation();
        int radius = settings.movementRadius();

        // Sometimes choose a nearby bed or workstation, otherwise wander randomly.
        if (ThreadLocalRandom.current().nextInt(4) == 0) {
            Location special = findSpecialBlock(base, radius);
            if (special != null) return special.add(0.5, 0, 0.5);
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            double x = base.getX() + ThreadLocalRandom.current().nextDouble(-radius, radius + 1);
            double z = base.getZ() + ThreadLocalRandom.current().nextDouble(-radius, radius + 1);
            Location candidate = new Location(base.getWorld(), x, base.getY(), z);
            Location safe = findSafeLocation(candidate);
            if (safe != null) return safe;
        }
        return null;
    }

    private Location findSpecialBlock(Location base, int radius) {
        World world = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        for (int attempt = 0; attempt < 20; attempt++) {
            int x = bx + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int z = bz + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = by - 2; y <= by + 2; y++) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                try {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type.name().endsWith("_BED") || isWorkstation(type)) {
                        return world.getBlockAt(x, y, z).getLocation();
                    }
                } catch (RuntimeException ignored) { }
            }
        }
        return null;
    }

    private Location findSafeLocation(Location candidate) {
        World world = candidate.getWorld();
        int x = candidate.getBlockX(), z = candidate.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int y = candidate.getBlockY() + 2; y >= world.getMinHeight() + 1; y--) {
            try {
                Block floor = world.getBlockAt(x, y - 1, z);
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (floor.getType().isSolid() && feet.isPassable() && head.isPassable()) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            } catch (RuntimeException ignored) { return null; }
        }
        return null;
    }

    private boolean isWorkstation(Material type) {
        return switch (type) {
            case BLAST_FURNACE, SMOKER, CARTOGRAPHY_TABLE, BREWING_STAND,
                    COMPOSTER, BARREL, FLETCHING_TABLE, CAULDRON, LECTERN,
                    STONECUTTER, LOOM, SMITHING_TABLE, GRINDSTONE -> true;
            default -> false;
        };
    }
}
