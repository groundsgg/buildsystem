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
package gg.grounds.buildsystem.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What a gamemode needs marked before a map is playable.
 *
 * <p>Data, not code paths: adding a gamemode is a line here. Every requirement is expanded from the
 * team count, because "four teams" is the one number a builder actually knows — everything else
 * follows from it, and asking them to remember that four teams means twenty-four points is how maps
 * ship half-configured.
 *
 * <p>The list is what makes {@code /map setup} able to answer "what is left", which is the whole
 * reason it exists. Without it a builder marks points and hopes.
 */
@NullMarked
public final class SetupProfile {

    private SetupProfile() {}

    /** What every team needs, in the order a builder would naturally walk it. */
    private static final Map<String, List<String>> PER_TEAM = Map.of(
            "bedwars", List.of("spawn", "bed", "iron", "gold", "shop", "upgrade"));

    /** What the map needs once, regardless of team count. */
    private static final Map<String, List<String>> GLOBAL = Map.of(
            "bedwars", List.of("lobby", "spectator", "diamond.1", "emerald.1"));

    public static Set<String> gamemodes() {
        return PER_TEAM.keySet();
    }

    public static boolean isKnown(String gamemode) {
        return PER_TEAM.containsKey(gamemode.toLowerCase(Locale.ROOT));
    }

    /**
     * Every point this map must have, in walking order: the shared ones first, then each team as a
     * block, so a builder can finish one base before moving to the next.
     */
    public static List<String> required(String gamemode, int teams) {
        String mode = gamemode.toLowerCase(Locale.ROOT);
        List<String> required = new ArrayList<>(GLOBAL.getOrDefault(mode, List.of()));
        for (int team = 1; team <= teams; team++) {
            for (String point : PER_TEAM.getOrDefault(mode, List.of())) {
                required.add("team" + team + "." + point);
            }
        }
        return required;
    }

    /** Which of the required points are still unmarked, in the same walking order. */
    public static List<String> missing(String gamemode, int teams, Set<String> marked) {
        List<String> missing = new ArrayList<>();
        for (String point : required(gamemode, teams)) {
            if (!marked.contains(point)) {
                missing.add(point);
            }
        }
        return missing;
    }

    /**
     * Groups the missing points by their team, so the reply can say "team3 is missing bed, shop"
     * rather than listing twenty names a builder has to sort themselves.
     */
    public static Map<String, List<String>> missingByGroup(
            String gamemode, int teams, Set<String> marked) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String point : missing(gamemode, teams, marked)) {
            int dot = point.indexOf('.');
            String group = dot < 0 || !point.startsWith("team") ? "map" : point.substring(0, dot);
            String rest = dot < 0 ? point : point.substring(dot + 1);
            grouped.computeIfAbsent(group, key -> new ArrayList<>()).add(rest);
        }
        return grouped;
    }

    /**
     * The full point name for {@code /ms team1 spawn}. Returns null when the thing is not part of
     * this gamemode — a typo like `spwan` would otherwise become a point nothing ever reads.
     */
    public static @Nullable String resolve(String gamemode, String group, String thing) {
        String mode = gamemode.toLowerCase(Locale.ROOT);
        String point = thing.toLowerCase(Locale.ROOT);
        if (group.equalsIgnoreCase("map")) {
            return GLOBAL.getOrDefault(mode, List.of()).stream()
                            .anyMatch(known -> known.equals(point) || known.startsWith(point + "."))
                    ? point
                    : null;
        }
        return PER_TEAM.getOrDefault(mode, List.of()).contains(point)
                ? group.toLowerCase(Locale.ROOT) + "." + point
                : null;
    }

    /** What a builder may write after a team, for the "unknown thing" reply and tab completion. */
    public static List<String> thingsFor(String gamemode) {
        return PER_TEAM.getOrDefault(gamemode.toLowerCase(Locale.ROOT), List.of());
    }
}
