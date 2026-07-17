package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Per-companion global monotonic event sequence (§3 event envelope). Every event
 * note that lands in the conversation tail is stamped with the next value; the
 * frozen-prefix protocol declares "同主题以最高 seq 为准", so the counter must
 * never restart from 0 mid-history — a restarted counter would make old events
 * outrank new ones.
 *
 * <h2>Persistence design</h2>
 * A tiny sidecar file next to the conversation JSONL —
 * {@code config/numen/conversations/<uuid>.seq} — holding the last issued seq as
 * a plain ASCII decimal. Write-through on every {@link #next()}: events are
 * low-frequency (a handful per turn at most), so the extra write is negligible,
 * and write-through means a crash can never hand out a duplicate seq (worst case
 * after a torn write we fall back to 0 <em>only if the file is corrupt</em>, and
 * a warning is logged). Chosen over folding the counter into ConvoLog metadata
 * to keep ConvoLog a pure message log — the counter is envelope state, not
 * conversation content, and a one-value sidecar file is trivially inspectable
 * and repairable by hand.
 *
 * <p>Client main thread only, like everything in this package.
 */
final class EventSeqCounter {

    private final Path file;
    private long last;

    private EventSeqCounter(Path file) {
        this.file = file;
        this.last = load();
    }

    /** Counter for one companion, stored as {@code <conversationsDir>/<uuid>.seq}. */
    static EventSeqCounter forEntity(Path conversationsDir, UUID entityUuid) {
        return new EventSeqCounter(conversationsDir.resolve(entityUuid + ".seq"));
    }

    /** Issue the next seq (monotonic, 1-based) and persist it immediately. */
    long next() {
        last++;
        persist();
        return last;
    }

    private long load() {
        if (!Files.isRegularFile(file)) return 0L;
        try {
            return Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-seq] could not read {} ({}); restarting event seq at 0",
                    file, ex.toString());
            return 0L;
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Long.toString(last), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-seq] could not persist {} ({}); seq continues in memory",
                    file, ex.toString());
        }
    }
}
