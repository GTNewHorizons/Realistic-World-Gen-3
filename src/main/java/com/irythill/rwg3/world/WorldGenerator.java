package com.irythill.rwg3.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import com.irythill.rwg3.preview.PreviewDecorations;
import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.steps.BaseHeightStep;
import com.irythill.rwg3.world.steps.BiomeStep;
import com.irythill.rwg3.world.steps.ContinentalPlatesStep;
import com.irythill.rwg3.world.steps.HeightRedistributionStep;
import com.irythill.rwg3.world.steps.IGenerationStep;
import com.irythill.rwg3.world.steps.MergePlatesStep;
import com.irythill.rwg3.world.steps.PlateMotionStep;
import com.irythill.rwg3.world.steps.PlatePlacementStep;
import com.irythill.rwg3.world.steps.TemperatureStep;
import com.irythill.rwg3.world.steps.TerrainBiomeStep;
import com.irythill.rwg3.world.steps.TerrainRegionsStep;
import com.irythill.rwg3.world.steps.WindHumidityStep;

/** Deterministic generator for the complete fixed-size world. */
public final class WorldGenerator {

    public static final double WORLD_WIDTH = World.WIDTH;
    public static final double WORLD_BLOCK_WIDTH = World.BLOCK_WIDTH;
    public static final double BLOCKS_PER_WORLD_UNIT = World.BLOCKS_PER_WORLD_UNIT;
    public static final double WATER_LEVEL = 63.0;

    private final List<StepDefinition> steps = new ArrayList<>();
    private final boolean traced;

    public WorldGenerator() {
        this(false);
    }

    private WorldGenerator(boolean traced) {
        this.traced = traced;
        register("plates", "Plates", new PlatePlacementStep(), PreviewDecorations::plates, null, null, null);
        register(
            "continental-plates",
            "Continental plates",
            new ContinentalPlatesStep(),
            PreviewDecorations::oceanicPlates,
            null,
            null,
            null);
        register(
            "merged-plates",
            "Merged plates",
            new MergePlatesStep(),
            PreviewDecorations::oceanicPlates,
            null,
            null,
            null);
        register(
            "plate-motion",
            "Plate motion",
            new PlateMotionStep(),
            PreviewDecorations::movement,
            PreviewDecorations::movementArrow,
            null,
            null);
        register("base-height", "Base height", new BaseHeightStep(), PreviewDecorations::hexHeight, null, null, null);
        register(
            "height-redistribution",
            "Height redistribution",
            new HeightRedistributionStep(),
            PreviewDecorations::hexHeight,
            null,
            null,
            null);
        register(
            "temperature",
            "Temperature",
            new TemperatureStep(),
            PreviewDecorations::temperature,
            null,
            PreviewDecorations::temperatureTooltip,
            null);
        register(
            "wind-humidity",
            "Wind and humidity",
            new WindHumidityStep(),
            PreviewDecorations::humidity,
            PreviewDecorations::windArrow,
            PreviewDecorations::climateTooltip,
            null);
        registerShaded(
            "biomes",
            "Biomes",
            new BiomeStep(),
            PreviewDecorations::biome,
            null,
            PreviewDecorations::biomeTooltip,
            null);
        registerShaded(
            "terrain-regions",
            "Terrain regions",
            new TerrainRegionsStep(),
            PreviewDecorations::biome,
            null,
            PreviewDecorations::terrainRegionTooltip,
            hex -> hex.terrainRegionId);
        registerShaded(
            "terrain-biomes",
            "Terrain category × biome",
            new TerrainBiomeStep(),
            PreviewDecorations::biome,
            null,
            PreviewDecorations::terrainBiomeTooltip,
            hex -> hex.terrainBiomeGroup);
    }

    public static WorldGenerator traced() {
        return new WorldGenerator(true);
    }

    public World generate(long seed) {
        List<IGenerationStep> pipeline = new ArrayList<>(steps.size());
        for (StepDefinition definition : steps) pipeline.add(definition.step);
        return generate(seed, pipeline);
    }

    public World generate(long seed, List<IGenerationStep> pipeline) {
        World world = new World(seed, traced);
        for (int index = 0; index < pipeline.size(); index++) {
            long started = traced ? System.nanoTime() : 0L;
            pipeline.get(index)
                .apply(seed, world);
            if (traced) world.record(index, System.nanoTime() - started);
        }
        return world;
    }

    private void register(String id, String label, IGenerationStep step, ToIntFunction<Hex> colour,
        Function<Hex, Vec2> arrow, Function<Hex, String> tooltip, ToIntFunction<Hex> contourGroup) {
        steps.add(new StepDefinition(id, label, steps.size(), step, colour, arrow, tooltip, contourGroup, false));
    }

    private void registerShaded(String id, String label, IGenerationStep step, ToIntFunction<Hex> colour,
        Function<Hex, Vec2> arrow, Function<Hex, String> tooltip, ToIntFunction<Hex> contourGroup) {
        steps.add(new StepDefinition(id, label, steps.size(), step, colour, arrow, tooltip, contourGroup, true));
    }

    public List<StepDefinition> steps() {
        return Collections.unmodifiableList(steps);
    }

    public static final class StepDefinition {

        public final String id;
        public final String label;
        public final int index;
        public final IGenerationStep step;
        public final ToIntFunction<Hex> colour;
        public final Function<Hex, Vec2> arrow;
        public final Function<Hex, String> tooltip;
        public final ToIntFunction<Hex> contourGroup;
        public final boolean slopeShading;

        StepDefinition(String id, String label, int index, IGenerationStep step, ToIntFunction<Hex> colour,
            Function<Hex, Vec2> arrow, Function<Hex, String> tooltip, ToIntFunction<Hex> contourGroup,
            boolean slopeShading) {
            this.id = id;
            this.label = label;
            this.index = index;
            this.step = step;
            this.colour = colour;
            this.arrow = arrow;
            this.tooltip = tooltip;
            this.contourGroup = contourGroup;
            this.slopeShading = slopeShading;
        }
    }

    public static final class Plate {

        public Vec2 center;
        public final Vec2 drift;
        public final double spin;
        public boolean oceanic = true;
        public int continentSeed = -1;

        public Plate(Vec2 drift, double spin) {
            this.drift = drift;
            this.spin = spin;
        }

        public Vec2 velocityAt(World state, Vec2 point) {
            double dx = wrappedDelta(point.x - center.x, state.grid.width);
            double dy = wrappedDelta(point.y - center.y, state.grid.height);
            return drift.add(new Vec2(-spin * dy, spin * dx));
        }

        private static double wrappedDelta(double delta, double period) {
            double result = (delta + period * 0.5) % period;
            if (result < 0.0) result += period;
            return result - period * 0.5;
        }
    }
}
