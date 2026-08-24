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

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** A published (or pinned) map bundle ready to download from the CDN. */
@NullMarked
public record BundleRef(
        String address,
        int version,
        String bundleSha256,
        String bundleUrl,
        @Nullable Long sizeBytes) {

    public static String bundleKey(String sha256) {
        return "bundle/sha256/" + sha256.substring(0, 2) + "/" + sha256 + ".tar.zst";
    }

    public static String bundleUrl(String cdnBase, String sha256) {
        String base = cdnBase.replaceAll("/+$", "");
        return base + "/" + bundleKey(sha256);
    }
}
