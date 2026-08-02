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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.luben.zstd.ZstdInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldArchiveTest {

    @TempDir
    Path tmp;

    /**
     * The registry keys bundles by digest, so an unchanged world has to produce an unchanged
     * archive. Without this, every push stores another copy of identical bytes and "nothing
     * changed" is indistinguishable from a real edit.
     */
    @Test
    void packs_the_same_world_to_the_same_digest() throws IOException {
        Path world = world("region/r.0.0.mca", "level.dat", "data/raids.dat");

        WorldArchive.Archive first = WorldArchive.pack(world, tmp.resolve("a.tar.zst"));
        // Touching every file: mtime must not reach the archive, or two build servers that
        // checked out the same world at different times would disagree about its digest.
        for (Path file : List.of(world.resolve("level.dat"), world.resolve("region/r.0.0.mca"))) {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        }
        WorldArchive.Archive second = WorldArchive.pack(world, tmp.resolve("b.tar.zst"));

        assertEquals(first.sha256(), second.sha256());
        assertEquals(first.sizeBytes(), second.sizeBytes());
    }

    @Test
    void a_changed_block_changes_the_digest() throws IOException {
        Path world = world("region/r.0.0.mca", "level.dat");
        WorldArchive.Archive before = WorldArchive.pack(world, tmp.resolve("a.tar.zst"));

        Files.writeString(world.resolve("region/r.0.0.mca"), "a builder placed a block");
        WorldArchive.Archive after = WorldArchive.pack(world, tmp.resolve("b.tar.zst"));

        assertNotEquals(before.sha256(), after.sha256());
    }

    /**
     * A map is a place, not the people who visited it. Player state is both a size problem and a
     * privacy one: these files would otherwise be copied to every game server that loads the map.
     */
    @Test
    void leaves_player_state_and_the_lock_file_out() throws IOException {
        Path world = world(
                "region/r.0.0.mca",
                "level.dat",
                "playerdata/0000-0000.dat",
                "stats/0000-0000.json",
                "advancements/0000-0000.json",
                "session.lock",
                "uid.dat");

        List<String> entries =
                entriesOf(WorldArchive.pack(world, tmp.resolve("a.tar.zst")).file());

        assertTrue(entries.contains("region/r.0.0.mca"));
        assertTrue(entries.contains("level.dat"));
        assertFalse(entries.stream().anyMatch(entry -> entry.startsWith("playerdata/")), entries.toString());
        assertFalse(entries.stream().anyMatch(entry -> entry.startsWith("stats/")), entries.toString());
        assertFalse(entries.stream().anyMatch(entry -> entry.startsWith("advancements/")), entries.toString());
        assertFalse(entries.contains("session.lock"), entries.toString());
        assertFalse(entries.contains("uid.dat"), entries.toString());
    }

    private Path world(String... files) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("world-" + files.length));
        for (String file : files) {
            Path path = root.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "contents of " + file);
        }
        return root;
    }

    private static List<String> entriesOf(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = Files.newInputStream(archive);
                ZstdInputStream zstd = new ZstdInputStream(in);
                TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
