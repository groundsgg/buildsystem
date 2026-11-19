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
package gg.grounds.buildsystem.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class MapDialogueProjectionTest {
    private MapVersion read(String extra) {
        return MapVersion.from(JsonParser.parseString("{\"version\":7,\"state\":\"PUBLISHED\"" + extra + "}")
                .getAsJsonObject());
    }

    @Test
    void readsQualifiedNativeStoryAndExactSharedDiagnostics() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/maps-dialogue-projections.json")) {
            assertNotNull(input);
            var fixture = JsonParser.parseString(
                            new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals(
                    fixture.get("validSourceSha256").getAsString(),
                    fixture.getAsJsonObject("valid")
                            .getAsJsonObject("proof")
                            .get("sourceSha256")
                            .getAsString());
            var valid = read(",\"dialogue\":" + fixture.get("valid"));
            assertEquals("VALID", valid.dialogue().status());
            assertTrue(valid.dialogue().problems().isEmpty());
            var invalid = read(",\"dialogue\":" + fixture.get("invalid"));
            assertEquals("INVALID", invalid.dialogue().status());
            var problem = invalid.dialogue().problems().stream()
                    .filter(p -> "REFERENCE_UNKNOWN".equals(p.code()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("dialogues/mechanic.json", problem.file());
            assertEquals("/nodes/farewell", problem.pointer());
            assertEquals("farewell", problem.nodeId());
            assertEquals("ERROR", problem.severity());
        }
    }

    @Test
    void readsOldAndNewRegistryResponses() {
        assertEquals("UNKNOWN", read("").dialogue().status());
        assertEquals("UNKNOWN", read(",\"dialogue\":null").dialogue().status());
        assertEquals(
                "NONE",
                read(",\"dialogue\":{\"status\":\"NONE\",\"problems\":[]}")
                        .dialogue()
                        .status());
        assertEquals(
                "UNKNOWN",
                new MapVersion(1, "DRAFT", null, null, 0, null).dialogue().status());
    }

    @Test
    void readsAllDiagnosticLocationsAndPreservesWarnings() {
        MapVersion v = read("""
            ,"dialogue":{"status":"VALID","problems":[{"code":"OPTIONAL_GESTURE","file":"dialogues/mechanic.json",
            "pointer":"/nodes/start","nodeId":"start","severity":"WARNING","message":"Optional gesture unavailable"}]}
            """);
        assertEquals("VALID", v.dialogue().status());
        assertEquals(
                new MapVersion.DialogueProblem(
                        "OPTIONAL_GESTURE",
                        "dialogues/mechanic.json",
                        "/nodes/start",
                        "start",
                        "WARNING",
                        "Optional gesture unavailable"),
                v.dialogue().problems().getFirst());
        assertThrows(
                UnsupportedOperationException.class,
                () -> v.dialogue().problems().clear());
    }
}
