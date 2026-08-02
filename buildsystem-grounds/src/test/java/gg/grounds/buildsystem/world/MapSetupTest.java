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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapSetupTest {

    @TempDir
    Path world;

    @Test
    void a_map_carries_its_own_shape() throws IOException {
        MapSetup.write(world, new MapSetup.Setup("bedwars", 4));

        MapSetup.Setup read = MapSetup.read(world);

        assertEquals("bedwars", read.gamemode());
        assertEquals(4, read.teams());
        // Inside the world, so a gamemode loading the bundle is told the shape by the map itself.
        assertTrue(Files.isRegularFile(world.resolve("grounds").resolve("setup.json")));
    }

    @Test
    void an_unconfigured_map_reads_as_null_rather_than_a_guess() {
        assertNull(MapSetup.read(world));
    }

    @Test
    void a_broken_file_reads_as_not_set_up() throws IOException {
        Files.createDirectories(world.resolve("grounds"));
        Files.writeString(world.resolve("grounds").resolve("setup.json"), "{ not json");

        assertNull(MapSetup.read(world));
    }
}
