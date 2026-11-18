package com.irythill.rwg3.preview;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

import com.irythill.rwg3.procgen.continent.Continent;
import com.irythill.rwg3.procgen.continent.Hex;
import com.irythill.rwg3.procgen.continent.steps.TerrainRegionsStep.TerrainRegion;
import com.irythill.rwg3.procgen.util.hex.Vec2;
import com.irythill.rwg3.procgen.world.WorldGenerator;
import com.irythill.rwg3.procgen.world.WorldGenerator.ComposedSample;

/** Pure preview data extractors used by generator step registrations. */
public final class PreviewDecorations {

    private static final Color[] WATER = { new Color(1, 3, 14), new Color(3, 8, 29), new Color(5, 15, 46),
        new Color(8, 51, 107), new Color(20, 115, 166), new Color(115, 204, 199) };
    private static final Color[] LAND = { new Color(18, 77, 26), new Color(38, 140, 46), new Color(89, 179, 77),
        new Color(140, 140, 133), new Color(199, 199, 199), new Color(250, 250, 252) };
    private static final double[] WATER_STOPS = { 0, .18, .36, .65, .84, 1 };
    private static final double[] LAND_STOPS = { 0, .45, .7, .82, .92, 1 };

    private PreviewDecorations() {}

    public static int plates(Hex hex) {
        if (hex.plateId == 0) return Color.DARK_GRAY.getRGB();
        long mixed = Integer.toUnsignedLong(hex.plateId) * 0x9E3779B97F4A7C15L;
        return Color.getHSBColor((float) ((mixed >>> 11) * 0x1.0p-53), .62f, .82f)
            .getRGB();
    }

    public static int oceanicPlates(Hex hex) {
        return hex.oceanic ? new Color(30, 80, 200).getRGB() : plates(hex);
    }

    public static int movement(Hex hex) {
        double boundary = Math.max(hex.convergence, hex.divergence);
        if (boundary <= 1.0e-9) return new Color(55, 60, 68).getRGB();
        Color signal = hex.convergence >= hex.divergence ? new Color(220, 48, 55) : new Color(38, 105, 225);
        return mix(new Color(55, 60, 68).getRGB(), signal.getRGB(), 0.45 + 0.55 * boundary);
    }

    public static Vec2 movementArrow(Hex hex) {
        return hex.velocity;
    }

    public static int hexHeight(Hex hex) {
        return normalizedHeight(hex.height, WorldGenerator.MINIMUM_OCEAN_HEIGHT, 1.0, 2.25);
    }

    public static int terrainRegion(Hex hex) {
        return normalizedHeight(hex.height, WorldGenerator.MINIMUM_OCEAN_HEIGHT, 1.0, 2.25);
    }

    public static String terrainRegionTooltip(Hex hex) {
        return hex.terrainRegionId == 0 ? null : "Terrain region " + hex.terrainRegionId;
    }

    public static List<TerrainRegion> terrainRegionLabels(Continent continent) {
        return continent.terrainRegions();
    }

    public static int worldHeight(ComposedSample sample) {
        return heightColor(sample.elevation);
    }

    public static int worldHeight(double height) {
        return heightColor(height);
    }

    public static String heightTooltip(ComposedSample sample) {
        return String.format(Locale.ROOT, "Height %.1f", sample.elevation);
    }

    private static int normalizedHeight(double height, double oceanMinimum, double seaLevel, double landMaximum) {
        return height <= seaLevel ? ramp(WATER, WATER_STOPS, normalize(height, oceanMinimum, seaLevel))
            : ramp(LAND, LAND_STOPS, normalize(height, seaLevel, landMaximum));
    }

    private static int heightColor(double height) {
        if (height <= WorldGenerator.WATER_LEVEL) {
            if (height < 8.0) return mix(0x06172D, 0x123D68, normalize(height, -64.0, 8.0));
            return mix(0x123D68, 0x4AA1C7, normalize(height, 8.0, 62.0));
        }
        if (height < 125.0) return mix(0x3D8F45, 0x7C8179, normalize(height, 62.0, 125.0));
        return mix(0x7C8179, 0xFAFDFF, normalize(height, 125.0, 230.0));
    }

    private static int ramp(Color[] colors, double[] stops, double value) {
        for (int index = 0; index < stops.length - 1; index++) if (value <= stops[index + 1]) {
            double amount = (value - stops[index]) / Math.max(1.0e-9, stops[index + 1] - stops[index]);
            return new Color(
                interpolate(colors[index].getRed(), colors[index + 1].getRed(), amount),
                interpolate(colors[index].getGreen(), colors[index + 1].getGreen(), amount),
                interpolate(colors[index].getBlue(), colors[index + 1].getBlue(), amount)).getRGB();
        }
        return colors[colors.length - 1].getRGB();
    }

    private static double normalize(double value, double minimum, double maximum) {
        return Math.max(0.0, Math.min(1.0, (value - minimum) / Math.max(1.0e-9, maximum - minimum)));
    }

    private static int interpolate(int first, int second, double amount) {
        return (int) Math.round(first + (second - first) * amount);
    }

    private static int mix(int first, int second, double amount) {
        amount = Math.max(0.0, Math.min(1.0, amount));
        int red = (int) (((first >> 16) & 255) * (1.0 - amount) + ((second >> 16) & 255) * amount);
        int green = (int) (((first >> 8) & 255) * (1.0 - amount) + ((second >> 8) & 255) * amount);
        int blue = (int) ((first & 255) * (1.0 - amount) + (second & 255) * amount);
        return red << 16 | green << 8 | blue;
    }
}
