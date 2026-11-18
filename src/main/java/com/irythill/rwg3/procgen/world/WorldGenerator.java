package com.irythill.rwg3.procgen.world;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import com.irythill.rwg3.preview.PreviewDecorations;
import com.irythill.rwg3.procgen.GenerationStep;
import com.irythill.rwg3.procgen.continent.*;
import com.irythill.rwg3.procgen.continent.steps.TerrainRegionsStep.TerrainRegion;
import com.irythill.rwg3.procgen.util.TerrainErosion;
import com.irythill.rwg3.procgen.util.hex.AxialCoordinate;
import com.irythill.rwg3.procgen.util.noise.ICoherentNoise.Query;
import com.irythill.rwg3.procgen.util.noise.PoissonNoise;
import com.irythill.rwg3.procgen.world.PlacedContinent.LocalSample;
import com.irythill.rwg3.procgen.world.steps.*;

/** Composes immutable finite continent plans into a deterministic infinite field. */
public final class WorldGenerator {

    public static final double CONTINENT_SPACING = 100.0;
    public static final double PLAN_WIDTH = 120.0;
    public static final double CONTINENT_BLOCK_WIDTH = 10_000.0;
    public static final double SEA_FLOOR = 0.33;
    public static final double MINIMUM_OCEAN_HEIGHT = 0.12;
    public static final double WATER_LEVEL = 63.0;
    public static final double PEAK_TARGET_HEIGHT = 800.0;
    public static final double BLOCKS_PER_WORLD_UNIT = CONTINENT_BLOCK_WIDTH / PLAN_WIDTH;
    private static final int COMPOSITION_SITES = 12;
    private static final double PLAN_FADE_DISTANCE = 30.0;
    private static final double HEIGHT_SOFTMAX_SHARPNESS = 8.0;
    private static final double SEDIMENT_BASE_PLATEAU_LAND_FRACTION = 0.105;
    private static final double SEDIMENT_BASE_VARIATION_LAND_FRACTION = 0.018;
    private static final double SEDIMENT_BASE_VARIATION_SCALE = 0.035;
    private static final double SEDIMENT_BASE_SATURATION_LAND_FRACTION = 0.01;
    private static final double SEDIMENT_VERY_SOFT_MAX_LAND_FRACTION = 0.05;
    private static final double HEIGHTMAP_WARP_SCALE = 0.01;
    private static final double HEIGHTMAP_WARP_STRENGTH = 0.5;
    private static final long PLAN_SALT = 0x434F4E54494E454EL;

    private final long worldSeed;
    private final PoissonNoise field;
    private final OceanFloor oceanFloor;
    private final GenerationSettings plannerConfig;
    private final TerrainErosion erosion;
    private final ThreadLocal<Query> nearestQuery = ThreadLocal.withInitial(() -> new Query(COMPOSITION_SITES));
    private final ThreadLocal<double[]> compositionWeights = ThreadLocal
        .withInitial(() -> new double[COMPOSITION_SITES]);
    private final ThreadLocal<HeightmapWorkspaces> heightmapWorkspaces = ThreadLocal
        .withInitial(HeightmapWorkspaces::new);
    private final Map<Long, PlacedContinent> plans = new ConcurrentHashMap<>();
    private final ContinentGenerator continentGenerator;
    private final List<GenerationStep> previewSteps;
    private final List<GenerationStep> registeredSteps;
    private final List<WorldStepDefinition> worldSteps = new ArrayList<>();
    private final ThreadLocal<Map<GenerationStep, Long>> latestTimings = ThreadLocal.withInitial(LinkedHashMap::new);
    private final ThreadLocal<SampleTiming> activeTiming = new ThreadLocal<>();
    private volatile List<PlacedContinent> latestViewportPlans = Collections.emptyList();

    public WorldGenerator(long worldSeed) {
        this(worldSeed, false);
    }

    private WorldGenerator(long worldSeed, boolean tracedContinents) {
        this.worldSeed = worldSeed;
        continentGenerator = tracedContinents ? ContinentGenerator.traced() : new ContinentGenerator();
        long fieldSeed = worldSeed ^ 0x4F55544552504F49L;
        field = new PoissonNoise(fieldSeed, CONTINENT_SPACING, 16);
        oceanFloor = new OceanFloor(worldSeed);
        plannerConfig = GenerationSettings.defaults();
        erosion = new TerrainErosion(worldSeed);
        register("ocean-floor", "Ocean floor", new OceanFloorStep(), null, null);
        register("continent-composition", "Continent composition", new ContinentCompositionStep(), null, null);
        register(
            "interpolation",
            "Continuous interpolation",
            new InterpolationStep(),
            PreviewDecorations::worldHeight,
            null);
        register("erosion", "Erosion", new ErosionStep(), null, null);
        register("recovery", "Recovery", new RecoveryStep(), null, null);
        register(
            "sedimentation",
            "Sedimentation",
            new SedimentationStep(),
            PreviewDecorations::worldHeight,
            PreviewDecorations::heightTooltip);
        List<GenerationStep> registered = new ArrayList<>();
        registered.addAll(continentGenerator.steps());
        registered.addAll(worldSteps);
        registeredSteps = Collections.unmodifiableList(registered);
        List<GenerationStep> visible = new ArrayList<>();
        for (GenerationStep step : registered) if (step.hasView()) visible.add(step);
        previewSteps = Collections.unmodifiableList(visible);
    }

    public static WorldGenerator forPreview(long worldSeed) {
        return new WorldGenerator(worldSeed, true);
    }

    private void register(String id, String label, IWorldStep step, ToIntFunction<ComposedSample> colour,
        Function<ComposedSample, String> tooltip) {
        worldSteps.add(new WorldStepDefinition(id, label, worldSteps.size(), step, colour, tooltip));
    }

    public List<GenerationStep> steps() {
        return previewSteps;
    }

    public List<GenerationStep> registeredSteps() {
        return registeredSteps;
    }

    public List<WorldStepDefinition> worldSteps() {
        return Collections.unmodifiableList(worldSteps);
    }

    private List<Placement> nearestContinents(double worldX, double worldY) {
        return nearestContinents(worldX, worldY, 5);
    }

    private List<Placement> nearestContinents(double worldX, double worldY, int count) {
        List<Placement> result = new ArrayList<>(count);
        Query query = nearestQuery.get();
        field.nearest(worldX, worldY, count, query);
        for (int i = 0; i < count; i++) {
            result.add(new Placement(query.ids[i], query.x[i], query.y[i], query.x[i], query.y[i], query.distance[i]));
        }
        return result;
    }

    public void publish(PlacedContinent plan) {
        plans.put(plan.seed.id, plan);
    }

    public void remove(long siteId) {
        plans.remove(siteId);
    }

    public PlacedContinent loaded(long siteId) {
        return plans.get(siteId);
    }

    public List<TerrainLabel> terrainLabels(Rectangle2D bounds) {
        List<TerrainLabel> labels = new ArrayList<>();
        ContinentGenerator.StepDefinition labelStep = null;
        for (ContinentGenerator.StepDefinition step : continentGenerator.steps()) if (step.labels != null) {
            labelStep = step;
            break;
        }
        if (labelStep == null) return labels;
        for (PlacedContinent plan : plans.values()) {
            double originX = plan.seed.centerX - plan.plannerWidth() * 0.5;
            double originY = plan.seed.centerY - plan.plannerWidth() * 0.5;
            for (TerrainRegion region : labelStep.labels.apply(plan.continent)) {
                double x = originX + region.centerX;
                double y = originY + region.centerY;
                if (!bounds.contains(x, y)) continue;
                ComposedSample composed = sample(
                    x,
                    y,
                    continentGenerator.steps()
                        .get(5));
                if (composed.elevation > plannerConfig.seaLevel && composed.terrainRegionId != 0) labels.add(
                    new TerrainLabel(
                        region.name,
                        presetLabels(region),
                        x,
                        y,
                        region.cellCount,
                        plan.seed.id,
                        region.id));
            }
        }
        return labels;
    }

    public ComposedSample sample(double worldX, double worldY, GenerationStep stage) {
        if (stage instanceof WorldStepDefinition) {
            int index = ((WorldStepDefinition) stage).index;
            TerrainProfile profile = terrainProfile(worldX, worldY);
            double height = index == 2 ? profile.sourceHeight
                : index == 3 ? profile.erodedHeight : index == 4 ? profile.recoveredHeight : profile.sedimentedHeight;
            return new ComposedSample(
                height,
                profile.siteId,
                profile.plateId,
                profile.oceanic,
                profile.loadedCategory,
                profile.complete);
        }
        return sampleRaw(worldX, worldY, stage);
    }

    public ComposedSample sample(double worldX, double worldY, GenerationStep stage, TimingSession timings) {
        if (!(stage instanceof WorldStepDefinition)) return sample(worldX, worldY, stage);
        SampleTiming timing = new SampleTiming();
        activeTiming.set(timing);
        try {
            TerrainProfile profile = terrainProfile(worldX, worldY);
            timings.add(timing);
            double height = ((WorldStepDefinition) stage).index == 2 ? profile.sourceHeight : profile.sedimentedHeight;
            return new ComposedSample(
                height,
                profile.sourceHeight,
                profile.sedimentedHeight,
                profile.siteId,
                profile.plateId,
                profile.oceanic,
                profile.loadedCategory,
                profile.complete);
        } finally {
            activeTiming.remove();
        }
    }

    private static String presetLabels(TerrainRegion region) {
        return region.noiseErosion ? "Phacelle Noise Erosion" : "Base Smooth Heightmap";
    }

    /** Samples the exact source, erosion and recovery pipeline at one world-space point. */
    public TerrainProfile terrainProfile(double worldX, double worldY) {
        return sampleTerrainProfile(worldX, worldY);
    }

    public Column sampleColumn(double blockX, double blockZ) {
        TerrainProfile profile = terrainProfile(blockX / BLOCKS_PER_WORLD_UNIT, -blockZ / BLOCKS_PER_WORLD_UNIT);
        Column column = new Column(blockX, blockZ);
        column.sourceHeight = profile.sourceHeight;
        column.erodedHeight = profile.erodedHeight;
        column.recoveredHeight = profile.recoveredHeight;
        column.sedimentHeight = profile.pureSedimentHeight;
        column.finalHeight = profile.sedimentedHeight;
        column.height = profile.sedimentedHeight;
        column.sourceGrade = profile.sourceGrade;
        column.sedimentGrade = profile.pureSedimentGrade;
        column.sedimentMix = profile.sedimentMix;
        column.continentId = profile.siteId;
        column.plateId = profile.plateId;
        column.oceanic = profile.oceanic;
        column.complete = profile.complete;
        Map<GenerationStep, Long> timings = latestTimings.get();
        timings.clear();
        long cumulative = 0L;
        for (int index = 0; index < worldSteps.size(); index++) {
            long started = System.nanoTime();
            worldSteps.get(index).step.apply(this, column);
            cumulative += System.nanoTime() - started;
            timings.put(worldSteps.get(index), cumulative);
        }
        return column;
    }

    public Map<GenerationStep, Long> latestTimingsNanos() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(latestTimings.get()));
    }

    public TimingSession newTimingSession() {
        return new TimingSession();
    }

    public void prepare(Rectangle2D blockBounds) {
        Rectangle2D bounds = new Rectangle2D.Double(
            blockBounds.getMinX() / BLOCKS_PER_WORLD_UNIT,
            -blockBounds.getMaxY() / BLOCKS_PER_WORLD_UNIT,
            blockBounds.getWidth() / BLOCKS_PER_WORLD_UNIT,
            blockBounds.getHeight() / BLOCKS_PER_WORLD_UNIT);
        List<PlacedContinent> viewport = new ArrayList<>();
        for (Placement site : requiredContinents(bounds)) viewport.add(
            plans.computeIfAbsent(
                site.id,
                ignored -> new PlacedContinent(
                    site,
                    planSeed(site.id),
                    continentGenerator.generate(planSeed(site.id)))));
        latestViewportPlans = Collections.unmodifiableList(viewport);
    }

    public Map<GenerationStep, Double> continentTimingsMicrosPerHex() {
        long[] nanos = new long[continentGenerator.steps()
            .size()];
        long hexes = 0L;
        for (PlacedContinent placed : latestViewportPlans) {
            hexes += placed.continent.grid.coordinates()
                .size();
            for (Map.Entry<Integer, Long> timing : placed.continent.timingsNanos()
                .entrySet()) nanos[timing.getKey()] += timing.getValue();
        }
        Map<GenerationStep, Double> result = new LinkedHashMap<>();
        double cumulative = 0.0;
        for (int index = 0; index < continentGenerator.steps()
            .size(); index++) {
            cumulative += nanos[index] / 1_000.0 / Math.max(1L, hexes);
            result.put(
                continentGenerator.steps()
                    .get(index),
                cumulative);
        }
        return result;
    }

    /** Smooth terrain-preset influence used by the final heightmap composition. */
    public double noiseErosionWeight(double worldX, double worldY) {
        return sampleSmoothedHeightmap(worldX, worldY, heightmapWorkspaces.get().center).noiseErosionWeight;
    }

    private ComposedSample sampleRaw(double worldX, double worldY, GenerationStep stage) {
        long started = System.nanoTime();
        Query nearest = nearestQuery.get();
        field.nearest(worldX, worldY, COMPOSITION_SITES, nearest);
        long oceanStarted = System.nanoTime();
        OceanFloor.Sample background = oceanFloor.sample(worldX, worldY, stage);
        long oceanNanos = System.nanoTime() - oceanStarted;
        double[] weights = compositionWeights.get();
        double total = 0.0;
        double nearestDistance = nearest.distance[0];
        for (int i = 0; i < COMPOSITION_SITES; i++) {
            weights[i] = Math.exp(-(nearest.distance[i] - nearestDistance) / 24.0);
            total += weights[i];
        }
        double underwaterDelta = 0.0;
        double landNumerator = 0.0;
        double landDenominator = 0.0;
        double noiseErosionNumerator = 0.0;
        double categoryWeight = 0.0;
        long categorySite = background.plateId;
        int plateId = (int) (background.plateId ^ background.plateId >>> 32);
        boolean oceanic = true;
        double velocityX = 0.0, velocityY = 0.0, convergence = 0.0, divergence = 0.0;
        int terrainRegionId = 0;
        boolean loadedCategory = false;
        boolean complete = true;
        for (int i = 0; i < COMPOSITION_SITES; i++) {
            double weight = total == 0.0 ? (i == 0 ? 1.0 : 0.0) : weights[i] / total;
            double influence = planInfluence(worldX, worldY, nearest.x[i], nearest.y[i]);
            if (influence <= 0.0) continue;
            PlacedContinent plan = plans.get(nearest.ids[i]);
            if (plan == null) {
                if (weight * influence > 1.0e-4) complete = false;
                continue;
            }
            LocalSample local = plan.sample(worldX, worldY, stage, background.elevation);
            double elevationDelta = local.elevation - background.elevation;
            if (local.elevation > local.seaLevel) {
                double activation = weight * Math.exp(elevationDelta * HEIGHT_SOFTMAX_SHARPNESS);
                landNumerator += activation * elevationDelta;
                landDenominator += activation;
                noiseErosionNumerator += activation * local.noiseErosionWeight;
            } else {
                underwaterDelta += elevationDelta * weight;
            }
            double categorical = weight * local.falloff;
            if (insidePlan(worldX, worldY, nearest.x[i], nearest.y[i]) && categorical > categoryWeight) {
                categoryWeight = categorical;
                categorySite = nearest.ids[i];
                plateId = local.plateId;
                oceanic = local.oceanic;
                velocityX = local.velocityX;
                velocityY = local.velocityY;
                convergence = local.convergence;
                divergence = local.divergence;
                terrainRegionId = local.terrainRegionId;
                loadedCategory = true;
            }
        }
        double elevation = background.elevation
            + (landDenominator > 0.0 ? landNumerator / landDenominator : underwaterDelta);
        double noiseErosionWeight = landDenominator > 0.0 ? noiseErosionNumerator / landDenominator : 0.0;
        elevation = Math.max(MINIMUM_OCEAN_HEIGHT, elevation);
        if (stage instanceof ContinentGenerator.StepDefinition
            && ((ContinentGenerator.StepDefinition) stage).labels != null
            && elevation <= plannerConfig.seaLevel) terrainRegionId = 0;
        ComposedSample result = new ComposedSample(
            elevation,
            categorySite,
            plateId,
            oceanic,
            loadedCategory,
            complete,
            velocityX,
            velocityY,
            convergence,
            divergence,
            terrainRegionId,
            noiseErosionWeight);
        SampleTiming timing = activeTiming.get();
        if (timing != null) {
            timing.nanos[0] += oceanNanos;
            timing.nanos[1] += Math.max(0L, System.nanoTime() - started - oceanNanos);
        }
        return result;
    }

    private static boolean insidePlan(double worldX, double worldY, double centerX, double centerY) {
        double halfWidth = PLAN_WIDTH * 0.5;
        return worldX >= centerX - halfWidth && worldX < centerX + halfWidth
            && worldY >= centerY - halfWidth
            && worldY < centerY + halfWidth;
    }

    private TerrainProfile sampleTerrainProfile(double worldX, double worldY) {
        HeightmapWorkspaces workspaces = heightmapWorkspaces.get();
        ComposedSample center = sampleSmoothedHeightmap(worldX, worldY, workspaces.center);
        double sourceHeight = toBlockHeight(center.elevation);
        if (!center.complete || center.elevation <= plannerConfig.seaLevel) {
            return profile(
                center,
                sourceHeight,
                sourceHeight,
                sourceHeight,
                sourceHeight,
                sourceHeight,
                0.0,
                0.0,
                0.0,
                center.complete);
        }

        double step = plannerConfig.erosionGradientStep;
        ComposedSample left = sampleSmoothedHeightmap(worldX - step, worldY, workspaces.left);
        ComposedSample right = sampleSmoothedHeightmap(worldX + step, worldY, workspaces.right);
        ComposedSample down = sampleSmoothedHeightmap(worldX, worldY - step, workspaces.down);
        ComposedSample up = sampleSmoothedHeightmap(worldX, worldY + step, workspaces.up);
        boolean complete = left.complete && right.complete && down.complete && up.complete;
        if (!complete) {
            return profile(
                center,
                sourceHeight,
                sourceHeight,
                sourceHeight,
                sourceHeight,
                sourceHeight,
                0.0,
                0.0,
                0.0,
                false);
        }

        long erosionStarted = System.nanoTime();
        double strength = smoothRange(
            center.elevation,
            plannerConfig.seaLevel,
            plannerConfig.erosionShoreFadeEndHeight);
        double slopeX = (right.elevation - left.elevation) / (2.0 * step) * plannerConfig.erosionSlopeScale;
        double slopeY = (up.elevation - down.elevation) / (2.0 * step) * plannerConfig.erosionSlopeScale;
        double sourceGrade = Math.hypot(slopeX, slopeY);
        double eroded = center.elevation;
        if (center.noiseErosionWeight > 0.0) {
            double erosionOutput = erosion.apply(worldX, worldY, center.elevation, strength, slopeX, slopeY);
            eroded = weightedTerrainComposition(center.elevation, erosionOutput, center.noiseErosionWeight);
        }
        SampleTiming timing = activeTiming.get();
        if (timing != null) timing.nanos[3] += System.nanoTime() - erosionStarted;
        long recoveryStarted = System.nanoTime();
        double recovered = recoverIncision(center.elevation, eroded);
        double erodedBlock = toBlockHeight(Math.max(MINIMUM_OCEAN_HEIGHT, eroded));
        if (timing != null) timing.nanos[4] += System.nanoTime() - recoveryStarted;
        long sedimentStarted = System.nanoTime();
        double saturatedBaseBlock = saturatedSedimentBase(sourceHeight, worldX, worldY);
        double sedimentedBlock = verySoftSedimentMaximum(erodedBlock, saturatedBaseBlock);
        if (timing != null) timing.nanos[5] += System.nanoTime() - sedimentStarted;
        return profile(
            center,
            sourceHeight,
            erodedBlock,
            toBlockHeight(Math.max(MINIMUM_OCEAN_HEIGHT, recovered)),
            saturatedBaseBlock,
            sedimentedBlock,
            sourceGrade,
            0.0,
            1.0,
            true);
    }

    private static double weightedTerrainComposition(double base, double presetOutput, double presetWeight) {
        double weight = Math.max(0.0, Math.min(1.0, presetWeight));
        double baseWeight = 1.0 - weight;
        return (base * baseWeight + presetOutput * weight) / (baseWeight + weight);
    }

    private double recoverIncision(double source, double eroded) {
        double rawCut = source - eroded;
        if (rawCut <= 0.0) return eroded;
        double clearance = source - plannerConfig.seaLevel;
        double maximum = plannerConfig.terrainRecoveryMaximumIncision;
        double allowed = maximum * Math.tanh(clearance * plannerConfig.terrainRecoveryClearanceFraction / maximum);
        if (allowed <= 1.0e-12) return source;
        return source - allowed * Math.tanh(rawCut / allowed);
    }

    private TerrainProfile profile(ComposedSample source, double sourceHeight, double erodedHeight,
        double recoveredHeight, double pureSedimentHeight, double sedimentedHeight, double sourceGrade,
        double pureSedimentGrade, double sedimentMix, boolean complete) {
        return new TerrainProfile(
            sourceHeight,
            erodedHeight,
            recoveredHeight,
            pureSedimentHeight,
            sedimentedHeight,
            sourceGrade,
            pureSedimentGrade,
            sedimentMix,
            source.siteId,
            source.plateId,
            source.oceanic,
            source.loadedCategory,
            complete);
    }

    private double toBlockHeight(double elevation) {
        if (elevation <= plannerConfig.seaLevel) return elevation / plannerConfig.seaLevel * WATER_LEVEL;
        double land = (elevation - plannerConfig.seaLevel)
            / (plannerConfig.peaksMaximumHeight - plannerConfig.seaLevel);
        return lerp(WATER_LEVEL, PEAK_TARGET_HEIGHT, land);
    }

    private double saturatedSedimentBase(double smoothBaseHeight, double worldX, double worldY) {
        double landRange = PEAK_TARGET_HEIGHT - WATER_LEVEL;
        double noiseX = worldX * SEDIMENT_BASE_VARIATION_SCALE;
        double noiseY = worldY * SEDIMENT_BASE_VARIATION_SCALE;
        double variation = scalarNoise(noiseX, noiseY, 11) * 0.7 + scalarNoise(noiseX * 2.0, noiseY * 2.0, 12) * 0.3;
        double plateauFraction = SEDIMENT_BASE_PLATEAU_LAND_FRACTION
            + variation * SEDIMENT_BASE_VARIATION_LAND_FRACTION;
        double plateau = WATER_LEVEL + landRange * plateauFraction;
        double transition = landRange * SEDIMENT_BASE_SATURATION_LAND_FRACTION;
        return smoothMinimum(smoothBaseHeight, plateau, transition);
    }

    private static double verySoftSedimentMaximum(double erosionHeight, double saturatedBaseHeight) {
        double transition = (PEAK_TARGET_HEIGHT - WATER_LEVEL) * SEDIMENT_VERY_SOFT_MAX_LAND_FRACTION;
        return smoothMaximum(erosionHeight, saturatedBaseHeight, transition);
    }

    /** Compact C2 smooth minimum: exact outside the transition interval. */
    private static double smoothMinimum(double first, double second, double transition) {
        if (first <= second - transition) return first;
        if (first >= second + transition) return second;
        double t = (first - second + transition) / (2.0 * transition);
        return second - transition + 2.0 * transition * (t - t * t * t + 0.5 * t * t * t * t);
    }

    private static double smoothMaximum(double first, double second, double transition) {
        return -smoothMinimum(-first, -second, transition);
    }

    private ComposedSample sampleSmoothedHeightmap(double worldX, double worldY, HeightmapWorkspace workspace) {
        long started = System.nanoTime();
        SampleTiming timing = activeTiming.get();
        long nestedBefore = timing == null ? 0L : timing.nanos[0] + timing.nanos[1];
        workspace.reset();
        long categoryKey = AxialCoordinate.packedFromWorld(worldX, worldY);
        ComposedSample category = sampleAt((int) categoryKey, (int) (categoryKey >>> 32), workspace);
        double warpedX = worldX
            + scalarNoise(worldX * HEIGHTMAP_WARP_SCALE, worldY * HEIGHTMAP_WARP_SCALE, 0) * HEIGHTMAP_WARP_STRENGTH;
        double warpedY = worldY
            + scalarNoise(worldX * HEIGHTMAP_WARP_SCALE, worldY * HEIGHTMAP_WARP_SCALE, 1) * HEIGHTMAP_WARP_STRENGTH;
        addSplineWeights(warpedX, warpedY, workspace);
        double elevation = 0.0;
        double noiseErosionWeight = 0.0;
        double totalWeight = 0.0;
        for (int i = 0; i < workspace.weightedCount; i++) {
            long key = workspace.weightedKeys[i];
            ComposedSample vertex = sampleAt((int) key, (int) (key >>> 32), workspace);
            double weight = workspace.weights[i];
            elevation += vertex.elevation * weight;
            noiseErosionWeight += vertex.noiseErosionWeight * weight;
            totalWeight += weight;
        }
        ComposedSample result = new ComposedSample(
            Math.max(MINIMUM_OCEAN_HEIGHT, elevation / totalWeight),
            category.siteId,
            category.plateId,
            category.oceanic,
            category.loadedCategory,
            category.complete && workspace.complete,
            noiseErosionWeight / totalWeight);
        if (timing != null) {
            long nested = timing.nanos[0] + timing.nanos[1] - nestedBefore;
            timing.nanos[2] += Math.max(0L, System.nanoTime() - started - nested);
        }
        return result;
    }

    /** C2 cubic reconstruction symmetric across the triangular lattice's three axes. */
    private static void addSplineWeights(double x, double y, HeightmapWorkspace workspace) {
        double denominator = 2.0 * Math.sqrt(3.0) / 3.0;
        double q = x * 0.5 + y / denominator;
        double r = x * 0.5 - y / denominator;
        int q0 = floor(q), r0 = floor(r);
        for (int sampleQ = q0 - 1; sampleQ <= q0 + 2; sampleQ++) {
            double qWeight = cubicSpline(q - sampleQ);
            if (qWeight == 0.0) continue;
            for (int sampleR = r0 - 1; sampleR <= r0 + 2; sampleR++) {
                double weight = qWeight * cubicSpline(r - sampleR) * cubicSpline(q + r - sampleQ - sampleR);
                workspace.addWeight(sampleQ, sampleR, weight);
            }
        }
    }

    private static double cubicSpline(double value) {
        double distance = Math.abs(value);
        if (distance < 1.0) return 2.0 / 3.0 - distance * distance + 0.5 * distance * distance * distance;
        if (distance < 2.0) {
            double remainder = 2.0 - distance;
            return remainder * remainder * remainder / 6.0;
        }
        return 0.0;
    }

    private ComposedSample sampleAt(int q, int r, HeightmapWorkspace workspace) {
        long key = (long) q & 0xffffffffL | (long) r << 32;
        for (int i = 0; i < workspace.count; i++) if (workspace.keys[i] == key) return workspace.samples[i];
        ComposedSample cached = workspace.cached(key);
        if (cached != null) {
            int index = workspace.count++;
            workspace.keys[index] = key;
            workspace.samples[index] = cached;
            return cached;
        }
        double x = q + r;
        double y = (q - r) * Math.sqrt(3.0) / 3.0;
        ComposedSample sample = sampleRaw(
            x,
            y,
            continentGenerator.steps()
                .get(5));
        int index = workspace.count++;
        workspace.keys[index] = key;
        workspace.samples[index] = sample;
        workspace.complete &= sample.complete;
        if (sample.complete) workspace.cache(key, sample);
        return sample;
    }

    private double scalarNoise(double x, double y, int salt) {
        int x0 = floor(x), y0 = floor(y);
        double tx = smoothstep(x - x0), ty = smoothstep(y - y0);
        double a = signedNoise(x0, y0, salt), b = signedNoise(x0 + 1, y0, salt);
        double c = signedNoise(x0, y0 + 1, salt), d = signedNoise(x0 + 1, y0 + 1, salt);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
    }

    private double signedNoise(int x, int y, int salt) {
        long value = mix(
            worldSeed ^ (long) x * 341873128712L
                ^ (long) y * 132897987541L
                ^ (long) salt * 0x9E3779B97F4A7C15L
                ^ 0x4845494748544D50L);
        return (value >>> 11) * 0x1.0p-52 - 1.0;
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static double smoothstep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double start, double end, double value) {
        return start + (end - start) * value;
    }

    private static double normalize(double value, double minimum, double maximum) {
        return Math.max(0.0, Math.min(1.0, (value - minimum) / Math.max(1.0e-9, maximum - minimum)));
    }

    private static double smoothRange(double value, double minimum, double maximum) {
        return smoothstep(normalize(value, minimum, maximum));
    }

    private List<Placement> requiredContinents(Rectangle2D bounds) {
        double padding = PLAN_WIDTH * 0.5 + 30.0;
        double minimumX = bounds.getMinX() - padding;
        double maximumX = bounds.getMaxX() + padding;
        double minimumY = bounds.getMinY() - padding;
        double maximumY = bounds.getMaxY() + padding;
        Set<Long> seen = new LinkedHashSet<>();
        List<Placement> result = new ArrayList<>();
        double step = CONTINENT_SPACING * 0.35;
        for (double y = minimumY; y <= maximumY + step; y += step) {
            for (double x = minimumX; x <= maximumX + step; x += step) {
                for (Placement seed : nearestContinents(x, y, COMPOSITION_SITES)) {
                    double reach = PLAN_WIDTH * 0.5 + PLAN_FADE_DISTANCE;
                    if (seed.centerX + reach < bounds.getMinX() || seed.centerX - reach > bounds.getMaxX()
                        || seed.centerY + reach < bounds.getMinY()
                        || seed.centerY - reach > bounds.getMaxY()) continue;
                    if (seen.add(seed.id)) result.add(seed);
                }
            }
        }
        return result;
    }

    private static double planInfluence(double worldX, double worldY, double centerX, double centerY) {
        double localX = worldX - centerX + PLAN_WIDTH * 0.5;
        double localY = worldY - centerY + PLAN_WIDTH * 0.5;
        double edgeDistance = Math.min(Math.min(localX, PLAN_WIDTH - localX), Math.min(localY, PLAN_WIDTH - localY));
        double value = Math.max(0.0, Math.min(1.0, (edgeDistance + PLAN_FADE_DISTANCE) / (PLAN_FADE_DISTANCE * 2.0)));
        return value * value * (3.0 - 2.0 * value);
    }

    public long planSeed(long siteId) {
        return mix(worldSeed ^ siteId ^ PLAN_SALT);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }

    private static final class HeightmapWorkspace {

        private final HeightmapSampleCache cache;
        final long[] keys = new long[32];
        final ComposedSample[] samples = new ComposedSample[32];
        final long[] weightedKeys = new long[32];
        final double[] weights = new double[32];
        int count;
        int weightedCount;
        boolean complete;

        HeightmapWorkspace(HeightmapSampleCache cache) {
            this.cache = cache;
        }

        void reset() {
            count = 0;
            weightedCount = 0;
            complete = true;
        }

        void addWeight(int q, int r, double weight) {
            if (weight == 0.0) return;
            long key = (long) q & 0xffffffffL | (long) r << 32;
            for (int i = 0; i < weightedCount; i++) if (weightedKeys[i] == key) {
                weights[i] += weight;
                return;
            }
            weightedKeys[weightedCount] = key;
            weights[weightedCount++] = weight;
        }

        ComposedSample cached(long key) {
            return cache.get(key);
        }

        void cache(long key, ComposedSample sample) {
            cache.put(key, sample);
        }
    }

    /** One shared lattice-sample cache per sampling thread. */
    private static final class HeightmapSampleCache {

        private static final int MASK = 8191;
        private final long[] keys = new long[MASK + 1];
        private final ComposedSample[] samples = new ComposedSample[MASK + 1];
        private final boolean[] valid = new boolean[MASK + 1];

        ComposedSample get(long key) {
            int index = (int) mix(key) & MASK;
            return valid[index] && keys[index] == key ? samples[index] : null;
        }

        void put(long key, ComposedSample sample) {
            int index = (int) mix(key) & MASK;
            keys[index] = key;
            samples[index] = sample;
            valid[index] = true;
        }
    }

    private static final class HeightmapWorkspaces {

        private final HeightmapSampleCache cache = new HeightmapSampleCache();
        final HeightmapWorkspace center = new HeightmapWorkspace(cache);
        final HeightmapWorkspace left = new HeightmapWorkspace(cache);
        final HeightmapWorkspace right = new HeightmapWorkspace(cache);
        final HeightmapWorkspace down = new HeightmapWorkspace(cache);
        final HeightmapWorkspace up = new HeightmapWorkspace(cache);
    }

    public static final class TerrainProfile {

        public final double sourceHeight;
        public final double erodedHeight;
        public final double recoveredHeight;
        public final double pureSedimentHeight;
        public final double sedimentedHeight;
        public final double sourceGrade;
        public final double pureSedimentGrade;
        public final double sedimentMix;
        public final long siteId;
        public final int plateId;
        public final boolean oceanic;
        public final boolean loadedCategory;
        public final boolean complete;

        TerrainProfile(double sourceHeight, double erodedHeight, double recoveredHeight, double pureSedimentHeight,
            double sedimentedHeight, double sourceGrade, double pureSedimentGrade, double sedimentMix, long siteId,
            int plateId, boolean oceanic, boolean loadedCategory, boolean complete) {
            this.sourceHeight = sourceHeight;
            this.erodedHeight = erodedHeight;
            this.recoveredHeight = recoveredHeight;
            this.pureSedimentHeight = pureSedimentHeight;
            this.sedimentedHeight = sedimentedHeight;
            this.sourceGrade = sourceGrade;
            this.pureSedimentGrade = pureSedimentGrade;
            this.sedimentMix = sedimentMix;
            this.siteId = siteId;
            this.plateId = plateId;
            this.oceanic = oceanic;
            this.loadedCategory = loadedCategory;
            this.complete = complete;
        }
    }

    public static final class ComposedSample {

        public final double elevation;
        public final double sourceElevation;
        public final double finalElevation;
        public final long siteId;
        public final int plateId;
        public final boolean oceanic;
        public final boolean loadedCategory;
        public final boolean complete;
        public final double velocityX, velocityY, convergence, divergence;
        public final int terrainRegionId;
        public final double noiseErosionWeight;

        ComposedSample(double elevation, long siteId, int plateId, boolean oceanic, boolean loadedCategory,
            boolean complete) {
            this(elevation, elevation, elevation, siteId, plateId, oceanic, loadedCategory, complete);
        }

        ComposedSample(double elevation, double sourceElevation, double finalElevation, long siteId, int plateId,
            boolean oceanic, boolean loadedCategory, boolean complete) {
            this.elevation = elevation;
            this.sourceElevation = sourceElevation;
            this.finalElevation = finalElevation;
            this.siteId = siteId;
            this.plateId = plateId;
            this.oceanic = oceanic;
            this.loadedCategory = loadedCategory;
            this.complete = complete;
            this.velocityX = 0.0;
            this.velocityY = 0.0;
            this.convergence = 0.0;
            this.divergence = 0.0;
            this.terrainRegionId = 0;
            this.noiseErosionWeight = 0.0;
        }

        ComposedSample(double elevation, long siteId, int plateId, boolean oceanic, boolean loadedCategory,
            boolean complete, double noiseErosionWeight) {
            this(
                elevation,
                siteId,
                plateId,
                oceanic,
                loadedCategory,
                complete,
                0.0,
                0.0,
                0.0,
                0.0,
                0,
                noiseErosionWeight);
        }

        ComposedSample(double elevation, long siteId, int plateId, boolean oceanic, boolean loadedCategory,
            boolean complete, double velocityX, double velocityY, double convergence, double divergence,
            int terrainRegionId, double noiseErosionWeight) {
            this.elevation = elevation;
            this.sourceElevation = elevation;
            this.finalElevation = elevation;
            this.siteId = siteId;
            this.plateId = plateId;
            this.oceanic = oceanic;
            this.loadedCategory = loadedCategory;
            this.complete = complete;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.convergence = convergence;
            this.divergence = divergence;
            this.terrainRegionId = terrainRegionId;
            this.noiseErosionWeight = noiseErosionWeight;
        }
    }

    public static final class TerrainLabel {

        public final String text;
        public final String presetText;
        public final double worldX, worldY;
        public final int cellCount;
        public final long regionKey;

        TerrainLabel(String text, String presetText, double worldX, double worldY, int cellCount, long siteId,
            int regionId) {
            this.text = text;
            this.presetText = presetText;
            this.worldX = worldX;
            this.worldY = worldY;
            this.cellCount = cellCount;
            regionKey = siteId * 0x9E3779B97F4A7C15L ^ Integer.toUnsignedLong(regionId);
        }
    }

    public final class TimingSession {

        private final LongAdder[] nanos = new LongAdder[6];
        private final LongAdder samples = new LongAdder();

        public TimingSession() {
            for (int index = 0; index < nanos.length; index++) nanos[index] = new LongAdder();
        }

        private void add(SampleTiming timing) {
            for (int index = 0; index < nanos.length; index++) nanos[index].add(timing.nanos[index]);
            samples.increment();
        }

        public Map<GenerationStep, Double> cumulativeMicrosPerSample() {
            Map<GenerationStep, Double> result = new LinkedHashMap<>();
            double cumulative = 0.0;
            long count = Math.max(1L, samples.sum());
            for (int index = 0; index < worldSteps.size(); index++) {
                cumulative += nanos[index].sum() / 1_000.0 / count;
                result.put(worldSteps.get(index), cumulative);
            }
            return result;
        }
    }

    private static final class SampleTiming {

        final long[] nanos = new long[6];
    }

    public static final class WorldStepDefinition implements GenerationStep {

        public final String id;
        public final String label;
        public final int index;
        public final IWorldStep step;
        public final ToIntFunction<ComposedSample> colour;
        public final Function<ComposedSample, String> tooltip;

        WorldStepDefinition(String id, String label, int index, IWorldStep step, ToIntFunction<ComposedSample> colour,
            Function<ComposedSample, String> tooltip) {
            this.id = id;
            this.label = label;
            this.index = index;
            this.step = step;
            this.colour = colour;
            this.tooltip = tooltip;
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

    /** World placement metadata deliberately hidden behind the generator facade. */
    static final class Placement {

        final long id;
        final double poissonX, poissonY, centerX, centerY, distance;

        Placement(long id, double poissonX, double poissonY, double centerX, double centerY, double distance) {
            this.id = id;
            this.poissonX = poissonX;
            this.poissonY = poissonY;
            this.centerX = centerX;
            this.centerY = centerY;
            this.distance = distance;
        }
    }

}
