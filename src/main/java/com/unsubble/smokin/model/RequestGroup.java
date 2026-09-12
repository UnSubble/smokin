package com.unsubble.smokin.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record RequestGroup(String name, List<Request> requests) {

    private static long groupId = 1L;

    public RequestGroup(List<Request> requests) {
        this("default-" + groupId, requests);
    }

    public RequestGroup(String name, List<Request> requests) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.requests = requests != null ? List.copyOf(requests) : List.of();
        groupId++;
    }

    public static long getGroupId() {
        return groupId;
    }
}
