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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PinnedEntryTest {

    @Test
    void finds_an_address_in_the_pin_file() {
        String json = """
                {"environment":"stage","maps":{"lobby/mainlobby":{"version":3,"bundleSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bundleUrl":"https://maps.grounds.gg/bundle/sha256/aa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.tar.zst","sizeBytes":10}}}
                """;
        PinnedEntry entry = PinnedEntry.find(json, "lobby/mainlobby");
        assertEquals(3, entry.version());
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", entry.bundleSha256());
        assertEquals(
                "https://maps.grounds.gg/bundle/sha256/aa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.tar.zst",
                entry.bundleUrl());
    }

    @Test
    void returns_null_when_not_pinned() {
        assertNull(PinnedEntry.find("{\"environment\":\"stage\",\"maps\":{}}", "lobby/mainlobby"));
    }

    @Test
    void builds_a_cdn_bundle_url() {
        String sha = "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";
        assertEquals(
                "https://maps.grounds.gg/bundle/sha256/ab/" + sha + ".tar.zst",
                BundleRef.bundleUrl("https://maps.grounds.gg/", sha));
    }
}
