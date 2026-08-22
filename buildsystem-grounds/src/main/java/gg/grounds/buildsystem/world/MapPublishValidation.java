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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Checks that a map has the minimum information required to publish it. */
@NullMarked
public final class MapPublishValidation {

    private MapPublishValidation() {}

    /** Returns the user-facing publishing problem, or null when publishing may continue. */
    public static @Nullable String problem(Path worldFolder) {
        MapSetup.Setup setup = MapSetup.read(worldFolder);
        if (setup == null || !setup.gamemode().equalsIgnoreCase("lobby")) {
            return null;
        }
        if (hasUsableSpawn(worldFolder)) {
            return null;
        }
        return "This lobby has no spawn. Stand where players should land and run /ms spawn.";
    }

    private static boolean hasUsableSpawn(Path worldFolder) {
        Path points = PointsOfInterest.fileIn(worldFolder);
        if (!Files.isRegularFile(points)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(points, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonElement pois = root.getAsJsonObject().get("pois");
            if (pois == null || !pois.isJsonObject()) {
                return false;
            }
            JsonElement spawn = pois.getAsJsonObject().get("spawn");
            return spawn != null
                    && spawn.isJsonObject()
                    && finiteDouble(spawn.getAsJsonObject(), "x")
                    && finiteDouble(spawn.getAsJsonObject(), "y")
                    && finiteDouble(spawn.getAsJsonObject(), "z")
                    && finiteFloat(spawn.getAsJsonObject(), "yaw")
                    && finiteFloat(spawn.getAsJsonObject(), "pitch");
        } catch (IOException | JsonSyntaxException | IllegalStateException | NumberFormatException e) {
            return false;
        }
    }

    private static boolean finiteDouble(JsonObject point, String field) {
        JsonElement value = point.get(field);
        return value != null
                && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isNumber()
                && Double.isFinite(value.getAsDouble());
    }

    private static boolean finiteFloat(JsonObject point, String field) {
        JsonElement value = point.get(field);
        return value != null
                && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isNumber()
                && Float.isFinite(value.getAsFloat());
    }
}
