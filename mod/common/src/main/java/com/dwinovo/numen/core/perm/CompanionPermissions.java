package com.dwinovo.numen.core.perm;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.platform.Services;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.permissions.PermissionLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side store mapping a companion NAME (lower-cased, trimmed) to the
 * {@link PermissionLevel} tier its owner has granted it. This is the policy the
 * {@code run_command} tool caps itself against — see
 * {@code com.dwinovo.numen.core.tools.RunCommandTool}.
 *
 * <p>The store is keyed by companion NAME rather than UUID on purpose: it is the
 * same handle the owner types (<code>/numen player summon &lt;name&gt;</code>,
 * <code>/numenperm &lt;name&gt; &lt;tier&gt;</code>) and it survives a companion
 * respawn (which mints a fresh UUID). {@code self.getName().getString()} on the
 * body equals the summon name (the profile carries it — see
 * {@code CompanionFactory}), so lookup at command time is stable.
 *
 * <h2>File</h2>
 * <p>{@code <configDir>/numen/companion_permissions.json}, shape:
 * <pre>{@code
 *   { "levels": { "test": "gamemasters", "ally": "admins" } }
 * }</pre>
 * Values are {@link PermissionLevel} serialized names
 * ("all"/"moderators"/"gamemasters"/"admins"/"owners"). The default for an
 * absent companion is {@link PermissionLevel#ALL} (the lowest tier).
 *
 * <h2>Persistence style</h2>
 * <p>Mirrors the engine's non-destructive config handling (see
 * {@code ModelRegistry}): the cache is lazy-loaded once and kept in memory;
 * {@link #set} writes the whole file back atomically (temp file + move). A
 * structurally corrupt file is renamed aside (never overwritten blind) and the
 * store starts fresh; individual unknown-tier entries are skipped with a warning
 * rather than discarding the rest.
 *
 * <h2>Thread context</h2>
 * <p><strong>Server thread only.</strong> The cache and its file I/O are not
 * synchronized. All callers ({@code run_command} execution and the
 * {@code /numenperm} command) run on the server thread.
 */
public final class CompanionPermissions {

    /** Tier assumed for any companion with no stored entry — the lowest, least-privileged tier. */
    public static final PermissionLevel DEFAULT_LEVEL = PermissionLevel.ALL;

    /** Lazily loaded; {@code null} until the first {@link #load()}. Insertion-ordered for a stable file. */
    private static Map<String, PermissionLevel> cache;

    private CompanionPermissions() {}

    // ---- public API (server thread only) ----

    /** The tier granted to {@code companionName}, or {@link #DEFAULT_LEVEL} if none is stored. */
    public static PermissionLevel get(String companionName) {
        load();
        return cache.getOrDefault(key(companionName), DEFAULT_LEVEL);
    }

    /** Grant/revoke a companion's tier and persist immediately (atomic write). */
    public static void set(String companionName, PermissionLevel level) {
        load();
        cache.put(key(companionName), level == null ? DEFAULT_LEVEL : level);
        save();
    }

    /** An immutable snapshot of every stored (name → tier) entry. */
    public static Map<String, PermissionLevel> all() {
        load();
        return Map.copyOf(cache);
    }

    /**
     * The tier ceiling to apply when a companion's OP master switch is ON: the owner's explicit
     * {@code /numenperm} tier if one was set, otherwise {@link PermissionLevel#GAMEMASTERS} — the sensible
     * "OP on" default so flipping OP actually grants operator-tier commands even before the dial is touched.
     * (Distinct from {@link #get}, which returns the {@link #DEFAULT_LEVEL ALL} default and drives the
     * command's own query/display; OP-off flooring to ALL is handled by the caller, not here.)
     */
    public static PermissionLevel tierWhenOpEnabled(String companionName) {
        load();
        PermissionLevel explicit = cache.get(key(companionName));
        return explicit != null ? explicit : PermissionLevel.GAMEMASTERS;
    }

    // ---- tier name helpers (shared with the /numenperm command) ----

    /** The serialized tier names, lowest → highest, for command suggestions. */
    public static List<String> tierNames() {
        List<String> out = new ArrayList<>(PermissionLevel.values().length);
        for (PermissionLevel l : PermissionLevel.values()) {
            out.add(l.getSerializedName());
        }
        return out;
    }

    /**
     * Parse a tier from user text, accepting the {@link PermissionLevel} serialized
     * name ("gamemasters") or the enum constant name ("GAMEMASTERS"),
     * case-insensitively. Returns {@code null} if it matches neither.
     */
    public static PermissionLevel tierByName(String name) {
        if (name == null) return null;
        String s = name.strip();
        for (PermissionLevel l : PermissionLevel.values()) {
            if (l.getSerializedName().equalsIgnoreCase(s) || l.name().equalsIgnoreCase(s)) {
                return l;
            }
        }
        return null;
    }

    // ---- internals ----

    private static String key(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve("numen").resolve("companion_permissions.json");
    }

    private static void load() {
        if (cache != null) return;
        Map<String, PermissionLevel> loaded = new LinkedHashMap<>();
        Path file = file();
        try {
            if (Files.exists(file)) {
                parse(Files.readString(file, StandardCharsets.UTF_8), loaded);
            }
        } catch (Exception e) {
            // Structural corruption: preserve the bad file for the user and start fresh.
            Constants.LOG.warn("[numen-core] companion_permissions.json is unreadable/corrupt; "
                    + "backing it up and starting fresh", e);
            backup(file);
            loaded.clear();
        }
        cache = loaded;
    }

    private static void parse(String json, Map<String, PermissionLevel> out) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();   // throws on non-object → treated as corrupt
        if (!root.has("levels") || !root.get("levels").isJsonObject()) {
            return;   // valid JSON without a "levels" object → simply empty, not corrupt
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("levels").entrySet()) {
            String raw = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : null;
            PermissionLevel lvl = tierByName(raw);
            if (lvl == null) {
                Constants.LOG.warn("[numen-core] companion_permissions.json: unknown tier '{}' for '{}', skipping",
                        raw, e.getKey());
                continue;
            }
            out.put(key(e.getKey()), lvl);   // re-normalize the key defensively
        }
    }

    private static void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            JsonObject levels = new JsonObject();
            for (Map.Entry<String, PermissionLevel> e : cache.entrySet()) {
                levels.addProperty(e.getKey(), e.getValue().getSerializedName());
            }
            JsonObject root = new JsonObject();
            root.add("levels", levels);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);

            // Atomic write: stage to a sibling temp file, then move over the target so a crash
            // mid-write can never leave a half-written config.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicUnsupported) {
                // Some filesystems don't support ATOMIC_MOVE — fall back to a plain replace.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Constants.LOG.error("[numen-core] failed to persist companion_permissions.json", e);
        }
    }

    private static void backup(Path file) {
        try {
            Path bak = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
            Constants.LOG.warn("[numen-core] moved corrupt companion_permissions.json to {}", bak.getFileName());
        } catch (Exception e) {
            // If we can't even back it up, leave it: the next save() will overwrite with valid content.
            Constants.LOG.warn("[numen-core] couldn't back up corrupt companion_permissions.json", e);
        }
    }
}
