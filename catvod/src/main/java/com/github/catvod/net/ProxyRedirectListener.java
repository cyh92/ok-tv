package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.bean.Proxy;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

final class ProxyRedirectListener extends EventListener {

    private final OkProxySelector selector;
    private HttpUrl redirectTarget;
    private Proxy redirectRule;

    ProxyRedirectListener(OkProxySelector selector) {
        this.selector = selector;
    }

    @Override
    public void followUpDecision(@NonNull Call call, @NonNull Response response, Request nextRequest) {
        if (!response.isRedirect() || nextRequest == null) return;
        HttpUrl source = response.request().url();
        Proxy rule = selector.find(source.uri());
        if (rule == null && source.equals(redirectTarget)) rule = redirectRule;
        redirectTarget = selector.redirect(nextRequest.url().uri(), rule) ? nextRequest.url() : null;
        redirectRule = redirectTarget != null ? rule : null;
    }

    @Override
    public void callEnd(@NonNull Call call) {
        selector.clearRedirect();
    }

    @Override
    public void callFailed(@NonNull Call call, @NonNull java.io.IOException ioe) {
        selector.clearRedirect();
    }
}
