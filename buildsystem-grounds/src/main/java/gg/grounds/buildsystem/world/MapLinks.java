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

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Which map a build world belongs to.
 *
 * <p>Kept in this plugin's own file rather than in BuildSystem's world data. That was the first
 * attempt and it cannot work: {@code WorldDataKey.of} is public, but {@code WorldDataImpl}
 * registers its properties at construction and throws {@code Unknown world data key} for anything
 * a third party invents. The API offers a key type it will not accept.
 *
 * <p>Entries are keyed by the world's <strong>UUID</strong>, not its name, so renaming a world in
 * the navigator cannot separate it from its map — which was the whole reason to prefer BuildSystem's
 * storage in the first place.
 */
@NullMarked
public final class MapLinks {

    private final File file;
    private final YamlConfiguration links;

    public MapLinks(File dataFolder) {
        this.file = new File(dataFolder, "links.yml");
        this.links = YamlConfiguration.loadConfiguration(file);
    }

    public @Nullable String addressOf(UUID world) {
        String address = links.getString(world + ".address");
        return address == null || address.isBlank() ? null : address;
    }

    /** The version this world was built from: provenance for the next push, not a lock. */
    public @Nullable Integer baseVersionOf(UUID world) {
        int version = links.getInt(world + ".base-version", -1);
        return version < 0 ? null : version;
    }

    public void link(UUID world, String worldName, String address, @Nullable Integer baseVersion) throws IOException {
        String id = world.toString();
        links.set(id + ".address", address);
        links.set(id + ".base-version", baseVersion == null ? -1 : baseVersion);
        // Written for a human reading the file after a rename, never read back.
        links.set(id + ".world", worldName);
        save();
    }

    private void save() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        links.save(file);
    }
}
