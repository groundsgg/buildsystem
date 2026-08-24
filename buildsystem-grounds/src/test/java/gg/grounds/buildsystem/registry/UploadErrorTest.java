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
package gg.grounds.buildsystem.registry;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A refused upload has to say why. "HTTP 400" alone cost three separate diagnoses — expired
 * credentials, a bad key and a stale pod all look identical without the storage's own message.
 */
class UploadErrorTest {

    @TempDir
    Path tmp;

    @Test
    void a_refused_upload_reports_the_storage_error() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/maps/bedwars/crater/uploads", exchange -> {
            String url = "http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/bucket/key";
            byte[] body =
                    ("{\"uploadId\":\"u\",\"key\":\"k\",\"url\":\"" + url + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/bucket/key", exchange -> {
            byte[] body = ("<Error><Code>InvalidAccessKeyId</Code>"
                            + "<Message>The access key is not valid</Message></Error>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path archive = Files.writeString(tmp.resolve("a.tar.zst"), "bytes");
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            RegistryClient client = new RegistryClient(base, base + "/token", "id", "secret");

            RegistryException failure = assertThrows(
                    RegistryException.class,
                    () -> client.push(() -> "token", "bedwars/crater", archive, "sha", 5L, null, null));

            assertTrue(failure.getMessage().contains("InvalidAccessKeyId"), failure.getMessage());
            assertTrue(failure.getMessage().contains("The access key is not valid"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
