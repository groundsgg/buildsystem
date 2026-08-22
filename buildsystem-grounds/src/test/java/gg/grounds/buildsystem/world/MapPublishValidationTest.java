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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
        PointsOfInterest.write(world, Map.of("spawn", new PointsOfInterest.Poi(10.5, 64.0, -3.5, 90.0f, 0.0f)));

        assertNull(MapPublishValidation.problem(world));
    }

    @Test
    void refuses_a_lobby_with_a_null_spawn() throws IOException {
        writePois("{\"format\":1,\"pois\":{\"spawn\":null}}");

        assertMissingSpawn();
    }

    @ParameterizedTest
    @MethodSource("requiredSpawnFields")
    void refuses_a_lobby_with_a_missing_spawn_field(String field, String json) throws IOException {
        writePois(json);

        assertMissingSpawn();
    }

    @Test
    void refuses_a_lobby_with_a_non_numeric_spawn_field() throws IOException {
        writePois(
                "{\"format\":1,\"pois\":{\"spawn\":{\"x\":\"not-a-number\",\"y\":64.0,\"z\":-3.5,\"yaw\":90.0,\"pitch\":-12.5}}}");

        assertMissingSpawn();
    }

    @Test
    void refuses_a_lobby_with_corrupt_points_json() throws IOException {
        writePois("{not-json");

        assertMissingSpawn();
    }

    @Test
    void does_not_apply_lobby_rules_to_other_maps() throws IOException {
        MapSetup.write(world, new MapSetup.Setup("bedwars", List.of("red", "blue")));

        assertNull(MapPublishValidation.problem(world));
    }

    private static Stream<Arguments> requiredSpawnFields() {
        return Stream.of(
                Arguments.of(
                        "x",
                        "{\"format\":1,\"pois\":{\"spawn\":{\"y\":64.0,\"z\":-3.5,\"yaw\":90.0,\"pitch\":-12.5}}}"),
                Arguments.of(
                        "y",
                        "{\"format\":1,\"pois\":{\"spawn\":{\"x\":10.5,\"z\":-3.5,\"yaw\":90.0,\"pitch\":-12.5}}}"),
                Arguments.of(
                        "z",
                        "{\"format\":1,\"pois\":{\"spawn\":{\"x\":10.5,\"y\":64.0,\"yaw\":90.0,\"pitch\":-12.5}}}"),
                Arguments.of(
                        "yaw",
                        "{\"format\":1,\"pois\":{\"spawn\":{\"x\":10.5,\"y\":64.0,\"z\":-3.5,\"pitch\":-12.5}}}"),
                Arguments.of(
                        "pitch",
                        "{\"format\":1,\"pois\":{\"spawn\":{\"x\":10.5,\"y\":64.0,\"z\":-3.5,\"yaw\":90.0}}}"));
    }

    private void writePois(String json) throws IOException {
        MapSetup.write(world, new MapSetup.Setup("lobby", List.of()));
        Path points = PointsOfInterest.fileIn(world);
        Files.createDirectories(points.getParent());
        Files.writeString(points, json, StandardCharsets.UTF_8);
    }

    private void assertMissingSpawn() {
        assertEquals(
                "This lobby has no spawn. Stand where players should land and run /ms spawn.",
                MapPublishValidation.problem(world));
    }
}
