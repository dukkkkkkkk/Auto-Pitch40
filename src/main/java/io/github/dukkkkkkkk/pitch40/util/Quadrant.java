package io.github.dukkkkkkkk.pitch40.util;

import org.jspecify.annotations.Nullable;

/**
 * A travel direction written as an X sign then a Z sign, like "++" or "0-".
 *
 * <p>Each character is {@code +}, {@code -} or {@code 0}: "++" heads toward +X +Z (a diagonal), "+0" straight
 * along +X, "0-" straight along -Z. "00" is not a direction.
 */
public final class Quadrant {
    private Quadrant() {}

    /** Normalises a quadrant like "++" or "0-", or returns null if it isn't one. */
    public static @Nullable String parse(@Nullable String input) {
        if (input == null) return null;
        String quad = input.trim();
        if (quad.length() != 2) return null;
        int dx = sign(quad.charAt(0));
        int dz = sign(quad.charAt(1));
        if (dx == Integer.MIN_VALUE || dz == Integer.MIN_VALUE || (dx == 0 && dz == 0)) return null;
        return quad;
    }

    /**
     * Minecraft yaw that faces the quadrant: 0 is +Z (south), -90 is +X (east), 90 is -X (west), 180 is -Z (north).
     * Only call with a value {@link #parse} accepted.
     */
    public static float yaw(String quad) {
        int dx = sign(quad.charAt(0));
        int dz = sign(quad.charAt(1));
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Plain-English direction, e.g. "+X +Z (south-east)". */
    public static String describe(String quad) {
        int dx = sign(quad.charAt(0));
        int dz = sign(quad.charAt(1));
        String axes = (dx != 0 ? (dx > 0 ? "+X" : "-X") : "") + (dx != 0 && dz != 0 ? " " : "") + (dz != 0 ? (dz > 0 ? "+Z" : "-Z") : "");
        String compass = (dz > 0 ? "south" : dz < 0 ? "north" : "") + (dx != 0 && dz != 0 ? "-" : "") + (dx > 0 ? "east" : dx < 0 ? "west" : "");
        return axes + " (" + compass + ")";
    }

    private static int sign(char c) {
        return switch (c) {
            case '+' -> 1;
            case '-' -> -1;
            case '0' -> 0;
            default -> Integer.MIN_VALUE;
        };
    }
}
