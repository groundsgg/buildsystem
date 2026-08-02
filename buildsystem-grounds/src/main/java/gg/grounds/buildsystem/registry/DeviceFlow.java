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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.jspecify.annotations.NullMarked;

/**
 * OAuth 2.0 device authorization grant — the flow behind {@code /map login}.
 *
 * <p>The device flow exists for exactly this situation: the thing that needs a token has no
 * browser. A Minecraft client cannot follow a redirect, and asking a builder to paste a password
 * into chat would put their credential in the server log, in any chat-logging plugin, and possibly
 * on a stream. Instead they get a short code, open it wherever they already have a browser, and the
 * server polls until they are done.
 *
 * <p>Same flow and same client as {@code grounds login}, so a builder signs in with a screen they
 * have already seen.
 */
@NullMarked
public final class DeviceFlow {

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final String deviceUrl;
    private final String tokenUrl;
    private final String clientId;

    public DeviceFlow(String issuerUrl, String clientId) {
        String base = issuerUrl.replaceAll("/+$", "");
        this.deviceUrl = base + "/protocol/openid-connect/auth/device";
        this.tokenUrl = base + "/protocol/openid-connect/token";
        this.clientId = clientId;
    }

    /** What to show the builder, and what to poll with. */
    public record Pending(
            String deviceCode,
            String userCode,
            String verificationUri,
            /**
             * The PKCE secret this login is bound to. It never leaves the server and is sent only
             * when redeeming the device code, which is what stops a leaked code being redeemed by
             * somebody else.
             */
            String codeVerifier,
            Duration interval,
            Instant expiresAt) {}

    /** An access token and when to stop trusting it. */
    public record Tokens(String accessToken, String refreshToken, Instant expiresAt) {}

    public String tokenUrl() {
        return tokenUrl;
    }

    public String clientId() {
        return clientId;
    }

    public Pending begin() throws RegistryException {
        // PKCE is not optional here: the grounds-cli client requires S256, and without a
        // challenge Keycloak answers "Missing parameter: code_challenge_method" — which is also
        // exactly the right requirement, since it binds this device code to this login attempt.
        String verifier = newCodeVerifier();
        JsonObject body = form(
                deviceUrl,
                "client_id="
                        + enc(clientId)
                        + "&code_challenge="
                        + enc(challengeFor(verifier))
                        + "&code_challenge_method=S256",
                "start a login");
        // Prefer the complete URI: it carries the code, so the builder confirms rather than
        // types. Keycloak omits it in some configurations, hence the fallback.
        String verification = body.has("verification_uri_complete")
                ? body.get("verification_uri_complete").getAsString()
                : body.get("verification_uri").getAsString();
        long interval = body.has("interval") ? body.get("interval").getAsLong() : 5;
        long expiresIn = body.has("expires_in") ? body.get("expires_in").getAsLong() : 600;
        return new Pending(
                body.get("device_code").getAsString(),
                body.get("user_code").getAsString(),
                verification,
                verifier,
                Duration.ofSeconds(interval),
                Instant.now().plusSeconds(expiresIn));
    }

    /**
     * Blocks until the builder finishes, declines, or the code expires. Runs off the main thread —
     * this waits minutes by design.
     */
    public Tokens awaitApproval(Pending pending) throws RegistryException {
        Duration interval = pending.interval();
        while (Instant.now().isBefore(pending.expiresAt())) {
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RegistryException("the login was interrupted");
            }
            HttpResponse<String> response = post(
                    tokenUrl,
                    "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                            + "&client_id="
                            + enc(clientId)
                            + "&device_code="
                            + enc(pending.deviceCode())
                            // Without this the challenge registered at the device request
                            // has nothing to verify against, and Keycloak refuses the code
                            // — but only AFTER the builder has approved in the browser.
                            + "&code_verifier="
                            + enc(pending.codeVerifier()),
                    "complete the login");
            if (response.statusCode() == 200) {
                return toTokens(JsonParser.parseString(response.body()).getAsJsonObject());
            }
            String error = errorOf(response);
            switch (error) {
                case "authorization_pending" -> {
                    // The builder has not finished yet. Keep waiting.
                }
                // The spec's backpressure signal: poll slower or the server stops answering.
                case "slow_down" -> interval = interval.plusSeconds(5);
                case "access_denied" -> throw new RegistryException("The login was declined.");
                // Keycloak answers `invalid_grant` ("Device code not valid") rather than the
                // spec's `expired_token` for a code that expired, was already used, or belongs
                // to an earlier /map login. All three mean the same thing to a builder.
                case "expired_token", "invalid_grant" ->
                    throw new RegistryException("That sign-in link is no longer valid ("
                            + describeOAuthError(response)
                            + "). Run /map login again and open the newest link.");
                default -> throw new RegistryException("The login failed: " + describeOAuthError(response));
            }
        }
        throw new RegistryException("The code expired. Run /map login again.");
    }

    /** Exchanges a refresh token. Returns null when it is no longer accepted. */
    public Tokens refresh(String refreshToken) throws RegistryException {
        HttpResponse<String> response = post(
                tokenUrl,
                "grant_type=refresh_token&client_id=" + enc(clientId) + "&refresh_token=" + enc(refreshToken),
                "refresh the login");
        if (response.statusCode() != 200) {
            throw new RegistryException("Your login expired. Run /map login again.");
        }
        return toTokens(JsonParser.parseString(response.body()).getAsJsonObject());
    }

    // ------------------------------------------------------------ internals

    private static Tokens toTokens(JsonObject json) {
        long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 300;
        return new Tokens(
                json.get("access_token").getAsString(),
                json.has("refresh_token") ? json.get("refresh_token").getAsString() : "",
                Instant.now().plusSeconds(expiresIn));
    }

    private JsonObject form(String url, String body, String what) throws RegistryException {
        HttpResponse<String> response = post(url, body, what);
        if (response.statusCode() / 100 != 2) {
            // Never the body: a failed token response can echo credentials back.
            throw new RegistryException("Could not " + what + " (HTTP " + response.statusCode() + ").");
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private HttpResponse<String> post(String url, String body, String what) throws RegistryException {
        try {
            return http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RegistryException("Could not " + what + ": " + e.getMessage(), e);
        }
    }

    /** `error_description` if Keycloak sent one, else the code, else the status. */
    private static String describeOAuthError(HttpResponse<String> response) {
        try {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (body.has("error_description")) {
                return body.get("error_description").getAsString();
            }
            if (body.has("error")) {
                return body.get("error").getAsString();
            }
        } catch (RuntimeException e) {
            // Not an OAuth error object. The status code is still better than nothing.
        }
        return "HTTP " + response.statusCode();
    }

    private static String newCodeVerifier() {
        byte[] random = new byte[48];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String challengeFor(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String errorOf(HttpResponse<String> response) {
        try {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            return body.has("error") ? body.get("error").getAsString() : "unknown";
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
