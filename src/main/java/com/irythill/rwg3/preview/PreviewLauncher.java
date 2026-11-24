package com.irythill.rwg3.preview;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.swing.SwingUtilities;

import com.irythill.rwg3.util.hex.AxialCoordinate;
import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.Biome;
import com.irythill.rwg3.world.Hex;
import com.irythill.rwg3.world.World;
import com.irythill.rwg3.world.WorldGenerator;
import com.irythill.rwg3.world.WorldGenerator.StepDefinition;
import com.irythill.rwg3.world.steps.ContinentalPlatesStep;

/** Preview entry point; --smoke performs deterministic headless validation. */
public final class PreviewLauncher {

    private PreviewLauncher() {}

    public static void main(String[] args) {
        if (Arrays.asList(args)
            .contains("--smoke")) {
            smoke();
            return;
        }
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Preview requires a display");
        SwingUtilities.invokeLater(() -> new PreviewWindow().setVisible(true));
    }

    private static void smoke() {
        WorldGenerator tracedGenerator = WorldGenerator.traced();
        World first = tracedGenerator.generate(123L);
        if (first.grid.width != 360.0 || first.grid.height != 360.0)
            throw new IllegalStateException("Unexpected fixed world dimensions");
        if (first.previews()
            .size()
            != tracedGenerator.steps()
                .size())
            throw new IllegalStateException("Preview registration mismatch");

        long firstHash = hash(first);
        World production = new WorldGenerator().generate(123L);
        if (production.hasTrace() || !production.previews()
            .isEmpty()
            || !production.timingsNanos()
                .isEmpty())
            throw new IllegalStateException("Production world retained preview trace data");
        if (hash(production) != firstHash) throw new IllegalStateException("Tracing changed the generated world");
        World different = new WorldGenerator().generate(124L);
        if (hash(different) == firstHash) throw new IllegalStateException("World seed does not affect generation");
        assertClimate(different);

        Set<Integer> seeds = new HashSet<>();
        int categorized = 0;
        for (int blob = 0; blob < ContinentalPlatesStep.BLOB_COUNT; blob++) {
            int seedPlate = first.continentSeedPlateIds[blob];
            if (seedPlate <= 0 || !seeds.add(seedPlate)) throw new IllegalStateException("Duplicate continent seed");
            if (first.plates.get(seedPlate - 1).oceanic || first.continentPlateCounts[blob] <= 0)
                throw new IllegalStateException("Continent seed was classified as oceanic");
            categorized += first.continentPlateCounts[blob];
        }
        int continental = 0;
        for (WorldGenerator.Plate plate : first.plates) if (!plate.oceanic) continental++;
        if (continental != categorized) throw new IllegalStateException("Continental plate categorization mismatch");
        assertPlateMerging(first);

        assertToroidalPlateField(first);
        assertClimate(first);
        assertTerrainRegions(first);
        StepDefinition movement = tracedGenerator.steps()
            .get(3);
        StepDefinition temperature = tracedGenerator.steps()
            .get(6);
        StepDefinition humidity = tracedGenerator.steps()
            .get(7);
        StepDefinition biomes = tracedGenerator.steps()
            .get(8);
        StepDefinition regions = tracedGenerator.steps()
            .get(9);
        StepDefinition terrainBiomes = tracedGenerator.steps()
            .get(10);
        if (!"biomes".equals(biomes.id) || !"terrain-regions".equals(regions.id)
            || !"terrain-biomes".equals(terrainBiomes.id))
            throw new IllegalStateException("Unexpected biome and terrain preview order");
        if (terrainBiomes.colour == null || terrainBiomes.tooltip == null || terrainBiomes.contourGroup == null)
            throw new IllegalStateException("Terrain-biome preview decorations are incomplete");
        for (StepDefinition outlined : new StepDefinition[] { regions, terrainBiomes }) {
            if (PreviewCanvas.buildContours(first, outlined)
                .getCurrentPoint() == null) throw new IllegalStateException("Missing contours for " + outlined.id);
        }
        new PreviewCanvas(); // Verify packaged tree icon resources load in the preview.
        if (movement.colour == null || movement.arrow == null)
            throw new IllegalStateException("Movement preview decorations are incomplete");
        if (regions.colour == null || regions.tooltip == null || regions.contourGroup == null)
            throw new IllegalStateException("Terrain-region preview decorations are incomplete");
        if (temperature.colour == null || temperature.tooltip == null)
            throw new IllegalStateException("Temperature preview decorations are incomplete");
        if (humidity.colour == null || humidity.arrow == null || humidity.tooltip == null)
            throw new IllegalStateException("Humidity preview decorations are incomplete");
        if (biomes.colour == null || biomes.tooltip == null)
            throw new IllegalStateException("Biome preview decorations are incomplete");
        for (StepDefinition biomeView : new StepDefinition[] { biomes, regions, terrainBiomes }) {
            if (!biomeView.slopeShading || !PreviewCanvas.hasTreeIcons(biomeView))
                throw new IllegalStateException("Missing biome shading or trees for " + biomeView.id);
        }
        for (StepDefinition step : tracedGenerator.steps()) {
            BufferedImage rendered = PreviewCanvas.rasterize(first, step);
            if (rendered.getWidth() <= 0 || rendered.getRGB(0, 0) == 0)
                throw new IllegalStateException("Full-world preview rendering failed for " + step.id);
            if (step.slopeShading) assertSlopeShading(first, step, rendered);
        }
        System.out.println("smoke checksum=" + Long.toUnsignedString(firstHash));
    }

    private static void assertTerrainRegions(World world) {
        if (world.terrainRegionKinds.isEmpty()) throw new IllegalStateException("Missing terrain regions");
        for (int cell = 0; cell < world.terrainRegionIds.length; cell++) {
            int id = world.terrainRegionIds[cell];
            if (id == 0) continue;
            for (int neighbor : world.index.neighbors[cell]) {
                int adjacent = world.terrainRegionIds[neighbor];
                if (adjacent != 0 && adjacent != id
                    && world.terrainRegionKinds.get(id - 1) == world.terrainRegionKinds.get(adjacent - 1))
                    throw new IllegalStateException("Adjacent regions of the same terrain category were not merged");
            }
        }
    }

    private static void assertClimate(World world) {
        int land = 0;
        int[] dominantCounts = new int[Biome.values().length];
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            Hex hex = world.grid.get(coordinate);
            if (!Double.isFinite(hex.temperatureCelsius) || !Double.isFinite(hex.humidityPercent)
                || hex.humidityPercent < 0.0
                || hex.humidityPercent > 100.0
                || !Double.isFinite(hex.wind.x)
                || !Double.isFinite(hex.wind.y)) throw new IllegalStateException("Invalid climate value");
            if (hex.height <= World.SEA_LEVEL) {
                if (hex.biomeWeights != null || hex.dominantBiome != null)
                    throw new IllegalStateException("Ocean received a terrestrial biome");
                continue;
            }
            land++;
            if (hex.biomeWeights == null || hex.biomeWeights.length != Biome.values().length
                || hex.dominantBiome == null) throw new IllegalStateException("Land is missing biome weights");
            double total = 0.0;
            for (float weight : hex.biomeWeights) {
                if (!Float.isFinite(weight) || weight < 0.0f || weight > 100.0f)
                    throw new IllegalStateException("Invalid biome weight");
                total += weight;
            }
            if (Math.abs(total - 100.0) > 0.01) throw new IllegalStateException("Biome weights do not total 100%");
            dominantCounts[hex.dominantBiome.ordinal()]++;
        }
        if (land == 0) throw new IllegalStateException("Climate generated no land");
        for (Biome biome : Biome.values()) {
            double actual = dominantCounts[biome.ordinal()] * 100.0 / land;
            if (Math.abs(actual - biome.targetPercent) > 1.0) throw new IllegalStateException(
                biome.label + " share was " + actual + "% instead of " + biome.targetPercent + "%");
            if (Math.abs(actual - world.biomeShares[biome.ordinal()]) > 1.0e-9)
                throw new IllegalStateException("Published biome share mismatch");
        }
    }

    private static void assertSlopeShading(World world, StepDefinition biomes, BufferedImage rendered) {
        int px = rendered.getWidth() / 2;
        int py = rendered.getHeight() / 2;
        double x = (px + 0.5) * World.WIDTH / rendered.getWidth();
        double y = (rendered.getHeight() - py - 0.5) * World.WIDTH / rendered.getHeight();
        Hex sample = world.preview(biomes.index)
            .getWrapped(AxialCoordinate.fromWorld(new Vec2(x, y)));
        int pixel = rendered.getRGB(px, py) & 0xFFFFFF;
        int unshaded = biomes.colour.applyAsInt(sample) & 0xFFFFFF;
        if (pixel == unshaded) throw new IllegalStateException("Biome slope shading was not applied");
    }

    private static void assertPlateMerging(World world) {
        Set<Integer> classified = new HashSet<>(), merged = new HashSet<>();
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            Hex before = world.preview(1)
                .get(coordinate);
            Hex after = world.preview(2)
                .get(coordinate);
            classified.add(before.plateId);
            merged.add(after.plateId);
            if (before.oceanic != after.oceanic)
                throw new IllegalStateException("Plate merging changed the land/ocean classification");
        }
        if (merged.size() >= classified.size()) throw new IllegalStateException("Plate merging did not reduce plates");
    }

    private static void assertToroidalPlateField(World world) {
        Set<Integer> left = new HashSet<>(), right = new HashSet<>(), bottom = new HashSet<>(), top = new HashSet<>();
        int[] cellsPerPlate = new int[world.plates.size() + 1];
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            Hex hex = world.grid.get(coordinate);
            if (hex.plateId <= 0 || hex.plateId > world.plates.size())
                throw new IllegalStateException("Plate flood fill left an invalid hex");
            cellsPerPlate[hex.plateId]++;
            double x = coordinate.toWorld().x;
            double y = coordinate.toWorld().y;
            if (x < 2.0) left.add(hex.plateId);
            if (x > World.WIDTH - 2.0) right.add(hex.plateId);
            if (y < 2.0) bottom.add(hex.plateId);
            if (y > World.WIDTH - 2.0) top.add(hex.plateId);
        }
        for (int id = 1; id < cellsPerPlate.length; id++)
            if (cellsPerPlate[id] == 0) throw new IllegalStateException("Plate smoothing erased plate " + id);
        left.retainAll(right);
        bottom.retainAll(top);
        if (left.isEmpty() || bottom.isEmpty()) throw new IllegalStateException("No plates cross the world seams");
    }

    private static long hash(World world) {
        long hash = 0xcbf29ce484222325L;
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            Hex hex = world.grid.get(coordinate);
            hash = (hash ^ hex.plateId) * 0x100000001b3L;
            hash = (hash ^ (hex.oceanic ? 1L : 0L)) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(hex.height)) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(hex.temperatureCelsius)) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(hex.humidityPercent)) * 0x100000001b3L;
            hash = (hash ^ (hex.dominantBiome == null ? 0L : hex.dominantBiome.ordinal() + 1L)) * 0x100000001b3L;
        }
        return hash;
    }
}
