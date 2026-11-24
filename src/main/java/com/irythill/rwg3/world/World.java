package com.irythill.rwg3.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.irythill.rwg3.util.hex.GridIndex;
import com.irythill.rwg3.util.hex.HexGrid;
import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.steps.ContinentalPlatesStep;
import com.irythill.rwg3.world.steps.TerrainRegionsStep.TerrainRegionKind;

/** The mutable-while-generating, immutable-after-publication fixed world. */
public final class World {

    public static final double WIDTH = 360.0;
    public static final double BLOCK_WIDTH = 30_000.0;
    public static final double BLOCKS_PER_WORLD_UNIT = BLOCK_WIDTH / WIDTH;
    public static final double SEA_LEVEL = 1.0;
    public static final double MAXIMUM_HEIGHT = 2.25;
    public static final double BLOCK_SEA_LEVEL = 63.0;
    public static final double BLOCK_PEAK_TARGET_HEIGHT = 800.0;
    public static final double PLATE_DRIFT = 3.0;
    public static final double PLATE_SPIN = 8.0e-4;

    public final long seed;
    public final HexGrid<Hex> grid;
    public final List<WorldGenerator.Plate> plates = new ArrayList<>();
    public final int[] continentSeedPlateIds = new int[ContinentalPlatesStep.BLOB_COUNT];
    public final int[] continentPlateCounts = new int[ContinentalPlatesStep.BLOB_COUNT];
    public GridIndex index;
    public int[] plateIds;
    public boolean[] oceanic;
    public Vec2[] velocity;
    public double[] convergence;
    public double[] divergence;
    public double[] height;
    public int[] terrainRegionIds;
    public List<TerrainRegionKind> terrainRegionKinds;
    public double[] temperatureCelsius;
    public Vec2[] wind;
    public double[] humidityPercent;
    public Biome[] dominantBiomes;
    public double[] biomeShares = new double[Biome.values().length];
    private final Trace trace;

    World(long seed, boolean traced) {
        this.seed = seed;
        trace = traced ? new Trace() : null;
        grid = new HexGrid<>(WIDTH, WIDTH, ignored -> new Hex());
    }

    void record(int stepIndex, long nanos) {
        if (trace == null) return;
        HexGrid<Hex> copy = new HexGrid<>(
            grid.width,
            grid.height,
            coordinate -> grid.get(coordinate)
                .copy());
        trace.previews.put(stepIndex, copy);
        trace.timingsNanos.put(stepIndex, nanos);
    }

    public Map<Integer, HexGrid<Hex>> previews() {
        return trace == null ? Collections.emptyMap() : Collections.unmodifiableMap(trace.previews);
    }

    public HexGrid<Hex> preview(int stepIndex) {
        return trace == null ? null : trace.previews.get(stepIndex);
    }

    public Map<Integer, Long> timingsNanos() {
        return trace == null ? Collections.emptyMap() : Collections.unmodifiableMap(trace.timingsNanos);
    }

    public boolean hasTrace() {
        return trace != null;
    }

    public static double normalizedHeight(double blockHeight) {
        double fraction = (blockHeight - BLOCK_SEA_LEVEL) / (BLOCK_PEAK_TARGET_HEIGHT - BLOCK_SEA_LEVEL);
        return SEA_LEVEL + fraction * (MAXIMUM_HEIGHT - SEA_LEVEL);
    }

    public static double blockHeight(double normalizedHeight) {
        if (normalizedHeight <= SEA_LEVEL) return normalizedHeight / SEA_LEVEL * BLOCK_SEA_LEVEL;
        double land = (normalizedHeight - SEA_LEVEL) / (MAXIMUM_HEIGHT - SEA_LEVEL);
        return BLOCK_SEA_LEVEL + land * (BLOCK_PEAK_TARGET_HEIGHT - BLOCK_SEA_LEVEL);
    }

    private static final class Trace {

        final Map<Integer, HexGrid<Hex>> previews = new LinkedHashMap<>();
        final Map<Integer, Long> timingsNanos = new LinkedHashMap<>();
    }
}
