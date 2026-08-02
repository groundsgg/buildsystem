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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The realm's client requires PKCE (S256). Without a challenge Keycloak answers 400 "Missing
 * parameter: code_challenge_method" and no builder can ever sign in, so the parameters are pinned
 * here rather than trusted to survive a refactor.
 */
class DeviceFlowPkceTest {

    @Test
    void sends_an_s256_challenge_derived_from_the_verifier() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> received = new HashMap<>();
        server.createContext(
                "/realms/x/protocol/openid-connect/auth/device",
                exchange -> {
                    received.putAll(parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    byte[] body =
                            ("{\"device_code\":\"dc\",\"user_code\":\"UC\",\"verification_uri\":\"https://example/device\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/x";
            DeviceFlow.Pending pending = new DeviceFlow(issuer, "grounds-cli").begin();

            assertEquals("S256", received.get("code_challenge_method"));
            assertEquals("grounds-cli", received.get("client_id"));
            // The challenge must be the SHA-256 of the verifier we kept, or redeeming fails later
            // with an error that says nothing about this request.
            assertEquals(challengeOf(pending.codeVerifier()), received.get("code_challenge"));
            // Long enough to be a real secret, and URL-safe so it survives form encoding.
            assertTrue(pending.codeVerifier().length() >= 43, pending.codeVerifier());
        } finally {
            server.stop(0);
        }
    }

    private static String challengeOf(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static Map<String, String> parse(String body) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            values.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
}
