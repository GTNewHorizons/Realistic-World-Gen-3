package com.irythill.rwg3.procgen.continent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import com.irythill.rwg3.preview.PreviewDecorations;
import com.irythill.rwg3.procgen.GenerationStep;
import com.irythill.rwg3.procgen.continent.steps.BaseHeightStep;
import com.irythill.rwg3.procgen.continent.steps.HeightRedistributionStep;
import com.irythill.rwg3.procgen.continent.steps.IContinentStep;
import com.irythill.rwg3.procgen.continent.steps.OceanicPlatesStep;
import com.irythill.rwg3.procgen.continent.steps.PlateMotionStep;
import com.irythill.rwg3.procgen.continent.steps.PlatePlacementStep;
import com.irythill.rwg3.procgen.continent.steps.TerrainRegionsStep;
import com.irythill.rwg3.procgen.continent.steps.TerrainRegionsStep.TerrainRegion;
import com.irythill.rwg3.procgen.util.hex.Vec2;

/** Deterministic Java port of iVy's high-level tectonic planner. */
public final class ContinentGenerator {

    private final List<StepDefinition> steps = new ArrayList<>();
    private final boolean traced;

    public ContinentGenerator() {
        this(false);
    }

    private ContinentGenerator(boolean traced) {
        this.traced = traced;
        register("plates", "Plates", new PlatePlacementStep(), PreviewDecorations::plates, null, null, null);
        register(
            "oceanic-plates",
            "Oceanic plates",
            new OceanicPlatesStep(),
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
            "terrain-regions",
            "Terrain regions",
            new TerrainRegionsStep(),
            PreviewDecorations::terrainRegion,
            null,
            PreviewDecorations::terrainRegionTooltip,
            PreviewDecorations::terrainRegionLabels);
    }

    public static ContinentGenerator traced() {
        return new ContinentGenerator(true);
    }

    public Continent generate(long seed) {
        List<IContinentStep> pipeline = new ArrayList<>(steps.size());
        for (StepDefinition definition : steps) pipeline.add(definition.step);
        return generate(seed, pipeline);
    }

    private void register(String id, String label, IContinentStep step, ToIntFunction<Hex> colour,
        Function<Hex, Vec2> arrow, Function<Hex, String> tooltip, Function<Continent, List<TerrainRegion>> labels) {
        steps.add(new StepDefinition(id, label, steps.size(), step, colour, arrow, tooltip, labels));
    }

    public List<StepDefinition> steps() {
        return Collections.unmodifiableList(steps);
    }

    public Continent generate(long seed, List<IContinentStep> pipeline) {
        Continent continent = new Continent(seed, traced);
        for (int index = 0; index < pipeline.size(); index++) {
            long started = traced ? System.nanoTime() : 0L;
            pipeline.get(index)
                .apply(seed, continent);
            if (traced) continent.record(index, System.nanoTime() - started);
        }
        return continent;
    }

    public static final class StepDefinition implements GenerationStep {

        public final String id;
        public final String label;
        public final int index;
        public final IContinentStep step;
        public final ToIntFunction<Hex> colour;
        public final Function<Hex, Vec2> arrow;
        public final Function<Hex, String> tooltip;
        public final Function<Continent, List<TerrainRegion>> labels;

        StepDefinition(String id, String label, int index, IContinentStep step, ToIntFunction<Hex> colour,
            Function<Hex, Vec2> arrow, Function<Hex, String> tooltip, Function<Continent, List<TerrainRegion>> labels) {
            this.id = id;
            this.label = label;
            this.index = index;
            this.step = step;
            this.colour = colour;
            this.arrow = arrow;
            this.tooltip = tooltip;
            this.labels = labels;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public boolean hasView() {
            return colour != null;
        }
    }

    public static final class Plate {

        public Vec2 center;
        public final Vec2 drift;
        public final double spin;
        public boolean oceanic;

        public Plate(Vec2 drift, double spin) {
            this.drift = drift;
            this.spin = spin;
        }

        public Vec2 velocityAt(Continent state, Vec2 point) {
            double dx = wrappedDelta(point.x - center.x, state.grid.width());
            double dy = wrappedDelta(point.y - center.y, state.grid.height());
            return drift.add(new Vec2(-spin * dy, spin * dx));
        }

        private static double wrappedDelta(double delta, double period) {
            double result = (delta + period * 0.5) % period;
            if (result < 0.0) result += period;
            return result - period * 0.5;
        }
    }

}
