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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether a URL may be fetched by {@code /map import}.
 *
 * <p>The build server runs inside the cluster, so a URL a builder types is fetched from where
 * internal services answer. Without this check {@code https://10.0.0.5/} or the cloud metadata
 * address would be a way to read them. Only https to a host whose every address is public is
 * allowed, optionally narrowed to an allowlist of hosts.
 *
 * <p>Residual risk, stated rather than hidden: the HTTP client resolves the host again when it
 * connects, so a host that answers differently between the two lookups (DNS rebinding) is not
 * caught here. The build server's egress network policy is the layer that closes that.
 */
@NullMarked
public final class ImportUrlPolicy {

    /** How host names are resolved; replaceable so tests need no DNS. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Comma-separated hosts that replace {@code import.allowed-hosts}. An environment variable
     * because config.yml is baked into the jar and the stage build server has no volume to edit it
     * on — the deployment is the only place a per-environment value can live.
     */
    public static final String ALLOWED_HOSTS_ENV = "GROUNDS_MAPS_IMPORT_ALLOWED_HOSTS";

    private final List<String> allowedHosts;
    private final Resolver resolver;

    /**
     * The allowlist in force: the environment variable when it names at least one host, otherwise
     * the configured list. Replacing rather than merging, so a deployment can narrow what the jar
     * ships and not only widen it.
     */
    public static List<String> allowedHosts(@Nullable String fromEnv, List<String> configured) {
        if (fromEnv != null) {
            List<String> hosts = Arrays.stream(fromEnv.split(","))
                    .map(String::trim)
                    .filter(host -> !host.isEmpty())
                    .toList();
            if (!hosts.isEmpty()) {
                return hosts;
            }
        }
        return configured;
    }

    /** @param allowedHosts lowercase host names; empty allows any public host */
    public ImportUrlPolicy(List<String> allowedHosts) {
        this(allowedHosts, InetAddress::getAllByName);
    }

    ImportUrlPolicy(List<String> allowedHosts, Resolver resolver) {
        this.allowedHosts = allowedHosts.stream()
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .toList();
        this.resolver = resolver;
    }

    /** The parsed URI when it may be fetched; otherwise a sentence saying why not. */
    public URI check(String raw) throws RegistryException {
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new RegistryException("That is not a URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new RegistryException("Only https:// URLs can be imported.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RegistryException("The URL has no host.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new RegistryException("URLs with a user name or password are not accepted.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new RegistryException("Only the standard https port is allowed.");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!isAllowedHost(host)) {
            throw new RegistryException(host + " is not on this server's import allowlist.");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new RegistryException("Could not resolve " + host + ".");
        }
        if (addresses.length == 0) {
            throw new RegistryException("Could not resolve " + host + ".");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new RegistryException(
                        host + " points at a private or internal address; not importing from there.");
            }
        }
        return uri;
    }

    private boolean isAllowedHost(String host) {
        if (allowedHosts.isEmpty()) {
            return true;
        }
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an address is reachable on the public internet rather than inside some network. */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(b);
        }
        if (address instanceof Inet6Address) {
            // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible forms carry an IPv4 address that
            // the checks above do not look at.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
                return isPublicV4(new byte[] {b[12], b[13], b[14], b[15]});
            }
            int first = b[0] & 0xff;
            // fc00::/7 unique-local; 2001:db8::/32 documentation; 64:ff9b::/96 NAT64 may map to internal v4.
            if ((first & 0xfe) == 0xfc) {
                return false;
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false;
            }
            if (first == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
                return false;
            }
            return true;
        }
        return false;
    }

    private static boolean isPublicV4(byte[] b) {
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        if (a == 0 || a == 10 || a == 127 || a >= 224) {
            return false; // this-network, private, loopback, multicast and reserved
        }
        if (a == 100 && c >= 64 && c <= 127) {
            return false; // 100.64.0.0/10 carrier-grade NAT (also Tailscale)
        }
        if (a == 169 && c == 254) {
            return false; // link-local, including the cloud metadata address
        }
        if (a == 172 && c >= 16 && c <= 31) {
            return false;
        }
        if (a == 192 && c == 168) {
            return false;
        }
        if (a == 192 && c == 0 && (b[2] & 0xff) == 0) {
            return false; // 192.0.0.0/24 protocol assignments
        }
        if (a == 198 && (c == 18 || c == 19)) {
            return false; // benchmarking
        }
        return true;
    }
}
