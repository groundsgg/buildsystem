package gg.grounds.buildsystem.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryPreparationPollingTest {
    @TempDir
    Path tmp;

    @Test
    void reports_real_stages_and_bounded_heartbeats_until_ready() throws Exception {
        List<Reply> replies = new ArrayList<>();
        replies.add(new Reply(200, version("QUEUED")));
        replies.add(new Reply(200, version("DERIVING")));
        replies.add(new Reply(200, version("WAITING_FOR_LIGHT")));
        for (int i = 0; i < 18; i++) replies.add(new Reply(200, version("LIGHTING")));
        replies.add(new Reply(200, version("READY")));
        try (Harness h = new Harness(replies)) {
            List<RegistryClient.PushUpdate> updates = new ArrayList<>();
            MapVersion result = h.push(updates);
            assertTrue(result.isPublished());
            assertEquals("READY", result.preparation().stage());
            List<RegistryClient.PushPhase> changes = updates.stream()
                    .filter(u -> !u.heartbeat())
                    .map(RegistryClient.PushUpdate::phase)
                    .toList();
            assertEquals(
                    List.of(
                            RegistryClient.PushPhase.UPLOADING,
                            RegistryClient.PushPhase.SUBMITTING,
                            RegistryClient.PushPhase.QUEUED,
                            RegistryClient.PushPhase.DERIVING,
                            RegistryClient.PushPhase.WAITING_FOR_LIGHT,
                            RegistryClient.PushPhase.LIGHTING,
                            RegistryClient.PushPhase.READY),
                    changes);
            var heartbeats = updates.stream()
                    .filter(RegistryClient.PushUpdate::heartbeat)
                    .toList();
            assertTrue(heartbeats.size() >= 2);
            assertTrue(heartbeats.size() <= 3);
            assertTrue(heartbeats.stream()
                    .allMatch(u -> u.phase() == RegistryClient.PushPhase.LIGHTING && u.version() == 7));
            for (int i = 1; i < heartbeats.size(); i++)
                assertTrue(heartbeats
                                .get(i)
                                .elapsed()
                                .minus(heartbeats.get(i - 1).elapsed())
                                .compareTo(Duration.ofSeconds(30))
                        >= 0);
        }
    }

    @Test
    void waits_beyond_the_previous_ten_minute_budget() throws Exception {
        try (Harness h = new Harness(List.of(new Reply(200, version("LIGHTING")), new Reply(200, version("READY"))))) {
            h.advance = Duration.ofMinutes(15);
            assertEquals("READY", h.push(new ArrayList<>()).preparation().stage());
        }
    }

    @Test
    void status_outages_are_retried_but_permission_errors_are_not() throws Exception {
        try (Harness h = new Harness(
                List.of(new Reply(503, "{\"detail\":\"internal secret\"}"), new Reply(200, version("READY"))))) {
            List<RegistryClient.PushUpdate> updates = new ArrayList<>();
            h.push(updates);
            assertTrue(updates.stream().anyMatch(u -> u.phase() == RegistryClient.PushPhase.CONTACTING_REGISTRY));
            assertFalse(updates.toString().contains("secret"));
        }
        try (Harness h = new Harness(List.of(new Reply(403, "{\"detail\":\"permission denied\"}")))) {
            RegistryException error = assertThrows(RegistryException.class, () -> h.push(new ArrayList<>()));
            assertTrue(error.getMessage().contains("permission denied"));
            assertEquals(1, h.polls.get());
        }
    }

    @Test
    void missing_unknown_and_disabled_preparation_never_claim_success() throws Exception {
        for (String body : List.of(
                "{\"version\":7,\"state\":\"PUBLISHED\",\"bundleSha256\":\"digest\",\"sizeBytes\":5}",
                version("FUTURE_STAGE"),
                version("UPLOADING"),
                version("UNAVAILABLE"),
                version("READY").replace("\"version\":7", "\"version\":8"),
                version("READY").replace("\"state\":\"PUBLISHED\"", "\"state\":\"DERIVING\""))) {
            try (Harness h = new Harness(List.of(new Reply(200, body)))) {
                List<RegistryClient.PushUpdate> updates = new ArrayList<>();
                RegistryException error = assertThrows(RegistryException.class, () -> h.push(updates));
                assertTrue(error.getMessage().contains("v7"));
                assertFalse(updates.stream().anyMatch(u -> u.phase() == RegistryClient.PushPhase.READY));
            }
        }
    }

    @Test
    void a_custom_deadline_reports_continued_processing_without_ready() throws Exception {
        try (Harness h = new Harness(List.of(new Reply(200, version("LIGHTING"))))) {
            h.budget = Duration.ofSeconds(3);
            List<RegistryClient.PushUpdate> updates = new ArrayList<>();
            RegistryException error = assertThrows(RegistryException.class, () -> h.push(updates));
            assertTrue(error.getMessage().contains("processing continues"));
            assertTrue(error.getMessage().contains("v7"));
            assertEquals(Duration.ofSeconds(3).toNanos(), h.now.get());
            assertFalse(updates.stream().anyMatch(u -> u.phase() == RegistryClient.PushPhase.READY));
        }
    }

    @Test
    void terminal_lighting_failure_names_the_stage_and_keeps_server_details() throws Exception {
        try (Harness h = new Harness(
                List.of(
                        new Reply(
                                200,
                                version("FAILED")
                                        .replace(
                                                "\"lightFormat\":2",
                                                "\"lightFormat\":2,\"failureCode\":\"LIGHTING_FAILED\",\"detail\":\"Light preparation failed after its retry limit.\""))))) {
            RegistryException error = assertThrows(RegistryException.class, () -> h.push(new ArrayList<>()));
            assertTrue(error.getMessage().contains("v7"));
            assertTrue(error.getMessage().contains("Light preparation failed"));
        }
    }

    private static String version(String stage) {
        String state =
                switch (stage) {
                    case "QUEUED" -> "DRAFT";
                    case "DERIVING" -> "DERIVING";
                    default -> "PUBLISHED";
                };
        return "{\"version\":7,\"state\":\"" + state + "\",\"bundleSha256\":\"digest\",\"sizeBytes\":5,"
                + "\"preparation\":{\"stage\":\"" + stage + "\",\"lightFormat\":2}}";
    }

    @Test
    void reports_lod_queue_and_generation_after_lighting_without_claiming_early_success() throws Exception {
        try (Harness h = new Harness(List.of(
                new Reply(200, lodVersion("QUEUED")),
                new Reply(200, lodVersion("GENERATING")),
                new Reply(200, lodVersion("READY"))))) {
            List<RegistryClient.PushUpdate> updates = new ArrayList<>();
            MapVersion result = h.push(updates);
            assertEquals("READY", result.lod().stage());
            assertEquals(1812, result.lod().columns());
            assertEquals(4113662L, result.lod().bundleBytes());
            assertEquals(
                    List.of(
                            RegistryClient.PushPhase.UPLOADING,
                            RegistryClient.PushPhase.SUBMITTING,
                            RegistryClient.PushPhase.QUEUED,
                            RegistryClient.PushPhase.WAITING_FOR_LOD,
                            RegistryClient.PushPhase.GENERATING_LOD,
                            RegistryClient.PushPhase.READY),
                    updates.stream().map(RegistryClient.PushUpdate::phase).toList());
            assertEquals(3, h.polls.get());
        }
    }

    @Test
    void optional_lod_wait_limit_and_failure_do_not_turn_a_prepared_map_into_a_failed_upload() throws Exception {
        for (String stage : List.of("WAITING_FOR_ACTIVATION", "LIMIT_REACHED", "FAILED", "NOT_CONFIGURED")) {
            try (Harness h = new Harness(List.of(new Reply(200, lodVersion(stage))))) {
                MapVersion result = h.push(new ArrayList<>());
                assertEquals("READY", result.preparation().stage());
                assertEquals(stage, result.lod().stage());
                assertEquals(1, h.polls.get());
            }
        }
    }

    @Test
    void confirmed_map_readiness_survives_optional_lod_timeout_or_status_outage() throws Exception {
        try (Harness h = new Harness(List.of(new Reply(200, lodVersion("GENERATING"))))) {
            h.budget = Duration.ofSeconds(3);
            h.advance = Duration.ofSeconds(3);
            MapVersion result = h.push(new ArrayList<>());
            assertTrue(result.isPublished());
            assertEquals("READY", result.preparation().stage());
            assertEquals("UNAVAILABLE", result.lod().stage());
        }
        for (int code : List.of(403, 500)) {
            try (Harness h = new Harness(List.of(
                    new Reply(200, lodVersion("GENERATING")),
                    new Reply(code, "{\"detail\":\"private-internal-marker\"}")))) {
                MapVersion result = h.push(new ArrayList<>());
                assertEquals("READY", result.preparation().stage());
                assertEquals("UNAVAILABLE", result.lod().stage());
            }
        }
    }

    @Test
    void status_read_does_not_forward_raw_service_error_details() throws Exception {
        try (Harness h = new Harness(List.of(new Reply(500, "{\"detail\":\"private-internal-marker\"}")))) {
            String base = "http://127.0.0.1:" + h.server.getAddress().getPort();
            RegistryClient client = new RegistryClient(base, base + "/token", "id", "secret");
            RegistryException error =
                    assertThrows(RegistryException.class, () -> client.getVersion(() -> "token", "maps/test", 7));
            assertFalse(error.getMessage().contains("private-internal-marker"));
            assertTrue(error.getMessage().contains("500"));
        }
    }

    private static String lodVersion(String stage) {
        var json = com.google.gson.JsonParser.parseString(version("READY")).getAsJsonObject();
        var lod = new com.google.gson.JsonObject();
        lod.addProperty("stage", stage);
        if (stage.equals("READY")) {
            lod.addProperty("columns", 1812);
            lod.addProperty("bundleBytes", 4113662L);
        }
        json.add("lod", lod);
        return json.toString();
    }

    private record Reply(int status, String body) {}

    private class Harness implements AutoCloseable {
        final AtomicLong now = new AtomicLong();
        final AtomicInteger polls = new AtomicInteger();
        final HttpServer server;
        Duration budget = Duration.ofMinutes(90);
        Duration advance;

        Harness(List<Reply> replies) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                Reply reply;
                if (path.endsWith("/uploads"))
                    reply = new Reply(
                            200,
                            "{\"uploadId\":\"u\",\"url\":\"http://127.0.0.1:"
                                    + server.getAddress().getPort() + "/archive\"}");
                else if (path.equals("/archive")) reply = new Reply(200, "{}");
                else if (path.endsWith("/versions")) reply = new Reply(200, version("QUEUED"));
                else reply = replies.get(Math.min(polls.getAndIncrement(), replies.size() - 1));
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        MapVersion push(List<RegistryClient.PushUpdate> updates) throws Exception {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            RegistryClient client = new RegistryClient(
                    base,
                    base + "/token",
                    "id",
                    "secret",
                    now::get,
                    duration -> now.addAndGet((advance == null ? duration : advance).toNanos()),
                    budget);
            Path archive = Files.writeString(tmp.resolve("archive.tar.zst"), "bytes");
            return client.push(() -> "token", "maps/test", archive, "digest", 5, null, null, updates::add);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
