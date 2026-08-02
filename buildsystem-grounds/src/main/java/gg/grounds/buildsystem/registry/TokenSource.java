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

import org.jspecify.annotations.NullMarked;

/**
 * Who a registry call is made as.
 *
 * <p>There are two answers and the difference is visible in the registry's history: a builder who
 * signed in with {@code /map login}, or the build server's own service account. Preferring the
 * builder is the point — "published by the build server" is true but useless when three people
 * share a world.
 */
@NullMarked
@FunctionalInterface
public interface TokenSource {

    /** A bearer token, fetched or refreshed as needed. Never logged. */
    String token() throws RegistryException;

    /** What to call this identity in a message to a builder. */
    default String describe() {
        return "the build server";
    }
}
