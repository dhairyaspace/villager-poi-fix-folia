package com.example.villagerpoihalt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Villager;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * /vpoihalt status | toggle | restore | reload
 *
 * <p><b>Folia threading:</b> commands execute on the global region thread on
 * Folia, which does NOT own any entities. Therefore this class never mutates
 * a villager directly. Instead it:</p>
 * <ol>
 *   <li>takes a thread-safe snapshot of the world's villagers
 *       ({@code World#getEntitiesByClass} is backed by Folia's concurrent
 *       entity lookup and is safe to call off-region for read-only snapshots),</li>
 *   <li>hops onto each villager's own {@code EntityScheduler}
 *       ({@code villager.getScheduler().run(...)}), which Folia guarantees to
 *       execute on the region thread that currently owns that entity,</li>
 *   <li>re-checks validity and radius <i>inside</i> the region-thread task
 *       (the villager may have moved or despawned between snapshot and run),</li>
 *   <li>aggregates results with atomics and reports back when every scheduled
 *       task has either run or been retired (entity removed).</li>
 * </ol>
 * Using the legacy {@code Bukkit.getScheduler()} for any of this would throw
 * Folia's "thread failed main thread check" / TickThread errors.
 */
public final class VpoiHaltCommand implements TabExecutor {

    private final VillagerPoiHaltPlugin plugin;
    private final HaltManager haltManager;

    public VpoiHaltCommand(VillagerPoiHaltPlugin plugin, HaltManager haltManager) {
        this.plugin = plugin;
        this.haltManager = haltManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "toggle" -> radiusAction(sender, args, Mode.TOGGLE);
            case "restore" -> radiusAction(sender, args, Mode.RESTORE);
            case "reload" -> {
                plugin.reloadSettings();
                sender.sendMessage(Component.text("[VillagerPoiHalt] Config reloaded. Mode: "
                                + (plugin.settings().disableAiGlobally()
                                        ? "GLOBAL"
                                        : "scoped (" + plugin.settings().areas().size() + " area(s))"),
                        NamedTextColor.GREEN));
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        Settings settings = plugin.settings();
        sender.sendMessage(Component.text("--- VillagerPoiHalt status ---", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Loaded villagers with AI disabled by this plugin: ", NamedTextColor.GRAY)
                .append(Component.text(haltManager.haltedLoadedCount(), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Mode: ", NamedTextColor.GRAY)
                .append(Component.text(settings.disableAiGlobally() ? "disable-ai-globally" : "scoped areas",
                        NamedTextColor.AQUA)));
        if (!settings.disableAiGlobally()) {
            if (settings.areas().isEmpty()) {
                sender.sendMessage(Component.text("  (no areas configured — manual /vpoihalt toggle only)",
                        NamedTextColor.DARK_GRAY));
            }
            for (Settings.Area area : settings.areas()) {
                sender.sendMessage(Component.text("  - " + area.describe(), NamedTextColor.DARK_AQUA));
            }
        }
        sender.sendMessage(Component.text("(Unloaded villagers keep their halt flag in persistent data.)",
                NamedTextColor.DARK_GRAY));
    }

    private enum Mode { TOGGLE, RESTORE }

    /** Shared implementation of "toggle" and "restore" over a world/x/z/radius. */
    private void radiusAction(CommandSender sender, String[] args, Mode mode) {
        if (args.length != 5) {
            sender.sendMessage(Component.text("Usage: /vpoihalt " + args[0].toLowerCase(Locale.ROOT)
                    + " <world> <x> <z> <radius>", NamedTextColor.RED));
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(Component.text("Unknown world: " + args[1], NamedTextColor.RED));
            return;
        }
        final double x;
        final double z;
        final double radius;
        try {
            x = Double.parseDouble(args[2]);
            z = Double.parseDouble(args[3]);
            radius = Double.parseDouble(args[4]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("x, z and radius must be numbers.", NamedTextColor.RED));
            return;
        }
        if (radius <= 0 || radius > 10_000) {
            sender.sendMessage(Component.text("Radius must be between 0 and 10000.", NamedTextColor.RED));
            return;
        }

        // Step 1: read-only snapshot (safe on Folia from the global thread).
        Collection<Villager> snapshot = world.getEntitiesByClass(Villager.class);
        if (snapshot.isEmpty()) {
            sender.sendMessage(Component.text("No loaded villagers in world " + world.getName() + ".",
                    NamedTextColor.YELLOW));
            return;
        }

        final double radiusSq = radius * radius;
        AtomicInteger halted = new AtomicInteger();
        AtomicInteger restored = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger pending = new AtomicInteger(snapshot.size());

        Runnable onOneDone = () -> {
            if (pending.decrementAndGet() == 0) {
                // All entity tasks finished (possibly on different region
                // threads). Adventure message sending is thread-safe.
                sender.sendMessage(Component.text("[VillagerPoiHalt] " + mode.name().toLowerCase(Locale.ROOT)
                                + " in " + world.getName() + " @ " + x + "," + z + " r=" + radius + ": ",
                                NamedTextColor.GOLD)
                        .append(Component.text(halted.get() + " halted, ", NamedTextColor.RED))
                        .append(Component.text(restored.get() + " restored, ", NamedTextColor.GREEN))
                        .append(Component.text(skipped.get() + " out of range/unchanged.", NamedTextColor.GRAY)));
            }
        };

        for (Villager villager : snapshot) {
            // Step 2: hop to the villager's owning region thread.
            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> work = task -> {
                try {
                    // Step 3: re-check on the correct thread — the snapshot is stale by now.
                    if (!villager.isValid() || villager.getWorld() != world) {
                        skipped.incrementAndGet();
                        return;
                    }
                    double dx = villager.getLocation().getX() - x;
                    double dz = villager.getLocation().getZ() - z;
                    if (dx * dx + dz * dz > radiusSq) {
                        skipped.incrementAndGet();
                        return;
                    }
                    switch (mode) {
                        case TOGGLE -> {
                            if (haltManager.isHalted(villager)) {
                                haltManager.restore(villager);
                                restored.incrementAndGet();
                            } else {
                                haltManager.halt(villager);
                                halted.incrementAndGet();
                            }
                        }
                        case RESTORE -> {
                            if (haltManager.restore(villager)) {
                                restored.incrementAndGet();
                            } else {
                                skipped.incrementAndGet(); // wasn't halted by us
                            }
                        }
                    }
                } finally {
                    onOneDone.run();
                }
            };
            // 'retired' runs if the entity is removed before the task executes;
            // we must still count it so the summary message fires.
            Runnable retired = () -> {
                skipped.incrementAndGet();
                onOneDone.run();
            };
            villager.getScheduler().run(plugin, work, retired);
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("VillagerPoiHalt — Folia POI-load workaround (Folia#292)",
                NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/vpoihalt status", NamedTextColor.AQUA)
                .append(Component.text(" — count of villagers halted by this plugin", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/vpoihalt toggle <world> <x> <z> <radius>", NamedTextColor.AQUA)
                .append(Component.text(" — halt/unhalt villagers in radius", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/vpoihalt restore <world> <x> <z> <radius>", NamedTextColor.AQUA)
                .append(Component.text(" — re-enable AI for villagers we halted", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/vpoihalt reload", NamedTextColor.AQUA)
                .append(Component.text(" — reload config.yml", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "toggle", "restore", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("restore"))) {
            return Bukkit.getWorlds().stream()
                    .map(World::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
