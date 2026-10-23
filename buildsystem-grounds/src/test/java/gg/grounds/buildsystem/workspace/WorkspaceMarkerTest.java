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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.grounds.buildsystem.registry.WorkspaceHead;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceMarkerTest {

    @TempDir
    Path root;

    @Test
    void reads_a_snapshot_number_or_none() throws IOException {
        Files.writeString(root.resolve(WorkspaceMarker.FILE_NAME), "42\n");
        assertEquals(42L, WorkspaceMarker.read(root));

        Files.writeString(root.resolve(WorkspaceMarker.FILE_NAME), "none\n");
        assertNull(WorkspaceMarker.read(root));
    }

    @Test
    void a_missing_or_garbled_marker_is_refused() throws IOException {
        IOException missing = assertThrows(IOException.class, () -> WorkspaceMarker.read(root));
        assertTrue(missing.getMessage().contains("missing"), missing.getMessage());

        for (String content : List.of("", "0", "-3", "latest", "42 43")) {
            Files.writeString(root.resolve(WorkspaceMarker.FILE_NAME), content);
            assertThrows(IOException.class, () -> WorkspaceMarker.read(root), content);
        }
    }

    @Test
    void marker_and_registry_head_must_agree() {
        WorkspaceHead head42 = new WorkspaceHead(42, List.of());

        assertNull(WorkspaceMarker.disagreement(null, null));
        assertNull(WorkspaceMarker.disagreement(42L, head42));
        // An empty boot while the registry holds worlds: the case that must never sync.
        assertNotNull(WorkspaceMarker.disagreement(null, head42));
        assertNotNull(WorkspaceMarker.disagreement(42L, null));
        assertNotNull(WorkspaceMarker.disagreement(41L, head42));
    }
}
