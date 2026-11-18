package com.irythill.rwg3.procgen.continent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.irythill.rwg3.procgen.continent.steps.TerrainRegionsStep.TerrainRegion;
import com.irythill.rwg3.procgen.continent.steps.TerrainRegionsStep.TerrainRegionKind;
import com.irythill.rwg3.procgen.util.hex.AxialCoordinate;
import com.irythill.rwg3.procgen.util.hex.GridIndex;
import com.irythill.rwg3.procgen.util.hex.HexGrid;
import com.irythill.rwg3.procgen.util.hex.Vec2;
import com.irythill.rwg3.procgen.util.noise.ICoherentNoise.Query;
import com.irythill.rwg3.procgen.util.noise.PoissonNoise;
import com.irythill.rwg3.procgen.util.noise.Warped;

/** The mutable-while-generating, immutable-after-publication finite continent. */
public final class Continent {

    public static final double WIDTH = 120.0;
    public static final double SEA_LEVEL = 1.0;
    public static final double MAXIMUM_HEIGHT = 2.25;

    public final long seed;
    public final HexGrid<Hex> grid;
    public final GenerationSettings config;
    public final Random random;
    public final Warped<PoissonNoise> plateField;
    public final Query query = new Query(1);
    public final List<ContinentGenerator.Plate> plates = new ArrayList<>();
    public final Map<Long, Integer> plateIdBySite = new HashMap<>();
    public final Map<AxialCoordinate, Long> plateSiteByCoordinate = new HashMap<>();
    public GridIndex index;
    public int[] plateIds;
    public boolean[] oceanic;
    public Vec2[] velocity;
    public double[] convergence;
    public double[] divergence;
    public double[] height;
    public double[] noiseErosionWeights;
    public int[] terrainRegionIds;
    public List<TerrainRegion> terrainRegions = Collections.emptyList();
    public List<TerrainRegionKind> terrainRegionKinds;
    private final Trace trace;

    Continent(long seed, boolean traced) {
        this.seed = seed;
        trace = traced ? new Trace() : null;
        config = GenerationSettings.defaults();
        random = new Random(seed);
        grid = new HexGrid<>(WIDTH, WIDTH, ignored -> new Hex());
        int plateSize = 22;
        plateField = new Warped<>(
            seed ^ 0x57415250504C4154L,
            new PoissonNoise(seed, plateSize * 0.85, 4),
            plateSize * 1.1,
            plateSize * 0.89);
    }

    void record(int stepIndex, long nanos) {
        if (trace == null) return;
        HexGrid<Hex> copy = new HexGrid<>(
            grid.width(),
            grid.height(),
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

    public List<TerrainRegion> terrainRegions() {
        return Collections.unmodifiableList(terrainRegions);
    }

    public Map<Integer, Long> timingsNanos() {
        return trace == null ? Collections.emptyMap() : Collections.unmodifiableMap(trace.timingsNanos);
    }

    public boolean hasTrace() {
        return trace != null;
    }

    private static final class Trace {

        final Map<Integer, HexGrid<Hex>> previews = new LinkedHashMap<>();
        final Map<Integer, Long> timingsNanos = new LinkedHashMap<>();
    }
}
