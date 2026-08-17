package com.dwinovo.numen.mcp.transport;

import com.dwinovo.numen.mcp.Constants;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One session's server-to-client SSE stream, opened by {@code GET /mcp}.
 *
 * <h2>Optimisation, never the contract</h2>
 * Everything written here is ALSO retrievable by cursor from the journal. That is deliberate and
 * load-bearing: today's desktop-agent harnesses do not reliably inject mid-turn notifications
 * into the model's context, so a design that depended on push would depend on a feature we do not
 * control. SSE buys latency for clients that can use it; {@code await_events} remains the
 * contract. Never one or the other.
 *
 * <h2>A slow reader must never stall the game</h2>
 * Writes happen off the client main thread. If a client stops reading, its socket buffer fills
 * and {@link #send} throws; we mark the sink {@link #isBroken() broken} and stop trying rather
 * than blocking. The session survives — it simply falls back to cursor reads, which lose nothing
 * because the journal already holds the events. Back-pressuring the tick to feed a stalled HTTP
 * client would be the worst possible trade.
 */
public final class SseSink {

    private static final Gson GSON = new Gson();

    private final OutputStream out;
    private volatile boolean broken = false;

    public SseSink(OutputStream out) {
        this.out = out;
    }

    /**
     * Write one JSON-RPC notification as an SSE {@code data:} frame.
     *
     * <p>Synchronised because the journal pump and a keep-alive tick can both reach a sink, and a
     * half-written frame would desynchronise the client's parser permanently. The critical
     * section is one socket write; it never touches game state.
     */
    public synchronized void send(JsonObject notification) {
        if (broken) return;
        try {
            // Single-line JSON: SSE splits on newlines, and a pretty-printed payload would be
            // parsed as several truncated frames.
            String payload = GSON.toJson(notification);
            out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException disconnected) {
            broken = true;
            Constants.LOG.debug("[numen-mcp] SSE sink closed by peer: {}", disconnected.toString());
        }
    }

    /** SSE comment frame — keeps intermediaries from reaping an idle stream. */
    public synchronized void keepAlive() {
        if (broken) return;
        try {
            out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException disconnected) {
            broken = true;
        }
    }

    /** True once the peer has gone. The owning session drops the sink and reverts to cursor reads. */
    public boolean isBroken() {
        return broken;
    }

    public synchronized void close() {
        broken = true;
        try {
            out.close();
        } catch (IOException ignored) {
            // Closing a socket the peer already dropped is routine, not news.
        }
    }
}
