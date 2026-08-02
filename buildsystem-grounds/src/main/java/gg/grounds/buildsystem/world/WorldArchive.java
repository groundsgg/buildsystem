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

import com.github.luben.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jspecify.annotations.NullMarked;

/**
 * Packs a world folder into the `.tar.zst` the registry addresses by digest.
 *
 * <p><strong>The archive is byte-for-byte reproducible.</strong> Entries are sorted, and every
 * mode, timestamp, owner and group is fixed. That is not tidiness: the registry keys bundles by
 * their sha256, so an unchanged world must produce an unchanged digest — otherwise every push
 * uploads and stores a fresh copy of bytes that already exist, and a "nothing changed" push is
 * indistinguishable from a real edit.
 *
 * <p>What is deliberately left out is player state: {@code playerdata}, {@code stats},
 * {@code advancements}. A map is a place, not the people who visited it while it was being built —
 * and shipping a builder's inventory to every game server is both a size problem and a privacy one.
 */
@NullMarked
public final class WorldArchive {

    /**
     * Player state and the lock file. {@code session.lock} is held by the running server, and a
     * {@code uid.dat} copied into a second world makes two worlds claim one identity.
     */
    private static final Set<String> EXCLUDED =
            Set.of("playerdata", "stats", "advancements", "session.lock", "uid.dat");

    private WorldArchive() {}

    /** What packing produced: where it is, what it hashes to, and how big it turned out. */
    public record Archive(Path file, String sha256, long sizeBytes) {}

    /**
     * @param worldFolder the world directory, e.g. {@code <server>/bedwars_crater}
     * @param target where to write the archive; the caller owns deleting it
     */
    public static Archive pack(Path worldFolder, Path target) throws IOException {
        if (!Files.isDirectory(worldFolder)) {
            throw new IOException("not a world folder: " + worldFolder);
        }
        List<Path> files = collect(worldFolder);

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }

        try (OutputStream out = Files.newOutputStream(target);
                DigestOutputStream digested = new DigestOutputStream(out, digest);
                ZstdOutputStream zstd = new ZstdOutputStream(digested, 9);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
            // Region file names are short, but a nested datapack path can exceed the 100 bytes
            // classic tar allows. Truncating one would corrupt a world in a way that only shows
            // up when a server loads it.
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Path file : files) {
                String name = worldFolder.relativize(file).toString().replace('\\', '/');
                TarArchiveEntry entry = new TarArchiveEntry(file.toFile(), name);
                entry.setModTime(0L);
                entry.setMode(0100644);
                entry.setUserId(0);
                entry.setGroupId(0);
                entry.setUserName("");
                entry.setGroupName("");
                tar.putArchiveEntry(entry);
                Files.copy(file, tar);
                tar.closeArchiveEntry();
            }
        }
        return new Archive(target, HexFormat.of().formatHex(digest.digest()), Files.size(target));
    }

    private static List<Path> collect(Path worldFolder) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(
                worldFolder,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        boolean excluded =
                                !dir.equals(worldFolder)
                                        && EXCLUDED.contains(dir.getFileName().toString());
                        return excluded ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()
                                && !EXCLUDED.contains(file.getFileName().toString())) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        // Sorted by the archive-relative path rather than by the OS's walk order, which differs
        // between machines and would make the same world hash differently on two build servers.
        files.sort(Comparator.comparing(file -> worldFolder.relativize(file).toString()));
        return files;
    }
}
