package com.irythill.rwg3.world.steps;

import com.irythill.rwg3.util.hex.AxialCoordinate;
import com.irythill.rwg3.world.Biome;
import com.irythill.rwg3.world.Hex;
import com.irythill.rwg3.world.World;
import com.irythill.rwg3.world.steps.TerrainRegionsStep.TerrainRegionKind;

/** Groups land by terrain category and dominant biome. Zero denotes water. */
public final class TerrainBiomeStep implements IGenerationStep {

    @Override
    public void apply(long seed, World world) {
        int biomeCount = Biome.values().length;
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            Hex hex = world.grid.get(coordinate);
            TerrainRegionKind kind = hex.terrainRegionKind == null ? TerrainRegionKind.UNCLASSIFIED
                : hex.terrainRegionKind;
            hex.terrainBiomeGroup = hex.dominantBiome == null ? 0
                : 1 + kind.ordinal() * biomeCount + hex.dominantBiome.ordinal();
        }
    }
}
