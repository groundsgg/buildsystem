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
import gg.grounds.buildsystem.world.MapSetup;
import gg.grounds.buildsystem.world.PendingMarks;
import gg.grounds.buildsystem.world.PointsOfInterest;
import gg.grounds.buildsystem.world.SetupProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * {@code /ms team1 spawn} — the command a builder types a hundred times while laying out a map.
 *
 * <p>It exists separately from {@code /map poi set} because that is the difference between
 * {@code /ms team1 bed} and {@code /map poi set team1.bed} repeated for every point of every team.
 * The short form also knows the gamemode's profile, so a typo like {@code spwan} is refused instead
 * of quietly becoming a point nothing will ever read — the failure mode that leaves a map looking
 * finished and behaving broken.
 */
@NullMarked
public final class MapSetupCommand implements CommandExecutor, TabCompleter {

    private final PendingMarks pending;

    public MapSetupCommand(PendingMarks pending) {
        this.pending = pending;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this in-game, standing where the place belongs.");
            return true;
        }
        BuildWorld buildWorld = BuildSystemProvider.get()
                .getWorldService()
                .getWorldStorage()
                .getBuildWorld(player.getWorld());
        if (buildWorld == null) {
            error(player, "This is not a build world.");
            return true;
        }
        World world = buildWorld.getWorld().orElse(null);
        if (world == null) {
            error(player, "The world is not loaded.");
            return true;
        }
        Path folder = world.getWorldFolder().toPath();

        MapSetup.Setup setup = MapSetup.read(folder);
        if (setup == null) {
            error(player, "Say what this map is first: /map setup bedwars 4");
            return true;
        }
        if (args.length < 1) {
            error(player, "/ms team1 spawn, or /ms lobby — /map setup shows what is missing.");
            return true;
        }

        // `/ms lobby` rather than `/ms map lobby`: the shared places belong to no team, and making
        // a builder type a group name for them is tax on the command they type most.
        String group = args.length >= 2 ? args[0].toLowerCase(Locale.ROOT) : "map";
        String thing = (args.length >= 2 ? args[1] : args[0]).toLowerCase(Locale.ROOT);
        String point = SetupProfile.resolve(setup.gamemode(), group, thing);
        if (point == null) {
            error(
                    player,
                    "\"" + thing + "\" is not part of " + setup.gamemode() + ". For a team: "
                            + String.join(", ", SetupProfile.thingsFor(setup.gamemode())));
            return true;
        }
        if (!group.equals("map") && !setup.teams().contains(group)) {
            error(
                    player,
                    "This map's teams are " + String.join(", ", setup.teams()) + " — " + group
                            + " is not one of them.");
            return true;
        }

        if (SetupProfile.isBlock(thing)) {
            // Already built and standing there: pointing at it is exact, and standing next to it
            // is off by the width of a player.
            pending.arm(player.getUniqueId(), point, world.getUID());
            player.sendMessage(Component.text(
                    "Right-click the " + thing + " for " + group + ".", NamedTextColor.AQUA));
            return true;
        }

        Location at = player.getLocation();
        Map<String, PointsOfInterest.Poi> pois = PointsOfInterest.read(folder);
        boolean replaced = pois.containsKey(point);
        pois.put(point, new PointsOfInterest.Poi(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()));
        try {
            PointsOfInterest.write(folder, pois);
        } catch (IOException e) {
            error(player, "Could not save the places: " + e.getMessage());
            return true;
        }

        List<String> missing = SetupProfile.missing(setup.gamemode(), setup.teams(), pois.keySet());
        player.sendMessage(Component.text(
                (replaced ? "Moved " : "Marked ") + point + ".", NamedTextColor.GREEN));
        if (missing.isEmpty()) {
            player.sendMessage(Component.text(
                    "That was the last one — the map is complete. /map push publishes it.",
                    NamedTextColor.GREEN));
        } else {
            // The next name, not a count: it is what they type next, and typing it is the work.
            player.sendMessage(Component.text(
                    missing.size() + " left. Next: /ms " + asCommand(missing.get(0)),
                    NamedTextColor.GRAY));
        }
        return true;
    }

    /** `red.bed` reads back as `red bed`, so the hint is the command rather than a name. */
    private static String asCommand(String point) {
        return point.replace('.', ' ');
    }

    private static void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    @Override
    public @Nullable List<String> onTabComplete(
            CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        BuildWorld buildWorld = BuildSystemProvider.get()
                .getWorldService()
                .getWorldStorage()
                .getBuildWorld(player.getWorld());
        World world = buildWorld == null ? null : buildWorld.getWorld().orElse(null);
        MapSetup.Setup setup = world == null ? null : MapSetup.read(world.getWorldFolder().toPath());
        if (setup == null) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        if (args.length == 1) {
            // The colours first: they are what gets typed, and the shared things are few.
            matches.addAll(setup.teams());
            matches.addAll(SetupProfile.thingsFor(setup.gamemode()));
            matches.add("lobby");
            matches.add("spectator");
        } else if (args.length == 2) {
            matches.addAll(SetupProfile.thingsFor(setup.gamemode()));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        matches.removeIf(match -> !match.startsWith(prefix));
        return matches;
    }
}
