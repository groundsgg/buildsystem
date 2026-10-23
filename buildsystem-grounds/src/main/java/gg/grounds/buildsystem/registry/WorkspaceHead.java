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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/** The committed snapshot a working copy stands at, and the files it names. */
@NullMarked
public record WorkspaceHead(long snapshot, List<WorkspaceEntry> files) {

    static WorkspaceHead from(JsonObject json) {
        List<WorkspaceEntry> files = new ArrayList<>();
        if (json.has("files")) {
            for (JsonElement file : json.getAsJsonArray("files")) {
                files.add(WorkspaceEntry.from(file.getAsJsonObject()));
            }
        }
        return new WorkspaceHead(json.get("snapshot").getAsLong(), List.copyOf(files));
    }
}
