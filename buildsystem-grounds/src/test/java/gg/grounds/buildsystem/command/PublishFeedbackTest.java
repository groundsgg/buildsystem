package gg.grounds.buildsystem.command;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.buildsystem.registry.RegistryClient.PushPhase;
import gg.grounds.buildsystem.registry.RegistryClient.PushUpdate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PublishFeedbackTest {
    private final PublishSessions sessions = new PublishSessions();
    private final UUID world = UUID.randomUUID();
    private final ArrayDeque<Runnable> main = new ArrayDeque<>();
    private final List<String> info = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    @Test
    void the_same_world_is_serialized_while_other_worlds_remain_independent() {
        var first = sessions.begin(world, "lobby/mainlobby", "lobby/mainlobby", 13);
        assertNotNull(first);
        assertNull(sessions.begin(world, "lobby/other", "lobby/other", 2));
        var second = sessions.begin(UUID.randomUUID(), "bedwars/map", "bedwars/map", 1);
        assertNotNull(second);
        sessions.finish(first);
        var replacement = sessions.begin(world, "lobby/mainlobby", "lobby/mainlobby", 13);
        sessions.finish(first);
        assertSame(replacement, sessions.active(world));
        assertTrue(sessions.isActive(second));
    }

    @Test
    void progress_and_failures_are_delivered_only_through_the_server_thread() throws Exception {
        var session = sessions.begin(world, "lobby/mainlobby", "lobby/mainlobby", 13);
        var feedback = feedback(session);
        Thread worker = new Thread(() -> {
            feedback.archiveSize(46 * 1024 * 1024L);
            feedback.update(update(PushPhase.UPLOADING, 0, false));
            feedback.update(update(PushPhase.LIGHTING, 14, false));
            feedback.update(update(PushPhase.LIGHTING, 14, true));
            feedback.failed("worker did not finish");
        });
        worker.start();
        worker.join();
        assertTrue(info.isEmpty());
        assertTrue(errors.isEmpty());
        drain();
        assertTrue(info.get(0).contains("46.0 MB"));
        assertTrue(info.get(1).contains("lobby/mainlobby v14"));
        assertTrue(info.get(1).contains("Calculating light"));
        assertTrue(info.get(2).contains("Still calculating light"));
        assertTrue(errors.getFirst().contains("Calculating light"));
        assertTrue(errors.getFirst().contains("worker did not finish"));
    }

    @Test
    void completion_cannot_link_before_readiness_and_cleanup_always_releases_the_world() {
        var session = sessions.begin(world, "lobby/mainlobby", "lobby/mainlobby", 13);
        var feedback = feedback(session);
        AtomicInteger linked = new AtomicInteger();
        feedback.update(update(PushPhase.LIGHTING, 14, false));
        feedback.complete(linked::incrementAndGet);
        main.removeFirst().run();
        assertThrows(IllegalStateException.class, () -> main.removeFirst().run());
        assertEquals(0, linked.get());
        feedback.update(update(PushPhase.READY, 14, false));
        feedback.complete(linked::incrementAndGet);
        drain();
        assertEquals(1, linked.get());
        feedback.finish(() -> {
            throw new IllegalStateException("world unloaded");
        });
        assertThrows(IllegalStateException.class, () -> main.removeFirst().run());
        assertNull(sessions.active(world));
    }

    @Test
    void stale_callbacks_and_deliberate_link_changes_are_preserved() {
        var session = sessions.begin(world, "lobby/mainlobby", "lobby/mainlobby", 13);
        assertTrue(session.canUpdateLink("lobby/mainlobby", 13));
        assertFalse(session.canUpdateLink("lobby/other", 13));
        assertFalse(session.canUpdateLink("lobby/mainlobby", 15));
        var feedback = feedback(session);
        feedback.finish(() -> {});
        feedback.update(update(PushPhase.LIGHTING, 14, false));
        drain();
        assertTrue(info.isEmpty());
        assertNull(sessions.active(world));
        var unlinked = sessions.begin(world, "lobby/mainlobby", null, null);
        assertTrue(unlinked.canUpdateLink(null, null));
        assertFalse(unlinked.canUpdateLink("lobby/mainlobby", 13));
    }

    @Test
    void lod_feedback_separates_success_activation_and_measurement_limits() {
        assertTrue(
                PublishMessages.lodSummary(new gg.grounds.buildsystem.registry.MapVersion.Lod("READY", 1812, 4113662L))
                        .contains("1,812"));
        assertTrue(PublishMessages.lodSummary(
                        new gg.grounds.buildsystem.registry.MapVersion.Lod("WAITING_FOR_ACTIVATION", null, null))
                .contains("activation"));
        assertTrue(PublishMessages.lodSummary(
                        new gg.grounds.buildsystem.registry.MapVersion.Lod("LIMIT_REACHED", null, null))
                .contains("measurement limit"));
        assertTrue(PublishMessages.lodSummary(new gg.grounds.buildsystem.registry.MapVersion.Lod("FAILED", null, null))
                .contains("failed"));
        assertTrue(PublishMessages.lodSummary(null).contains("unavailable"));
    }

    private PublishFeedback feedback(PublishSessions.Session session) {
        Thread serverThread = Thread.currentThread();
        return new PublishFeedback(
                sessions,
                session,
                main::add,
                text -> {
                    assertSame(serverThread, Thread.currentThread());
                    info.add(text);
                },
                text -> {
                    assertSame(serverThread, Thread.currentThread());
                    errors.add(text);
                });
    }

    private void drain() {
        while (!main.isEmpty()) main.removeFirst().run();
    }

    private static PushUpdate update(PushPhase phase, int version, boolean heartbeat) {
        return new PushUpdate(phase, version, Duration.ofSeconds(30), heartbeat);
    }
}
