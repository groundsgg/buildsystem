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

import gg.grounds.buildsystem.world.WorldArchive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;

/**
 * What a build server's working copy consists of: every world folder, BuildSystem's own data, the
 * map links, and the builder tools' configuration. Plugin JARs and server configuration are not in
 * it — they come from the image.
 *
 * <p>A world is what {@link WorldArchive#collect} says it is, so a push and a working copy cannot
 * disagree about whether {@code playerdata} or the nested {@code dimensions} folder belongs to it.
 */
@NullMarked
public final class WorkspaceFiles {

    /** What the registry accepts as a path; anything else would be refused for the whole snapshot. */
    private static final Pattern VALID_PATH = Pattern.compile("[A-Za-z0-9._/-]{1,512}");

    /**
     * The builder tools whose data folders the image's start wrapper already treats as durable.
     * Only their configuration and schematics: the rest is caches that rebuild themselves.
     */
    private static final List<String> TOOL_PLUGINS = List.of(
            "FastAsyncWorldEdit",
            "FastAsyncVoxelSniper",
            "AxiomPaper",
            "Axiom",
            "goPaintAdvanced",
            "CreativeUtilities",
            "EasyArmorStands",
            "HeadDatabase");

    private WorkspaceFiles() {}

    /**
     * @param serverRoot the directory every path is relative to ({@code /app} in the image)
     * @param worldRoots every world folder to include; duplicates and folders that do not exist are
     *     ignored
     * @return the files by their server-relative path, sorted
     */
    public static Map<String, Path> collect(Path serverRoot, Collection<Path> worldRoots, Logger logger)
            throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize();
        Map<String, Path> files = new TreeMap<>();
        for (Path world : worldRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList()) {
            if (Files.isDirectory(world)) {
                for (Path file : WorldArchive.collect(world)) {
                    add(root, file, files, logger);
                }
            }
        }

        Path plugins = root.resolve("plugins");
        addMatching(root, plugins.resolve("BuildSystem"), files, logger);
        Path links = plugins.resolve("GroundsMaps").resolve("links.yml");
        if (Files.isRegularFile(links)) {
            add(root, links, files, logger);
        }
        for (String tool : TOOL_PLUGINS) {
            Path folder = plugins.resolve(tool);
            addMatching(root, folder, files, logger);
            Path schematics = folder.resolve("schematics");
            if (Files.isDirectory(schematics)) {
                try (Stream<Path> walk = Files.walk(schematics)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        add(root, file, files, logger);
                    }
                }
            }
        }
        return files;
    }

    /** The configuration files directly in {@code folder}: {@code *.yml} and {@code *.json}. */
    private static void addMatching(Path root, Path folder, Map<String, Path> files, Logger logger) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> list = Files.list(folder)) {
            for (Path file : list.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.endsWith(".yml") || name.endsWith(".json");
                    })
                    .toList()) {
                add(root, file, files, logger);
            }
        }
    }

    private static void add(Path root, Path file, Map<String, Path> files, Logger logger) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            logger.warning("Working copy: skipping " + absolute + ", which is outside the server root " + root);
            return;
        }
        String path = root.relativize(absolute).toString().replace('\\', '/');
        if (!isValidPath(path)) {
            logger.warning("Working copy: skipping " + path + ", whose name the registry would refuse");
            return;
        }
        files.put(path, absolute);
    }

    /** The registry's rule for a path: its characters, and no empty, {@code .} or {@code ..} segment. */
    static boolean isValidPath(String path) {
        if (!VALID_PATH.matcher(path).matches()) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
