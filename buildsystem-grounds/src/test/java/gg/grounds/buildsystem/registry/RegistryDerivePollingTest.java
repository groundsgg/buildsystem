/*
 * Copyright (c) 2026, Grounds
 * Copyright (c) 2018-2026, Thomas Meaney
 * Copyright (c) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package gg.grounds.buildsystem.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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

class RegistryDerivePollingTest {

    @TempDir
    Path tmp;

    @Test
    void large_failure_report_keeps_error_and_exact_version_without_unbounded_chat_component() throws Exception {
        var root = new com.google.gson.JsonObject();
        root.addProperty("version", 7);
        root.addProperty("state", "DERIVE_FAILED");
        var dialogue = new com.google.gson.JsonObject();
        dialogue.addProperty("status", "INVALID");
        var problems = new com.google.gson.JsonArray();
        for (int i = 0; i < 1000; i++) {
            var problem = new com.google.gson.JsonObject();
            problem.addProperty("code", i == 999 ? "ACTUAL_ERROR" : "NOTE");
            problem.addProperty("severity", i == 999 ? "ERROR" : "WARNING");
            problem.addProperty("message", "ümlaut ".repeat(80));
            problems.add(problem);
        }
        dialogue.add("problems", problems);
        root.add("dialogue", dialogue);
        HttpServer server = server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/uploads"))
                json(exchange, 200, "{\"uploadId\":\"u\",\"url\":\"" + base(exchange) + "/archive\"}");
            else if (path.equals("/archive")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (path.endsWith("/versions")) json(exchange, 200, "{\"version\":7,\"state\":\"DRAFT\"}");
            else if (path.endsWith("/versions/7")) json(exchange, 200, root.toString());
            else throw new AssertionError(path);
        });
        server.start();
        try {
            var error = assertThrows(
                    RegistryException.class,
                    () -> client(server, new AtomicLong(), duration -> {})
                            .push(() -> "token", "bedwars/crater", archive(), "digest", 5, null, null));
            String text = error.getMessage();
            assertTrue(text.contains("ACTUAL_ERROR"), text.substring(0, Math.min(200, text.length())));
            assertTrue(text.contains("/v1/maps/bedwars/crater/versions/7"));
            assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 3000, "Chat summary must be bounded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void push_commits_derivation_and_polls_until_the_version_is_published() throws Exception {
        List<String> requests = new ArrayList<>();
        List<Duration> delays = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.add(
                    exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/uploads")) {
                json(exchange, 200, "{\"uploadId\":\"u\",\"url\":\"" + base(exchange) + "/archive\"}");
            } else if (path.equals("/archive")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (path.endsWith("/versions") && exchange.getRequestMethod().equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("\"sourceSha256\":\"digest\""));
                assertTrue(body.contains("\"derive\":true"));
                json(
                        exchange,
                        200,
                        "{\"version\":7,\"state\":\"DRAFT\",\"parentVersion\":null,\"sizeBytes\":0,\"note\":null}");
            } else if (path.endsWith("/versions/7")) {
                int poll = polls.getAndIncrement();
                String preparation =
                        switch (poll) {
                            case 0 -> "QUEUED";
                            case 1 -> "DERIVING";
                            case 2 -> "WAITING_FOR_LIGHT";
                            case 3, 4 -> "LIGHTING";
                            default -> "READY";
                        };
                String state = poll == 0 ? "DRAFT" : poll == 1 ? "DERIVING" : "PUBLISHED";
                json(
                        exchange,
                        200,
                        "{\"version\":7,\"state\":\"" + state
                                + "\",\"bundleSha256\":\"derived\",\"parentVersion\":null,\"sizeBytes\":5,\"note\":null,"
                                + "\"preparation\":{\"stage\":\"" + preparation + "\",\"lightFormat\":2}}");
            } else {
                throw new AssertionError("unexpected request: " + path);
            }
        });
        server.start();
        try {
            RegistryClient client = client(server, now, duration -> {
                delays.add(duration);
                now.addAndGet(duration.toNanos());
            });

            MapVersion version = client.push(() -> "token", "bedwars/crater", archive(), "digest", 5, null, null);

            assertEquals(7, version.version());
            assertEquals("PUBLISHED", version.state());
            assertEquals(
                    List.of(
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(4),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(5)),
                    delays);
            assertEquals(6, polls.get());
            assertFalse(requests.stream().anyMatch(request -> request.contains("/publish")), requests.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedDialogueShowsFilePointerAndNode() throws Exception {
        AtomicLong now = new AtomicLong();
        HttpServer server = server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/uploads")) {
                json(exchange, 200, "{\"uploadId\":\"u\",\"url\":\"" + base(exchange) + "/archive\"}");
            } else if (path.equals("/archive")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (path.endsWith("/versions") && exchange.getRequestMethod().equals("POST")) {
                json(exchange, 201, "{\"version\":7,\"state\":\"DRAFT\"}");
            } else if (path.endsWith("/versions/7")) {
                json(exchange, 200, """
                    {"version":7,"state":"DERIVE_FAILED","scene":{"status":"PENDING","problems":[]},
                     "dialogue":{"status":"INVALID","problems":[{"code":"UNKNOWN_NODE","file":"dialogues/mechanic.json",
                     "pointer":"/nodes/start/next","nodeId":"start","severity":"ERROR","message":"Unknown target"}]}}
                    """);
            } else {
                throw new AssertionError("Must poll committed version 7: " + path);
            }
        });
        server.start();
        try {
            RegistryException failure = assertThrows(
                    RegistryException.class,
                    () -> client(server, now, duration -> now.addAndGet(duration.toNanos()))
                            .push(() -> "token", "bedwars/crater", archive(), "digest", 5, null, null));
            for (String expected :
                    List.of("v7", "dialogues/mechanic.json", "/nodes/start/next", "start", "UNKNOWN_NODE", "ERROR"))
                assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void derivation_failure_timeout_and_interruption_are_actionable() throws Exception {
        assertFailure("DERIVE_FAILED", now -> {}, "v7", "BROKEN_SCENE", "world.spawn");
        assertFailure(
                "DRAFT", now -> now.set(Duration.ofMinutes(90).plusNanos(1).toNanos()), "v7", "processing continues");
        assertFailure(
                "DRAFT",
                now -> {
                    throw new InterruptedException("stopped");
                },
                "interrupted",
                "__interrupt__");
    }

    @Test
    void published_version_without_a_bundle_digest_is_a_contract_failure() throws Exception {
        AtomicLong now = new AtomicLong();
        HttpServer server = server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/uploads")) {
                json(exchange, 200, "{\"uploadId\":\"u\",\"url\":\"" + base(exchange) + "/archive\"}");
            } else if (path.equals("/archive")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (path.endsWith("/versions") && exchange.getRequestMethod().equals("POST")) {
                json(exchange, 200, "{\"version\":7,\"state\":\"DRAFT\",\"sizeBytes\":0}");
            } else if (path.endsWith("/versions/7")) {
                json(exchange, 200, "{\"version\":7,\"state\":\"PUBLISHED\",\"sizeBytes\":9}");
            } else {
                throw new AssertionError("unexpected request: " + path);
            }
        });
        server.start();
        try {
            RegistryException failure = assertThrows(
                    RegistryException.class,
                    () -> client(server, now, duration -> now.addAndGet(duration.toNanos()))
                            .push(() -> "token", "bedwars/crater", archive(), "source", 5, null, null));
            assertTrue(failure.getMessage().contains("published v7 without a bundle digest"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private void assertFailure(String state, ThrowingSleeper sleeper, String... required) throws Exception {
        AtomicLong now = new AtomicLong();
        HttpServer server = server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/uploads")) {
                json(exchange, 200, "{\"uploadId\":\"u\",\"url\":\"" + base(exchange) + "/archive\"}");
            } else if (path.equals("/archive")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (path.endsWith("/versions") && exchange.getRequestMethod().equals("POST")) {
                json(
                        exchange,
                        200,
                        "{\"version\":7,\"state\":\"DRAFT\",\"parentVersion\":null,\"sizeBytes\":0,\"note\":null}");
            } else if (path.endsWith("/versions/7")) {
                json(
                        exchange,
                        200,
                        "{\"version\":7,\"state\":\"" + state
                                + "\",\"parentVersion\":null,\"sizeBytes\":0,\"note\":null,\"preparation\":{\"stage\":\"QUEUED\",\"lightFormat\":2},\"scene\":{\"problems\":[{\"code\":\"BROKEN_SCENE\",\"path\":\"world.spawn\",\"message\":\"bad spawn\"}]}}");
            } else {
                throw new AssertionError("unexpected request: " + path);
            }
        });
        server.start();
        try {
            RegistryException failure = assertThrows(
                    RegistryException.class,
                    () -> client(server, now, duration -> sleeper.sleep(now))
                            .push(() -> "token", "bedwars/crater", archive(), "digest", 5, null, null));
            for (String phrase : required) {
                if (phrase.equals("__interrupt__")) {
                    assertTrue(Thread.interrupted(), "an interrupted sleeper must restore the interrupt flag");
                    continue;
                }
                assertTrue(failure.getMessage().contains(phrase), failure.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    private RegistryClient client(HttpServer server, AtomicLong now, RegistryClient.Sleeper sleeper) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new RegistryClient(base, base + "/token", "id", "secret", now::get, sleeper);
    }

    private Path archive() throws IOException {
        return Files.writeString(tmp.resolve("archive-" + System.nanoTime() + ".tar.zst"), "bytes");
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        return server;
    }

    private static String base(HttpExchange exchange) {
        return "http://127.0.0.1:" + exchange.getLocalAddress().getPort();
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingSleeper {
        void sleep(AtomicLong now) throws InterruptedException;
    }
}
