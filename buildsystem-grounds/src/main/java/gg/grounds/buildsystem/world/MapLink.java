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

import de.eintosti.buildsystem.api.world.BuildWorld;
import de.eintosti.buildsystem.api.world.data.WorldDataKey;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What ties a build world on this server to a map in the registry.
 *
 * <p>Stored as BuildSystem world data rather than in a file of our own, because BuildSystem already
 * persists, renames and deletes that alongside the world. A parallel store would drift the first
 * time somebody renames a world through the navigator, and the drift would only show up as a push
 * landing on the wrong map.
 *
 * <p>The builder never types any of this. It is written when a world is created from a map and read
 * when they push — the point is that {@code /map push} needs no arguments.
 */
@NullMarked
public final class MapLink {

    /** {@code bedwars/4x4-baumhaus}. Absent means this world is not a registry map yet. */
    public static final WorldDataKey<String> ADDRESS = WorldDataKey.of("grounds-map-address", String.class);

    /**
     * The version this world was created from, which becomes the {@code parentVersion} of the next
     * push. It is provenance, not a lock: two builders can both descend from version 3.
     */
    public static final WorldDataKey<String> BASE_VERSION = WorldDataKey.of("grounds-map-base-version", String.class);

    private MapLink() {}

    public static @Nullable String addressOf(BuildWorld world) {
        String address = world.getData().get(ADDRESS);
        return address == null || address.isBlank() ? null : address;
    }

    public static void link(BuildWorld world, String address, @Nullable Integer baseVersion) {
        world.getData().set(ADDRESS, address);
        // Stored as text because WorldDataKey is typed per value class and the world data file
        // round-trips strings without a converter; the parse is one place, right below.
        world.getData().set(BASE_VERSION, baseVersion == null ? "" : String.valueOf(baseVersion));
    }

    public static @Nullable Integer baseVersionOf(BuildWorld world) {
        String raw = world.getData().get(BASE_VERSION);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            // A hand-edited world data file should not stop a push; provenance is the only
            // thing lost, and the registry records the version it actually allocated.
            return null;
        }
    }
}
