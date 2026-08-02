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
        if (args.length < 2) {
            error(player, "Two words: /ms team1 spawn — or /map setup to see what is missing.");
            return true;
        }

        String group = args[0].toLowerCase(Locale.ROOT);
        String thing = args[1].toLowerCase(Locale.ROOT);
        String point = SetupProfile.resolve(setup.gamemode(), group, thing);
        if (point == null) {
            error(
                    player,
                    "\"" + thing + "\" is not part of " + setup.gamemode() + ". For a team: "
                            + String.join(", ", SetupProfile.thingsFor(setup.gamemode())));
            return true;
        }
        if (group.startsWith("team")) {
            int number = teamNumber(group);
            if (number < 1 || number > setup.teams()) {
                error(player, "This map has " + setup.teams() + " teams, so " + group + " is not one of them.");
                return true;
            }
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

    /** `team2.bed` reads back as `team2 bed`, so the hint is the command rather than a name. */
    private static String asCommand(String point) {
        int dot = point.indexOf('.');
        return dot < 0 || !point.startsWith("team")
                ? "map " + point
                : point.substring(0, dot) + " " + point.substring(dot + 1);
    }

    private static int teamNumber(String group) {
        try {
            return Integer.parseInt(group.substring("team".length()));
        } catch (RuntimeException e) {
            return -1;
        }
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
            matches.add("map");
            for (int team = 1; team <= setup.teams(); team++) {
                matches.add("team" + team);
            }
        } else if (args.length == 2) {
            matches.addAll(SetupProfile.thingsFor(setup.gamemode()));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        matches.removeIf(match -> !match.startsWith(prefix));
        return matches;
    }
}
