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

import org.junit.jupiter.api.Test;

/**
 * The registry allows only {@code [a-z0-9-]} per segment, because an address becomes a URL path and
 * an object key. Builders type world names, so these cases are the normal input rather than abuse.
 */
class MapAddressesTest {

    @Test
    void lowercases_a_world_name() {
        assertEquals("lobby/mainlobby", MapAddresses.normalise("lobby/MainLobby"));
    }

    @Test
    void turns_word_breaks_into_single_hyphens() {
        // Runs must collapse: "sky--wars-2" is exactly what the registry refuses.
        assertEquals("bedwars/sky-wars-2", MapAddresses.normalise("BedWars/Sky  Wars_2"));
    }

    @Test
    void leaves_an_address_that_is_already_valid_alone() {
        assertEquals("bedwars/4x4-baumhaus", MapAddresses.normalise("bedwars/4x4-baumhaus"));
    }

    /** A creator namespace is itself two segments, and the address is not two-part. */
    @Test
    void keeps_a_creator_namespace_intact() {
        assertEquals("u/hendrik/treehouse", MapAddresses.normalise("u/Hendrik/TreeHouse"));
    }

    @Test
    void refuses_what_has_no_sensible_reading() {
        assertNull(MapAddresses.normalise("mainlobby"), "no namespace");
        assertNull(MapAddresses.normalise("lobby/"), "no name");
        assertNull(MapAddresses.normalise("lobby/***"), "punctuation only");
        // Three segments are a creator address or nothing; guessing a namespace would be worse.
        assertNull(MapAddresses.normalise("a/b/c"));
    }

    @Test
    void world_name_is_the_last_segment() {
        assertEquals("mainlobby", MapAddresses.worldName("lobby/mainlobby"));
        assertEquals("4x4-baumhaus", MapAddresses.worldName("bedwars/4x4-baumhaus"));
        assertEquals("garden", MapAddresses.worldName("u/alice/garden"));
    }
}
