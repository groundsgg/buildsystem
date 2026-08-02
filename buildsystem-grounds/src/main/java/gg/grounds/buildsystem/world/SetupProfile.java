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

    /**
     * What every team needs, in the order a builder would naturally walk it.
     *
     * <p>Read off game-bedwars rather than guessed. That game has no diamonds and no emeralds: its
     * currencies are copper, iron and gold, ported from the 2018 server, and copper and iron
     * trickle inside a team's base.
     */
    private static final Map<String, List<String>> PER_TEAM = Map.of(
            "bedwars", List.of("spawn", "bed", "shop", "copper", "iron"),
            // A lobby has no teams. Everything it needs is shared, which is why the team list is
            // empty rather than absent: `hasTeams` reads it, and a gamemode missing from the map
            // is a different answer from one that has none.
            "lobby", List.of());

    /** What the map needs once, regardless of team count. Gold sits in the middle, contested. */
    private static final Map<String, List<String>> GLOBAL = Map.of(
            "bedwars", List.of("gold"),
            // Where players land. Everything else a lobby wants — portals, boards, shops — is
            // either built in the world or comes later; asking for it now would ask a builder to
            // mark places nothing reads.
            "lobby", List.of("spawn"));

    /**
     * Things a map has several of, so marking one takes the next free number.
     *
     * <p>The reference arena places two gold generators, "far enough apart that one team cannot
     * stand on both" — one of anything is the exception, not the rule.
     */
    private static final Set<String> NUMBERED = Set.of("copper", "iron", "gold");

    /**
     * Minecraft's dye colours, in the order a four-team map conventionally uses them.
     *
     * <p>Teams are colours because that is what a player sees: the red bed, the blue base. `team3`
     * is a number a builder has to translate every single time they walk into a base.
     */
    public static final List<String> COLOURS = List.of(
            "red",
            "blue",
            "green",
            "yellow",
            "cyan",
            "white",
            "pink",
            "gray",
            "orange",
            "lime",
            "purple",
            "magenta",
            "light_blue",
            "light_gray",
            "brown",
            "black");

    public static boolean isColour(String name) {
        return COLOURS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** The first {@code count} colours, for `/map setup bedwars 4`. */
    public static List<String> defaultColours(int count) {
        return List.copyOf(COLOURS.subList(0, Math.min(count, COLOURS.size())));
    }

    public static Set<String> gamemodes() {
        return PER_TEAM.keySet();
    }

    /** Whether this gamemode is played in teams. A lobby is not, so it takes no team argument. */
    public static boolean hasTeams(String gamemode) {
        return !PER_TEAM.getOrDefault(gamemode.toLowerCase(Locale.ROOT), List.of()).isEmpty();
    }

    public static boolean isKnown(String gamemode) {
        return PER_TEAM.containsKey(gamemode.toLowerCase(Locale.ROOT));
    }

    /**
     * Every point this map must have, in walking order: the shared ones first, then each team as a
     * block, so a builder can finish one base before moving to the next.
     */
    public static List<String> required(String gamemode, List<String> teams) {
        String mode = gamemode.toLowerCase(Locale.ROOT);
        List<String> required = new ArrayList<>();
        for (String point : GLOBAL.getOrDefault(mode, List.of())) {
            // One is required; a builder places as many more as the map wants.
            required.add(numberedFirst(point));
        }
        for (String team : teams) {
            for (String point : PER_TEAM.getOrDefault(mode, List.of())) {
                required.add(team + "." + numberedFirst(point));
            }
        }
        return required;
    }

    /** Which of the required points are still unmarked, in the same walking order. */
    public static List<String> missing(String gamemode, List<String> teams, Set<String> marked) {
        List<String> missing = new ArrayList<>();
        for (String point : required(gamemode, teams)) {
            if (!marked.contains(point)) {
                missing.add(point);
            }
        }
        return missing;
    }

    /**
     * Groups the missing points by their team colour, so the reply can say "red is missing bed,
     * shop" rather than listing twenty names a builder has to sort themselves.
     */
    public static Map<String, List<String>> missingByGroup(
            String gamemode, List<String> teams, Set<String> marked) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String point : missing(gamemode, teams, marked)) {
            int dot = point.indexOf('.');
            String group = dot < 0 || !teams.contains(point.substring(0, dot)) ? "map" : point.substring(0, dot);
            String rest = dot < 0 ? point : point.substring(dot + 1);
            grouped.computeIfAbsent(group, key -> new ArrayList<>()).add(rest);
        }
        return grouped;
    }

    /**
     * The full point name for {@code /ms team1 spawn}. Returns null when the thing is not part of
     * this gamemode — a typo like `spwan` would otherwise become a point nothing ever reads.
     */
    public static @Nullable String resolve(String gamemode, String group, String thing, Set<String> marked) {
        String mode = gamemode.toLowerCase(Locale.ROOT);
        String point = thing.toLowerCase(Locale.ROOT);
        if (group.equalsIgnoreCase("map")) {
            List<String> globals = GLOBAL.getOrDefault(mode, List.of());
            // Numbering is checked first: `gold` is in the list AND numbered, and returning the
            // plain name here is exactly the bug that left a marked generator missing forever.
            if (NUMBERED.contains(point)) {
                return nextFree(point, marked);
            }
            if (globals.contains(point)) {
                return point;
            }
            // A numbered family: a map has several gold spawns. `/ms gold` takes the next free
            // number, so a builder walks the map clicking rather than counting — and, critically,
            // the point it writes is the one the checklist asks for. Writing `gold` for a
            // requirement named `gold.1` left it missing forever, which is how this was found.
            return null;
        }
        if (!PER_TEAM.getOrDefault(mode, List.of()).contains(point)) {
            return null;
        }
        String team = group.toLowerCase(Locale.ROOT);
        // nextFree already returns the full name, so the team must not be prefixed twice.
        return NUMBERED.contains(point) ? nextFree(team + "." + point, marked) : team + "." + point;
    }

    /**
     * Things that are a block somebody has already built, not a place to stand.
     *
     * <p>A bed, a generator pad, a shop's block: standing next to one and taking the player's
     * position is off by the width of a player and by whatever they were looking at. These are
     * marked by right-clicking the block itself, which is also how a builder already thinks about
     * them — the bed is *there*, not where I happen to be standing.
     *
     * <p>The rest — spawns, the waiting lobby, the spectator point — are exactly a player's
     * position and facing, so they are taken from the player.
     */
    private static final Set<String> BLOCKS = Set.of("bed", "shop", "copper", "iron", "gold");

    private static String numberedFirst(String thing) {
        return NUMBERED.contains(thing) ? thing + ".1" : thing;
    }

    public static boolean isBlock(String thing) {
        String point = thing.toLowerCase(Locale.ROOT);
        int dot = point.indexOf('.');
        return BLOCKS.contains(dot < 0 ? point : point.substring(0, dot));
    }

    /** `copper` becomes `copper.1`, then `copper.2` — the builder clicks rather than counts. */
    private static String nextFree(String base, Set<String> marked) {
        for (int index = 1; index <= 64; index++) {
            if (!marked.contains(base + "." + index)) {
                return base + "." + index;
            }
        }
        return base + ".64";
    }

    /** The shared things, for tab completion. Suggesting one the profile refuses is worse than none. */
    public static List<String> globalThings(String gamemode) {
        return GLOBAL.getOrDefault(gamemode.toLowerCase(Locale.ROOT), List.of());
    }

    /** What a builder may write after a team, for the "unknown thing" reply and tab completion. */
    public static List<String> thingsFor(String gamemode) {
        return PER_TEAM.getOrDefault(gamemode.toLowerCase(Locale.ROOT), List.of());
    }
}
