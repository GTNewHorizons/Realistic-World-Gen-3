package com.irythill.rwg3.procgen.world;

import java.util.List;

import com.irythill.rwg3.procgen.GenerationStep;
import com.irythill.rwg3.procgen.continent.Continent;
import com.irythill.rwg3.procgen.continent.ContinentGenerator;
import com.irythill.rwg3.procgen.continent.Hex;
import com.irythill.rwg3.procgen.continent.steps.TerrainRegionsStep.TerrainRegion;
import com.irythill.rwg3.procgen.util.hex.AxialCoordinate;
import com.irythill.rwg3.procgen.util.hex.HexGrid;
import com.irythill.rwg3.procgen.util.hex.Vec2;

/** Internal pairing of a world placement and its generated finite continent. */
final class PlacedContinent {

    final WorldGenerator.Placement seed;
    final long plannerSeed;
    final Continent continent;

    PlacedContinent(WorldGenerator.Placement seed, long plannerSeed, Continent continent) {
        this.seed = seed;
        this.plannerSeed = plannerSeed;
        this.continent = continent;
    }

    LocalSample sample(double worldX, double worldY, GenerationStep stage, double seaFloor) {
        int index = Math.max(
            0,
            Math.min(
                ((ContinentGenerator.StepDefinition) stage).index,
                continent.previews()
                    .size() - 1));
        HexGrid<Hex> grid = continent.preview(index);
        if (grid == null) grid = continent.grid;
        double localX = worldX - seed.centerX + grid.width() * .5;
        double localY = worldY - seed.centerY + grid.height() * .5;
        double edge = Math.min(Math.min(localX, grid.width() - localX), Math.min(localY, grid.height() - localY));
        double falloff = smoothstep((edge + 30.0) / 60.0);
        double x = mirror(localX, grid.width()), y = mirror(localY, grid.height());
        Hex hex = grid.get(AxialCoordinate.fromWorld(new Vec2(x, y)));
        if (hex == null) hex = nearest(grid, x, y);
        return new LocalSample(
            seaFloor + (hex.height - seaFloor) * falloff,
            hex.plateId,
            hex.oceanic,
            falloff,
            Continent.SEA_LEVEL,
            hex.velocity.x,
            hex.velocity.y,
            hex.convergence,
            hex.divergence,
            hex.terrainRegionId,
            hex.erosionWeight);
    }

    List<TerrainRegion> terrainRegions() {
        return continent.terrainRegions();
    }

    double plannerWidth() {
        return continent.grid.width();
    }

    private static Hex nearest(HexGrid<Hex> grid, double x, double y) {
        Hex selected = null;
        double best = Double.POSITIVE_INFINITY;
        for (AxialCoordinate coordinate : grid.coordinates()) {
            Vec2 point = coordinate.toWorld();
            double distance = (point.x - x) * (point.x - x) + (point.y - y) * (point.y - y);
            if (distance < best) {
                best = distance;
                selected = grid.get(coordinate);
            }
        }
        return selected;
    }

    private static double mirror(double value, double extent) {
        double result = value % (extent * 2.0);
        if (result < 0.0) result += extent * 2.0;
        return result <= extent ? result : extent * 2.0 - result;
    }

    private static double smoothstep(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        return v * v * (3.0 - 2.0 * v);
    }

    static final class LocalSample {

        final double elevation, falloff, seaLevel, velocityX, velocityY, convergence, divergence, noiseErosionWeight;
        final int plateId, terrainRegionId;
        final boolean oceanic;

        LocalSample(double elevation, int plateId, boolean oceanic, double falloff, double seaLevel, double velocityX,
            double velocityY, double convergence, double divergence, int terrainRegionId, double noiseErosionWeight) {
            this.elevation = elevation;
            this.plateId = plateId;
            this.oceanic = oceanic;
            this.falloff = falloff;
            this.seaLevel = seaLevel;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.convergence = convergence;
            this.divergence = divergence;
            this.terrainRegionId = terrainRegionId;
            this.noiseErosionWeight = noiseErosionWeight;
        }
    }
}
