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

import gg.grounds.buildsystem.registry.RegistryClient;
import gg.grounds.buildsystem.registry.RegistryException;
import gg.grounds.buildsystem.registry.TokenSource;
import gg.grounds.buildsystem.registry.WorkspaceConflictException;
import gg.grounds.buildsystem.registry.WorkspaceEntry;
import gg.grounds.buildsystem.registry.WorkspaceHead;
import gg.grounds.buildsystem.registry.WorkspaceSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One pass of the working copy: hash what changed, upload what the registry lacks, commit.
 *
 * <p>Not thread-safe, and it does not have to be: {@link WorkspaceLoop} runs every pass on one
 * thread. A pass never touches Bukkit — saving the worlds happens on the main thread before.
 *
 * <p><strong>Torn files.</strong> Paper writes region files asynchronously, and FAWE and Axiom
 * write them outside Paper's save path, so a file can be read while it is being written. Two
 * defences, neither complete: a file modified within {@link #quietPeriod} keeps the entry it had
 * in the last commit (or is left out if it never had one), and every uploaded file is hashed again
 * afterwards — if the bytes moved during the upload, the snapshot is abandoned and the next pass
 * tries again.
 */
@NullMarked
public final class WorkspaceSync {

    /** What a pass did, for the log and the tests. */
    public enum Outcome {
        /** Nothing changed since the last commit; not even the registry was asked. */
        UNCHANGED,
        COMMITTED,
        /** A file changed while it was uploaded. Nothing was committed; the next pass retries. */
        ABANDONED,
        /**
         * The registry's shrink guard refused a manifest much smaller than the head. Nothing was
         * committed; the next pass retries, and keeps being refused until a builder looks.
         */
        SHRINK_REFUSED,
        /** More files than the registry accepts in one manifest. Nothing was sent. */
        TOO_MANY_FILES
    }

    /** The registry's cap on one manifest. Above it nothing is sent, rather than a partial copy. */
    static final int MAX_FILES = 20_000;

    private final RegistryClient registry;
    private final TokenSource auth;
    private final String server;
    private final Path serverRoot;
    private final Logger logger;

    private @Nullable Long base;
    /** What the head holds, by path. The quiet-period fallback and the change check read it. */
    private Map<String, WorkspaceEntry> committed;

    private final Map<Path, CachedHash> hashes = new HashMap<>();
    private boolean worldDeleted;

    private record CachedHash(long size, long modified, String sha256) {}

    public WorkspaceSync(
            RegistryClient registry,
            TokenSource auth,
            String server,
            Path serverRoot,
            @Nullable WorkspaceHead head,
            Logger logger) {
        this.registry = registry;
        this.auth = auth;
        this.server = server;
        this.serverRoot = serverRoot;
        this.logger = logger;
        this.base = head == null ? null : head.snapshot();
        this.committed = byPath(head == null ? List.of() : head.files());
    }

    /** BuildSystem deleted a world, so the next commit may legitimately be much smaller. */
    public void worldDeleted() {
        this.worldDeleted = true;
    }

    public @Nullable Long base() {
        return base;
    }

    /**
     * @param worldRoots every world folder, gathered on the main thread — Bukkit's world list and
     *     BuildSystem's storage are not safe to read from here
     * @param quietPeriod how long a file must have been left alone to be read; zero on shutdown,
     *     where the worlds were just saved and nothing will write after
     * @throws WorkspaceConflictException when another writer moved the head; the caller must stop
     *     syncing
     */
    public Outcome pass(Collection<Path> worldRoots, Duration quietPeriod) throws IOException, RegistryException {
        Instant settled = Instant.now().minus(quietPeriod);
        Map<String, Path> files = WorkspaceFiles.collect(serverRoot, worldRoots, logger);

        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>();
        Map<String, Path> local = new HashMap<>();
        for (Map.Entry<String, Path> file : files.entrySet()) {
            BasicFileAttributes attributes = Files.readAttributes(file.getValue(), BasicFileAttributes.class);
            if (attributes.lastModifiedTime().toInstant().isAfter(settled)) {
                WorkspaceEntry previous = committed.get(file.getKey());
                if (previous != null) {
                    entries.put(file.getKey(), previous);
                }
                continue;
            }
            String sha256 = hash(file.getValue(), attributes);
            entries.put(file.getKey(), new WorkspaceEntry(file.getKey(), sha256, attributes.size()));
            local.putIfAbsent(sha256, file.getValue());
        }
        hashes.keySet().retainAll(files.values());

        if (entries.equals(committed)) {
            return Outcome.UNCHANGED;
        }
        if (entries.size() > MAX_FILES) {
            logger.severe("Working copy: " + entries.size() + " files, more than the registry's " + MAX_FILES
                    + " per snapshot — not syncing until there are fewer.");
            return Outcome.TOO_MANY_FILES;
        }

        WorkspaceSnapshot snapshot;
        try {
            snapshot =
                    registry.createWorkspaceSnapshot(auth, server, base, List.copyOf(entries.values()), worldDeleted);
        } catch (WorkspaceConflictException e) {
            if (!e.shrinkRefused()) {
                throw e;
            }
            logger.severe("Working copy: " + e.getMessage() + " — retrying next pass.");
            return Outcome.SHRINK_REFUSED;
        }
        List<WorkspaceSnapshot.Missing> uploaded = new ArrayList<>();
        for (WorkspaceSnapshot.Missing missing : snapshot.missing()) {
            Path file = local.get(missing.sha256());
            if (file == null) {
                // A blob the head named is gone from the bucket and this file is too fresh to
                // re-read. Retry once it has settled.
                logger.warning("Working copy: the registry lacks a blob this pass could not re-read; retrying");
                return Outcome.ABANDONED;
            }
            registry.putBlob(missing.url(), file);
            uploaded.add(missing);
        }
        for (WorkspaceSnapshot.Missing missing : uploaded) {
            Path file = local.get(missing.sha256());
            if (!missing.sha256().equals(sha256(file))) {
                logger.info("Working copy: " + serverRoot.relativize(file) + " changed during upload; retrying");
                hashes.remove(file);
                return Outcome.ABANDONED;
            }
        }

        this.base = registry.commitWorkspaceSnapshot(auth, server, snapshot.snapshot());
        this.committed = entries;
        this.worldDeleted = false;
        return Outcome.COMMITTED;
    }

    private String hash(Path file, BasicFileAttributes attributes) throws IOException {
        long modified = attributes.lastModifiedTime().toMillis();
        CachedHash cached = hashes.get(file);
        if (cached != null && cached.size() == attributes.size() && cached.modified() == modified) {
            return cached.sha256();
        }
        String sha256 = sha256(file);
        hashes.put(file, new CachedHash(attributes.size(), modified, sha256));
        return sha256;
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, WorkspaceEntry> byPath(List<WorkspaceEntry> files) {
        Map<String, WorkspaceEntry> map = new LinkedHashMap<>();
        for (WorkspaceEntry file : files) {
            map.put(file.path(), file);
        }
        return map;
    }
}
