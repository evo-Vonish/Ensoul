package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.Services;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom companion skins, read from {@code <gameDir>/config/numen_api/skins}.
 *
 * <p>A companion named {@code Momo} renders with {@code momo.png} (64x64 modern
 * layout); a {@code momo.slim.png} file instead selects the slim-arm model.
 * Without a per-name file, {@code default.png} / {@code default.slim.png}
 * covers every companion. No file at all &rarr; the vanilla profile skin,
 * untouched.
 *
 * <p>Files are read once per name and cached for the session (the pixels are
 * uploaded to the GPU on first use). {@link #invalidate(String)} evicts one
 * name so {@code SkinPicker} committing a freshly-chosen file takes effect on
 * the very next lookup &mdash; no client restart, superseding what this class
 * used to require.
 *
 * <p>Client main thread only &mdash; lookups happen inside the player renderer,
 * and {@link #invalidate} is likewise only ever reached from client-thread
 * code ({@code SkinPicker.commit}, itself always called after the picker's
 * background thread has already marshalled back through
 * {@code Minecraft.getInstance().execute}).
 */
public final class NumenSkins {

    /** Cache by lower-cased companion name; empty = no custom file, use vanilla. */
    private static final Map<String, Optional<PlayerSkin>> CACHE = new HashMap<>();

    /**
     * The GPU texture id currently backing {@link #CACHE}'s entry for a key,
     * so a later successful reload knows what to release. Populated only for
     * keys that resolved to a real (non-vanilla) skin; a key that has only
     * ever missed never appears here.
     */
    private static final Map<String, Identifier> TEX_IDS = new HashMap<>();

    /**
     * The last skin that ever successfully loaded for a key, kept alive
     * independent of {@link #CACHE} and {@link #invalidate}.
     *
     * <p>Why: {@link #invalidate} only evicts {@link #CACHE} — it deliberately
     * does not touch this map or release anything, because the companion it
     * names may not be the one currently rendering (out of render distance,
     * off-screen for a filming client watching a different companion, ...).
     * If a reload attempted after invalidation ever misses — the copy in
     * {@code SkinPicker.commit} landing on disk races the very next lookup,
     * or the file was simply removed — {@link #cached} falls back to this
     * instead of the vanilla default, so a companion that already had a face
     * never flashes back to blank because of a transient miss. The texture
     * this points at is only ever released once {@link #load} has a proven
     * replacement in hand (see the swap at the end of that method) — never
     * eagerly — so it is always safe to keep serving.
     */
    private static final Map<String, PlayerSkin> LAST_GOOD = new HashMap<>();

    /** Monotonic suffix so every successful {@link #load} registers a texture
     * under a brand-new id. A stable, name-derived id would force a choice
     * between releasing the old texture before the new one is registered
     * (a companion rendering in that gap draws a destroyed texture) or after
     * (relying on {@code TextureManager.register} to safely replace an
     * already-registered id, which this class has no way to verify). A fresh
     * id every time sidesteps the question: old and new are simultaneously
     * valid, and {@link #load} releases the old one only once the new one is
     * confirmed live. */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private NumenSkins() {}

    /** The custom skin for this companion, or {@code null} for the vanilla skin. */
    public static PlayerSkin lookup(String companionName) {
        Optional<PlayerSkin> own = cached(companionName.toLowerCase(Locale.ROOT));
        if (own.isPresent()) return own.get();
        return cached("default").orElse(null);
    }

    /**
     * Evict {@code name}'s cached resolution so the next {@link #lookup} rereads
     * the skins directory instead of serving the answer from before a skin was
     * (re)picked. Deliberately does NOT touch {@link #TEX_IDS} or
     * {@link #LAST_GOOD} — see {@link #LAST_GOOD}'s javadoc for why the old
     * GPU texture must stay alive until a reload actually replaces it, not the
     * instant this runs.
     *
     * <p>Also evicts the literal {@code name + ".slim"} key defensively: today
     * slim vs. wide is resolved by file presence under the single {@code name}
     * key (see {@link #load}), never a distinct cache key, so this is a no-op
     * — but it costs nothing and guards against a future keying change quietly
     * leaving a stale slim entry behind.
     */
    public static void invalidate(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        CACHE.remove(key);
        CACHE.remove(key + ".slim");
    }

    private static Optional<PlayerSkin> cached(String key) {
        Optional<PlayerSkin> hit = CACHE.get(key);
        if (hit == null) {
            hit = load(key);
            if (hit.isEmpty()) {
                // Ambiguous miss: either this name never had a custom skin
                // (the common case — LAST_GOOD is empty too, falls through
                // below exactly as before), or it did and this particular
                // reload attempt lost a race. Prefer the last thing that
                // actually rendered over snapping to the vanilla default.
                PlayerSkin lastGood = LAST_GOOD.get(key);
                if (lastGood != null) hit = Optional.of(lastGood);
            }
            CACHE.put(key, hit);
        }
        return hit;
    }

    private static Optional<PlayerSkin> load(String key) {
        Path dir = skinsDir();
        Path slimFile = dir.resolve(key + ".slim.png");
        boolean slim = Files.isRegularFile(slimFile);
        Path file = slim ? slimFile : dir.resolve(key + ".png");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID,
                    "skins/" + texturePath(key) + (slim ? "_slim" : "") + "_g" + GENERATION.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
            Constants.LOG.info("[numen-skin] {} → {} ({})", key, file.getFileName(), slim ? "slim" : "wide");
            ClientAsset.Texture body = new ClientAsset.DownloadedTexture(id, "");
            PlayerSkin skin = PlayerSkin.insecure(body, null, null,
                    slim ? PlayerModelType.SLIM : PlayerModelType.WIDE);

            // Only now — replacement registered and live — release whatever
            // this key was showing before. Never the reverse order (see
            // GENERATION's javadoc).
            Identifier old = TEX_IDS.put(key, id);
            if (old != null) {
                Minecraft.getInstance().getTextureManager().release(old);
            }
            LAST_GOOD.put(key, skin);
            return Optional.of(skin);
        } catch (IOException e) {
            Constants.LOG.warn("[numen-skin] unreadable skin file {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /** {@code <gameDir>/config/numen_api/skins}, shared with {@code SkinPicker}
     *  so both classes agree on where a picked file lands. */
    static Path skinsDir() {
        return Services.PLATFORM.getConfigDir().resolve("numen_api").resolve("skins");
    }

    /** Identifier paths only allow [a-z0-9/._-]; names outside that (e.g. CJK)
     *  keep their legal chars and gain a hash suffix so distinct names cannot
     *  collide after sanitising. */
    private static String texturePath(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            }
        }
        if (sb.length() == key.length()) return sb.toString();
        return (sb.isEmpty() ? "skin" : sb.toString()) + "_" + Integer.toHexString(key.hashCode());
    }
}
