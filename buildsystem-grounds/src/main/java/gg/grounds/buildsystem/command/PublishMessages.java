package gg.grounds.buildsystem.command;

import gg.grounds.buildsystem.registry.MapVersion.Lod;
import gg.grounds.buildsystem.registry.RegistryClient.PushPhase;
import gg.grounds.buildsystem.registry.RegistryClient.PushUpdate;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class PublishMessages {
    private PublishMessages() {}

    static String progress(PublishSessions.Session session, PushUpdate update) {
        String label = label(update.phase());
        if (update.phase() == PushPhase.UPLOADING && session.archiveBytes >= 0)
            label += String.format(Locale.ROOT, " %.1f MB", session.archiveBytes / 1024.0 / 1024.0);
        if (update.heartbeat()) {
            long seconds = update.elapsed().toSeconds();
            label = "Still " + Character.toLowerCase(label.charAt(0)) + label.substring(1) + " (" + seconds / 60 + "m "
                    + seconds % 60 + "s elapsed)";
        }
        return prefix(session) + label + "…";
    }

    static String failure(PublishSessions.Session session, String detail) {
        return prefix(session) + label(session.last.phase()) + " failed: " + detail;
    }

    static String already(PublishSessions.Session session) {
        return prefix(session) + "Already publishing: " + label(session.last.phase()) + ".";
    }

    static String lodSummary(@Nullable Lod lod) {
        if (lod == null || lod.stage() == null) return "LODs: status unavailable from the map service.";
        return switch (lod.stage()) {
            case "READY" ->
                lod.columns() != null && lod.columns() > 0 && lod.bundleBytes() != null && lod.bundleBytes() > 0
                        ? String.format(
                                Locale.ROOT,
                                "LODs ready: %,d chunks, %.1f MB.",
                                lod.columns(),
                                lod.bundleBytes() / 1024.0 / 1024.0)
                        : "LODs ready.";
            case "WAITING_FOR_ACTIVATION" ->
                "LODs: waiting for Stage activation of this version; generation starts afterwards.";
            case "WAITING_FOR_MAP" -> "LODs: waiting for map preparation.";
            case "WAITING_FOR_LIGHT" -> "LODs: waiting for lighting.";
            case "QUEUED" -> "LODs: queued for generation.";
            case "GENERATING" -> "LODs: generation is running.";
            case "FAILED" ->
                "LOD generation failed. The prepared map remains available; an admin can check the failure.";
            case "LIMIT_REACHED" ->
                "LOD generation paused: the measurement limit has been reached. The prepared map remains available.";
            case "NOT_CONFIGURED" -> "LOD generation is not enabled for this map.";
            default -> "LODs: status unavailable from the map service.";
        };
    }

    private static String prefix(PublishSessions.Session session) {
        return "[" + session.address + (session.last.version() > 0 ? " v" + session.last.version() : "") + "] ";
    }

    private static String label(PushPhase phase) {
        return switch (phase) {
            case CHECKING -> "Checking map data";
            case SAVING -> "Saving world";
            case PACKING -> "Packing world";
            case UPLOADING -> "Uploading";
            case SUBMITTING -> "Submitting map for preparation";
            case QUEUED -> "Waiting for map preparation";
            case DERIVING -> "Preparing map/scene";
            case AWAITING_REVIEW -> "Waiting for review";
            case WAITING_FOR_LIGHT -> "Waiting for lighting";
            case LIGHTING -> "Calculating light";
            case WAITING_FOR_LOD -> "Waiting for LOD generation";
            case GENERATING_LOD -> "Generating LODs";
            case CONTACTING_REGISTRY -> "Checking preparation status";
            case READY -> "Ready to go live";
        };
    }
}
