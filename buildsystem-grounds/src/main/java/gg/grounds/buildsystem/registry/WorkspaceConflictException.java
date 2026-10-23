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
 * The registry refused a working copy write with 409: the head is not the snapshot this server
 * last restored or committed, a blob it announced never arrived, or the manifest is so much
 * smaller than the head that it looks like an empty boot ({@link #shrinkRefused()}).
 */
@NullMarked
public final class WorkspaceConflictException extends RegistryException {

    private final boolean shrinkRefused;

    public WorkspaceConflictException(String message, boolean shrinkRefused) {
        super(message);
        this.shrinkRefused = shrinkRefused;
    }

    /**
     * The registry's shrink guard refused the manifest rather than another writer moving the
     * head. Told apart by the problem's wording only, so it is for deciding whether to retry, not
     * proof of anything.
     */
    public boolean shrinkRefused() {
        return shrinkRefused;
    }
}
