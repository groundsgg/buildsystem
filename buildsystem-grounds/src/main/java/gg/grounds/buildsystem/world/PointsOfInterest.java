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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The named places in a map: where players spawn, where a bed stands, where a generator ticks.
 *
 * <p><strong>Stored inside the world folder</strong>, at {@code grounds/pois.json}. That is the
 * whole design decision: the file travels in the bundle, so a version's points are fixed the moment
 * it is published and a gamemode reads them from the map it just loaded — not from a second store
 * that has to be kept in step with which version is live.
 *
 * <p>Names are dotted and lowercase — {@code lobby.spawn}, {@code team.red.bed} — so a gamemode can
 * ask for a prefix and get a group.
 */
@NullMarked
public final class PointsOfInterest {

    /** Dotted lowercase, e.g. `team.red.spawn`. A gamemode groups by prefix, so dots matter. */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9]+(?:[.-][a-z0-9]+)*$");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PointsOfInterest() {}

    /**
     * A place in the world, with the direction a player should face when put there. Yaw and pitch
     * are not decoration: a spawn that drops players facing a wall is a bug report.
     */
    public record Poi(double x, double y, double z, float yaw, float pitch) {}

    /** The file as it is written. Versioned so a reader can refuse a format it does not know. */
    private static final class Document {
        int format = 1;
        Map<String, Poi> pois = new TreeMap<>();
    }

    public static boolean isValidName(String name) {
        return NAME.matcher(name).matches();
    }

    public static String normaliseName(String typed) {
        return typed.toLowerCase(Locale.ROOT).replace('_', '.').replace(' ', '.');
    }

    public static Path fileIn(Path worldFolder) {
        return worldFolder.resolve("grounds").resolve("pois.json");
    }

    /** Never throws on a broken file: an unreadable one reads as empty and is replaced on write. */
    public static Map<String, Poi> read(Path worldFolder) {
        Path file = fileIn(worldFolder);
        if (!Files.isRegularFile(file)) {
            return new TreeMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Document document = GSON.fromJson(reader, Document.class);
            if (document == null || document.pois == null) {
                return new TreeMap<>();
            }
            return new TreeMap<>(document.pois);
        } catch (IOException | JsonSyntaxException e) {
            return new TreeMap<>();
        }
    }

    public static void write(Path worldFolder, Map<String, Poi> pois) throws IOException {
        Path file = fileIn(worldFolder);
        // A file that exists but will not parse reads as empty, so writing over it would replace
        // every point with whichever one is being set — silently. That is the whole risk of editing
        // this by hand, so the write refuses and says what to do instead.
        if (Files.isRegularFile(file) && !isReadable(file)) {
            throw new IOException(fileIn(worldFolder).getFileName()
                    + " is not valid JSON. Fix or delete it — refusing to overwrite what is in it.");
        }
        Files.createDirectories(file.getParent());
        Document document = new Document();
        document.pois = new TreeMap<>(pois);
        // Written beside the target and moved into place: a half-written file here is a map whose
        // spawns are gone, and the builder would not find out until a server loaded it.
        Path temporary = file.resolveSibling("pois.json.tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(document, writer);
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Whether the file on disk parses. Absent counts as readable: there is nothing to lose. */
    public static boolean isReadable(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            GSON.fromJson(reader, Document.class);
            return true;
        } catch (IOException | JsonSyntaxException e) {
            return false;
        }
    }

    public static @Nullable Poi get(Path worldFolder, String name) {
        return read(worldFolder).get(name);
    }
}
