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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import org.openide.modules.ModuleInfo;
import org.openide.modules.SpecificationVersion;
import org.openide.util.Lookup;

final class ControlServer {
    private static final Logger LOG = Logger.getLogger(ControlServer.class.getName());

    private final int port;
    private final GephiFacade gephi;
    private final ReentrantLock commandLock = new ReentrantLock(true);
    private final long queueWaitMs = Long.getLong("gephi.mcp.queue.wait.ms", 30000L);
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
            context("/project/save", this::handleSaveProject);
            context("/graph/open", this::handleOpenGraph);
            context("/graph/summary", this::handleGraphSummary);
            context("/graph/attributes", this::handleGraphAttributes);
            context("/graph/nodes", this::handleGraphNodes);
            context("/graph/edges", this::handleGraphEdges);
            context("/graph/node", this::handleGraphNode);
            context("/graph/neighborhood", this::handleGraphNeighborhood);
            context("/graph/profile", this::handleGraphProfile);
            context("/graph/distribution", this::handleGraphDistribution);
            context("/graph/top-nodes", this::handleTopNodes);
            context("/graph/delete/nodes", this::handleDeleteNodes);
            context("/graph/delete/edges", this::handleDeleteEdges);
            context("/graph/set/nodes", this::handleSetNodeAttribute);
            context("/graph/add/node", this::handleAddNode);
            context("/graph/add/edge", this::handleAddEdge);
            context("/graph/style/nodes", this::handleStyleNodes);
            context("/graph/style/edges", this::handleStyleEdges);
            context("/graph/layout/nodes/circle", this::handleCircleLayoutNodes);
            context("/graph/partition/nodes", this::handlePartitionNodes);
            context("/graph/partition/edges", this::handlePartitionEdges);
            context("/graph/ranking/nodes", this::handleRankingNodes);
            context("/graph/preset/default", this::handleGraphPreset);
            context("/graph/filter", this::handleGraphFilter);
            context("/graph/filter/reset", this::handleResetFilters);
            context("/layouts", this::handleLayouts);
            context("/layouts/run", this::handleRunLayout);
            context("/statistics", this::handleStatistics);
            context("/statistics/run", this::handleRunStatistics);
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
                    try {
                        locked = commandLock.tryLock(queueWaitMs, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        write(exchange, 503, Json.error("interrupted", "interrupted while waiting for Gephi MCP command queue"));
                        return;
                    }
                    if (!locked) {
                        write(exchange, 429, Json.error("queue_timeout",
                                "timed out waiting for another Gephi MCP command to finish"));
                        return;
                    }
                }
                if (mutatesWorkspace(path) && !uiResponsive()) {
                    write(exchange, 503, Json.error("ui_busy",
                            "Gephi UI is not responding; wait until the workspace finishes opening"));
                    return;
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

    private boolean mutatesWorkspace(String path) {
        return path.startsWith("/graph/partition/")
                || path.equals("/graph/open")
                || path.startsWith("/graph/delete/")
                || path.startsWith("/graph/set/")
                || path.startsWith("/graph/add/")
                || path.startsWith("/graph/style/")
                || path.startsWith("/graph/layout/")
                || path.startsWith("/graph/ranking/")
                || path.startsWith("/graph/preset/")
                || path.equals("/graph/filter")
                || path.equals("/graph/filter/reset")
                || path.equals("/project/save")
                || path.equals("/layouts/run")
                || path.equals("/statistics/run");
    }

    private boolean uiResponsive() {
        if (SwingUtilities.isEventDispatchThread()) {
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(latch::countDown);
        try {
            return latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("service", "gephi-mcp-plugin");
        out.put("version", moduleVersion());
        out.put("queueWaitMs", queueWaitMs);
        out.put("queueLength", commandLock.getQueueLength());
        write(exchange, 200, Json.object(out));
    }

    private String moduleVersion() {
        for (ModuleInfo module : Lookup.getDefault().lookupAll(ModuleInfo.class)) {
            if ("org.kgatilin.gephi.gephi.mcp.plugin".equals(module.getCodeNameBase())) {
                SpecificationVersion version = module.getSpecificationVersion();
                return version == null ? "dev" : version.toString();
            }
        }
        Package pkg = ControlServer.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private void handleGraphSummary(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        GraphSummary summary = gephi.graphSummary();
        write(exchange, 200, summary.toJson());
    }

    private void handleOpenGraph(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        write(exchange, 200, gephi.openGraph(query(exchange).get("path")));
    }

    private void handleSaveProject(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        write(exchange, 200, gephi.saveProject(query(exchange).get("path")));
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

    private void handleGraphProfile(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        write(exchange, 200, gephi.graphProfile(intQuery(query(exchange), "limit", 20)));
    }

    private void handleGraphDistribution(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.attributeDistribution(
                query.getOrDefault("element", "node"),
                query.get("attribute"),
                intQuery(query, "limit", 50)));
    }

    private void handleTopNodes(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.topNodes(
                query.getOrDefault("score", "degree"),
                intQuery(query, "limit", 25),
                query.get("includeKinds"),
                query.get("excludeKinds")));
    }

    private void handleDeleteNodes(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.deleteNodes(
                query.get("ids"),
                query.get("attribute"),
                query.getOrDefault("op", "eq"),
                query.get("value"),
                booleanQuery(query, "confirm", false),
                intQuery(query, "limit", 1000)));
    }

    private void handleDeleteEdges(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.deleteEdges(
                query.get("ids"),
                query.get("attribute"),
                query.getOrDefault("op", "eq"),
                query.get("value"),
                booleanQuery(query, "confirm", false),
                intQuery(query, "limit", 1000)));
    }

    private void handleSetNodeAttribute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.setNodeAttribute(
                query.get("ids"),
                query.get("attribute"),
                query.get("value"),
                query.getOrDefault("type", "string"),
                booleanQuery(query, "confirm", false),
                intQuery(query, "limit", 1000)));
    }

    private void handleAddNode(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.addNode(
                query.get("id"),
                query.get("label"),
                query.get("kind")));
    }

    private void handleAddEdge(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.addEdge(
                query.get("id"),
                query.get("source"),
                query.get("target"),
                booleanQuery(query, "directed", true),
                query.get("kind"),
                doubleQuery(query, "weight", 1d)));
    }

    private void handleStyleNodes(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.styleNodes(
                query.get("ids"),
                query.get("attribute"),
                query.getOrDefault("op", "eq"),
                query.get("value"),
                query.get("color"),
                query.get("alpha"),
                query.get("size"),
                intQuery(query, "limit", 1000)));
    }

    private void handleStyleEdges(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.styleEdges(
                query.get("ids"),
                query.get("attribute"),
                query.getOrDefault("op", "eq"),
                query.get("value"),
                query.get("color"),
                query.get("alpha"),
                query.get("weight"),
                intQuery(query, "limit", 1000)));
    }

    private void handleCircleLayoutNodes(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        write(exchange, 200, gephi.circleLayoutNodes(
                query.get("ids"),
                query.get("attribute"),
                query.getOrDefault("op", "eq"),
                query.get("value"),
                floatQuery(query, "radius", 1200f),
                floatQuery(query, "centerX", 0f),
                floatQuery(query, "centerY", 0f),
                floatQuery(query, "startAngle", -90f),
                booleanQuery(query, "clockwise", true),
                booleanQuery(query, "fixed", true),
                intQuery(query, "limit", 1000)));
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

    private void handleGraphPreset(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        write(exchange, 200, gephi.applyGraphPreset());
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
                query.get("value"),
                query.getOrDefault("mode", "view"),
                query.getOrDefault("scope", "visible")));
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
        Map<String, String> parameters = new LinkedHashMap<>(query);
        parameters.remove("name");
        parameters.remove("iterations");
        write(exchange, 200, gephi.runLayout(
                query.get("name"),
                intQuery(query, "iterations", 100),
                parameters));
    }

    private void handleStatistics(HttpExchange exchange) throws IOException {
        if (!requireGet(exchange)) {
            return;
        }
        write(exchange, 200, gephi.statistics());
    }

    private void handleRunStatistics(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        Map<String, String> parameters = new LinkedHashMap<>(query);
        parameters.remove("name");
        write(exchange, 200, gephi.runStatistics(query.get("name"), parameters));
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

    private double doubleQuery(Map<String, String> query, String key, double defaultValue) {
        try {
            return Double.parseDouble(query.getOrDefault(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean booleanQuery(Map<String, String> query, String key, boolean defaultValue) {
        String value = query.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.toLowerCase()) {
            case "true", "t", "yes", "y", "1", "on" -> true;
            case "false", "f", "no", "n", "0", "off" -> false;
            default -> defaultValue;
        };
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
