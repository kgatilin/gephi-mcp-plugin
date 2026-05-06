package org.kgatilin.gephi.mcp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openide.modules.ModuleInstall;

public class Installer extends ModuleInstall {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(Installer.class.getName());
    private static final String SERVER_PROPERTY = "org.kgatilin.gephi.mcp.ControlServer.instance";

    private transient ControlServer server;

    @Override
    public void restored() {
        int port = Integer.getInteger("gephi.mcp.port", 8765);
        stopPreviousServer();
        server = new ControlServer(port, new GephiFacade());
        try {
            server.start();
            System.getProperties().put(SERVER_PROPERTY, server);
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

    private void stopPreviousServer() {
        Object previous = System.getProperties().remove(SERVER_PROPERTY);
        if (previous == null) {
            return;
        }
        try {
            Method stop = previous.getClass().getDeclaredMethod("stop");
            stop.setAccessible(true);
            stop.invoke(previous);
            LOG.log(Level.INFO, "Stopped previous Gephi MCP control server before module reload");
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            LOG.log(Level.WARNING, "Failed to stop previous Gephi MCP control server", e);
        }
    }

    private synchronized void stopServer() {
        if (server != null) {
            server.stop();
            if (System.getProperties().get(SERVER_PROPERTY) == server) {
                System.getProperties().remove(SERVER_PROPERTY);
            }
            server = null;
        }
    }
}
