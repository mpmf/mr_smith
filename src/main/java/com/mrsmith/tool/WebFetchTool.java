package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.io.IO;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class WebFetchTool implements Tool {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final long MAX_BYTES = 1_048_576;
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient httpClient;
    private final long timeoutMillis;
    private final IO io;

    public WebFetchTool(IO io) {
        this(HttpClient.newHttpClient(), 10_000L, io);
    }

    public WebFetchTool(HttpClient httpClient, long timeoutMillis, IO io) {
        this.httpClient = httpClient;
        this.timeoutMillis = timeoutMillis;
        this.io = io;
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch a URL over HTTP(S) and return the response body text.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("url").put("type", "string");
        schema.putArray("required").add("url");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String url = args.path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new ToolException("missing required 'url' argument");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ToolException("url must start with http:// or https://");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid url: " + url, e);
        }
        if (uri.getHost() == null) {
            throw new ToolException("invalid url: " + url);
        }
        return fetch(uri, new HashSet<>(), 0);
    }

    private ToolResult fetch(URI uri, Set<String> approvedHosts, int redirects) {
        String host = normalizeHost(uri.getHost());
        if (isPrivateHost(host) && !approvedHosts.contains(host) && !userApproves(uri)) {
            return new ToolResult("User did not approve fetching " + uri
                    + " (private/link-local/localhost host).", true);
        }
        approvedHosts.add(host);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("User-Agent", "mr-smith")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid url: " + uri, e);
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                return handleRedirect(response, uri, approvedHosts, redirects);
            }
            if (response.statusCode() >= 400) {
                close(response);
                return new ToolResult("HTTP " + response.statusCode(), true);
            }
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes((int) MAX_BYTES + 1);
                boolean truncated = bytes.length > MAX_BYTES;
                if (truncated) {
                    bytes = Arrays.copyOf(bytes, (int) MAX_BYTES);
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (truncated) {
                    text = text + "\n[truncated]";
                }
                return new ToolResult(text, false);
            }
        } catch (IOException e) {
            return new ToolResult("fetch failed: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("fetch interrupted");
        }
    }

    private ToolResult handleRedirect(HttpResponse<InputStream> response, URI current,
                                      Set<String> approvedHosts, int redirects) {
        if (redirects >= MAX_REDIRECTS) {
            close(response);
            return new ToolResult("too many redirects", true);
        }
        String location = response.headers().firstValue("Location").orElse(null);
        close(response);
        if (location == null) {
            return new ToolResult("HTTP " + response.statusCode(), true);
        }
        URI next;
        try {
            next = current.resolve(location);
        } catch (IllegalArgumentException e) {
            return new ToolResult("invalid redirect location: " + location, true);
        }
        String scheme = next.getScheme();
        if (next.getHost() == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
            return new ToolResult("invalid redirect location: " + location, true);
        }
        return fetch(next, approvedHosts, redirects + 1);
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
        }
    }

    private boolean userApproves(URI uri) {
        io.writePrompt("web_fetch: " + uri + " targets a private/link-local/localhost host. Fetch anyway? [y/N] ");
        String answer;
        try {
            answer = io.readLine();
        } catch (IOException e) {
            return false;
        }
        return answer != null && (answer.trim().equalsIgnoreCase("y")
                || answer.trim().equalsIgnoreCase("yes"));
    }

    static boolean isPrivateHost(String host) {
        String h = normalizeHost(host);
        if (h.equals("localhost") || h.endsWith(".localhost")) {
            return true;
        }
        if (!isIpLiteral(h)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(h);
            return address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || isUniqueLocalAddress(address);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
            h = h.replace("%25", "%");
        }
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        int zone = h.indexOf('%');
        if (zone >= 0) {
            h = h.substring(0, zone);
        }
        return h;
    }

    private static boolean isUniqueLocalAddress(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.matches("\\d+(\\.\\d+){0,3}");
    }
}
