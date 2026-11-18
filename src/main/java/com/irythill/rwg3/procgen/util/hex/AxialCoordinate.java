package com.irythill.rwg3.procgen.util.hex;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable axial hex coordinate. */
public final class AxialCoordinate implements Comparable<AxialCoordinate> {

    private static final AxialCoordinate[] DIRECTIONS = { new AxialCoordinate(1, 0), new AxialCoordinate(1, -1),
        new AxialCoordinate(0, -1), new AxialCoordinate(-1, 0), new AxialCoordinate(-1, 1), new AxialCoordinate(0, 1) };

    public final int q;
    public final int r;

    public AxialCoordinate(int q, int r) {
        this.q = q;
        this.r = r;
    }

    public Vec2 toWorld() {
        return new Vec2(q + r, (q - r) * Math.sqrt(3.0) / 3.0);
    }

    public static AxialCoordinate fromWorld(Vec2 point) {
        long packed = packedFromWorld(point.x, point.y);
        return new AxialCoordinate((int) packed, (int) (packed >>> 32));
    }

    public static long packedFromWorld(double x, double y) {
        double denominator = 2.0 * Math.sqrt(3.0) / 3.0;
        double q = x * 0.5 + y / denominator;
        double r = x * 0.5 - y / denominator;
        double s = -q - r;
        int roundedQ = (int) Math.round(q);
        int roundedR = (int) Math.round(r);
        int roundedS = (int) Math.round(s);
        double errorQ = Math.abs(roundedQ - q);
        double errorR = Math.abs(roundedR - r);
        double errorS = Math.abs(roundedS - s);
        if (errorQ > errorR && errorQ > errorS) roundedQ = -roundedR - roundedS;
        else if (errorR > errorS) roundedR = -roundedQ - roundedS;
        return (long) roundedQ & 0xffffffffL | (long) roundedR << 32;
    }

    public AxialCoordinate add(AxialCoordinate other) {
        return new AxialCoordinate(q + other.q, r + other.r);
    }

    public AxialCoordinate neighbor(int direction) {
        return add(DIRECTIONS[Math.floorMod(direction, 6)]);
    }

    public int distanceTo(AxialCoordinate other) {
        int dq = Math.abs(q - other.q);
        int dr = Math.abs(r - other.r);
        int ds = Math.abs(q + r - other.q - other.r);
        return (dq + dr + ds) / 2;
    }

    public static List<AxialCoordinate> ring(AxialCoordinate center, int radius) {
        List<AxialCoordinate> result = new ArrayList<>(Math.max(0, radius * 6));
        if (radius <= 0) return result;
        AxialCoordinate cursor = center;
        for (int i = 0; i < radius; i++) cursor = cursor.add(DIRECTIONS[4]);
        for (int direction = 0; direction < 6; direction++) {
            for (int i = 0; i < radius; i++) {
                result.add(cursor);
                cursor = cursor.neighbor(direction);
            }
        }
        return result;
    }

    @Override
    public int compareTo(AxialCoordinate other) {
        int byQ = Integer.compare(q, other.q);
        return byQ != 0 ? byQ : Integer.compare(r, other.r);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof AxialCoordinate)) return false;
        AxialCoordinate other = (AxialCoordinate) object;
        return q == other.q && r == other.r;
    }

    @Override
    public int hashCode() {
        return Objects.hash(q, r);
    }

    @Override
    public String toString() {
        return "(" + q + "," + r + ")";
    }
}
