package org.kgatilin.gephi.mcp;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.function.Predicate;
import org.gephi.graph.api.Column;
import org.gephi.graph.api.Edge;
import org.gephi.graph.api.Element;
import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.GraphView;
import org.gephi.graph.api.Node;
import org.gephi.graph.api.Table;
import org.gephi.layout.spi.Layout;
import org.gephi.layout.spi.LayoutBuilder;
import org.gephi.layout.spi.LayoutProperty;
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

    private GraphView filterView;

    GraphSummary graphSummary() {
        Graph graph = visibleGraph();
        if (graph == null) {
            return GraphSummary.empty(missingGraphReason());
        }
        return new GraphSummary(true, "", graph.getNodeCount(), graph.getEdgeCount(), graph.isDirected());
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
            for (Node node : graph.getNodes()) {
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
            for (Edge edge : graph.getEdges()) {
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
                for (Edge edge : graph.getEdges(current)) {
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

    String applyCodeGraphPreset() {
        Graph graph = visibleGraph();
        if (graph == null) {
            return missingGraphJson();
        }
        int styledNodes = 0;
        int styledEdges = 0;
        for (Node node : graph.getNodes()) {
            Object foreign = attributeValue(node, "foreign", graph);
            if (foreign instanceof Boolean b && b) {
                node.setColor(new Color(170, 170, 170));
                node.setAlpha(0.45f);
            } else {
                node.setColor(colorFor(valueOr(attributeValue(node, "kind", graph), "node")));
                node.setAlpha(0.95f);
            }
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
        out.put("tool", "apply_code_graph_preset");
        out.put("styledNodes", styledNodes);
        out.put("styledEdges", styledEdges);
        return Json.object(out);
    }

    String filter(String elementType, String attribute, String op, String value) {
        GraphModel model = graphModel();
        if (model == null) {
            return missingGraphJson();
        }
        Graph base = model.getGraph();
        if (base == null) {
            return missingGraphJson();
        }
        if (attribute == null || attribute.isBlank()) {
            return Json.error("bad_request", "attribute is required");
        }
        String operator = op == null || op.isBlank() ? "eq" : op;
        Predicate<Node> nodePredicate = n -> true;
        Predicate<Edge> edgePredicate = e -> true;
        if ("edge".equals(elementType)) {
            Column column = attributeColumn(model.getEdgeTable(), attribute, "edge");
            edgePredicate = e -> matchesFilterValue(attributeValue(e, attribute, base, column), operator, value);
        } else {
            Column column = attributeColumn(model.getNodeTable(), attribute, "node");
            nodePredicate = n -> matchesFilterValue(attributeValue(n, attribute, base, column), operator, value);
        }
        destroyFilterView(model);
        filterView = model.createView(nodePredicate, edgePredicate);
        model.setVisibleView(filterView);

        Graph visible = model.getGraphVisible();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "filter_graph");
        out.put("element", "edge".equals(elementType) ? "edge" : "node");
        out.put("attribute", attribute);
        out.put("op", operator);
        out.put("value", value == null ? "" : value);
        out.put("visibleNodes", visible == null ? 0 : visible.getNodeCount());
        out.put("visibleEdges", visible == null ? 0 : visible.getEdgeCount());
        return Json.object(out);
    }

    String resetFilters() {
        GraphModel model = graphModel();
        if (model == null) {
            return missingGraphJson();
        }
        destroyFilterView(model);
        Graph graph = model.getGraph();
        if (graph != null) {
            model.setVisibleView(graph.getView());
        }
        Graph visible = model.getGraphVisible();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tool", "reset_filters");
        out.put("visibleNodes", visible == null ? 0 : visible.getNodeCount());
        out.put("visibleEdges", visible == null ? 0 : visible.getEdgeCount());
        return Json.object(out);
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

    String runLayout(String name, int iterations) {
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
        out.put("visibleNodes", graph == null ? 0 : graph.getNodeCount());
        out.put("visibleEdges", graph == null ? 0 : graph.getEdgeCount());
        return Json.object(out);
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
            case "gt" -> compareNumber(raw, expected) > 0;
            case "gte" -> compareNumber(raw, expected) >= 0;
            case "lt" -> compareNumber(raw, expected) < 0;
            case "lte" -> compareNumber(raw, expected) <= 0;
            default -> actual.equals(expected);
        };
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
            if ("archmotif_id".equals(attr)) {
                aliases.add("n_id");
            }
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
        String key = normalize(relation);
        if (key.contains("call")) {
            return new Color(31, 119, 180);
        }
        if (key.contains("contain")) {
            return new Color(44, 160, 44);
        }
        if (key.contains("depend") || key.contains("import")) {
            return new Color(214, 39, 40);
        }
        return colorFor(relation);
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

    private void destroyFilterView(GraphModel model) {
        if (filterView != null && !filterView.isDestroyed() && !filterView.isMainView()) {
            model.destroyView(filterView);
        }
        filterView = null;
    }

    private Graph visibleGraph() {
        GraphModel model = graphModel();
        return model == null ? null : model.getGraphVisible();
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

    private int clamp(int value, int min, int max, int defaultValue) {
        int candidate = value <= 0 ? defaultValue : value;
        return Math.max(min, Math.min(max, candidate));
    }
}
