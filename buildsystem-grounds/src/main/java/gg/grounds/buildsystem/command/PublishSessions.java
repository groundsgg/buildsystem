package gg.grounds.buildsystem.command;

import gg.grounds.buildsystem.registry.RegistryClient.PushPhase;
import gg.grounds.buildsystem.registry.RegistryClient.PushUpdate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Owned exclusively by the server thread; a physical world has one snapshot/publish at a time. */
@NullMarked
final class PublishSessions {
    private final Map<UUID, Session> active = new HashMap<>();

    @Nullable Session begin(UUID world, String address, @Nullable String originalAddress, @Nullable Integer base) {
        if (active.containsKey(world)) return null;
        Session session = new Session(world, address, originalAddress, base);
        active.put(world, session);
        return session;
    }

    @Nullable Session active(UUID world) {
        return active.get(world);
    }

    boolean isActive(Session session) {
        return active.get(session.world) == session;
    }

    void finish(Session session) {
        active.remove(session.world, session);
    }

    static final class Session {
        final UUID world;
        final String address;
        private final @Nullable String originalAddress;
        private final @Nullable Integer originalBase;
        PushUpdate last = new PushUpdate(PushPhase.CHECKING, 0, Duration.ZERO, false);
        long archiveBytes = -1;

        Session(UUID world, String address, @Nullable String originalAddress, @Nullable Integer originalBase) {
            this.world = world;
            this.address = address;
            this.originalAddress = originalAddress;
            this.originalBase = originalBase;
        }

        boolean canUpdateLink(@Nullable String address, @Nullable Integer base) {
            return Objects.equals(originalAddress, address) && Objects.equals(originalBase, base);
        }
    }
}
