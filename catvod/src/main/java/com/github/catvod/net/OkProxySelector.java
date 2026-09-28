package com.github.catvod.net;

import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class OkProxySelector extends ProxySelector {

    private final List<Proxy> proxy;
    private final ProxySelector system;
    private final ThreadLocal<Redirect> redirect;
    private final ThreadLocal<Redirect> selectedRedirect;
    private volatile int generation;
    private boolean authSet;

    public OkProxySelector() {
        proxy = new CopyOnWriteArrayList<>();
        system = ProxySelector.getDefault();
        redirect = new ThreadLocal<>();
        selectedRedirect = new ThreadLocal<>();
        Authenticator.setDefault(new ProxyAuthenticator(this));
    }

    public synchronized void addAll(List<Proxy> items) {
        if (items.isEmpty()) return;
        items.forEach(Proxy::init);
        proxy.addAll(items);
        proxy.sort(null);
    }

    public synchronized void clear() {
        Authenticator.setDefault(null);
        generation++;
        clearRedirect();
        proxy.clear();
    }

    public List<Proxy> getProxy() {
        return proxy;
    }

    private List<java.net.Proxy> fallback(URI uri) {
        return system != null ? system.select(uri) : List.of(java.net.Proxy.NO_PROXY);
    }

    @Override
    public List<java.net.Proxy> select(URI uri) {
        Redirect pending = redirect.get();
        redirect.remove();
        selectedRedirect.remove();
        if (proxy.isEmpty() || uri.getHost() == null || "127.0.0.1".equals(uri.getHost())) return fallback(uri);
        if (pending != null && pending.generation == generation && pending.matches(uri)) {
            selectedRedirect.set(pending);
            return pending.proxies;
        }
        Proxy item = find(uri);
        if (item != null) return !item.getProxies().isEmpty() ? item.getProxies() : fallback(uri);
        return fallback(uri);
    }

    boolean redirect(URI target, Proxy item) {
        redirect.remove();
        if (item == null || item.getProxies().isEmpty() || target.getScheme() == null || target.getHost() == null || "127.0.0.1".equals(target.getHost()) || !proxy.contains(item)) return false;
        redirect.set(new Redirect(target, item, generation));
        return true;
    }

    void clearRedirect() {
        redirect.remove();
        selectedRedirect.remove();
    }

    Proxy getRedirectRule(URI uri, java.net.Proxy routeProxy) {
        Redirect selected = selectedRedirect.get();
        return selected != null && selected.generation == generation && selected.matches(uri) && selected.proxies.contains(routeProxy) ? selected.item : null;
    }

    Proxy find(URI uri) {
        if (uri == null || uri.getHost() == null || "127.0.0.1".equals(uri.getHost())) return null;
        for (Proxy item : proxy) for (String host : item.getHosts()) if (Util.containOrMatch(uri.getHost(), host)) return item;
        return null;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
        if (system != null) system.connectFailed(uri, socketAddress, e);
    }

    private static final class Redirect {

        private final Proxy item;
        private final List<java.net.Proxy> proxies;
        private final int generation;
        private final String scheme;
        private final String host;
        private final int port;

        private Redirect(URI uri, Proxy item, int generation) {
            this.scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            this.host = uri.getHost().toLowerCase(Locale.ROOT);
            this.port = getPort(uri);
            this.item = item;
            this.proxies = item.getProxies();
            this.generation = generation;
        }

        private boolean matches(URI uri) {
            return uri.getScheme() != null && uri.getHost() != null && scheme.equals(uri.getScheme().toLowerCase(Locale.ROOT)) && host.equals(uri.getHost().toLowerCase(Locale.ROOT)) && port == getPort(uri);
        }

        private static int getPort(URI uri) {
            if (uri.getPort() != -1) return uri.getPort();
            if ("http".equalsIgnoreCase(uri.getScheme())) return 80;
            if ("https".equalsIgnoreCase(uri.getScheme())) return 443;
            return -1;
        }
    }
}
