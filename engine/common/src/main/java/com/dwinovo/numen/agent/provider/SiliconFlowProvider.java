package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;

/**
 * SiliconFlow (硅基流动) provider — an OpenAI-compatible aggregator hosting many open models behind one
 * key (model ids are namespaced, e.g. {@code deepseek-ai/DeepSeek-V3}, {@code zai-org/GLM-4.6}). Only the
 * endpoint differs from OpenAI; pick the exact model in settings (or type a custom one).
 */
public final class SiliconFlowProvider extends OpenAIProvider {

    public static final String NAME = "siliconflow";
    public static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }

    /**
     * Documented user-info endpoint carrying balance fields ({@code GET /v1/user/info}, Bearer key):
     * <a href="https://docs.siliconflow.com/en/api-reference/userinfo/get-user-info">docs.siliconflow.com</a>.
     */
    @Override
    public String balanceUrl(String configuredBaseUrl) {
        return "https://api.siliconflow.cn/v1/user/info";
    }

    /** {@code {"code":20000,"status":true,"data":{"balance","chargeBalance","totalBalance",...}}} — CNY, strings. */
    @Override
    public String parseBalance(JsonObject body) {
        if (!body.has("data") || !body.get("data").isJsonObject()) return null;
        JsonObject data = body.getAsJsonObject("data");
        String total = str(data, "totalBalance");
        String gift = str(data, "balance");   // gift/granted portion per the docs' field naming
        String s = "余额: ¥" + (total.isEmpty() ? "?" : total);
        if (!gift.isEmpty() && !"0".equals(gift) && !"0.00".equals(gift)) s += " (赠送 ¥" + gift + ")";
        return s;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
