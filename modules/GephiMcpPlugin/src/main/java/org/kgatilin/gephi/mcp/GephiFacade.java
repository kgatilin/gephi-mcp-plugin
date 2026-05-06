package org.kgatilin.gephi.mcp;

import java.awt.Color;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.gephi.graph.api.Column;
import org.gephi.graph.api.DirectedGraph;
import org.gephi.graph.api.Edge;
import org.gephi.graph.api.Element;
import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.GraphView;
import org.gephi.graph.api.Node;
import org.gephi.graph.api.Table;
import org.gephi.io.importer.api.Container;
import org.gephi.io.importer.api.ImportController;
import org.gephi.io.importer.api.Report;
import org.gephi.layout.spi.Layout;
import org.gephi.layout.spi.LayoutBuilder;
import org.gephi.layout.spi.LayoutProperty;
import org.gephi.project.api.Project;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.gephi.statistics.api.StatisticsController;
import org.gephi.statistics.spi.Statistics;
import org.gephi.statistics.spi.StatisticsBuilder;
import org.openide.util.Lookup;

final class GephiFacade {
    private static final Color[] PALETTE = new Color[]{
        new Color(31, 119, 180),
        new Color(255, 127, 14),
        new Color(44, 160, 44),
        new Color(214, 39, 40),
        new Color(148, 103, 189),
        new Color(140, 86, 75),
        new Color(227, 119, 194),
        new Color(127, 127, 127),
        new Color(188, 189, 34),
        new Color(23, 190, 207)
    };
    private GraphView mcpFilterView;

    GraphSummary graphSummary() {
        Graph graph = visibleGraph();
        if (graph == null) {
            return GraphSummary.empty(missingGraphReason());
        }
        return new GraphSummary(true, "", graph.getNodeCount(), graph.getEdgeCount(), graph.isDirected());
    }

    String openGraph(String path) {
        if (path == null || path.isBlank()) {
            return Json.error("bad_request", "path is required");
        }
        File file = expandPath(path);
        if (!file.isFile()) {
            return Json.error("not_found", "file not found: " + file.getAbsolutePath());
        }
        ProjectController projects = Lookup.getDefault().lookup(ProjectController.class);
        if (projects == null) {
            return Json.error("not_available", "Gephi ProjectController is not available");
        }
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".gephi")) {
            Project project = projects.openProject(file);
            if (project == null) {
                return Json.error("open_failed", "Gephi did not open project: " + file.getAbsolutePath());
            }
            Workspace workspace = projects.getCurrentWorkspace();
            mcpFilterView = null;
            return openGraphResult(file, "project", workspace, null);
        }

        ImportController imports = Lookup.getDefault().lookup(ImportController.class);
        if (imports == null) {
            return Json.error("not_available", "Gephi ImportController is not available");
        }
        if (!imports.isFileSupported(file)) {
            return Json.error("unsupported_file", "no Gephi importer for file: " + file.getAbsolutePath());
        }
        Container container;
        try {
            container = imports.importFile(file);
        } catch (FileNotFoundException e) {
            return Json.error("not_found", e.getMessage());
        }
        if (container == null) {
            return Json.error("import_failed", "Gephi could not import file: " + file.getAbsolutePath());
        }
        if (!container.verify()) {
            return Json.error("invalid_graph", reportText(container.getReport(), 4000));
        }
        Workspace workspace = imports.process(container);
        if (workspace != null) {
            projects.openWorkspace(workspace);
        }
        mcpFilterView = null;
        return openGraphResult(file, "import", workspace, container.getReport());
    }

    String attributes(String elementType) {
        GraphModel model = graphModel();
        if (model == null) {
            return missingGraphJson();
        }
        Table table = "edge".equals(elementType) ? model.getEdgeTable() : model.getNodeTable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("element", "edge".equals(elementType) ? "edge" : "node");
        out.put("attributes", columns(table));
        return Json.object(out);
    }

    String nodes(int limit, String query) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        int max = clamp(limit, 1, 500, 50);
        String q = normalize(query);
        List<Object> nodes = new ArrayList<>();
        graph.readLock();
        try {
            for (Node node : graph.getNodes().toArray()) {
                if (!q.isEmpty() && !matchesNode(graph, node, q)) {
                    continue;
                }
                nodes.add(nodeRecord(graph, node, true));
                if (nodes.size() >= max) {
                    break;
                }
            }
        } finally {
            graph.readUnlock();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("limit", max);
        out.put("query", query == null ? "" : query);
        out.put("nodes", nodes);
        return Json.object(out);
    }

    String edges(int limit, String query) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        int max = clamp(limit, 1, 500, 50);
        String q = normalize(query);
        List<Object> edges = new ArrayList<>();
        graph.readLock();
        try {
            for (Edge edge : graph.getEdges().toArray()) {
                if (!q.isEmpty() && !matchesEdge(edge, q)) {
                    continue;
                }
                edges.add(edgeRecord(edge, true));
                if (edges.size() >= max) {
                    break;
                }
            }
        } finally {
            graph.readUnlock();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("limit", max);
        out.put("query", query == null ? "" : query);
        out.put("edges", edges);
        return Json.object(out);
    }

    String graphProfile(int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        int max = clamp(limit, 1, 100, 20);
        Map<String, Integer> nodeKinds = new LinkedHashMap<>();
        Map<String, Integer> edgeKinds = new LinkedHashMap<>();
        graph.readLock();
        try {
            for (Node node : graph.getNodes()) {
                countValue(nodeKinds, valueOr(attributeValue(node, "kind", graph), "(missing)"));
            }
            for (Edge edge : graph.getEdges()) {
                countValue(edgeKinds, edgeKind(edge, graph));
            }
        } finally {
            graph.readUnlock();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", graph.getNodeCount());
        out.put("edges", graph.getEdgeCount());
        out.put("directed", graph.isDirected());
        out.put("nodeKinds", topCounts(nodeKinds, max));
        out.put("edgeKinds", topCounts(edgeKinds, max));
        return Json.object(out);
    }

    String attributeDistribution(String elementType, String attribute, int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if (attribute == null || attribute.isBlank()) {
            return Json.error("bad_request", "attribute is required");
        }
        int max = clamp(limit, 1, 500, 50);
        String element = "edge".equals(elementType) ? "edge" : "node";
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        graph.readLock();
        try {
            if ("edge".equals(element)) {
                for (Edge edge : graph.getEdges()) {
                    countValue(counts, valueOr(attributeValue(edge, attribute, graph), "(missing)"));
                    total++;
                }
            } else {
                for (Node node : graph.getNodes()) {
                    countValue(counts, valueOr(attributeValue(node, attribute, graph), "(missing)"));
                    total++;
                }
            }
        } finally {
            graph.readUnlock();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("element", element);
        out.put("attribute", attribute);
        out.put("total", total);
        out.put("values", topCounts(counts, max));
        return Json.object(out);
    }

    String topNodes(String score, int limit, String includeKinds, String excludeKinds) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        int max = clamp(limit, 1, 200, 25);
        String scoreMode = score == null || score.isBlank() ? "degree" : normalizeIdentifier(score);
        if (!List.of("degree", "indegree", "outdegree", "weighted", "weighteddegree").contains(scoreMode)) {
            return Json.error("bad_request",
                    "unsupported score: " + score + " (want degree, inDegree, outDegree, or weightedDegree)");
        }
        Set<String> includeKindSet = normalizedSet(includeKinds);
        Set<String> excludeKindSet = normalizedSet(excludeKinds);
        List<Map<String, Object>> ranked = new ArrayList<>();
        graph.readLock();
        try {
            for (Node node : graph.getNodes()) {
                String kind = normalizeIdentifier(valueOr(attributeValue(node, "kind", graph), ""));
                if (!includeKindSet.isEmpty() && !includeKindSet.contains(kind)) {
                    continue;
                }
                if (excludeKindSet.contains(kind)) {
                    continue;
                }
                NodeStats stats = nodeStats(graph, node);
                double value = switch (scoreMode) {
                    case "degree" -> stats.degree;
                    case "indegree" -> stats.inDegree;
                    case "outdegree" -> stats.outDegree;
                    case "weighted", "weighteddegree" -> stats.weightedDegree;
                    default -> stats.degree;
                };
                Map<String, Object> record = compactNodeRecord(graph, node);
                record.put("score", value);
                record.put("scoreMode", scoreMode);
                record.put("degree", stats.degree);
                record.put("inDegree", stats.inDegree);
                record.put("outDegree", stats.outDegree);
                record.put("weightedDegree", stats.weightedDegree);
                record.put("incomingEdgeKinds", topCounts(stats.inEdgeKinds, 10));
                record.put("outgoingEdgeKinds", topCounts(stats.outEdgeKinds, 10));
                ranked.add(record);
            }
        } finally {
            graph.readUnlock();
        }
        ranked.sort((a, b) -> Double.compare(((Number) b.get("score")).doubleValue(),
                ((Number) a.get("score")).doubleValue()));
        if (ranked.size() > max) {
            ranked = new ArrayList<>(ranked.subList(0, max));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", scoreMode);
        out.put("limit", max);
        out.put("includeKinds", includeKindSet);
        out.put("excludeKinds", excludeKindSet);
        out.put("nodes", ranked);
        return Json.object(out);
    }

    String node(String id, int depth, int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if (id == null || id.isBlank()) {
            return Json.error("bad_request", "id is required");
        }
        if (depth > 0) {
            return neighborhood(id, depth, limit);
        }
        graph.readLock();
        try {
            Node node = graph.getNode(id);
            if (node == null) {
                return Json.error("not_found", "node not found: " + id);
            }
            return Json.object(nodeRecord(graph, node, true));
        } finally {
            graph.readUnlock();
        }
    }

    String neighborhood(String id, int depth, int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if (id == null || id.isBlank()) {
            return Json.error("bad_request", "id is required");
        }
        int maxDepth = clamp(depth, 0, 5, 1);
        int max = clamp(limit, 1, 1000, 200);
        graph.readLock();
        try {
            Node start = graph.getNode(id);
            if (start == null) {
                return Json.error("not_found", "node not found: " + id);
            }
            Set<Node> includedNodes = new LinkedHashSet<>();
            Set<Edge> includedEdges = new LinkedHashSet<>();
            Map<Node, Integer> distances = new HashMap<>();
            Queue<Node> queue = new ArrayDeque<>();
            includedNodes.add(start);
            distances.put(start, 0);
            queue.add(start);

            while (!queue.isEmpty() && includedNodes.size() < max) {
                Node current = queue.remove();
                int currentDepth = distances.get(current);
                if (currentDepth >= maxDepth) {
                    continue;
                }
                for (Edge edge : graph.getEdges(current).toArray()) {
                    includedEdges.add(edge);
                    Node opposite = graph.getOpposite(current, edge);
                    if (opposite != null && !includedNodes.contains(opposite)) {
                        includedNodes.add(opposite);
                        distances.put(opposite, currentDepth + 1);
                        queue.add(opposite);
                        if (includedNodes.size() >= max) {
                            break;
                        }
                    }
                }
            }

            List<Object> nodeRecords = new ArrayList<>();
            for (Node n : includedNodes) {
                Map<String, Object> record = nodeRecord(graph, n, true);
                record.put("distance", distances.getOrDefault(n, -1));
                nodeRecords.add(record);
            }
            List<Object> edgeRecords = new ArrayList<>();
            for (Edge edge : includedEdges) {
                if (includedNodes.contains(edge.getSource()) && includedNodes.contains(edge.getTarget())) {
                    edgeRecords.add(edgeRecord(edge, true));
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("center", id);
            out.put("depth", maxDepth);
            out.put("nodes", nodeRecords);
            out.put("edges", edgeRecords);
            return Json.object(out);
        } finally {
            graph.readUnlock();
        }
    }

    String partitionNodes(String attribute) {
        return colorByAttribute("node", attribute);
    }

    String partitionEdges(String attribute) {
        return colorByAttribute("edge", attribute);
    }

    String rankingNodes(String attribute, float minSize, float maxSize) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if (attribute == null || attribute.isBlank()) {
            return Json.error("bad_request", "attribute is required");
        }
        float min = Math.max(1f, minSize);
        float max = Math.max(min, maxSize);
        List<Node> nodes = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        graph.readLock();
        try {
            for (Node node : graph.getNodes()) {
                Double value = numericNodeValue(graph, node, attribute);
                if (value != null && Double.isFinite(value)) {
                    nodes.add(node);
                    values.add(value);
                }
            }
        } finally {
            graph.readUnlock();
        }
        if (nodes.isEmpty()) {
            return Json.error("no_values", "no numeric values for node attribute: " + attribute);
        }
        double low = values.stream().min(Comparator.naturalOrder()).orElse(0d);
        double high = values.stream().max(Comparator.naturalOrder()).orElse(0d);
        for (int i = 0; i < nodes.size(); i++) {
            double ratio = high == low ? 0.5d : (values.get(i) - low) / (high - low);
            nodes.get(i).setSize((float) (min + ratio * (max - min)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "ranking_nodes");
        out.put("attribute", attribute);
        out.put("styledNodes", nodes.size());
        out.put("minValue", low);
        out.put("maxValue", high);
        out.put("minSize", min);
        out.put("maxSize", max);
        return Json.object(out);
    }

    String applyGraphPreset() {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        int styledNodes = 0;
        int styledEdges = 0;
        for (Node node : graph.getNodes()) {
            Object group = firstPresentAttribute(node, graph, List.of("kind", "type", "group", "modularity_class"));
            node.setColor(colorFor(valueOr(group, "node")));
            node.setAlpha(0.95f);
            node.setSize(Math.max(8f, 6f + graph.getDegree(node) * 1.2f));
            styledNodes++;
        }
        for (Edge edge : graph.getEdges()) {
            Object relation = valueOr(attributeValue(edge, "relation", graph),
                    valueOr(attributeValue(edge, "kind", graph), valueOr(edge.getTypeLabel(), "edge")));
            edge.setColor(edgeColorFor(String.valueOf(relation)));
            edge.setAlpha(0.55f);
            styledEdges++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "apply_graph_preset");
        out.put("styledNodes", styledNodes);
        out.put("styledEdges", styledEdges);
        return Json.object(out);
    }

    String saveProject(String path) {
        ProjectController projects = Lookup.getDefault().lookup(ProjectController.class);
        if (projects == null) {
            return Json.error("not_available", "Gephi ProjectController is not available");
        }
        Project project = projects.getCurrentProject();
        if (project == null) {
            return Json.error("no_project", "no active Gephi project");
        }
        File file;
        if (path == null || path.isBlank()) {
            if (!project.hasFile()) {
                return Json.error("bad_request", "path is required because the current project has no file");
            }
            file = project.getFile();
        } else {
            file = expandPath(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return Json.error("io_error", "cannot create directory: " + parent.getAbsolutePath());
            }
        }

        projects.saveProject(project, file);
        Workspace workspace = projects.getCurrentWorkspace();
        Graph graph = visibleGraph();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "save_project");
        out.put("path", file.getAbsolutePath());
        out.put("projectName", project.getName());
        out.put("workspaceId", workspace == null ? null : workspace.getId());
        out.put("workspaceName", workspace == null ? null : workspace.getName());
        out.put("visibleNodes", graph == null ? 0 : graph.getNodeCount());
        out.put("visibleEdges", graph == null ? 0 : graph.getEdgeCount());
        out.put("filtered", isFilteredView(graphModel()));
        return Json.object(out);
    }

    String filter(String elementType, String attribute, String op, String value, String mode, String scope) {
        String filterMode = normalizeIdentifier(mode);
        if (filterMode.isEmpty() || "view".equals(filterMode) || "graphview".equals(filterMode)) {
            return filterView(elementType, attribute, op, value, scope);
        }
        if ("highlight".equals(filterMode) || "visual".equals(filterMode) || "dim".equals(filterMode)) {
            return filterHighlight(elementType, attribute, op, value);
        }
        return Json.error("bad_request", "unsupported filter mode: " + mode + " (want view or highlight)");
    }

    private String filterView(String elementType, String attribute, String op, String value, String scope) {
        GraphModel model = graphModel();
        if (model == null) {
            return missingGraphJson();
        }
        Graph graph = "full".equals(normalizeIdentifier(scope)) ? model.getGraph() : model.getGraphVisible();
        if (graph == null) {
            return missingGraphJson();
        }
        if (attribute == null || attribute.isBlank()) {
            return Json.error("bad_request", "attribute is required");
        }
        String operator = op == null || op.isBlank() ? "eq" : op;
        String element = "edge".equals(elementType) ? "edge" : "node";

        Set<Node> matchedNodes = new HashSet<>();
        Set<Edge> matchedEdges = new HashSet<>();
        int totalNodes = 0;
        int totalEdges = 0;
        graph.readLock();
        try {
            if ("edge".equals(element)) {
                for (Edge edge : graph.getEdges().toArray()) {
                    totalEdges++;
                    if (matchesFilter(edge, attribute, operator, value, graph)) {
                        matchedEdges.add(edge);
                        matchedNodes.add(edge.getSource());
                        matchedNodes.add(edge.getTarget());
                    }
                }
                for (Node ignored : graph.getNodes()) {
                    totalNodes++;
                }
            } else {
                for (Node node : graph.getNodes().toArray()) {
                    totalNodes++;
                    if (matchesFilter(node, attribute, operator, value, graph)) {
                        matchedNodes.add(node);
                    }
                }
                for (Edge edge : graph.getEdges().toArray()) {
                    totalEdges++;
                    if (matchedNodes.contains(edge.getSource()) && matchedNodes.contains(edge.getTarget())) {
                        matchedEdges.add(edge);
                    }
                }
            }
        } finally {
            graph.readUnlock();
        }

        GraphView view = model.createView(matchedNodes::contains, matchedEdges::contains);
        GraphView previous = mcpFilterView;
        model.setVisibleView(view);
        mcpFilterView = view;
        destroyFilterView(model, previous);
        Graph visible = model.getGraphVisible();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "filter_graph");
        out.put("mode", "view_filter");
        out.put("scope", "full".equals(normalizeIdentifier(scope)) ? "full" : "visible");
        out.put("element", element);
        out.put("attribute", attribute);
        out.put("op", operator);
        out.put("value", value == null ? "" : value);
        out.put("matchedNodes", matchedNodes.size());
        out.put("matchedEdges", matchedEdges.size());
        out.put("beforeNodes", totalNodes);
        out.put("beforeEdges", totalEdges);
        out.put("visibleNodes", visible == null ? 0 : visible.getNodeCount());
        out.put("visibleEdges", visible == null ? 0 : visible.getEdgeCount());
        return Json.object(out);
    }

    private String filterHighlight(String elementType, String attribute, String op, String value) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if (attribute == null || attribute.isBlank()) {
            return Json.error("bad_request", "attribute is required");
        }
        String operator = op == null || op.isBlank() ? "eq" : op;
        String element = "edge".equals(elementType) ? "edge" : "node";

        Set<Node> matchedNodes = new HashSet<>();
        Set<Edge> matchedEdges = new HashSet<>();
        int totalNodes = 0;
        int totalEdges = 0;
        if ("edge".equals(element)) {
            for (Edge edge : graph.getEdges()) {
                totalEdges++;
                if (matchesFilter(edge, attribute, operator, value, graph)) {
                    matchedEdges.add(edge);
                    matchedNodes.add(edge.getSource());
                    matchedNodes.add(edge.getTarget());
                }
            }
            for (Node ignored : graph.getNodes()) {
                totalNodes++;
            }
        } else {
            for (Node node : graph.getNodes()) {
                totalNodes++;
                if (matchesFilter(node, attribute, operator, value, graph)) {
                    matchedNodes.add(node);
                }
            }
            for (Edge edge : graph.getEdges()) {
                totalEdges++;
                if (matchedNodes.contains(edge.getSource()) && matchedNodes.contains(edge.getTarget())) {
                    matchedEdges.add(edge);
                }
            }
        }

        for (Node node : graph.getNodes()) {
            node.setAlpha(matchedNodes.contains(node) ? 1.0f : 0.08f);
        }
        for (Edge edge : graph.getEdges()) {
            edge.setAlpha(matchedEdges.contains(edge) ? 0.75f : 0.03f);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "filter_graph");
        out.put("mode", "visual_highlight");
        out.put("element", element);
        out.put("attribute", attribute);
        out.put("op", operator);
        out.put("value", value == null ? "" : value);
        out.put("matchedNodes", matchedNodes.size());
        out.put("matchedEdges", matchedEdges.size());
        out.put("dimmedNodes", Math.max(0, totalNodes - matchedNodes.size()));
        out.put("dimmedEdges", Math.max(0, totalEdges - matchedEdges.size()));
        out.put("visibleNodes", totalNodes);
        out.put("visibleEdges", totalEdges);
        return Json.object(out);
    }

    String resetFilters() {
        GraphModel model = graphModel();
        if (model == null) {
            return missingGraphJson();
        }
        Graph graph = model.getGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        GraphView previous = mcpFilterView;
        GraphView mainView = graph.getView();
        if (mainView != null) {
            model.setVisibleView(mainView);
        }
        mcpFilterView = null;
        destroyFilterView(model, previous);
        graph = model.getGraphVisible();
        if (graph == null) {
            return missingGraphJson();
        }
        int nodes = 0;
        int edges = 0;
        for (Node node : graph.getNodes()) {
            node.setAlpha(0.95f);
            nodes++;
        }
        for (Edge edge : graph.getEdges()) {
            edge.setAlpha(0.55f);
            edges++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "reset_filters");
        out.put("mode", "view_filter_reset");
        out.put("visibleNodes", nodes);
        out.put("visibleEdges", edges);
        return Json.object(out);
    }

    String deleteNodes(String ids, String attribute, String op, String value, boolean confirm, int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        int max = clamp(limit, 1, 10000, 1000);
        List<Node> selected = selectedNodes(graph, ids, attribute, op, value, max);
        List<Object> sample = new ArrayList<>();
        for (Node node : selected.subList(0, Math.min(20, selected.size()))) {
            sample.add(compactNodeRecord(graph, node));
        }
        int beforeNodes = graph.getNodeCount();
        int beforeEdges = graph.getEdgeCount();
        boolean changed = false;
        if (confirm) {
            changed = graph.removeAllNodes(selected);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "delete_nodes");
        out.put("confirmed", confirm);
        out.put("deletedNodes", confirm ? selected.size() : 0);
        out.put("matchedNodes", selected.size());
        out.put("changed", changed);
        out.put("beforeNodes", beforeNodes);
        out.put("beforeEdges", beforeEdges);
        out.put("afterNodes", graph.getNodeCount());
        out.put("afterEdges", graph.getEdgeCount());
        out.put("sample", sample);
        return Json.object(out);
    }

    String deleteEdges(String ids, String attribute, String op, String value, boolean confirm, int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        int max = clamp(limit, 1, 10000, 1000);
        List<Edge> selected = selectedEdges(graph, ids, attribute, op, value, max);
        List<Object> sample = new ArrayList<>();
        for (Edge edge : selected.subList(0, Math.min(20, selected.size()))) {
            sample.add(edgeRecord(edge, true));
        }
        int beforeNodes = graph.getNodeCount();
        int beforeEdges = graph.getEdgeCount();
        boolean changed = false;
        if (confirm) {
            changed = graph.removeAllEdges(selected);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "delete_edges");
        out.put("confirmed", confirm);
        out.put("deletedEdges", confirm ? selected.size() : 0);
        out.put("matchedEdges", selected.size());
        out.put("changed", changed);
        out.put("beforeNodes", beforeNodes);
        out.put("beforeEdges", beforeEdges);
        out.put("afterNodes", graph.getNodeCount());
        out.put("afterEdges", graph.getEdgeCount());
        out.put("sample", sample);
        return Json.object(out);
    }

    String setNodeAttribute(String ids, String attribute, String value, String type, boolean confirm, int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if (attribute == null || attribute.isBlank()) {
            return Json.error("bad_request", "attribute is required");
        }
        if (ids == null || ids.isBlank()) {
            return Json.error("bad_request", "ids is required");
        }
        int max = clamp(limit, 1, 10000, 1000);
        List<Node> selected = selectedNodes(graph, ids, null, "eq", null, max);
        Object parsed = parseAttributeValue(value, type);
        Column column = ensureElementColumn(graph.getModel().getNodeTable(), "node", attribute, parsed);
        if (confirm) {
            for (Node node : selected) {
                node.setAttribute(column, parsed);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "set_node_attribute");
        out.put("confirmed", confirm);
        out.put("matchedNodes", selected.size());
        out.put("attribute", column.getId());
        out.put("value", parsed);
        return Json.object(out);
    }

    String addNode(String id, String label, String kind) {
        GraphModel model = graphModel();
        if (model == null) {
            return missingGraphJson();
        }
        Graph graph = model.getGraphVisible();
        if (graph == null) {
            return missingGraphJson();
        }
        if (id == null || id.isBlank()) {
            return Json.error("bad_request", "id is required");
        }
        if (graph.getNode(id) != null) {
            return Json.error("already_exists", "node already exists: " + id);
        }
        Node node = model.factory().newNode(id);
        if (label != null && !label.isBlank()) {
            node.setLabel(label);
        }
        if (kind != null && !kind.isBlank()) {
            setElementAttribute(graph, node, "node", "kind", kind);
        }
        graph.addNode(node);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "add_node");
        out.put("node", nodeRecord(graph, node, true));
        out.put("nodes", graph.getNodeCount());
        out.put("edges", graph.getEdgeCount());
        return Json.object(out);
    }

    String addEdge(String id, String sourceId, String targetId, boolean directed, String kind, double weight) {
        GraphModel model = graphModel();
        if (model == null) {
            return missingGraphJson();
        }
        Graph graph = model.getGraphVisible();
        if (graph == null) {
            return missingGraphJson();
        }
        if (sourceId == null || sourceId.isBlank() || targetId == null || targetId.isBlank()) {
            return Json.error("bad_request", "source and target are required");
        }
        Node source = graph.getNode(sourceId);
        Node target = graph.getNode(targetId);
        if (source == null || target == null) {
            return Json.error("not_found", "source or target node not found");
        }
        Object edgeId = id == null || id.isBlank() ? null : id;
        if (edgeId != null && graph.getEdge(edgeId) != null) {
            return Json.error("already_exists", "edge already exists: " + edgeId);
        }
        int type = kind == null || kind.isBlank() ? 0 : model.addEdgeType(kind);
        Edge edge = edgeId == null
                ? model.factory().newEdge(source, target, type, weight, directed)
                : model.factory().newEdge(edgeId, source, target, type, weight, directed);
        if (kind != null && !kind.isBlank()) {
            setElementAttribute(graph, edge, "edge", "kind", kind);
        }
        graph.addEdge(edge);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "add_edge");
        out.put("edge", edgeRecord(edge, true));
        out.put("nodes", graph.getNodeCount());
        out.put("edges", graph.getEdgeCount());
        return Json.object(out);
    }

    String styleNodes(String ids, String attribute, String op, String value, String colorSpec,
            String alphaSpec, String sizeSpec, int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if ((ids == null || ids.isBlank()) && (attribute == null || attribute.isBlank())) {
            return Json.error("bad_request", "ids or attribute is required");
        }

        Color color;
        Float alpha;
        Float size;
        try {
            color = colorSpec == null || colorSpec.isBlank() ? null : parseStyleColor(colorSpec);
            alpha = alphaSpec == null || alphaSpec.isBlank() ? null : parseStyleFloat(alphaSpec, "alpha");
            size = sizeSpec == null || sizeSpec.isBlank() ? null : parseStyleFloat(sizeSpec, "size");
        } catch (IllegalArgumentException e) {
            return Json.error("bad_request", e.getMessage());
        }
        if (color == null && alpha == null && size == null) {
            return Json.error("bad_request", "color, alpha, or size is required");
        }
        if (alpha != null && (alpha < 0f || alpha > 1f)) {
            return Json.error("bad_request", "alpha must be between 0 and 1");
        }
        if (size != null && size <= 0f) {
            return Json.error("bad_request", "size must be greater than 0");
        }

        int max = clamp(limit, 1, 10000, 1000);
        List<Node> selected = selectedNodes(graph, ids, attribute, op, value, max);
        List<Object> sample = new ArrayList<>();
        for (Node node : selected) {
            if (color != null) {
                node.setColor(color);
            }
            if (alpha != null) {
                node.setAlpha(alpha);
            }
            if (size != null) {
                node.setSize(size);
            }
            if (sample.size() < 20) {
                sample.add(nodeRecord(graph, node, true));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "style_nodes");
        out.put("matchedNodes", selected.size());
        out.put("styledNodes", selected.size());
        if (color != null) {
            out.put("color", colorRecord(color));
        }
        if (alpha != null) {
            out.put("alpha", alpha);
        }
        if (size != null) {
            out.put("size", size);
        }
        out.put("sample", sample);
        return Json.object(out);
    }

    String styleEdges(String ids, String attribute, String op, String value, String colorSpec,
            String alphaSpec, String weightSpec, int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if ((ids == null || ids.isBlank()) && (attribute == null || attribute.isBlank())) {
            return Json.error("bad_request", "ids or attribute is required");
        }

        Color color;
        Float alpha;
        Double weight;
        try {
            color = colorSpec == null || colorSpec.isBlank() ? null : parseStyleColor(colorSpec);
            alpha = alphaSpec == null || alphaSpec.isBlank() ? null : parseStyleFloat(alphaSpec, "alpha");
            weight = weightSpec == null || weightSpec.isBlank() ? null : parseStyleDouble(weightSpec, "weight");
        } catch (IllegalArgumentException e) {
            return Json.error("bad_request", e.getMessage());
        }
        if (color == null && alpha == null && weight == null) {
            return Json.error("bad_request", "color, alpha, or weight is required");
        }
        if (alpha != null && (alpha < 0f || alpha > 1f)) {
            return Json.error("bad_request", "alpha must be between 0 and 1");
        }
        if (weight != null && weight <= 0d) {
            return Json.error("bad_request", "weight must be greater than 0");
        }

        int max = clamp(limit, 1, 10000, 1000);
        List<Edge> selected = selectedEdges(graph, ids, attribute, op, value, max);
        List<Object> sample = new ArrayList<>();
        for (Edge edge : selected) {
            if (color != null) {
                edge.setColor(color);
            }
            if (alpha != null) {
                edge.setAlpha(alpha);
            }
            if (weight != null) {
                edge.setWeight(weight);
            }
            if (sample.size() < 20) {
                sample.add(edgeRecord(edge, true));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "style_edges");
        out.put("matchedEdges", selected.size());
        out.put("styledEdges", selected.size());
        if (color != null) {
            out.put("color", colorRecord(color));
        }
        if (alpha != null) {
            out.put("alpha", alpha);
        }
        if (weight != null) {
            out.put("weight", weight);
        }
        out.put("sample", sample);
        return Json.object(out);
    }

    String circleLayoutNodes(String ids, String attribute, String op, String value, float radius,
            float centerX, float centerY, float startAngle, boolean clockwise, boolean fixed, int limit) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if ((ids == null || ids.isBlank()) && (attribute == null || attribute.isBlank())) {
            return Json.error("bad_request", "ids or attribute is required");
        }
        if (!Float.isFinite(radius) || radius <= 0f) {
            return Json.error("bad_request", "radius must be greater than 0");
        }
        int max = clamp(limit, 1, 10000, 1000);
        List<Node> selected = selectedNodes(graph, ids, attribute, op, value, max);
        if (selected.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("tool", "circle_layout_nodes");
            out.put("matchedNodes", 0);
            out.put("styledNodes", 0);
            return Json.object(out);
        }

        double direction = clockwise ? 1d : -1d;
        double start = Math.toRadians(startAngle);
        int count = selected.size();
        List<Object> sample = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Node node = selected.get(i);
            double angle = start + direction * (2d * Math.PI * i / count);
            node.setPosition(
                    centerX + (float) (radius * Math.cos(angle)),
                    centerY + (float) (radius * Math.sin(angle)));
            node.setFixed(fixed);
            if (sample.size() < 20) {
                sample.add(nodeRecord(graph, node, true));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "circle_layout_nodes");
        out.put("matchedNodes", selected.size());
        out.put("styledNodes", selected.size());
        out.put("radius", radius);
        out.put("centerX", centerX);
        out.put("centerY", centerY);
        out.put("startAngle", startAngle);
        out.put("clockwise", clockwise);
        out.put("fixed", fixed);
        out.put("sample", sample);
        return Json.object(out);
    }

    String statistics() {
        List<Object> stats = new ArrayList<>();
        for (StatisticsBuilder builder : Lookup.getDefault().lookupAll(StatisticsBuilder.class)) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("name", builder.getName());
            record.put("class", builder.getStatisticsClass().getName());
            record.put("aliases", statisticsBuilderAliases(builder));
            record.put("parameters", statisticsParameters(builder.getStatistics()));
            stats.add(record);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statistics", stats);
        return Json.object(out);
    }

    String runStatistics(String name, Map<String, String> parameters) {
        GraphModel model = graphModel();
        if (model == null) {
            return missingGraphJson();
        }
        if (name == null || name.isBlank()) {
            return Json.error("bad_request", "statistic name is required");
        }
        StatisticsController controller = Lookup.getDefault().lookup(StatisticsController.class);
        if (controller == null) {
            return Json.error("not_available", "Gephi StatisticsController is not available");
        }
        StatisticsBuilder builder = findStatisticsBuilder(name);
        if (builder == null) {
            return Json.error("not_found", "statistic not found: " + name);
        }
        Statistics statistic = builder.getStatistics();
        List<Object> appliedParameters;
        try {
            appliedParameters = applyStatisticsParameters(statistic, parameters);
        } catch (IllegalArgumentException e) {
            return Json.error("bad_request", e.getMessage());
        }
        controller.execute(statistic);

        String report = statistic.getReport();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "run_statistics");
        out.put("statistic", builder.getName());
        out.put("class", builder.getStatisticsClass().getName());
        out.put("parameters", appliedParameters);
        out.put("results", statisticsResults(statistic));
        out.put("reportLength", report == null ? 0 : report.length());
        out.put("reportTruncated", report != null && report.length() > 20000);
        out.put("report", truncate(report, 20000));
        return Json.object(out);
    }

    private String openGraphResult(File file, String mode, Workspace workspace, Report report) {
        Graph graph = visibleGraph();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "open_graph");
        out.put("mode", mode);
        out.put("path", file.getAbsolutePath());
        if (workspace != null) {
            out.put("workspaceId", workspace.getId());
            out.put("workspaceName", workspace.getName());
            out.put("workspaceSource", workspace.getSource());
        }
        out.put("nodes", graph == null ? 0 : graph.getNodeCount());
        out.put("edges", graph == null ? 0 : graph.getEdgeCount());
        out.put("directed", graph != null && graph.isDirected());
        if (report != null) {
            out.put("reportHasIssues", report.hasIssues());
            out.put("report", reportText(report, 4000));
        }
        return Json.object(out);
    }

    private String reportText(Report report, int maxLength) {
        if (report == null) {
            return "";
        }
        return truncate(report.getText(false), maxLength);
    }

    private List<Node> selectedNodes(Graph graph, String ids, String attribute, String op, String value, int limit) {
        List<Node> selected = new ArrayList<>();
        Set<String> wantedIds = splitSet(ids);
        if (!wantedIds.isEmpty()) {
            for (String id : wantedIds) {
                Node node = graph.getNode(id);
                if (node != null) {
                    selected.add(node);
                    if (selected.size() >= limit) {
                        break;
                    }
                }
            }
            return selected;
        }
        if (attribute == null || attribute.isBlank()) {
            return selected;
        }
        String operator = op == null || op.isBlank() ? "eq" : op;
            for (Node node : graph.getNodes().toArray()) {
                if (matchesFilter(node, attribute, operator, value, graph)) {
                    selected.add(node);
                    if (selected.size() >= limit) {
                    break;
                }
            }
        }
        return selected;
    }

    private List<Edge> selectedEdges(Graph graph, String ids, String attribute, String op, String value, int limit) {
        List<Edge> selected = new ArrayList<>();
        Set<String> wantedIds = splitSet(ids);
        if (!wantedIds.isEmpty()) {
            for (String id : wantedIds) {
                Edge edge = graph.getEdge(id);
                if (edge != null) {
                    selected.add(edge);
                    if (selected.size() >= limit) {
                        break;
                    }
                }
            }
            return selected;
        }
        if (attribute == null || attribute.isBlank()) {
            return selected;
        }
        String operator = op == null || op.isBlank() ? "eq" : op;
        for (Edge edge : graph.getEdges().toArray()) {
            if (matchesFilter(edge, attribute, operator, value, graph)) {
                selected.add(edge);
                if (selected.size() >= limit) {
                    break;
                }
            }
        }
        return selected;
    }

    private Map<String, Object> compactNodeRecord(Graph graph, Node node) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", node.getId());
        record.put("label", node.getLabel());
        record.put("kind", attributeValue(node, "kind", graph));
        record.put("attributes", attributesRecord(node));
        return record;
    }

    private NodeStats nodeStats(Graph graph, Node node) {
        NodeStats stats = new NodeStats();
        if (graph instanceof DirectedGraph directed) {
            stats.inDegree = directed.getInDegree(node);
            stats.outDegree = directed.getOutDegree(node);
            stats.degree = stats.inDegree + stats.outDegree;
            for (Edge edge : directed.getInEdges(node)) {
                String kind = edgeKind(edge, graph);
                countValue(stats.inEdgeKinds, kind);
                stats.weightedDegree += edgeWeight(edge);
            }
            for (Edge edge : directed.getOutEdges(node)) {
                String kind = edgeKind(edge, graph);
                countValue(stats.outEdgeKinds, kind);
                stats.weightedDegree += edgeWeight(edge);
            }
        } else {
            stats.degree = graph.getDegree(node);
            stats.inDegree = stats.degree;
            stats.outDegree = stats.degree;
            for (Edge edge : graph.getEdges(node)) {
                String kind = edgeKind(edge, graph);
                countValue(stats.inEdgeKinds, kind);
                stats.weightedDegree += edgeWeight(edge);
            }
        }
        return stats;
    }

    private String edgeKind(Edge edge, Graph graph) {
        Object relation = valueOr(attributeValue(edge, "relation", graph),
                valueOr(attributeValue(edge, "kind", graph), valueOr(edge.getTypeLabel(), "edge")));
        return String.valueOf(relation);
    }

    private double edgeWeight(Edge edge) {
        double weight = edge.getWeight();
        return Double.isFinite(weight) ? weight : 1d;
    }

    private void countValue(Map<String, Integer> counts, Object value) {
        String key = value == null ? "(missing)" : String.valueOf(value);
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private List<Object> topCounts(Map<String, Integer> counts, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Object> out = new ArrayList<>();
        int max = Math.min(limit, entries.size());
        for (int i = 0; i < max; i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("value", entry.getKey());
            record.put("count", entry.getValue());
            out.add(record);
        }
        return out;
    }

    private Set<String> normalizedSet(String raw) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : splitSet(raw)) {
            String normalized = normalizeIdentifier(value);
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private Set<String> splitSet(String raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : raw.split("[,\\n]")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private void setElementAttribute(Graph graph, Element element, String elementType, String attribute, Object value) {
        Table table = "edge".equals(elementType) ? graph.getModel().getEdgeTable() : graph.getModel().getNodeTable();
        Column column = ensureElementColumn(table, elementType, attribute, value);
        element.setAttribute(column, value);
    }

    private Column ensureElementColumn(Table table, String elementType, String attribute, Object value) {
        Column existing = attributeColumn(table, attribute, elementType);
        if (existing != null && !existing.isReadOnly()) {
            return existing;
        }
        String id = attribute;
        Column column = table.getColumn(id);
        if (column != null) {
            return column;
        }
        return table.addColumn(id, attribute, attributeType(value), null);
    }

    private Class<?> attributeType(Object value) {
        if (value instanceof Boolean) {
            return Boolean.class;
        }
        if (value instanceof Integer) {
            return Integer.class;
        }
        if (value instanceof Long) {
            return Long.class;
        }
        if (value instanceof Float) {
            return Float.class;
        }
        if (value instanceof Double) {
            return Double.class;
        }
        return String.class;
    }

    private Object parseAttributeValue(String value, String type) {
        String t = normalizeIdentifier(type);
        String raw = value == null ? "" : value;
        try {
            return switch (t) {
                case "bool", "boolean" -> parseBooleanValue(raw, "value");
                case "int", "integer" -> Integer.valueOf(raw);
                case "long" -> Long.valueOf(raw);
                case "float" -> Float.valueOf(raw);
                case "double", "number" -> Double.valueOf(raw);
                default -> inferAttributeValue(raw);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + type + " attribute value: " + raw);
        }
    }

    private Object inferAttributeValue(String raw) {
        String normalized = normalizeIdentifier(raw);
        if (List.of("true", "false", "yes", "no", "1", "0").contains(normalized)) {
            return parseBooleanValue(raw, "value");
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ignored) {
            // Try a floating point value next.
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    private Color parseStyleColor(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "black" -> Color.BLACK;
            case "blue" -> Color.BLUE;
            case "cyan" -> Color.CYAN;
            case "gray", "grey" -> Color.GRAY;
            case "green" -> Color.GREEN;
            case "magenta" -> Color.MAGENTA;
            case "orange" -> Color.ORANGE;
            case "pink" -> Color.PINK;
            case "red" -> Color.RED;
            case "white" -> Color.WHITE;
            case "yellow" -> Color.YELLOW;
            default -> parseHexStyleColor(value);
        };
    }

    private Color parseHexStyleColor(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0)
                    + hex.charAt(1) + hex.charAt(1)
                    + hex.charAt(2) + hex.charAt(2);
        }
        if (hex.length() != 6) {
            throw new IllegalArgumentException("color must be a named color or #RRGGBB");
        }
        try {
            int rgb = Integer.parseUnsignedInt(hex, 16);
            return new Color(rgb);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("color must be a named color or #RRGGBB");
        }
    }

    private Float parseStyleFloat(String raw, String name) {
        try {
            return Float.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private Double parseStyleDouble(String raw, String name) {
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static final class NodeStats {
        int degree;
        int inDegree;
        int outDegree;
        double weightedDegree;
        final Map<String, Integer> inEdgeKinds = new LinkedHashMap<>();
        final Map<String, Integer> outEdgeKinds = new LinkedHashMap<>();
    }

    String layouts() {
        List<Object> layouts = new ArrayList<>();
        for (LayoutBuilder builder : Lookup.getDefault().lookupAll(LayoutBuilder.class)) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("name", builder.getName());
            Layout layout = builder.buildLayout();
            List<Object> properties = new ArrayList<>();
            for (LayoutProperty property : safeProperties(layout)) {
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("name", property.getCanonicalName());
                prop.put("category", property.getCategory());
                prop.put("displayName", property.getProperty().getDisplayName());
                prop.put("type", property.getProperty().getValueType().getSimpleName());
                properties.add(prop);
            }
            record.put("properties", properties);
            layouts.add(record);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layouts", layouts);
        return Json.object(out);
    }

    String runLayout(String name, int iterations, Map<String, String> parameters) {
        GraphModel model = graphModel();
        if (model == null) {
            return missingGraphJson();
        }
        if (name == null || name.isBlank()) {
            return Json.error("bad_request", "layout name is required");
        }
        LayoutBuilder builder = findLayoutBuilder(name);
        if (builder == null) {
            return Json.error("not_found", "layout not found: " + name);
        }
        int maxIterations = clamp(iterations, 1, 5000, 100);
        Layout layout = builder.buildLayout();
        layout.setGraphModel(model);
        layout.resetPropertiesValues();
        List<Object> appliedParameters;
        try {
            appliedParameters = applyLayoutParameters(layout, parameters);
        } catch (IllegalArgumentException e) {
            return Json.error("bad_request", e.getMessage());
        }
        int ran = 0;
        layout.initAlgo();
        try {
            while (ran < maxIterations && layout.canAlgo()) {
                layout.goAlgo();
                ran++;
            }
        } finally {
            layout.endAlgo();
        }
        Graph graph = model.getGraphVisible();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "run_layout");
        out.put("layout", builder.getName());
        out.put("iterations", ran);
        out.put("parameters", appliedParameters);
        out.put("visibleNodes", graph == null ? 0 : graph.getNodeCount());
        out.put("visibleEdges", graph == null ? 0 : graph.getEdgeCount());
        return Json.object(out);
    }

    private List<Object> applyLayoutParameters(Layout layout, Map<String, String> parameters) {
        List<Object> applied = new ArrayList<>();
        if (parameters == null || parameters.isEmpty()) {
            return applied;
        }
        Map<String, LayoutProperty> properties = layoutPropertiesByAlias(layout);
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            LayoutProperty layoutProperty = properties.get(normalizeIdentifier(key));
            if (layoutProperty == null) {
                throw new IllegalArgumentException("unknown layout property: " + key
                        + ". Use list_layouts to inspect available properties.");
            }
            org.openide.nodes.Node.Property<?> property = layoutProperty.getProperty();
            if (!property.canWrite()) {
                throw new IllegalArgumentException("layout property is read-only: "
                        + layoutPropertyName(layoutProperty));
            }
            Object value = convertLayoutValue(entry.getValue(), property.getValueType(), layoutProperty);
            setLayoutProperty(layoutProperty, value);

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("input", key);
            record.put("name", layoutProperty.getCanonicalName());
            record.put("displayName", property.getDisplayName());
            record.put("type", property.getValueType().getSimpleName());
            record.put("value", value);
            applied.add(record);
        }
        return applied;
    }

    private Map<String, LayoutProperty> layoutPropertiesByAlias(Layout layout) {
        Map<String, LayoutProperty> aliases = new LinkedHashMap<>();
        for (LayoutProperty property : safeProperties(layout)) {
            for (String alias : layoutPropertyAliases(property)) {
                String key = normalizeIdentifier(alias);
                if (!key.isEmpty()) {
                    aliases.putIfAbsent(key, property);
                }
            }
        }
        return aliases;
    }

    private List<String> layoutPropertyAliases(LayoutProperty property) {
        List<String> aliases = new ArrayList<>();
        String canonicalName = property.getCanonicalName();
        aliases.add(canonicalName);
        String withoutNameSuffix = stripNameSuffix(canonicalName);
        aliases.add(withoutNameSuffix);
        int dot = withoutNameSuffix == null ? -1 : withoutNameSuffix.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < withoutNameSuffix.length()) {
            aliases.add(withoutNameSuffix.substring(dot + 1));
        }
        String displayName = property.getProperty().getDisplayName();
        aliases.add(displayName);
        return aliases;
    }

    private String stripNameSuffix(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith(".name") ? value.substring(0, value.length() - ".name".length()) : value;
    }

    private Object convertLayoutValue(String raw, Class<?> type, LayoutProperty property) {
        if (type == String.class) {
            return raw;
        }
        try {
            if (type == Boolean.class || type == Boolean.TYPE) {
                return parseBoolean(raw, property);
            }
            if (type == Integer.class || type == Integer.TYPE) {
                return Integer.valueOf(raw);
            }
            if (type == Long.class || type == Long.TYPE) {
                return Long.valueOf(raw);
            }
            if (type == Float.class || type == Float.TYPE) {
                return Float.valueOf(raw);
            }
            if (type == Double.class || type == Double.TYPE) {
                return Double.valueOf(raw);
            }
            if (type.isEnum()) {
                return enumValue(raw, type, property);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + type.getSimpleName()
                    + " value for " + layoutPropertyName(property) + ": " + raw);
        }
        throw new IllegalArgumentException("unsupported layout property type for "
                + layoutPropertyName(property) + ": " + type.getSimpleName());
    }

    private Boolean parseBoolean(String raw, LayoutProperty property) {
        String value = normalizeIdentifier(raw);
        return switch (value) {
            case "true", "t", "yes", "y", "1", "on" -> Boolean.TRUE;
            case "false", "f", "no", "n", "0", "off" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("invalid Boolean value for "
                    + layoutPropertyName(property) + ": " + raw);
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object enumValue(String raw, Class<?> type, LayoutProperty property) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid " + type.getSimpleName()
                    + " value for " + layoutPropertyName(property) + ": " + raw);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setLayoutProperty(LayoutProperty layoutProperty, Object value) {
        org.openide.nodes.Node.Property property = layoutProperty.getProperty();
        try {
            property.setValue(value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException("cannot set layout property "
                    + layoutPropertyName(layoutProperty) + ": " + e.getMessage(), e);
        }
    }

    private String layoutPropertyName(LayoutProperty property) {
        String canonicalName = property.getCanonicalName();
        if (canonicalName != null && !canonicalName.isBlank()) {
            return canonicalName;
        }
        return property.getProperty().getDisplayName();
    }

    private List<Object> statisticsParameters(Statistics statistic) {
        List<Object> parameters = new ArrayList<>();
        List<Method> setters = statisticsSetters(statistic.getClass());
        setters.sort(Comparator.comparing(Method::getName));
        for (Method setter : setters) {
            Class<?> type = setter.getParameterTypes()[0];
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("name", propertyName(setter.getName(), 3));
            record.put("type", type.getSimpleName());
            Object current = currentStatisticsValue(statistic, setter);
            if (current != null) {
                record.put("value", current);
            }
            parameters.add(record);
        }
        return parameters;
    }

    private List<Object> applyStatisticsParameters(Statistics statistic, Map<String, String> parameters) {
        List<Object> applied = new ArrayList<>();
        if (parameters == null || parameters.isEmpty()) {
            return applied;
        }
        Map<String, Method> setters = statisticsSettersByAlias(statistic.getClass());
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            Method setter = setters.get(normalizeIdentifier(key));
            if (setter == null) {
                throw new IllegalArgumentException("unknown statistic parameter: " + key
                        + ". Use list_statistics to inspect available parameters.");
            }
            Class<?> type = setter.getParameterTypes()[0];
            Object value = convertStatisticsValue(entry.getValue(), type, key);
            try {
                setter.invoke(statistic, value);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalArgumentException("cannot set statistic parameter "
                        + key + ": " + e.getMessage(), e);
            }

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("input", key);
            record.put("name", propertyName(setter.getName(), 3));
            record.put("type", type.getSimpleName());
            record.put("value", value);
            applied.add(record);
        }
        return applied;
    }

    private Map<String, Method> statisticsSettersByAlias(Class<?> type) {
        Map<String, Method> setters = new LinkedHashMap<>();
        for (Method setter : statisticsSetters(type)) {
            String name = propertyName(setter.getName(), 3);
            setters.putIfAbsent(normalizeIdentifier(name), setter);
            setters.putIfAbsent(normalizeIdentifier(setter.getName()), setter);
        }
        return setters;
    }

    private List<Method> statisticsSetters(Class<?> type) {
        List<Method> setters = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && method.getName().startsWith("set")
                    && method.getParameterCount() == 1
                    && method.getReturnType() == Void.TYPE) {
                Class<?> parameterType = method.getParameterTypes()[0];
                if (isSupportedParameterType(parameterType)) {
                    setters.add(method);
                }
            }
        }
        return setters;
    }

    private boolean isSupportedParameterType(Class<?> type) {
        return type == String.class
                || type == Boolean.class || type == Boolean.TYPE
                || type == Integer.class || type == Integer.TYPE
                || type == Long.class || type == Long.TYPE
                || type == Float.class || type == Float.TYPE
                || type == Double.class || type == Double.TYPE
                || type.isEnum();
    }

    private Object convertStatisticsValue(String raw, Class<?> type, String name) {
        if (type == String.class) {
            return raw;
        }
        try {
            if (type == Boolean.class || type == Boolean.TYPE) {
                return parseBooleanValue(raw, name);
            }
            if (type == Integer.class || type == Integer.TYPE) {
                return Integer.valueOf(raw);
            }
            if (type == Long.class || type == Long.TYPE) {
                return Long.valueOf(raw);
            }
            if (type == Float.class || type == Float.TYPE) {
                return Float.valueOf(raw);
            }
            if (type == Double.class || type == Double.TYPE) {
                return Double.valueOf(raw);
            }
            if (type.isEnum()) {
                return enumValue(raw, type, name);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + type.getSimpleName()
                    + " value for " + name + ": " + raw);
        }
        throw new IllegalArgumentException("unsupported statistic parameter type for "
                + name + ": " + type.getSimpleName());
    }

    private Boolean parseBooleanValue(String raw, String name) {
        String value = normalizeIdentifier(raw);
        return switch (value) {
            case "true", "t", "yes", "y", "1", "on" -> Boolean.TRUE;
            case "false", "f", "no", "n", "0", "off" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("invalid Boolean value for " + name + ": " + raw);
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object enumValue(String raw, Class<?> type, String name) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid " + type.getSimpleName()
                    + " value for " + name + ": " + raw);
        }
    }

    private Object currentStatisticsValue(Statistics statistic, Method setter) {
        String suffix = setter.getName().substring(3);
        for (String getterName : List.of("get" + suffix, "is" + suffix)) {
            try {
                Method getter = statistic.getClass().getMethod(getterName);
                if (getter.getParameterCount() == 0 && isJsonSafeScalarType(getter.getReturnType())) {
                    return getter.invoke(statistic);
                }
            } catch (NoSuchMethodException ignored) {
                // Try the next JavaBean getter form.
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> statisticsResults(Statistics statistic) {
        Map<String, Object> results = new LinkedHashMap<>();
        List<Method> getters = new ArrayList<>();
        for (Method method : statistic.getClass().getMethods()) {
            if (!Modifier.isPublic(method.getModifiers())
                    || method.getParameterCount() != 0
                    || method.getReturnType() == Void.TYPE
                    || method.getDeclaringClass() == Object.class
                    || method.getName().toLowerCase(Locale.ROOT).contains("report")) {
                continue;
            }
            if (method.getName().startsWith("get") || method.getName().startsWith("is")) {
                getters.add(method);
            }
        }
        getters.sort(Comparator.comparing(Method::getName));
        for (Method getter : getters) {
            try {
                Object value = getter.invoke(statistic);
                Object safe = jsonSafeResult(value);
                if (safe != null) {
                    results.put(getterPropertyName(getter), safe);
                }
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                // Skip expensive or stateful getters that are not safe to call after execution.
            }
        }
        return results;
    }

    private Object jsonSafeResult(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            if (length <= 50) {
                return value;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("type", valueClass.getComponentType().getSimpleName() + "[]");
            summary.put("length", length);
            return summary;
        }
        return null;
    }

    private boolean isJsonSafeScalarType(Class<?> type) {
        return type == String.class
                || type == Boolean.class || type == Boolean.TYPE
                || Number.class.isAssignableFrom(type)
                || type == Integer.TYPE || type == Long.TYPE
                || type == Float.TYPE || type == Double.TYPE;
    }

    private String getterPropertyName(Method getter) {
        if (getter.getName().startsWith("get")) {
            return propertyName(getter.getName(), 3);
        }
        return propertyName(getter.getName(), 2);
    }

    private String propertyName(String methodName, int prefixLength) {
        String suffix = methodName.length() <= prefixLength ? "" : methodName.substring(prefixLength);
        if (suffix.isEmpty()) {
            return "";
        }
        return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
    }

    private StatisticsBuilder findStatisticsBuilder(String name) {
        String requested = normalizeIdentifier(name);
        StatisticsBuilder fuzzy = null;
        for (StatisticsBuilder builder : Lookup.getDefault().lookupAll(StatisticsBuilder.class)) {
            for (String alias : statisticsBuilderAliases(builder)) {
                String normalized = normalizeIdentifier(alias);
                if (normalized.equals(requested)) {
                    return builder;
                }
                if (fuzzy == null && normalized.contains(requested)) {
                    fuzzy = builder;
                }
            }
        }
        return fuzzy;
    }

    private List<String> statisticsBuilderAliases(StatisticsBuilder builder) {
        List<String> aliases = new ArrayList<>();
        aliases.add(builder.getName());
        aliases.add(builder.getClass().getSimpleName());
        aliases.add(stripSuffix(builder.getClass().getSimpleName(), "Builder"));
        aliases.add(builder.getStatisticsClass().getSimpleName());
        aliases.add(builder.getStatisticsClass().getName());
        return aliases;
    }

    private String stripSuffix(String value, String suffix) {
        if (value == null || suffix == null || !value.endsWith(suffix)) {
            return value == null ? "" : value;
        }
        return value.substring(0, value.length() - suffix.length());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private File expandPath(String path) {
        String p = path.trim();
        if (p.equals("~")) {
            return new File(System.getProperty("user.home"));
        }
        if (p.startsWith("~/")) {
            return new File(System.getProperty("user.home"), p.substring(2));
        }
        return new File(p);
    }

    private String colorByAttribute(String elementType, String attribute) {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        if (attribute == null || attribute.isBlank()) {
            return Json.error("bad_request", "attribute is required");
        }
        Map<String, Color> colors = new LinkedHashMap<>();
        int styled = 0;
        if ("edge".equals(elementType)) {
            for (Edge edge : graph.getEdges()) {
                Object value = attributeValue(edge, attribute, graph);
                Color color = partitionColor(colors, value);
                edge.setColor(color);
                edge.setAlpha(0.65f);
                styled++;
            }
        } else {
            for (Node node : graph.getNodes()) {
                Object value = attributeValue(node, attribute, graph);
                Color color = partitionColor(colors, value);
                node.setColor(color);
                node.setAlpha(0.95f);
                styled++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "edge".equals(elementType) ? "partition_edges" : "partition_nodes");
        out.put("element", "edge".equals(elementType) ? "edge" : "node");
        out.put("attribute", attribute);
        out.put("styled", styled);
        out.put("partitions", colors.keySet());
        return Json.object(out);
    }

    private List<Object> columns(Table table) {
        List<Object> columns = new ArrayList<>();
        for (Column column : columnsSnapshot(table)) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", column.getId());
            record.put("title", column.getTitle());
            record.put("index", column.getIndex());
            record.put("type", column.getTypeClass().getSimpleName());
            record.put("origin", String.valueOf(column.getOrigin()));
            record.put("property", column.isProperty());
            record.put("number", column.isNumber());
            record.put("dynamic", column.isDynamic());
            record.put("readOnly", column.isReadOnly());
            columns.add(record);
        }
        return columns;
    }

    private List<Column> columnsSnapshot(Table table) {
        List<Column> columns = new ArrayList<>();
        for (Column column : table) {
            columns.add(column);
        }
        return columns;
    }

    private List<Column> attributeColumnsSnapshot(Element element) {
        List<Column> columns = new ArrayList<>();
        for (Column column : element.getAttributeColumns()) {
            columns.add(column);
        }
        return columns;
    }

    private Map<String, Object> nodeRecord(Graph graph, Node node, boolean includeAttributes) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", node.getId());
        record.put("label", node.getLabel());
        record.put("degree", graph.getDegree(node));
        record.put("x", node.x());
        record.put("y", node.y());
        record.put("size", node.size());
        record.put("fixed", node.isFixed());
        record.put("color", colorRecord(node.getColor()));
        if (includeAttributes) {
            record.put("attributes", attributesRecord(node));
        }
        return record;
    }

    private Map<String, Object> edgeRecord(Edge edge, boolean includeAttributes) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", edge.getId());
        record.put("source", edge.getSource().getId());
        record.put("target", edge.getTarget().getId());
        record.put("label", edge.getLabel());
        record.put("directed", edge.isDirected());
        record.put("weight", edge.getWeight());
        record.put("type", edge.getTypeLabel());
        record.put("color", colorRecord(edge.getColor()));
        if (includeAttributes) {
            record.put("attributes", attributesRecord(edge));
        }
        return record;
    }

    private Map<String, Object> attributesRecord(Element element) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (Column column : attributeColumnsSnapshot(element)) {
            Object value = element.getAttribute(column);
            if (value != null) {
                attrs.put(column.getId(), value);
            }
        }
        return attrs;
    }

    private Map<String, Object> colorRecord(Color color) {
        Map<String, Object> record = new LinkedHashMap<>();
        if (color == null) {
            record.put("r", 0);
            record.put("g", 0);
            record.put("b", 0);
            record.put("a", 0);
        } else {
            record.put("r", color.getRed());
            record.put("g", color.getGreen());
            record.put("b", color.getBlue());
            record.put("a", color.getAlpha());
        }
        return record;
    }

    private boolean matchesNode(Graph graph, Node node, String query) {
        if (contains(node.getId(), query) || contains(node.getLabel(), query)) {
            return true;
        }
        if (String.valueOf(graph.getDegree(node)).contains(query)) {
            return true;
        }
        return matchesAttributes(node, query);
    }

    private boolean matchesEdge(Edge edge, String query) {
        if (contains(edge.getId(), query)
                || contains(edge.getLabel(), query)
                || contains(edge.getSource().getId(), query)
                || contains(edge.getTarget().getId(), query)
                || contains(edge.getTypeLabel(), query)) {
            return true;
        }
        return matchesAttributes(edge, query);
    }

    private boolean matchesAttributes(Element element, String query) {
        for (Column column : attributeColumnsSnapshot(element)) {
            Object value = element.getAttribute(column);
            if (contains(column.getId(), query) || contains(value, query)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesFilter(Element element, String attribute, String op, String value, Graph graph) {
        Object raw = attributeValue(element, attribute, graph);
        return matchesFilterValue(raw, op, value);
    }

    private boolean matchesFilterValue(Object raw, String op, String value) {
        String actual = raw == null ? "" : String.valueOf(raw);
        String expected = value == null ? "" : value;
        return switch (op) {
            case "exists" -> raw != null && !actual.isBlank();
            case "missing" -> raw == null || actual.isBlank();
            case "neq" -> !actual.equals(expected);
            case "contains" -> normalize(actual).contains(normalize(expected));
            case "in" -> valueInSet(raw, expected);
            case "not_in" -> !valueInSet(raw, expected);
            case "gt" -> compareNumber(raw, expected) > 0;
            case "gte" -> compareNumber(raw, expected) >= 0;
            case "lt" -> compareNumber(raw, expected) < 0;
            case "lte" -> compareNumber(raw, expected) <= 0;
            default -> actual.equals(expected);
        };
    }

    private boolean valueInSet(Object raw, String expected) {
        String actual = raw == null ? "" : String.valueOf(raw);
        for (String candidate : splitSet(expected)) {
            if (actual.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private double compareNumber(Object actual, String expected) {
        Double left = number(actual);
        Double right = number(expected);
        if (left == null || right == null) {
            return Double.NaN;
        }
        return Double.compare(left, right);
    }

    private Object attributeValue(Element element, String attribute, Graph graph) {
        String attr = attribute == null ? "" : attribute;
        if ("id".equals(attr)) {
            return element.getId();
        }
        if ("label".equals(attr)) {
            return element.getLabel();
        }
        if (element instanceof Node node && "degree".equals(attr)) {
            return graph.getDegree(node);
        }
        if (element instanceof Edge edge) {
            if ("source".equals(attr)) {
                return edge.getSource().getId();
            }
            if ("target".equals(attr)) {
                return edge.getTarget().getId();
            }
            if ("weight".equals(attr)) {
                return edge.getWeight();
            }
            if ("type".equals(attr)) {
                return edge.getTypeLabel();
            }
        }
        return columnAttributeValue(element, attr);
    }

    private Object columnAttributeValue(Element element, String attr) {
        Column column = attributeColumn(element, attr);
        return column == null ? null : element.getAttribute(column);
    }

    private Object attributeValue(Element element, String attribute, Graph graph, Column column) {
        String attr = attribute == null ? "" : attribute;
        if ("id".equals(attr)) {
            return element.getId();
        }
        if ("label".equals(attr)) {
            return element.getLabel();
        }
        if (element instanceof Node node && "degree".equals(attr)) {
            return graph.getDegree(node);
        }
        if (element instanceof Edge edge) {
            if ("source".equals(attr)) {
                return edge.getSource().getId();
            }
            if ("target".equals(attr)) {
                return edge.getTarget().getId();
            }
            if ("weight".equals(attr)) {
                return edge.getWeight();
            }
            if ("type".equals(attr)) {
                return edge.getTypeLabel();
            }
        }
        return column == null ? null : element.getAttribute(column);
    }

    private Column attributeColumn(Element element, String attr) {
        List<Column> columns = attributeColumnsSnapshot(element);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(attr);
        candidates.addAll(attributeAliases(element, attr));
        for (String candidate : candidates) {
            String wanted = normalize(candidate);
            for (Column column : columns) {
                String id = normalize(column.getId());
                String title = normalize(column.getTitle());
                if (id.equals(wanted) || title.equals(wanted)) {
                    return column;
                }
            }
        }
        String wanted = normalize(attr);
        for (Column column : columns) {
            String id = normalize(column.getId());
            String title = normalize(column.getTitle());
            if (id.equals(wanted) || title.equals(wanted) || id.endsWith("_" + wanted)) {
                return column;
            }
        }
        return null;
    }

    private Column attributeColumn(Table table, String attr, String elementType) {
        List<Column> columns = columnsSnapshot(table);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(attr);
        candidates.addAll(attributeAliases(elementType, attr));
        for (String candidate : candidates) {
            String wanted = normalize(candidate);
            for (Column column : columns) {
                String id = normalize(column.getId());
                String title = normalize(column.getTitle());
                if (id.equals(wanted) || title.equals(wanted)) {
                    return column;
                }
            }
        }
        String wanted = normalize(attr);
        for (Column column : columns) {
            String id = normalize(column.getId());
            String title = normalize(column.getTitle());
            if (id.equals(wanted) || title.equals(wanted) || id.endsWith("_" + wanted)) {
                return column;
            }
        }
        return null;
    }

    private List<String> attributeAliases(Element element, String attr) {
        return attributeAliases(element instanceof Edge ? "edge" : "node", attr);
    }

    private List<String> attributeAliases(String elementType, String attr) {
        List<String> aliases = new ArrayList<>();
        if ("node".equals(elementType)) {
            aliases.add("n_" + attr);
        }
        if ("edge".equals(elementType)) {
            aliases.add("e_" + attr);
            if ("relation".equals(attr)) {
                aliases.add("e_kind");
                aliases.add("kind");
            }
        }
        return aliases;
    }

    private Double numericNodeValue(Graph graph, Node node, String attribute) {
        if ("degree".equals(attribute)) {
            return (double) graph.getDegree(node);
        }
        if ("size".equals(attribute)) {
            return (double) node.size();
        }
        return number(attributeValue(node, attribute, graph));
    }

    private Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.valueOf(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Color partitionColor(Map<String, Color> colors, Object value) {
        String key = value == null ? "(missing)" : String.valueOf(value);
        return colors.computeIfAbsent(key, k -> PALETTE[colors.size() % PALETTE.length]);
    }

    private Color colorFor(Object value) {
        return PALETTE[Math.floorMod(String.valueOf(value).hashCode(), PALETTE.length)];
    }

    private Color edgeColorFor(String relation) {
        return colorFor(relation);
    }

    private Object firstPresentAttribute(Element element, Graph graph, List<String> attributes) {
        for (String attribute : attributes) {
            Object value = attributeValue(element, attribute, graph);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object valueOr(Object value, Object fallback) {
        return value == null ? fallback : value;
    }

    private LayoutBuilder findLayoutBuilder(String name) {
        String requested = normalize(name);
        LayoutBuilder fuzzy = null;
        for (LayoutBuilder builder : Lookup.getDefault().lookupAll(LayoutBuilder.class)) {
            String current = normalize(builder.getName());
            if (current.equals(requested)) {
                return builder;
            }
            if (fuzzy == null && current.contains(requested)) {
                fuzzy = builder;
            }
        }
        return fuzzy;
    }

    private LayoutProperty[] safeProperties(Layout layout) {
        try {
            LayoutProperty[] properties = layout.getProperties();
            return properties == null ? new LayoutProperty[0] : properties;
        } catch (RuntimeException e) {
            return new LayoutProperty[0];
        }
    }

    private Graph visibleGraph() {
        GraphModel model = graphModel();
        return model == null ? null : model.getGraphVisible();
    }

    private boolean isFilteredView(GraphModel model) {
        if (model == null) {
            return false;
        }
        GraphView view = model.getVisibleView();
        return view != null && !view.isMainView();
    }

    private void destroyFilterView(GraphModel model, GraphView view) {
        if (model == null || view == null || view.isDestroyed() || view.isMainView()) {
            return;
        }
        if (view.getGraphModel() == model) {
            model.destroyView(view);
        }
    }

    private GraphModel graphModel() {
        GraphController controller = Lookup.getDefault().lookup(GraphController.class);
        if (controller == null) {
            return null;
        }
        return controller.getGraphModel();
    }

    private String missingGraphReason() {
        GraphController controller = Lookup.getDefault().lookup(GraphController.class);
        if (controller == null) {
            return "graph_controller_missing";
        }
        GraphModel model = controller.getGraphModel();
        if (model == null) {
            return "no_active_graph_model";
        }
        if (model.getGraphVisible() == null) {
            return "no_visible_graph";
        }
        return "unknown";
    }

    private String missingGraphJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", missingGraphReason());
        return Json.object(out);
    }

    private boolean contains(Object value, String query) {
        return value != null && normalize(value).contains(query);
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private String normalizeIdentifier(Object value) {
        if (value == null) {
            return "";
        }
        String raw = String.valueOf(value);
        StringBuilder normalized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                normalized.append(Character.toLowerCase(ch));
            }
        }
        return normalized.toString();
    }

    private int clamp(int value, int min, int max, int defaultValue) {
        int candidate = value <= 0 ? defaultValue : value;
        return Math.max(min, Math.min(max, candidate));
    }
}
