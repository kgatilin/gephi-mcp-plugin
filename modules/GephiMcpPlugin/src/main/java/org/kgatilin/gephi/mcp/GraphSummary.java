package org.kgatilin.gephi.mcp;

final class GraphSummary {
    private final boolean hasGraph;
    private final String reason;
    private final int nodes;
    private final int edges;
    private final boolean directed;

    GraphSummary(boolean hasGraph, String reason, int nodes, int edges, boolean directed) {
        this.hasGraph = hasGraph;
        this.reason = reason;
        this.nodes = nodes;
        this.edges = edges;
        this.directed = directed;
    }

    static GraphSummary empty(String reason) {
        return new GraphSummary(false, reason, 0, 0, false);
    }

    String toJson() {
        return "{"
                + "\"hasGraph\":" + hasGraph
                + ",\"reason\":\"" + escape(reason) + "\""
                + ",\"nodes\":" + nodes
                + ",\"edges\":" + edges
                + ",\"directed\":" + directed
                + "}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

