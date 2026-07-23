package com.familylocation.net;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

public final class JsonApiClient {
    public static final class RequestCancelledException extends IOException {
        RequestCancelledException() {
            super("请求已取消。");
        }
    }

    public static final class RequestHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();

        public void cancel() {
            cancelled.set(true);
            HttpURLConnection active = connection.getAndSet(null);
            if (active != null) {
                active.disconnect();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        private boolean attach(HttpURLConnection next) {
            if (cancelled.get()) {
                return false;
            }
            if (!connection.compareAndSet(null, next)) {
                throw new IllegalStateException("请求句柄不能重复使用。");
            }
            if (cancelled.get() && connection.compareAndSet(next, null)) {
                next.disconnect();
                return false;
            }
            return true;
        }

        private void detach(HttpURLConnection completed) {
            connection.compareAndSet(completed, null);
        }
    }

    public interface CookieReceiver {
        void accept(String setCookieHeader);
    }

    public static final class HttpStatusException extends Exception {
        public final int status;
        public final String code;

        HttpStatusException(int status, String message, String code) {
            super(message);
            this.status = status;
            this.code = code == null ? "" : code;
        }
    }

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final String userAgent;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public JsonApiClient(String userAgent, int connectTimeoutMs, int readTimeoutMs) {
        this.userAgent = userAgent;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public JSONObject get(String url, String cookie, CookieReceiver cookieReceiver) throws Exception {
        return request(url, "GET", null, cookie, cookieReceiver, true, null);
    }

    public JSONObject get(String url, String cookie, CookieReceiver cookieReceiver, RequestHandle handle) throws Exception {
        return request(url, "GET", null, cookie, cookieReceiver, true, handle);
    }

    public JSONObject post(String url, JSONObject payload, String cookie, CookieReceiver cookieReceiver) throws Exception {
        return request(url, "POST", payload, cookie, cookieReceiver, true, null);
    }

    public JSONObject post(String url, JSONObject payload, String cookie, CookieReceiver cookieReceiver, RequestHandle handle) throws Exception {
        return request(url, "POST", payload, cookie, cookieReceiver, true, handle);
    }

    public JSONObject getOpen(String url) throws Exception {
        return request(url, "GET", null, "", null, false, null);
    }

    private JSONObject request(
        String url,
        String method,
        JSONObject payload,
        String cookie,
        CookieReceiver cookieReceiver,
        boolean requireOk,
        RequestHandle handle
    ) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (handle != null && !handle.attach(connection)) {
            connection.disconnect();
            throw new RequestCancelledException();
        }
        try {
            configure(connection, method, cookie);
            writePayload(connection, payload);

            int status = connection.getResponseCode();
            captureCookies(connection, cookieReceiver);
            String responseText = readResponse(connection, status);
            return parseResponse(status, responseText, requireOk);
        } catch (IOException exception) {
            if (handle != null && handle.isCancelled()) {
                throw new RequestCancelledException();
            }
            throw exception;
        } finally {
            if (handle != null) {
                handle.detach(connection);
            }
            connection.disconnect();
        }
    }

    private void configure(HttpURLConnection connection, String method, String cookie) throws Exception {
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        if (cookie != null && !cookie.trim().isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
    }

    private void writePayload(HttpURLConnection connection, JSONObject payload) throws Exception {
        if (payload == null) {
            return;
        }
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
    }

    private void captureCookies(HttpURLConnection connection, CookieReceiver receiver) {
        captureCookies(connection.getHeaderFields(), receiver);
    }

    private void captureCookies(Map<String, List<String>> headers, CookieReceiver receiver) {
        if (receiver == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue() == null ? Collections.<String>emptyList() : entry.getValue()) {
                receiver.accept(value);
            }
        }
    }

    private JSONObject parseResponse(int status, String responseText, boolean requireOk) throws Exception {
        assertJsonResponse(responseText);
        JSONObject response = responseText.isEmpty() ? new JSONObject() : new JSONObject(responseText);
        if (status < 200 || status >= 300 || (requireOk && !response.optBoolean("ok", false))) {
            throw new HttpStatusException(
                status,
                response.optString("message", "请求失败。"),
                response.optString("code", "")
            );
        }
        return response;
    }

    private String readResponse(HttpURLConnection connection, int status) throws Exception {
        InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (source == null) {
            return "";
        }
        if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
            source = new GZIPInputStream(source);
        }
        try (InputStream stream = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("服务端响应过大。");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void assertJsonResponse(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() || value.startsWith("{") || value.startsWith("[")) {
            return;
        }
        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("cloudflare") || lower.contains("cf-chl") || lower.contains("turnstile")) {
            throw new IllegalStateException("Cloudflare 质询拦截了 App API，请检查服务器 WAF 规则。");
        }
        throw new IllegalStateException("服务端返回了非 JSON 响应，请检查服务器地址和反代规则。");
    }
}
