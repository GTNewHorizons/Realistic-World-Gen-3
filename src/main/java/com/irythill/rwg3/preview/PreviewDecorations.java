package com.irythill.rwg3.preview;

import java.awt.Color;
import java.util.Locale;

import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.Biome;
import com.irythill.rwg3.world.Hex;
import com.irythill.rwg3.world.steps.TerrainRegionsStep.TerrainRegionKind;

/** Pure preview data extractors used by generator step registrations. */
public final class PreviewDecorations {

    private static final Color[] WATER = { new Color(1, 3, 14), new Color(3, 8, 29), new Color(5, 15, 46),
        new Color(8, 51, 107), new Color(20, 115, 166), new Color(115, 204, 199) };
    private static final Color[] LAND = { new Color(18, 77, 26), new Color(38, 140, 46), new Color(89, 179, 77),
        new Color(140, 140, 133), new Color(199, 199, 199), new Color(250, 250, 252) };
    private static final double[] WATER_STOPS = { 0, .18, .36, .65, .84, 1 };
    private static final double[] LAND_STOPS = { 0, .45, .7, .82, .92, 1 };
    private static final Color[] BIOMES = { new Color(225, 174, 77), new Color(166, 181, 65), new Color(28, 127, 52),
        new Color(174, 147, 103), new Color(51, 101, 66), new Color(70, 139, 74), new Color(220, 227, 232),
        new Color(117, 154, 137) };

    private PreviewDecorations() {}

    public static int plates(Hex hex) {
        if (hex.plateId == 0) return Color.DARK_GRAY.getRGB();
        long mixed = Integer.toUnsignedLong(hex.plateId) * 0x9E3779B97F4A7C15L;
        return Color.getHSBColor((float) ((mixed >>> 11) * 0x1.0p-53), .62f, .82f)
            .getRGB();
    }

    public static int oceanicPlates(Hex hex) {
        long mixed = Integer.toUnsignedLong(hex.plateId) * 0x9E3779B97F4A7C15L;
        double random = (mixed >>> 11) * 0x1.0p-53;
        if (hex.oceanic) {
            float hue = (float) (0.54 + random * 0.10);
            float saturation = (float) (0.62 + random * 0.25);
            float brightness = (float) (0.58 + random * 0.32);
            return Color.getHSBColor(hue, saturation, brightness)
                .getRGB();
        }
        double hue = random * 0.73;
        if (hue >= 0.45) hue += 0.27;
        return Color.getHSBColor((float) hue, .68f, .84f)
            .getRGB();
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
        return normalizedHeight(hex.height, 0.12, 1.0, 2.25);
    }

    public static String terrainRegionTooltip(Hex hex) {
        return hex.terrainRegionKind == null ? "Ocean" : hex.terrainRegionKind.label;
    }

    public static String terrainBiomeTooltip(Hex hex) {
        if (hex.dominantBiome == null) return "Ocean";
        String terrain = hex.terrainRegionKind == null ? TerrainRegionKind.UNCLASSIFIED.label
            : hex.terrainRegionKind.label;
        return terrain + " × " + hex.dominantBiome.label;
    }

    public static int temperature(Hex hex) {
        double normalized = normalize(hex.temperatureCelsius, -25.0, 35.0);
        float hue = (float) (0.66 * (1.0 - normalized));
        return Color.getHSBColor(hue, 0.78f, 0.92f)
            .getRGB();
    }

    public static String temperatureTooltip(Hex hex) {
        return String.format(Locale.ROOT, "%.1f °C", hex.temperatureCelsius);
    }

    public static int humidity(Hex hex) {
        int dry = new Color(151, 113, 68).getRGB();
        int wet = new Color(31, 111, 173).getRGB();
        return mix(dry, wet, hex.humidityPercent / 100.0);
    }

    public static Vec2 windArrow(Hex hex) {
        return hex.wind.multiply(3.0);
    }

    public static String climateTooltip(Hex hex) {
        return String.format(
            Locale.ROOT,
            "%.1f °C, %.1f%% humidity, wind (%.2f, %.2f)",
            hex.temperatureCelsius,
            hex.humidityPercent,
            hex.wind.x,
            hex.wind.y);
    }

    public static int biome(Hex hex) {
        if (hex.biomeWeights == null) return new Color(20, 63, 103).getRGB();
        double red = 0.0, green = 0.0, blue = 0.0;
        for (int index = 0; index < hex.biomeWeights.length; index++) {
            double weight = hex.biomeWeights[index] / 100.0;
            red += BIOMES[index].getRed() * weight;
            green += BIOMES[index].getGreen() * weight;
            blue += BIOMES[index].getBlue() * weight;
        }
        return new Color((int) Math.round(red), (int) Math.round(green), (int) Math.round(blue)).getRGB();
    }

    public static String biomeTooltip(Hex hex) {
        if (hex.biomeWeights == null) return "Ocean";
        StringBuilder result = new StringBuilder(hex.dominantBiome.label);
        Biome[] biomes = Biome.values();
        for (int index = 0; index < biomes.length; index++) {
            if (hex.biomeWeights[index] < 1.0f) continue;
            result.append(String.format(Locale.ROOT, ", %s %.0f%%", biomes[index].label, hex.biomeWeights[index]));
        }
        return result.toString();
    }

    private static int normalizedHeight(double height, double oceanMinimum, double seaLevel, double landMaximum) {
        return height <= seaLevel ? ramp(WATER, WATER_STOPS, normalize(height, oceanMinimum, seaLevel))
            : ramp(LAND, LAND_STOPS, normalize(height, seaLevel, landMaximum));
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
