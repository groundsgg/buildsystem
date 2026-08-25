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

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.grounds.buildsystem.registry.MapVersion;
import org.junit.jupiter.api.Test;

class MapCommandPublicationTest {

    @Test
    void publication_facts_use_the_derived_bundle_size_and_digest() {
        MapVersion published = new MapVersion(7, "PUBLISHED", "derived", null, 2 * 1024 * 1024L, null);

        assertEquals("2.0 MB, sha256 derived", MapCommand.publicationFacts(published));
    }
}
