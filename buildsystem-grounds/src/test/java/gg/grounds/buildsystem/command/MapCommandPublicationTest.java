/*
 * Copyright (c) 2026, Grounds
 * Copyright (c) 2018-2026, Thomas Meaney
 * Copyright (c) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package gg.grounds.buildsystem.command;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.buildsystem.registry.MapVersion;
import java.lang.reflect.Proxy;
import java.util.*;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class MapCommandPublicationTest {

    @Test
    void actual_completion_feedback_describes_static_story_validation_and_preserves_warning() {
        List<String> messages = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage")) messages.add(((TextComponent) args[0]).content());
                    return method.getReturnType() == boolean.class ? false : null;
                });
        MapVersion version = new MapVersion(
                7,
                "PUBLISHED",
                "derived",
                null,
                5,
                null,
                null,
                null,
                null,
                new MapVersion.Dialogue(
                        "VALID",
                        List.of(new MapVersion.DialogueProblem(
                                "DIALOGUE_GESTURE_UNAVAILABLE",
                                "grounds/dialogue.json",
                                "/speakers/guide",
                                null,
                                "WARNING",
                                "Optional wave unavailable"))));
        MapCommand.publicationComplete(player, "bedwars/crater", version, "builder");
        String text = String.join("\n", messages);
        assertTrue(text.contains("statically validated"), text);
        assertTrue(text.contains("runtime activation is not available"), text);
        assertTrue(text.contains("DIALOGUE_GESTURE_UNAVAILABLE"), text);
        assertTrue(text.contains("bedwars/crater v7"), text);
        assertFalse(text.contains("Ready to go live"), text);
    }

    @Test
    void publication_facts_use_the_derived_bundle_size_and_digest() {
        MapVersion published = new MapVersion(7, "PUBLISHED", "derived", null, 2 * 1024 * 1024L, null);

        assertEquals("2.0 MB, sha256 derived", MapCommand.publicationFacts(published));
    }
}
