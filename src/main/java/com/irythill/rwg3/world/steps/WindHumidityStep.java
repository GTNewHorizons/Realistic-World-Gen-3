package com.irythill.rwg3.world.steps;

import java.util.Random;

import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.Hex;
import com.irythill.rwg3.world.World;

/** Builds mirrored counter-rotating winds and advects ocean moisture over land. */
public final class WindHumidityStep implements IGenerationStep {

    private static final long WIND_SEED_SALT = 0x57494E4448554D49L;
    private static final double VORTEX_RING_RADIUS = World.WIDTH * 0.25;
    private static final double VORTEX_CORE_RADIUS = World.WIDTH * 0.08;
    private static final int TRANSPORT_PASSES = 140;
    private static final double LATERAL_SPREAD = 0.08;
    private static final double DISTANCE_LOSS = 0.992;
    private static final double RETAINED_MOISTURE = 0.985;

    @Override
    public void apply(long seed, World world) {
        createWind(seed, world);
        transportHumidity(world);
        publish(world);
    }

    private static void createWind(long seed, World world) {
        Random random = new Random(seed ^ WIND_SEED_SALT);
        double angle = random.nextDouble() * Math.PI * 2.0;
        Vec2 center = new Vec2(world.grid.width * 0.5, world.grid.height * 0.5);
        Vec2 offset = new Vec2(Math.cos(angle), Math.sin(angle)).multiply(VORTEX_RING_RADIUS);
        Vec2 first = world.grid.wrap(center.add(offset));
        Vec2 second = world.grid.wrap(center.subtract(offset));
        world.wind = new Vec2[world.height.length];
        for (int cell = 0; cell < world.wind.length; cell++) {
            Vec2 point = world.index.worldPositions[cell];
            Vec2 wind = vortex(world, point, first, 1.0).add(vortex(world, point, second, -1.0));
            double length = Math.hypot(wind.x, wind.y);
            world.wind[cell] = length < 1.0e-9 ? Vec2.ZERO : wind.multiply(1.0 / length);
        }
    }

    private static Vec2 vortex(World world, Vec2 point, Vec2 center, double rotation) {
        double dx = wrappedDelta(point.x - center.x, world.grid.width);
        double dy = wrappedDelta(point.y - center.y, world.grid.height);
        double distance = Math.hypot(dx, dy);
        if (distance < 1.0e-9) return Vec2.ZERO;
        double influence = 1.0 / (VORTEX_CORE_RADIUS + distance);
        return new Vec2(-dy / distance * rotation * influence, dx / distance * rotation * influence);
    }

    private static void transportHumidity(World world) {
        int size = world.height.length;
        double[] moisture = new double[size];
        boolean[] ocean = new boolean[size];
        for (int cell = 0; cell < size; cell++) {
            ocean[cell] = world.height[cell] <= World.SEA_LEVEL;
            if (ocean[cell]) moisture[cell] = carryingCapacity(world.temperatureCelsius[cell]);
        }
        for (int pass = 0; pass < TRANSPORT_PASSES; pass++) {
            double[] next = new double[size];
            for (int cell = 0; cell < size; cell++) {
                if (ocean[cell]) {
                    next[cell] = carryingCapacity(world.temperatureCelsius[cell]);
                    continue;
                }
                double weighted = 0.0;
                double totalWeight = 0.0;
                for (int direction = 0; direction < 6; direction++) {
                    int neighbor = world.index.neighbors[cell][direction];
                    Vec2 towardCell = world.index.directionVectors[direction].multiply(-1.0);
                    double alignment = world.wind[neighbor].dot(towardCell);
                    double angularWeight = LATERAL_SPREAD + square(Math.max(0.0, alignment + 0.35));
                    double uphill = Math
                        .max(0.0, World.blockHeight(world.height[cell]) - World.blockHeight(world.height[neighbor]));
                    double mountainLoss = Math.exp(-uphill / 220.0);
                    weighted += moisture[neighbor] * angularWeight * mountainLoss;
                    totalWeight += angularWeight;
                }
                double advected = totalWeight <= 0.0 ? 0.0 : weighted / totalWeight * DISTANCE_LOSS;
                next[cell] = Math.max(moisture[cell] * RETAINED_MOISTURE, advected);
                next[cell] = Math.min(next[cell], carryingCapacity(world.temperatureCelsius[cell]));
            }
            moisture = next;
        }
        world.humidityPercent = new double[size];
        for (int cell = 0; cell < size; cell++) world.humidityPercent[cell] = clamp01(moisture[cell]) * 100.0;
    }

    private static double carryingCapacity(double temperature) {
        double normalized = clamp01((temperature + 20.0) / 50.0);
        double smooth = normalized * normalized * (3.0 - 2.0 * normalized);
        return 0.2 + 0.8 * smooth;
    }

    private static void publish(World world) {
        for (int cell = 0; cell < world.height.length; cell++) {
            Hex hex = world.grid.get(world.index.coordinates.get(cell));
            hex.wind = world.wind[cell];
            hex.humidityPercent = world.humidityPercent[cell];
        }
    }

    private static double square(double value) {
        return value * value;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double wrappedDelta(double delta, double period) {
        double result = (delta + period * 0.5) % period;
        if (result < 0.0) result += period;
        return result - period * 0.5;
    }
}
