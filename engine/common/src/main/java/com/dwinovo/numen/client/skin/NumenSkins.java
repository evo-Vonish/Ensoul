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
 * uploaded to the GPU on first use); restart the client after swapping a PNG.
 * Client main thread only &mdash; lookups happen inside the player renderer.
 */
public final class NumenSkins {

    /** Cache by lower-cased companion name; empty = no custom file, use vanilla. */
    private static final Map<String, Optional<PlayerSkin>> CACHE = new HashMap<>();

    private NumenSkins() {}

    /** The custom skin for this companion, or {@code null} for the vanilla skin. */
    public static PlayerSkin lookup(String companionName) {
        Optional<PlayerSkin> own = cached(companionName.toLowerCase(Locale.ROOT));
        if (own.isPresent()) return own.get();
        return cached("default").orElse(null);
    }

    private static Optional<PlayerSkin> cached(String key) {
        Optional<PlayerSkin> hit = CACHE.get(key);
        if (hit == null) {
            hit = load(key);
            CACHE.put(key, hit);
        }
        return hit;
    }

    private static Optional<PlayerSkin> load(String key) {
        Path dir = Services.PLATFORM.getConfigDir().resolve("numen_api").resolve("skins");
        Path slimFile = dir.resolve(key + ".slim.png");
        boolean slim = Files.isRegularFile(slimFile);
        Path file = slim ? slimFile : dir.resolve(key + ".png");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            Identifier id = Identifier.fromNamespaceAndPath(
                    Constants.MOD_ID, "skins/" + texturePath(key) + (slim ? "_slim" : ""));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
            Constants.LOG.info("[numen-skin] {} → {} ({})", key, file.getFileName(), slim ? "slim" : "wide");
            ClientAsset.Texture body = new ClientAsset.DownloadedTexture(id, "");
            return Optional.of(PlayerSkin.insecure(body, null, null,
                    slim ? PlayerModelType.SLIM : PlayerModelType.WIDE));
        } catch (IOException e) {
            Constants.LOG.warn("[numen-skin] unreadable skin file {}: {}", file, e.toString());
            return Optional.empty();
        }
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
