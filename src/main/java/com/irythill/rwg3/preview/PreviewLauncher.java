package com.irythill.rwg3.preview;

import java.awt.GraphicsEnvironment;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.Map;

import javax.swing.SwingUtilities;

import com.irythill.rwg3.procgen.GenerationStep;
import com.irythill.rwg3.procgen.continent.Continent;
import com.irythill.rwg3.procgen.continent.ContinentGenerator;
import com.irythill.rwg3.procgen.continent.ContinentGenerator.StepDefinition;
import com.irythill.rwg3.procgen.continent.Hex;
import com.irythill.rwg3.procgen.util.hex.AxialCoordinate;
import com.irythill.rwg3.procgen.world.Column;
import com.irythill.rwg3.procgen.world.WorldGenerator;
import com.irythill.rwg3.procgen.world.WorldGenerator.WorldStepDefinition;

/** Preview entry point; --smoke performs deterministic headless validation. */
public final class PreviewLauncher {

    private PreviewLauncher() {}

    public static void main(String[] args) {
        if (args.length > 0 && "--sample".equals(args[0])) {
            TerrainSampler.run(args);
            return;
        }
        if (Arrays.asList(args)
            .contains("--smoke")) {
            smoke();
            return;
        }
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Preview requires a display");
        SwingUtilities.invokeLater(() -> new PreviewWindow().setVisible(true));
    }

    private static void smoke() {
        Continent first = ContinentGenerator.traced()
            .generate(123L);
        Continent second = ContinentGenerator.traced()
            .generate(123L);
        Continent different = ContinentGenerator.traced()
            .generate(124L);
        if (first.previews()
            .size()
            != ContinentGenerator.traced()
                .steps()
                .size())
            throw new IllegalStateException("Preview registration mismatch");
        long firstHash = hash(first), secondHash = hash(second), differentHash = hash(different);
        if (firstHash != secondHash || firstHash == differentHash)
            throw new IllegalStateException("Determinism failure");
        AxialCoordinate coordinate = first.grid.coordinates()
            .get(0);
        Hex previewHex = first.preview(0)
            .get(coordinate);
        int plate = previewHex.plateId;
        first.grid.get(coordinate).plateId++;
        if (previewHex.plateId != plate) throw new IllegalStateException("Preview copy is not isolated");
        if (first.terrainRegions()
            .isEmpty()) throw new IllegalStateException("Missing terrain regions");
        boolean oceanic = false, moving = false, boundary = false, region = false;
        for (AxialCoordinate cell : first.grid.coordinates()) {
            Hex oceanicHex = first.preview(1)
                .get(cell);
            Hex motionHex = first.preview(2)
                .get(cell);
            Hex regionHex = first.preview(5)
                .get(cell);
            oceanic |= oceanicHex.oceanic;
            moving |= Math.hypot(motionHex.velocity.x, motionHex.velocity.y) > 1.0e-9;
            boundary |= motionHex.convergence > 0.0 || motionHex.divergence > 0.0;
            region |= regionHex.terrainRegionId != 0;
        }
        if (!oceanic) throw new IllegalStateException("Oceanic classification was not committed to hexes");
        if (!moving || !boundary) throw new IllegalStateException("Plate movement was not committed to hexes");
        if (!region) throw new IllegalStateException("Terrain regions were not committed to hexes");
        StepDefinition movement = ContinentGenerator.traced()
            .steps()
            .get(2);
        StepDefinition regions = ContinentGenerator.traced()
            .steps()
            .get(5);
        if (movement.colour == null || movement.arrow == null)
            throw new IllegalStateException("Movement preview decorations are incomplete");
        if (regions.colour == null || regions.tooltip == null || regions.labels == null)
            throw new IllegalStateException("Terrain-region preview decorations are incomplete");

        Continent production = new ContinentGenerator().generate(123L);
        if (production.hasTrace() || !production.previews()
            .isEmpty()
            || !production.timingsNanos()
                .isEmpty())
            throw new IllegalStateException("Production continent retained preview trace data");
        if (hash(production) != firstHash) throw new IllegalStateException("Tracing changed the final continent");

        WorldGenerator world = WorldGenerator.forPreview(123L);
        world.prepare(new Rectangle2D.Double(-5000, -5000, 10000, 10000));
        if (world.terrainLabels(new Rectangle2D.Double(-60.0, -60.0, 120.0, 120.0))
            .isEmpty()) throw new IllegalStateException("Terrain-region labels were not exposed to the preview");
        long visibleWorldViews = world.steps()
            .stream()
            .filter(step -> step instanceof WorldStepDefinition)
            .count();
        if (visibleWorldViews != 2L) throw new IllegalStateException("Expected base and final world views only");
        WorldGenerator.TimingSession timing = world.newTimingSession();
        world.sample(
            0.0,
            0.0,
            world.worldSteps()
                .get(5),
            timing);
        Map<GenerationStep, Double> rows = timing.cumulativeMicrosPerSample();
        if (rows.size() != 6) throw new IllegalStateException("Incomplete world timing pipeline");
        double previous = -1.0;
        for (double cumulative : rows.values()) {
            if (cumulative < previous) throw new IllegalStateException("Non-cumulative timing rows");
            previous = cumulative;
        }
        Column column = world.sampleColumn(0, 0);
        if (!column.complete || !Double.isFinite(column.height))
            throw new IllegalStateException("Invalid world column");
        System.out
            .println("smoke checksum=" + Long.toUnsignedString(firstHash ^ Double.doubleToLongBits(column.height)));
    }

    private static long hash(Continent continent) {
        long hash = 0xcbf29ce484222325L;
        for (AxialCoordinate coordinate : continent.grid.coordinates()) {
            Hex hex = continent.grid.get(coordinate);
            hash = (hash ^ hex.plateId) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(hex.height)) * 0x100000001b3L;
        }
        return hash;
    }
}
