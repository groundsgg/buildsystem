/*
 * Copyright (c) 2026, Grounds
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

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jspecify.annotations.NullMarked;

/**
 * Where Paper keeps world directories — mirrored from BuildSystem's {@code FileUtils} because
 * GroundsMaps only depends on the published API, not on core.
 *
 * <p>Since Paper 26.1 every Bukkit world lives under
 * {@code <level-name>/dimensions/minecraft/<name>}.
 */
@NullMarked
public final class WorldFolders {

    private static final Set<String> VANILLA_DIMENSIONS = Set.of("overworld", "the_nether", "the_end");

    private WorldFolders() {}

    public static File forName(String worldName) {
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            return loaded.getWorldFolder();
        }
        File dimension = new File(dimensionsRoot(), worldName.toLowerCase(Locale.ROOT));
        if (dimension.isDirectory()) {
            return dimension;
        }
        File legacy = new File(Bukkit.getWorldContainer(), worldName);
        return legacy.isDirectory() ? legacy : dimension;
    }

    public static File dimensionsRoot() {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds == null || worlds.isEmpty()) {
            return Bukkit.getWorldContainer();
        }
        String levelName = worlds.getFirst().getName();
        return new File(
                Bukkit.getWorldContainer(), levelName + File.separator + "dimensions" + File.separator + "minecraft");
    }

    public static boolean isImportableWorldDirectory(File folder) {
        if (!folder.isDirectory()
                || VANILLA_DIMENSIONS.contains(folder.getName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return new File(folder, "region").isDirectory() || new File(folder, "level.dat").isFile();
    }
}
