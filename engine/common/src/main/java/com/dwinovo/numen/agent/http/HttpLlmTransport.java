package com.dwinovo.numen.agent.http;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * HTTPS transport for OpenAI-protocol chat completions. Built on the JDK
 * {@code java.net.http.HttpClient} (Java 11+, daemon-threaded executor) so
 * we ship zero third-party HTTP dependencies.
 *
 * <h2>Two modes</h2>
 * <ul>
 *   <li>{@link #post}    — buffered: response JSON returned whole.
 *       Used historically; useful for non-streaming backends or debug.</li>
 *   <li>{@link #postSse} — streamed: caller passes a chunk-handler invoked
 *       once per parsed SSE event. Backend must respond with
 *       {@code text/event-stream}.</li>
 * </ul>
 *
 * <h2>Request id tagging</h2>
 * Every call gets a short sequential id ({@code lr-N}) logged on send, on
 * response status, and on each streamed chunk — makes it possible to follow
 * one specific request through the log when the agent loop has interleaved
 * activity from multiple Numenes or back-to-back retries.
 *
 * <h2>Error model</h2>
 * <ul>
 *   <li>2xx + streaming → caller's chunkHandler invoked, future completes
 *       when stream terminates (graceful {@code [DONE]} or stream close)</li>
 *   <li>non-2xx → future fails with {@link LlmHttpException} carrying the
 *       status code and full response body</li>
 *   <li>network / DNS / timeout → future fails with the wrapped IOException</li>
 * </ul>
 *
 * <h2>Transient-failure policy</h2>
 * A single upstream hiccup used to abort the companion's whole turn. Both call
 * shapes now retry {@value #MAX_ATTEMPTS} times total on 429 / 5xx / IOException,
 * with exponential backoff plus jitter, honouring a server {@code Retry-After}
 * header when one is present. Permanent 4xx (bad key, bad model id, malformed
 * body) are never retried — the server would reject them identically forever.
 *
 * <p><strong>Streaming retries are guarded:</strong> once any chunk has reached the
 * caller's handler the request is no longer replayable (the partial turn is already
 * out), so retries stop there and the failure propagates.
 */
public final class HttpLlmTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Whole-exchange deadline — <strong>buffered calls only</strong> ({@link #post} /
     * {@link #get}). They produce no incremental progress, so a flat deadline is the
     * only liveness signal available.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Streaming calls deliberately get <strong>no</strong> whole-exchange deadline.
     * {@code HttpRequest.timeout()} bounds the entire response body, not just the
     * connect/header phase, so a 120s cap silently guillotined every reasoning model
     * that thinks longer than that (DeepSeek V4, GLM, kimi-k2-thinking routinely do)
     * — mid-stream, after the user already paid for the tokens.
     *
     * <p>Liveness is enforced by an <em>idle</em> watchdog instead: the stream is killed
     * only after this long with no traffic at all. Any line counts as activity — data
     * lines and {@code :} keep-alive comments alike — so a model that is thinking but
     * whose gateway is still breathing is never cut off.
     */
    private static final Duration SSE_IDLE_TIMEOUT = Duration.ofSeconds(90);

    /** How often the watchdog samples idleness. Cheap: one daemon thread for the whole game. */
    private static final long WATCHDOG_PERIOD_MS = 5_000L;

    /** Total attempts (1 initial + 2 retries) for transient failures — 429, 5xx, network blips. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 1_000L;
    private static final long RETRY_MAX_DELAY_MS = 20_000L;

    private static final AtomicInteger REQUEST_ID_SOURCE = new AtomicInteger();

    /** Watchdog ticks + retry delays. Daemon so it never holds the game open on quit. */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "numen-http-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final HttpClient client;
    private final java.util.Map<String, String> extraHeaders;

    public HttpLlmTransport(String proxy, java.util.Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders == null ? java.util.Map.of() : extraHeaders;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        java.net.ProxySelector ps = proxySelector(proxy);
        if (ps != null) b.proxy(ps);
        this.client = b.build();
    }

    /** Parse a {@code host:port} (scheme optional) proxy into a selector, or null if blank/invalid. */
    private static java.net.ProxySelector proxySelector(String proxy) {
        if (proxy == null || proxy.isBlank()) return null;
        try {
            String s = proxy.trim();
            int scheme = s.indexOf("://");
            if (scheme >= 0) s = s.substring(scheme + 3);
            s = s.replaceAll("/.*$", "");
            int colon = s.lastIndexOf(':');
            if (colon < 0) return null;
            String host = s.substring(0, colon);
            int port = Integer.parseInt(s.substring(colon + 1));
            Constants.LOG.info("[numen-http] routing LLM calls through proxy {}:{}", host, port);
            return java.net.ProxySelector.of(new java.net.InetSocketAddress(host, port));
        } catch (Exception e) {
            Constants.LOG.warn("[numen-http] invalid proxy '{}' (expected host:port) — going direct", proxy);
            return null;
        }
    }

    /**
     * POST a JSON body, get the whole JSON response back. Used for
     * non-streaming requests.
     */
    public CompletableFuture<JsonObject> post(String url, String apiKey, JsonObject body) {
        String requestId = nextRequestId();
        String bodyStr = body.toString();
        Constants.LOG.debug("[numen-http][{}] POST {} ({} bytes, buffered)",
                requestId, url, bodyStr.length());

        HttpRequest request = baseRequest(url, apiKey, "application/json", bodyStr);
        // Buffered: nothing has been handed to a caller mid-flight, so every attempt is replayable.
        return retrying(requestId, 1, () -> {
            long t0 = System.nanoTime();
            return client.sendAsync(request, BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenCompose(resp -> interpretBuffered(requestId, t0, resp));
        }, () -> true);
    }

    /**
     * GET a JSON resource with the same bearer auth + per-site headers as chat calls —
     * used for provider account-balance endpoints. Buffered (no SSE); resolves with the
     * parsed body or fails with {@link LlmHttpException} on non-2xx.
     */
    public CompletableFuture<JsonObject> get(String url, String apiKey) {
        String requestId = nextRequestId();
        Constants.LOG.debug("[numen-http][{}] GET {}", requestId, url);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT);
        extraHeaders.forEach(b::header);
        HttpRequest request = b.header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        return retrying(requestId, 1, () -> {
            long t0 = System.nanoTime();
            return client.sendAsync(request, BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenCompose(resp -> interpretBuffered(requestId, t0, resp));
        }, () -> true);
    }

    /**
     * POST a JSON body, stream the response as SSE events into the
     * {@code chunkHandler}. The returned future completes when the stream
     * terminates normally; it fails with {@link LlmHttpException} if the
     * server replied non-2xx (in which case the chunk handler is never
     * invoked).
     */
    public CompletableFuture<Void> postSse(String url, String apiKey, JsonObject body,
                                            Consumer<JsonObject> chunkHandler) {
        return postSse(nextRequestId(), url, apiKey, body, chunkHandler);
    }

    /**
     * Same as {@link #postSse(String, String, JsonObject, Consumer)} but with a caller-reserved
     * request id (obtained from {@link #nextRequestId()}), so the caller can emit its own log
     * lines correlated with this request BEFORE dispatch (e.g. the per-request reasoning-effort
     * line in {@code NumenLlmClient}).
     */
    public CompletableFuture<Void> postSse(String requestId, String url, String apiKey, JsonObject body,
                                            Consumer<JsonObject> chunkHandler) {
        String bodyStr = body.toString();
        AtomicLong chunkCount = new AtomicLong();
        Constants.LOG.debug("[numen-http][{}] POST {} ({} bytes, streaming)",
                requestId, url, bodyStr.length());

        // Retry gate: once a chunk has reached the caller's handler the turn is already
        // partially emitted downstream, so replaying the request would double-deliver it.
        return retrying(requestId, 1,
                () -> sseAttempt(requestId, url, apiKey, bodyStr, chunkHandler, chunkCount),
                () -> chunkCount.get() == 0);
    }

    /** One streaming attempt: no whole-exchange deadline, an idle watchdog instead. */
    private CompletableFuture<Void> sseAttempt(String requestId, String url, String apiKey, String bodyStr,
                                               Consumer<JsonObject> chunkHandler, AtomicLong chunkCount) {
        long t0 = System.nanoTime();
        AtomicLong lastActivity = new AtomicLong(t0);
        CompletableFuture<Void> result = new CompletableFuture<>();

        // No .timeout(): the watchdog below owns liveness for streaming (see SSE_IDLE_TIMEOUT).
        HttpRequest request = baseRequest(url, apiKey, "text/event-stream", bodyStr, null);

        // Branch on status: 2xx → SSE subscriber; non-2xx → buffer to string so
        // we can surface the (typically JSON) error body in LlmHttpException.
        // Uniform String result: streaming branch returns the "" sentinel,
        // error branch returns the actual body. The downstream continuation
        // decides which path applied based on status code.
        BodyHandler<String> handler = ri -> {
            if (ri.statusCode() / 100 == 2) {
                SseSubscriber sub = new SseSubscriber(requestId, chunkHandler, chunkCount, lastActivity);
                // 4-arg overload: subscriber, finisher → String, charset, line separator
                return BodySubscribers.fromLineSubscriber(sub, s -> "", StandardCharsets.UTF_8, "\n");
            }
            return BodySubscribers.ofString(StandardCharsets.UTF_8);
        };

        CompletableFuture<HttpResponse<String>> send = client.sendAsync(request, handler);

        ScheduledFuture<?> watchdog = SCHEDULER.scheduleWithFixedDelay(() -> {
            long idleMs = (System.nanoTime() - lastActivity.get()) / 1_000_000;
            if (idleMs >= SSE_IDLE_TIMEOUT.toMillis() && !result.isDone()) {
                Constants.LOG.warn("[numen-http][{}] ✗ SSE idle {}ms (> {}s) after {} chunk(s) — cutting stream",
                        requestId, idleMs, SSE_IDLE_TIMEOUT.toSeconds(), chunkCount.get());
                result.completeExceptionally(new SseIdleTimeoutException(requestId, idleMs, chunkCount.get()));
                send.cancel(true);   // tear the connection down; nothing is listening any more
            }
        }, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
        result.whenComplete((v, e) -> watchdog.cancel(false));

        send.whenComplete((resp, err) -> {
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            if (err != null) {
                result.completeExceptionally(unwrap(err));
                return;
            }
            int status = resp.statusCode();
            if (status / 100 != 2) {
                String body2 = resp.body() == null ? "" : resp.body();
                Constants.LOG.warn("[numen-http][{}] ✗ {} in {}ms — body: {}",
                        requestId, status, elapsedMs, truncate(body2, 500));
                result.completeExceptionally(
                        new LlmHttpException(status, body2, retryAfterSeconds(resp.headers())));
                return;
            }
            Constants.LOG.debug("[numen-http][{}] ✓ {} in {}ms, {} chunks",
                    requestId, status, elapsedMs, chunkCount.get());
            result.complete(null);
        });
        return result;
    }

    // ---- internals ----

    private HttpRequest baseRequest(String url, String apiKey, String accept, String body) {
        return baseRequest(url, apiKey, accept, body, REQUEST_TIMEOUT);
    }

    /** @param timeout whole-exchange deadline, or {@code null} for none (streaming — see the watchdog). */
    private HttpRequest baseRequest(String url, String apiKey, String accept, String body, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url));
        if (timeout != null) b.timeout(timeout);
        extraHeaders.forEach(b::header);   // per-site headers (e.g. OpenRouter Referer / Title)
        return b.header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    // ---- transient-failure retry ----

    /**
     * Run {@code call}, retrying transient failures with exponential backoff + jitter.
     *
     * @param safeToRetry re-checked before every retry — the streaming path uses it to
     *                    refuse replay once chunks have already been delivered downstream.
     */
    private <T> CompletableFuture<T> retrying(String requestId, int attempt,
                                              Supplier<CompletableFuture<T>> call,
                                              BooleanSupplier safeToRetry) {
        return call.get()
                .<CompletableFuture<T>>handle((value, err) -> {
                    if (err == null) return CompletableFuture.completedFuture(value);
                    Throwable cause = unwrap(err);
                    if (attempt >= MAX_ATTEMPTS || !isTransient(cause) || !safeToRetry.getAsBoolean()) {
                        return CompletableFuture.<T>failedFuture(cause);
                    }
                    long delayMs = backoffMillis(attempt, cause);
                    Constants.LOG.warn("[numen-http][{}] attempt {}/{} failed ({}) — retrying in {}ms",
                            requestId, attempt, MAX_ATTEMPTS,
                            cause.getClass().getSimpleName() + ": " + cause.getMessage(), delayMs);
                    CompletableFuture<T> next = new CompletableFuture<>();
                    // Block lambda (not an expression one) so this binds to schedule(Runnable, ..)
                    // rather than the Callable overload.
                    SCHEDULER.schedule(() -> {
                        retrying(requestId, attempt + 1, call, safeToRetry)
                                .whenComplete((v2, e2) -> {
                                    if (e2 != null) next.completeExceptionally(unwrap(e2));
                                    else next.complete(v2);
                                });
                    }, delayMs, TimeUnit.MILLISECONDS);
                    return next;
                })
                .thenCompose(f -> f);
    }

    /**
     * Transient = worth another attempt. 429 / 5xx per {@link LlmHttpException#isTransient()};
     * every {@link IOException} (connection reset, DNS blip, TLS hiccup, request timeout, and
     * our own {@link SseIdleTimeoutException}) counts too. Everything else — 4xx, JSON shape
     * errors, programming bugs — would fail identically on replay.
     */
    private static boolean isTransient(Throwable t) {
        if (t instanceof LlmHttpException e) return e.isTransient();
        return t instanceof IOException;
    }

    /** Server {@code Retry-After} when offered, else exponential backoff with jitter. */
    private static long backoffMillis(int attempt, Throwable cause) {
        if (cause instanceof LlmHttpException e && e.hasRetryAfter()) {
            return Math.min(e.retryAfterSeconds() * 1_000L, RETRY_MAX_DELAY_MS);
        }
        long exp = Math.min(RETRY_BASE_DELAY_MS << (attempt - 1), RETRY_MAX_DELAY_MS);
        // Jitter: several companions share one backend, and lock-step retries would
        // re-collide on exactly the rate limit that bounced them.
        return exp + ThreadLocalRandom.current().nextLong(250L);
    }

    /** {@code Retry-After}: delta-seconds or an HTTP-date. {@link LlmHttpException#NO_RETRY_AFTER} if absent/unusable. */
    private static long retryAfterSeconds(HttpHeaders headers) {
        String raw = headers.firstValue("retry-after").orElse(null);
        if (raw == null || raw.isBlank()) return LlmHttpException.NO_RETRY_AFTER;
        String v = raw.trim();
        try {
            return Math.max(0L, Long.parseLong(v));
        } catch (NumberFormatException ignored) {
            // Not delta-seconds — try the HTTP-date form.
        }
        try {
            long secs = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                    - System.currentTimeMillis() / 1000L;
            return Math.max(0L, secs);
        } catch (RuntimeException ignored) {
            return LlmHttpException.NO_RETRY_AFTER;
        }
    }

    /** Peel the {@link CompletionException} wrapper the CompletableFuture chain adds. */
    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }

    /**
     * The SSE stream went completely silent for {@link #SSE_IDLE_TIMEOUT}. An
     * {@link IOException} because that is what it is — a dead connection — and so the
     * retry layer treats it as transient like any other network blip.
     */
    public static final class SseIdleTimeoutException extends IOException {
        public SseIdleTimeoutException(String requestId, long idleMs, long chunks) {
            super("SSE stream idle for " + idleMs + "ms after " + chunks
                    + " chunk(s) (request " + requestId + ")");
        }
    }

    private static CompletableFuture<JsonObject> interpretBuffered(String requestId, long t0,
                                                                    HttpResponse<String> resp) {
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        int status = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();
        if (status / 100 != 2) {
            Constants.LOG.warn("[numen-http][{}] ✗ {} in {}ms — body: {}",
                    requestId, status, elapsedMs, truncate(body, 500));
            return CompletableFuture.failedFuture(
                    new LlmHttpException(status, body, retryAfterSeconds(resp.headers())));
        }
        Constants.LOG.debug("[numen-http][{}] ✓ {} in {}ms ({} bytes)",
                requestId, status, elapsedMs, body.length());
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            return CompletableFuture.completedFuture(obj);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(
                    new LlmHttpException(status, "response is not a JSON object: " + ex.getMessage()
                            + "; body: " + body));
        }
    }

    /** Reserve the next sequential request id ({@code lr-N}) — for callers that pre-log against it. */
    public static String nextRequestId() {
        return "lr-" + REQUEST_ID_SOURCE.incrementAndGet();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Server-Sent Events subscriber. Buffers data lines per event (events
     * are blank-line separated, lines start with {@code data: }), parses
     * each completed event as JSON, and feeds it to the chunk handler.
     *
     * <h2>Multiline data handling</h2>
     * SSE spec allows multiple {@code data:} lines per event (concatenated
     * with {@code \n} between them when the event fires). OpenAI / DeepSeek
     * use single-line data exclusively, but we handle multiline for spec
     * compliance.
     *
     * <h2>{@code [DONE]} sentinel</h2>
     * OpenAI's stream terminates with {@code data: [DONE]\n\n}; we
     * specifically skip parsing that as JSON.
     */
    private static final class SseSubscriber implements Flow.Subscriber<String> {

        private final String requestId;
        private final Consumer<JsonObject> handler;
        private final AtomicLong chunkCount;
        /** Stamped (nanoTime) on every inbound line — the idle watchdog's only input. */
        private final AtomicLong lastActivity;
        private final StringBuilder buffer = new StringBuilder();
        private Flow.Subscription subscription;

        SseSubscriber(String requestId, Consumer<JsonObject> handler, AtomicLong chunkCount,
                      AtomicLong lastActivity) {
            this.requestId = requestId;
            this.handler = handler;
            this.chunkCount = chunkCount;
            this.lastActivity = lastActivity;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String rawLine) {
            // ANY inbound line proves the connection is alive — data lines, `event:`,
            // and `:` keep-alive comments alike. Stamp before parsing so a stream that
            // is only sending heartbeats while the model thinks never trips the watchdog.
            lastActivity.set(System.nanoTime());
            // We split on "\n" (see the 4-arg fromLineSubscriber), so a CRLF gateway
            // leaves a trailing \r on every line: "" never matches, no event ever
            // flushes, and the whole response vanishes silently. Strip it.
            String line = (!rawLine.isEmpty() && rawLine.charAt(rawLine.length() - 1) == '\r')
                    ? rawLine.substring(0, rawLine.length() - 1)
                    : rawLine;
            if (line.isEmpty()) {
                flushEvent();
            } else if (line.startsWith("data: ")) {
                if (buffer.length() > 0) buffer.append('\n');
                buffer.append(line, 6, line.length());
            } else if (line.startsWith("data:")) {
                // Spec-permissive: "data:" without trailing space is valid too.
                if (buffer.length() > 0) buffer.append('\n');
                buffer.append(line, 5, line.length());
            }
            // Other SSE fields (event:, id:, retry:) ignored — we don't need them.
        }

        @Override
        public void onError(Throwable t) {
            Constants.LOG.warn("[numen-http][{}] SSE stream error: {}",
                    requestId, t.getClass().getSimpleName() + ": " + t.getMessage());
            // Future will fail via the wrapping CompletableFuture.
        }

        @Override
        public void onComplete() {
            flushEvent();
        }

        private void flushEvent() {
            if (buffer.length() == 0) return;
            String data = buffer.toString();
            buffer.setLength(0);
            if ("[DONE]".equals(data)) return;
            try {
                JsonObject obj = JsonParser.parseString(data).getAsJsonObject();
                chunkCount.incrementAndGet();
                handler.accept(obj);
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[numen-http][{}] ignoring malformed SSE chunk: {} (data: {})",
                        requestId, ex.getMessage(), truncate(data, 200));
            }
        }
    }
}
