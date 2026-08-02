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
package gg.grounds.buildsystem.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Turns what a builder types into an address the registry accepts.
 *
 * <p>A map address becomes a URL path and an object key, so the registry allows only
 * {@code [a-z0-9-]} per segment. Builders type world names — {@code MainLobby}, {@code Sky Wars} —
 * and forwarding those verbatim produced "not a valid map address", which states a rule nobody was
 * told and offers no way forward.
 *
 * <p>So this normalises instead, and the caller says which address it used. The pretty name is not
 * lost: it goes to the registry as the map's display name.
 */
@NullMarked
public final class MapAddresses {

    private MapAddresses() {}

    /**
     * @return the address the registry will accept, or null if nothing usable is left — an address
     *     of punctuation alone has no sensible reading and guessing one would be worse than asking
     */
    public static @Nullable String normalise(String typed) {
        List<String> segments = new ArrayList<>();
        for (String raw : typed.split("/")) {
            String segment = clean(raw);
            if (segment.isEmpty()) {
                return null;
            }
            segments.add(segment);
        }
        // `<namespace>/<name>`, or `u/<creator>/<name>` for creator content. Anything else is not
        // an address the registry can route.
        if (segments.size() < 2 || segments.size() > 3) {
            return null;
        }
        if (segments.size() == 3 && !segments.get(0).equals("u")) {
            return null;
        }
        return String.join("/", segments);
    }

    private static String clean(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                // Spaces, underscores and the rest all read as a word break; collapsing runs keeps
                // "Sky  Wars_2" from becoming "sky--wars-2", which the registry would refuse.
                out.append('-');
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }
}
