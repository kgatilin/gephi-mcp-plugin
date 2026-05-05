package org.kgatilin.gephi.mcp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

