package org.kgatilin.gephi.mcp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

final class ControlServer {
    private final int port;
    private final GephiFacade gephi;
    private HttpServer server;

    ControlServer(int port, GephiFacade gephi) {
        this.port = port;
        this.gephi = gephi;
    }

    void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/health", this::handleHealth);
            server.createContext("/graph/summary", this::handleGraphSummary);
            server.createContext("/graph/attributes", this::handleGraphAttributes);
            server.createContext("/graph/nodes", this::handleGraphNodes);
            server.createContext("/graph/edges", this::handleGraphEdges);
            server.createContext("/graph/node", this::handleGraphNode);
            server.createContext("/graph/neighborhood", this::handleGraphNeighborhood);
            server.createContext("/graph/partition/nodes", this::handlePartitionNodes);
            server.createContext("/graph/partition/edges", this::handlePartitionEdges);
            server.createContext("/graph/ranking/nodes", this::handleRankingNodes);
            server.createContext("/graph/preset/code", this::handleCodePreset);
            server.createContext("/graph/filter", this::handleGraphFilter);
            server.createContext("/graph/filter/reset", this::handleResetFilters);
            server.createContext("/layouts", this::handleLayouts);
            server.createContext("/layouts/run", this::handleRunLayout);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "gephi-mcp-control");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start local control server", e);
        }
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        write(exchange, 200, "{\"status\":\"ok\",\"service\":\"gephi-mcp-plugin\"}");
    }

    private void handleGraphSummary(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        GraphSummary summary = gephi.graphSummary();
        write(exchange, 200, summary.toJson());
    }

    private void handleGraphAttributes(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.attributes(query.getOrDefault("element", "node")));
    }

    private void handleGraphNodes(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.nodes(intQuery(query, "limit", 50), query.get("query")));
    }

    private void handleGraphEdges(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.edges(intQuery(query, "limit", 50), query.get("query")));
    }

    private void handleGraphNode(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.node(query.get("id"), intQuery(query, "depth", 0), intQuery(query, "limit", 200)));
    }

    private void handleGraphNeighborhood(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.neighborhood(query.get("id"), intQuery(query, "depth", 1), intQuery(query, "limit", 200)));
    }

    private void handlePartitionNodes(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        write(exchange, 200, gephi.partitionNodes(query(exchange).get("attribute")));
    }

    private void handlePartitionEdges(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        write(exchange, 200, gephi.partitionEdges(query(exchange).get("attribute")));
    }

    private void handleRankingNodes(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.rankingNodes(
                query.get("attribute"),
                floatQuery(query, "minSize", 6f),
                floatQuery(query, "maxSize", 30f)));
    }

    private void handleCodePreset(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        write(exchange, 200, gephi.applyCodeGraphPreset());
    }

    private void handleGraphFilter(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.filter(
                query.getOrDefault("element", "node"),
                query.get("attribute"),
                query.getOrDefault("op", "eq"),
                query.get("value")));
    }

    private void handleResetFilters(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        write(exchange, 200, gephi.resetFilters());
    }

    private void handleLayouts(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        write(exchange, 200, gephi.layouts());
    }

    private void handleRunLayout(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.runLayout(query.get("name"), intQuery(query, "iterations", 100)));
    }

    private boolean requireGet(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return false;
        }
        return true;
    }

    private boolean requirePost(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return false;
        }
        return true;
    }

    private Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : raw.split("&")) {
            int idx = part.indexOf('=');
            String key = idx >= 0 ? part.substring(0, idx) : part;
            String value = idx >= 0 ? part.substring(idx + 1) : "";
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int intQuery(Map<String, String> query, String key, int defaultValue) {
        try {
            return Integer.parseInt(query.getOrDefault(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private float floatQuery(Map<String, String> query, String key, float defaultValue) {
        try {
            return Float.parseFloat(query.getOrDefault(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
