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
package gg.grounds.buildsystem.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** One map entry inside {@code pins/<env>.json}. */
@NullMarked
public record PinnedEntry(
        int version,
        String bundleSha256,
        @Nullable String bundleUrl,
        @Nullable Long sizeBytes) {

    static PinnedEntry from(JsonObject json) {
        Long size = null;
        if (json.has("sizeBytes") && !json.get("sizeBytes").isJsonNull()) {
            size = json.get("sizeBytes").getAsLong();
        }
        String url = null;
        if (json.has("bundleUrl") && !json.get("bundleUrl").isJsonNull()) {
            url = json.get("bundleUrl").getAsString();
        }
        return new PinnedEntry(
                json.get("version").getAsInt(), json.get("bundleSha256").getAsString(), url, size);
    }

    static @Nullable PinnedEntry find(String pinJson, String address) {
        JsonObject root = JsonParser.parseString(pinJson).getAsJsonObject();
        JsonElement maps = root.get("maps");
        if (maps == null || !maps.isJsonObject()) {
            return null;
        }
        JsonElement entry = maps.getAsJsonObject().get(address);
        if (entry == null || !entry.isJsonObject()) {
            return null;
        }
        return from(entry.getAsJsonObject());
    }
}
