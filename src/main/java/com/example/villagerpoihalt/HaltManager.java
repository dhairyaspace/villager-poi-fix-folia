package com.example.villagerpoihalt;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies / reverts the AI halt and tracks which villagers THIS plugin halted.
 *
 * <p><b>Threading contract (Folia):</b> every method that touches a
 * {@link Villager} ({@link #halt}, {@link #restore}, {@link #applyPolicy},
 * {@link #isHalted}) must be invoked on the region thread that owns that
 * villager — i.e. from an entity event handler or from a task scheduled via
 * {@code villager.getScheduler()}. The tracking set itself is concurrent and
 * safe to read from any thread (used by /vpoihalt status).</p>
 *
 * <p>Halted villagers are marked with a {@link org.bukkit.persistence.PersistentDataContainer}
 * flag so the "halted by this plugin" state survives chunk unloads and server
 * restarts, and so we never "restore" a villager that some other plugin or a
 * map-maker intentionally set to NoAI.</p>
 */
public final class HaltManager {

    /** PDC marker: byte 1 = this plugin disabled the villager's AI. */
    private final NamespacedKey haltedKey;

    private final Plugin plugin;
    private volatile Settings settings;

    /**
     * UUIDs of currently-LOADED villagers halted by this plugin. Maintained by
     * {@link VillagerListener} on entity add/remove. Concurrent because status
     * reads may come from the global region thread while region threads write.
     */
    private final Set<UUID> haltedLoaded = ConcurrentHashMap.newKeySet();

    public HaltManager(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.haltedKey = new NamespacedKey(plugin, "halted");
    }

    public void updateSettings(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    /** True if the configured policy says this villager should be halted. Region thread only. */
    public boolean shouldHalt(Villager villager) {
        return settings.applies(villager.getLocation());
    }

    /**
     * Applies the config policy to a villager: halts it if it is inside a
     * configured scope and not already halted. Never un-halts (manual
     * /vpoihalt restore is the only way back, per requirement 7).
     * Must run on the villager's owning region thread.
     */
    public void applyPolicy(Villager villager) {
        if (!villager.isValid()) {
            return;
        }
        if (isHalted(villager)) {
            // Re-assert the CURRENT halt method. This also migrates villagers
            // halted under a different method (e.g. old NO_AI installs after
            // switching halt-method to "aware": AI is turned back on so
            // physics work again, and setAware(false) keeps the brain off).
            applyHaltState(villager);
            haltedLoaded.add(villager.getUniqueId());
            return;
        }
        if (shouldHalt(villager)) {
            halt(villager);
        }
    }

    /**
     * Stops the villager's brain so the POI-searching behaviors
     * (AcquirePoi / YieldJobSite / PoiCompetitorScan) never run and
     * {@code PoiManager.getOrLoad()} is never reached (Folia#292 workaround).
     * Must run on the villager's owning region thread.
     */
    public void halt(Villager villager) {
        applyHaltState(villager);
        villager.getPersistentDataContainer().set(haltedKey, PersistentDataType.BYTE, (byte) 1);
        haltedLoaded.add(villager.getUniqueId());
    }

    /**
     * Sets the entity flags for the configured halt method.
     *
     * <p>AWARE (default since v1.3.0): {@code setAware(false)} skips the mob's
     * AI step (brain + goals) while leaving physics fully intact — the
     * villager keeps gravity (falls if there's no block below), can be pushed
     * by players/entities/water, and takes knockback when hit. AI stays
     * enabled so the physics tick runs normally.</p>
     *
     * <p>NO_AI: legacy full freeze — no gravity, no knockback, statue-like.</p>
     */
    private void applyHaltState(Villager villager) {
        switch (settings.haltMethod()) {
            case AWARE -> {
                villager.setAI(true);      // physics tick ON (migrates old NO_AI villagers)
                villager.setAware(false);  // brain/goals OFF -> no POI lookups
            }
            case NO_AI -> {
                villager.setAware(false);
                villager.setAI(false);     // full freeze
            }
        }
    }

    /**
     * Cleanly reverts a halt: re-enables AI/awareness and removes our marker.
     * Only acts on villagers WE halted, so villagers that were NoAI/unaware
     * for other reasons are left alone. Must run on the villager's owning
     * region thread.
     *
     * @return true if the villager was halted by us and has been restored
     */
    public boolean restore(Villager villager) {
        if (!isHalted(villager)) {
            return false;
        }
        // Undo BOTH flags regardless of the current halt-method, since the
        // villager may have been halted under a different config.
        villager.setAI(true);
        villager.setAware(true);
        villager.getPersistentDataContainer().remove(haltedKey);
        haltedLoaded.remove(villager.getUniqueId());
        return true;
    }

    /** True if OUR marker is on this villager. Region thread only (reads PDC). */
    public boolean isHalted(Villager villager) {
        Byte flag = villager.getPersistentDataContainer().get(haltedKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    /** Called from entity-remove events; thread-safe. */
    public void onUnload(UUID uuid) {
        haltedLoaded.remove(uuid);
    }

    /** Number of currently-loaded villagers halted by this plugin. Thread-safe. */
    public int haltedLoadedCount() {
        return haltedLoaded.size();
    }
}
