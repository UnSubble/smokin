package com.unsubble.smokin.model;

import java.util.Locale;
import java.util.Objects;

public enum Version {
    UNKNOWN(""),
    HTTP_1_1("1_1");

    private final String version;

    Version(String version) {
        this.version = version;
    }

    public static Version fromString(String versionStr) {
        Objects.requireNonNull(versionStr);
        versionStr = versionStr.toUpperCase(Locale.ENGLISH);
        for (Version ver : values()) {
            if (ver != UNKNOWN && versionStr.equals(ver.version))
                return ver;
        }
        return UNKNOWN;
    }
}
