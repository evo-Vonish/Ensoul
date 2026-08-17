package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientControl;
import com.dwinovo.numen.client.agent.ClientDeaths;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.entity.ControlRegistry.ControlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Left-edge activity HUD — ACTIVITY-gated, not permanent. A companion draws NOTHING unless it has
 * a live, expiring reason to be shown; there is no resting-state chrome (no idle sliver, no "still
 * here" hint). This is a deliberate rebuild for the multi-companion competition case: with five or
 * more companions, permanence used to force a collapse to one shared slot showing whoever spoke
 * last, hiding everyone else. Activity-gating makes that collapse unnecessary — an inactive body
 * simply isn't in the candidate set, so the stack self-limits.
 *
 * <h2>The four reasons, each with its own expiry</h2>
 * <ul>
 *   <li><b>{@link Reason#SPOKE}</b> — a new assistant utterance within {@link #LINE_LIFE_MS} (5 s)
 *       → avatar + speech bubble.</li>
 *   <li><b>{@link Reason#WORKING}</b> — {@code loop.isBusy()} within {@link #AVATAR_LIFE_MS} (13 s)
 *       of last activity → avatar only. Suppressed in {@link HudMode#SPEECH_ONLY}.</li>
 *   <li><b>{@link Reason#CONTROL_FLASH}</b> — the companion's control state (see {@link
 *       com.dwinovo.numen.entity.ControlRegistry ControlRegistry}) changed within {@link #CONTROL_FLASH_MS} (5 s) → avatar
 *       + a one-line badge ("X took control" / "Released"). Read from {@link ClientControl}'s
 *       pushed {@code lastStateChangeMillis} — never polled, never derived locally, per the
 *       invariant that the client asserts nothing about who controls a body.</li>
 *   <li><b>{@link Reason#DEAD}</b> — {@link ClientDeaths#isDead} → dimmed avatar + respawn
 *       countdown, mirroring what the panel rail already draws (NumenScreen's
 *       {@code renderRail}) so the two portrait surfaces agree.</li>
 * </ul>
 * A companion may satisfy several reasons at once; only one is drawn, in priority DEAD &gt;
 * CONTROL_FLASH &gt; SPOKE &gt; WORKING — death dominates every other signal, and a control
 * takeover is a bigger event than routine chatter. When the last satisfied reason lapses, the
 * frame retracts with the existing 220 ms slide ({@link Reason#RETRACTING}) and then draws
 * nothing at all.
 *
 * <p><b>Known quirk, not fixed here (outside this file's ownership):</b> {@link ClientControl}
 * treats the very first {@code ControlStatePayload} it ever sees for a companion as a "change"
 * (there is no previous snapshot to compare against), so every companion can show a brief
 * control-flash badge right after joining a world even though nothing actually changed. Cosmetic
 * only — it's a control class this wave does not own.
 *
 * <h2>Suppression</h2>
 * The whole HUD is suppressed while ANY screen is open ({@code mc.screen != null} — not just the
 * roster screen; the vanilla inventory, chat and pause menu all clear it too), while the roster is
 * empty, or while {@link #hudMode()} is {@link HudMode#OFF}.
 *
 * <h2>Multi-body stacking</h2>
 * At most {@link #MAX_STACK} (4) currently-active frames are drawn, most-recently-active first;
 * any further active companions collapse into a single "+N" chip below the stack. Because inactive
 * bodies render nothing, this only ever engages when 5+ companions are ACTIVE at once — not merely
 * summoned — which is exactly the filmed multi-brain race this HUD needs to survive.
 */
public final class NumenToasts {

    private static final int W = 172;            // bubble width
    private static final int AVATAR = 24;        // avatar head size
    private static final int MARGIN = 0;         // avatar flush against the left window edge (no gap)
    private static final int STACK_GAP = 8;
    private static final int BUBBLE_GAP = 5;
    private static final int TIP_W = 6, TIP_H = 11;
    private static final int MAX_STACK = 4;      // currently-active frames shown before collapsing to "+N"
    private static final int LINE_H = 11;
    private static final int PADV = 5;
    private static final int INNER_W = W - 14;
    private static final int MAX_LINES = 4;
    private static final int MAX_REPLY_LINES = 3;
    private static final long LINE_LIFE_MS = 5000;                  // toast line lifetime ("spoke")
    private static final long AVATAR_LIFE_MS = LINE_LIFE_MS + 8000; // "working" reason lifetime (13 s)
    private static final long CONTROL_FLASH_MS = 5000;              // "control flash" reason lifetime
    private static final long SLIDE_MS = 220;

    /** Owner-facing HUD verbosity. {@code SPEECH_ONLY} (default) shows speech, control flashes and
     *  death but not routine busy-work — the toggle a filmed run needs to keep the rail quiet while
     *  several bodies think in the background. Persistence + a Settings-tab control are a follow-up
     *  (they require {@code INumenConfig} and the two per-loader impls, outside this file's set);
     *  for now this mirrors {@link UiTheme}'s own "static in-memory default, persistence lands
     *  later" pattern. */
    public enum HudMode { OFF, SPEECH_ONLY, FULL }

    private static HudMode hudMode = HudMode.SPEECH_ONLY;

    public static HudMode hudMode() { return hudMode; }

    public static void setHudMode(HudMode mode) { hudMode = mode == null ? HudMode.SPEECH_ONLY : mode; }

    /** Why a companion currently has a frame on screen. See the class doc for the priority order
     *  and each reason's expiry. {@link #RETRACTING} is not a real activity reason — it is the
     *  220 ms slide-out tail after the last real reason has lapsed. */
    private enum Reason { DEAD, CONTROL_FLASH, SPOKE, WORKING, RETRACTING }

    private record Active(UUID uuid, Reason reason, long recencyMs) {}

    private static net.minecraft.resources.Identifier spr(String n) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(com.dwinovo.numen.Constants.MOD_ID, n);
    }
    private static final net.minecraft.resources.Identifier BUBBLE_SPRITE = spr("bubble");
    private static final net.minecraft.resources.Identifier TIP_SPRITE = spr("bubble_tip");
    private static final net.minecraft.resources.Identifier AVATAR_FRAME = spr("avatar_frame");

    private static final Map<UUID, Integer> SEEN = new HashMap<>();
    private static final Map<UUID, Status> STATUS = new HashMap<>();
    /** First-observed-dead timestamp per companion, tracked locally since {@link ClientDeaths} only
     *  exposes the current dead/alive fact plus a respawn epoch, not when death began — needed here
     *  purely to give the DEAD reason a stable recency for stack sorting. Cleared on respawn. */
    private static final Map<UUID, Long> DEAD_SINCE = new HashMap<>();

    private NumenToasts() {}

    private record Line(String text, int color, long bornMs) {}

    private static final class Status {
        long lastActivityMs = Long.MIN_VALUE;    // any assistant turn / busy tick — drives WORKING's expiry
        long activeSinceMs;                       // most recent inactive→active transition — drives slide-IN
        long inactiveSinceMs = Long.MIN_VALUE;    // most recent active→inactive transition — drives slide-OUT
        boolean wasActive;                        // tick-scoped bookkeeping for the two transitions above
        long bubbleBornMs;                        // last time a bubble (re)appeared — drives the bubble slide
        final Deque<Line> lines = new ArrayDeque<>();
    }

    /** Bundles the four raw activity signals for one companion at one instant, plus the two derived
     *  visibility questions the HUD actually needs answered. Computed fresh every call — cheap
     *  (a couple of map lookups), and it keeps {@link #tick()}'s transition bookkeeping and {@link
     *  #render}'s frame selection reading the exact same definition of "active". */
    private record Reasons(boolean dead, ClientControl.Snapshot ctrl, boolean spoke, boolean working, long now) {
        boolean controlFlash() { return ctrl != null && now - ctrl.lastStateChangeMillis() < CONTROL_FLASH_MS; }
        /** Reasons visible even in {@link HudMode#SPEECH_ONLY}. */
        boolean speechVisible() { return dead || controlFlash() || spoke; }
        /** Every reason, including routine busy-work — {@link HudMode#FULL} only. */
        boolean anyVisible() { return speechVisible() || working; }
    }

    private static Reasons reasonsFor(UUID uuid, long now) {
        Status s = STATUS.get(uuid);
        boolean dead = ClientDeaths.isDead(uuid);
        ClientControl.Snapshot ctrl = ClientControl.instance().snapshotOf(uuid);
        boolean spoke = s != null && !s.lines.isEmpty();
        boolean working = s != null && now - s.lastActivityMs < AVATAR_LIFE_MS;
        return new Reasons(dead, ctrl, spoke, working, now);
    }

    private static boolean visibleUnderMode(Reasons r) {
        return hudMode == HudMode.FULL ? r.anyVisible() : r.speechVisible();
    }

    /** Expire toast lines, poll loops for new assistant turns / busy state, track death, and run the
     *  idle↔active transition bookkeeping that drives the slide-in / slide-out animations. Runs on
     *  the game tick (not every render frame) so the transition edges are detected exactly once. */
    public static void tick() {
        long now = System.currentTimeMillis();
        STATUS.values().forEach(s -> s.lines.removeIf(l -> now - l.bornMs() > LINE_LIFE_MS));

        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            UUID uuid = entry.uuid();
            AgentLoopRegistry.get(uuid).ifPresent(loop -> {
                List<ConvoState.Msg> snap = loop.convo().snapshot();
                int prev = SEEN.getOrDefault(uuid, -1);
                if (prev >= 0) {
                    for (int i = prev; i < snap.size(); i++) {
                        if (snap.get(i) instanceof ConvoState.Msg.Assistant a) {
                            markActivity(uuid, now);
                            if (a.turn().content() != null && !a.turn().content().isBlank()) {
                                addToastLines(uuid, a.turn(), now);
                            }
                        }
                    }
                }
                SEEN.put(uuid, snap.size());
                if (loop.isBusy()) markActivity(uuid, now);   // keep WORKING alive during long task runs
            });

            if (ClientDeaths.isDead(uuid)) DEAD_SINCE.putIfAbsent(uuid, now);
            else DEAD_SINCE.remove(uuid);

            // Idle↔active transition, unified across all four reasons: this is what drives the
            // avatar's slide-in (activeSinceMs) and the 220 ms slide-out tail (inactiveSinceMs) for
            // EVERY reason, not just "spoke" as before — a control flash or a death now animates in
            // exactly like a speech bubble used to.
            boolean activeNow = visibleUnderMode(reasonsFor(uuid, now));
            Status s = STATUS.get(uuid);
            if (activeNow) {
                if (s == null) { s = new Status(); STATUS.put(uuid, s); }
                if (!s.wasActive) s.activeSinceMs = now;
                s.wasActive = true;
            } else if (s != null && s.wasActive) {
                s.wasActive = false;
                s.inactiveSinceMs = now;
            }
        }
    }

    private static void markActivity(UUID uuid, long now) {
        STATUS.computeIfAbsent(uuid, k -> new Status()).lastActivityMs = now;
    }

    private static void addToastLines(UUID uuid, AssistantTurn turn, long now) {
        UiTheme th = UiTheme.current();
        Status s = STATUS.computeIfAbsent(uuid, k -> new Status());
        boolean wasEmpty = s.lines.isEmpty();
        for (String wrapped : wrapToWidth(turn.content(), INNER_W, MAX_REPLY_LINES)) {
            s.lines.addLast(new Line(wrapped, th.reply(), now));
        }
        while (s.lines.size() > MAX_LINES) s.lines.removeFirst();
        if (wasEmpty) s.bubbleBornMs = now;                 // fresh bubble → restart the slide
    }

    public static void render(GuiGraphicsExtractor g) {
        if (hudMode == HudMode.OFF) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;                       // ANY open screen suppresses the HUD
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        if (entries.isEmpty()) return;

        Font font = mc.font;
        UiTheme th = UiTheme.current();
        long now = System.currentTimeMillis();

        List<Active> visible = new ArrayList<>();
        for (NumenRoster.Entry entry : entries) {
            Active a = activeReasonOf(entry.uuid(), now);
            if (a != null) visible.add(a);
        }
        if (visible.isEmpty()) return;                        // nobody has a live reason — draw nothing
        visible.sort((x, y) -> Long.compare(y.recencyMs(), x.recencyMs()));   // most-recently-active first

        int shown = Math.min(visible.size(), MAX_STACK);
        int overflow = visible.size() - shown;
        int rows = shown + (overflow > 0 ? 1 : 0);
        int startY = (g.guiHeight() - (rows * AVATAR + (rows - 1) * STACK_GAP)) / 2;

        for (int i = 0; i < shown; i++) {
            renderOne(g, font, th, visible.get(i), startY + i * (AVATAR + STACK_GAP), now);
        }
        if (overflow > 0) {
            drawOverflowChip(g, font, th, overflow, startY + shown * (AVATAR + STACK_GAP));
        }
    }

    /** The reason (if any) {@code uuid} currently earns a frame for, honouring {@link #hudMode}, or
     *  {@code null} if it should draw nothing at all. Falls back to {@link Reason#RETRACTING} for
     *  the brief slide-out tail after every real reason has just lapsed. */
    private static Active activeReasonOf(UUID uuid, long now) {
        Reasons r = reasonsFor(uuid, now);
        if (visibleUnderMode(r)) {
            Reason reason = r.dead() ? Reason.DEAD
                    : r.controlFlash() ? Reason.CONTROL_FLASH
                    : r.spoke() ? Reason.SPOKE
                    : Reason.WORKING;
            long recency = switch (reason) {
                case DEAD -> DEAD_SINCE.getOrDefault(uuid, now);
                case CONTROL_FLASH -> r.ctrl().lastStateChangeMillis();
                default -> STATUS.get(uuid).lastActivityMs;   // SPOKE / WORKING both require Status != null
            };
            return new Active(uuid, reason, recency);
        }
        Status s = STATUS.get(uuid);
        if (s != null && s.inactiveSinceMs != Long.MIN_VALUE && now - s.inactiveSinceMs < SLIDE_MS) {
            return new Active(uuid, Reason.RETRACTING, s.inactiveSinceMs);
        }
        return null;
    }

    /** Draw one companion's frame for the reason it currently earns. */
    private static void renderOne(GuiGraphicsExtractor g, Font font, UiTheme th, Active a, int ay, long now) {
        UUID uuid = a.uuid();
        switch (a.reason()) {
            case DEAD -> {
                int ax = slideInX(uuid, now);
                drawAvatar(g, uuid, ax, ay, th);
                drawDeathOverlay(g, font, ax, ay, uuid, th);
            }
            case CONTROL_FLASH -> {
                int ax = slideInX(uuid, now);
                drawAvatar(g, uuid, ax, ay, th);
                drawControlBadge(g, font, ax, ay, uuid, th, now);
            }
            case SPOKE -> {
                int ax = slideInX(uuid, now);
                Status s = STATUS.get(uuid);
                if (s != null && !s.lines.isEmpty()) drawBubble(g, font, ax, ay, s, now);
                drawAvatar(g, uuid, ax, ay, th);
            }
            case WORKING -> {
                int ax = slideInX(uuid, now);
                drawAvatar(g, uuid, ax, ay, th);
            }
            case RETRACTING -> {
                Status s = STATUS.get(uuid);
                long age = now - s.inactiveSinceMs;
                int ax = MARGIN - ((MARGIN + AVATAR) - slideOut(age, MARGIN + AVATAR));
                drawAvatar(g, uuid, ax, ay, th);
            }
        }
    }

    /** Slide-in x offset for a frame that currently has a live reason: {@link #MARGIN} once fully
     *  in, sliding from off-screen for {@link #SLIDE_MS} after {@code Status.activeSinceMs}. */
    private static int slideInX(UUID uuid, long now) {
        Status s = STATUS.get(uuid);
        long age = s == null ? SLIDE_MS : now - s.activeSinceMs;
        return MARGIN - slideOut(age, MARGIN + AVATAR);
    }

    private static void drawAvatar(GuiGraphicsExtractor g, UUID uuid, int x, int y, UiTheme th) {
        // textured socket behind the head (same sprite as the panel rail), face on top covering the centre
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                AVATAR_FRAME, x - 2, y - 2, AVATAR + 4, AVATAR + 4);
        PlayerFaceExtractor.extractRenderState(g, skinFor(uuid), x, y, AVATAR);
    }

    /** Dimming veil + respawn countdown over a dead companion's avatar — the same treatment
     *  NumenScreen's rail already gives a dead entry, so the two portrait surfaces agree. */
    private static void drawDeathOverlay(GuiGraphicsExtractor g, Font font, int ax, int ay, UUID uuid, UiTheme th) {
        g.fill(ax, ay, ax + AVATAR, ay + AVATAR, 0xB0101010);
        long rem = ClientDeaths.remainingMs(uuid);
        if (rem >= 0) {
            String c = String.valueOf((int) Math.ceil(rem / 1000.0));
            Nb.text(g, font, c, ax + (AVATAR - font.width(c)) / 2, ay + (AVATAR - 8) / 2, th.cta());
        }
    }

    /** One-line "took control" / "released" badge, reusing the speech bubble's sprite so it reads
     *  as part of the same HUD language rather than a new UI element. Slides out from the avatar in
     *  lockstep with {@link ClientControl.Snapshot#lastStateChangeMillis()} — pushed, never polled. */
    private static void drawControlBadge(GuiGraphicsExtractor g, Font font, int ax, int ay, UUID uuid, UiTheme th, long now) {
        ClientControl.Snapshot ctrl = ClientControl.instance().snapshotOf(uuid);
        if (ctrl == null) return;
        String label = ctrl.controllerLabel();
        String raw = ctrl.state() == ControlState.EXTERNAL
                ? (label == null || label.isBlank() ? "Took control" : label + " took control")
                : "Released";
        String text = wrapToWidth(raw, INNER_W, 1).get(0);
        int color = ctrl.state() == ControlState.EXTERNAL ? th.cta() : th.ok();

        int h = LINE_H + PADV * 2;
        int targetX = ax + AVATAR + BUBBLE_GAP;
        int bx = targetX - slideOut(now - ctrl.lastStateChangeMillis(), AVATAR + BUBBLE_GAP);
        int by = ay + AVATAR / 2 - h / 2;
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, TIP_SPRITE, bx - TIP_W + 1, ay + AVATAR / 2 - TIP_H / 2, TIP_W, TIP_H);
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, BUBBLE_SPRITE, bx, by, W, h);
        Nb.text(g, font, text, bx + 7, by + PADV, color);
    }

    /** Collapsed indicator for companions active beyond {@link #MAX_STACK} — static, no slide,
     *  since it isn't tied to any single companion's animation state. */
    private static void drawOverflowChip(GuiGraphicsExtractor g, Font font, UiTheme th, int overflow, int ay) {
        String text = "+" + overflow;
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                AVATAR_FRAME, MARGIN - 2, ay - 2, AVATAR + 4, AVATAR + 4);
        Nb.text(g, font, text, MARGIN + (AVATAR - font.width(text)) / 2, ay + (AVATAR - 8) / 2, th.text());
    }

    private static void drawBubble(GuiGraphicsExtractor g, Font font, int ax, int ay, Status s, long now) {
        int h = s.lines.size() * LINE_H + PADV * 2;
        int targetX = ax + AVATAR + BUBBLE_GAP;
        int bx = targetX - slideOut(now - s.bubbleBornMs, AVATAR + BUBBLE_GAP);
        int by = ay + AVATAR / 2 - h / 2;
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, TIP_SPRITE, bx - TIP_W + 1, ay + AVATAR / 2 - TIP_H / 2, TIP_W, TIP_H);
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, BUBBLE_SPRITE, bx, by, W, h);
        int ly = by + PADV;
        for (Line line : s.lines) {
            Nb.text(g, font, line.text(), bx + 7, ly, line.color());
            ly += LINE_H;
        }
    }

    private static PlayerSkin skinFor(UUID uuid) {
        AbstractClientPlayer e = ClientNumenLookup.resolve(uuid);
        return e != null ? e.getSkin() : DefaultPlayerSkin.get(uuid);
    }

    /** Eased slide: {@code dist} px → 0 over {@link #SLIDE_MS}. */
    private static int slideOut(long age, int dist) {
        if (age >= SLIDE_MS) return 0;
        float p = 1f - (float) age / SLIDE_MS;
        return (int) (dist * p * p);
    }

    /** Greedily wrap to a pixel width (CJK-aware), capped at {@code maxLines}; ellipsis if truncated. */
    private static List<String> wrapToWidth(String text, int maxW, int maxLines) {
        Font font = Minecraft.getInstance().font;
        String s = text.replaceAll("\\s+", " ").trim();
        List<String> out = new ArrayList<>();
        while (!s.isEmpty() && out.size() < maxLines) {
            String head = font.plainSubstrByWidth(s, maxW);
            if (head.isEmpty()) head = s.substring(0, 1);
            out.add(head.trim());
            s = s.substring(head.length());
        }
        if (!s.isEmpty() && !out.isEmpty()) {
            String last = out.get(out.size() - 1);
            while (!last.isEmpty() && font.width(last + "…") > maxW) last = last.substring(0, last.length() - 1);
            out.set(out.size() - 1, last + "…");
        }
        return out;
    }

    public static void clear() {
        SEEN.clear();
        STATUS.clear();
        DEAD_SINCE.clear();
    }
}
