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

/** One version of a map. `state` is the registry's word, not a second vocabulary. */
@NullMarked
public record MapVersion(
        int version,
        String state,
        @Nullable String bundleSha256,
        @Nullable Integer parentVersion,
        long sizeBytes,
        @Nullable String note) {

    static MapVersion from(JsonObject json) {
        return new MapVersion(
                json.get("version").getAsInt(),
                json.get("state").getAsString(),
                optionalString(json, "bundleSha256"),
                json.get("parentVersion").isJsonNull() ? null : json.get("parentVersion").getAsInt(),
                json.get("sizeBytes").isJsonNull() ? 0L : json.get("sizeBytes").getAsLong(),
                optionalString(json, "note"));
    }

    private static @Nullable String optionalString(JsonObject json, String field) {
        return json.get(field).isJsonNull() ? null : json.get(field).getAsString();
    }

    public boolean isPublished() {
        return "PUBLISHED".equals(state);
    }
}
