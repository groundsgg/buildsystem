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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SetupProfileTest {

    /** Four teams is the one number a builder knows; everything else follows from it. */
    @Test
    void expands_every_requirement_from_the_team_count() {
        List<String> required = SetupProfile.required("bedwars", 4);

        // 4 shared + 4 teams × 6 places.
        assertEquals(28, required.size());
        assertTrue(required.contains("team1.spawn"));
        assertTrue(required.contains("team4.upgrade"));
        assertTrue(required.contains("lobby"));
        assertTrue(required.contains("diamond.1"));
        // Two teams is two teams: nothing lingers from a bigger map.
        assertEquals(16, SetupProfile.required("bedwars", 2).size());
    }

    /** Teams come as blocks so a builder can finish one base before walking to the next. */
    @Test
    void lists_requirements_in_walking_order() {
        List<String> required = SetupProfile.required("bedwars", 2);

        assertEquals("lobby", required.get(0));
        assertEquals(
                List.of("team1.spawn", "team1.bed", "team1.iron", "team1.gold", "team1.shop", "team1.upgrade"),
                required.subList(4, 10));
    }

    @Test
    void reports_what_is_left_grouped_by_team() {
        Set<String> marked = Set.of("lobby", "spectator", "diamond.1", "emerald.1", "team1.spawn", "team1.bed");

        var missing = SetupProfile.missingByGroup("bedwars", 2, marked);

        assertEquals(List.of("iron", "gold", "shop", "upgrade"), missing.get("team1"));
        assertEquals(6, missing.get("team2").size());
        assertNull(missing.get("map"), "everything shared is marked");
    }

    /**
     * A typo must not become a point nothing reads — that is the failure mode that leaves a map
     * looking finished and behaving broken.
     */
    @Test
    void refuses_a_thing_the_gamemode_does_not_have() {
        assertEquals("team1.spawn", SetupProfile.resolve("bedwars", "team1", "spawn"));
        assertEquals("team3.bed", SetupProfile.resolve("bedwars", "TEAM3", "BED"));
        assertEquals("lobby", SetupProfile.resolve("bedwars", "map", "lobby"));
        assertNull(SetupProfile.resolve("bedwars", "team1", "spwan"));
        assertNull(SetupProfile.resolve("bedwars", "map", "nonsense"));
    }
}
