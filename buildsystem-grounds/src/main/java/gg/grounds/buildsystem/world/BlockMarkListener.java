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
package gg.grounds.buildsystem.world;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.NullMarked;

/**
 * Turns the next right-click on a block into a marked place.
 *
 * <p>A bed, a generator pad or a shop's block is something already standing in the map. Taking the
 * builder's own position for those is off by the width of a player, and by whatever they were
 * facing — so those are pointed at instead. The block's centre is recorded, not its corner, because
 * a generator that spawns items at a corner drops them into the wall behind it.
 */
@NullMarked
public final class BlockMarkListener implements Listener {

    private final PendingMarks pending;

    public BlockMarkListener(PendingMarks pending) {
        this.pending = pending;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!pending.isArmed(player.getUniqueId())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        PendingMarks.Pending armed =
                pending.take(player.getUniqueId(), player.getWorld().getUID());
        if (armed == null) {
            // Expired, or armed in another world. Saying so beats a click that does nothing.
            player.sendMessage(Component.text(
                    "That mark expired or belongs to another world. Run the command again.", NamedTextColor.RED));
            return;
        }

        // A bed opens a sleep prompt, a chest opens an inventory: neither is what a click meant to
        // mark a place should do, and both would hide the confirmation behind a screen.
        event.setCancelled(true);

        java.nio.file.Path folder = player.getWorld().getWorldFolder().toPath();
        Map<String, PointsOfInterest.Poi> pois = PointsOfInterest.read(folder);
        boolean replaced = pois.containsKey(armed.point());
        pois.put(
                armed.point(),
                new PointsOfInterest.Poi(
                        block.getX() + 0.5,
                        block.getY(),
                        block.getZ() + 0.5,
                        // Which way the builder faced while pointing at it, which is the sensible
                        // direction for anything that later faces a player.
                        player.getLocation().getYaw(),
                        0f));
        try {
            PointsOfInterest.write(folder, pois);
        } catch (IOException e) {
            player.sendMessage(Component.text("Could not save the places: " + e.getMessage(), NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text(
                (replaced ? "Moved " : "Marked ") + armed.point() + " to the block you clicked.",
                NamedTextColor.GREEN));

        MapSetup.Setup setup = MapSetup.read(folder);
        if (setup == null) {
            return;
        }
        List<String> missing = SetupProfile.missing(setup.gamemode(), setup.teams(), pois.keySet());
        player.sendMessage(
                missing.isEmpty()
                        ? Component.text(
                                "That was the last one — the map is complete. /map push publishes it.",
                                NamedTextColor.GREEN)
                        : Component.text(missing.size() + " left. Next: " + missing.get(0), NamedTextColor.GRAY));
    }

    /** A builder who logs out mid-mark should not come back armed. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.cancel(event.getPlayer().getUniqueId());
    }
}
