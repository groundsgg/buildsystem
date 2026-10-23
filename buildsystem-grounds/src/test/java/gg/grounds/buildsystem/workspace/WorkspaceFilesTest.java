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
package gg.grounds.buildsystem.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFilesTest {

    @TempDir
    Path root;

    @Test
    void collects_worlds_plugin_data_and_tool_configuration_relative_to_the_server_root() throws IOException {
        write("world/level.dat");
        write("world/region/r.0.0.mca");
        write("world/playerdata/builder.dat");
        write("world/session.lock");
        write("world/dimensions/minecraft/crater/level.dat");
        write("world/dimensions/minecraft/crater/region/r.-1.0.mca");
        write("world/dimensions/minecraft/crater/stats/builder.json");
        write("plugins/BuildSystem/worlds.yml");
        write("plugins/BuildSystem/backups/crater.zip");
        write("plugins/GroundsMaps/links.yml");
        write("plugins/GroundsMaps/config.yml");
        write("plugins/FastAsyncWorldEdit/worldedit-config.yml");
        write("plugins/FastAsyncWorldEdit/schematics/tower/top.schem");
        write("plugins/FastAsyncWorldEdit/history/1.bd");
        write("plugins/BuildSystem.jar");

        Path crater = root.resolve("world/dimensions/minecraft/crater");
        var files = WorkspaceFiles.collect(
                root, List.of(root.resolve("world"), crater, crater, root.resolve("missing")), logger());

        assertEquals(
                Set.of(
                        "world/level.dat",
                        "world/region/r.0.0.mca",
                        "world/dimensions/minecraft/crater/level.dat",
                        "world/dimensions/minecraft/crater/region/r.-1.0.mca",
                        "plugins/BuildSystem/worlds.yml",
                        "plugins/GroundsMaps/links.yml",
                        "plugins/FastAsyncWorldEdit/worldedit-config.yml",
                        "plugins/FastAsyncWorldEdit/schematics/tower/top.schem"),
                files.keySet());
        assertEquals(root.resolve("world/level.dat").toAbsolutePath(), files.get("world/level.dat"));
    }

    @Test
    void walking_the_main_world_alone_does_not_pull_in_the_worlds_nested_under_it() throws IOException {
        write("world/level.dat");
        write("world/dimensions/minecraft/crater/level.dat");

        var files = WorkspaceFiles.collect(root, List.of(root.resolve("world")), logger());

        assertEquals(Set.of("world/level.dat"), files.keySet());
    }

    @Test
    void skips_files_whose_names_the_registry_would_refuse_and_folders_outside_the_root(@TempDir Path elsewhere)
            throws IOException {
        write("world/level.dat");
        write("world/data/my map.dat");
        Files.createDirectories(elsewhere.resolve("region"));
        Files.writeString(elsewhere.resolve("region/r.0.0.mca"), "x");

        var files = WorkspaceFiles.collect(root, List.of(root.resolve("world"), elsewhere), logger());

        assertEquals(Set.of("world/level.dat"), files.keySet());
    }

    @Test
    void path_rule_matches_the_registry() {
        assertTrue(WorkspaceFiles.isValidPath("world/dimensions/minecraft/crater/region/r.-1.0.mca"));
        assertFalse(WorkspaceFiles.isValidPath("world/../etc/passwd"));
        assertFalse(WorkspaceFiles.isValidPath("world//level.dat"));
        assertFalse(WorkspaceFiles.isValidPath("./world/level.dat"));
        assertFalse(WorkspaceFiles.isValidPath("world/level.dat/"));
        assertFalse(WorkspaceFiles.isValidPath("world/my map.dat"));
        assertFalse(WorkspaceFiles.isValidPath("x".repeat(513)));
    }

    private void write(String path) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, path);
    }

    private static Logger logger() {
        return Logger.getLogger(WorkspaceFilesTest.class.getName());
    }
}
