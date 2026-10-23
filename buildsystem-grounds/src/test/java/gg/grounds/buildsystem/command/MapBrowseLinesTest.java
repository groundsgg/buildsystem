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
import static org.junit.jupiter.api.Assertions.assertNull;

import gg.grounds.buildsystem.registry.MapSummary;
import gg.grounds.buildsystem.registry.MapVersion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class MapBrowseLinesTest {

    @Test
    void publishedVersionSuggestsThePullCommand() {
        Component line = MapCommand.versionLine(
                "bedwars/crater", new MapVersion(3, "PUBLISHED", "ab".repeat(32), 2, 2 * 1024 * 1024, "roof"));

        assertEquals("  v3 · published · 2.0 MB · roof [pull]", plain(line));
        ClickEvent click = line.children().getFirst().clickEvent();
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action());
        assertEquals("/map pull bedwars/crater 3", ((ClickEvent.Payload.Text) click.payload()).value());
    }

    /** Only published versions have a bundle on the CDN; offering to pull a draft would just fail. */
    @Test
    void unpublishedVersionOffersNoPull() {
        Component line = MapCommand.versionLine("bedwars/crater", new MapVersion(4, "DRAFT", null, 3, 0, null));

        assertEquals("  v4 · draft", plain(line));
        assertEquals(0, line.children().size());
        assertNull(line.clickEvent());
    }

    @Test
    void mapLineOpensItsVersions() {
        Component line = MapCommand.mapLine(new MapSummary("lobby/mainlobby", "MainLobby", "LOBBY", false, null));

        assertEquals("  lobby/mainlobby · lobby · MainLobby", plain(line));
        assertEquals(ClickEvent.Action.RUN_COMMAND, line.clickEvent().action());
        assertEquals(
                "/map versions lobby/mainlobby",
                ((ClickEvent.Payload.Text) line.clickEvent().payload()).value());
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
