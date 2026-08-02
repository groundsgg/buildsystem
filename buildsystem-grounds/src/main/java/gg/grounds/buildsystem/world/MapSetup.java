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
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What this map is for: which gamemode, and how many teams.
 *
 * <p>Stored beside the points, inside the world at {@code grounds/setup.json}, for the same reason:
 * it travels in the bundle, so a published version carries its own shape. A gamemode loading the map
 * does not have to be told how many teams it has — the map says.
 */
@NullMarked
public final class MapSetup {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MapSetup() {}

    /**
     * @param teams the team colours this map is built for, in order. Colours rather than numbers
     *     because that is what a player sees — the red bed, the blue base — and `team3` is
     *     something a builder has to translate every time they walk into one.
     */
    public record Setup(String gamemode, List<String> teams) {}

    private static final class Document {
        int format = 1;
        @Nullable String gamemode;
        @Nullable List<String> teams;
    }

    public static Path fileIn(Path worldFolder) {
        return worldFolder.resolve("grounds").resolve("setup.json");
    }

    public static @Nullable Setup read(Path worldFolder) {
        Path file = fileIn(worldFolder);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Document document = GSON.fromJson(reader, Document.class);
            if (document == null || document.gamemode == null || document.gamemode.isBlank()) {
                return null;
            }
            return new Setup(document.gamemode, document.teams == null ? List.of() : List.copyOf(document.teams));
        } catch (IOException | JsonSyntaxException e) {
            // A broken file reads as "not set up" rather than taking the map down with it.
            return null;
        }
    }

    private static boolean isReadable(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            GSON.fromJson(reader, Document.class);
            return true;
        } catch (IOException | JsonSyntaxException e) {
            return false;
        }
    }

    public static void write(Path worldFolder, Setup setup) throws IOException {
        Path file = fileIn(worldFolder);
        // Same reason as the points: an unparseable file reads as "not set up", and writing over it
        // would quietly discard whatever a builder had edited by hand.
        if (Files.isRegularFile(file) && !isReadable(file)) {
            throw new IOException("setup.json is not valid JSON. Fix or delete it — refusing to"
                    + " overwrite what is in it.");
        }
        Files.createDirectories(file.getParent());
        Document document = new Document();
        document.gamemode = setup.gamemode();
        document.teams = List.copyOf(setup.teams());
        // Same atomic write as the points: half a file is a map that claims a shape it does not
        // have, and a gamemode would find out at match start.
        Path temporary = file.resolveSibling("setup.json.tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(document, writer);
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
