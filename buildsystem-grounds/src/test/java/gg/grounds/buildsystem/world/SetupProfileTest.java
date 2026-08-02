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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SetupProfileTest {

    /** Four teams is the one number a builder knows; everything else follows from it. */
    /**
     * A lobby has no teams and, for now, one place: where players land. Everything else it wants is
     * built in the world, and asking a builder to mark places nothing reads is worse than asking
     * for nothing.
     */
    @Test
    void a_lobby_has_no_teams_and_one_place() {
        assertFalse(SetupProfile.hasTeams("lobby"));
        assertTrue(SetupProfile.hasTeams("bedwars"));
        assertTrue(SetupProfile.isKnown("lobby"));

        assertEquals(List.of("spawn"), SetupProfile.required("lobby", List.of()));
        assertEquals("spawn", SetupProfile.resolve("lobby", "map", "spawn", Set.of()));
        // Not a block: it is where a player stands, so it comes from the builder's feet.
        assertFalse(SetupProfile.isBlock("spawn"));
        assertEquals(
                List.of("spawn"), SetupProfile.missing("lobby", List.of(), Set.of()), "nothing marked yet");
        assertEquals(
                List.of(), SetupProfile.missing("lobby", List.of(), Set.of("spawn")), "and then it is done");
    }

    @Test
    void teams_are_colours_because_that_is_what_a_player_sees() {
        assertEquals(List.of("red", "blue", "green", "yellow"), SetupProfile.defaultColours(4));
        assertTrue(SetupProfile.isColour("cyan"));
        assertFalse(SetupProfile.isColour("team1"));
    }

    @Test
    void expands_every_requirement_from_the_team_count() {
        List<String> required = SetupProfile.required("bedwars", SetupProfile.defaultColours(4));

        // 1 shared (gold.1) + 4 teams × 5 places.
        assertEquals(21, required.size());
        assertTrue(required.contains("red.spawn"));
        assertTrue(required.contains("yellow.iron.1"));
        assertTrue(required.contains("gold.1"));
        // Two teams is two teams: nothing lingers from a bigger map.
        assertEquals(11, SetupProfile.required("bedwars", SetupProfile.defaultColours(2)).size());
    }

    /** Teams come as blocks so a builder can finish one base before walking to the next. */
    @Test
    void lists_requirements_in_walking_order() {
        List<String> required = SetupProfile.required("bedwars", SetupProfile.defaultColours(2));

        assertEquals("gold.1", required.get(0));
        assertEquals(
                List.of("red.spawn", "red.bed", "red.shop", "red.copper.1", "red.iron.1"),
                required.subList(1, 6));
    }

    @Test
    void reports_what_is_left_grouped_by_team() {
        Set<String> marked = Set.of("gold.1", "red.spawn", "red.bed");

        var missing = SetupProfile.missingByGroup("bedwars", SetupProfile.defaultColours(2), marked);

        assertEquals(List.of("shop", "copper.1", "iron.1"), missing.get("red"));
        assertEquals(5, missing.get("blue").size());
        assertNull(missing.get("map"), "everything shared is marked");
    }

    /**
     * A typo must not become a point nothing reads — that is the failure mode that leaves a map
     * looking finished and behaving broken.
     */
    /**
     * `/ms diamond` used to write a point called `diamond` while the checklist asked for
     * `diamond.1`, so a marked generator stayed missing forever. It now takes the next free
     * number — maps have several, and a builder should click rather than count.
     */
    @Test
    void a_numbered_generator_takes_the_next_free_slot() {
        assertEquals("gold.1", SetupProfile.resolve("bedwars", "map", "gold", Set.of()));
        assertEquals("gold.2", SetupProfile.resolve("bedwars", "map", "gold", Set.of("gold.1")));
        assertEquals("gold.3", SetupProfile.resolve("bedwars", "map", "gold", Set.of("gold.1", "gold.2")));
    }

    /** Bases have several spawns too, so the numbering has to work per team as well. */
    @Test
    void a_team_can_have_several_of_the_same_spawn() {
        assertEquals("red.copper.1", SetupProfile.resolve("bedwars", "red", "copper", Set.of()));
        assertEquals(
                "red.copper.2", SetupProfile.resolve("bedwars", "red", "copper", Set.of("red.copper.1")));
        assertEquals("red.iron.1", SetupProfile.resolve("bedwars", "red", "iron", Set.of("red.copper.1")));
        // The one-of-a-kind places keep their plain name.
        assertEquals("red.bed", SetupProfile.resolve("bedwars", "red", "bed", Set.of()));
    }

    @Test
    void refuses_a_thing_the_gamemode_does_not_have() {
        assertEquals("red.spawn", SetupProfile.resolve("bedwars", "red", "spawn", Set.of()));
        assertEquals("green.bed", SetupProfile.resolve("bedwars", "GREEN", "BED", Set.of()));
        assertNull(SetupProfile.resolve("bedwars", "red", "spwan", Set.of()));
        assertNull(SetupProfile.resolve("bedwars", "map", "nonsense", Set.of()));
        // This game has no diamonds, no emeralds and no upgrade villager — asking for them would
        // send a builder to mark places nothing will ever read.
        assertNull(SetupProfile.resolve("bedwars", "map", "diamond", Set.of()));
        assertNull(SetupProfile.resolve("bedwars", "red", "upgrade", Set.of()));
        assertNull(SetupProfile.resolve("bedwars", "red", "diamond", Set.of()), "not in this game");
    }
}
