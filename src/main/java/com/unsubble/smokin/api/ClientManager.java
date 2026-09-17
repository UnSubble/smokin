package com.unsubble.smokin.api;

import com.unsubble.smokin.executor.ClientCtl;
import com.unsubble.smokin.executor.ClientService;
import com.unsubble.smokin.model.Protocol;
import com.unsubble.smokin.model.Version;
import com.unsubble.smokin.parser.HttpRequestParser;


public class ClientManager {

    private static volatile ClientManager manager;
    private static final Object LOCK = new Object();
    private final HttpRequestParser parser;
    private final ClientServiceFactory clientServiceFactory;

    private ClientManager() {
        parser = new HttpRequestParser();
        clientServiceFactory = new ClientServiceFactory();
    }

    public HttpRequestParser getParser() {
        return parser;
    }

    public ClientService getDefaultService(Protocol protocol, String host, int port) {
        return clientServiceFactory.create(protocol, host, port);
    }

    public ClientService getService(Version version, Protocol protocol, String host, int port) {
        return clientServiceFactory.create(version, protocol, host, port);
    }

    public ClientCtl getController(ClientService clientService) {
        return clientService.createController();
    }

    public ClientCtl getController(Version version, Protocol protocol, String host, int port) {
        return getService(version, protocol, host, port).createController();
    }

    public static ClientManager getInstance() {
        ClientManager instance = manager;

        if (instance == null) {
            throw new IllegalStateException("ClientManager is not initialized yet");
        }

        return instance;
    }

    public static void initialize() {
        if (manager != null) {
            throw new IllegalStateException("ClientManager is already initialized");
        }

        synchronized (LOCK) {
            if (manager != null) {
                throw new IllegalStateException("ClientManager is already initialized");
            }

            manager = new ClientManager();
        }
    }
}