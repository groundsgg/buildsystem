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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.eintosti.buildsystem.api.world.BuildWorld;
import gg.grounds.scene.editor.SceneEditStatus;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

class MapCommandPushGuardTest {

    private static final UUID BUILD_SYSTEM_WORLD_ID = new UUID(3L, 4L);
    private static final UUID BUKKIT_WORLD_ID = new UUID(5L, 6L);

    @Test
    void dirty_scene_stops_the_actual_push_before_any_world_archive_link_or_registry_work() throws Exception {
        List<String> messages = new ArrayList<>();
        MapCommand command = new MapCommand(
                null,
                null,
                null,
                null,
                null,
                null,
                new SceneEditorPushGuard(
                        pluginManager(),
                        services(worldId -> {
                            assertEquals(BUKKIT_WORLD_ID, worldId);
                            return true;
                        }),
                        Logger.getAnonymousLogger()));

        assertDoesNotThrow(() -> push(command, player(messages), dirtyWorld(), new String[] {"push"}));
        assertEquals(List.of("You have unsaved scene edits. Run /scene save before /map push."), messages);
    }

    private static void push(MapCommand command, Player player, BuildWorld world, String[] args) throws Throwable {
        Method push = MapCommand.class.getDeclaredMethod("push", Player.class, BuildWorld.class, String[].class);
        push.setAccessible(true);
        try {
            push.invoke(command, player, world, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Player player(List<String> messages) {
        return proxy(Player.class, (method, args) -> {
            if (method.getName().equals("sendMessage")) {
                messages.add(((TextComponent) args[0]).content());
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static BuildWorld dirtyWorld() {
        return proxy(BuildWorld.class, (method, ignored) -> {
            if (method.getName().equals("getUniqueId")) {
                return BUILD_SYSTEM_WORLD_ID;
            }
            if (method.getName().equals("getWorld")) {
                return Optional.of(proxy(World.class, (worldMethod, worldArguments) -> {
                    if (worldMethod.getName().equals("getUID")) {
                        return BUKKIT_WORLD_ID;
                    }
                    throw new AssertionError("dirty scene push accessed Bukkit world " + worldMethod.getName());
                }));
            }
            throw new AssertionError("dirty scene push accessed " + method.getName());
        });
    }

    private static PluginManager pluginManager() {
        return proxy(
                PluginManager.class,
                (method, ignored) ->
                        method.getName().equals("isPluginEnabled") ? true : defaultValue(method.getReturnType()));
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
        return type == boolean.class ? false : null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] arguments) throws Throwable;
    }
}
