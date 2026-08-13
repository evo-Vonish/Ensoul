package com.dwinovo.numen.mcp.coordinator;

import com.dwinovo.numen.api.NumenActuator;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.mcp.McpConfig;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The cluster tool surface: {@code broadcast}, {@code whisper}, and the
 * {@code trade_*} family. These are MCP-level tools — siblings of
 * {@code list_companions}, not engine tools — because messaging and trade
 * bookkeeping are cluster business, not body business. The one physical act
 * (handing items over) is choreographed out of existing engine tools
 * ({@code drop_items} → {@code collect_items}) through {@link NumenActuator},
 * so everything a trade does stays survival-legal.
 *
 * <p>Replies use the engine's {@link TaskResult} JSON envelope, so an external
 * brain reads cluster results exactly like body-tool results.
 */
public final class CoordinatorTools {

    /** Resolve the caller's {@code companion} argument to a live companion uuid (McpServer's resolver). */
    public interface CompanionResolver {
        UUID resolve(JsonObject args) throws Exception;
    }

    /** One handled call: result text for the wire, error flag, whose inbox to drain onto this reply. */
    public record Reply(String text, boolean error, UUID inboxOf) {
        static Reply ok(String json, UUID inboxOf) { return new Reply(json, false, inboxOf); }
        static Reply err(String json, UUID inboxOf) { return new Reply(json, true, inboxOf); }
    }

    private static final int CONTROL_TIMEOUT_SECONDS = 10;
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final int COLLECT_RADIUS = 6;
    /** Slack on the locked-trade distance check so breathing room doesn't void a hand-off. */
    private static final double GIVE_DISTANCE_SLACK = 1.5;

    private final Coordinator coordinator;
    private final McpConfig config;

    public CoordinatorTools(Coordinator coordinator, McpConfig config) {
        this.coordinator = coordinator;
        this.config = config;
    }

    // ---- tools/list ----

    public List<JsonObject> defs() {
        List<JsonObject> out = new ArrayList<>();
        out.add(def("broadcast",
                "Say something to EVERY other agent in the cluster (every companion currently driven through "
                        + "this server). Arrives in their inbox — appended to the tail of a future tool result, "
                        + "never interrupting them. Use for market calls, surplus announcements, warnings. "
                        + "Not for one-to-one talk (use whisper).",
                schema(props(
                        prop("message", str("What to announce. One thought, ≤" + MAX_MESSAGE_CHARS + " chars."))),
                        req("message"))));
        out.add(def("whisper",
                "Private message to ONE other agent, by companion name or id (see list_companions). Arrives "
                        + "in their inbox only. Use for price talks, coordinates, side deals — anything the "
                        + "whole cluster shouldn't hear.",
                schema(props(
                        prop("to", str("Recipient's companion name or id.")),
                        prop("message", str("What to say privately, ≤" + MAX_MESSAGE_CHARS + " chars."))),
                        req("to", "message"))));
        out.add(def("trade_invite",
                "Propose a locked, face-to-face trade to another agent within " + fmt(config.tradeDistance())
                        + " blocks of you. They receive the invitation in their inbox and answer with "
                        + "trade_accept / trade_decline and the returned session id. Stand next to them FIRST "
                        + "(move_to) — distance is checked. One live session per companion; invitations "
                        + "expire after " + config.inviteTimeoutSeconds() + "s.",
                schema(props(
                        prop("to", str("Who to trade with (companion name or id).")),
                        prop("note", nullableStr("Optional one-liner — what you offer / want, e.g. 'my 3 bread for your 1 iron'."))),
                        req("to"))));
        out.add(def("trade_accept",
                "Accept a pending trade invitation (you were the target). Distance is re-checked — be "
                        + "standing next to the inviter. Both sides become LOCKED and can then trade_give.",
                schema(props(
                        prop("session", str("Session id from the invite, e.g. 't1'."))),
                        req("session"))));
        out.add(def("trade_decline",
                "Decline a pending trade invitation. The inviter is notified.",
                schema(props(
                        prop("session", str("Session id from the invite."))),
                        req("session"))));
        out.add(def("trade_give",
                "Hand items to the other party of a LOCKED trade session: you drop them in front of you, they "
                        + "auto-pick-up — both legs run for you, and the other agent is notified in their inbox. "
                        + "Repeatable while the session is open. count above what you carry gives everything "
                        + "you have of it. Distance is re-checked (small slack).",
                schema(props(
                        prop("session", str("Locked session id.")),
                        prop("item_id", str("Namespaced item id, e.g. minecraft:iron_ingot.")),
                        prop("count", integer("How many to hand over (1-999).", 1, 999))),
                        req("session", "item_id", "count"))));
        out.add(def("trade_close",
                "End a trade session you are part of (invited or locked). The other party is notified. Close "
                        + "when done — one live session per companion, so an open one blocks the next trade.",
                schema(props(
                        prop("session", str("Session id to close."))),
                        req("session"))));
        return out;
    }

    // ---- tools/call ----

    public Reply call(String name, JsonObject args, CompanionResolver resolver) {
        UUID caller;
        try {
            caller = resolver.resolve(args);
        } catch (Exception ex) {
            return Reply.err(TaskResult.fail("could not resolve companion: " + ex.getMessage()).toJson(), null);
        }
        if (caller == null) {
            return Reply.err(TaskResult.fail("this tool needs a 'companion' argument naming one of YOUR "
                    + "acquired companions (see list_companions)").toJson(), null);
        }
        if (!coordinator.isParticipant(caller)) {
            return Reply.err(TaskResult.fail("your companion is not in the cluster — acquire_companion first")
                    .toJson(), caller);
        }
        try {
            return switch (name) {
                case "broadcast" -> doBroadcast(caller, args);
                case "whisper" -> doWhisper(caller, args);
                case "trade_invite" -> doInvite(caller, args);
                case "trade_accept" -> doAccept(caller, args);
                case "trade_decline" -> doDecline(caller, args);
                case "trade_give" -> doGive(caller, args);
                case "trade_close" -> doClose(caller, args);
                default -> Reply.err(TaskResult.fail("unknown cluster tool: " + name).toJson(), caller);
            };
        } catch (TimeoutException te) {
            return Reply.err(TaskResult.timeout("timed out waiting for the game to answer").toJson(), caller);
        } catch (Exception ex) {
            return Reply.err(TaskResult.fail("cluster call failed: " + ex.getMessage()).toJson(), caller);
        }
    }

    // ---- messaging handlers ----

    private Reply doBroadcast(UUID caller, JsonObject args) {
        String message = message(args);
        if (message == null) return Reply.err(TaskResult.fail("broadcast needs a non-empty 'message' "
                + "(≤" + MAX_MESSAGE_CHARS + " chars)").toJson(), caller);
        int reached = coordinator.broadcast(caller, message);
        Map<String, Object> data = Map.of("recipients", reached);
        String msg = reached == 0
                ? "broadcast sent, but no other agent is in the cluster yet"
                : "broadcast delivered to " + reached + " agent(s) — it lands in their next tool result";
        return Reply.ok(TaskResult.ok(msg, data).toJson(), caller);
    }

    private Reply doWhisper(UUID caller, JsonObject args) {
        UUID to = coordinator.resolveParticipant(str(args, "to"));
        if (to == null) {
            return Reply.err(TaskResult.fail("no such cluster participant — check list_companions for who "
                    + "is actually in the game (and wait for them to acquire their companion)").toJson(), caller);
        }
        if (to.equals(caller)) {
            return Reply.err(TaskResult.fail("that's you — whisper goes to OTHER agents").toJson(), caller);
        }
        String message = message(args);
        if (message == null) return Reply.err(TaskResult.fail("whisper needs a non-empty 'message' "
                + "(≤" + MAX_MESSAGE_CHARS + " chars)").toJson(), caller);
        coordinator.whisper(caller, to, message);
        return Reply.ok(TaskResult.ok("whisper delivered to " + coordinator.nameOf(to)
                + " — it lands in their next tool result").toJson(), caller);
    }

    // ---- trade handlers ----

    private Reply doInvite(UUID caller, JsonObject args) throws Exception {
        UUID to = coordinator.resolveParticipant(str(args, "to"));
        if (to == null) {
            return Reply.err(TaskResult.fail("no such cluster participant — they must be in the game and "
                    + "acquired by their brain").toJson(), caller);
        }
        if (to.equals(caller)) {
            return Reply.err(TaskResult.fail("you cannot trade with yourself").toJson(), caller);
        }
        Reply far = checkDistance(caller, to, config.tradeDistance(), caller,
                "trade invite needs you two within " + fmt(config.tradeDistance()) + " blocks — move closer first");
        if (far != null) return far;
        String note = str(args, "note");
        String err = coordinator.openInvite(caller, to, note, config.inviteTimeoutSeconds());
        if (err != null) return Reply.err(TaskResult.fail(err).toJson(), caller);
        TradeSession s = coordinator.activeSessionOf(caller, config.inviteTimeoutSeconds());
        coordinator.notifyTrade(to, coordinator.nameOf(caller), "invite",
                s.id() + (note == null || note.isBlank() ? "" : " — " + note)
                        + " — answer with trade_accept / trade_decline, session \"" + s.id() + "\"");
        return Reply.ok(TaskResult.ok("invitation sent to " + coordinator.nameOf(to) + " (session " + s.id()
                        + ") — it expires in " + config.inviteTimeoutSeconds() + "s",
                Map.of("session", s.id())).toJson(), caller);
    }

    private Reply doAccept(UUID caller, JsonObject args) throws Exception {
        TradeSession s = sessionOrErr(args);
        if (s == null) return Reply.err(TaskResult.fail("no such live session — check the id from the invite")
                .toJson(), caller);
        if (!s.target().equals(caller)) {
            return Reply.err(TaskResult.fail("only the invited party can accept session " + s.id()).toJson(), caller);
        }
        if (s.state() != TradeSession.State.INVITED) {
            return Reply.err(TaskResult.fail("session " + s.id() + " is " + s.state() + ", not awaiting an answer")
                    .toJson(), caller);
        }
        Reply far = checkDistance(caller, s.initiator(), config.tradeDistance(), caller,
                "too far to lock the trade — get within " + fmt(config.tradeDistance()) + " blocks of the inviter");
        if (far != null) return far;
        s.setState(TradeSession.State.LOCKED);
        coordinator.notifyTrade(s.initiator(), coordinator.nameOf(caller), "accepted",
                s.id() + " — trade is LOCKED; use trade_give to hand items over, trade_close when done");
        return Reply.ok(TaskResult.ok("trade " + s.id() + " LOCKED with " + coordinator.nameOf(s.initiator())
                + " — trade_give to hand items, trade_close when done").toJson(), caller);
    }

    private Reply doDecline(UUID caller, JsonObject args) {
        TradeSession s = sessionOrErr(args);
        if (s == null) return Reply.err(TaskResult.fail("no such live session — check the id").toJson(), caller);
        if (!s.target().equals(caller)) {
            return Reply.err(TaskResult.fail("only the invited party can decline session " + s.id()).toJson(), caller);
        }
        s.setState(TradeSession.State.DECLINED);
        coordinator.notifyTrade(s.initiator(), coordinator.nameOf(caller), "declined", s.id());
        return Reply.ok(TaskResult.ok("declined session " + s.id() + " — the inviter has been notified").toJson(), caller);
    }

    private Reply doGive(UUID caller, JsonObject args) throws Exception {
        TradeSession s = sessionOrErr(args);
        if (s == null) return Reply.err(TaskResult.fail("no such live session — check the id").toJson(), caller);
        if (!s.isParty(caller)) {
            return Reply.err(TaskResult.fail("you are not a party of session " + s.id()).toJson(), caller);
        }
        if (s.state() != TradeSession.State.LOCKED) {
            return Reply.err(TaskResult.fail("session " + s.id() + " is " + s.state()
                    + " — trade_give needs LOCKED (invitee must trade_accept first)").toJson(), caller);
        }
        String itemId = str(args, "item_id");
        if (itemId == null || itemId.isBlank()) {
            return Reply.err(TaskResult.fail("trade_give needs an 'item_id' (namespaced, e.g. minecraft:iron_ingot)")
                    .toJson(), caller);
        }
        int count = args.has("count") && !args.get("count").isJsonNull() ? args.get("count").getAsInt() : 0;
        if (count < 1 || count > 999) {
            return Reply.err(TaskResult.fail("'count' must be 1-999").toJson(), caller);
        }
        UUID receiver = s.other(caller);
        Reply far = checkDistance(caller, receiver, config.tradeDistance() + GIVE_DISTANCE_SLACK, caller,
                "you two drifted apart — get back within ~" + fmt(config.tradeDistance()) + " blocks to hand items over");
        if (far != null) return far;

        // Leg 1 — the caller drops the items in front of them.
        String dropJson = NumenActuator.invoke(caller, "drop_items",
                        "{\"item_id\":\"" + itemId + "\",\"count\":" + count + "}")
                .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
        if (!succeeded(dropJson)) {
            return Reply.err(TaskResult.fail("hand-off failed at the drop leg — " + gist(dropJson),
                    Map.of("session", s.id(), "drop_result", gist(dropJson))).toJson(), caller);
        }
        // Leg 2 — the receiver picks them up.
        String collectJson = NumenActuator.invoke(receiver, "collect_items",
                        "{\"item_ids\":[\"" + itemId + "\"],\"radius\":" + COLLECT_RADIUS + "}")
                .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
        boolean ok = succeeded(collectJson);
        String summary = ok
                ? "handed " + count + "x " + itemId + " to " + coordinator.nameOf(receiver)
                : "dropped " + count + "x " + itemId + " but pickup is unconfirmed — " + gist(collectJson);
        Map<String, Object> data = new HashMap<>();
        data.put("session", s.id());
        data.put("item_id", itemId);
        data.put("count", count);
        data.put("drop_result", gist(dropJson));
        data.put("collect_result", gist(collectJson));
        coordinator.notifyTrade(receiver, coordinator.nameOf(caller), "give",
                s.id() + ": " + count + "x " + itemId + " — " + (ok ? "now in your inventory" : gist(collectJson)));
        TaskResult tr = ok ? TaskResult.ok(summary, data) : TaskResult.fail(summary, data);
        return new Reply(tr.toJson(), !ok, caller);
    }

    private Reply doClose(UUID caller, JsonObject args) {
        TradeSession s = sessionOrErr(args);
        if (s == null) return Reply.err(TaskResult.fail("no such live session — check the id").toJson(), caller);
        if (!s.isParty(caller)) {
            return Reply.err(TaskResult.fail("you are not a party of session " + s.id()).toJson(), caller);
        }
        s.setState(TradeSession.State.CLOSED);
        UUID partner = s.other(caller);
        if (partner != null) {
            coordinator.notifyTrade(partner, coordinator.nameOf(caller), "close", s.id() + " closed");
        }
        return Reply.ok(TaskResult.ok("session " + s.id() + " closed").toJson(), caller);
    }

    // ---- helpers ----

    /** Live session named by args.session, with lazy expiry applied; null if none. */
    private TradeSession sessionOrErr(JsonObject args) {
        String id = str(args, "session");
        if (id == null || id.isBlank()) return null;
        TradeSession s = coordinator.session(id.trim(), config.inviteTimeoutSeconds());
        if (s == null || s.state() == TradeSession.State.EXPIRED
                || s.state() == TradeSession.State.CLOSED || s.state() == TradeSession.State.DECLINED) {
            return null;
        }
        return s;
    }

    /** @return an error Reply if the two are out of range, else null (in range). */
    private Reply checkDistance(UUID a, UUID b, double maxBlocks, UUID inboxOf, String hint) throws Exception {
        double d = distanceBlocks(a, b);
        if (d == -1.0) {
            return Reply.err(TaskResult.fail("one of the two companions isn't loaded on this client "
                    + "(out of sight or offline)").toJson(), inboxOf);
        }
        if (d == -2.0) {
            return Reply.err(TaskResult.fail("you two are in different dimensions").toJson(), inboxOf);
        }
        if (d > maxBlocks) {
            return Reply.err(TaskResult.fail("too far apart: " + fmt(d) + " blocks (limit " + fmt(maxBlocks)
                    + "). " + hint, Map.of("distance", d)).toJson(), inboxOf);
        }
        return null;
    }

    /**
     * Client-thread distance between two companions' bodies. -1 = a body isn't
     * loaded; -2 = different dimensions. Companions materialise as client player
     * entities, so their positions are just entity positions.
     */
    private static double distanceBlocks(UUID a, UUID b) throws Exception {
        CompletableFuture<Double> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            AbstractClientPlayer pa = ClientNumenLookup.resolve(a);
            AbstractClientPlayer pb = ClientNumenLookup.resolve(b);
            if (pa == null || pb == null) { f.complete(-1.0); return; }
            if (!pa.level().dimension().equals(pb.level().dimension())) { f.complete(-2.0); return; }
            f.complete(pa.position().distanceTo(pb.position()));
        });
        return f.get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static boolean succeeded(String taskResultJson) {
        try {
            JsonObject r = JsonParser.parseString(taskResultJson).getAsJsonObject();
            return r.has("success") && r.get("success").getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** The human-readable line out of a TaskResult JSON (whole string as fallback), for composing replies. */
    private static String gist(String taskResultJson) {
        try {
            JsonObject r = JsonParser.parseString(taskResultJson).getAsJsonObject();
            if (r.has("message")) return r.get("message").getAsString();
        } catch (RuntimeException ignored) {}
        return taskResultJson.length() <= 160 ? taskResultJson : taskResultJson.substring(0, 160) + "…";
    }

    private static String message(JsonObject args) {
        String m = str(args, "message");
        if (m == null || m.isBlank() || m.length() > MAX_MESSAGE_CHARS) return null;
        return m.trim();
    }

    private static String str(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : null;
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }

    // ---- schema plumbing (MCP toolDef shape: name / description / inputSchema) ----

    private static JsonObject def(String name, String description, JsonObject inputSchema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", inputSchema);
        return t;
    }

    private record Prop(String key, JsonObject schema) {}
    private static Prop prop(String key, JsonObject schema) { return new Prop(key, schema); }

    private static JsonObject schema(Prop[] props, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject p = new JsonObject();
        JsonObject companion = new JsonObject();
        companion.addProperty("type", "string");
        companion.addProperty("description", "YOUR companion — the one you acquired. You speak and trade as it.");
        p.add("companion", companion);
        for (Prop prop : props) p.add(prop.key(), prop.schema());
        schema.add("properties", p);
        JsonArray req = new JsonArray();
        req.add("companion");
        for (String r : required) req.add(r);
        schema.add("required", req);
        return schema;
    }

    private static Prop[] props(Prop... props) { return props; }
    private static String[] req(String... keys) { return keys; }

    private static JsonObject str(String description) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "string");
        s.addProperty("description", description);
        return s;
    }

    private static JsonObject nullableStr(String description) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "string");
        s.addProperty("description", description + " (optional — omit if none)");
        return s;
    }

    private static JsonObject integer(String description, int min, int max) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "integer");
        s.addProperty("description", description);
        s.addProperty("minimum", min);
        s.addProperty("maximum", max);
        return s;
    }
}
