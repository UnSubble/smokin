package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.RequestGroup;

import java.util.List;
import java.util.function.Supplier;

public class ExecutionPlan {

    private final List<RequestGroup> groups;
    private final boolean async;
    private final boolean synchronizeLastBytes;
    private final int threadCount;
    private final Supplier<? extends Client> clientSupplier;
    private final int size;

    public ExecutionPlan(List<RequestGroup> groups, boolean async,
                         boolean synchronizeLastBytes, int threadCount,
                         Supplier<? extends Client> clientSupplier) {
        this.groups = groups != null ? List.copyOf(groups) : List.of();
        this.async = async;
        this.synchronizeLastBytes = synchronizeLastBytes;
        this.threadCount = threadCount;
        this.clientSupplier = clientSupplier;
        size = this.groups.stream()
                .mapToInt(g -> g.requests().size())
                .sum();
    }

    public int totalRequestCount() {
        return size;
    }

    public List<Request> allRequests() {
        return groups.stream()
                .flatMap(g -> g.requests().stream())
                .toList();
    }

    public List<RequestGroup> getGroups() {
        return groups;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean isSynchronizeLastBytes() {
        return synchronizeLastBytes;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public Supplier<? extends Client> getClientSupplier() {
        return clientSupplier;
    }
}
