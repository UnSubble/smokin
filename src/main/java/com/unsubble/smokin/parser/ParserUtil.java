package com.unsubble.smokin.parser;

public class ParserUtil {

    public static String trimOWS(String value) {
        int start = 0;
        int end = value.length();

        while (start < end && isOWS(value.charAt(start))) {
            start++;
        }

        while (end > start && isOWS(value.charAt(end - 1))) {
            end--;
        }

        return value.substring(start, end);
    }

    public static boolean isTokenChar(char c) {
        if (c <= 32 || c == 127) {
            return false;
        }

        return switch (c) {
            case '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}' -> false;
            default -> c < 128;
        };
    }

    public static boolean isOWS(char c) {
        return c == ' ' || c == '\t';
    }
}
