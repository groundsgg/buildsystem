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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The map registry, as this plugin sees it.
 *
 * <p>Every call is synchronous and blocking. That is deliberate rather than lazy: the callers are
 * commands, and a command that has to upload 200 MB must not run on the main thread at all. Making
 * the client blocking pushes that decision to the call site instead of hiding it behind a future
 * that somebody eventually joins on the tick loop.
 *
 * <p>Authentication is a Keycloak <em>client credentials</em> grant: the build server has its own
 * service-account client, not a builder's session. A builder's identity would be the wrong thing to
 * carry here — they are editing on a server that is itself trusted to publish, and tying a published
 * version to whoever happened to run the command would make the audit trail a lie the moment two
 * people work on one world.
 */
@NullMarked
public final class RegistryClient {

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    /** Refresh a little before expiry, so a long upload never starts on a token about to die. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final String baseUrl;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    private @Nullable String token;
    private Instant tokenExpiry = Instant.EPOCH;

    public RegistryClient(String baseUrl, String tokenUrl, String clientId, String clientSecret) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    // ----------------------------------------------------------------- maps

    /** Every map this service account is allowed to see. */
    public List<MapSummary> listMaps() throws RegistryException {
        JsonElement body = send(request("/v1/maps").GET(), "list maps");
        List<MapSummary> maps = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray()) {
            maps.add(MapSummary.from(element.getAsJsonObject()));
        }
        return maps;
    }

    public MapSummary createMap(String address, String displayName, String kind, boolean stateful)
            throws RegistryException {
        JsonObject request = new JsonObject();
        request.addProperty("address", address);
        request.addProperty("displayName", displayName);
        request.addProperty("kind", kind);
        request.addProperty("stateful", stateful);
        return MapSummary.from(
                send(json("/v1/maps", request), "create " + address).getAsJsonObject());
    }

    public List<MapVersion> listVersions(String address) throws RegistryException {
        JsonElement body = send(request(versionsPath(address)).GET(), "list versions of " + address);
        List<MapVersion> versions = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray()) {
            versions.add(MapVersion.from(element.getAsJsonObject()));
        }
        return versions;
    }

    // -------------------------------------------------------------- publish

    /**
     * Uploads a bundle and makes it a published version, which is the whole push in one call
     * because the three steps are not independently useful: an upload nobody committed expires in a
     * day, and a committed version nobody published cannot be pinned.
     *
     * @param archive a `.tar.zst` produced by {@link gg.grounds.buildsystem.world.WorldArchive}
     * @param sha256 that archive's digest, which becomes its address on the CDN
     */
    public MapVersion push(
            String address,
            Path archive,
            String sha256,
            long sizeBytes,
            @Nullable Integer parentVersion,
            @Nullable String note)
            throws RegistryException {
        JsonObject upload = send(request(path(address) + "/uploads").POST(noBody()), "open an upload")
                .getAsJsonObject();
        String uploadId = upload.get("uploadId").getAsString();
        putArchive(upload.get("url").getAsString(), archive);

        JsonObject commit = new JsonObject();
        commit.addProperty("uploadId", uploadId);
        // Source and bundle are the same object until a derive Job exists to make them differ.
        // Recording the same digest for both is honest about that rather than inventing one.
        commit.addProperty("sourceSha256", sha256);
        if (parentVersion != null) {
            commit.addProperty("parentVersion", parentVersion);
        }
        if (note != null && !note.isBlank()) {
            commit.addProperty("note", note);
        }
        MapVersion committed =
                MapVersion.from(
                        send(json(versionsPath(address), commit), "commit a version of " + address)
                                .getAsJsonObject());

        JsonObject publish = new JsonObject();
        publish.addProperty("bundleSha256", sha256);
        publish.addProperty("sizeBytes", sizeBytes);
        return MapVersion.from(
                send(
                                json(
                                        versionsPath(address) + "/" + committed.version() + "/publish",
                                        publish),
                                "publish version " + committed.version())
                        .getAsJsonObject());
    }

    /** A new map from an existing version. Copies no bytes; the fork is usable immediately. */
    public MapSummary fork(
            String source, String target, @Nullable Integer fromVersion, @Nullable String displayName)
            throws RegistryException {
        JsonObject request = new JsonObject();
        request.addProperty("target", target);
        if (fromVersion != null) {
            request.addProperty("fromVersion", fromVersion);
        }
        if (displayName != null) {
            request.addProperty("displayName", displayName);
        }
        return MapSummary.from(
                send(json(path(source) + "/forks", request), "fork " + source).getAsJsonObject());
    }

    // ------------------------------------------------------------ internals

    private void putArchive(String presignedUrl, Path archive) throws RegistryException {
        try {
            // No Authorization header and no content type: the presigned URL signs `host` only,
            // so anything else either travels unsigned or breaks the signature outright.
            HttpResponse<Void> response =
                    http.send(
                            HttpRequest.newBuilder(URI.create(presignedUrl))
                                    .timeout(TIMEOUT)
                                    .PUT(HttpRequest.BodyPublishers.ofFile(archive))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new RegistryException(
                        "the upload was refused with HTTP " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RegistryException("the upload failed: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder request(String path) throws RegistryException {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/json");
    }

    private HttpRequest.Builder json(String path, JsonObject body) throws RegistryException {
        return request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
    }

    private static HttpRequest.BodyPublisher noBody() {
        return HttpRequest.BodyPublishers.noBody();
    }

    private JsonElement send(HttpRequest.Builder builder, String what) throws RegistryException {
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RegistryException("could not " + what + ": " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new RegistryException("could not " + what + ": " + describe(response));
        }
        return JsonParser.parseString(response.body());
    }

    /**
     * The registry answers failures as {@code {"status":..,"detail":".."}}. Surfacing the detail is
     * the difference between "publish failed" and "publish failed: bundleSha256 must be 64
     * lowercase hex characters" — one of which a builder can act on.
     */
    private static String describe(HttpResponse<String> response) {
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("detail")) {
                return parsed.getAsJsonObject().get("detail").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Not JSON. Fall through to the status code, which is still better than nothing.
        }
        return "HTTP " + response.statusCode();
    }

    private synchronized String accessToken() throws RegistryException {
        if (token != null && Instant.now().isBefore(tokenExpiry.minus(EXPIRY_MARGIN))) {
            return token;
        }
        String form =
                "grant_type=client_credentials"
                        + "&client_id="
                        + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                        + "&client_secret="
                        + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        HttpResponse<String> response;
        try {
            response =
                    http.send(
                            HttpRequest.newBuilder(URI.create(tokenUrl))
                                    .timeout(Duration.ofSeconds(30))
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .POST(HttpRequest.BodyPublishers.ofString(form))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RegistryException("could not reach Keycloak: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            // Never echo the body: a failed token response can carry the client secret back.
            throw new RegistryException(
                    "Keycloak refused the build server's credentials (HTTP "
                            + response.statusCode()
                            + ")");
        }
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        String fresh = body.get("access_token").getAsString();
        long expiresIn = body.has("expires_in") ? body.get("expires_in").getAsLong() : 60;
        this.token = fresh;
        this.tokenExpiry = Instant.now().plusSeconds(expiresIn);
        return fresh;
    }

    /**
     * A creator namespace is itself {@code u/<creator>}, so an address is not two segments and is
     * never split here. It is encoded per segment: the registry routes it as one catch-all
     * parameter and a raw slash is exactly what makes that work.
     */
    private static String path(String address) {
        StringBuilder encoded = new StringBuilder("/v1/maps");
        for (String segment : address.split("/")) {
            encoded.append('/').append(URLEncoder.encode(segment, StandardCharsets.UTF_8));
        }
        return encoded.toString();
    }

    private static String versionsPath(String address) {
        return path(address) + "/versions";
    }
}
