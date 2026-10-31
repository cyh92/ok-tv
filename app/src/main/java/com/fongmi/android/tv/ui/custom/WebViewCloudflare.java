package com.fongmi.android.tv.ui.custom;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.util.Map;

final class WebViewCloudflare {

    static final String CHECK_SCRIPT = "Boolean(window._cf_chl_opt && window._cf_chl_opt.cType)";

    private static final String CHALLENGE_PATH = "/cdn-cgi/challenge-platform/";
    private static final String TURNSTILE_HOST = "challenges.cloudflare.com";

    static boolean isResource(Uri uri) {
        return isTurnstileHost(uri) || uri.getPath() != null && uri.getPath().contains(CHALLENGE_PATH);
    }

    static boolean isTurnstile(Uri uri) {
        return isTurnstileHost(uri) && uri.getPath() != null && uri.getPath().startsWith("/turnstile/");
    }

    static boolean isChallenge(WebResourceResponse response) {
        return response != null && isChallenge(response.getResponseHeaders());
    }

    static boolean isChallenge(Map<String, String> headers) {
        if (headers == null) return false;
        for (Map.Entry<String, String> entry : headers.entrySet()) if ("cf-mitigated".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && "challenge".equalsIgnoreCase(entry.getValue().trim())) return true;
        return false;
    }

    private static boolean isTurnstileHost(Uri uri) {
        return TURNSTILE_HOST.equalsIgnoreCase(uri.getHost());
    }

    private WebViewCloudflare() {
    }
}
