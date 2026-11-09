package gg.grounds.buildsystem.command;

import gg.grounds.buildsystem.registry.RegistryClient.PushPhase;
import gg.grounds.buildsystem.registry.RegistryClient.PushUpdate;
import java.util.function.Consumer;
import org.jspecify.annotations.NullMarked;

/** Every state mutation, player message and final link action uses the supplied server-thread relay. */
@NullMarked
final class PublishFeedback {
    private final PublishSessions sessions;
    private final PublishSessions.Session session;
    private final Consumer<Runnable> main;
    private final Consumer<String> info;
    private final Consumer<String> error;

    PublishFeedback(
            PublishSessions sessions,
            PublishSessions.Session session,
            Consumer<Runnable> main,
            Consumer<String> info,
            Consumer<String> error) {
        this.sessions = sessions;
        this.session = session;
        this.main = main;
        this.info = info;
        this.error = error;
    }

    void archiveSize(long bytes) {
        main.accept(() -> {
            if (sessions.isActive(session)) session.archiveBytes = bytes;
        });
    }

    void update(PushUpdate update) {
        main.accept(() -> {
            if (!sessions.isActive(session)) return;
            session.last = update;
            // READY is confirmed by the client; its final success/link action follows separately.
            if (update.phase() != PushPhase.READY) info.accept(PublishMessages.progress(session, update));
        });
    }

    void failed(String detail) {
        main.accept(() -> {
            if (sessions.isActive(session)) error.accept(PublishMessages.failure(session, detail));
        });
    }

    void complete(Runnable ready) {
        main.accept(() -> {
            if (!sessions.isActive(session)) return;
            if (session.last.phase() != PushPhase.READY)
                throw new IllegalStateException("publish completion requires confirmed readiness");
            ready.run();
        });
    }

    void finish(Runnable cleanup) {
        main.accept(() -> {
            if (!sessions.isActive(session)) return;
            try {
                cleanup.run();
            } finally {
                sessions.finish(session);
            }
        });
    }
}
