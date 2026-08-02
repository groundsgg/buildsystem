/*
 * Copyright (c) 2026, Grounds
 * Copyright (c) 2018-2026, Thomas Meaney
 * Copyright (c) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package gg.grounds.buildsystem.command;

import de.eintosti.buildsystem.api.BuildSystemProvider;
import de.eintosti.buildsystem.api.world.BuildWorld;
import gg.grounds.buildsystem.registry.MapSummary;
import gg.grounds.buildsystem.registry.MapVersion;
import gg.grounds.buildsystem.registry.RegistryClient;
import gg.grounds.buildsystem.registry.RegistryException;
import gg.grounds.buildsystem.world.MapLink;
import gg.grounds.buildsystem.world.WorldArchive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Everything a builder does with the registry, in one command that mostly takes no arguments.
 *
 * <p>The design constraint is that builders are not operators: nobody types a digest, a version
 * number or a bucket name. {@code /map push} works out which map the world belongs to from the
 * world itself, and says what happened in one sentence.
 */
@NullMarked
public final class MapCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("push", "fork", "versions", "link", "status");

    private final JavaPlugin plugin;
    private final RegistryClient registry;

    public MapCommand(JavaPlugin plugin, RegistryClient registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this in-game, standing in the world you mean.");
            return true;
        }
        BuildWorld world = BuildSystemProvider.get().getWorldService().getWorldStorage()
                .getBuildWorld(player.getWorld());
        if (world == null) {
            error(player, "This is not a build world.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(player, world);
            case "push" -> push(player, world, join(args, 1));
            case "fork" -> fork(player, world, args.length > 1 ? args[1] : null);
            case "versions" -> versions(player, world);
            case "link" -> link(player, world, args.length > 1 ? args[1] : null);
            default -> error(player, "Unknown: " + sub + ". Try /map push.");
        }
        return true;
    }

    // ------------------------------------------------------------- commands

    private void status(Player player, BuildWorld world) {
        String address = MapLink.addressOf(world);
        if (address == null) {
            info(player, "This world is not linked to a map yet. Use /map link <namespace/name>.");
            return;
        }
        Integer base = MapLink.baseVersionOf(world);
        info(
                player,
                "This world is "
                        + address
                        + (base == null ? " (not built on a published version)" : ", built on v" + base)
                        + ".");
    }

    private void link(Player player, BuildWorld world, @Nullable String address) {
        if (address == null) {
            error(player, "Which map? /map link bedwars/4x4-baumhaus");
            return;
        }
        if (!address.contains("/")) {
            error(player, "A map address is <namespace>/<name>, e.g. bedwars/4x4-baumhaus.");
            return;
        }
        String existing = MapLink.addressOf(world);
        if (existing != null) {
            // Relinking silently would make the next push land on a different map with no
            // trace of why. Deleting the world data is the operator's call, not a side effect.
            error(player, "Already linked to " + existing + ".");
            return;
        }
        offMainThread(
                player,
                () -> {
                    List<MapSummary> maps = registry.listMaps();
                    boolean exists = maps.stream().anyMatch(map -> map.address().equals(address));
                    if (!exists) {
                        registry.createMap(address, world.getName(), "ARENA", false);
                    }
                    onMainThread(
                            () -> {
                                MapLink.link(world, address, null);
                                ok(player, "Linked to " + address + (exists ? "." : " (new map)."));
                            });
                });
    }

    private void push(Player player, BuildWorld world, @Nullable String note) {
        String address = MapLink.addressOf(world);
        if (address == null) {
            error(player, "Link this world to a map first: /map link <namespace/name>.");
            return;
        }
        World bukkitWorld = world.getWorld().orElse(null);
        if (bukkitWorld == null) {
            error(player, "The world is not loaded.");
            return;
        }

        // Save on the main thread, then stop autosaving while we read the region files. A tick
        // that flushes a chunk mid-archive produces a bundle that is a valid tar of a corrupt
        // world, and nothing downstream can tell.
        bukkitWorld.save();
        boolean autoSave = bukkitWorld.isAutoSave();
        bukkitWorld.setAutoSave(false);

        Path folder = bukkitWorld.getWorldFolder().toPath();
        Integer parent = MapLink.baseVersionOf(world);
        info(player, "Packing " + address + "…");

        offMainThread(
                player,
                () -> {
                    Path archive = Files.createTempFile("grounds-map-", ".tar.zst");
                    try {
                        WorldArchive.Archive packed = WorldArchive.pack(folder, archive);
                        onMainThread(() -> bukkitWorld.setAutoSave(autoSave));
                        info(
                                player,
                                "Uploading " + mib(packed.sizeBytes()) + " to the registry…");
                        MapVersion published =
                                registry.push(
                                        address,
                                        packed.file(),
                                        packed.sha256(),
                                        packed.sizeBytes(),
                                        parent,
                                        note);
                        onMainThread(
                                () -> {
                                    MapLink.link(world, address, published.version());
                                    ok(
                                            player,
                                            "Published "
                                                    + address
                                                    + " v"
                                                    + published.version()
                                                    + " ("
                                                    + mib(packed.sizeBytes())
                                                    + "). An admin decides when it goes live.");
                                });
                    } finally {
                        // Whatever happened, autosave goes back on and the temp file goes away.
                        onMainThread(() -> bukkitWorld.setAutoSave(autoSave));
                        Files.deleteIfExists(archive);
                    }
                });
    }

    private void fork(Player player, BuildWorld world, @Nullable String target) {
        String address = MapLink.addressOf(world);
        if (address == null) {
            error(player, "This world is not a map, so there is nothing to fork.");
            return;
        }
        if (target == null || !target.contains("/")) {
            error(player, "Fork into which address? /map fork bedwars/4x4-baumhaus-winter");
            return;
        }
        Integer base = MapLink.baseVersionOf(world);
        offMainThread(
                player,
                () -> {
                    MapSummary forked = registry.fork(address, target, base, null);
                    ok(
                            player,
                            "Forked "
                                    + address
                                    + " into "
                                    + forked.address()
                                    + ". It starts at v1 with the same content and its own history.");
                });
    }

    private void versions(Player player, BuildWorld world) {
        String address = MapLink.addressOf(world);
        if (address == null) {
            error(player, "This world is not linked to a map.");
            return;
        }
        offMainThread(
                player,
                () -> {
                    List<MapVersion> versions = registry.listVersions(address);
                    if (versions.isEmpty()) {
                        info(player, "No versions yet — /map push makes the first one.");
                        return;
                    }
                    info(player, address + ":");
                    versions.stream()
                            .sorted((a, b) -> Integer.compare(b.version(), a.version()))
                            .limit(10)
                            .forEach(
                                    version ->
                                            info(
                                                    player,
                                                    "  v"
                                                            + version.version()
                                                            + " · "
                                                            + version.state().toLowerCase(Locale.ROOT)
                                                            + (version.note() == null
                                                                    ? ""
                                                                    : " · " + version.note())));
                });
    }

    // ------------------------------------------------------------ plumbing

    /** Anything that touches the network or the disk. Never the main thread. */
    private void offMainThread(Player player, Work work) {
        plugin.getServer()
                .getScheduler()
                .runTaskAsynchronously(
                        plugin,
                        () -> {
                            try {
                                work.run();
                            } catch (RegistryException e) {
                                error(player, e.getMessage());
                            } catch (IOException e) {
                                error(player, "Could not read the world: " + e.getMessage());
                            } catch (RuntimeException e) {
                                // The builder gets a sentence; the console gets the trace.
                                error(player, "Something went wrong. An admin can see the details.");
                                plugin.getLogger().severe("map command failed: " + e);
                            }
                        });
    }

    /** World data is not thread-safe, so writing it goes back onto the tick. */
    private void onMainThread(Runnable runnable) {
        plugin.getServer().getScheduler().runTask(plugin, runnable);
    }

    @FunctionalInterface
    private interface Work {
        void run() throws RegistryException, IOException;
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static @Nullable String join(String[] args, int from) {
        if (args.length <= from) {
            return null;
        }
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private static void ok(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void info(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    @Override
    public @Nullable List<String> onTabComplete(
            CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String sub : SUBCOMMANDS) {
            if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                matches.add(sub);
            }
        }
        return matches;
    }
}
