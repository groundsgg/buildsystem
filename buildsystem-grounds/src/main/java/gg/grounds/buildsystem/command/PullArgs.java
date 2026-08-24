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
package gg.grounds.buildsystem.command;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Arguments after {@code /map pull}. {@code -f} may appear anywhere (FAWE habit). Version is an
 * integer, {@code latest}, or omitted for the environment pin.
 */
@NullMarked
public record PullArgs(String addressTyped, @Nullable Integer version, boolean latest, boolean force) {

    public static @Nullable PullArgs parse(String[] argsAfterSubcommand) {
        if (argsAfterSubcommand.length == 0) {
            return null;
        }
        boolean force = false;
        String address = null;
        Integer version = null;
        boolean latest = false;
        for (String raw : argsAfterSubcommand) {
            if (raw.equals("-f")) {
                force = true;
                continue;
            }
            if (address == null) {
                address = raw;
                continue;
            }
            if (version != null || latest) {
                return null;
            }
            if (raw.equalsIgnoreCase("latest")) {
                latest = true;
                continue;
            }
            try {
                version = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return null;
            }
            if (version < 1) {
                return null;
            }
        }
        if (address == null) {
            return null;
        }
        return new PullArgs(address, version, latest, force);
    }

    public boolean usePin() {
        return version == null && !latest;
    }
}
