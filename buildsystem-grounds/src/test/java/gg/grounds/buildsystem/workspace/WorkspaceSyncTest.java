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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gg.grounds.buildsystem.registry.RegistryClient;
import gg.grounds.buildsystem.registry.WorkspaceConflictException;
import gg.grounds.buildsystem.registry.WorkspaceEntry;
import gg.grounds.buildsystem.registry.WorkspaceHead;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSyncTest {

    private static final Instant SETTLED = Instant.now().minus(Duration.ofMinutes(5));

    @TempDir
    Path root;

    private final FakeRegistry registry = new FakeRegistry();
    private HttpServer server;
    private RegistryClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", registry::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new RegistryClient(base, base + "/token", "buildserver-maps", "secret");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void first_pass_uploads_everything_then_an_idle_pass_asks_nothing() throws Exception {
        write("world/level.dat", "level");
        write("world/region/r.0.0.mca", "region");
        write("plugins/BuildSystem/worlds.yml", "worlds");
        WorkspaceSync sync = sync(null);

        assertEquals(WorkspaceSync.Outcome.COMMITTED, sync.pass(worlds(), Duration.ZERO));
        assertEquals(1L, sync.base());
        assertEquals(3, registry.blobs.size());
        assertEquals(
                Set.of("world/level.dat", "world/region/r.0.0.mca", "plugins/BuildSystem/worlds.yml"),
                registry.headFiles().keySet());

        registry.requests.clear();
        assertEquals(WorkspaceSync.Outcome.UNCHANGED, sync.pass(worlds(), Duration.ZERO));
        assertEquals(List.of(), registry.requests);
    }

    @Test
    void a_changed_region_is_the_only_upload() throws Exception {
        write("world/level.dat", "level");
        write("world/region/r.0.0.mca", "region");
        WorkspaceSync sync = sync(null);
        sync.pass(worlds(), Duration.ZERO);

        write("world/region/r.0.0.mca", "region, edited");
        registry.puts.clear();

        assertEquals(WorkspaceSync.Outcome.COMMITTED, sync.pass(worlds(), Duration.ZERO));
        assertEquals(1, registry.puts.size());
        assertEquals(2L, sync.base());
        // The base sent is the head this server committed, which is what lets the registry spot a second writer.
        assertEquals(1L, registry.lastBase);
    }

    @Test
    void a_file_rewritten_during_its_upload_abandons_the_snapshot() throws Exception {
        write("world/level.dat", "level");
        Path region = write("world/region/r.0.0.mca", "region");
        registry.onPut = () -> {
            try {
                Files.writeString(region, "region, half written");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        WorkspaceSync sync = sync(null);

        assertEquals(WorkspaceSync.Outcome.ABANDONED, sync.pass(worlds(), Duration.ZERO));
        assertNull(registry.head);
        assertNull(sync.base());

        registry.onPut = () -> {};
        settle(region);
        assertEquals(WorkspaceSync.Outcome.COMMITTED, sync.pass(worlds(), Duration.ZERO));
        assertEquals(WorkspaceSync.sha256(region), registry.headFiles().get("world/region/r.0.0.mca"));
    }

    @Test
    void a_file_still_being_written_keeps_the_entry_it_had_in_the_head() throws Exception {
        write("world/level.dat", "level");
        Path region = write("world/region/r.0.0.mca", "region");
        WorkspaceSync sync = sync(null);
        sync.pass(worlds(), Duration.ZERO);
        String committed = registry.headFiles().get("world/region/r.0.0.mca");

        Files.writeString(region, "being written");
        Files.setLastModifiedTime(region, FileTime.from(Instant.now()));
        write("world/data/new.dat", "new");
        Path fresh = write("world/data/fresher.dat", "fresher");
        Files.setLastModifiedTime(fresh, FileTime.from(Instant.now()));

        assertEquals(WorkspaceSync.Outcome.COMMITTED, sync.pass(worlds(), Duration.ofSeconds(5)));
        Map<String, String> head = registry.headFiles();
        assertEquals(committed, head.get("world/region/r.0.0.mca"));
        assertTrue(head.containsKey("world/data/new.dat"));
        // Too fresh and never committed: left for the next pass rather than read half-written.
        assertTrue(!head.containsKey("world/data/fresher.dat"));
    }

    @Test
    void another_writer_moving_the_head_is_a_conflict() throws Exception {
        write("world/level.dat", "level");
        WorkspaceSync sync = sync(null);
        sync.pass(worlds(), Duration.ZERO);

        registry.head = 7L;
        write("world/level.dat", "level, edited");

        WorkspaceConflictException conflict =
                assertThrows(WorkspaceConflictException.class, () -> sync.pass(worlds(), Duration.ZERO));
        assertTrue(!conflict.shrinkRefused());
    }

    @Test
    void a_refused_shrink_is_retried_rather_than_stopping_the_loop() throws Exception {
        write("world/level.dat", "level");
        WorkspaceSync sync = sync(null);
        sync.pass(worlds(), Duration.ZERO);

        registry.refuseShrink = true;
        write("world/level.dat", "level, edited");
        assertEquals(WorkspaceSync.Outcome.SHRINK_REFUSED, sync.pass(worlds(), Duration.ZERO));

        sync.worldDeleted();
        assertEquals(WorkspaceSync.Outcome.COMMITTED, sync.pass(worlds(), Duration.ZERO));
    }

    @Test
    void a_manifest_over_the_registry_cap_is_never_sent() throws Exception {
        for (int i = 0; i <= WorkspaceSync.MAX_FILES; i++) {
            write("world/data/f" + i + ".dat", "");
        }
        registry.requests.clear();

        assertEquals(WorkspaceSync.Outcome.TOO_MANY_FILES, sync(null).pass(worlds(), Duration.ZERO));
        assertEquals(List.of(), registry.requests);
    }

    @Test
    void a_deleted_world_is_announced_as_a_shrink_once() throws Exception {
        write("world/level.dat", "level");
        WorkspaceSync sync = sync(null);
        sync.pass(worlds(), Duration.ZERO);

        sync.worldDeleted();
        write("world/level.dat", "level, edited");
        sync.pass(worlds(), Duration.ZERO);
        assertTrue(registry.lastShrink);

        write("world/level.dat", "level, edited again");
        sync.pass(worlds(), Duration.ZERO);
        assertTrue(!registry.lastShrink);
    }

    @Test
    void the_head_read_at_enable_is_the_base_and_its_entries_count_as_committed() throws Exception {
        Path level = write("world/level.dat", "level");
        registry.head = 5L;
        registry.blobs.add(WorkspaceSync.sha256(level));
        WorkspaceHead head = new WorkspaceHead(
                5, List.of(new WorkspaceEntry("world/level.dat", WorkspaceSync.sha256(level), Files.size(level))));
        registry.snapshots.put(5L, Map.of("world/level.dat", WorkspaceSync.sha256(level)));

        WorkspaceSync sync = sync(head);

        assertEquals(WorkspaceSync.Outcome.UNCHANGED, sync.pass(worlds(), Duration.ZERO));
        assertEquals(
                registry.head,
                client.workspaceHead(client.serviceAccount(), "stage-buildserver")
                        .snapshot());
    }

    @Test
    void head_is_null_only_on_404() throws Exception {
        assertNull(client.workspaceHead(client.serviceAccount(), "stage-buildserver"));
    }

    private WorkspaceSync sync(@Nullable WorkspaceHead head) {
        return new WorkspaceSync(
                client,
                client.serviceAccount(),
                "stage-buildserver",
                root,
                head,
                Logger.getLogger(WorkspaceSyncTest.class.getName()));
    }

    private List<Path> worlds() {
        return List.of(root.resolve("world"));
    }

    private Path write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        settle(file);
        return file;
    }

    /**
     * Distinct per write, so the size/mtime cache sees an edit even when the size is unchanged.
     * Counting backwards keeps every write in the past however many files a test writes.
     */
    private static int writes;

    private static void settle(Path file) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(SETTLED.minusSeconds(writes++)));
    }

    /** The registry's workspace endpoints, a token endpoint and a bucket, in memory. */
    private final class FakeRegistry {
        final Set<String> blobs = new HashSet<>();
        final List<String> puts = new ArrayList<>();
        final List<String> requests = new ArrayList<>();
        final Map<Long, Map<String, String>> snapshots = new HashMap<>();

        @Nullable Long head;

        @Nullable Long lastBase;

        boolean lastShrink;
        boolean refuseShrink;
        Runnable onPut = () -> {};
        private long next = 1;

        Map<String, String> headFiles() {
            return snapshots.get(head);
        }

        void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/token")) {
                json(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":300}");
                return;
            }
            requests.add(method + " " + path);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (method.equals("PUT") && path.startsWith("/blob/")) {
                onPut.run();
                String sha = path.substring("/blob/".length());
                puts.add(sha);
                blobs.add(sha);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (path.equals("/v1/workspaces/stage-buildserver/head")) {
                if (head == null) {
                    json(exchange, 404, "{\"status\":404,\"detail\":\"no snapshot\"}");
                } else {
                    json(exchange, 200, "{\"snapshot\":" + head + ",\"files\":[]}");
                }
            } else if (path.equals("/v1/workspaces/stage-buildserver/snapshots")) {
                JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                lastBase = request.get("base").isJsonNull()
                        ? null
                        : request.get("base").getAsLong();
                lastShrink = request.get("shrink").getAsBoolean();
                if (!Objects.equals(lastBase, head)) {
                    json(exchange, 409, "{\"status\":409,\"detail\":\"head moved\"}");
                    return;
                }
                if (refuseShrink && !lastShrink) {
                    json(exchange, 409, "{\"status\":409,\"detail\":\"refusing to shrink the working copy\"}");
                    return;
                }
                long snapshot = next++;
                Map<String, String> files = new HashMap<>();
                JsonArray missing = new JsonArray();
                Set<String> announced = new HashSet<>();
                for (JsonElement element : request.getAsJsonArray("files")) {
                    JsonObject file = element.getAsJsonObject();
                    String sha = file.get("sha256").getAsString();
                    files.put(file.get("path").getAsString(), sha);
                    if (!blobs.contains(sha) && announced.add(sha)) {
                        JsonObject blob = new JsonObject();
                        blob.addProperty("sha256", sha);
                        blob.addProperty(
                                "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/blob/" + sha);
                        missing.add(blob);
                    }
                }
                snapshots.put(snapshot, files);
                JsonObject response = new JsonObject();
                response.addProperty("snapshot", snapshot);
                response.add("missing", missing);
                json(exchange, 201, response.toString());
            } else if (path.matches("/v1/workspaces/stage-buildserver/snapshots/\\d+/commit")) {
                long snapshot = Long.parseLong(path.split("/")[5]);
                if (!blobs.containsAll(snapshots.get(snapshot).values())) {
                    json(exchange, 409, "{\"status\":409,\"detail\":\"blob missing\"}");
                    return;
                }
                head = snapshot;
                json(exchange, 200, "{\"head\":" + snapshot + "}");
            } else {
                json(exchange, 500, "{\"detail\":\"unexpected " + method + " " + path + "\"}");
            }
        }

        private static void json(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
