package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side image plumbing for chat attachments: decode a dropped/pasted image
 * file (PNG or JPEG) into a GPU thumbnail for the pending-card row, and persist
 * the original bytes under {@code config/numen/attachments/<uuid>/} so the wire
 * layer can base64-inline them at request-build time.
 *
 * <h2>PNG vs JPEG decode</h2>
 * Minecraft's {@link NativeImage#read} guards on a PNG header, so it rejects
 * JPEG. We try it first (the common case — Windows screenshots and clipboard
 * images are PNG) and fall back to a raw STB decode (STB reads PNG, JPEG, BMP,
 * …) forcing 4-channel RGBA, which is what {@link NativeImage#resizeSubRectTo}
 * needs (same-format only) and what the GUI texture upload expects.
 *
 * <h2>Persistence, not re-encode</h2>
 * We COPY the original file bytes (extension preserved) rather than re-encoding
 * to PNG: lossless, keeps JPEG photos small on the wire, and sidesteps the
 * PNG-only decode gate for the persistence path. The wire MIME is derived from
 * the extension in {@code OpenAIProvider}.
 *
 * <p>Client main thread only (texture registration happens on the render thread).
 */
final class ChatImages {

    private ChatImages() {}

    /** Monotonic id so each registered thumbnail texture gets a unique Identifier path. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** A registered thumbnail texture plus its on-screen display size (px). */
    record Thumb(Identifier texId, int width, int height) {}

    /** True for the extensions we accept as attachments. */
    static boolean isImageFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif");
    }

    /**
     * Decode {@code source}, downscale to fit within {@code maxPx} (longest side),
     * upload as a {@link DynamicTexture}, and return its handle — or {@code null}
     * if the file can't be read/decoded. The full-resolution image is freed here;
     * only the small thumbnail stays resident (owned by the DynamicTexture).
     */
    static Thumb loadThumbnail(Path source, int maxPx) {
        NativeImage full = null;
        try {
            full = decode(source);
            int w = full.getWidth(), h = full.getHeight();
            int longest = Math.max(w, h);
            double scale = longest <= maxPx ? 1.0 : (double) maxPx / longest;
            int tw = Math.max(1, (int) Math.round(w * scale));
            int th = Math.max(1, (int) Math.round(h * scale));

            NativeImage thumb = new NativeImage(NativeImage.Format.RGBA, tw, th, false);
            full.resizeSubRectTo(0, 0, w, h, thumb);   // STB linear downscale, RGBA→RGBA

            Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID,
                    "attachments/thumb_" + COUNTER.incrementAndGet());
            // DynamicTexture takes ownership of `thumb`; releasing the id later
            // (see NumenScreen) closes both the texture and its NativeImage.
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, thumb));
            return new Thumb(id, tw, th);
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-chat] can't load image {}: {}", source, ex.toString());
            return null;
        } finally {
            if (full != null) full.close();
        }
    }

    /**
     * Copy {@code source}'s bytes into {@code config/numen/attachments/<uuid>/}
     * as {@code <timestamp>-<n>.<ext>} and return the persisted path. Best-effort:
     * returns {@code null} on IO failure (the caller drops that image).
     */
    static Path persist(Path source, UUID companion, long timestamp, int index) {
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("numen").resolve("attachments")
                    .resolve(companion.toString());
            Files.createDirectories(dir);
            Path dest = dir.resolve(timestamp + "-" + index + "." + ext(source));
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-chat] can't persist attachment {}: {}", source, ex.toString());
            return null;
        }
    }

    /** Lower-cased file extension (png/jpg/jpeg/…), defaulting to {@code png}. */
    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot == n.length() - 1) return "png";
        return n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Decode to an RGBA {@link NativeImage}; PNG via Minecraft, everything else via STB. */
    private static NativeImage decode(Path source) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return NativeImage.read(NativeImage.Format.RGBA, in);   // PNG (header-validated)
        } catch (Exception pngMiss) {
            return stbDecode(bytes);                                 // JPEG / BMP / …
        }
    }

    /** Raw STB decode forcing 4-channel RGBA; bypasses Minecraft's PNG-only header gate. */
    private static NativeImage stbDecode(byte[] bytes) throws IOException {
        ByteBuffer direct = MemoryUtil.memAlloc(bytes.length);
        try {
            direct.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer comp = stack.mallocInt(1);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(direct, w, h, comp, 4);
                if (pixels == null) {
                    throw new IOException("stb decode failed: " + STBImage.stbi_failure_reason());
                }
                // useStbFree=true → NativeImage.close() frees the STB buffer.
                return new NativeImage(NativeImage.Format.RGBA, w.get(0), h.get(0), true,
                        MemoryUtil.memAddress(pixels));
            }
        } finally {
            MemoryUtil.memFree(direct);
        }
    }
}
