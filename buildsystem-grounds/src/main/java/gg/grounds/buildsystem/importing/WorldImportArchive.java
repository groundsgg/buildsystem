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
package gg.grounds.buildsystem.importing;

import com.github.luben.zstd.ZstdInputStream;
import gg.grounds.buildsystem.registry.RegistryException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.jspecify.annotations.NullMarked;

/**
 * Unpacks a world someone else made into a staging folder, keeping only what a map is.
 *
 * <p><strong>An allowlist, not a blocklist.</strong> A downloaded world is untrusted, and the
 * dangerous part is not the terrain: a {@code datapacks/} folder can carry functions tagged
 * {@code #minecraft:load} that the server runs with operator rights the moment the world loads.
 * So only files a map needs are written — the level, its chunk, entity and point-of-interest
 * regions, world data, and the Grounds metadata — and everything else is counted and dropped.
 *
 * <p>Archives from the internet usually wrap the world in a folder, and a full save carries the
 * nether and end as nested worlds. The world root is the shallowest folder holding
 * {@code region/r.X.Z.mca}; only the overworld is imported.
 *
 * <p>Both formats are recognised by their first bytes rather than by the URL's file name, which
 * the other end chooses.
 */
@NullMarked
public final class WorldImportArchive {

    private static final Pattern REGION = Pattern.compile("^(.*/)?region/r\\.-?\\d+\\.-?\\d+\\.mca$");

    private static final List<Pattern> ALLOWED = List.of(
            Pattern.compile("^level\\.dat$"),
            Pattern.compile("^(region|entities|poi)/r\\.-?\\d+\\.-?\\d+\\.mca$"),
            Pattern.compile("^data/[A-Za-z0-9_./-]+\\.dat$"),
            Pattern.compile("^grounds/(setup|pois)\\.json$"),
            Pattern.compile("^scene\\.json$"),
            Pattern.compile("^paper-world\\.yml$"));

    /** The budget one import may spend. */
    public record Limits(long maxUnpackedBytes, int maxEntries) {}

    /** What was written, and how much was left behind. */
    public record Result(int filesWritten, long bytesWritten, int skipped, boolean droppedDatapacks) {}

    private WorldImportArchive() {}

    public static Result extract(Path archive, Path target, Limits limits) throws RegistryException {
        Format format = detect(archive);
        List<String> names = new ArrayList<>();
        visit(archive, format, limits, (name, in) -> names.add(name));
        String root = worldRoot(names);

        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new RegistryException("Could not prepare the staging folder: " + e.getMessage(), e);
        }
        Path base = target.toAbsolutePath().normalize();
        int[] written = {0};
        int[] skipped = {0};
        long[] bytes = {0};
        boolean[] datapacks = {false};
        visit(archive, format, limits, (name, in) -> {
            if (!name.startsWith(root)) {
                skipped[0]++;
                return;
            }
            String relative = name.substring(root.length());
            if (!isAllowed(relative)) {
                skipped[0]++;
                datapacks[0] |= relative.startsWith("datapacks/");
                return;
            }
            Path dest = base.resolve(relative).normalize();
            if (!dest.startsWith(base)) {
                throw new RegistryException("The archive tries to write outside the world folder: " + name);
            }
            Files.createDirectories(dest.getParent());
            try (OutputStream out = Files.newOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytes[0] += read;
                    if (bytes[0] > limits.maxUnpackedBytes()) {
                        throw new RegistryException("The world unpacks to more than "
                                + ImportDownloader.mib(limits.maxUnpackedBytes()) + ".");
                    }
                    out.write(buffer, 0, read);
                }
            }
            written[0]++;
        });
        return new Result(written[0], bytes[0], skipped[0], datapacks[0]);
    }

    /** Whether a path, relative to the world root, is something a map is made of. */
    static boolean isAllowed(String relative) {
        if (relative.contains("..")) {
            return false;
        }
        for (Pattern pattern : ALLOWED) {
            if (pattern.matcher(relative).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The folder, as an archive path prefix ending in "/" or empty, that holds the overworld's
     * region files: the shallowest one, since a full save nests the nether and end below it.
     */
    static String worldRoot(List<String> names) throws RegistryException {
        String best = null;
        boolean tie = false;
        for (String name : names) {
            Matcher m = REGION.matcher(name);
            if (!m.matches()) {
                continue;
            }
            String prefix = m.group(1) == null ? "" : m.group(1);
            if (best == null || depth(prefix) < depth(best)) {
                best = prefix;
                tie = false;
            } else if (depth(prefix) == depth(best) && !prefix.equals(best)) {
                tie = true;
            }
        }
        if (best == null) {
            throw new RegistryException("The archive holds no world: there is no region/r.X.Z.mca in it.");
        }
        if (tie) {
            throw new RegistryException(
                    "The archive holds more than one world side by side; import them one at a time.");
        }
        return best;
    }

    private static int depth(String prefix) {
        return (int) prefix.chars().filter(c -> c == '/').count();
    }

    // ------------------------------------------------------------------ formats

    enum Format {
        ZIP,
        TAR_ZSTD
    }

    static Format detect(Path archive) throws RegistryException {
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(archive)) {
            if (in.readNBytes(head, 0, 4) < 4) {
                throw new RegistryException("The download is not an archive.");
            }
        } catch (IOException e) {
            throw new RegistryException("Could not read the download: " + e.getMessage(), e);
        }
        if (head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            return Format.ZIP;
        }
        if ((head[0] & 0xff) == 0x28
                && (head[1] & 0xff) == 0xb5
                && (head[2] & 0xff) == 0x2f
                && (head[3] & 0xff) == 0xfd) {
            return Format.TAR_ZSTD;
        }
        throw new RegistryException("The download is neither a .zip nor a .tar.zst.");
    }

    @FunctionalInterface
    private interface EntryVisitor {
        void accept(String name, InputStream in) throws IOException, RegistryException;
    }

    /**
     * Calls the visitor for each regular file, with a normalised name. Links and devices are never
     * handed on; a path that is absolute or climbs out fails the whole archive, since an archive
     * built to do that is not a world with one bad file.
     */
    private static void visit(Path archive, Format format, Limits limits, EntryVisitor visitor)
            throws RegistryException {
        int entries = 0;
        try {
            if (format == Format.ZIP) {
                try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
                    Enumeration<ZipArchiveEntry> all = zip.getEntriesInPhysicalOrder();
                    while (all.hasMoreElements()) {
                        ZipArchiveEntry entry = all.nextElement();
                        if (++entries > limits.maxEntries()) {
                            throw tooMany(limits);
                        }
                        if (entry.isDirectory() || entry.isUnixSymlink()) {
                            continue;
                        }
                        String name = safeName(entry.getName());
                        try (InputStream in = zip.getInputStream(entry)) {
                            visitor.accept(name, in);
                        }
                    }
                }
            } else {
                try (InputStream raw = Files.newInputStream(archive);
                        ZstdInputStream zstd = new ZstdInputStream(raw);
                        TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
                    TarArchiveEntry entry;
                    while ((entry = tar.getNextEntry()) != null) {
                        if (++entries > limits.maxEntries()) {
                            throw tooMany(limits);
                        }
                        if (!entry.isFile()) {
                            continue;
                        }
                        visitor.accept(safeName(entry.getName()), tar);
                    }
                }
            }
        } catch (IOException e) {
            throw new RegistryException("The archive is damaged: " + e.getMessage(), e);
        }
    }

    private static RegistryException tooMany(Limits limits) {
        return new RegistryException("The archive has more than " + limits.maxEntries() + " entries.");
    }

    static String safeName(String raw) throws RegistryException {
        String name = raw.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.startsWith("/")
                || name.indexOf(':') >= 0
                || name.equals("..")
                || name.startsWith("../")
                || name.contains("/../")
                || name.endsWith("/..")) {
            throw new RegistryException("The archive tries to write outside the world folder: " + raw);
        }
        return name;
    }
}
