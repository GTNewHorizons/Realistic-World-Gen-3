package com.irythill.rwg3.procgen.util.hex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Finite hex grid whose neighbor access wraps through rectangular world space. */
public final class HexGrid<T> {

    private final double width;
    private final double height;
    private final Map<AxialCoordinate, T> cells;
    private final List<AxialCoordinate> coordinates;

    public HexGrid(double width, double height, Function<AxialCoordinate, T> factory) {
        this.width = width;
        this.height = height;
        Map<AxialCoordinate, T> generated = new LinkedHashMap<>();
        int xSteps = (int) Math.ceil(width) + 1;
        double yStep = Math.sqrt(3.0) / 3.0;
        int ySteps = (int) Math.ceil(height / yStep) + 2;
        for (int x = 0; x <= xSteps; x++) {
            for (int y = 0; y <= ySteps; y++) {
                AxialCoordinate coordinate = AxialCoordinate.fromWorld(new Vec2(x, y * yStep + 1.0 / 3.0));
                Vec2 center = coordinate.toWorld();
                if (center.x >= 0.0 && center.x < width && center.y >= 0.0 && center.y < height) {
                    generated.computeIfAbsent(coordinate, factory);
                }
            }
        }
        List<AxialCoordinate> ordered = new ArrayList<>(generated.keySet());
        Collections.sort(ordered);
        cells = new LinkedHashMap<>();
        for (AxialCoordinate coordinate : ordered) cells.put(coordinate, generated.get(coordinate));
        coordinates = Collections.unmodifiableList(ordered);
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public List<AxialCoordinate> coordinates() {
        return coordinates;
    }

    public T get(AxialCoordinate coordinate) {
        return cells.get(coordinate);
    }

    public T getWrapped(AxialCoordinate coordinate) {
        return cells.get(wrap(coordinate));
    }

    public Vec2 wrap(Vec2 point) {
        return new Vec2(mod(point.x, width), mod(point.y, height));
    }

    public AxialCoordinate wrap(AxialCoordinate coordinate) {
        AxialCoordinate wrapped = AxialCoordinate.fromWorld(wrap(coordinate.toWorld()));
        if (cells.containsKey(wrapped)) return wrapped;
        // Sampling at the rectangular seam can round to an absent boundary cell.
        AxialCoordinate nearest = null;
        double best = Double.POSITIVE_INFINITY;
        Vec2 target = wrap(coordinate.toWorld());
        for (AxialCoordinate candidate : coordinates) {
            Vec2 point = candidate.toWorld();
            double distance = Math.abs(point.x - target.x) + Math.abs(point.y - target.y);
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private static double mod(double value, double modulus) {
        double result = value % modulus;
        return result < 0.0 ? result + modulus : result;
    }
}
