package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Native file dialog and validate/preview/commit steps that turn a
 * user-chosen PNG into what {@link NumenSkins} reads. Deliberately
 * UI-agnostic — this class knows nothing about screens or widgets; the
 * creation dialog (a later pass, over {@code NumenScreen}) composes these
 * three calls into the "Choose file…" row plus its 24x24 preview and the
 * "replace existing file?" prompt.
 *
 * <h2>Three phases, not one</h2>
 * A companion's name can still change after its skin is picked (the owner is
 * free to keep editing the Name field), and the whole creation dialog can
 * still be cancelled — so picking a file must not, by itself, touch the
 * skins directory. The phases are kept separate on purpose:
 * <ol>
 *   <li>{@link #choose} — native dialog, hands back a raw path.</li>
 *   <li>{@link #preview} — decode + validate only, for the inline 24x24
 *       preview while the dialog is still open. No disk writes.</li>
 *   <li>{@link #commit} — the actual copy into {@code config/numen_api/skins},
 *       plus {@link NumenSkins#invalidate}. Call only once the companion's
 *       name is final (i.e. when Create is actually pressed).</li>
 * </ol>
 *
 * <h2>Why a dedicated thread for the dialog</h2>
 * {@link TinyFileDialogs#tinyfd_openFileDialog} is a blocking native modal —
 * running it on the render thread freezes the game until the OS dialog
 * closes, and on at least Linux/GTK builds can deadlock against GLFW's own
 * event pump. It runs on a throwaway thread instead; the chosen path (if any)
 * is marshalled back onto the client thread via
 * {@code Minecraft.getInstance().execute(...)} — the same idiom
 * {@code NumenActuator} uses to hop back from its own worker thread.
 * {@code catch (Throwable)}, not {@code Exception}: tinyfd is a native shared
 * library LWJGL loads lazily on first use, and its absence surfaces as an
 * {@link UnsatisfiedLinkError}, not anything checked-exception shaped.
 *
 * <p>There is deliberately no import-folder fallback here: dropping a PNG
 * onto the CHAT tab's existing {@code onFilesDrop} handling is the
 * zero-new-API path for a headless/no-native-dialog environment, not a
 * second copy of this class's job.
 */
public final class SkinPicker {

    private SkinPicker() {}

    /** Monotonic suffix for preview texture ids — every {@link #preview} call
     * gets a fresh one so re-picking a file doesn't collide with (or need to
     * explicitly race-free release) whatever preview is already on screen. */
    private static final AtomicInteger PREVIEW_GENERATION = new AtomicInteger();

    /** A registered 24x24 preview texture. The caller blits {@link #texId}
     * like any other GUI texture and, per the codebase's existing
     * {@code ChatImages.Thumb} convention, is responsible for releasing it
     * (via {@code Minecraft.getInstance().getTextureManager().release(texId)})
     * once it's no longer shown — e.g. the dialog closes or the owner picks a
     * different file. */
    public record Preview(Identifier texId, int width, int height) {}

    /**
     * Open a native "choose a PNG" file dialog on a background thread. If the
     * owner picks a file, {@code onChosen} runs on the client thread with its
     * path; if they cancel, or the dialog can't open at all (missing native
     * library, headless environment, ...), {@code onChosen} is simply never
     * called — there is no error path to report here, only presence/absence.
     */
    public static void choose(Consumer<Path> onChosen) {
        new Thread(() -> {
            String chosen = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png")).flip();
                chosen = TinyFileDialogs.tinyfd_openFileDialog(
                        "Numen skin (64x64 png)", null, filters, "PNG", false);
            } catch (Throwable t) {
                Constants.LOG.warn("[numen-skin] file dialog unavailable: {}", t.toString());
            }
            if (chosen != null) {
                Path path = Path.of(chosen);
                Minecraft.getInstance().execute(() -> onChosen.accept(path));
            }
        }, "numen-skin-picker").start();
    }

    /**
     * Decode {@code source} and, if it's a readable 64x64 PNG, register a
     * 24x24 crop of its face (the standard {@code (8,8)}-{@code (16,16)} head
     * layer) as a GUI texture. Touches no directory this class owns — safe to
     * call speculatively for any file the owner drops onto the picker, even
     * one that never gets committed. Returns {@code null} (after logging the
     * reason) if {@code source} isn't a readable/right-sized PNG.
     */
    public static Preview preview(Path source) {
        try (InputStream in = Files.newInputStream(source)) {
            NativeImage full = NativeImage.read(in);
            try {
                if (full.getWidth() != 64 || full.getHeight() != 64) {
                    Constants.LOG.warn("[numen-skin] {} is {}x{}, need 64x64",
                            source, full.getWidth(), full.getHeight());
                    return null;
                }
                NativeImage face = new NativeImage(NativeImage.Format.RGBA, 24, 24, false);
                full.resizeSubRectTo(8, 8, 8, 8, face);   // base head layer, no hat overlay
                Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID,
                        "skins/preview_" + PREVIEW_GENERATION.incrementAndGet());
                // DynamicTexture takes ownership of `face`; caller releases `id` later
                // (same handoff ChatImages.loadThumbnail uses for its thumbnails).
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, face));
                return new Preview(id, 24, 24);
            } finally {
                full.close();
            }
        } catch (IOException e) {
            Constants.LOG.warn("[numen-skin] can't read {}: {}", source, e.toString());
            return null;
        }
    }

    /**
     * True if committing {@code companionName}/{@code slim} right now would
     * overwrite a file already on the skins directory. The creation dialog
     * calls this right before {@link #commit} (i.e. once the name is final,
     * on Create) and asks the owner first if it's {@code true} — {@link
     * #commit} itself always overwrites unconditionally, because "ask
     * first" is a confirmation-dialog concern that belongs to the UI layer,
     * not here.
     */
    public static boolean wouldOverwrite(String companionName, boolean slim) {
        return Files.isRegularFile(skinFile(companionName, slim));
    }

    /**
     * Copy {@code source} into {@code config/numen_api/skins} for {@code
     * companionName}/{@code slim} (overwriting unconditionally — see {@link
     * #wouldOverwrite}) and invalidate {@link NumenSkins}' cache for that
     * name so the new skin is visible on the very next lookup. Copies the
     * original bytes rather than re-encoding, same rationale as {@code
     * ChatImages.persist}.
     *
     * <p>Re-validates the 64x64-PNG requirement itself rather than trusting
     * that the caller already ran {@link #preview} — the two calls aren't
     * forced to happen back-to-back (the name can still change in between,
     * and a future caller may not go through {@code preview} at all), and
     * re-decoding a skin-sized PNG is cheap enough that trusting call order
     * isn't worth the fragility. Returns {@code false} (after logging the
     * reason) if {@code source} fails that check or any IO step does.
     */
    public static boolean commit(Path source, String companionName, boolean slim) {
        try (InputStream in = Files.newInputStream(source)) {
            NativeImage image = NativeImage.read(in);
            try {
                if (image.getWidth() != 64 || image.getHeight() != 64) {
                    Constants.LOG.warn("[numen-skin] refusing {} for {}: {}x{}, need 64x64",
                            source, companionName, image.getWidth(), image.getHeight());
                    return false;
                }
            } finally {
                image.close();
            }
            Path dest = skinFile(companionName, slim);
            Files.createDirectories(dest.getParent());
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            NumenSkins.invalidate(companionName);
            return true;
        } catch (IOException e) {
            Constants.LOG.warn("[numen-skin] can't save skin for {}: {}", companionName, e.toString());
            return false;
        }
    }

    private static Path skinFile(String companionName, boolean slim) {
        String key = companionName.toLowerCase(Locale.ROOT);
        return NumenSkins.skinsDir().resolve(key + (slim ? ".slim.png" : ".png"));
    }
}
