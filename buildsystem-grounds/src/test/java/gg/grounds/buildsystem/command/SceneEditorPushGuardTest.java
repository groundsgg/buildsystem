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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.grounds.scene.editor.SceneEditStatus;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

class SceneEditorPushGuardTest {

    private static final UUID WORLD_ID = new UUID(1L, 2L);

    @Test
    void allows_push_when_the_editor_plugin_is_absent() {
        SceneEditorPushGuard guard =
                new SceneEditorPushGuard(pluginManager(false), services(null), Logger.getAnonymousLogger());

        assertTrue(guard.allowsPush(WORLD_ID));
    }

    @Test
    void allows_push_when_the_enabled_editor_has_no_status_service() {
        SceneEditorPushGuard guard =
                new SceneEditorPushGuard(pluginManager(true), services(null), Logger.getAnonymousLogger());

        assertTrue(guard.allowsPush(WORLD_ID));
    }

    @Test
    void allows_push_when_the_editor_session_is_clean() {
        SceneEditorPushGuard guard =
                new SceneEditorPushGuard(pluginManager(true), services(worldId -> false), Logger.getAnonymousLogger());

        assertTrue(guard.allowsPush(WORLD_ID));
    }

    @Test
    void blocks_push_when_the_editor_session_is_dirty() {
        SceneEditorPushGuard guard =
                new SceneEditorPushGuard(pluginManager(true), services(worldId -> true), Logger.getAnonymousLogger());

        assertFalse(guard.allowsPush(WORLD_ID));
    }

    @Test
    void logs_and_blocks_push_when_the_editor_provider_fails() {
        AtomicReference<LogRecord> logged = new AtomicReference<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.set(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        SceneEditorPushGuard guard = new SceneEditorPushGuard(
                pluginManager(true),
                services(worldId -> {
                    throw new IllegalStateException("unavailable");
                }),
                logger);

        assertFalse(guard.allowsPush(WORLD_ID));
        assertNotNull(logged.get());
        LogRecord record = logged.get();
        assertTrue(record.getLevel().intValue() >= Level.WARNING.intValue());
        assertTrue(record.getMessage().contains("blocking map push"));
    }

    private static PluginManager pluginManager(boolean sceneEditorEnabled) {
        return proxy(
                PluginManager.class,
                (method, ignored) -> method.getName().equals("isPluginEnabled")
                        ? sceneEditorEnabled
                        : defaultValue(method.getReturnType()));
    }

    private static ServicesManager services(SceneEditStatus status) {
        return proxy(
                ServicesManager.class,
                (method, ignored) -> method.getName().equals("load") ? status : defaultValue(method.getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> invocation.invoke(method, args));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
