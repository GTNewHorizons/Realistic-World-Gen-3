package com.irythill.rwg3.procgen.continent;

/** Immutable tuning values ported from the iVy high-level planner. */
public final class GenerationSettings {

    private static final double NORMALIZED_SEA_LEVEL = 1.0;
    private static final double NORMALIZED_MAXIMUM_HEIGHT = 2.25;
    private static final double BLOCK_SEA_LEVEL = 63.0;
    private static final double BLOCK_PEAK_TARGET_HEIGHT = 800.0;

    // Continent setup / PlatePlacementStep
    public final double gridWidth = 120.0;
    public final int plateSize = 22;
    public final double driftMultiplier = 3.0; // Also used in PlateMotionStep.
    public final double spinMultiplier = 8.0e-4; // Also used in PlateMotionStep.

    // OceanicPlatesStep
    public final double exteriorMargin = 3.0;
    public final int internalCherryPick = 1;
    public final int externalCherryPick = 2;

    // BaseHeightStep
    public final double seaLevel = NORMALIZED_SEA_LEVEL; // Used in many places.
    public final double featureDecayRate = 0.82;
    public final double seaFloorHeight = 0.33;
    public final int seaShoreDetectionRadius = 30;
    public final double seaDivergenceHeight = 0.15;
    public final int seaDivergenceDetectionRadius = 10;
    public final double seaDivergenceDecayFactor = 1.0;
    public final double seaConvergenceHeight = 1.4;
    public final int seaConvergenceDetectionRadius = 20;
    public final double seaConvergenceDecayFactor = 0.5;
    public final double continentHeight = 1.05;
    public final int continentShoreDetectionRadius = 30;
    public final double continentDivergenceHeight = 1.05;
    public final int continentDivergenceDetectionRadius = 30;
    public final double continentDivergenceDecayFactor = 0.5;
    public final double continentConvergenceHeight = 2.0;
    public final int continentConvergenceDetectionRadius = 30;
    public final double continentConvergenceDecayFactor = 1.0;

    // HeightRedistributionStep
    public final double plainsFraction = 0.60;
    public final double hillsFraction = 0.25;
    public final double mountainsFraction = 0.12;
    public final double heightRemappingStrength = 0.75;
    public final double heightRemappingCurveSmoothing = 0.50;
    public final double landMinimumHeight = normalizedHeight(65.0);
    public final double plainsMaximumHeight = normalizedHeight(75.0);
    public final double hillsMaximumHeight = normalizedHeight(150.0);
    public final double mountainsMaximumHeight = normalizedHeight(600.0);
    public final double peaksMaximumHeight = NORMALIZED_MAXIMUM_HEIGHT; // Used in many places.

    // TerrainRegionsStep
    public final double terrainRegionHillMinimumHeight = normalizedHeight(120.0);
    public final double terrainRegionMountainMinimumHeight = normalizedHeight(300.0);
    public final double terrainRegionPlainMaximumHeight = normalizedHeight(90.0);
    public final int terrainRegionOceanClearance = 5;
    public final int terrainPresetTransitionRadius = 8;

    // ErosionStep
    public final double erosionShoreFadeEndHeight = normalizedHeight(80.0);
    public final double erosionScale = 2.7;
    public final double erosionStrength = 0.09333333333333334;
    public final double erosionGullyWeight = 0.5;
    public final double erosionDetail = 1.5;
    public final int erosionMaximumOctaves = 5;
    public final double erosionLacunarity = 2.0;
    public final double erosionGain = 0.5;
    public final double erosionAssumedSlope = 0.7;
    public final double erosionSlopeOverride = 1.0;
    public final double erosionSlopeScale = 7.0752;
    public final double erosionGradientStep = 0.25;

    // RecoveryStep
    public final double terrainRecoveryClearanceFraction = 0.50;
    public final double terrainRecoveryMaximumIncision = normalizedHeightSpan(16.0);

    // SedimentationStep
    public final double sedimentationStrength = 0.75;
    public final double sedimentationFullMixGrade = 0.40;
    public final double sedimentationNoMixGrade = 1.20;

    GenerationSettings() {}

    public static GenerationSettings defaults() {
        return new GenerationSettings();
    }

    private static double normalizedHeight(double blockHeight) {
        double fraction = (blockHeight - BLOCK_SEA_LEVEL) / (BLOCK_PEAK_TARGET_HEIGHT - BLOCK_SEA_LEVEL);
        return NORMALIZED_SEA_LEVEL + fraction * (NORMALIZED_MAXIMUM_HEIGHT - NORMALIZED_SEA_LEVEL);
    }

    private static double normalizedHeightSpan(double blockHeight) {
        return blockHeight / (BLOCK_PEAK_TARGET_HEIGHT - BLOCK_SEA_LEVEL)
            * (NORMALIZED_MAXIMUM_HEIGHT - NORMALIZED_SEA_LEVEL);
    }

    public double blockHeight(double normalizedHeight) {
        if (normalizedHeight <= seaLevel) return normalizedHeight / seaLevel * BLOCK_SEA_LEVEL;
        double land = (normalizedHeight - seaLevel) / (peaksMaximumHeight - seaLevel);
        return BLOCK_SEA_LEVEL + land * (BLOCK_PEAK_TARGET_HEIGHT - BLOCK_SEA_LEVEL);
    }

}
