/*
 * Copyright (c) 2026, Grounds
 * Copyright (c) 2018-2026, Thomas Meaney
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
package gg.grounds.buildsystem;

import gg.grounds.buildsystem.command.MapCommand;
import gg.grounds.buildsystem.registry.RegistryClient;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;

/**
 * Wires the build server to the map registry.
 *
 * <p>A separate plugin rather than a patch to buildsystem-core, and that is the whole point of the
 * fork's layout: upstream keeps shipping MC versions and this module never appears in a merge
 * conflict. It talks to BuildSystem through its published API, exactly as an unrelated plugin would.
 */
@NullMarked
public final class GroundsMapsPlugin extends JavaPlugin {

    private @org.jspecify.annotations.Nullable RegistryClient registry;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String clientSecret = System.getenv("GROUNDS_MAPS_CLIENT_SECRET");
        if (clientSecret == null || clientSecret.isBlank()) {
            // Refusing to start is the safe direction. A build server that silently cannot
            // publish looks like a working build server until somebody finishes a map.
            getLogger()
                    .severe("GROUNDS_MAPS_CLIENT_SECRET is not set. The build server cannot talk to"
                            + " the map registry, so this plugin will not enable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.registry = new RegistryClient(
                require("registry.base-url"), require("oidc.token-url"), require("oidc.client-id"), clientSecret);

        MapCommand command = new MapCommand(this, registry);
        Objects.requireNonNull(getCommand("map"), "the map command is declared in plugin.yml")
                .setExecutor(command);
        Objects.requireNonNull(getCommand("map")).setTabCompleter(command);

        getLogger().info("Map registry: " + require("registry.base-url"));
    }

    private String require(String path) {
        String value = getConfig().getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(path + " is required in config.yml");
        }
        return value;
    }
}
