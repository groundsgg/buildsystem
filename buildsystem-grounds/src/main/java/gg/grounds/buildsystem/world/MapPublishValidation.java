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

import com.github.luben.zstd.ZstdInputStream;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Checks that a map has the minimum information required to publish it. */
@NullMarked
public final class MapPublishValidation {

    private MapPublishValidation() {}

    /** Returns the user-facing publishing problem, or null when publishing may continue. */
    public static @Nullable String problem(Path worldFolder) {
        return problem(MapSetup.read(worldFolder), poiDocument(PointsOfInterest.fileIn(worldFolder)));
    }

    /** Returns the user-facing publishing problem for the exact archive that will be uploaded. */
    public static @Nullable String problem(WorldArchive.Archive archive) {
        try (InputStream in = Files.newInputStream(archive.file());
                ZstdInputStream zstd = new ZstdInputStream(in);
                TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
            byte[] setup = null;
            byte[] pois = null;
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.getName().equals("grounds/setup.json")) {
                    setup = tar.readAllBytes();
                } else if (entry.getName().equals("grounds/pois.json")) {
                    pois = tar.readAllBytes();
                }
            }
            return problem(
                    setup == null ? null : MapSetup.read(new StringReader(new String(setup, StandardCharsets.UTF_8))),
                    pois == null ? null : poiDocument(new StringReader(new String(pois, StandardCharsets.UTF_8))));
        } catch (IOException e) {
            return null;
        }
    }

    private static @Nullable String problem(MapSetup.@Nullable Setup setup, @Nullable JsonElement pois) {
        if (setup == null || !setup.gamemode().equalsIgnoreCase("lobby")) {
            return null;
        }
        if (hasUsableSpawn(pois)) {
            return null;
        }
        return "This lobby has no spawn. Stand where players should land and run /ms spawn.";
    }

    private static @Nullable JsonElement poiDocument(Path points) {
        if (!Files.isRegularFile(points)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(points, StandardCharsets.UTF_8)) {
            return poiDocument(reader);
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    private static @Nullable JsonElement poiDocument(Reader reader) {
        try {
            return JsonParser.parseReader(reader);
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    private static boolean hasUsableSpawn(@Nullable JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return false;
        }
        try {
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
        } catch (IllegalStateException | NumberFormatException e) {
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
