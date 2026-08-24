/*
 * Copyright (c) 2026, Grounds
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Turns a map address + pin/version into a {@link BundleRef}, then downloads it. */
@NullMarked
public final class MapPullResolver {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient http;

    public MapPullResolver() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    MapPullResolver(HttpClient http) {
        this.http = http;
    }

    public BundleRef resolvePin(String cdnBase, String environment, String address) throws RegistryException {
        String base = cdnBase.replaceAll("/+$", "");
        String pinUrl = base + "/pins/" + environment + ".json";
        String body = getText(pinUrl, "read the " + environment + " pin file");
        PinnedEntry entry = PinnedEntry.find(body, address);
        if (entry == null) {
            throw new RegistryException(address + " is not pinned in " + environment + ".");
        }
        String url = entry.bundleUrl() != null && !entry.bundleUrl().isBlank()
                ? entry.bundleUrl()
                : BundleRef.bundleUrl(base, entry.bundleSha256());
        return new BundleRef(address, entry.version(), entry.bundleSha256(), url, entry.sizeBytes());
    }

    /**
     * @param versionOrNullForLatest explicit version, or {@code null} for the highest published
     */
    public BundleRef resolveVersion(
            RegistryClient registry,
            TokenSource auth,
            String cdnBase,
            String address,
            @Nullable Integer versionOrNullForLatest)
            throws RegistryException {
        List<MapVersion> versions = registry.listVersions(auth, address);
        MapVersion chosen;
        if (versionOrNullForLatest == null) {
            chosen = versions.stream()
                    .filter(MapVersion::isPublished)
                    .max(Comparator.comparingInt(MapVersion::version))
                    .orElseThrow(() -> new RegistryException(address + " has no published version yet."));
        } else {
            int want = versionOrNullForLatest;
            chosen = versions.stream()
                    .filter(v -> v.version() == want)
                    .findFirst()
                    .orElseThrow(() -> new RegistryException(address + " has no version " + want + "."));
            if (!chosen.isPublished()) {
                throw new RegistryException(
                        address + " v" + want + " is " + chosen.state().toLowerCase(Locale.ROOT) + ", not published.");
            }
        }
        String sha = chosen.bundleSha256();
        if (sha == null || sha.isBlank()) {
            throw new RegistryException(address + " v" + chosen.version() + " has no bundle digest.");
        }
        long size = chosen.sizeBytes();
        return new BundleRef(address, chosen.version(), sha, BundleRef.bundleUrl(cdnBase, sha), size);
    }

    public void download(String url, String expectedSha256, Path dest) throws RegistryException {
        try {
            HttpResponse<Path> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMinutes(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofFile(dest));
            if (response.statusCode() / 100 != 2) {
                Files.deleteIfExists(dest);
                throw new RegistryException("could not download the map bundle: HTTP " + response.statusCode());
            }
            String actual = sha256Of(dest);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(dest);
                throw new RegistryException("the downloaded bundle did not match its digest (expected "
                        + expectedSha256.substring(0, 12)
                        + "…, got "
                        + actual.substring(0, 12)
                        + "…).");
            }
        } catch (RegistryException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                Files.deleteIfExists(dest);
            } catch (IOException ignored) {
                // best effort
            }
            throw new RegistryException("could not download the map bundle: " + e.getMessage(), e);
        }
    }

    private String getText(String url, String what) throws RegistryException {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(TIMEOUT)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RegistryException("could not " + what + ": HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RegistryException("could not " + what + ": " + e.getMessage(), e);
        }
    }

    private static String sha256Of(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
