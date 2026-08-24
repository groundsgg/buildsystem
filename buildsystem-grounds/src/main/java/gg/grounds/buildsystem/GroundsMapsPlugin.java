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
import gg.grounds.buildsystem.command.MapSetupCommand;
import gg.grounds.buildsystem.registry.DeviceFlow;
import gg.grounds.buildsystem.registry.PlayerLogins;
import gg.grounds.buildsystem.registry.RegistryClient;
import gg.grounds.buildsystem.world.BlockMarkListener;
import gg.grounds.buildsystem.world.MapLinks;
import gg.grounds.buildsystem.world.PendingMarks;
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

        // Optional for push (builders use /map login). Required for /map pull — see MapCommand.
        String clientSecret = System.getenv("GROUNDS_MAPS_CLIENT_SECRET");
        if (clientSecret == null || clientSecret.isBlank()) {
            clientSecret = "";
            getLogger()
                    .info("GROUNDS_MAPS_CLIENT_SECRET is not set — builders must run /map login"
                            + " before pushing, and /map pull will not work until the secret is set.");
        }

        this.registry = new RegistryClient(
                require("registry.base-url"), require("oidc.token-url"), require("oidc.client-id"), clientSecret);

        DeviceFlow deviceFlow = new DeviceFlow(require("oidc.issuer-url"), require("oidc.device-client-id"));
        PlayerLogins logins = new PlayerLogins(deviceFlow);

        MapCommand command = new MapCommand(
                this, registry, deviceFlow, logins, new MapLinks(getDataFolder()), require("registry.cdn-base-url"));
        Objects.requireNonNull(getCommand("map"), "the map command is declared in plugin.yml")
                .setExecutor(command);
        Objects.requireNonNull(getCommand("map")).setTabCompleter(command);

        // Its own command because `/ms team1 bed` is typed once per place per team, and
        // `/map poi set team1.bed` is the same thing with more to mistype.
        PendingMarks pendingMarks = new PendingMarks();
        getServer().getPluginManager().registerEvents(new BlockMarkListener(pendingMarks), this);

        MapSetupCommand setup = new MapSetupCommand(pendingMarks);
        Objects.requireNonNull(getCommand("ms"), "the ms command is declared in plugin.yml")
                .setExecutor(setup);
        Objects.requireNonNull(getCommand("ms")).setTabCompleter(setup);

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
