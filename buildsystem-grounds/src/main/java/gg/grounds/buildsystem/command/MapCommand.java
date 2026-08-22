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
import gg.grounds.buildsystem.registry.DeviceFlow;
import gg.grounds.buildsystem.registry.MapSummary;
import gg.grounds.buildsystem.registry.MapVersion;
import gg.grounds.buildsystem.registry.PlayerLogins;
import gg.grounds.buildsystem.registry.RegistryClient;
import gg.grounds.buildsystem.registry.RegistryException;
import gg.grounds.buildsystem.registry.TokenSource;
import gg.grounds.buildsystem.world.MapAddresses;
import gg.grounds.buildsystem.world.MapLinks;
import gg.grounds.buildsystem.world.MapPublishValidation;
import gg.grounds.buildsystem.world.MapSetup;
import gg.grounds.buildsystem.world.PointsOfInterest;
import gg.grounds.buildsystem.world.SetupProfile;
import gg.grounds.buildsystem.world.WorldArchive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
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

    private static final List<String> SUBCOMMANDS =
            List.of("push", "fork", "versions", "link", "status", "login", "logout", "poi", "setup");

    private final JavaPlugin plugin;
    private final RegistryClient registry;
    private final DeviceFlow deviceFlow;
    private final PlayerLogins logins;
    private final MapLinks links;
    /** Who has a sign-in link outstanding, so a second one cannot orphan the first. */
    private final java.util.Set<java.util.UUID> pendingLogins = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MapCommand(
            JavaPlugin plugin, RegistryClient registry, DeviceFlow deviceFlow, PlayerLogins logins, MapLinks links) {
        this.plugin = plugin;
        this.registry = registry;
        this.deviceFlow = deviceFlow;
        this.logins = logins;
        this.links = links;
    }

    /**
     * Writing the link must not undo a push that already succeeded: the version exists in the
     * registry either way, and a failed write is local bookkeeping the builder can repair by
     * running the command again.
     */
    private void linkQuietly(Player player, BuildWorld world, String address, @Nullable Integer version) {
        try {
            links.link(world.getUniqueId(), world.getName(), address, version);
        } catch (java.io.IOException e) {
            error(player, "Saved to the registry, but this server could not remember the link: " + e.getMessage());
        }
    }

    /**
     * The address the registry will take, or null after telling the builder why not. Typing a
     * world name is the normal case, and world names are not addresses: the registry allows only
     * lowercase letters, digits and hyphens because an address becomes a URL path and an object
     * key. Saying which address was used keeps the translation from being a silent one.
     */
    private @Nullable String usableAddress(Player player, String typed) {
        String normalised = MapAddresses.normalise(typed);
        if (normalised == null) {
            error(
                    player,
                    "\"" + typed + "\" is not a map address. It looks like bedwars/crater — a"
                            + " gamemode, a slash, and a name.");
            return null;
        }
        if (!normalised.equals(typed)) {
            info(player, "Using " + normalised + " — addresses are lowercase, with hyphens for spaces.");
        }
        return normalised;
    }

    /** Who this command acts as: the signed-in builder, else the build server itself. */
    private TokenSource authFor(Player player) {
        return logins.actingAs(player.getUniqueId(), registry.serviceAccount());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this in-game, standing in the world you mean.");
            return true;
        }
        String early = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        // Signing in is not about a world, so it must work while standing anywhere — including
        // the lobby a builder lands in.
        if (early.equals("login")) {
            login(player);
            return true;
        }
        if (early.equals("logout")) {
            pendingLogins.remove(player.getUniqueId());
            logins.forget(player.getUniqueId());
            ok(player, "Signed out. Pushes now run as the build server.");
            return true;
        }

        BuildWorld world =
                BuildSystemProvider.get().getWorldService().getWorldStorage().getBuildWorld(player.getWorld());
        if (world == null) {
            error(player, "This is not a build world.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(player, world);
            case "push" -> push(player, world, args);
            case "fork" -> fork(player, world, args.length > 1 ? args[1] : null);
            case "versions" -> versions(player, world);
            case "link" -> link(player, world, args.length > 1 ? args[1] : null);
            case "poi" -> poi(player, world, args);
            case "setup" -> setup(player, world, args);
            default -> error(player, "Unknown: " + sub + ". Try /map push.");
        }
        return true;
    }

    // ------------------------------------------------------------- commands

    private void status(Player player, BuildWorld world) {
        String signedIn = logins.nameOf(player.getUniqueId());
        info(
                player,
                signedIn == null
                        ? "Not signed in — pushes run as the build server. /map login changes that."
                        : "Signed in as " + signedIn + ".");
        String address = links.addressOf(world.getUniqueId());
        if (address == null) {
            info(player, "This world is not linked to a map yet. Use /map link <namespace/name>.");
            return;
        }
        Integer base = links.baseVersionOf(world.getUniqueId());
        info(
                player,
                "This world is "
                        + address
                        + (base == null ? " (not built on a published version)" : ", built on v" + base)
                        + ".");
    }

    /**
     * The device flow, which exists for exactly this: a Minecraft client cannot follow a redirect,
     * and a password typed into chat lands in the server log and in every chat-logging plugin.
     * The builder gets a link, opens it where they already have a browser, and the server waits.
     */
    private void login(Player player) {
        if (logins.isSignedIn(player.getUniqueId())) {
            info(player, "Already signed in as " + logins.nameOf(player.getUniqueId()) + ".");
            return;
        }
        // A second /map login while one is pending leaves two links alive, and approving the
        // older one fails with "device code not valid" — which reads like a bug rather than
        // like "you clicked the wrong link". Only the newest attempt stays.
        if (!pendingLogins.add(player.getUniqueId())) {
            info(player, "A sign-in is already waiting. Open the last link, or /map logout first.");
            return;
        }
        offMainThread(player, () -> {
            DeviceFlow.Pending pending = deviceFlow.begin();
            player.sendMessage(Component.text("Open this to sign in:", NamedTextColor.GRAY));
            player.sendMessage(Component.text(pending.verificationUri(), NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(pending.verificationUri())));
            info(player, "If it asks for a code: " + pending.userCode());

            info(player, "Waiting — the link is good for the next few minutes.");
            try {
                DeviceFlow.Tokens tokens = deviceFlow.awaitApproval(pending);
                logins.remember(player.getUniqueId(), player.getName(), tokens);
                ok(player, "Signed in. Pushes are now recorded as you.");
            } finally {
                pendingLogins.remove(player.getUniqueId());
            }
        });
    }

    /**
     * The named places a gamemode needs: spawns, beds, generators.
     *
     * <p>Marked by standing where the thing belongs and naming it, because a builder already knows
     * how to stand somewhere — no coordinates to read off a debug screen, and no sign to place and
     * later forget to remove. The facing is taken from where they look: a spawn that drops players
     * into a wall is a bug report nobody can explain from coordinates alone.
     *
     * <p>Points live in the world folder, so they travel in the bundle and a version's places are
     * fixed the moment it is published.
     */
    /**
     * What this map is for, and what is still missing.
     *
     * <p>{@code /map setup bedwars 4} is the one number a builder knows; every requirement is
     * expanded from it. Called without arguments it answers the only question that matters while
     * building — what is left — grouped by team, because a flat list of twenty names is something
     * the builder then has to sort themselves.
     */
    private void setup(Player player, BuildWorld world, String[] args) {
        World bukkitWorld = world.getWorld().orElse(null);
        if (bukkitWorld == null) {
            error(player, "The world is not loaded.");
            return;
        }
        Path folder = bukkitWorld.getWorldFolder().toPath();

        if (args.length > 1) {
            String gamemode = args[1].toLowerCase(Locale.ROOT);
            if (!SetupProfile.isKnown(gamemode)) {
                error(player, "No setup for \"" + gamemode + "\". Known: " + String.join(", ", SetupProfile.gamemodes()));
                return;
            }
            // Either a count — "4" picks the first four colours — or the colours themselves, for
            // a map whose bases are green and pink. Both end up as a list of colours, because a
            // player sees the red bed, never team one.
            List<String> teams = SetupProfile.hasTeams(gamemode) ? teamsFrom(args) : List.of();
            if (SetupProfile.hasTeams(gamemode) && teams.isEmpty()) {
                error(
                        player,
                        "How many teams? /map setup " + gamemode + " 4 — or name them: /map setup "
                                + gamemode + " red blue green yellow");
                return;
            }
            try {
                MapSetup.write(folder, new MapSetup.Setup(gamemode, teams));
            } catch (IOException e) {
                error(player, "Could not save the setup: " + e.getMessage());
                return;
            }
            ok(
                    player,
                    teams.isEmpty()
                            ? "This map is a " + gamemode + "."
                            : "This map is " + gamemode + " for " + teams.size() + " teams: "
                                    + String.join(", ", teams));
        }

        MapSetup.Setup current = MapSetup.read(folder);
        if (current == null) {
            info(player, "Not set up yet. /map setup bedwars 4 says what this map is for.");
            return;
        }
        reportProgress(player, folder, current);
    }

    private void reportProgress(Player player, Path folder, MapSetup.Setup setup) {
        Set<String> marked = PointsOfInterest.read(folder).keySet();
        Map<String, List<String>> missing =
                SetupProfile.missingByGroup(setup.gamemode(), setup.teams(), marked);
        int required = SetupProfile.required(setup.gamemode(), setup.teams()).size();
        int done = required - SetupProfile.missing(setup.gamemode(), setup.teams(), marked).size();

        if (missing.isEmpty()) {
            ok(
                    player,
                    (setup.teams().isEmpty()
                                    ? setup.gamemode()
                                    : setup.gamemode() + " for " + setup.teams().size() + " teams")
                            + ": all " + required + " places marked. /map push publishes it.");
            return;
        }
        info(
                player,
                (setup.teams().isEmpty()
                                ? setup.gamemode()
                                : setup.gamemode() + " for " + setup.teams().size() + " teams")
                        + " — " + done + " of " + required + " marked. Still missing:");
        missing.forEach((group, things) -> player.sendMessage(Component.text("  " + group + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.join(", ", things), NamedTextColor.RED))));
        info(player, "Stand where one belongs and run /ms " + missing.keySet().iterator().next()
                + " " + missing.values().iterator().next().get(0));
    }

    /** `4` means the first four colours; `red blue` means exactly those. */
    private static List<String> teamsFrom(String[] args) {
        if (args.length == 3) {
            try {
                int count = Integer.parseInt(args[2]);
                if (count >= 1 && count <= SetupProfile.COLOURS.size()) {
                    return SetupProfile.defaultColours(count);
                }
            } catch (NumberFormatException e) {
                // Not a count, so read it as the first colour below.
            }
        }
        List<String> colours = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String colour = args[i].toLowerCase(Locale.ROOT);
            if (!SetupProfile.isColour(colour) || colours.contains(colour)) {
                return List.of();
            }
            colours.add(colour);
        }
        return colours;
    }

    private void poi(Player player, BuildWorld world, String[] args) {
        World bukkitWorld = world.getWorld().orElse(null);
        if (bukkitWorld == null) {
            error(player, "The world is not loaded.");
            return;
        }
        Path folder = bukkitWorld.getWorldFolder().toPath();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        String name = args.length > 2 ? PointsOfInterest.normaliseName(args[2]) : null;

        switch (action) {
            case "list" -> listPois(player, folder);
            case "set" -> setPoi(player, folder, name);
            case "remove", "delete" -> removePoi(player, folder, name);
            case "tp", "goto" -> goToPoi(player, folder, name);
            default -> error(player, "Try /map poi set <name>, list, remove <name> or tp <name>.");
        }
    }

    private void listPois(Player player, Path folder) {
        Map<String, PointsOfInterest.Poi> pois = PointsOfInterest.read(folder);
        if (pois.isEmpty()) {
            info(player, "No places marked yet. Stand where one belongs and run /map poi set <name>.");
            return;
        }
        info(player, pois.size() + " place" + (pois.size() == 1 ? "" : "s") + " in this map:");
        pois.forEach((poiName, poi) -> player.sendMessage(Component.text("  " + poiName, NamedTextColor.AQUA)
                .append(Component.text(
                        String.format(
                                Locale.ROOT, "  %.0f %.0f %.0f", poi.x(), poi.y(), poi.z()),
                        NamedTextColor.DARK_GRAY))));
    }

    private void setPoi(Player player, Path folder, @Nullable String name) {
        if (name == null) {
            error(player, "Name it: /map poi set lobby.spawn");
            return;
        }
        if (!PointsOfInterest.isValidName(name)) {
            error(player, "\"" + name + "\" will not do. Names are lowercase and dotted, like team.red.spawn.");
            return;
        }
        Location at = player.getLocation();
        Map<String, PointsOfInterest.Poi> pois = PointsOfInterest.read(folder);
        boolean replaced = pois.containsKey(name);
        pois.put(
                name,
                new PointsOfInterest.Poi(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()));
        try {
            PointsOfInterest.write(folder, pois);
        } catch (IOException e) {
            error(player, "Could not save the places: " + e.getMessage());
            return;
        }
        ok(
                player,
                (replaced ? "Moved " : "Marked ") + name + " here, facing the way you are looking."
                        + (replaced ? "" : " It travels with the next push."));
    }

    private void removePoi(Player player, Path folder, @Nullable String name) {
        if (name == null) {
            error(player, "Which one? /map poi remove lobby.spawn");
            return;
        }
        Map<String, PointsOfInterest.Poi> pois = PointsOfInterest.read(folder);
        if (pois.remove(name) == null) {
            error(player, "No place called " + name + " in this map.");
            return;
        }
        try {
            PointsOfInterest.write(folder, pois);
        } catch (IOException e) {
            error(player, "Could not save the places: " + e.getMessage());
            return;
        }
        ok(player, "Removed " + name + ". Published versions keep the one they were published with.");
    }

    private void goToPoi(Player player, Path folder, @Nullable String name) {
        if (name == null) {
            error(player, "Where to? /map poi tp lobby.spawn");
            return;
        }
        PointsOfInterest.Poi poi = PointsOfInterest.get(folder, name);
        if (poi == null) {
            error(player, "No place called " + name + " in this map.");
            return;
        }
        // Standing in it is the only honest check that a spawn is not inside a wall.
        player.teleport(new Location(
                player.getWorld(), poi.x(), poi.y(), poi.z(), poi.yaw(), poi.pitch()));
        info(player, "This is " + name + ".");
    }

    private void link(Player player, BuildWorld world, @Nullable String typedAddress) {
        if (typedAddress == null) {
            error(player, "Which map? /map link bedwars/4x4-baumhaus");
            return;
        }
        String usable = usableAddress(player, typedAddress);
        if (usable == null) {
            return;
        }
        final String address = usable;
        String existing = links.addressOf(world.getUniqueId());
        if (existing != null) {
            // Relinking silently would make the next push land on a different map with no
            // trace of why. Deleting the world data is the operator's call, not a side effect.
            error(player, "Already linked to " + existing + ".");
            return;
        }
        TokenSource auth = authFor(player);
        offMainThread(player, () -> {
            List<MapSummary> maps = registry.listMaps(auth);
            boolean exists = maps.stream().anyMatch(map -> map.address().equals(address));
            if (!exists) {
                registry.createMap(auth, address, world.getName(), "ARENA", false);
            }
            onMainThread(() -> {
                linkQuietly(player, world, address, null);
                ok(player, "Linked to " + address + (exists ? "." : " (new map)."));
            });
        });
    }

    private void push(Player player, BuildWorld world, String[] args) {
        // `/map push bedwars/crater` is what a builder types for a world that has no map yet, so
        // it means what it looks like: link it, then push. Once a world is linked the same token
        // is a note, because repeating the address every time would be noise.
        String linked = links.addressOf(world.getUniqueId());
        boolean linkFirst = linked == null && args.length > 1 && args[1].contains("/");
        String note = join(args, linkFirst ? 2 : 1);
        String resolved = linked;
        if (linkFirst) {
            resolved = usableAddress(player, args[1]);
            if (resolved == null) {
                return;
            }
        }
        if (resolved == null) {
            error(player, "Which map? /map push <namespace/name> — or /map link it once.");
            return;
        }
        final String address = resolved;
        World bukkitWorld = world.getWorld().orElse(null);
        if (bukkitWorld == null) {
            error(player, "The world is not loaded.");
            return;
        }
        Path folder = bukkitWorld.getWorldFolder().toPath();
        String problem = MapPublishValidation.problem(folder);
        if (problem != null) {
            error(player, problem);
            return;
        }

        // Save on the main thread, then stop autosaving while we read the region files. A tick
        // that flushes a chunk mid-archive produces a bundle that is a valid tar of a corrupt
        // world, and nothing downstream can tell.
        bukkitWorld.save();
        boolean autoSave = bukkitWorld.isAutoSave();
        bukkitWorld.setAutoSave(false);

        Integer parent = links.baseVersionOf(world.getUniqueId());
        TokenSource auth = authFor(player);
        info(player, "Packing " + address + "…");

        offMainThread(player, () -> {
            Path archive = Files.createTempFile("grounds-map-", ".tar.zst");
            try {
                if (linkFirst) {
                    List<MapSummary> maps = registry.listMaps(auth);
                    if (maps.stream().noneMatch(map -> map.address().equals(address))) {
                        registry.createMap(auth, address, world.getName(), "ARENA", false);
                        info(player, "Created " + address + ".");
                    }
                }
                WorldArchive.Archive packed = WorldArchive.pack(folder, archive);
                String archiveProblem = MapPublishValidation.problem(packed);
                if (archiveProblem != null) {
                    onMainThread(() -> error(player, archiveProblem));
                    return;
                }
                onMainThread(() -> bukkitWorld.setAutoSave(autoSave));
                info(player, "Uploading " + mib(packed.sizeBytes()) + " to the registry…");
                MapVersion published =
                        registry.push(auth, address, packed.file(), packed.sha256(), packed.sizeBytes(), parent, note);
                onMainThread(() -> {
                    linkQuietly(player, world, address, published.version());
                    ok(
                            player,
                            "Published "
                                    + address
                                    + " v"
                                    + published.version()
                                    + " ("
                                    + mib(packed.sizeBytes())
                                    + ") as "
                                    + auth.describe()
                                    + ". An admin decides when it goes live.");
                });
            } finally {
                // Whatever happened, autosave goes back on and the temp file goes away.
                onMainThread(() -> bukkitWorld.setAutoSave(autoSave));
                Files.deleteIfExists(archive);
            }
        });
    }

    private void fork(Player player, BuildWorld world, @Nullable String target) {
        String address = links.addressOf(world.getUniqueId());
        if (address == null) {
            error(player, "This world is not a map, so there is nothing to fork.");
            return;
        }
        if (target == null || !target.contains("/")) {
            error(player, "Fork into which address? /map fork bedwars/4x4-baumhaus-winter");
            return;
        }
        Integer base = links.baseVersionOf(world.getUniqueId());
        TokenSource auth = authFor(player);
        offMainThread(player, () -> {
            MapSummary forked = registry.fork(auth, address, target, base, null);
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
        String address = links.addressOf(world.getUniqueId());
        if (address == null) {
            error(player, "This world is not linked to a map.");
            return;
        }
        TokenSource auth = authFor(player);
        offMainThread(player, () -> {
            List<MapVersion> versions = registry.listVersions(auth, address);
            if (versions.isEmpty()) {
                info(player, "No versions yet — /map push makes the first one.");
                return;
            }
            info(player, address + ":");
            versions.stream()
                    .sorted((a, b) -> Integer.compare(b.version(), a.version()))
                    .limit(10)
                    .forEach(version -> info(
                            player,
                            "  v"
                                    + version.version()
                                    + " · "
                                    + version.state().toLowerCase(Locale.ROOT)
                                    + (version.note() == null ? "" : " · " + version.note())));
        });
    }

    // ------------------------------------------------------------ plumbing

    /** Anything that touches the network or the disk. Never the main thread. */
    private void offMainThread(Player player, Work work) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
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
    public @Nullable List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
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
