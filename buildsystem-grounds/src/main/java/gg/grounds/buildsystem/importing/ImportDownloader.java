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
package gg.grounds.buildsystem.importing;

import gg.grounds.buildsystem.registry.RegistryException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.OptionalLong;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Fetches an archive for {@code /map import}, within the limits a build server can afford.
 *
 * <p>Redirects are followed by hand rather than by the client: a public URL that answers "go to
 * http://10.0.0.5/" would otherwise walk straight past {@link ImportUrlPolicy}. Every hop is
 * checked again, and there are at most {@value #MAX_REDIRECTS} of them.
 *
 * <p>The size cap is enforced twice — against Content-Length before reading, and against the bytes
 * actually received, because a server may send no length or a false one.
 */
@NullMarked
public final class ImportDownloader {

    static final int MAX_REDIRECTS = 3;

    /** What arrived: the digest of exactly the bytes on disk, and where the last hop pointed. */
    public record Download(Path file, String sha256, long sizeBytes, URI finalUri) {}

    private final ImportUrlPolicy policy;
    private final long maxBytes;
    private final HttpClient http;

    public ImportDownloader(ImportUrlPolicy policy, long maxBytes) {
        this(
                policy,
                maxBytes,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    ImportDownloader(ImportUrlPolicy policy, long maxBytes, HttpClient http) {
        this.policy = policy;
        this.maxBytes = maxBytes;
        this.http = http;
    }

    /**
     * @param expectedSha256 lowercase hex the download must hash to, or null to accept any
     */
    public Download fetch(String url, Path dest, @Nullable String expectedSha256) throws RegistryException {
        URI uri = policy.check(url);
        for (int hop = 0; ; hop++) {
            HttpResponse<InputStream> response = send(uri);
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                closeQuietly(response.body());
                if (hop >= MAX_REDIRECTS) {
                    throw new RegistryException("The URL redirects more than " + MAX_REDIRECTS + " times.");
                }
                String location = response.headers().firstValue("location").orElse(null);
                if (location == null) {
                    throw new RegistryException("The server redirected without saying where to.");
                }
                uri = policy.check(uri.resolve(location).toString());
                continue;
            }
            if (status / 100 != 2) {
                closeQuietly(response.body());
                throw new RegistryException("The download answered HTTP " + status + ".");
            }
            OptionalLong declared = response.headers().firstValueAsLong("content-length");
            if (declared.isPresent() && declared.getAsLong() > maxBytes) {
                closeQuietly(response.body());
                throw new RegistryException(
                        "The file is " + mib(declared.getAsLong()) + "; the limit is " + mib(maxBytes) + ".");
            }
            Download download = store(response.body(), dest, uri);
            if (expectedSha256 != null && !download.sha256().equals(expectedSha256.toLowerCase(Locale.ROOT))) {
                deleteQuietly(dest);
                throw new RegistryException("The file does not match the sha256 you gave (got "
                        + download.sha256().substring(0, 12) + "…).");
            }
            return download;
        }
    }

    private HttpResponse<InputStream> send(URI uri) throws RegistryException {
        try {
            return http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofMinutes(10))
                            .header("User-Agent", "GroundsMaps map import")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RegistryException("Could not download: " + e.getMessage(), e);
        }
    }

    private Download store(InputStream body, Path dest, URI from) throws RegistryException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
        long total = 0;
        try (InputStream in = body;
                OutputStream out = Files.newOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new RegistryException("The file is larger than the " + mib(maxBytes) + " limit.");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (RegistryException e) {
            deleteQuietly(dest);
            throw e;
        } catch (IOException e) {
            deleteQuietly(dest);
            throw new RegistryException("The download broke off: " + e.getMessage(), e);
        }
        return new Download(dest, HexFormat.of().formatHex(digest.digest()), total, from);
    }

    static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.0f MB", bytes / 1024.0 / 1024.0);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to recover
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
