package com.irythill.rwg3.world;

import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.steps.TerrainRegionsStep.TerrainRegionKind;

/** Mutable data carried by one finite world hex. */
public final class Hex {

    // Core information
    public double height;
    public int terrainRegionId;
    public TerrainRegionKind terrainRegionKind;

    // Tectonic simulation
    public int plateId;
    public boolean oceanic;
    public Vec2 velocity = Vec2.ZERO;
    public double convergence;
    public double divergence;

    // Climate
    public double temperatureCelsius;
    public Vec2 wind = Vec2.ZERO;
    public double humidityPercent;

    // Biome placement
    public Biome dominantBiome;
    public float[] biomeWeights;
    public int terrainBiomeGroup;

    public Hex copy() {
        Hex copy = new Hex();
        copy.height = height;
        copy.terrainRegionId = terrainRegionId;
        copy.terrainRegionKind = terrainRegionKind;
        copy.plateId = plateId;
        copy.oceanic = oceanic;
        copy.velocity = velocity;
        copy.convergence = convergence;
        copy.divergence = divergence;
        copy.temperatureCelsius = temperatureCelsius;
        copy.wind = wind;
        copy.humidityPercent = humidityPercent;
        copy.dominantBiome = dominantBiome;
        copy.biomeWeights = biomeWeights == null ? null : biomeWeights.clone();
        copy.terrainBiomeGroup = terrainBiomeGroup;
        return copy;
    }
}
