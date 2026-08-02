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
package gg.grounds.buildsystem.registry;

import com.google.gson.JsonObject;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** One map in the registry, as much of it as the build server cares about. */
@NullMarked
public record MapSummary(
        String address, String displayName, String kind, boolean stateful, @Nullable String forkedFrom) {

    static MapSummary from(JsonObject json) {
        return new MapSummary(
                json.get("address").getAsString(),
                json.get("displayName").getAsString(),
                json.get("kind").getAsString(),
                json.get("stateful").getAsBoolean(),
                json.get("forkedFrom").isJsonNull() ? null : json.get("forkedFrom").getAsString());
    }

    /** `bedwars/4x4-baumhaus` -> `bedwars`. Split at the LAST slash: `u/hendrik/treehouse`. */
    public String namespace() {
        int slash = address.lastIndexOf('/');
        return slash < 0 ? "" : address.substring(0, slash);
    }
}
