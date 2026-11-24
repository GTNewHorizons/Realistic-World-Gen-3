package com.irythill.rwg3.world.steps;

import com.irythill.rwg3.world.Hex;
import com.irythill.rwg3.world.World;

/** Computes a seam-safe latitude and elevation temperature field. */
public final class TemperatureStep implements IGenerationStep {

    private static final double EQUATOR_TEMPERATURE = 30.0;
    private static final double POLAR_TEMPERATURE = -20.0;
    private static final double LAPSE_RATE_PER_THOUSAND_BLOCKS = 6.5;

    @Override
    public void apply(long seed, World world) {
        world.temperatureCelsius = new double[world.height.length];
        double equator = world.grid.height * 0.5;
        for (int cell = 0; cell < world.height.length; cell++) {
            double y = world.index.worldPositions[cell].y;
            double latitude = Math.min(1.0, Math.abs(y - equator) / equator);
            double latitudeCurve = latitude * latitude * (3.0 - 2.0 * latitude);
            double seaLevelTemperature = EQUATOR_TEMPERATURE
                + (POLAR_TEMPERATURE - EQUATOR_TEMPERATURE) * latitudeCurve;
            double elevation = Math.max(0.0, World.blockHeight(world.height[cell]) - World.BLOCK_SEA_LEVEL);
            double temperature = seaLevelTemperature - elevation / 1_000.0 * LAPSE_RATE_PER_THOUSAND_BLOCKS;
            world.temperatureCelsius[cell] = temperature;
            Hex hex = world.grid.get(world.index.coordinates.get(cell));
            hex.temperatureCelsius = temperature;
        }
    }
}
