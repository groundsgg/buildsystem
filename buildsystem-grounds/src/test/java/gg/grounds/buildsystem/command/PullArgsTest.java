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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PullArgsTest {

    @Test
    void parses_address_only_as_pin() {
        PullArgs args = PullArgs.parse(new String[] {"lobby/mainlobby"});
        assertEquals("lobby/mainlobby", args.addressTyped());
        assertTrue(args.usePin());
        assertFalse(args.force());
        assertFalse(args.latest());
        assertNull(args.version());
    }

    @Test
    void parses_version_and_force_in_either_order() {
        PullArgs a = PullArgs.parse(new String[] {"bedwars/crater", "12", "-f"});
        assertEquals(12, a.version());
        assertTrue(a.force());
        PullArgs b = PullArgs.parse(new String[] {"-f", "bedwars/crater", "latest"});
        assertTrue(b.latest());
        assertTrue(b.force());
        assertFalse(b.usePin());
    }

    @Test
    void refuses_garbage() {
        assertNull(PullArgs.parse(new String[] {}));
        assertNull(PullArgs.parse(new String[] {"-f"}));
        assertNull(PullArgs.parse(new String[] {"lobby/mainlobby", "nope"}));
        assertNull(PullArgs.parse(new String[] {"lobby/mainlobby", "0"}));
        assertNull(PullArgs.parse(new String[] {"lobby/mainlobby", "1", "2"}));
    }
}
