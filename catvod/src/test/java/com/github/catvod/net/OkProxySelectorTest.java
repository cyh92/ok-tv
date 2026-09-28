package com.github.catvod.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import com.github.catvod.bean.Proxy;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public class OkProxySelectorTest {

    private final OkProxySelector selector = new OkProxySelector();
    private final Call call = null;

    @After
    public void tearDown() {
        selector.clear();
    }

    @Test
    public void redirect302CarriesHttpProxyFromDomainToIp() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.HTTP, 8080);
        addRule("ott.example.com", proxy);

        redirect(302, "http://ott.example.com/live", "http://233.1.2.3/stream");

        assertEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/stream")));
    }

    @Test
    public void redirect307CarriesSocksProxyFromDomainToIp() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.SOCKS, 1080);
        addRule("ott.example.com", proxy);

        redirect(307, "http://ott.example.com/live", "http://233.1.2.3/stream");

        assertEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/stream")));
    }

    @Test
    public void redirectChainCarriesProxyAcrossIpTargets() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.SOCKS, 1080);
        addRule("ott.example.com", proxy);
        ProxyRedirectListener listener = redirect(302, "http://ott.example.com/live", "http://233.1.2.3/stream");
        assertEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/stream")));

        redirect(listener, 302, "http://233.1.2.3/stream", "http://233.1.2.4/stream");

        assertEquals(List.of(proxy), selector.select(new URI("http://233.1.2.4/stream")));
    }

    @Test
    public void redirectedHttpProxyCanAuthenticate() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.HTTP, 8080);
        addRule("ott.example.com", proxy, "user:password");
        redirect(302, "http://ott.example.com/live", "http://233.1.2.3/stream");
        assertEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/stream")));
        Proxy rule = selector.getRedirectRule(new URI("http://233.1.2.3/stream"), proxy);

        assertEquals("user:password", rule.getUserInfo("proxy.example.com", "http"));
        assertNull(selector.getRedirectRule(new URI("http://198.51.100.2/other"), proxy));
        assertNull(selector.getRedirectRule(new URI("http://233.1.2.3/stream"), proxy(java.net.Proxy.Type.HTTP, 8081)));
    }

    @Test
    public void redirectDoesNotProxyLoopbackTarget() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.SOCKS, 1080);
        addRule("ott.example.com", proxy);
        redirect(302, "http://ott.example.com/live", "http://127.0.0.1/stream");

        assertNotEquals(List.of(proxy), selector.select(new URI("http://127.0.0.1/stream")));
    }

    @Test
    public void redirectWithoutFollowUpDoesNotChangeProxy() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.HTTP, 8080);
        addRule("ott.example.com", proxy);
        Request request = new Request.Builder().url("http://ott.example.com/live").build();
        Response response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Redirect").header("Location", "http://233.1.2.3/stream").build();

        new ProxyRedirectListener(selector).followUpDecision(call, response, null);

        assertNotEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/stream")));
    }

    @Test
    public void actualRedirectRequestsStayOnHttpProxy() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            addRule("ott.example.com", new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort())));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<List<String>> requests = executor.submit(() -> {
                    List<String> lines = new ArrayList<>();
                    for (int i = 0; i < 3; i++) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(5000);
                            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                            lines.add(reader.readLine());
                            while (!reader.readLine().isEmpty()) { }
                            String headers = i == 0 ? "HTTP/1.1 302 Found\r\nLocation: http://233.1.2.3/stream\r\n" : i == 1 ? "HTTP/1.1 302 Found\r\nLocation: http://233.1.2.4/stream\r\n" : "HTTP/1.1 200 OK\r\n";
                            socket.getOutputStream().write((headers + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        }
                    }
                    return lines;
                });
                OkHttpClient client = new OkHttpClient.Builder().proxySelector(selector).eventListenerFactory(call -> new ProxyRedirectListener(selector)).connectTimeout(1, TimeUnit.SECONDS).build();

                try (Response response = client.newCall(new Request.Builder().url("http://ott.example.com/live").build()).execute()) {
                    assertEquals(200, response.code());
                }
                assertEquals(List.of("GET http://ott.example.com/live HTTP/1.1", "GET http://233.1.2.3/stream HTTP/1.1", "GET http://233.1.2.4/stream HTTP/1.1"), requests.get(5, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void noRedirectDoesNotChangeUnrelatedHost() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.HTTP, 8080);
        addRule("ott.example.com", proxy);

        assertEquals(List.of(proxy), selector.select(new URI("http://ott.example.com/live")));
        assertNotEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/stream")));
    }

    @Test
    public void redirectFromUnmatchedHostDoesNotEnableProxy() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.HTTP, 8080);
        addRule("ott.example.com", proxy);

        redirect(302, "http://other.example.com/live", "http://233.1.2.3/stream");

        assertNotEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/stream")));
    }

    @Test
    public void redirectDoesNotStickToDifferentRequest() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.HTTP, 8080);
        addRule("ott.example.com", proxy);
        ProxyRedirectListener listener = redirect(302, "http://ott.example.com/live", "http://233.1.2.3/stream");

        listener.callEnd(call);

        assertNotEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/stream")));
    }

    @Test
    public void redirectOnlyAppliesToExactTarget() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.HTTP, 8080);
        addRule("ott.example.com", proxy);

        redirect(302, "http://ott.example.com/live", "http://233.1.2.3/stream");

        assertNotEquals(List.of(proxy), selector.select(new URI("http://198.51.100.2/other")));
        assertNotEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/stream")));
    }

    @Test
    public void redirectStateIsIsolatedFromConcurrentRequest() throws Exception {
        java.net.Proxy proxy = proxy(java.net.Proxy.Type.HTTP, 8080);
        addRule("ott.example.com", proxy);
        redirect(302, "http://ott.example.com/live", "http://233.1.2.3/stream");
        AtomicReference<List<java.net.Proxy>> concurrent = new AtomicReference<>();

        Thread thread = new Thread(() -> concurrent.set(selector.select(URI.create("http://233.1.2.3/stream"))));
        thread.start();
        thread.join();

        assertNotEquals(List.of(proxy), concurrent.get());
        assertEquals(List.of(proxy), selector.select(new URI("http://233.1.2.3/another-path")));
    }

    private ProxyRedirectListener redirect(int code, String source, String location) {
        ProxyRedirectListener listener = new ProxyRedirectListener(selector);
        redirect(listener, code, source, location);
        return listener;
    }

    private void redirect(ProxyRedirectListener listener, int code, String source, String location) {
        Request request = new Request.Builder().url(source).build();
        Response response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Redirect").header("Location", location).build();
        listener.followUpDecision(call, response, request.newBuilder().url(request.url().resolve(location)).build());
    }

    private void addRule(String host, java.net.Proxy javaProxy) {
        addRule(host, javaProxy, null);
    }

    private void addRule(String host, java.net.Proxy javaProxy, String userInfo) {
        Proxy rule = new Proxy() {
            @Override
            public List<String> getHosts() {
                return List.of(host);
            }

            @Override
            public List<java.net.Proxy> getProxies() {
                return List.of(javaProxy);
            }

            @Override
            public String getUserInfo(String proxyHost, String scheme) {
                return userInfo;
            }
        };
        selector.getProxy().add(rule);
    }

    private java.net.Proxy proxy(java.net.Proxy.Type type, int port) {
        return new java.net.Proxy(type, InetSocketAddress.createUnresolved("proxy.example.com", port));
    }
}
