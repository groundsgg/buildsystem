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

/** An opened, not yet committed snapshot, and the blobs the registry still needs for it. */
@NullMarked
public record WorkspaceSnapshot(long snapshot, List<Missing> missing) {

    /** A blob the bucket does not have, and the presigned URL to PUT it to. Never log the URL. */
    public record Missing(String sha256, String url) {}

    static WorkspaceSnapshot from(JsonObject json) {
        List<Missing> missing = new ArrayList<>();
        if (json.has("missing")) {
            for (JsonElement element : json.getAsJsonArray("missing")) {
                JsonObject blob = element.getAsJsonObject();
                missing.add(new Missing(
                        blob.get("sha256").getAsString(), blob.get("url").getAsString()));
            }
        }
        return new WorkspaceSnapshot(json.get("snapshot").getAsLong(), List.copyOf(missing));
    }
}
