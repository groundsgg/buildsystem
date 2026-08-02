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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Who is about to click a block, and what it will be called.
 *
 * <p>Expires, because an armed click that outlives the builder's memory of arming it turns the next
 * ordinary right-click into a silent edit of the map.
 */
@NullMarked
public final class PendingMarks {

    private static final Duration TTL = Duration.ofMinutes(2);

    /** @param point the full name the clicked block will get, e.g. `team1.bed` */
    public record Pending(String point, UUID world, Instant expiresAt) {}

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public void arm(UUID player, String point, UUID world) {
        pending.put(player, new Pending(point, world, Instant.now().plus(TTL)));
    }

    /** Takes the armed mark if it is still valid and the player is in the world they armed it in. */
    public @Nullable Pending take(UUID player, UUID world) {
        Pending armed = pending.remove(player);
        if (armed == null || Instant.now().isAfter(armed.expiresAt())) {
            return null;
        }
        // Arming in one world and clicking in another would file a point under a map it is not in.
        return armed.world().equals(world) ? armed : null;
    }

    public void cancel(UUID player) {
        pending.remove(player);
    }

    public boolean isArmed(UUID player) {
        Pending armed = pending.get(player);
        return armed != null && Instant.now().isBefore(armed.expiresAt());
    }
}
