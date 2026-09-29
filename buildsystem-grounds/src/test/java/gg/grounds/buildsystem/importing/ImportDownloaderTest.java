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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.grounds.buildsystem.registry.RegistryException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportDownloaderTest {

    @TempDir
    Path tmp;

    private static final Map<String, String> DNS = Map.of(
            "maps.example.com", "93.184.216.34",
            "mirror.example.com", "93.184.216.35",
            "internal.example.com", "10.0.0.5");

    private static ImportUrlPolicy policy() {
        return new ImportUrlPolicy(List.of(), host -> {
            String address = DNS.get(host);
            if (address == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(address)};
        });
    }

    /** One scripted answer: a status, headers and a body. */
    private record Answer(int status, Map<String, List<String>> headers, byte[] body) {
        static Answer ok(byte[] body) {
            return new Answer(200, Map.of(), body);
        }

        static Answer redirect(String location) {
            return new Answer(302, Map.of("location", List.of(location)), new byte[0]);
        }
    }

    @Test
    void downloads_and_hashes_the_body() throws Exception {
        byte[] body = "PK\u0003\u0004 world".getBytes();
        ScriptedClient http = new ScriptedClient(List.of(Answer.ok(body)));
        ImportDownloader downloader = new ImportDownloader(policy(), 1_000_000, http);

        ImportDownloader.Download download =
                downloader.fetch("https://maps.example.com/w.zip", tmp.resolve("w"), sha256(body));

        assertEquals(sha256(body), download.sha256());
        assertEquals(body.length, download.sizeBytes());
    }

    /** The reason redirects are followed by hand: a public URL must not bounce into the cluster. */
    @Test
    void refuses_a_redirect_to_an_internal_address() {
        ScriptedClient http = new ScriptedClient(List.of(Answer.redirect("https://internal.example.com/secret")));
        ImportDownloader downloader = new ImportDownloader(policy(), 1_000_000, http);

        assertThrows(
                RegistryException.class,
                () -> downloader.fetch("https://maps.example.com/w.zip", tmp.resolve("w"), null));
        assertEquals(1, http.requested.size());
    }

    @Test
    void follows_a_redirect_to_another_public_host() throws Exception {
        byte[] body = "world".getBytes();
        ScriptedClient http =
                new ScriptedClient(List.of(Answer.redirect("https://mirror.example.com/w.zip"), Answer.ok(body)));
        ImportDownloader downloader = new ImportDownloader(policy(), 1_000_000, http);

        ImportDownloader.Download download = downloader.fetch("https://maps.example.com/w.zip", tmp.resolve("w"), null);

        assertEquals(URI.create("https://mirror.example.com/w.zip"), download.finalUri());
    }

    @Test
    void gives_up_after_too_many_redirects() {
        List<Answer> loop = new ArrayList<>();
        for (int i = 0; i <= ImportDownloader.MAX_REDIRECTS; i++) {
            loop.add(Answer.redirect("https://maps.example.com/again"));
        }
        ImportDownloader downloader = new ImportDownloader(policy(), 1_000_000, new ScriptedClient(loop));

        assertThrows(
                RegistryException.class,
                () -> downloader.fetch("https://maps.example.com/w.zip", tmp.resolve("w"), null));
    }

    /** No Content-Length, or a false one: the cap has to hold on the bytes that actually arrive. */
    @Test
    void stops_at_the_size_limit_and_leaves_no_file() {
        ScriptedClient http = new ScriptedClient(List.of(Answer.ok(new byte[2_000])));
        ImportDownloader downloader = new ImportDownloader(policy(), 1_000, http);
        Path dest = tmp.resolve("w");

        assertThrows(RegistryException.class, () -> downloader.fetch("https://maps.example.com/w.zip", dest, null));
        assertFalse(Files.exists(dest));
    }

    @Test
    void refuses_a_declared_length_over_the_limit() {
        Answer big = new Answer(200, Map.of("content-length", List.of("5000")), new byte[0]);
        ImportDownloader downloader = new ImportDownloader(policy(), 1_000, new ScriptedClient(List.of(big)));

        assertThrows(
                RegistryException.class,
                () -> downloader.fetch("https://maps.example.com/w.zip", tmp.resolve("w"), null));
    }

    @Test
    void refuses_a_body_that_does_not_match_the_given_sha256() {
        ScriptedClient http = new ScriptedClient(List.of(Answer.ok("tampered".getBytes())));
        ImportDownloader downloader = new ImportDownloader(policy(), 1_000_000, http);
        Path dest = tmp.resolve("w");

        assertThrows(
                RegistryException.class,
                () -> downloader.fetch("https://maps.example.com/w.zip", dest, sha256("original".getBytes())));
        assertFalse(Files.exists(dest));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // -------------------------------------------------------------- scripting

    private static final class ScriptedClient extends HttpClient {
        private final List<Answer> answers;
        final List<URI> requested = new ArrayList<>();

        ScriptedClient(List<Answer> answers) {
            this.answers = answers;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            Answer answer = answers.get(requested.size());
            requested.add(request.uri());
            HttpHeaders headers = HttpHeaders.of(answer.headers(), (name, value) -> true);
            InputStream body = new ByteArrayInputStream(answer.body());
            return (HttpResponse<T>) new Response(answer.status(), headers, body, request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private record Response(int statusCode, HttpHeaders headers, InputStream body, HttpRequest request)
            implements HttpResponse<InputStream> {
        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
