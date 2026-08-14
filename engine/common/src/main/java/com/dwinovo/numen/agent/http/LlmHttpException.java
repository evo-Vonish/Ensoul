package com.dwinovo.numen.agent.http;

/**
 * Non-2xx HTTP response from an LLM endpoint. Carries both the status code
 * (for branching: 401 vs 429 vs 5xx) and the raw response body (for the
 * specific provider error message — DeepSeek 400s in particular contain
 * the actual problem in plain prose).
 *
 * <p>{@link java.io.IOException}-level failures (DNS, connection refused, TLS)
 * are surfaced via the wrapped {@code IOException} on the future, not this class.
 */
public final class LlmHttpException extends RuntimeException {

    /** {@link #retryAfterSeconds()} value meaning "the response carried no usable Retry-After". */
    public static final long NO_RETRY_AFTER = -1L;

    private final int statusCode;
    private final String responseBody;
    private final long retryAfterSeconds;

    public LlmHttpException(int statusCode, String responseBody) {
        this(statusCode, responseBody, NO_RETRY_AFTER);
    }

    /**
     * @param retryAfterSeconds the server's {@code Retry-After} hint in seconds, or
     *                          {@link #NO_RETRY_AFTER} when absent/unparseable. The
     *                          retry layer in {@link HttpLlmTransport} prefers this
     *                          over its own exponential backoff when present.
     */
    public LlmHttpException(int statusCode, String responseBody, long retryAfterSeconds) {
        super("HTTP " + statusCode + ": " + truncate(responseBody, 500));
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int statusCode() { return statusCode; }
    public String responseBody() { return responseBody; }

    /** Server-supplied backoff hint in seconds, or {@link #NO_RETRY_AFTER} if the response had none. */
    public long retryAfterSeconds() { return retryAfterSeconds; }
    public boolean hasRetryAfter()  { return retryAfterSeconds >= 0; }

    public boolean isRateLimited()    { return statusCode == 429; }
    public boolean isUnauthorized()   { return statusCode == 401 || statusCode == 403; }
    public boolean isClientError()    { return statusCode >= 400 && statusCode < 500; }
    public boolean isServerError()    { return statusCode >= 500; }

    /**
     * Worth another attempt? 429 (rate limited) and 5xx (backend wobble) are transient;
     * every other 4xx is a request the server will reject identically forever (bad key,
     * bad model id, malformed body), so retrying only burns latency.
     */
    public boolean isTransient()      { return isRateLimited() || isServerError(); }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "... (truncated)";
    }
}
