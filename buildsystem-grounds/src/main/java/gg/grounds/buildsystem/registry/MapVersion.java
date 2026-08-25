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
import java.util.ArrayList;
import java.util.List;
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
        @Nullable String note,
        @Nullable Scene scene) {

    /** Kept for callers that construct versions without registry derivation details. */
    public MapVersion(
            int version,
            String state,
            @Nullable String bundleSha256,
            @Nullable Integer parentVersion,
            long sizeBytes,
            @Nullable String note) {
        this(version, state, bundleSha256, parentVersion, sizeBytes, note, null);
    }

    static MapVersion from(JsonObject json) {
        return new MapVersion(
                json.get("version").getAsInt(),
                json.get("state").getAsString(),
                optionalString(json, "bundleSha256"),
                optionalInt(json, "parentVersion"),
                optionalLong(json, "sizeBytes"),
                optionalString(json, "note"),
                scene(json));
    }

    private static @Nullable String optionalString(JsonObject json, String field) {
        return !json.has(field) || json.get(field).isJsonNull()
                ? null
                : json.get(field).getAsString();
    }

    private static @Nullable Integer optionalInt(JsonObject json, String field) {
        return !json.has(field) || json.get(field).isJsonNull()
                ? null
                : json.get(field).getAsInt();
    }

    private static long optionalLong(JsonObject json, String field) {
        return !json.has(field) || json.get(field).isJsonNull()
                ? 0L
                : json.get(field).getAsLong();
    }

    private static @Nullable Scene scene(JsonObject json) {
        if (!json.has("scene") || !json.get("scene").isJsonObject()) {
            return null;
        }
        JsonObject source = json.getAsJsonObject("scene");
        List<SceneProblem> problems = new ArrayList<>();
        if (source.has("problems") && source.get("problems").isJsonArray()) {
            for (var element : source.getAsJsonArray("problems")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject problem = element.getAsJsonObject();
                problems.add(new SceneProblem(
                        optionalString(problem, "code"),
                        optionalString(problem, "path"),
                        optionalString(problem, "qualifiedIdentity"),
                        optionalString(problem, "message")));
            }
        }
        return new Scene(optionalString(source, "status"), List.copyOf(problems));
    }

    public boolean isPublished() {
        return "PUBLISHED".equals(state);
    }

    /** Optional derivation diagnostics supplied by newer registry responses. */
    public record Scene(@Nullable String status, List<SceneProblem> problems) {}

    /** One ordered scene validation problem. All fields are optional for forward compatibility. */
    public record SceneProblem(
            @Nullable String code,
            @Nullable String path,
            @Nullable String qualifiedIdentity,
            @Nullable String message) {}
}
