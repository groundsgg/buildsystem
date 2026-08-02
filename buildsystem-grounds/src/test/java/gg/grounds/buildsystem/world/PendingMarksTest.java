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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingMarksTest {

    private final UUID player = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();

    @Test
    void an_armed_mark_is_taken_once() {
        PendingMarks marks = new PendingMarks();
        marks.arm(player, "red.bed", world);
        assertTrue(marks.isArmed(player));

        PendingMarks.Pending taken = marks.take(player, world);
        assertNotNull(taken);
        assertEquals("red.bed", taken.point());
        // A second click is an ordinary click again — otherwise every right-click keeps editing
        // the map until the builder notices.
        assertNull(marks.take(player, world));
        assertFalse(marks.isArmed(player));
    }

    /** Arming here and clicking there would file a point under a map it is not in. */
    @Test
    void a_mark_does_not_travel_to_another_world() {
        PendingMarks marks = new PendingMarks();
        marks.arm(player, "red.bed", world);

        assertNull(marks.take(player, UUID.randomUUID()));
    }

    @Test
    void cancelling_disarms() {
        PendingMarks marks = new PendingMarks();
        marks.arm(player, "red.bed", world);

        marks.cancel(player);

        assertFalse(marks.isArmed(player));
        assertNull(marks.take(player, world));
    }

    /** Blocks are pointed at; places a player stands are taken from the player. */
    @Test
    void the_profile_knows_which_things_are_blocks() {
        assertTrue(SetupProfile.isBlock("bed"));
        assertTrue(SetupProfile.isBlock("copper"));
        assertTrue(SetupProfile.isBlock("gold.2"), "a numbered spawn is still a block");
        assertFalse(SetupProfile.isBlock("spawn"));
    }
}
