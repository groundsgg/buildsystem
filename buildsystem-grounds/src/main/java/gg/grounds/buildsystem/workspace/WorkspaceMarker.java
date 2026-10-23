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
package gg.grounds.buildsystem.workspace;

import gg.grounds.buildsystem.registry.WorkspaceHead;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What the image's start wrapper restored before Paper started, read from
 * {@code <server root>/.workspace-head}: a snapshot number, or {@code none} when the registry had
 * nothing for this server.
 *
 * <p>The wrapper writes it only once every file is verified and in place, so its presence is the
 * proof that the worlds on disk are the registry's and not an empty boot. Syncing without that
 * proof is exactly how an empty server would replace a good snapshot.
 */
@NullMarked
public final class WorkspaceMarker {

    public static final String FILE_NAME = ".workspace-head";

    private WorkspaceMarker() {}

    /**
     * The snapshot the worlds on disk were restored from, or {@code null} for {@code none}.
     *
     * @throws IOException when the marker is missing or holds anything else
     */
    public static @Nullable Long read(Path serverRoot) throws IOException {
        Path marker = serverRoot.resolve(FILE_NAME);
        String content;
        try {
            content = Files.readString(marker, StandardCharsets.UTF_8).trim();
        } catch (NoSuchFileException e) {
            throw new IOException(marker + " is missing, so nothing proves the worlds on disk were restored", e);
        }
        if (content.equals("none")) {
            return null;
        }
        try {
            long snapshot = Long.parseLong(content);
            if (snapshot > 0) {
                return snapshot;
            }
        } catch (NumberFormatException ignored) {
            // Reported below with the content, which is more useful than the parser's message.
        }
        throw new IOException(marker + " holds '" + content + "', not a snapshot number or 'none'");
    }

    /**
     * Why the marker and the registry disagree, or {@code null} when they agree. A disagreement
     * means the registry moved on since the restore — another writer, or a restore that never
     * happened — and either way this server must not sync.
     */
    public static @Nullable String disagreement(@Nullable Long restored, @Nullable WorkspaceHead head) {
        if (restored == null && head == null) {
            return null;
        }
        if (restored == null) {
            return "the registry holds snapshot " + head.snapshot() + " but this server restored none";
        }
        if (head == null) {
            return "this server restored snapshot " + restored + " but the registry holds none";
        }
        if (head.snapshot() != restored) {
            return "this server restored snapshot " + restored + " but the registry's head is " + head.snapshot();
        }
        return null;
    }
}
