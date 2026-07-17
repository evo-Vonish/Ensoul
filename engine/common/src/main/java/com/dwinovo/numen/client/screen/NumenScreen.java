package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.model.ModelRegistry;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientDeaths;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.ContextExporter;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.agent.UsageTracker;
import com.dwinovo.numen.client.data.ClientNumenInventory;
import com.dwinovo.numen.network.payload.RequestInventoryPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The owner-facing companion panel: one tabbed screen per Numen (Chat / Items /
 * Settings), opened from the roster or hotkey. Resolves the companion lazily by
 * UUID so it works at any distance.
 *
 * <h2>Chat tab</h2>
 * A scrollable transcript on the left + a live PLAN panel on the right. Tool calls
 * show a spinner while running and a green check once their result lands — the raw
 * tool-result JSON is NOT shown (it only flips the call to done), keeping the chat
 * readable. The plan is the companion's latest {@code todowrite}.
 */
public final class NumenScreen extends Screen {

    private enum Tab { CHAT, ITEMS, SETTINGS }

    // ---- layout ----
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 232;
    // Left companion rail (folded-in roster): one avatar per Numen, click to switch, + to summon.
    private static final int RAIL_W = 46;        // left rail column width (baked into the workspace sprite)
    private static final int RAIL_AV = 26;       // avatar tile size
    private static final int RAIL_SLOT = 32;     // vertical pitch per avatar
    private static final int RAIL_TOP = 12;      // top margin before the first avatar (clears the active crown)
    private static final int RAIL_BOT_GAP = 6;   // gap kept above the pinned "+" tile
    private static final int HEADER_H = 22;
    private static final int INPUT_H = 18;
    /** Text fields are inset inside their parchment frame: the EditBox is shrunk by this much
     *  (so vanilla's top-left unbordered text lands padded + centred) and the FIELD_SPRITE is
     *  inflated back out to the full frame. */
    private static final int FIELD_INSET_X = 5;
    private static final int FIELD_INSET_Y = 4;
    private static final int PAD = 8;
    private static final int LINE_H = 10;
    private static final int PLAN_W = 122;
    /** Context-usage bar (CHAT tab): thin fill strip thickness, and the full reserved band above
     *  it (one text line + a 1px gap + the strip) — mirrors {@code cardsBandH()}'s reserve-when-shown idiom. */
    private static final int CTX_BAR_H = 3;
    private static final int CTX_BAND_H = 12;
    private static final int MAX_PROMPT = 1024;

    // ---- palette (BlockFrame "Cottage" theme — single theme for now, see UiTheme) ----
    private static final UiTheme TH = UiTheme.WARM;
    private static final int BORDER = TH.border();
    private static final int ACCENT = TH.cta();
    private static final int TXT = TH.text();
    private static final int TXT_MUTED = TH.textDim();
    private static final int TXT_FAINT = 0xFF8C7C62;
    private static final int ON_BAND = TH.onBand();
    private static final int CTA = TH.cta();
    private static final int ON_CTA = TH.onCta();
    private static final int FIELD = TH.field();
    private static final int YOU = TH.reply();          // user messages — teal
    private static final int AI = 0xFF35562F;            // assistant replies — deep moss green (the "point")
    private static final int TOOL = TH.textDim();        // folded tool-call rows — muted, secondary
    private static final int OK = TH.ok();
    private static final int RUN = TH.run();
    private static final int FAIL = TH.fail();
    private static net.minecraft.resources.Identifier railSpr(String n) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(com.dwinovo.numen.Constants.MOD_ID, n);
    }
    /** rail + panel composited into ONE sprite (continuous header, no gap; panel's left border = divider). */
    private static final net.minecraft.resources.Identifier WORKSPACE_SPRITE = railSpr("workspace");
    private static final net.minecraft.resources.Identifier AVATAR_FRAME = railSpr("avatar_frame");
    private static final net.minecraft.resources.Identifier AVATAR_FRAME_ACTIVE = railSpr("avatar_frame_active");
    private static final net.minecraft.resources.Identifier SUMMON_SPRITE = railSpr("summon");
    private static final net.minecraft.resources.Identifier SUMMON_ACTIVE = railSpr("summon_active");
    /** API-key reveal toggle icons: open eye = "click to show", slashed eye = "click to hide". */
    private static final net.minecraft.resources.Identifier EYE = railSpr("eye");
    private static final net.minecraft.resources.Identifier EYE_OFF = railSpr("eye_off");
    private static final net.minecraft.resources.Identifier CHEVRON_UP = railSpr("chevron_up");
    private static final net.minecraft.resources.Identifier CHEVRON_DOWN = railSpr("chevron_down");

    private static final String[] SPIN = {"|", "/", "-", "\\"};
    /** Armor column on the Items tab (top → bottom); offhand is drawn separately below it. */
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private UUID uuid;       // active companion (mutable — the rail switches it in place)
    private String name;
    private Tab tab = Tab.CHAT;

    private EditBox input;
    private SimpleButton sendButton;
    private SimpleButton stopButton;
    private SimpleButton compactButton;
    private String savedInput = "";

    // ---- pending image attachments (chat tab) ----
    /** Thumbnail longest-side and card box, in px. */
    private static final int THUMB_MAX = 40;
    private static final int CARD_H = 40;
    private static final int CARD_W = 44;
    private static final int CARD_GAP = 4;
    /** Cap on images composed onto one message (keeps the card row within the panel). */
    private static final int MAX_ATTACH = 6;
    /** Images pasted/dropped onto the current draft, shown as removable cards above the input. */
    private final List<PendingImage> pendingImages = new ArrayList<>();
    /** Transient hint above the input (attachment limit, paste-unsupported, …). */
    private long attachHintUntil;
    private String attachHintText = "";

    // "+" summon flow: a transient name field shown over the panel
    private boolean summoning;
    private EditBox summonInput;
    private UUID dismissPending;   // non-null = showing the "delete companion?" confirm bar for this uuid

    // settings tab widgets
    private ProviderDropdown providerDropdown;
    private Dropdown modelDropdown;          // null when in custom-model mode
    private boolean customModel;             // model is a free-text custom id (not a registry preset)
    private static final String CUSTOM_MODEL = "__custom__";
    // unsaved working state — settings widgets are (re)built from these, NOT from config, so a rebuild
    // (provider change / custom toggle) doesn't revert what you just picked or typed.
    private String wProvider = "", wApiKey = "", wModel = "", wBaseUrl = "", wProxy = "", wSiteName = "";
    /** Working copy of the reasoning-effort knob (auto | low | medium | high), cycled by its button. */
    private String wReasoning = "auto";
    /** Settings body swapped to the Usage view (session token stats + provider balance query). */
    private boolean usageView;
    /** Balance query status/result line — written from the HTTP thread (immutable String), read by render. */
    private volatile String balanceLine;
    /** Context-export status line (written path or error) — main-thread only, shown in the Usage view. */
    private String exportLine;
    private boolean addingSite;              // "+ 添加站点" mode: name + base URL + model → writes a site
    private EditBox proxyInput;
    private EditBox siteNameInput;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox baseUrlInput;
    private long savedFlashUntil;
    private long warnUntil;        // transient "no API key" hint on the chat tab

    // Widgets are registered for EVENTS only (addWidget) and rendered MANUALLY at the end of the
    // frame, so they sit ON TOP of the panel background instead of being painted over by it (the
    // "dim fields" bug — the panel fill ran after the auto-rendered widgets).
    private final List<AbstractWidget> overlay = new ArrayList<>();
    /** API key is masked by default; the eye button toggles it. */
    private boolean showKey;

    // geometry resolved in init()
    private int left, top, railX;
    private final int[] tabX = new int[3];   // left x of each tab label, for click hit-testing
    private final int[] tabW = new int[3];

    // chat transcript scroll
    private int scroll;            // px scrolled down from the top of the content
    private boolean pinBottom = true;
    private int lastMaxScroll;
    private int railScroll;        // index of the first visible rail avatar (wheel-scroll when many companions)
    /** Completed tool-call groups the user clicked open (keyed by the group's first call id). */
    private final Set<String> expandedGroups = new HashSet<>();

    /** Re-request the backpack every ~1 s while the Items tab is open. */
    private static final int INV_REFRESH_TICKS = 20;
    private int tickCounter;

    private NumenScreen(UUID uuid, String name) {
        super(Component.literal(name == null ? "Fenn" : "Fenn - " + name));
        this.uuid = uuid;
        this.name = name;
    }

    /** Open the panel focused on a specific companion. */
    public static void open(UUID uuid, String name) {
        Minecraft.getInstance().setScreen(new NumenScreen(uuid, name));
    }

    /** Hotkey entry: open the workspace on the first companion (or an empty panel to summon from). */
    public static void openWorkspace() {
        var entries = NumenRoster.instance().entries();
        if (entries.isEmpty()) { Minecraft.getInstance().setScreen(new NumenScreen(null, null)); return; }
        NumenRoster.Entry first = entries.get(0);
        Minecraft.getInstance().setScreen(new NumenScreen(first.uuid(), first.name()));
    }

    /** Switch the panel to another companion in place (left-rail click) — no reopen. */
    private void switchTo(UUID u, String n) {
        if (java.util.Objects.equals(u, uuid)) return;
        input = null; savedInput = "";          // don't carry typed text across companions
        clearAttachments();                      // …nor composed image attachments
        uuid = u; name = n;
        scroll = 0; pinBottom = true; expandedGroups.clear();
        rebuild();
        if (tab == Tab.ITEMS && u != null) requestInventory();
    }

    private EntityAgentLoop loop() {
        return AgentLoopRegistry.getOrCreate(uuid);
    }

    @Override
    protected void init() {
        int composite = RAIL_W + PANEL_W;        // rail flush against the panel — one merged sprite
        this.railX = (this.width - composite) / 2;
        this.left = railX + RAIL_W;
        this.top = (this.height - PANEL_H) / 2;
        layoutTabs();
        rebuild();
    }

    private void layoutTabs() {
        String[] labels = {"Chat", "Items", "Settings"};
        int x = left + PANEL_W - PAD;
        for (int i = labels.length - 1; i >= 0; i--) {
            int w = font.width(labels[i]) + 10;
            x -= w;
            tabX[i] = x;
            tabW[i] = w;
            x -= 4;
        }
    }

    /** Rebuild the widgets for the active tab. */
    private void rebuild() {
        if (input != null) savedInput = input.getValue();
        clearWidgets();
        overlay.clear();
        input = null;
        sendButton = stopButton = compactButton = null;
        apiKeyInput = modelInput = baseUrlInput = proxyInput = siteNameInput = null;
        modelDropdown = null;
        summonInput = null;
        if (summoning) { buildSummonField(); return; }
        if (dismissPending != null) { buildDismissConfirm(); return; }
        switch (tab) {
            case CHAT -> { if (uuid != null) buildChatWidgets(); }
            case SETTINGS -> buildSettingsWidgets();
            case ITEMS -> { /* no widgets */ }
        }
    }

    private void buildSummonField() {
        int y = top + HEADER_H + 24;
        summonInput = new FlatEditBox(font, left + PAD + FIELD_INSET_X, y + FIELD_INSET_Y,
                PANEL_W - PAD * 2 - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
        summonInput.setMaxLength(com.dwinovo.numen.network.payload.SummonRequestPayload.MAX_NAME);
        summonInput.setBordered(false);
        summonInput.setTextColor(TXT);
        summonInput.setHint(Component.literal("New companion name…"));
        add(summonInput);
        setInitialFocus(summonInput);
    }

    /** Two buttons for the "delete companion?" confirm bar — Cancel and the destructive Delete. */
    private void buildDismissConfirm() {
        UUID target = dismissPending;
        int bw = 64, gap = 8, totalW = bw * 2 + gap;
        int bx = left + (PANEL_W - totalW) / 2;
        int by = top + HEADER_H + 52;
        add(new SimpleButton(bx, by, bw, 18, Component.literal("取消"),
                b -> { dismissPending = null; rebuild(); }));
        add(new SimpleButton(bx + bw + gap, by, bw, 18, Component.literal("删除"), b -> {
            Services.NETWORK.sendToServer(
                    new com.dwinovo.numen.network.payload.DismissRequestPayload(target));
            dismissPending = null;
            if (target.equals(uuid)) {                       // active one is leaving — jump to another / empty
                NumenRoster.Entry next = firstOther(target);
                if (next != null) { switchTo(next.uuid(), next.name()); return; }
                uuid = null; name = null;
            }
            rebuild();
        }));
    }

    /** First roster companion that isn't {@code exclude}, or null if none. */
    private NumenRoster.Entry firstOther(UUID exclude) {
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
            if (!e.uuid().equals(exclude)) return e;
        }
        return null;
    }

    private String nameFor(UUID u) {
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
            if (e.uuid().equals(u)) return e.name();
        }
        return "?";
    }

    /** Register a widget for EVENTS only; it's rendered manually (on top of the panel) in {@link
     *  #render}. */
    private <T extends AbstractWidget> T add(T w) {
        addWidget(w);
        overlay.add(w);
        return w;
    }

    // Shadowless text — BlockFrame is flat, and a drop shadow on DARK text over a LIGHT ground makes
    // the glyph merge with its own shadow ("smudged"). This build's shadowless path ignores the colour
    // PARAM, so we bake the colour into the text's Style instead.
    private void txt(GuiGraphicsExtractor g, Component c, int x, int y, int color) {
        g.text(font, c.copy().withStyle(s -> s.withColor(
                net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF))), x, y, -1, false);
    }

    /** The FormattedCharSequence must already carry its colour (see {@link #colored}). */
    private void txt(GuiGraphicsExtractor g, FormattedCharSequence c, int x, int y, int color) {
        g.text(font, c, x, y, -1, false);
    }

    /** A coloured text Component (colour in the Style, so shadowless rendering keeps it). */
    private static Component colored(String s, int color) {
        return Component.literal(s).withStyle(st -> st.withColor(
                net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF)));
    }


    private void buildChatWidgets() {
        int inputY = top + PANEL_H - INPUT_H - PAD;
        int compactW = 26;
        int sendW = 42;
        int stopW = 22;
        int inX = left + PAD + compactW + 4;
        int inW = PANEL_W - PAD * 2 - compactW - sendW - stopW - 12;

        compactButton = add(new SimpleButton(left + PAD, inputY, compactW, INPUT_H,
                Component.literal("⤬"), b -> loop().requestCompact()));
        compactButton.active = loop().canCompact();

        input = new FlatEditBox(font, inX + FIELD_INSET_X, inputY + FIELD_INSET_Y,
                inW - FIELD_INSET_X * 2, INPUT_H - FIELD_INSET_Y * 2, Component.literal("numen.chat.input"));
        input.setMaxLength(MAX_PROMPT);
        input.setBordered(false);
        input.setTextColor(TXT);
        // FlatEditBox draws the hint shadowless and UNDER the caret (same widget pass), so use it
        // directly — no separate screen-side placeholder that would paint over the blinking caret.
        // Faint colour is baked into the Component's Style.
        input.setHint(Nb.colored("Talk to " + (name == null ? "" : name) + "…", TXT_FAINT));
        if (!savedInput.isEmpty()) { input.setValue(savedInput); savedInput = ""; }
        add(input);
        setInitialFocus(input);

        sendButton = add(new SimpleButton(inX + inW + 4, inputY, sendW, INPUT_H,
                Component.literal("Send"), b -> onSend()));

        stopButton = add(new SimpleButton(inX + inW + 4 + sendW + 4, inputY, stopW, INPUT_H,
                Component.literal("■"), b -> loop().abort()));
        stopButton.active = loop().canInterrupt();
    }

    private void selectTab(Tab t) {
        if (t == tab) return;
        tab = t;
        scroll = 0;
        pinBottom = true;
        if (t == Tab.ITEMS) requestInventory();
        if (t == Tab.SETTINGS) initModelMode();
        rebuild();
    }

    /** Decide once (on entering Settings) whether the model field starts as a preset dropdown or a
     *  custom text box: custom-provider or a configured model that isn't a known preset → custom. */
    private void initModelMode() {
        INumenConfig cfg = Services.CONFIG;
        wProvider = cfg.getProvider() == null ? "openai" : cfg.getProvider();
        wApiKey = cfg.getApiKey() == null ? "" : cfg.getApiKey();
        wModel = cfg.getModel() == null ? "" : cfg.getModel();
        wBaseUrl = cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl();
        wProxy = cfg.getProxy() == null ? "" : cfg.getProxy();
        wReasoning = cfg.getReasoningEffort() == null || cfg.getReasoningEffort().isBlank()
                ? "auto" : cfg.getReasoningEffort();
        addingSite = false;
        usageView = false;   // entering Settings always lands on the form, not the Usage view
        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(wProvider));
        boolean known = mp != null && mp.models().stream().anyMatch(m -> m.id().equals(wModel));
        customModel = (mp != null && mp.custom()) || (!wModel.isBlank() && !known);
    }

    /** Snapshot the API-key + base-URL fields before a settings rebuild so the edits survive it. */
    private void preserveKeyUrl() {
        if (apiKeyInput != null) wApiKey = apiKeyInput.getValue();
        if (baseUrlInput != null) wBaseUrl = baseUrlInput.getValue();
        if (proxyInput != null) wProxy = proxyInput.getValue();
    }

    // ---- settings tab ----

    private static final int SET_SP = 33;     // settings row pitch (5 rows + Save must fit)

    private void buildSettingsWidgets() {
        int x = left + PAD, w = PANEL_W - PAD * 2;
        int y0 = top + HEADER_H + 8;

        if (usageView) {
            // Usage sub-view — swaps the whole settings body, like the "+ add site" form.
            // All text is drawn in renderUsage; only the two buttons are widgets.
            providerDropdown = null;   // don't let the stale form dropdown render/catch clicks
            SimpleButton qb = new SimpleButton(x, top + PANEL_H - PAD - 18, 110, 18,
                    Component.literal("Query balance"), b -> onQueryBalance());
            qb.active = NumenLlmClient.isConfigured() && NumenLlmClient.instance().supportsBalance();
            add(qb);
            // Full-context export (md + wire json) for the current companion — for human review.
            SimpleButton ex = new SimpleButton(x + 110 + 4, top + PANEL_H - PAD - 18, 64, 18,
                    Component.literal("Export"), b -> onExport());
            ex.active = uuid != null;
            add(ex);
            add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18,
                    64, 18, Component.literal("Back"), b -> { usageView = false; rebuild(); }));
            return;
        }

        if (addingSite) {
            // row0: site name + cancel
            siteNameInput = field(x, y0 + 11, w - 20, 64, wSiteName);
            add(new SimpleButton(x + w - 18, y0 + 11, 18, 18, Component.literal("✕"),
                    b -> { addingSite = false; rebuild(); }));
            buildApiKeyRow(x, y0 + SET_SP + 11, w);
            modelInput = field(x, y0 + 2 * SET_SP + 11, w, 128, wModel);
            baseUrlInput = field(x, y0 + 3 * SET_SP + 11, w, 256, wBaseUrl);
        } else {
            providerDropdown = new ProviderDropdown(wProvider, true);   // live + "+ 添加站点"
            providerDropdown.setBounds(x, y0 + 11, w, 18);
            buildApiKeyRow(x, y0 + SET_SP + 11, w);
            buildModelRow(x, y0 + 2 * SET_SP + 11, w);
            baseUrlInput = field(x, y0 + 3 * SET_SP + 11, w, 256, wBaseUrl);
            proxyInput = field(x, y0 + 4 * SET_SP + 11, w, 128, wProxy);
            // Reasoning-effort cycle button, self-labeled, on the Save line (no room
            // for a 6th labeled row — 5 rows + Save fill the panel). Values are
            // engine-generic; providers without reasoning_effort ignore them.
            int reasonW = 110;
            add(new SimpleButton(left + PANEL_W - PAD - 64 - 4 - reasonW, top + PANEL_H - PAD - 18,
                    reasonW, 18, Component.literal("Reasoning: " + wReasoning),
                    b -> {
                        wReasoning = nextEffort(wReasoning);
                        b.setMessage(Component.literal("Reasoning: " + wReasoning));
                    }));
            // Usage view entry — swaps the settings body to session stats + balance query.
            int usageW = 48;
            add(new SimpleButton(left + PANEL_W - PAD - 64 - 4 - reasonW - 4 - usageW,
                    top + PANEL_H - PAD - 18, usageW, 18, Component.literal("Usage"),
                    b -> { preserveKeyUrl(); usageView = true; balanceLine = null; exportLine = null; rebuild(); }));
            // Per-companion capability toggles on the free LEFT half of the action row. The rest of this tab is
            // GLOBAL config (Services.CONFIG); these two act on the CURRENTLY SELECTED companion (the rail's
            // active uuid), so they appear only when one is picked.
            if (uuid != null) buildCompanionCapButtons(top + PANEL_H - PAD - 18);
        }

        add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18,
                64, 18, Component.literal("Save"), b -> onSaveSettings()));
    }

    /**
     * The two per-companion capability toggles: GAME MODE (survival ⇄ creative) and OP (the command-permission
     * master switch). Current state is read from the server-authored {@link NumenRoster}; a click sends the flip
     * to the server (which persists it and re-pushes the roster) and optimistically relabels — the owner is
     * always authorised for their own panel, so the optimistic value and the confirming re-push agree. Each
     * click reads the LATEST roster state (not a build-time snapshot), so repeated clicks stay correct.
     */
    private void buildCompanionCapButtons(int rowY) {
        NumenRoster.Entry re = NumenRoster.instance().byUuid(uuid);
        GameType mode = re != null ? re.gameType() : GameType.SURVIVAL;
        boolean op = re != null && re.opEnabled();

        int modeW = 62, opW = 42;
        int modeX = left + PAD;
        int opX = modeX + modeW + 4;

        add(new SimpleButton(modeX, rowY, modeW, 18, Component.literal(modeLabel(mode)), b -> {
            NumenRoster.Entry cur = NumenRoster.instance().byUuid(uuid);
            GameType curMode = cur != null ? cur.gameType() : GameType.SURVIVAL;
            GameType next = curMode == GameType.CREATIVE ? GameType.SURVIVAL : GameType.CREATIVE;
            Services.NETWORK.sendToServer(
                    new com.dwinovo.numen.network.payload.SetCompanionGameModePayload(uuid, next));
            b.setMessage(Component.literal(modeLabel(next)));
        }));

        add(new SimpleButton(opX, rowY, opW, 18, Component.literal(opLabel(op)), b -> {
            NumenRoster.Entry cur = NumenRoster.instance().byUuid(uuid);
            boolean next = !(cur != null && cur.opEnabled());
            Services.NETWORK.sendToServer(
                    new com.dwinovo.numen.network.payload.SetCompanionOpPayload(uuid, next));
            b.setMessage(Component.literal(opLabel(next)));
        }));
    }

    private static String modeLabel(GameType mode) {
        return "模式:" + (mode == GameType.CREATIVE ? "创造" : "生存");
    }

    private static String opLabel(boolean op) {
        return "OP:" + (op ? "开" : "关");
    }

    /** Cycle auto → off → minimal → low → medium → high → auto (any unrecognised value re-enters at auto). */
    private static String nextEffort(String cur) {
        return switch (cur == null ? "auto" : cur) {
            case "auto" -> "off";
            case "off" -> "minimal";
            case "minimal" -> "low";
            case "low" -> "medium";
            case "medium" -> "high";
            default -> "auto";
        };
    }

    private void buildApiKeyRow(int x, int y, int w) {
        int eyeW = 22;
        apiKeyInput = field(x, y, w - eyeW - 2, 512, wApiKey);
        apiKeyInput.addFormatter((text, idx) -> showKey
                ? FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY)
                : FormattedCharSequence.forward("•".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        // Eye icon instead of a 见/隐 glyph: open eye when masked (click to show), slashed when shown.
        add(new SimpleButton(x + w - eyeW, y, eyeW, 18, Component.empty(),
                b -> { showKey = !showKey; ((SimpleButton) b).icon(showKey ? EYE_OFF : EYE); })
                .icon(showKey ? EYE_OFF : EYE));
    }

    /** Model row: a preset dropdown for the provider's known models, or a free-text box (custom mode)
     *  with a "▾" toggle back to presets. A custom provider (openai-compatible) is always free-text. */
    private void buildModelRow(int x, int y, int w) {
        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(providerDropdown.selectedId()));
        boolean providerCustom = mp != null && mp.custom();
        if (customModel || providerCustom) {
            customModel = true;
            modelDropdown = null;
            modelInput = field(x, y, providerCustom ? w : w - 20, 128, wModel);
            if (!providerCustom) {     // a way back to the preset list (custom providers have none)
                add(new SimpleButton(x + w - 18, y, 18, 18, Component.literal("▾"),
                        b -> { preserveKeyUrl(); customModel = false; rebuild(); }));
            }
        } else {
            modelInput = null;
            boolean known = mp != null && mp.models().stream().anyMatch(m -> m.id().equals(wModel));
            String sel = known ? wModel
                    : (mp != null && !mp.models().isEmpty() ? mp.models().get(0).id() : CUSTOM_MODEL);
            modelDropdown = new Dropdown(modelItems(mp), sel);
            modelDropdown.setBounds(x, y, w, 18);
        }
    }

    private List<Dropdown.Item> modelItems(ModelRegistry.Provider mp) {
        List<Dropdown.Item> items = new ArrayList<>();
        if (mp != null) for (ModelRegistry.Model m : mp.models()) items.add(new Dropdown.Item(m.id(), m.id()));
        items.add(new Dropdown.Item(CUSTOM_MODEL, "自定义…"));
        return items;
    }

    /** Shadowless placeholder for an empty, unfocused field — the EditBox's own hint renders with a shadow. */
    private void placeholder(GuiGraphicsExtractor g, EditBox f, String text) {
        if (f != null && f.getValue().isEmpty() && !f.isFocused() && text != null && !text.isEmpty()) {
            txt(g, Component.literal(text), f.getX(), f.getY(), TXT_FAINT);
        }
    }

    private EditBox field(int x, int y, int w, int max, String value) {
        EditBox e = new FlatEditBox(font, x + FIELD_INSET_X, y + FIELD_INSET_Y,
                w - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
        e.setMaxLength(max);
        e.setValue(value == null ? "" : value);
        e.setBordered(false);
        e.setTextColor(TXT);
        add(e);
        return e;
    }

    private void onSaveSettings() {
        INumenConfig cfg = Services.CONFIG;
        if (addingSite) {                          // create a new user site, then select it
            String name = siteNameInput.getValue().trim();
            String url = baseUrlInput.getValue().trim();
            String mdl = modelInput.getValue().trim();
            if (name.isEmpty() || url.isEmpty()) { warnUntil = System.currentTimeMillis() + 4000; return; }
            String id = ModelRegistry.addCustomSite(name, url, mdl);
            if (id == null) { warnUntil = System.currentTimeMillis() + 4000; return; }
            cfg.setProvider(id);
            cfg.setModel(mdl);
            cfg.setApiKey(apiKeyInput.getValue());
            cfg.setBaseUrl("");                    // site carries the URL now
            cfg.setProxy(wProxy);
            cfg.save();
            NumenLlmClient.reset();
            addingSite = false;
            wProvider = id; wModel = mdl; wBaseUrl = ""; customModel = false;
            rebuild();
            savedFlashUntil = System.currentTimeMillis() + 1500;
            return;
        }
        cfg.setProvider(providerDropdown.selectedId());
        cfg.setApiKey(apiKeyInput.getValue());
        String model = customModel
                ? (modelInput != null ? modelInput.getValue().trim() : "")
                : (modelDropdown != null && !CUSTOM_MODEL.equals(modelDropdown.selectedId())
                        ? modelDropdown.selectedId() : "");
        cfg.setModel(model);
        cfg.setBaseUrl(baseUrlInput.getValue());
        cfg.setProxy(proxyInput == null ? wProxy : proxyInput.getValue());
        cfg.setReasoningEffort(wReasoning);
        cfg.save();
        NumenLlmClient.reset();
        savedFlashUntil = System.currentTimeMillis() + 1500;
    }

    private void renderSettings(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left + PAD;
        int y0 = top + HEADER_H + 8;
        if (usageView) {
            renderUsage(g, x, y0);
            return;
        }
        if (addingSite) {
            txt(g, Component.literal("Site name"), x, y0, TXT_MUTED);
            txt(g, Component.literal("API Key"), x, y0 + SET_SP, TXT_MUTED);
            txt(g, Component.literal("Model"), x, y0 + 2 * SET_SP, TXT_MUTED);
            txt(g, Component.literal("Base URL"), x, y0 + 3 * SET_SP, TXT_MUTED);
        } else {
            txt(g, Component.literal("Provider"), x, y0, TXT_MUTED);
            txt(g, Component.literal("API Key"), x, y0 + SET_SP, TXT_MUTED);
            txt(g, Component.literal("Model"), x, y0 + 2 * SET_SP, TXT_MUTED);
            txt(g, Component.literal("Base URL"), x, y0 + 3 * SET_SP, TXT_MUTED);
            txt(g, Component.literal("Proxy"), x, y0 + 4 * SET_SP, TXT_MUTED);
        }
        if (savedFlashUntil > System.currentTimeMillis()) {
            txt(g, Component.literal("✔ saved"), x, top + PANEL_H - PAD - 14, OK);
        }
        // the dropdowns themselves render in render, AFTER the widgets (open list on top)
    }

    /**
     * " · cache NN%" for a usage stat, or "" when the provider reported no cache detail
     * (unknown is not 0% — hide rather than mislead). The percentage is the provider's
     * own metering, i.e. the supplier-side audit of the frozen-prefix constitution.
     */
    private static String cacheSuffix(UsageTracker.Stat s) {
        double rate = s.cacheHitRate();
        return rate < 0 ? "" : " · cache " + Math.round(rate * 100) + "%";
    }

    /** The Usage sub-view body: session token stats (global + per companion) and the balance line. */
    private void renderUsage(GuiGraphicsExtractor g, int x, int y0) {
        int y = y0;
        UsageTracker.Stat all = UsageTracker.instance().global();
        txt(g, Component.literal("Usage (this session)"), x, y, TXT_MUTED);
        y += 14;
        txt(g, Component.literal("all: " + all.requests() + " req · prompt " + fmtTok(all.promptTokens())
                + " · completion " + fmtTok(all.completionTokens()) + cacheSuffix(all)), x, y, TXT);
        y += 14;
        var per = UsageTracker.instance().perCompanion();
        if (per.isEmpty()) {
            txt(g, Component.literal("no requests yet"), x, y, TXT_FAINT);
        } else {
            int yMax = top + PANEL_H - PAD - 18 - 38;   // stay clear of the two status lines + buttons
            for (var e : per.entrySet()) {
                if (y > yMax) break;
                UsageTracker.Stat s = e.getValue();
                txt(g, Component.literal(rosterName(e.getKey()) + ": " + s.requests() + " req · "
                        + fmtTok(s.promptTokens()) + "/" + fmtTok(s.completionTokens()) + " tok"
                        + cacheSuffix(s)),
                        x, y, TXT_MUTED);
                y += 12;
            }
        }
        int w = PANEL_W - PAD * 2;
        // Export status line (written path / error), muted — mirrors the compacting-hint style.
        int by = top + PANEL_H - PAD - 18 - 12;
        if (exportLine != null) {
            txt(g, Component.literal(fitOneLine(exportLine, w)), x, by - 12, TXT_MUTED);
        }
        // Balance status line, just above the Query balance / Export / Back buttons.
        String line = balanceLine;
        if (line != null) {
            txt(g, Component.literal(fitOneLine(line, w)), x, by, TXT);
        } else if (!NumenLlmClient.isConfigured() || !NumenLlmClient.instance().supportsBalance()) {
            txt(g, Component.literal("provider has no balance API"), x, by, TXT_FAINT);
        }
    }

    /**
     * Export the current companion's full context (md + wire json) for human review.
     * Failure-safe by construction: everything is caught; errors render as a muted row.
     */
    private void onExport() {
        if (uuid == null) {
            exportLine = "no companion selected";
            return;
        }
        try {
            java.nio.file.Path p = ContextExporter.export(loop());
            java.nio.file.Path game = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
            java.nio.file.Path ap = p.toAbsolutePath().normalize();
            String shown = ap.startsWith(game)
                    ? game.relativize(ap).toString().replace('\\', '/')
                    : ap.toString();
            exportLine = "exported: " + shown;
        } catch (Exception ex) {
            com.dwinovo.numen.Constants.LOG.warn("[numen-export] failed: {}", ex.toString());
            exportLine = "export failed: " + shortErr(ex);
        }
    }

    /** Kick off the async balance query; the result lands in {@link #balanceLine} for render. */
    private void onQueryBalance() {
        balanceLine = "querying…";
        NumenLlmClient.instance().queryBalance().whenComplete((s, err) ->
                balanceLine = err != null ? "error: " + shortErr(err) : s);
    }

    /** Companion display name from the roster, falling back to a uuid stub for despawned ones. */
    private static String rosterName(UUID u) {
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
            if (e.uuid().equals(u)) return e.name();
        }
        return u.toString().substring(0, 8);
    }

    /** Compact token count: 812 → "812", 12345 → "12.3k", 4200000 → "4.20M". */
    private static String fmtTok(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0);
        return String.format(java.util.Locale.ROOT, "%.2fM", n / 1_000_000.0);
    }

    /** Root-cause message, one line, truncated — for the balance error display. */
    private static String shortErr(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c != c.getCause()) c = c.getCause();
        String m = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
        m = m.replace('\n', ' ');
        return m.length() > 70 ? m.substring(0, 70) + "…" : m;
    }

    @Override
    public void tick() {
        if (tab == Tab.ITEMS && ++tickCounter % INV_REFRESH_TICKS == 0) {
            requestInventory();
        }
    }

    private void requestInventory() {
        // No companion selected (empty roster / hotkey-opened blank panel) → nothing to fetch.
        // The payload's UUID stream-codec can't encode null, so this guard also prevents a crash.
        if (uuid == null) return;
        if (Minecraft.getInstance().getConnection() != null) {
            Services.NETWORK.sendToServer(new RequestInventoryPayload(uuid));
        }
    }

    private void onSend() {
        if (input == null) return;
        String text = input.getValue() == null ? "" : input.getValue().trim();
        if (text.isEmpty() && pendingImages.isEmpty()) return;   // nothing to send
        if (!NumenLlmClient.isConfigured()) {
            com.dwinovo.numen.Constants.LOG.warn("[numen-chat] no apiKey; open Settings.");
            warnUntil = System.currentTimeMillis() + 4000;   // visible hint instead of a silent no-op
            return;
        }
        // Persist the composed images (copy bytes into config/numen/attachments/<uuid>/)
        // and hand their paths to the loop alongside the text — one API call.
        List<String> attachments = persistPending();
        loop().submitPrompt(text, attachments);
        input.setValue("");
        clearAttachments();   // frees the thumbnail textures; the files persist on disk
        pinBottom = true;
    }

    /** Copy every pending image into the companion's attachments dir; returns the saved paths. */
    private List<String> persistPending() {
        if (pendingImages.isEmpty() || uuid == null) return List.of();
        List<String> paths = new ArrayList<>();
        long ts = System.currentTimeMillis();
        int n = 0;
        for (PendingImage pi : pendingImages) {
            Path saved = ChatImages.persist(pi.source(), uuid, ts, n++);
            if (saved != null) paths.add(saved.toAbsolutePath().toString());
        }
        return paths;
    }

    // ---- input ----

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (dismissPending != null) {
            if (k == 256) { dismissPending = null; rebuild(); return true; }   // Esc cancels the confirm
            return super.keyPressed(event);
        }
        if (summoning) {
            if (k == 257 || k == 335) { doSummon(); return true; }    // Enter
            if (k == 256) { summoning = false; rebuild(); return true; } // Esc cancels (doesn't close panel)
            return super.keyPressed(event);
        }
        if ((k == 257 || k == 335) && input != null && input.isFocused()) {
            onSend();
            return true;
        }
        // Ctrl+V image paste (best-effort). Minecraft sets java.awt.headless=true,
        // so AWT clipboard access usually throws on the dev client — drag-and-drop
        // is the reliable path. When paste yields no image and the clipboard has no
        // text either (i.e. the user meant to paste an image we can't read), show a
        // hint; when it DOES hold text, fall through so the EditBox pastes it.
        if (tab == Tab.CHAT && uuid != null && event.isPaste()) {
            if (tryPasteImages()) return true;
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clip == null || clip.isBlank()) {
                flashAttachHint("拖拽图片文件到窗口即可添加");
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void doSummon() {
        String n = summonInput == null ? "" : summonInput.getValue().trim();
        if (n.isEmpty()) return;
        Services.NETWORK.sendToServer(new com.dwinovo.numen.network.payload.SummonRequestPayload(n));
        summoning = false;
        rebuild();   // the new companion arrives via CompanionListPayload — click its avatar to open
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        if (dismissPending != null) {
            return super.mouseClicked(event, dbl);   // modal confirm — let its Cancel/Delete buttons handle it
        }
        if (button == 0) {
            UUID close = railCloseAt((int) mouseX, (int) mouseY);
            if (close != null) { dismissPending = close; rebuild(); return true; }   // ✕ → confirm bar
            if (railPlusAt((int) mouseX, (int) mouseY)) {   // + → start the summon name prompt
                summoning = !summoning;
                rebuild();
                return true;
            }
            int rail = railIndexAt((int) mouseX, (int) mouseY);
            if (rail >= 0) {
                List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
                if (rail < entries.size()) {
                    boolean wasSummoning = summoning;
                    summoning = false;
                    NumenRoster.Entry e = entries.get(rail);
                    if (e.uuid().equals(uuid)) { if (wasSummoning) rebuild(); }   // already active — just exit summon
                    else switchTo(e.uuid(), e.name());
                }
                return true;
            }
            if (tab == Tab.SETTINGS && providerDropdown != null) {
                String before = providerDropdown.selectedId();
                if (providerDropdown.mouseClicked(mouseX, mouseY)) {
                    if (modelDropdown != null) modelDropdown.close();
                    String sel = providerDropdown.selectedId();
                    if (ProviderDropdown.ADD_SITE.equals(sel)) {            // "+ 添加站点" → add-site editor
                        preserveKeyUrl();
                        addingSite = true; wSiteName = ""; wBaseUrl = ""; wModel = "";
                        rebuild();
                    } else if (!sel.equals(before)) {                      // provider changed → reset model
                        preserveKeyUrl();
                        wProvider = sel;
                        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(wProvider));
                        customModel = mp != null && mp.custom();
                        wModel = (mp != null && !mp.models().isEmpty()) ? mp.models().get(0).id() : "";
                        rebuild();
                    }
                    return true;
                }
            }
            if (tab == Tab.SETTINGS && modelDropdown != null
                    && modelDropdown.mouseClicked(mouseX, mouseY)) {
                providerDropdown.close();
                if (CUSTOM_MODEL.equals(modelDropdown.selectedId())) {       // "自定义…" → free-text box
                    preserveKeyUrl();
                    customModel = true;
                    wModel = "";
                    rebuild();
                }
                return true;
            }
            int my = (int) mouseY;
            if (my >= top && my < top + HEADER_H) {
                for (int i = 0; i < 3; i++) {
                    if (mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i]) {
                        selectTab(Tab.values()[i]);
                        return true;
                    }
                }
            }
            if (tab == Tab.CHAT && removeCardAt((int) mouseX, my)) return true;   // ❌ on a pending image
            if (tab == Tab.CHAT && toggleFoldAt((int) mouseX, my)) return true;
        }
        return super.mouseClicked(event, dbl);
    }

    /** If a chat fold-toggle row sits under (mx,my), flip its expanded state. Mirrors renderChat geometry. */
    private boolean toggleFoldAt(int mx, int my) {
        int bodyY = top + HEADER_H + 4;
        int bodyBottom = top + PANEL_H - INPUT_H - PAD - 6 - ctxBandH() - cardsBandH();
        int transX = left + PAD;
        int transW = PANEL_W - PAD * 2 - PLAN_W - 8;
        if (mx < transX || mx >= transX + transW || my < bodyY || my >= bodyBottom) return false;
        List<Row> rows = buildRows(transW);
        int idx = (my - (bodyY - scroll)) / LINE_H;
        if (idx < 0 || idx >= rows.size()) return false;
        String key = rows.get(idx).foldKey();
        if (key == null) return false;
        if (!expandedGroups.add(key)) expandedGroups.remove(key);   // toggle open/closed
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // Wheel over the left rail column scrolls the roster (works on any tab).
        if (sy != 0 && mx >= railX && mx < railX + RAIL_W && maxRailScroll() > 0) {
            railScroll = Math.clamp((long) (railScroll - sy), 0, maxRailScroll());
            return true;
        }
        if (tab == Tab.CHAT && sy != 0) {
            scroll = Math.clamp((long) (scroll - sy * LINE_H * 3), 0, lastMaxScroll);
            pinBottom = scroll >= lastMaxScroll;
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ---- render ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        // ONE merged Cottage sprite: left rail column + panel, continuous header, no gap.
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, 
                WORKSPACE_SPRITE, railX, top, RAIL_W + PANEL_W, PANEL_H);
        renderRail(g, mouseX, mouseY);   // avatars + status + summon tile on the rail column

        txt(g, Component.literal(name == null ? "Numen" : name), left + PAD, top + 7, ON_BAND);
        if (uuid != null && ClientDeaths.isDead(uuid)) {        // active companion dead — respawn countdown
            long rem = ClientDeaths.remainingMs(uuid);
            txt(g, Component.literal("· 复活中 " + (int) Math.ceil(rem / 1000.0) + "s"),
                    left + PAD + font.width(name == null ? "Numen" : name) + 6, top + 7, ON_BAND);
        }
        renderTabs(g, mouseX, mouseY);

        if (dismissPending != null) {
            txt(g, Component.literal("删除同伴 \"" + nameFor(dismissPending) + "\"？"),
                    left + PAD, top + HEADER_H + 12, TXT);
            txt(g, Component.literal("永久删除 · 背包会掉落在原地 · 无法撤销"),
                    left + PAD, top + HEADER_H + 30, FAIL);
        } else if (summoning) {
            txt(g, Component.literal("Summon a companion"), left + PAD, top + HEADER_H + 8, TXT);
            txt(g, Component.literal("type a name · Enter to confirm · Esc to cancel"),
                    left + PAD, top + HEADER_H + 48, TXT_FAINT);
        } else {
            if (uuid != null) {
                if (compactButton != null) compactButton.active = loop().canCompact();
                if (stopButton != null) stopButton.active = loop().canInterrupt();
            }
            switch (tab) {
                case SETTINGS -> renderSettings(g, mouseX, mouseY);   // global — works with no companion
                case CHAT -> { if (uuid != null) renderChat(g); else emptyHint(g); }
                case ITEMS -> { if (uuid != null) renderItems(g, mouseX, mouseY); else emptyHint(g); }
            }
            if (tab == Tab.CHAT && warnUntil > System.currentTimeMillis()) {   // no-API-key hint above the input
                txt(g, Component.literal("⚠ No API key — open Settings to add one"),
                        left + PAD, top + PANEL_H - INPUT_H - PAD - 11, FAIL);
            }
        }

        // Widgets render LAST, on top of the panel background (fixes the "dim fields" — the panel fill
        // used to paint over the auto-rendered widgets). Text fields are borderless EditBoxes, so draw
        // a parchment field background + border behind each before it renders its text.
        for (AbstractWidget w : overlay) {
            if (w instanceof EditBox eb) {                          // parchment frame, inflated past the inset text
                g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, 
                        FIELD_SPRITE, eb.getX() - FIELD_INSET_X, eb.getY() - FIELD_INSET_Y,
                        eb.getWidth() + FIELD_INSET_X * 2, eb.getHeight() + FIELD_INSET_Y * 2);
            }
        }
        for (AbstractWidget w : overlay) {
            w.extractRenderState(g, mouseX, mouseY, partial);
        }
        // Base URL / Proxy placeholders, drawn shadowless by us (the EditBox hint renders with a shadow).
        // Skipped in the Usage sub-view: its body has no fields and providerDropdown is null there.
        if (tab == Tab.SETTINGS && !usageView) {
            String urlPh = addingSite ? "https://… (OpenAI-compatible)"
                    : LlmProviders.byId(providerDropdown.selectedId()).defaultBaseUrl();
            placeholder(g, baseUrlInput, urlPh);
            placeholder(g, proxyInput, "host:port (optional)");
            // Model + site-name placeholders, also shadowless (these EditBoxes are null outside
            // custom-model / add-site mode, and placeholder() no-ops on null/non-empty/focused).
            placeholder(g, modelInput, addingSite ? "model id"
                    : LlmProviders.byId(providerDropdown.selectedId()).defaultModel());
            if (addingSite) placeholder(g, siteNameInput, "e.g. My Proxy");
        }
        // (Chat-input placeholder is the FlatEditBox hint now — drawn shadowless and under the
        // caret in the widget pass, so it can't paint over the caret like a screen-side draw did.)
        // The provider dropdown's open list must sit above even the fields.
        if (tab == Tab.SETTINGS && !usageView) {
            // render the non-open one first so the open list draws on top
            if (modelDropdown != null && providerDropdown != null && providerDropdown.isOpen()) {
                modelDropdown.render(g, font, mouseX, mouseY);
                providerDropdown.render(g, font, mouseX, mouseY);
            } else {
                if (providerDropdown != null) providerDropdown.render(g, font, mouseX, mouseY);
                if (modelDropdown != null) modelDropdown.render(g, font, mouseX, mouseY);
            }
        }
    }

    // ---- left companion rail ----

    /** The folded-in roster (on the merged sprite's rail column): one avatar head per companion below the
     *  green header, active one framed gold, a status dot each, + tile at the bottom. */
    private void renderRail(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        railScroll = Math.clamp(railScroll, 0, maxRailScroll());     // keep valid as the roster grows/shrinks
        int first = railScroll;
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            NumenRoster.Entry e = entries.get(i);
            boolean active = e.uuid().equals(uuid);
            // textured socket behind the head (gold-bordered when active), then the avatar, then a status LED
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, active ? AVATAR_FRAME_ACTIVE : AVATAR_FRAME, ax - 2, ay - 2, RAIL_AV + 4, RAIL_AV + 4);
            PlayerFaceExtractor.extractRenderState(g, skinFor(e.uuid()), ax, ay, RAIL_AV);
            if (ClientDeaths.isDead(e.uuid())) {                      // dead — dim veil + respawn countdown
                g.fill(ax, ay, ax + RAIL_AV, ay + RAIL_AV, 0xB0101010);
                long rem = ClientDeaths.remainingMs(e.uuid());
                if (rem >= 0) {
                    String c = String.valueOf((int) Math.ceil(rem / 1000.0));
                    txt(g, Component.literal(c), ax + (RAIL_AV - font.width(c)) / 2, ay + (RAIL_AV - 8) / 2, CTA);
                }
            } else {
                int d = ax + RAIL_AV - 6, e2 = ay + RAIL_AV - 6;     // status LED, bottom-right
                g.fill(d, e2, d + 5, e2 + 5, statusColor(e.uuid()));
                Nb.border(g, d, e2, 5, 5, 1, BORDER);
            }
            // hover → a small ✕ badge breaking OUT of the avatar's top-right corner (overhangs the frame).
            // Show it while the cursor is over the avatar OR the badge itself (the badge sticks out, so
            // moving onto it must not make it vanish).
            int bx = ax + RAIL_AV - 3, by = ay - 5;
            boolean overAvatar = mouseX >= ax && mouseX < ax + RAIL_AV && mouseY >= ay && mouseY < ay + RAIL_AV;
            boolean overBadge = mouseX >= bx && mouseX < bx + 9 && mouseY >= by && mouseY < by + 9;
            if (dismissPending == null && (overAvatar || overBadge)) {
                g.fill(bx, by, bx + 9, by + 9, FAIL);
                Nb.border(g, bx, by, 9, 9, 1, BORDER);
                txt(g, Component.literal("✕"), bx + 2, by + 1, ON_BAND);
            }
        }
        // "+" summon tile (baked "+" glyph), pinned to the rail bottom
        int py = top + PANEL_H - PAD - RAIL_AV;
        // scroll cues — gold chevrons when the roster overflows the rail in either direction
        int cx = ax + RAIL_AV / 2;
        if (railScroll > 0) chevron(g, cx, top + 1, true);
        if (railScroll < maxRailScroll()) chevron(g, cx, py - 9, false);
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, summoning ? SUMMON_ACTIVE : SUMMON_SPRITE, ax, py, RAIL_AV, RAIL_AV);
    }

    /** Scroll-affordance chevron sprite (amber pixel-art triangle, up = more above / down = more below).
     *  Blitted at its native 11×6 so the pixels stay crisp (no scaling, no AA). */
    private void chevron(GuiGraphicsExtractor g, int cx, int y, boolean up) {
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, 
                up ? CHEVRON_UP : CHEVRON_DOWN, cx - 5, y, 11, 6);
    }

    /** Bottom edge an avatar may reach (a gap above the pinned "+" tile). */
    private int railBottomEdge() {
        return top + PANEL_H - PAD - RAIL_AV - RAIL_BOT_GAP;
    }

    /** How many avatar slots fit in the rail above the pinned "+" tile. */
    private int railVisibleSlots() {
        int slots = 0;
        while (top + RAIL_TOP + slots * RAIL_SLOT + RAIL_AV <= railBottomEdge()) slots++;
        return Math.max(1, slots);
    }

    private int maxRailScroll() {
        return Math.max(0, NumenRoster.instance().entries().size() - railVisibleSlots());
    }

    /** Y of the first (visible) avatar: centred vertically when the whole roster fits, top-aligned once
     *  it overflows and scrolls. Fixes the big bottom gap + the first avatar poking past the top edge. */
    private int railStartY() {
        int n = NumenRoster.instance().entries().size();
        if (n > railVisibleSlots()) return top + RAIL_TOP;          // scrolling — top-align
        int blockH = Math.max(0, n - 1) * RAIL_SLOT + RAIL_AV;
        int span = railBottomEdge() - (top + RAIL_TOP);
        return top + RAIL_TOP + Math.max(0, (span - blockH) / 2);   // centre the block
    }

    /** The companion whose hover-✕ badge is under (mx,my), or null. Mirrors renderRail geometry. */
    private UUID railCloseAt(int mx, int my) {
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        int first = Math.clamp(railScroll, 0, maxRailScroll());
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            int bx = ax + RAIL_AV - 3, by = ay - 5;   // overhanging top-right badge (matches renderRail)
            if (mx >= bx && mx < bx + 9 && my >= by && my < by + 9) return entries.get(i).uuid();
        }
        return null;
    }

    private boolean railPlusAt(int mx, int my) {
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        int py = top + PANEL_H - PAD - RAIL_AV;
        return mx >= ax && mx < ax + RAIL_AV && my >= py && my < py + RAIL_AV;
    }

    /** idle = green, working/compacting = amber, queued = gold; faint if no loop yet. */
    private int statusColor(UUID u) {
        return AgentLoopRegistry.get(u).map(loop -> {
            if (loop.isCompacting() || loop.isBusy()) return RUN;
            if (loop.hasQueuedPrompts()) return CTA;
            return OK;
        }).orElse(TXT_FAINT);
    }

    private static PlayerSkin skinFor(UUID u) {
        AbstractClientPlayer e = ClientNumenLookup.resolve(u);
        return e != null ? e.getSkin() : DefaultPlayerSkin.get(u);
    }

    /** Roster index of the avatar under (mx,my), or -1. */
    private int railIndexAt(int mx, int my) {
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        if (mx < ax || mx >= ax + RAIL_AV) return -1;
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        int first = Math.clamp(railScroll, 0, maxRailScroll());
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            if (my >= ay && my < ay + RAIL_AV) return i;
        }
        return -1;
    }

    private void emptyHint(GuiGraphicsExtractor g) {
        txt(g, Component.literal("No companions. Click + to summon one."),
                left + PAD, top + HEADER_H + 10, TXT_FAINT);
    }

    private void renderTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String[] labels = {"Chat", "Items", "Settings"};
        for (int i = 0; i < 3; i++) {
            boolean active = tab == Tab.values()[i];
            boolean hover = mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i]
                    && mouseY >= top && mouseY < top + HEADER_H;
            int color = (active || hover) ? ON_BAND : (0x00FFFFFF & ON_BAND) | 0xA0000000;   // dim on band
            txt(g, Component.literal(labels[i]), tabX[i] + 5, top + 7, color);
            if (active) {                                                                     // gold CTA underline
                g.fill(tabX[i] + 3, top + HEADER_H - 4, tabX[i] + tabW[i] - 3, top + HEADER_H - 1, ACCENT);
            }
        }
    }

    private static final int ICON = 9;        // native vitals-icon size
    private static final int ICON_STEP = 9;   // touching = one chunky bar

    /** A row of segmented icons for a 0..max stat (2 units per icon): empty sockets first, then
     *  full / half overlaid. Used for hearts (HP) and drumsticks (hunger). */
    private void renderStatRow(GuiGraphicsExtractor g, int x, int y, float value, float max,
                               net.minecraft.resources.Identifier full,
                               net.minecraft.resources.Identifier half,
                               net.minecraft.resources.Identifier empty) {
        int units = Math.max(1, (int) Math.ceil(max / 2f));
        for (int i = 0; i < units; i++) {
            int ix = x + i * ICON_STEP;
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, empty, ix, y, ICON, ICON);
            float v = value - i * 2f;
            if (v >= 2f)      g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, full, ix, y, ICON, ICON);
            else if (v >= 1f) g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, half, ix, y, ICON, ICON);
        }
    }

    /** Live mouse-following 3D portrait of the companion — the body IS a client player entity, so the
     *  vanilla player renderer draws it for free. Sits in a recessed socket (slot_alt stretched). */
    private void renderPortrait(GuiGraphicsExtractor g, AbstractClientPlayer e,
                                int x, int y, int w, int h, int mouseX, int mouseY) {
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, SLOT_ALT, x, y, w, h);
        if (e == null) return;
        int scale = (int) (h * 0.45f);
        net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(
                g, x + 2, y + 2, x + w - 2, y + h - 2, scale, 0.0625f,
                (float) mouseX, (float) mouseY, e);
    }

    private void slotBg(GuiGraphicsExtractor g, net.minecraft.resources.Identifier sprite, int x, int y) {
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sprite, x, y, 16, 16);
    }

    private void stackOn(GuiGraphicsExtractor g, ItemStack st, int x, int y, int mouseX, int mouseY) {
        if (st == null || st.isEmpty()) return;
        g.item(st, x, y);
        g.itemDecorations(font, st, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            g.setTooltipForNextFrame(font, st, mouseX, mouseY);
        }
    }

    /** One equipment/armor socket, read off the live client entity (equipment IS client-synced). */
    private void drawEquip(GuiGraphicsExtractor g, AbstractClientPlayer e, EquipmentSlot slot,
                           int x, int y, int mouseX, int mouseY) {
        slotBg(g, SLOT_SPRITE, x, y);
        if (e != null) stackOn(g, e.getItemBySlot(slot), x, y, mouseX, mouseY);
    }

    // ---- chat transcript + plan ----

    private void renderChat(GuiGraphicsExtractor g) {
        int bodyY = top + HEADER_H + 4;
        int ctxH = ctxBandH();                                    // live token-usage strip, reserved when shown
        int planBottom = top + PANEL_H - INPUT_H - PAD - 6 - ctxH; // plan panel keeps full height (minus the ctx band)
        int bodyBottom = planBottom - cardsBandH();               // transcript yields room for the card row
        int transX = left + PAD;
        int transW = PANEL_W - PAD * 2 - PLAN_W - 8;
        int viewH = bodyBottom - bodyY;

        // plan panel divider + content
        int planX = transX + transW + 8;
        g.fill(planX - 4, bodyY, planX - 3, planBottom, BORDER);
        renderPlan(g, planX, bodyY, planBottom);

        List<Row> rows = buildRows(transW);
        int contentH = rows.size() * LINE_H;
        lastMaxScroll = Math.max(0, contentH - viewH);
        if (pinBottom) scroll = lastMaxScroll;
        scroll = Math.clamp((long) scroll, 0, lastMaxScroll);

        g.enableScissor(transX, bodyY, transX + transW, bodyBottom);
        int y = bodyY - scroll;
        long t = System.currentTimeMillis();
        Set<String> done = doneIds();
        Set<String> failed = failedIds();
        for (Row row : rows) {
            if (y + LINE_H > bodyY && y < bodyBottom) {
                if (row.toolIds() != null) {                 // tool row — status icon + narration (may also be click-to-expand)
                    boolean anyRunning = row.toolIds().stream().anyMatch(id -> !done.contains(id));
                    boolean anyFail = row.toolIds().stream().anyMatch(failed::contains);
                    String icon = anyRunning ? SPIN[(int) ((t / 120) % 4)] : (anyFail ? "✗" : "✔");
                    int ic = anyRunning ? RUN : (anyFail ? FAIL : OK);
                    txt(g, Component.literal(icon), transX, y, ic);
                    txt(g, row.text, transX + 11, y, row.color);
                } else if (row.foldKey() != null) {          // clickable fold toggle — glyph baked into text
                    txt(g, row.text, transX, y, row.color);
                } else {
                    txt(g, row.text, transX, y, row.color);
                }
            }
            y += LINE_H;
        }
        g.disableScissor();

        // scrollbar — Cottage track + thumb sprites (was off-theme white fills)
        if (lastMaxScroll > 0) {
            int trackH = viewH;
            int thumbH = Math.max(12, trackH * viewH / (viewH + lastMaxScroll));
            int thumbY = bodyY + (trackH - thumbH) * scroll / lastMaxScroll;
            int sbX = transX + transW - 4;
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, SCROLL_TRACK, sbX, bodyY, 4, viewH);
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, SCROLL_THUMB, sbX, thumbY, 4, thumbH);
        }

        // Live context-usage strip, in the band this frame reserved for it (0 height when hidden).
        if (ctxH > 0) renderCtxBar(g, transX, planX + PLAN_W - transX, planBottom);

        // Pending-attachment cards, in the reserved band just above the input.
        if (!pendingImages.isEmpty()) {
            renderAttachmentCards(g, transX, cardsBandTop());
        }
        // Transient attachment hint (limit reached / paste unsupported) — muted, above the band.
        if (attachHintUntil > System.currentTimeMillis()) {
            txt(g, colored(attachHintText, TXT_MUTED), transX, cardsBandTop() - 11, TXT_MUTED);
        }
    }

    // ---- live context-usage bar ----

    /** Height of the reserved ctx-bar band — 0 (hidden) until the companion has a measured prompt size. */
    private int ctxBandH() {
        return uuid != null && loop().lastPromptTokens() > 0 ? CTX_BAND_H : 0;
    }

    /**
     * One label line ("ctx 63.0k / 200k · 31%") over a thin fill strip, read straight off
     * {@link EntityAgentLoop#lastPromptTokens()} and {@link ModelRegistry#contextWindow} every
     * frame — zero stored state, like the rest of this poll-rendered panel. Fill colour steps
     * through the existing OK/RUN/FAIL palette by how close the last request came to the model's
     * context window (mirrors the tool-row status colouring just above).
     */
    private void renderCtxBar(GuiGraphicsExtractor g, int x, int w, int y) {
        int tokens = loop().lastPromptTokens();
        if (tokens <= 0) return;
        int window = ModelRegistry.contextWindow(
                LlmProviders.normalize(Services.CONFIG.getProvider()), Services.CONFIG.getModel());
        if (window <= 0) return;
        double frac = tokens / (double) window;
        int pct = (int) Math.round(frac * 100);
        int fill = frac < 0.60 ? AI : (frac < 0.85 ? RUN : FAIL);

        txt(g, Component.literal("ctx " + fmtTok(tokens) + " / " + fmtTok(window) + " · " + pct + "%"),
                x, y, TXT_MUTED);
        int barY = y + (CTX_BAND_H - CTX_BAR_H);   // strip sits flush at the bottom of the reserved band
        g.fill(x, barY, x + w, barY + CTX_BAR_H, FIELD);                          // empty track
        int fillW = (int) Math.round(w * Math.min(1.0, frac));
        if (fillW > 0) g.fill(x, barY, x + fillW, barY + CTX_BAR_H, fill);        // filled portion
    }

    // ---- pending image attachments ----

    /** Height of the reserved card band (0 when nothing is attached, so layout is stable). */
    private int cardsBandH() { return pendingImages.isEmpty() ? 0 : CARD_H + 6; }

    /** Top y of the card band — the cards sit flush above the input row. */
    private int cardsBandTop() { return top + PANEL_H - INPUT_H - PAD - CARD_H - 3; }

    private void flashAttachHint(String msg) {
        attachHintText = msg;
        attachHintUntil = System.currentTimeMillis() + 4000;
    }

    private void renderAttachmentCards(GuiGraphicsExtractor g, int x0, int y0) {
        for (int i = 0; i < pendingImages.size(); i++) {
            PendingImage pi = pendingImages.get(i);
            int cx = x0 + i * (CARD_W + CARD_GAP);
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, FIELD_SPRITE, cx, y0, CARD_W, CARD_H);
            int dw = pi.w(), dh = pi.h();                       // already scaled to fit THUMB_MAX
            int ix = cx + (CARD_W - dw) / 2;
            int iy = y0 + (CARD_H - dh) / 2;
            g.blit(pi.texId(), ix, iy, ix + dw, iy + dh, 0f, 1f, 0f, 1f);
            txt(g, colored("✗", FAIL), cx + CARD_W - 8, y0 + 1, FAIL);   // remove affordance (top-right)
        }
    }

    /** True if (mx,my) hit a card's ❌ region — remove that pending image and free its texture. */
    private boolean removeCardAt(int mx, int my) {
        if (pendingImages.isEmpty()) return false;
        int x0 = left + PAD;
        int y0 = cardsBandTop();
        for (int i = 0; i < pendingImages.size(); i++) {
            int cx = x0 + i * (CARD_W + CARD_GAP);
            if (mx >= cx + CARD_W - 12 && mx < cx + CARD_W && my >= y0 && my < y0 + 12) {
                freeAttachment(i);
                return true;
            }
        }
        return false;
    }

    /** Add an image file as a pending attachment (decodes a thumbnail); returns false if rejected. */
    private boolean addAttachment(Path source) {
        if (pendingImages.size() >= MAX_ATTACH) {
            flashAttachHint("最多 " + MAX_ATTACH + " 张图片");
            return false;
        }
        for (PendingImage pi : pendingImages) {
            if (pi.source().equals(source)) return false;   // already attached
        }
        ChatImages.Thumb thumb = ChatImages.loadThumbnail(source, THUMB_MAX);
        if (thumb == null) return false;
        pendingImages.add(new PendingImage(source, thumb.texId(), thumb.width(), thumb.height()));
        return true;
    }

    private void freeAttachment(int i) {
        PendingImage pi = pendingImages.remove(i);
        Minecraft.getInstance().getTextureManager().release(pi.texId());   // closes texture + NativeImage
    }

    private void clearAttachments() {
        for (PendingImage pi : pendingImages) {
            Minecraft.getInstance().getTextureManager().release(pi.texId());
        }
        pendingImages.clear();
    }

    /**
     * GLFW drag-and-drop of files onto the window — the reliable attach path
     * (dispatched to the active screen on the main thread by {@code MouseHandler}).
     * Accepts image files onto the chat tab; other files are ignored.
     */
    @Override
    public void onFilesDrop(List<Path> files) {
        if (tab != Tab.CHAT || uuid == null) { super.onFilesDrop(files); return; }
        boolean any = false;
        for (Path p : files) {
            if (ChatImages.isImageFile(p) && addAttachment(p)) any = true;
        }
        if (!any) flashAttachHint("仅支持 png / jpg 图片");
    }

    /**
     * Best-effort Ctrl+V image paste via AWT (image flavor + file-list flavor).
     * Minecraft sets {@code java.awt.headless=true}, so this typically throws on
     * the dev client and is fully guarded; drag-and-drop remains the supported
     * path. Returns true only if at least one image was actually attached.
     */
    private boolean tryPasteImages() {
        try {
            java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                Object data = cb.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                if (data instanceof List<?> files) {
                    boolean any = false;
                    for (Object f : files) {
                        if (f instanceof java.io.File file
                                && ChatImages.isImageFile(file.toPath())
                                && addAttachment(file.toPath())) any = true;
                    }
                    if (any) return true;
                }
            }
            if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                Object data = cb.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
                if (data instanceof java.awt.Image img) {
                    Path tmp = writeClipboardImage(img);
                    if (tmp != null && addAttachment(tmp)) return true;
                }
            }
        } catch (Throwable t) {
            com.dwinovo.numen.Constants.LOG.debug("[numen-chat] AWT clipboard image paste unavailable: {}",
                    t.toString());
        }
        return false;
    }

    /** Encode a raw clipboard image to a staging PNG (only reachable when AWT works). */
    private Path writeClipboardImage(java.awt.Image img) {
        try {
            java.awt.image.BufferedImage bi;
            if (img instanceof java.awt.image.BufferedImage b) {
                bi = b;
            } else {
                int w = img.getWidth(null), h = img.getHeight(null);
                if (w <= 0 || h <= 0) return null;
                bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D gr = bi.createGraphics();
                gr.drawImage(img, 0, 0, null);
                gr.dispose();
            }
            Path dir = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("numen").resolve("attachments").resolve("_paste");
            java.nio.file.Files.createDirectories(dir);
            Path tmp = dir.resolve("paste-" + System.currentTimeMillis() + ".png");
            javax.imageio.ImageIO.write(bi, "png", tmp.toFile());
            return tmp;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void removed() {
        clearAttachments();   // free GPU thumbnails when the panel closes
        super.removed();
    }

    /**
     * Flatten the convo into render rows, chat-panel v2 three-layer model.
     *
     * <p><b>Top layer — only four kinds of row surface here:</b> the owner's words, Fenn's spoken
     * words, a thinking fold (the SPEAKING turn's own reasoning, glued before its message), and a
     * <em>step-digest</em> row. Everything that happens <em>between two adjacent spoken messages</em>
     * — consecutive tool-call runs, the intermediate ("middle-turn") thinking of tool-only turns, and
     * machine-facing cognition-note user messages (system 见闻) — is aggregated into ONE collapsed
     * digest row per interval (see {@link #emitDigest}).
     *
     * <p><b>The active (trailing) round is exempt:</b> whatever follows the last spoken message has no
     * closing boundary yet, so it is laid out top-level as before (live spinners / live thinking stay
     * visible in real time; {@link #layoutActiveInterval}). Only completed intervals aggregate.
     *
     * <p>Tool RESULT messages aren't drawn — they only mark the matching call done (via {@link
     * #doneIds()}); the call line shows the spinner/check.
     */
    private List<Row> buildRows(int width) {
        List<Row> out = new ArrayList<>();
        Set<String> done = doneIds();
        Set<String> failed = failedIds();
        List<ConvoState.Msg> snap = loop().convo().snapshot();

        // Process accumulated between two adjacent spoken messages, in time order. `group` is the
        // pending run of consecutive tool calls; it's pushed into the interval (closing the run) the
        // moment a thinking / cognition-note element interleaves, so the timeline order is exact.
        List<Proc> interval = new ArrayList<>();
        List<LlmToolCall> group = new ArrayList<>();
        int intervalStart = -1;                     // snapshot index the interval began at → digest fold key

        for (int i = 0; i < snap.size(); i++) {
            switch (snap.get(i)) {
                case ConvoState.Msg.User u -> {
                    // The engine merges buffered cognition events WITH an owner prompt into one
                    // user message at a turn boundary ("<region_snapshot …>…</…>正常通关MC挑战!"),
                    // so a message can be events-only, speech-only, or MIXED. Split first: the
                    // event prefix belongs to the interval's step timeline (system 见闻), and only
                    // the human remainder renders as owner speech — the raw-XML wall the owner
                    // reported came from mixed messages falling through the all-or-nothing gate.
                    String[] parts = splitCognitivePrefix(u.content());
                    String events = parts[0];
                    String speech = parts[1];
                    if (!events.isEmpty()) {
                        if (intervalStart < 0) intervalStart = i;
                        pushGroup(interval, group);              // close the open tool run (keep time order)
                        interval.add(new Proc.Events("events-" + i, events));
                    }
                    if (!speech.isBlank()) {
                        // Owner speaks → a top-level boundary: seal the interval as a digest, then the message.
                        pushGroup(interval, group);
                        emitDigest(out, interval, intervalStart, failed, width);
                        interval = new ArrayList<>(); intervalStart = -1;
                        wrapPlain(out, speech, YOU, width);      // user = teal body, no label
                        int nAtt = u.attachments().size();       // muted "N image(s)" note under the text
                        if (nAtt > 0) {
                            wrapPlain(out, "[" + nAtt + (nAtt == 1 ? " image" : " images") + "]", TXT_MUTED, width);
                        }
                    }
                }
                case ConvoState.Msg.Assistant a -> {
                    AssistantTurn turn = a.turn();
                    String pastThink = loop().reasoningAt(i);
                    boolean thought = pastThink != null && !pastThink.isBlank();
                    boolean spoke = turn.content() != null && !turn.content().isBlank();
                    if (spoke) {
                        // Spoken reply → a top-level boundary. Seal the preceding interval, then this
                        // turn's OWN reasoning stays top-level glued before the reply (a speaking turn's
                        // thinking is NOT middle-turn process), then the bold name header + body.
                        pushGroup(interval, group);
                        emitDigest(out, interval, intervalStart, failed, width);
                        interval = new ArrayList<>(); intervalStart = -1;
                        if (thought) addThinkingRows(out, "think-" + i, pastThink, false, width);
                        addHeader(out, name, AI, width);         // bold name header on its OWN line
                        wrapPlain(out, turn.content(), AI, width);
                    } else if (thought) {
                        // Tool-only turn that still reasoned → middle-turn thinking: into the interval
                        // timeline, at its exact chronological slot (before this turn's tool calls).
                        if (intervalStart < 0) intervalStart = i;
                        pushGroup(interval, group);
                        interval.add(new Proc.Think("think-" + i, pastThink));
                    }
                    // This turn's tool calls open (or extend) the interval that follows this point.
                    if (!turn.toolCalls().isEmpty()) {
                        if (intervalStart < 0) intervalStart = i;
                        group.addAll(turn.toolCalls());
                    }
                }
                case ConvoState.Msg.Tool ignored -> { /* result drives done/fail, not a row */ }
            }
        }
        // Trailing interval = the ACTIVE round (no closing boundary yet) → laid out top-level so the
        // in-progress tool run and thinking stay live; it folds into a digest once the next message lands.
        pushGroup(interval, group);
        layoutActiveInterval(out, interval, done, failed, width);

        // Live "thinking" — the current turn's streaming reasoning transcript, shown as a fold:
        // collapsed (default) = ONE row with a live-updating tail preview, so the row count stays
        // stable while streaming; click to expand the full text. Keyed by the snapshot size so each
        // new turn's fold starts collapsed again. Display-only (never in the convo snapshot).
        String think = loop().liveReasoning();
        if (think != null && !think.isBlank()) {
            addThinkingRows(out, "think-live-" + snap.size(), think, true, width);
        }
        if (loop().isCompacting()) {
            wrapPlain(out, "compacting history…", TXT_MUTED, width);
        }
        if (out.isEmpty()) {
            wrapPlain(out, "Say something to " + name + ".", TXT_FAINT, width);
        }
        return out;
    }

    /** Close the pending tool run into the interval timeline (preserving time order), if non-empty. */
    private static void pushGroup(List<Proc> interval, List<LlmToolCall> group) {
        if (!group.isEmpty()) {
            interval.add(new Proc.Tools(new ArrayList<>(group)));
            group.clear();
        }
    }

    /**
     * Emit ONE collapsed step-digest row for a completed between-messages interval (mid-layer of the
     * three-layer model), or — when the user has clicked it open (fold key {@code "digest-"+start}) —
     * a {@code ▾ N 步} header followed by the interval's step timeline: each tool call as its own
     * {@link #addToolRow} (itself expandable to args + result), each middle-turn thinking as a
     * collapsed {@link #addThinkingRows}, each cognition note as a collapsed {@link #addEventRows}.
     * No-ops on an empty interval (e.g. two adjacent owner messages).
     */
    private void emitDigest(List<Row> out, List<Proc> interval, int start, Set<String> failed, int width) {
        if (interval.isEmpty()) return;
        String key = "digest-" + (start < 0 ? 0 : start);
        int steps = 0;
        boolean hasEvents = false, hasThink = false, anyFail = false;
        List<String> labels = new ArrayList<>();
        for (Proc p : interval) {
            if (p instanceof Proc.Tools t) {
                for (LlmToolCall tc : t.calls()) {
                    steps++;
                    labels.add(ToolLine.label(tc.name(), tc.arguments()));
                    if (failed.contains(tc.id())) anyFail = true;
                }
            } else if (p instanceof Proc.Think) {
                hasThink = true;
            } else if (p instanceof Proc.Events) {
                hasEvents = true;
            }
        }
        if (!expandedGroups.contains(key)) {                      // collapsed: one quiet summary line
            String suffix = ToolLine.digestSuffix(hasEvents, hasThink);
            String body = fitOneLine(ToolLine.digestBody(steps, labels), width - 2 - font.width(suffix));
            int color = anyFail ? FAIL : TOOL;
            out.add(new Row(colored(body + suffix, color).getVisualOrderText(), color, null, key));
        } else {                                                  // expanded: header + step timeline
            String hdr = steps > 0 ? "▾ " + steps + " 步" : "▾ 过程";
            out.add(new Row(colored(hdr, TXT_MUTED).getVisualOrderText(), TXT_MUTED, null, key));
            for (Proc p : interval) {
                if (p instanceof Proc.Tools t) {
                    for (LlmToolCall tc : t.calls()) addToolRow(out, tc, width);
                } else if (p instanceof Proc.Think th) {
                    addThinkingRows(out, th.key(), th.text(), false, width);
                } else if (p instanceof Proc.Events ev) {
                    addEventRows(out, ev.key(), ev.text(), width);
                }
            }
        }
    }

    /**
     * Lay out the ACTIVE (trailing) interval at top level, unchanged from the pre-v2 behaviour: each
     * tool run through {@link #flushTools} (live per-tool spinners while running, auto-folding to a
     * {@code ▸ N 步} summary once done), middle-turn thinking + cognition notes as their own collapsed
     * folds. Intentionally NOT digested — the user must see the round in progress.
     */
    private void layoutActiveInterval(List<Row> out, List<Proc> interval, Set<String> done, Set<String> failed, int width) {
        for (Proc p : interval) {
            if (p instanceof Proc.Tools t) {
                List<LlmToolCall> g = new ArrayList<>(t.calls());
                flushTools(out, g, done, failed, width);
            } else if (p instanceof Proc.Think th) {
                addThinkingRows(out, th.key(), th.text(), false, width);
            } else if (p instanceof Proc.Events ev) {
                addEventRows(out, ev.key(), ev.text(), width);
            }
        }
    }

    /** Emit rows for a run of consecutive tool calls. A single call is always one row. A run of
     *  many stays EXPANDED while any is still running (live per-tool spinners) and AUTO-FOLDS to a muted
     *  "N 步：<narrations>" summary once all are done — unless the user clicked it open (keyed by the first
     *  id in {@link #expandedGroups}), in which case it shows a "▾" header + the per-tool rows. Each per-tool
     *  row shows the model's {@code d} narration (chat-panel style, no raw args). */
    private void flushTools(List<Row> out, List<LlmToolCall> group, Set<String> done, Set<String> failed, int width) {
        if (group.isEmpty()) return;
        if (group.size() == 1) {                                  // single tool — never group-folds (still per-tool expandable)
            addToolRow(out, group.get(0), width);
            group.clear();
            return;
        }
        String key = group.get(0).id();
        boolean running = group.stream().anyMatch(tc -> !done.contains(tc.id()));
        boolean expanded = running || expandedGroups.contains(key);
        if (!expanded) {                                          // folded summary (click to expand) — narrations, not names
            List<String> labels = new ArrayList<>();
            for (LlmToolCall tc : group) labels.add(ToolLine.label(tc.name(), tc.arguments()));
            boolean anyFail = group.stream().anyMatch(tc -> failed.contains(tc.id()));
            String summary = ToolLine.groupSummary(group.size(), labels);
            out.add(new Row(colored(fitOneLine(summary, width - 2), anyFail ? FAIL : TOOL).getVisualOrderText(),
                    anyFail ? FAIL : TOOL, null, key));
        } else {
            if (!running) {                                       // manually expanded → collapsible header
                String hdr = "▾ " + group.size() + " 步";
                out.add(new Row(colored(hdr, TXT_MUTED).getVisualOrderText(), TXT_MUTED, null, key));
            }
            for (LlmToolCall tc : group) addToolRow(out, tc, width);
        }
        group.clear();
    }

    /** One tool call. COLLAPSED (default): status icon + the model's {@code d} narration (or the bare tool
     *  name when it wrote none) — no {@code <>} tags, no args JSON. EXPANDED (click): the same header line
     *  plus faint detail rows with the tool name, the full args JSON, and the result. The per-tool fold key
     *  ({@code "tool-"+id}) never collides with a group key (a raw id) or the event/thinking keys. */
    private void addToolRow(List<Row> out, LlmToolCall tc, int width) {
        String foldKey = "tool-" + tc.id();
        String label = ToolLine.label(tc.name(), tc.arguments());
        FormattedCharSequence seq = colored(fitOneLine(label, width - 2 - 11), TOOL).getVisualOrderText();
        out.add(new Row(seq, TOOL, List.of(tc.id()), foldKey));   // toolIds → status icon; foldKey → click to expand
        if (expandedGroups.contains(foldKey)) {
            wrapPlain(out, tc.name(), TXT_MUTED, width);
            String args = tc.arguments() == null ? "" : tc.arguments().replaceAll("\\s+", " ").trim();
            if (!args.isEmpty()) wrapPlain(out, args, TXT_FAINT, width);
            String result = resultFor(tc.id());
            if (result != null && !result.isBlank()) {
                wrapPlain(out, result.replaceAll("\\s+", " ").trim(), TXT_FAINT, width);
            }
        }
    }

    /** The tool-result content recorded for {@code toolCallId}, or null when none has landed yet. */
    private String resultFor(String toolCallId) {
        for (ConvoState.Msg m : loop().convo().snapshot()) {
            if (m instanceof ConvoState.Msg.Tool t && toolCallId.equals(t.toolCallId())) return t.content();
        }
        return null;
    }

    /** Root tags of machine-facing cognition notes (world-cognition events, corrective
     *  notices, inference ledger) that ride user-role messages. */
    private static final java.util.regex.Pattern EVENT_ROOT = java.util.regex.Pattern.compile(
            "<(event|region_snapshot|region_diff|landmark_event|system_notice|inference|context_snapshot)\\b");

    /** True when a user-role message is machine-facing cognition XML (and only that) —
     *  owner prompts are plain text; mixed messages stay fully visible to be safe. */
    /**
     * Split a user message into {@code [cognition-event prefix, human remainder]}. Complete
     * {@code <tag …>…</tag>} (or self-closing) cognition elements are peeled from the head;
     * whatever follows — typically the owner's own words merged into the same message at a
     * turn boundary — is returned as speech. Either part may be empty. A malformed/unclosed
     * tag stops the peel, leaving the rest as speech (never drops content).
     */
    private static String[] splitCognitivePrefix(String s) {
        if (s == null) return new String[]{"", ""};
        String rest = s;
        StringBuilder events = new StringBuilder();
        while (true) {
            String t = rest.stripLeading();
            var m = EVENT_ROOT.matcher(t);
            if (!m.lookingAt()) break;                    // head is no longer a cognition element
            String tag = m.group(1);
            int selfClose = t.indexOf("/>");
            int open = t.indexOf('>');
            int end;
            if (selfClose >= 0 && selfClose < open) {
                end = selfClose + 2;                      // <tag …/>
            } else {
                String close = "</" + tag + ">";
                int at = t.indexOf(close);
                if (at < 0) break;                        // unclosed — treat the rest as speech
                end = at + close.length();
            }
            events.append(t, 0, end).append('\n');
            rest = t.substring(end);
        }
        return new String[]{events.toString().strip(), rest.strip()};
    }

    private static boolean isCognitiveNote(String s) {
        if (s == null) return false;
        String t = s.strip();
        return t.startsWith("<") && t.endsWith(">") && EVENT_ROOT.matcher(t).find();
    }

    /** Fold a cognition-note message into one faint clickable row ({@code ▸ 认知事件 ×N}),
     *  expandable to the raw XML — same fold mechanism as thinking/tool groups. */
    private void addEventRows(List<Row> out, String key, String text, int width) {
        int n = 0;
        var m = EVENT_ROOT.matcher(text);
        while (m.find()) n++;
        boolean notice = text.contains("<system_notice");
        if (!expandedGroups.contains(key)) {
            String label = "▸ 认知事件 ×" + Math.max(1, n) + (notice ? " · 含系统通知" : "");
            out.add(new Row(colored(fitOneLine(label, width - 2), TXT_FAINT).getVisualOrderText(),
                    TXT_FAINT, null, key));
        } else {
            out.add(new Row(colored("▾ 认知事件", TXT_FAINT).getVisualOrderText(), TXT_FAINT, null, key));
            wrapPlain(out, text, TXT_FAINT, width);
        }
    }

    /** Emit a thinking fold. Collapsed → one clickable summary row: live turns show
     *  {@code ▸ thinking · <tail preview>} (updates in place while streaming), past turns show
     *  {@code ▸ thinking (1.2k chars)}. Expanded → a {@code ▾ thinking} header + the wrapped
     *  TXT_FAINT body. Click toggling rides the same {@code foldKey} + {@link #expandedGroups}
     *  mechanism as tool-call groups ({@link #toggleFoldAt}), just with non-colliding keys. */
    private void addThinkingRows(List<Row> out, String key, String text, boolean live, int width) {
        if (!expandedGroups.contains(key)) {
            String label = live
                    ? tailFit("▸ thinking · ", text, width - 2)
                    : fitOneLine("▸ thinking (" + fmtChars(text.length()) + ")", width - 2);
            out.add(new Row(colored(label, TXT_MUTED).getVisualOrderText(), TXT_MUTED, null, key));
        } else {
            out.add(new Row(colored("▾ thinking", TXT_MUTED).getVisualOrderText(), TXT_MUTED, null, key));
            wrapPlain(out, text, TXT_FAINT, width);
        }
    }

    /** One line of {@code prefix} + the TAIL of {@code text} (its newest part), front-ellipsized to
     *  fit {@code pxWidth}. A rough char pre-cut keeps the per-frame trim loop cheap on long text. */
    private String tailFit(String prefix, String text, int pxWidth) {
        String t = text.replaceAll("\\s+", " ").trim();
        int budget = pxWidth - font.width(prefix);
        int maxChars = Math.max(1, budget / 2);   // narrowest glyphs are ~2px, so this always overshoots
        String tail = t.length() > maxChars ? t.substring(t.length() - maxChars) : t;
        boolean cut = tail.length() < t.length();
        while (tail.length() > 1 && font.width((cut ? "…" : "") + tail) > budget) {
            tail = tail.substring(1);
            cut = true;
        }
        return prefix + (cut ? "…" : "") + tail;
    }

    /** Compact char count for the fold summary: 812 → "812 chars", 1234 → "1.2k chars". */
    private static String fmtChars(int n) {
        return n < 1000 ? n + " chars"
                : String.format(java.util.Locale.ROOT, "%.1fk chars", n / 1000.0);
    }

    /** Trim a string with an ellipsis so it fits one line of the given pixel width. */
    private String fitOneLine(String s, int pxWidth) {
        if (font.width(s) <= pxWidth) return s;
        while (s.length() > 1 && font.width(s + "…") > pxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /** A bold name header on its OWN line (fixed format — never merges into the body). */
    private void addHeader(List<Row> out, String label, int color, int width) {
        var tc = net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF);
        Component c = Component.literal(label).withStyle(s -> s.withColor(tc).withBold(true));
        for (FormattedCharSequence seq : font.split(c, width - 2)) {
            out.add(new Row(seq, color, null, null));
        }
    }

    /** A plain, regular-weight line (status hints) — colour baked into the style. */
    private void wrapPlain(List<Row> out, String text, int color, int width) {
        for (FormattedCharSequence seq : font.split(colored(text, color), width - 2)) {
            out.add(new Row(seq, color, null, null));
        }
    }

    private Set<String> doneIds() {
        Set<String> s = new HashSet<>();
        for (ConvoState.Msg m : loop().convo().snapshot()) {
            if (m instanceof ConvoState.Msg.Tool t) s.add(t.toolCallId());
        }
        return s;
    }

    private Set<String> failedIds() {
        Set<String> s = new HashSet<>();
        for (ConvoState.Msg m : loop().convo().snapshot()) {
            if (m instanceof ConvoState.Msg.Tool t && looksFailed(t.content())) s.add(t.toolCallId());
        }
        return s;
    }

    private static boolean looksFailed(String content) {
        if (content == null) return false;
        String c = content.replaceAll("\\s+", "");
        return c.contains("\"success\":false") || c.startsWith("ERROR") || c.contains("\"error\"");
    }

    /** Right-side PLAN panel: the companion's latest {@code todowrite}, with status glyphs. */
    private void renderPlan(GuiGraphicsExtractor g, int x, int y, int bottom) {
        txt(g, Component.literal("PLAN"), x, y, TXT_MUTED);
        int ly = y + 13;
        JsonArray todos = latestPlan();
        if (todos == null || todos.isEmpty()) {
            txt(g, Component.literal("no plan yet"), x, ly, TXT_FAINT);
            return;
        }
        for (int i = 0; i < todos.size() && ly + LINE_H < bottom; i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            String status = str(it, "status");
            String content = str(it, "content");
            String glyph = switch (status) { case "completed" -> "✔"; case "in_progress" -> "▸"; default -> "○"; };
            int color = switch (status) { case "completed" -> OK; case "in_progress" -> RUN; default -> TXT_FAINT; };
            txt(g, Component.literal(glyph), x, ly, color);
            // text hierarchy: in-progress = strong (current focus), completed = recede, pending = faint
            int textColor = switch (status) {
                case "in_progress" -> TXT;
                case "completed" -> TXT_MUTED;
                default -> TXT_FAINT;
            };
            List<FormattedCharSequence> lines = font.split(colored(content, textColor), PLAN_W - 14);
            int sub = 0;
            for (FormattedCharSequence seq : lines) {
                if (ly + LINE_H >= bottom) break;
                txt(g, seq, x + 10, ly, textColor);
                ly += LINE_H;
                if (++sub >= 2) break;   // cap each item at 2 lines
            }
            if (lines.isEmpty()) ly += LINE_H;
        }
    }

    /** Parse the most recent todowrite call's todos array, or null. */
    private JsonArray latestPlan() {
        JsonArray latest = null;
        for (ConvoState.Msg m : loop().convo().snapshot()) {
            if (m instanceof ConvoState.Msg.Assistant a) {
                for (LlmToolCall tc : a.turn().toolCalls()) {
                    if (!"todowrite".equals(tc.name())) continue;
                    try {
                        JsonObject args = JsonParser.parseString(tc.arguments()).getAsJsonObject();
                        if (args.has("todos") && args.get("todos").isJsonArray()) {
                            latest = args.getAsJsonArray("todos");
                        }
                    } catch (RuntimeException ignored) { /* keep the last good one */ }
                }
            }
        }
        return latest;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /** The Items tab: a vanilla-inventory-style "companion sheet" — armor column + offhand, a live
     *  mouse-following portrait, the synced 2×2 craft grid + result, segmented heart/drumstick vitals,
     *  and the read-only checkerboard 3×9 storage + hotbar. Body data is fetched on demand via
     *  RequestInventoryPayload (backpack + craft + food); HP + equipment come off the live client entity. */
    private void renderItems(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var snap = ClientNumenInventory.get(uuid).orElse(null);
        AbstractClientPlayer e = ClientNumenLookup.resolve(uuid);
        List<ItemStack> craft = snap != null ? snap.craft() : List.of();

        // Two centred columns: LEFT = big portrait + armor column + offhand; RIGHT = craft + vitals +
        // 3×9 storage + hotbar. Symmetric framing margins (no lopsided whitespace).
        final int STORAGE_W = 9 * 18;                     // 162 — the widest element (caps the band)
        final int COMP_W = 130 + STORAGE_W;               // left col (130) + right col (storage)
        final int COMP_H = 152;
        int startX = left + (PANEL_W - COMP_W) / 2;
        int cTop = top + HEADER_H + (PANEL_H - HEADER_H - COMP_H) / 2;
        int rightX = startX + 130;

        // -- LEFT: portrait socket, armor column + offhand (vertically centred against the portrait) --
        renderPortrait(g, e, startX + 22, cTop, 84, COMP_H, mouseX, mouseY);
        int armorTop = cTop + (COMP_H - 5 * 18) / 2;
        for (int i = 0; i < ARMOR.length; i++) {
            drawEquip(g, e, ARMOR[i], startX, armorTop + i * 18, mouseX, mouseY);
        }
        drawEquip(g, e, EquipmentSlot.OFFHAND, startX, armorTop + 4 * 18, mouseX, mouseY);

        // -- RIGHT top: synced 2×2 craft grid (+ arrow + result) --
        for (int i = 0; i < 4; i++) {
            int cx = rightX + (i % 2) * 18, cy = cTop + (i / 2) * 18;
            slotBg(g, SLOT_SPRITE, cx, cy);
            stackOn(g, i < craft.size() ? craft.get(i) : ItemStack.EMPTY, cx, cy, mouseX, mouseY);
        }
        txt(g, Component.literal("→"), rightX + 38, cTop + 13, TXT_MUTED);
        int resultX = rightX + 54, resultY = cTop + 9;
        slotBg(g, SLOT_SPRITE, resultX, resultY);
        stackOn(g, craft.size() > 4 ? craft.get(4) : ItemStack.EMPTY, resultX, resultY, mouseX, mouseY);

        // -- RIGHT mid: segmented hearts + drumsticks --
        if (e != null) renderStatRow(g, rightX, cTop + 46, e.getHealth(), e.getMaxHealth(),
                HEART_FULL, HEART_HALF, HEART_EMPTY);
        int food = (snap != null && snap.loaded()) ? snap.foodLevel() : 0;
        renderStatRow(g, rightX, cTop + 46 + ICON + 2, food, 20, FOOD_FULL, FOOD_HALF, FOOD_EMPTY);

        // -- RIGHT bottom: checkerboard 3×9 storage + hotbar --
        int storeY = cTop + 74;
        if (snap == null) {
            txt(g, Component.literal("loading…"), rightX, storeY + 4, TXT_FAINT);
            return;
        }
        if (!snap.loaded() || snap.items().isEmpty()) {
            txt(g, Component.literal("asleep — chat to wake it."), rightX, storeY + 4, TXT_FAINT);
            return;
        }
        List<ItemStack> items = snap.items();
        for (int i = 9; i < 36; i++) {                     // storage rows (slots 9..35)
            int col = (i - 9) % 9, row = (i - 9) / 9;
            int x = rightX + col * 18, y = storeY + row * 18;
            slotBg(g, ((col + row) & 1) == 0 ? SLOT_SPRITE : SLOT_ALT, x, y);
            stackOn(g, items.get(i), x, y, mouseX, mouseY);
        }
        int hotbarY = storeY + 3 * 18 + 6;                 // hotbar (slots 0..8)
        for (int i = 0; i < 9; i++) {
            int x = rightX + i * 18;
            slotBg(g, (i & 1) == 0 ? SLOT_SPRITE : SLOT_ALT, x, hotbarY);
            stackOn(g, items.get(i), x, hotbarY, mouseX, mouseY);
        }
    }

    private static net.minecraft.resources.Identifier spr(String name) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(com.dwinovo.numen.Constants.MOD_ID, name);
    }
    private static final net.minecraft.resources.Identifier SLOT_SPRITE = spr("slot");
    private static final net.minecraft.resources.Identifier SLOT_ALT = spr("slot_alt");        // checkerboard
    /** Parchment frame (reuses the button sprite) behind text fields. */
    private static final net.minecraft.resources.Identifier FIELD_SPRITE = spr("button");
    private static final net.minecraft.resources.Identifier HEART_FULL = spr("heart_full");
    private static final net.minecraft.resources.Identifier HEART_HALF = spr("heart_half");
    private static final net.minecraft.resources.Identifier HEART_EMPTY = spr("heart_empty");
    private static final net.minecraft.resources.Identifier FOOD_FULL = spr("food_full");
    private static final net.minecraft.resources.Identifier FOOD_HALF = spr("food_half");
    private static final net.minecraft.resources.Identifier FOOD_EMPTY = spr("food_empty");
    private static final net.minecraft.resources.Identifier SCROLL_TRACK = spr("scroll_track");
    private static final net.minecraft.resources.Identifier SCROLL_THUMB = spr("scroll_thumb");

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A rendered transcript line. {@code toolIds} non-null = a tool row (status icon = spinner/✔/✗).
     *  {@code foldKey} non-null = a clickable fold toggle (the group's first id); both null = plain text. */
    private record Row(FormattedCharSequence text, int color, List<String> toolIds, String foldKey) {}

    /**
     * One element on a between-messages interval's step timeline, in chronological order — the raw
     * material a {@linkplain #emitDigest step-digest} aggregates (collapsed) or replays (expanded).
     * {@code Tools} is a run of consecutive tool calls; {@code Think} is a middle-turn reasoning fold;
     * {@code Events} is a machine-facing cognition-note (system 见闻). {@code key} carries the fold key
     * each nested element keeps when the digest is expanded (non-colliding: {@code think-}/{@code events-}).
     */
    private sealed interface Proc {
        record Tools(List<LlmToolCall> calls) implements Proc {}
        record Think(String key, String text) implements Proc {}
        record Events(String key, String text) implements Proc {}
    }

    /** A composed-but-unsent image: the source file (copied into the attachments dir on send)
     *  plus its live thumbnail texture and on-screen size. */
    private record PendingImage(Path source, net.minecraft.resources.Identifier texId, int w, int h) {}
}
