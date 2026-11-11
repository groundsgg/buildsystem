package gg.grounds.buildsystem.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LodStatusFeedbackTest {
    @Test
    void success_and_failure_are_delivered_on_main_thread_and_discarded_after_link_change_or_disconnect() {
        var main = new ArrayDeque<Runnable>();
        var replies = new ArrayList<String>();
        var current = new AtomicBoolean(true);
        var feedback = new LodStatusFeedback(main::add, current::get, replies::add, "[lobby/mainlobby v19] ");
        feedback.unavailable();
        assertTrue(replies.isEmpty());
        main.removeFirst().run();
        assertTrue(replies.getFirst().contains("status unavailable"));
        replies.clear();
        feedback.unavailable();
        feedback.received(null);
        current.set(false);
        while (!main.isEmpty()) main.removeFirst().run();
        assertTrue(replies.isEmpty());
    }
}
