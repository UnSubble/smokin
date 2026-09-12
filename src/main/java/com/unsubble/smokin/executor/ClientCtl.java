package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.RequestGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class ClientCtl {

    private final ClientService clientService;
    private final List<RequestGroup> groups = new ArrayList<>();
    private boolean async = false;
    private boolean synchronizeLastBytes = false;
    private int threadCount = -1;
    private Supplier<HttpClient> clientSupplier;

    public ClientCtl(ClientService clientService) {
        this.clientService = Objects.requireNonNull(clientService, "clientService must not be null");
    }

    public ClientCtl addGroup(List<Request> requests) {
        return addGroup(new RequestGroup(requests));
    }

    public ClientCtl addGroup(String name, List<Request> requests) {
        return addGroup(new RequestGroup(name, requests));
    }

    public ClientCtl addGroup(RequestGroup group) {
        this.groups.add(Objects.requireNonNull(group, "group must not be null"));
        return this;
    }

    public ClientCtl activateAsync() {
        this.async = true;
        return this;
    }

    public ClientCtl synchronizeLastBytes() {
        this.synchronizeLastBytes = true;
        return this;
    }

    public ClientCtl threadCount(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be greater than 0");
        }
        this.threadCount = threadCount;
        return this;
    }

    public ClientCtl clientSupplier(Supplier<HttpClient> clientSupplier) {
        this.clientSupplier = clientSupplier;
        return this;
    }

    public ClientService clientService() {
        return clientService;
    }

    public List<RequestGroup> groups() {
        return List.copyOf(groups);
    }

    public boolean isAsync() {
        return async;
    }

    public boolean isSynchronizeLastBytes() {
        return synchronizeLastBytes;
    }

    public int threadCount() {
        return threadCount;
    }

    public ExecutionPlan buildPlan() {
        return new ExecutionPlan(groups, async, synchronizeLastBytes, threadCount, clientSupplier);
    }

    public ExecutionResult execute() {
        return clientService.execute(buildPlan());
    }
}
