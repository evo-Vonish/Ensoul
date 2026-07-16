package com.dwinovo.numen.core.perception;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.platform.Services;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Disk-backed tally of how many times each companion has died of each
 * <em>normalized</em> cause — the durable state behind the §8 "same-cause death
 * stop-loss" corrective producer (see {@link CorrectiveNotices}).
 *
 * <h2>Why keyed by NAME, not UUID</h2>
 * The whole point of the stop-loss is that the lesson outlives the death: a
 * companion dies to X, respawns, and must remember "X killed me twice". Companion
 * <em>identity</em> that survives that arc — and a dismiss/resummon, and a server
 * restart — is the NAME, the same stable handle {@link com.dwinovo.numen.core.perm.CompanionPermissions}
 * keys on ({@code self.getName().getString()} equals the summon name). Keying by
 * UUID would be fragile against the very respawn this feature exists to survive.
 * (Trade-off inherited from {@code CompanionPermissions}: two owners with
 * same-named companions share a row. Acceptable for v1; a follow-up could prefix
 * the owner UUID.)
 *
 * <h2>Normalized cause</h2>
 * The caller strips the companion name from the raw death message
 * ({@code "Fenn被铁傀儡杀死了" → "被铁傀儡杀死"}) before it reaches here, so
 * differently-phrased instances of the same death aggregate onto one counter
 * ({@link CorrectiveNotices#normalizeCause}).
 *
 * <h2>File</h2>
 * {@code <configDir>/numen/memory/death_tolls.json} — beside the per-companion
 * region-memory stores ({@code <uuid>.regions.json}) — shape:
 * <pre>{@code
 *   { "tolls": { "fenn": { "被铁傀儡杀死": 3, "从高处摔落": 1 } } }
 * }</pre>
 *
 * <h2>Persistence style</h2>
 * Mirrors {@link com.dwinovo.numen.core.perm.CompanionPermissions}: lazy-loaded
 * once, kept in memory (so it doubles as the session-scope store — it is NOT
 * dropped on death the way {@code PerceptionState} is), and written back whole and
 * atomically (temp file + move) on every {@link #record}. A structurally corrupt
 * file is renamed aside, never overwritten blind, and the store starts fresh.
 *
 * <h2>Reset policy</h2>
 * v1 never auto-decrements — counts are permanent until the owner deletes the
 * json (owner axiom: 创伤与失败不可删除). Follow-up: decay a cause after N game
 * days without a repeat.
 *
 * <h2>Thread context</h2>
 * <strong>Server thread only.</strong> The sole caller ({@code CorrectiveNotices.onDeath}
 * via the {@code CompanionLifecycle.onDeath} seam) runs on the server tick thread;
 * the cache and its file I/O are unsynchronized, exactly like {@code CompanionPermissions}.
 */
final class DeathTolls {

    /** Lazily loaded; {@code null} until the first {@link #load()}. Insertion-ordered for a stable file. */
    private static Map<String, Map<String, Integer>> cache;

    private DeathTolls() {}

    /**
     * Record one death of {@code companionName} by {@code normalizedCause}, persist the
     * updated tally atomically, and return the NEW total for that (companion, cause) —
     * i.e. 1 on the first such death, 2 on the second, and so on. Server thread only.
     */
    static int record(String companionName, String normalizedCause) {
        load();
        Map<String, Integer> byCause = cache.computeIfAbsent(key(companionName), k -> new LinkedHashMap<>());
        int n = byCause.merge(normalizedCause, 1, Integer::sum);
        save();
        return n;
    }

    // ---- internals (mirror CompanionPermissions) ----

    private static String key(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir()
                .resolve("numen").resolve("memory").resolve("death_tolls.json");
    }

    private static void load() {
        if (cache != null) return;
        Map<String, Map<String, Integer>> loaded = new LinkedHashMap<>();
        Path file = file();
        try {
            if (Files.exists(file)) {
                parse(Files.readString(file, StandardCharsets.UTF_8), loaded);
            }
        } catch (Exception e) {
            Constants.LOG.warn("[numen-core] death_tolls.json is unreadable/corrupt; "
                    + "backing it up and starting fresh", e);
            backup(file);
            loaded.clear();
        }
        cache = loaded;
    }

    private static void parse(String json, Map<String, Map<String, Integer>> out) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();   // throws on non-object → treated as corrupt
        if (!root.has("tolls") || !root.get("tolls").isJsonObject()) {
            return;   // valid JSON without a "tolls" object → simply empty, not corrupt
        }
        for (Map.Entry<String, JsonElement> nameEntry : root.getAsJsonObject("tolls").entrySet()) {
            if (!nameEntry.getValue().isJsonObject()) continue;   // skip a malformed row, keep the rest
            Map<String, Integer> byCause = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> causeEntry : nameEntry.getValue().getAsJsonObject().entrySet()) {
                try {
                    int n = causeEntry.getValue().getAsInt();
                    if (n > 0) byCause.put(causeEntry.getKey(), n);
                } catch (Exception skip) {
                    Constants.LOG.warn("[numen-core] death_tolls.json: bad count for '{}'/'{}', skipping",
                            nameEntry.getKey(), causeEntry.getKey());
                }
            }
            out.put(key(nameEntry.getKey()), byCause);   // re-normalize the name key defensively
        }
    }

    private static void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            JsonObject tolls = new JsonObject();
            for (Map.Entry<String, Map<String, Integer>> nameEntry : cache.entrySet()) {
                JsonObject byCause = new JsonObject();
                for (Map.Entry<String, Integer> causeEntry : nameEntry.getValue().entrySet()) {
                    byCause.addProperty(causeEntry.getKey(), causeEntry.getValue());
                }
                tolls.add(nameEntry.getKey(), byCause);
            }
            JsonObject root = new JsonObject();
            root.add("tolls", tolls);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);

            // Atomic write: stage to a sibling temp file, then move over the target so a crash
            // mid-write can never leave a half-written tally (mirrors CompanionPermissions).
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Constants.LOG.error("[numen-core] failed to persist death_tolls.json", e);
        }
    }

    private static void backup(Path file) {
        try {
            Path bak = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
            Constants.LOG.warn("[numen-core] moved corrupt death_tolls.json to {}", bak.getFileName());
        } catch (Exception e) {
            Constants.LOG.warn("[numen-core] couldn't back up corrupt death_tolls.json", e);
        }
    }
}
