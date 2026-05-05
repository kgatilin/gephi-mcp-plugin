package org.kgatilin.gephi.mcp;

import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphModel;
import org.openide.util.Lookup;

final class GephiFacade {
    GraphSummary graphSummary() {
        GraphController controller = Lookup.getDefault().lookup(GraphController.class);
        if (controller == null) {
            return GraphSummary.empty("graph_controller_missing");
        }
        GraphModel model = controller.getGraphModel();
        if (model == null) {
            return GraphSummary.empty("no_active_graph_model");
        }
        Graph graph = model.getGraphVisible();
        if (graph == null) {
            return GraphSummary.empty("no_visible_graph");
        }
        return new GraphSummary(true, "", graph.getNodeCount(), graph.getEdgeCount(), graph.isDirected());
    }
}

