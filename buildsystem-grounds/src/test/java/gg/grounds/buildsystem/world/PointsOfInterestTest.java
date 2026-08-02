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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PointsOfInterestTest {

    @TempDir
    Path world;

    @Test
    void a_marked_place_survives_a_reload_with_its_facing() throws IOException {
        Map<String, PointsOfInterest.Poi> pois = new TreeMap<>();
        // Yaw and pitch are not decoration: a spawn facing a wall is a bug report.
        pois.put("lobby.spawn", new PointsOfInterest.Poi(10.5, 64.0, -3.5, 90.0f, -12.5f));
        PointsOfInterest.write(world, pois);

        PointsOfInterest.Poi read = PointsOfInterest.get(world, "lobby.spawn");

        assertEquals(10.5, read.x());
        assertEquals(64.0, read.y());
        assertEquals(-3.5, read.z());
        assertEquals(90.0f, read.yaw());
        assertEquals(-12.5f, read.pitch());
    }

    /**
     * Inside the world folder is the whole point: the file travels in the bundle, so a version's
     * places are fixed when it is published rather than living in a second store to keep in step.
     */
    @Test
    void it_lives_inside_the_world_so_it_travels_with_the_map() throws IOException {
        PointsOfInterest.write(world, Map.of("a.b", new PointsOfInterest.Poi(0, 0, 0, 0, 0)));

        assertTrue(Files.isRegularFile(world.resolve("grounds").resolve("pois.json")));
        assertFalse(
                Files.exists(world.resolve("grounds").resolve("pois.json.tmp")),
                "the atomic-write scratch file must not be left in the bundle");
    }

    /** A broken file must not take the map with it — it reads empty and the next write replaces it. */
    @Test
    void a_corrupt_file_reads_as_empty() throws IOException {
        Files.createDirectories(world.resolve("grounds"));
        Files.writeString(world.resolve("grounds").resolve("pois.json"), "{ this is not json");

        assertTrue(PointsOfInterest.read(world).isEmpty());
        assertNull(PointsOfInterest.get(world, "anything"));
    }

    /**
     * Editing this file by hand is a supported way to work, and a typo in it must not cost the
     * points that are already in it: an unparseable file reads as empty, so writing over it would
     * replace every point with whichever one is being set.
     */
    @Test
    void refuses_to_overwrite_a_file_it_cannot_read() throws IOException {
        Files.createDirectories(world.resolve("grounds"));
        Path file = world.resolve("grounds").resolve("pois.json");
        Files.writeString(file, "{ \"pois\": { oops");

        IOException refused = assertThrows(
                IOException.class,
                () -> PointsOfInterest.write(world, Map.of("a.b", new PointsOfInterest.Poi(0, 0, 0, 0, 0))));

        assertTrue(refused.getMessage().contains("not valid JSON"), refused.getMessage());
        // Untouched, so a builder can go and fix their edit.
        assertEquals("{ \"pois\": { oops", Files.readString(file));
    }

    @Test
    void a_hand_written_file_is_read_as_it_stands() throws IOException {
        Files.createDirectories(world.resolve("grounds"));
        Files.writeString(
                world.resolve("grounds").resolve("pois.json"),
                "{\"format\":1,\"pois\":{\"red.spawn\":{\"x\":1.5,\"y\":64.0,\"z\":2.5,\"yaw\":90.0,\"pitch\":0.0}}}");

        PointsOfInterest.Poi read = PointsOfInterest.get(world, "red.spawn");

        assertEquals(1.5, read.x());
        assertEquals(90.0f, read.yaw());
    }

    @Test
    void names_are_dotted_and_lowercase_so_a_gamemode_can_group_by_prefix() {
        assertTrue(PointsOfInterest.isValidName("lobby.spawn"));
        assertTrue(PointsOfInterest.isValidName("team.red.bed"));
        assertTrue(PointsOfInterest.isValidName("generator.diamond-1"));
        assertFalse(PointsOfInterest.isValidName("Lobby.Spawn"));
        assertFalse(PointsOfInterest.isValidName("team..red"));
        assertFalse(PointsOfInterest.isValidName(""));
    }

    @Test
    void what_a_builder_types_becomes_a_valid_name() {
        assertEquals("team.red.spawn", PointsOfInterest.normaliseName("Team_Red_Spawn"));
        assertEquals("lobby.spawn", PointsOfInterest.normaliseName("Lobby Spawn"));
    }
}
