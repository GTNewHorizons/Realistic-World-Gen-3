package com.irythill.rwg3.preview;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.irythill.rwg3.procgen.GenerationStep;
import com.irythill.rwg3.procgen.world.WorldGenerator;
import com.irythill.rwg3.procgen.world.WorldGenerator.WorldStepDefinition;

/** Pixels and sampling metadata for one progressive preview projection. */
public final class RenderBuffer {

    public final BufferedImage image;
    public volatile BufferedImage motionArrows;
    public volatile BufferedImage hoveredRegionOverlay;
    public volatile long hoveredRegionKey = Long.MIN_VALUE;
    public final int[] pixels;
    public final double[] elevations, sourceElevations, finalElevations;
    public final int[] terrainRegionIds;
    public final long[] terrainRegionKeys;
    public final String[] tooltips;
    public final boolean[] valid, complete;
    public final int width, height;
    public final double originX, originY, unitsPerPixel;
    public final long seed;
    public volatile GenerationStep stage;
    public volatile boolean rendered;
    public final Object generation;
    public final WorldGenerator.TimingSession timingSession;
    final ConcurrentMap<Long, HexSample> samples = new ConcurrentHashMap<>();

    public RenderBuffer(int width, int height, double originX, double originY, double unitsPerPixel, long seed,
        GenerationStep stage, Object generation) {
        this.width = width;
        this.height = height;
        this.originX = originX;
        this.originY = originY;
        this.unitsPerPixel = unitsPerPixel;
        this.seed = seed;
        this.stage = stage;
        this.generation = generation;
        timingSession = stage instanceof WorldStepDefinition ? ((WorldGenerator) generation).newTimingSession() : null;
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) image.getRaster()
            .getDataBuffer()).getData();
        elevations = new double[pixels.length];
        sourceElevations = new double[pixels.length];
        finalElevations = new double[pixels.length];
        terrainRegionIds = new int[pixels.length];
        terrainRegionKeys = new long[pixels.length];
        tooltips = new String[pixels.length];
        valid = new boolean[pixels.length];
        complete = new boolean[pixels.length];
    }

    public boolean compatible(RenderBuffer other) {
        return other != null && seed == other.seed
            && stage == other.stage
            && generation == other.generation
            && unitsPerPixel == other.unitsPerPixel;
    }

    public boolean sameProjection(RenderBuffer other) {
        return other != null && seed == other.seed
            && generation == other.generation
            && width == other.width
            && height == other.height
            && originX == other.originX
            && originY == other.originY
            && unitsPerPixel == other.unitsPerPixel;
    }

    /** Keeps the old stage visible while a same-viewport replacement is rendered. */
    public void copyVisual(RenderBuffer source) {
        if (source == null || source.width != width
            || source.height != height
            || source.originX != originX
            || source.originY != originY
            || source.unitsPerPixel != unitsPerPixel
            || source.seed != seed
            || source.generation != generation) return;
        System.arraycopy(source.pixels, 0, pixels, 0, pixels.length);
    }

    public int copyAligned(RenderBuffer source) {
        if (!compatible(source)) return 0;
        double rawX = (originX - source.originX) / unitsPerPixel;
        double rawY = (source.originY - originY) / unitsPerPixel;
        int sx = (int) Math.round(rawX), sy = (int) Math.round(rawY);
        if (Math.abs(rawX - sx) > 1.0e-7 || Math.abs(rawY - sy) > 1.0e-7) return 0;
        int tx = Math.max(0, -sx), ty = Math.max(0, -sy);
        sx = Math.max(0, sx);
        sy = Math.max(0, sy);
        int w = Math.min(width - tx, source.width - sx), h = Math.min(height - ty, source.height - sy);
        if (w <= 0 || h <= 0) return 0;
        for (int y = 0; y < h; y++) {
            int from = (sy + y) * source.width + sx, to = (ty + y) * width + tx;
            System.arraycopy(source.pixels, from, pixels, to, w);
            System.arraycopy(source.elevations, from, elevations, to, w);
            System.arraycopy(source.sourceElevations, from, sourceElevations, to, w);
            System.arraycopy(source.finalElevations, from, finalElevations, to, w);
            System.arraycopy(source.terrainRegionIds, from, terrainRegionIds, to, w);
            System.arraycopy(source.terrainRegionKeys, from, terrainRegionKeys, to, w);
            System.arraycopy(source.tooltips, from, tooltips, to, w);
            System.arraycopy(source.valid, from, valid, to, w);
            System.arraycopy(source.complete, from, complete, to, w);
        }
        samples.putAll(source.samples);
        return w * h;
    }

    public void reproject(RenderBuffer source) {
        if (source == null || source.generation != generation || source.seed != seed || source.stage != stage) return;
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int x = (int) Math.floor((source.originX - originX) / unitsPerPixel);
        int y = (int) Math.floor((originY - source.originY) / unitsPerPixel);
        int w = (int) Math.ceil(source.width * source.unitsPerPixel / unitsPerPixel);
        int h = (int) Math.ceil(source.height * source.unitsPerPixel / unitsPerPixel);
        graphics.drawImage(source.image, x, y, w, h, null);
        graphics.dispose();
        samples.putAll(source.samples);
    }

    public double minimumX() {
        return originX;
    }

    public double maximumX() {
        return originX + width * unitsPerPixel;
    }

    public double maximumY() {
        return originY;
    }

    public double minimumY() {
        return originY - height * unitsPerPixel;
    }

    static final class HexSample {

        final int color;
        final double elevation;
        final double sourceElevation, finalElevation;
        final boolean complete;
        final double velocityX, velocityY;
        final int terrainRegionId;
        final long terrainRegionKey;
        final String tooltip;

        HexSample(int color, double elevation, boolean complete) {
            this(color, elevation, elevation, elevation, complete, 0.0, 0.0, 0, 0L, null);
        }

        HexSample(int color, double elevation, boolean complete, double velocityX, double velocityY,
            int terrainRegionId, long terrainRegionKey) {
            this(color, elevation, complete, velocityX, velocityY, terrainRegionId, terrainRegionKey, null);
        }

        HexSample(int color, double elevation, boolean complete, double velocityX, double velocityY,
            int terrainRegionId, long terrainRegionKey, String tooltip) {
            this(
                color,
                elevation,
                elevation,
                elevation,
                complete,
                velocityX,
                velocityY,
                terrainRegionId,
                terrainRegionKey,
                tooltip);
        }

        HexSample(int color, double elevation, double sourceElevation, double finalElevation, boolean complete,
            double velocityX, double velocityY, int terrainRegionId, long terrainRegionKey, String tooltip) {
            this.color = color;
            this.elevation = elevation;
            this.sourceElevation = sourceElevation;
            this.finalElevation = finalElevation;
            this.complete = complete;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.terrainRegionId = terrainRegionId;
            this.terrainRegionKey = terrainRegionKey;
            this.tooltip = tooltip;
        }
    }
}
