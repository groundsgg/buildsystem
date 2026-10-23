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
package gg.grounds.buildsystem.workspace;

import de.eintosti.buildsystem.api.BuildSystemProvider;
import de.eintosti.buildsystem.api.event.world.BuildWorldPostDeleteEvent;
import de.eintosti.buildsystem.api.world.BuildWorld;
import gg.grounds.buildsystem.registry.RegistryClient;
import gg.grounds.buildsystem.registry.RegistryException;
import gg.grounds.buildsystem.registry.WorkspaceConflictException;
import gg.grounds.buildsystem.registry.WorkspaceHead;
import gg.grounds.buildsystem.world.WorldFolders;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the build server's working copy in the registry, so a pod replacement loses at most one
 * interval of work. See the working-copy design in service-maps.
 *
 * <p>On only when {@code GROUNDS_WORKSPACE_SERVER} names this server and the service account is
 * configured, and only after the start wrapper proved a restore (see {@link WorkspaceMarker}).
 * Every refusal is logged at SEVERE and leaves the server running without a working copy: a build
 * server that cannot back up is still useful, one that overwrites its backup with empty worlds is
 * not.
 */
@NullMarked
public final class WorkspaceLoop implements Listener {

    public static final String SERVER_ENV = "GROUNDS_WORKSPACE_SERVER";

    private static final Pattern SERVER = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    /**
     * How long a file must be left alone before a pass reads it, and so also how long after the
     * worlds are saved the pass starts: Paper hands chunks to its writer threads and returns.
     */
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(5);

    /**
     * The shutdown pass's budget, best effort. Kubernetes' default grace period is 30 seconds and
     * Paper still has to save and stop after this.
     */
    private static final Duration SHUTDOWN_BUDGET = Duration.ofSeconds(15);

    private final JavaPlugin plugin;
    private final WorkspaceSync sync;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private @Nullable BukkitTask timer;

    private WorkspaceLoop(JavaPlugin plugin, WorkspaceSync sync) {
        this.plugin = plugin;
        this.sync = sync;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "GroundsMaps-workspace");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts the loop, or explains in the log why it does not run. */
    public static @Nullable WorkspaceLoop start(JavaPlugin plugin, RegistryClient registry, long intervalSeconds) {
        String server = System.getenv(SERVER_ENV);
        if (server == null || server.isBlank()) {
            plugin.getLogger().info(SERVER_ENV + " is not set — the working copy is not synced.");
            return null;
        }
        if (!SERVER.matcher(server).matches()) {
            plugin.getLogger().severe(SERVER_ENV + "='" + server + "' is not a valid server id — not syncing.");
            return null;
        }
        if (!registry.hasServiceAccount()) {
            plugin.getLogger().severe(SERVER_ENV + " is set but GROUNDS_MAPS_CLIENT_SECRET is not — not syncing.");
            return null;
        }

        Path serverRoot = serverRoot();
        WorkspaceHead head;
        try {
            Long restored = WorkspaceMarker.read(serverRoot);
            head = registry.workspaceHead(registry.serviceAccount(), server);
            String disagreement = WorkspaceMarker.disagreement(restored, head);
            if (disagreement != null) {
                plugin.getLogger().severe("Working copy: " + disagreement + " — not syncing.");
                return null;
            }
        } catch (IOException | RegistryException e) {
            plugin.getLogger().severe("Working copy: " + e.getMessage() + " — not syncing.");
            return null;
        }

        WorkspaceSync sync =
                new WorkspaceSync(registry, registry.serviceAccount(), server, serverRoot, head, plugin.getLogger());
        WorkspaceLoop loop = new WorkspaceLoop(plugin, sync);
        Bukkit.getPluginManager().registerEvents(loop, plugin);
        long ticks = Math.max(1, intervalSeconds) * 20;
        loop.timer = Bukkit.getScheduler().runTaskTimer(plugin, loop::tick, ticks, ticks);
        plugin.getLogger()
                .info("Working copy: syncing '" + server + "' every " + intervalSeconds + "s from snapshot "
                        + (head == null ? "none" : head.snapshot()));
        return loop;
    }

    @EventHandler
    public void onWorldDeleted(BuildWorldPostDeleteEvent event) {
        executor.execute(sync::worldDeleted);
    }

    /**
     * Main thread: save, then hand the pass to the worker once the writes have settled. A pass
     * still running when the next is due means this one is skipped, never queued.
     */
    private void tick() {
        if (stopped.get() || !running.compareAndSet(false, true)) {
            return;
        }
        saveWorlds();
        Collection<Path> roots = worldRoots();
        executor.schedule(
                () -> {
                    try {
                        runPass(roots, QUIET_PERIOD);
                    } finally {
                        running.set(false);
                    }
                },
                QUIET_PERIOD.toMillis() + 1000,
                TimeUnit.MILLISECONDS);
    }

    /** One last pass with the worlds just saved, bounded so a dead registry cannot hold shutdown. */
    public void shutdown() {
        if (timer != null) {
            timer.cancel();
        }
        if (!stopped.get()) {
            saveWorlds();
            Collection<Path> roots = worldRoots();
            Future<?> last = executor.submit(() -> runPass(roots, Duration.ZERO));
            try {
                last.get(SHUTDOWN_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                plugin.getLogger()
                        .severe("Working copy: the shutdown pass did not finish within " + SHUTDOWN_BUDGET.toSeconds()
                                + "s; the last interval is not in the registry.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                plugin.getLogger().log(Level.SEVERE, "Working copy: the shutdown pass failed", e.getCause());
            }
        }
        executor.shutdownNow();
    }

    private void runPass(Collection<Path> roots, Duration quietPeriod) {
        if (stopped.get()) {
            return;
        }
        try {
            WorkspaceSync.Outcome outcome = sync.pass(roots, quietPeriod);
            if (outcome == WorkspaceSync.Outcome.COMMITTED) {
                plugin.getLogger().fine("Working copy: committed snapshot " + sync.base());
            }
        } catch (WorkspaceConflictException e) {
            stopped.set(true);
            plugin.getLogger()
                    .severe("Working copy: " + e.getMessage()
                            + " — another writer holds this server's working copy. Syncing stopped.");
        } catch (IOException | RegistryException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Working copy: this pass failed, the next one retries", e);
        }
    }

    private static void saveWorlds() {
        for (World world : Bukkit.getWorlds()) {
            world.save();
        }
    }

    /**
     * Paper runs with {@code /app} as its working directory, and the restore wrote every path
     * relative to it.
     */
    private static Path serverRoot() {
        return Path.of("").toAbsolutePath();
    }

    /**
     * The main world (whose own walk skips the nested {@code dimensions} folder), every dimension
     * under it, and every BuildSystem world wherever it lives — an unloaded or not yet migrated one
     * may sit in the flat legacy layout.
     */
    private static Collection<Path> worldRoots() {
        List<Path> roots = new ArrayList<>();
        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty()) {
            roots.add(worlds.getFirst().getWorldFolder().toPath());
        }
        File[] dimensions = WorldFolders.dimensionsRoot().listFiles(File::isDirectory);
        if (dimensions != null) {
            for (File dimension : dimensions) {
                roots.add(dimension.toPath());
            }
        }
        for (BuildWorld world :
                BuildSystemProvider.get().getWorldService().getWorldStorage().getBuildWorlds()) {
            roots.add(WorldFolders.forName(world.getName()).toPath());
        }
        return roots.stream().filter(Files::isDirectory).toList();
    }
}
