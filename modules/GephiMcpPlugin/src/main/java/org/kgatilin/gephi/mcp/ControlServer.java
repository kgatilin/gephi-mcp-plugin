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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ControlServer {
    private static final Logger LOG = Logger.getLogger(ControlServer.class.getName());

    private final int port;
    private final GephiFacade gephi;
    private final ReentrantLock commandLock = new ReentrantLock(true);
    private HttpServer server;
    private ExecutorService executor;

    ControlServer(int port, GephiFacade gephi) {
        this.port = port;
        this.gephi = gephi;
    }

    void start() {
        try {
            preloadResponseClasses();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            context("/health", this::handleHealth);
            context("/graph/summary", this::handleGraphSummary);
            context("/graph/attributes", this::handleGraphAttributes);
            context("/graph/nodes", this::handleGraphNodes);
            context("/graph/edges", this::handleGraphEdges);
            context("/graph/node", this::handleGraphNode);
            context("/graph/neighborhood", this::handleGraphNeighborhood);
            context("/graph/partition/nodes", this::handlePartitionNodes);
            context("/graph/partition/edges", this::handlePartitionEdges);
            context("/graph/ranking/nodes", this::handleRankingNodes);
            context("/graph/preset/code", this::handleCodePreset);
            context("/graph/filter", this::handleGraphFilter);
            context("/graph/filter/reset", this::handleResetFilters);
            context("/layouts", this::handleLayouts);
            context("/layouts/run", this::handleRunLayout);
            AtomicInteger counter = new AtomicInteger();
            executor = Executors.newCachedThreadPool(command -> {
                Thread t = new Thread(command, "gephi-mcp-control-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start local control server", e);
        }
    }

    private void preloadResponseClasses() {
        Json.quote("");
        GraphSummary.empty("").toJson();
    }

    private void context(String path, Handler handler) {
        server.createContext(path, exchange -> {
            boolean locked = false;
            try {
                if (!"/health".equals(path)) {
                    locked = commandLock.tryLock();
                    if (!locked) {
                        write(exchange, 409, Json.error("busy", "another Gephi MCP command is still running"));
                        return;
                    }
                }
                handler.handle(exchange);
            } catch (RuntimeException | Error e) {
                LOG.log(Level.WARNING, "Gephi MCP request failed: " + exchange.getRequestURI(), e);
                write(exchange, 500, Json.error(e.getClass().getSimpleName(), e.getMessage()));
            } finally {
                if (locked) {
                    commandLock.unlock();
                }
            }
        });
    }

    void stop() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            current.stop(1);
        }
        ExecutorService currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
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

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
