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

import gg.grounds.scene.editor.SceneEditStatus;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.jspecify.annotations.NullMarked;

/** Blocks map publication while the optional scene editor has unsaved changes for the world. */
@NullMarked
final class SceneEditorPushGuard {

    private static final String SCENE_EDITOR_PLUGIN = "GroundsSceneEditor";

    private final PluginManager plugins;
    private final ServicesManager services;
    private final Logger logger;

    SceneEditorPushGuard(PluginManager plugins, ServicesManager services, Logger logger) {
        this.plugins = plugins;
        this.services = services;
        this.logger = logger;
    }

    /**
     * This is called from {@link MapCommand}'s synchronous command path, before it schedules any
     * archive or registry work. The API is only touched after the optional plugin is enabled, so
     * GroundsMaps continues to load on servers that do not install the editor API.
     */
    boolean allowsPush(UUID worldId) {
        if (!plugins.isPluginEnabled(SCENE_EDITOR_PLUGIN)) {
            return true;
        }
        try {
            return !EditorStatusAdapter.hasUnsavedChanges(services, worldId);
        } catch (RuntimeException | LinkageError failure) {
            logger.log(Level.WARNING, "Could not inspect scene edits; blocking map push.", failure);
            return false;
        }
    }

    /** Keeps all typed optional-API access out of the linkage-safe outer guard. */
    private static final class EditorStatusAdapter {

        private static boolean hasUnsavedChanges(ServicesManager services, UUID worldId) {
            SceneEditStatus status = services.load(SceneEditStatus.class);
            return status != null && status.hasUnsavedChanges(worldId);
        }
    }
}
