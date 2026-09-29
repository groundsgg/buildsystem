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
package gg.grounds.buildsystem.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.luben.zstd.ZstdOutputStream;
import gg.grounds.buildsystem.registry.RegistryException;
import gg.grounds.buildsystem.world.WorldArchive;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldImportArchiveTest {

    private static final WorldImportArchive.Limits ROOMY = new WorldImportArchive.Limits(10_000_000, 1_000);

    @TempDir
    Path tmp;

    private Path zip(String... names) throws IOException {
        Path file = tmp.resolve("world.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write(("content of " + name).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    private List<String> written(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /**
     * A typical download: a wrapper folder, a full save with nether and end, a datapack and some
     * clutter. Only the overworld's map files may come out of it.
     */
    @Test
    void keeps_only_the_overworld_map_files_and_drops_datapacks() throws Exception {
        Path archive = zip(
                "Crater Map/level.dat",
                "Crater Map/region/r.0.0.mca",
                "Crater Map/region/r.-1.0.mca",
                "Crater Map/entities/r.0.0.mca",
                "Crater Map/data/raids.dat",
                "Crater Map/grounds/setup.json",
                "Crater Map/datapacks/pwn/data/pwn/function/load.mcfunction",
                "Crater Map/datapacks/pwn/data/minecraft/tags/function/load.json",
                "Crater Map/DIM-1/region/r.0.0.mca",
                "Crater Map/playerdata/1234.dat",
                "Crater Map/session.lock",
                "Crater Map/icon.png",
                "readme.txt");
        Path target = tmp.resolve("staging");

        WorldImportArchive.Result result = WorldImportArchive.extract(archive, target, ROOMY);

        assertEquals(
                List.of(
                        "data/raids.dat",
                        "entities/r.0.0.mca",
                        "grounds/setup.json",
                        "level.dat",
                        "region/r.-1.0.mca",
                        "region/r.0.0.mca"),
                written(target));
        assertTrue(result.droppedDatapacks());
        assertEquals(7, result.skipped());
    }

    @Test
    void refuses_an_entry_that_climbs_out_of_the_world() throws Exception {
        Path archive = zip("region/r.0.0.mca", "../../../home/paper/.bashrc");
        assertThrows(RegistryException.class, () -> WorldImportArchive.extract(archive, tmp.resolve("s"), ROOMY));
    }

    @Test
    void refuses_an_archive_without_region_files() throws Exception {
        Path archive = zip("level.dat", "datapacks/x/pack.mcmeta");
        assertThrows(RegistryException.class, () -> WorldImportArchive.extract(archive, tmp.resolve("s"), ROOMY));
    }

    @Test
    void refuses_two_worlds_side_by_side() throws Exception {
        Path archive = zip("a/region/r.0.0.mca", "b/region/r.0.0.mca");
        assertThrows(RegistryException.class, () -> WorldImportArchive.extract(archive, tmp.resolve("s"), ROOMY));
    }

    /** A zip bomb is small on the wire; the unpacked limit is what protects the disk. */
    @Test
    void stops_when_the_world_unpacks_past_the_limit() throws Exception {
        Path archive = tmp.resolve("bomb.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("region/r.0.0.mca"));
            out.write(new byte[2_000_000]);
            out.closeEntry();
        }
        WorldImportArchive.Limits tight = new WorldImportArchive.Limits(1_000_000, 1_000);
        assertThrows(RegistryException.class, () -> WorldImportArchive.extract(archive, tmp.resolve("s"), tight));
    }

    @Test
    void stops_when_the_archive_has_too_many_entries() throws Exception {
        Path archive = zip("region/r.0.0.mca", "data/a.dat", "data/b.dat", "data/c.dat");
        WorldImportArchive.Limits tight = new WorldImportArchive.Limits(10_000_000, 3);
        assertThrows(RegistryException.class, () -> WorldImportArchive.extract(archive, tmp.resolve("s"), tight));
    }

    @Test
    void refuses_something_that_is_not_an_archive() throws Exception {
        Path html = tmp.resolve("page.zip");
        Files.writeString(html, "<!doctype html><title>Download</title>");
        assertThrows(RegistryException.class, () -> WorldImportArchive.extract(html, tmp.resolve("s"), ROOMY));
    }

    /** A registry bundle, as `/map push` produces it, imports like any other archive. */
    @Test
    void imports_a_tar_zst_bundle() throws Exception {
        Path world = tmp.resolve("world");
        for (String name : List.of("level.dat", "region/r.0.0.mca", "grounds/pois.json")) {
            Path file = world.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, name);
        }
        WorldArchive.Archive bundle = WorldArchive.pack(world, tmp.resolve("bundle.tar.zst"));
        Path target = tmp.resolve("staging");

        WorldImportArchive.extract(bundle.file(), target, ROOMY);

        assertEquals(List.of("grounds/pois.json", "level.dat", "region/r.0.0.mca"), written(target));
    }

    /** Links are never recreated: a symlink to /etc would turn a later write into an escape. */
    @Test
    void never_writes_a_symlink_from_a_tar() throws Exception {
        Path archive = tmp.resolve("links.tar.zst");
        try (OutputStream raw = Files.newOutputStream(archive);
                ZstdOutputStream zstd = new ZstdOutputStream(raw);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
            TarArchiveEntry link = new TarArchiveEntry("data", TarArchiveEntry.LF_SYMLINK);
            link.setLinkName("/etc");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
            byte[] region = "region".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry file = new TarArchiveEntry("region/r.0.0.mca");
            file.setSize(region.length);
            tar.putArchiveEntry(file);
            tar.write(region);
            tar.closeArchiveEntry();
        }
        Path target = tmp.resolve("staging");

        WorldImportArchive.extract(archive, target, ROOMY);

        assertFalse(Files.isSymbolicLink(target.resolve("data")));
        assertEquals(List.of("region/r.0.0.mca"), written(target));
    }
}
