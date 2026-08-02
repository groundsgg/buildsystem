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

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapLinksTest {

    @TempDir
    Path dataFolder;

    @Test
    void an_unknown_world_has_no_map() {
        MapLinks links = new MapLinks(dataFolder.toFile());

        assertNull(links.addressOf(UUID.randomUUID()));
        assertNull(links.baseVersionOf(UUID.randomUUID()));
    }

    /**
     * The link has to survive a restart, or every push after one would ask for the address again.
     */
    @Test
    void a_link_survives_a_reload() throws IOException {
        UUID world = UUID.randomUUID();
        MapLinks links = new MapLinks(dataFolder.toFile());
        links.link(world, "MainLobby", "lobby/main", 3);

        MapLinks reloaded = new MapLinks(dataFolder.toFile());

        assertEquals("lobby/main", reloaded.addressOf(world));
        assertEquals(3, reloaded.baseVersionOf(world));
    }

    /**
     * Keyed by UUID, not by name: renaming a world in the navigator must not separate it from its
     * map. That property is the whole reason this is not stored under the world's name.
     */
    @Test
    void a_rename_does_not_lose_the_link() throws IOException {
        UUID world = UUID.randomUUID();
        MapLinks links = new MapLinks(dataFolder.toFile());
        links.link(world, "MainLobby", "lobby/main", null);

        links.link(world, "Lobby_v2", "lobby/main", 1);

        assertEquals("lobby/main", links.addressOf(world));
        assertEquals(1, links.baseVersionOf(world));
    }

    /** No base version yet is null rather than 0 — version 0 does not exist in the registry. */
    @Test
    void a_world_never_pushed_has_no_base_version() throws IOException {
        UUID world = UUID.randomUUID();
        MapLinks links = new MapLinks(dataFolder.toFile());

        links.link(world, "Fresh", "bedwars/crater", null);

        assertEquals("bedwars/crater", links.addressOf(world));
        assertNull(links.baseVersionOf(world));
    }
}
