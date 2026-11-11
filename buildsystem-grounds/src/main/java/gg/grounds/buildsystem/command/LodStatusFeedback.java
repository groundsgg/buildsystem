package gg.grounds.buildsystem.command;

import gg.grounds.buildsystem.registry.MapVersion.Lod;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** One explicit status request; both outcomes share the server-thread and current-link fence. */
@NullMarked
final class LodStatusFeedback {
    private final Consumer<Runnable> mainThread;
    private final BooleanSupplier current;
    private final Consumer<String> reply;
    private final String prefix;

    LodStatusFeedback(Consumer<Runnable> mainThread, BooleanSupplier current, Consumer<String> reply, String prefix) {
        this.mainThread = mainThread;
        this.current = current;
        this.reply = reply;
        this.prefix = prefix;
    }

    void received(@Nullable Lod lod) {
        dispatch(PublishMessages.lodSummary(lod));
    }

    void unavailable() {
        dispatch("LODs: status unavailable. The prepared map is unaffected; try /map status again.");
    }

    private void dispatch(String message) {
        mainThread.accept(() -> {
            if (current.getAsBoolean()) reply.accept(prefix + message);
        });
    }
}
