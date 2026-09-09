package com.unsubble.smokin.model;

import java.util.Objects;

public record Header(String name, String value) {

    public Header(String name, String value) {
        this.name = Objects.requireNonNull(name);
        this.value = Objects.requireNonNull(value);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Header(String name1, String value1)))
            return false;
        return Objects.equals(name(), name1) && Objects.equals(value(), value1);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name(), value());
    }
}