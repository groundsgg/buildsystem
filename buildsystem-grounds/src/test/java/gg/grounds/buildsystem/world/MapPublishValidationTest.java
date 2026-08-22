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
package gg.grounds.buildsystem.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapPublishValidationTest {

    @TempDir
    Path world;

    @Test
    void refuses_a_lobby_without_its_spawn() throws IOException {
        MapSetup.write(world, new MapSetup.Setup("lobby", List.of()));

        assertEquals(
                "This lobby has no spawn. Stand where players should land and run /ms spawn.",
                MapPublishValidation.problem(world));
    }

    @Test
    void accepts_a_lobby_with_its_spawn() throws IOException {
        MapSetup.write(world, new MapSetup.Setup("lobby", List.of()));
        PointsOfInterest.write(
                world, Map.of("spawn", new PointsOfInterest.Poi(10.5, 64.0, -3.5, 90.0f, 0.0f)));

        assertNull(MapPublishValidation.problem(world));
    }

    @Test
    void does_not_apply_lobby_rules_to_other_maps() throws IOException {
        MapSetup.write(world, new MapSetup.Setup("bedwars", List.of("red", "blue")));

        assertNull(MapPublishValidation.problem(world));
    }
}
