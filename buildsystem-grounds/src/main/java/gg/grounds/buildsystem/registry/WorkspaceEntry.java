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

/**
 * One file of a build server's working copy: where it sits relative to the server root, and the
 * bytes it holds, named by their SHA-256.
 */
@NullMarked
public record WorkspaceEntry(String path, String sha256, long size) {

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("path", path);
        json.addProperty("sha256", sha256);
        json.addProperty("size", size);
        return json;
    }

    static WorkspaceEntry from(JsonObject json) {
        return new WorkspaceEntry(
                json.get("path").getAsString(),
                json.get("sha256").getAsString(),
                json.get("size").getAsLong());
    }
}
