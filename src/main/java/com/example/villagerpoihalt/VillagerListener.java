package com.example.villagerpoihalt;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Applies the halt policy as villagers enter the world.
 *
 * <p><b>Folia threading:</b> Folia fires entity events on the region thread
 * that owns the entity, so it is safe (and correct) to call
 * {@code villager.setAI(false)} directly inside these handlers — no scheduler
 * hop is needed here. We still route through the entity scheduler for the
 * spawn event's post-spawn apply, because at MONITOR time the entity may not
 * be fully added yet; using {@code entity.getScheduler()} guarantees the task
 * runs on the owning region thread on the next tick (or the 'retired'
 * callback fires if the spawn ends up cancelled/removed).</p>
 */
public final class VillagerListener implements Listener {

    private final Plugin plugin;
    private final HaltManager haltManager;

    public VillagerListener(Plugin plugin, HaltManager haltManager) {
        this.plugin = plugin;
        this.haltManager = haltManager;
    }

    /**
     * Fresh spawns: breeding, eggs, curing, commands, natural, etc.
     * (CreatureSpawnEvent is the concrete villager-relevant subtype of
     * EntitySpawnEvent — requirement 3.)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        // FOLIA: entity scheduler => runs on the villager's owning region
        // thread once it is actually in the world; never runs if the entity
        // got removed first ('retired' callback = null, nothing to clean up).
        villager.getScheduler().run(plugin, task -> haltManager.applyPolicy(villager), null);
    }

    /**
     * Existing villagers coming back as their chunks load. This is what makes
     * the plugin cover villagers that were NOT loaded during the onEnable
     * sweep. Fires on the owning region thread — direct mutation is safe.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Villager villager) {
            haltManager.applyPolicy(villager);
        }
    }

    /** Keep the "currently halted & loaded" counter accurate for /vpoihalt status. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Villager villager) {
            haltManager.onUnload(villager.getUniqueId());
        }
    }
}
