package org.kgatilin.gephi.mcp;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.openide.modules.ModuleInstall;

public class Installer extends ModuleInstall {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(Installer.class.getName());

    private transient ControlServer server;

    @Override
    public void restored() {
        int port = Integer.getInteger("gephi.mcp.port", 8765);
        server = new ControlServer(port, new GephiFacade());
        try {
            server.start();
            LOG.log(Level.INFO, "Gephi MCP control server started on 127.0.0.1:{0}", port);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Failed to start Gephi MCP control server", e);
        }
    }

    @Override
    public void close() {
        stopServer();
    }

    @Override
    public boolean closing() {
        stopServer();
        return true;
    }

    @Override
    public void uninstalled() {
        stopServer();
    }

    private synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
