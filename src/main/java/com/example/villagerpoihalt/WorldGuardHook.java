package com.example.villagerpoihalt;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/**
 * Optional WorldGuard integration for "wg:<world>:<region>" scope entries.
 *
 * <p>All WorldGuard classes are referenced ONLY inside {@link #contains},
 * and callers must gate on {@link #isAvailable()} first; this keeps the
 * plugin loadable when WorldGuard is not installed (soft dependency).</p>
 *
 * <p>Threading: WorldGuard's region index is safe for concurrent point
 * queries; we only ever call this with a location we obtained on the
 * relevant region thread.</p>
 */
final class WorldGuardHook {

    private WorldGuardHook() {
    }

    static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    /** True if the location is inside the named WorldGuard region of its world. */
    static boolean contains(Location loc, String regionName) {
        RegionManager manager = WorldGuard.getInstance().getPlatform()
                .getRegionContainer()
                .get(BukkitAdapter.adapt(loc.getWorld()));
        if (manager == null) {
            return false;
        }
        ProtectedRegion region = manager.getRegion(regionName);
        return region != null && region.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
