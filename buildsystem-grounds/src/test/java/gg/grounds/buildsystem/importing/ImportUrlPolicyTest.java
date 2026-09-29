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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.grounds.buildsystem.registry.RegistryException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ImportUrlPolicyTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    /** Host names resolve only through this map, so no test touches real DNS. */
    private static ImportUrlPolicy policy(List<String> allowed, Map<String, String> dns) {
        return new ImportUrlPolicy(allowed, host -> {
            String address = dns.get(host);
            if (address == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {ip(address)};
        });
    }

    private static final Map<String, String> DNS = Map.of(
            "maps.example.com", "93.184.216.34",
            "cdn.maps.example.com", "93.184.216.35",
            "evil.example.com", "10.0.0.5",
            "other.org", "93.184.216.36");

    @Test
    void accepts_https_to_a_public_host() {
        assertDoesNotThrow(() -> policy(List.of(), DNS).check("https://maps.example.com/world.zip"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://maps.example.com/world.zip",
                "ftp://maps.example.com/world.zip",
                "file:///etc/passwd",
                "https://user:pw@maps.example.com/world.zip",
                "https://maps.example.com:8443/world.zip",
                "not a url",
                "https:///nohost"
            })
    void refuses_anything_but_plain_https(String url) {
        assertThrows(RegistryException.class, () -> policy(List.of(), DNS).check(url));
    }

    /** The point of the policy: a public-looking name must not lead into the cluster. */
    @Test
    void refuses_a_host_that_resolves_to_a_private_address() {
        assertThrows(RegistryException.class, () -> policy(List.of(), DNS).check("https://evil.example.com/x.zip"));
    }

    @Test
    void allowlist_admits_listed_hosts_and_their_subdomains_only() throws RegistryException {
        ImportUrlPolicy allowlisted = policy(List.of("maps.example.com"), DNS);
        allowlisted.check("https://maps.example.com/a.zip");
        allowlisted.check("https://cdn.maps.example.com/a.zip");
        assertThrows(RegistryException.class, () -> allowlisted.check("https://other.org/a.zip"));
    }

    /** The deployment has to be able to narrow what the jar ships, so the variable replaces the list. */
    @Test
    void environment_allowlist_replaces_the_configured_one() {
        assertEquals(
                List.of("maps.example.com", "github.com"),
                ImportUrlPolicy.allowedHosts(" maps.example.com, ,github.com ", List.of("other.org")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", " , ,"})
    void unset_or_empty_environment_keeps_the_configured_allowlist(String fromEnv) {
        assertEquals(List.of("other.org"), ImportUrlPolicy.allowedHosts(fromEnv, List.of("other.org")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0.0.0.0",
                "10.1.2.3",
                "127.0.0.1",
                "100.64.0.1",
                "100.127.255.255",
                "169.254.169.254",
                "172.16.0.1",
                "172.31.255.255",
                "192.168.1.1",
                "192.0.0.8",
                "198.18.0.1",
                "224.0.0.1",
                "255.255.255.255",
                "::",
                "::1",
                "fe80::1",
                "fc00::1",
                "fd12:3456::1",
                "::ffff:10.0.0.1",
                "::ffff:127.0.0.1",
                "2001:db8::1",
                "64:ff9b::a00:1"
            })
    void internal_addresses_are_not_public(String address) {
        assertFalse(ImportUrlPolicy.isPublic(ip(address)), address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "1.1.1.1", "100.128.0.1", "172.32.0.1", "2606:4700::1111"})
    void internet_addresses_are_public(String address) {
        assertTrue(ImportUrlPolicy.isPublic(ip(address)), address);
    }
}
