package gg.grounds.buildsystem.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryPreparationConcurrencyTest {
    @TempDir
    Path tmp;

    @Test
    void simultaneous_pushes_keep_their_version_and_progress_listener() throws Exception {
        var pollsA = new AtomicInteger();
        var pollsB = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            String body;
            if (path.endsWith("/uploads")) {
                body = "{\"uploadId\":\"u\",\"url\":\"http://127.0.0.1:"
                        + server.getAddress().getPort() + "/archive\"}";
            } else if (path.equals("/archive")) {
                body = "{}";
            } else {
                int version = path.contains("/test/a/") ? 7 : 8;
                int poll = path.endsWith("/versions") ? -1 : (version == 7 ? pollsA : pollsB).getAndIncrement();
                String stage =
                        switch (poll) {
                            case -1 -> "QUEUED";
                            case 0 -> "WAITING_FOR_LIGHT";
                            case 1 -> "LIGHTING";
                            default -> "READY";
                        };
                body = "{\"version\":" + version + ",\"state\":\"" + (poll < 0 ? "DRAFT" : "PUBLISHED")
                        + "\",\"bundleSha256\":\"digest\",\"sizeBytes\":5,\"preparation\":{\"stage\":\""
                        + stage + "\",\"lightFormat\":2}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var executor = Executors.newFixedThreadPool(2)) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            RegistryClient client =
                    new RegistryClient(base, base + "/token", "id", "secret", System::nanoTime, ignored -> {});
            Path archive = Files.writeString(tmp.resolve("map.tar.zst"), "bytes");
            List<RegistryClient.PushUpdate> updatesA = new CopyOnWriteArrayList<>();
            List<RegistryClient.PushUpdate> updatesB = new CopyOnWriteArrayList<>();
            var a = executor.submit(
                    () -> client.push(() -> "token", "test/a", archive, "digest", 5, null, null, updatesA::add));
            var b = executor.submit(
                    () -> client.push(() -> "token", "test/b", archive, "digest", 5, null, null, updatesB::add));
            assertEquals(7, a.get(10, TimeUnit.SECONDS).version());
            assertEquals(8, b.get(10, TimeUnit.SECONDS).version());
            assertTrue(updatesA.stream().filter(u -> u.version() > 0).allMatch(u -> u.version() == 7));
            assertTrue(updatesB.stream().filter(u -> u.version() > 0).allMatch(u -> u.version() == 8));
            assertEquals(RegistryClient.PushPhase.READY, updatesA.getLast().phase());
            assertEquals(RegistryClient.PushPhase.READY, updatesB.getLast().phase());
        } finally {
            server.stop(0);
        }
    }
}
