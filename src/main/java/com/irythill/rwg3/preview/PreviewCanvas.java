package com.irythill.rwg3.preview;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.irythill.rwg3.preview.RenderBuffer.HexSample;
import com.irythill.rwg3.procgen.GenerationStep;
import com.irythill.rwg3.procgen.continent.ContinentGenerator.StepDefinition;
import com.irythill.rwg3.procgen.continent.Hex;
import com.irythill.rwg3.procgen.util.hex.AxialCoordinate;
import com.irythill.rwg3.procgen.util.hex.Vec2;
import com.irythill.rwg3.procgen.world.WorldGenerator;
import com.irythill.rwg3.procgen.world.WorldGenerator.ComposedSample;
import com.irythill.rwg3.procgen.world.WorldGenerator.TerrainLabel;
import com.irythill.rwg3.procgen.world.WorldGenerator.WorldStepDefinition;

/** Overscanned, cancellable and progressively refined world preview. */
public final class PreviewCanvas extends JPanel implements AutoCloseable {

    private static final long serialVersionUID = 1L;
    private static final Color BACKGROUND = new Color(19, 21, 26);
    private static final int TILE_SIZE = 16;
    private static final int PUBLISH_TILE_INTERVAL = 32;
    private final AtomicLong tickets = new AtomicLong();
    private final int workerCount = Math.max(
        1,
        Runtime.getRuntime()
            .availableProcessors() - 1);
    private final ThreadPoolExecutor coordinator = new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        runnable -> daemon(runnable, "rwg-preview-coordinator"));
    private final ExecutorService workers = Executors
        .newFixedThreadPool(workerCount, runnable -> daemon(runnable, "rwg-preview-worker"));
    private final Timer panTimer = new Timer(100, event -> rebuildAligned());
    private final Timer planRefreshTimer = new Timer(100, event -> refreshIncomplete());
    private transient WorldGenerator composer;
    private GenerationStep stage;
    private GenerationStep columnStage;
    private transient Runnable viewportListener;
    private transient Consumer<Map<GenerationStep, Double>> timingListener;
    private transient Point dragOrigin;
    private transient Point hoverPoint;
    private double centerX, centerY, zoom = 1;
    private long seed, lastPanBufferNanos;
    private volatile RenderBuffer buffer;
    private volatile RenderBuffer columnBuffer;

    public PreviewCanvas() {
        setBackground(BACKGROUND);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setPreferredSize(new Dimension(860, 860));
        panTimer.setRepeats(false);
        planRefreshTimer.setRepeats(false);
        MouseAdapter navigation = new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                if (event.getButton() == MouseEvent.BUTTON1 || event.getButton() == MouseEvent.BUTTON2)
                    dragOrigin = event.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                hoverPoint = event.getPoint();
                if (dragOrigin == null) return;
                double scale = pixelsPerUnit();
                centerX -= (event.getX() - dragOrigin.x) / scale;
                centerY += (event.getY() - dragOrigin.y) / scale;
                dragOrigin = event.getPoint();
                panChanged();
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                hoverPoint = event.getPoint();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hoverPoint = null;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragOrigin = null;
                rebuildAligned();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                zoomAt(event.getX(), event.getY(), Math.pow(1.12, -event.getPreciseWheelRotation()));
            }
        };
        addMouseListener(navigation);
        addMouseMotionListener(navigation);
        addMouseWheelListener(navigation);
        addComponentListener(new ComponentAdapter() {

            @Override
            public void componentResized(ComponentEvent event) {
                rebuildAligned();
            }
        });
    }

    public void setWorld(WorldGenerator source, long worldSeed) {
        composer = source;
        seed = worldSeed;
        stage = source.steps()
            .get(0);
        columnStage = null;
        for (GenerationStep candidate : source.steps()) if (candidate instanceof WorldStepDefinition) {
            columnStage = candidate;
            break;
        }
        columnBuffer = null;
        replaceBuffer(false, false);
    }

    public void setWorld(WorldGenerator source) {
        setWorld(source, 0L);
    }

    public void setStage(GenerationStep value) {
        if (stage != value) {
            RenderBuffer current = buffer;
            boolean currentIsColumns = current != null && current.stage instanceof WorldStepDefinition;
            boolean nextIsColumns = value instanceof WorldStepDefinition;
            stage = value;
            if (currentIsColumns && nextIsColumns) {
                current.stage = value;
                selectColumnStage(current, value);
                repaint();
                return;
            }
            if (currentIsColumns) columnBuffer = current;
            if (nextIsColumns && current != null && columnBuffer != null && columnBuffer.sameProjection(current)) {
                tickets.incrementAndGet();
                coordinator.getQueue()
                    .clear();
                columnBuffer.stage = value;
                selectColumnStage(columnBuffer, value);
                buffer = columnBuffer;
                repaint();
                if (!columnBuffer.rendered) schedule(columnBuffer);
                return;
            }
            replaceBuffer(false, false);
        }
    }

    private static void selectColumnStage(RenderBuffer target, GenerationStep selected) {
        boolean source = ((WorldStepDefinition) selected).index == 2;
        for (int index = 0; index < target.elevations.length; index++)
            target.elevations[index] = source ? target.sourceElevations[index] : target.finalElevations[index];
        colorizeHeightmap(target);
    }

    public void setViewportListener(Runnable listener) {
        viewportListener = listener;
    }

    public void setTimingListener(Consumer<Map<GenerationStep, Double>> listener) {
        timingListener = listener;
    }

    public Rectangle2D worldBounds() {
        RenderBuffer current = buffer;
        if (current != null) return new Rectangle2D.Double(
            current.minimumX(),
            current.minimumY(),
            current.maximumX() - current.minimumX(),
            current.maximumY() - current.minimumY());
        double u = unitsPerPixel();
        return new Rectangle2D.Double(
            centerX - getWidth() * u / 2,
            centerY - getHeight() * u / 2,
            getWidth() * u,
            getHeight() * u);
    }

    public double centerX() {
        return centerX;
    }

    public double centerY() {
        return centerY;
    }

    public void planCompleted() {
        planRefreshTimer.restart();
    }

    private void refreshIncomplete() {
        RenderBuffer current = buffer;
        if (current == null) return;
        boolean currentChanged = invalidateIncomplete(current);
        RenderBuffer columns = columnBuffer;
        boolean columnsChanged = columns != null && columns != current && invalidateIncomplete(columns);
        if (currentChanged) schedule(current);
        else if (columnsChanged && current.stage instanceof StepDefinition) preloadColumns(current);
    }

    private static boolean invalidateIncomplete(RenderBuffer target) {
        boolean changed = false;
        target.samples.entrySet()
            .removeIf(entry -> !entry.getValue().complete);
        for (int i = 0; i < target.valid.length; i++) if (target.valid[i] && !target.complete[i]) {
            target.valid[i] = false;
            changed = true;
        }
        if (changed) target.rendered = false;
        return changed;
    }

    public void resetView() {
        centerX = centerY = 0;
        zoom = 1;
        replaceBuffer(true, false);
    }

    private void zoomAt(double sx, double sy, double factor) {
        double old = pixelsPerUnit(), wx = centerX + (sx - getWidth() / 2.0) / old;
        double wy = centerY - (sy - getHeight() / 2.0) / old;
        zoom = Math.max(.08, Math.min(30, zoom * factor));
        double next = pixelsPerUnit();
        centerX = wx - (sx - getWidth() / 2.0) / next;
        centerY = wy + (sy - getHeight() / 2.0) / next;
        replaceBuffer(false, true);
    }

    private double pixelsPerUnit() {
        return 860.0 / 180.0 * zoom;
    }

    private double unitsPerPixel() {
        return 1 / pixelsPerUnit();
    }

    private void panChanged() {
        repaint();
        RenderBuffer current = buffer;
        if (current != null && viewportInside(current)) return;
        long elapsed = (System.nanoTime() - lastPanBufferNanos) / 1_000_000;
        if (elapsed >= 100) rebuildAligned();
        else if (!panTimer.isRunning()) {
            panTimer.setInitialDelay((int) (100 - elapsed));
            panTimer.start();
        }
    }

    private boolean viewportInside(RenderBuffer value) {
        double u = unitsPerPixel(), left = centerX - getWidth() * u / 2, right = centerX + getWidth() * u / 2;
        double bottom = centerY - getHeight() * u / 2, top = centerY + getHeight() * u / 2;
        return left >= value.minimumX() && right <= value.maximumX()
            && bottom >= value.minimumY()
            && top <= value.maximumY();
    }

    private void rebuildAligned() {
        lastPanBufferNanos = System.nanoTime();
        replaceBuffer(true, false);
    }

    private void replaceBuffer(boolean aligned, boolean reproject) {
        if (composer == null || getWidth() < 2 || getHeight() < 2) {
            repaint();
            return;
        }
        RenderBuffer old = buffer;
        double u = unitsPerPixel();
        int width = (int) Math.ceil(getWidth() * 1.2), height = (int) Math.ceil(getHeight() * 1.2);
        double ox = centerX - width * u / 2, oy = centerY + height * u / 2;
        if (aligned && old != null && old.unitsPerPixel == u) {
            ox = old.originX + Math.rint((ox - old.originX) / u) * u;
            oy = old.originY + Math.rint((oy - old.originY) / u) * u;
        }
        RenderBuffer next = new RenderBuffer(width, height, ox, oy, u, seed, stage, composer);
        if (!reproject && !aligned) next.copyVisual(old);
        if (reproject) next.reproject(old);
        if (aligned) next.copyAligned(old);
        buffer = next;
        if (stage instanceof WorldStepDefinition) columnBuffer = next;
        repaint();
        if (viewportListener != null) viewportListener.run();
        schedule(next);
    }

    private void schedule(RenderBuffer target) {
        target.rendered = false;
        long ticket = tickets.incrementAndGet();
        coordinator.getQueue()
            .clear();
        coordinator.execute(() -> generate(ticket, target));
    }

    private void generate(long ticket, RenderBuffer target) {
        boolean rasterizeHexes = target.stage instanceof StepDefinition && target.unitsPerPixel < 0.5;
        int tilesWide = (target.width + TILE_SIZE - 1) / TILE_SIZE;
        int tilesHigh = (target.height + TILE_SIZE - 1) / TILE_SIZE;
        int minimumQ = 0, minimumR = 0, coordinatesWide = 0;
        int workCount;
        if (rasterizeHexes) {
            double denominator = 2.0 * Math.sqrt(3.0) / 3.0;
            double minimumX = target.minimumX(), maximumX = target.maximumX();
            double minimumY = target.minimumY(), maximumY = target.maximumY();
            minimumQ = (int) Math.floor(minimumX * 0.5 + minimumY / denominator) - 1;
            int maximumQ = (int) Math.ceil(maximumX * 0.5 + maximumY / denominator) + 1;
            minimumR = (int) Math.floor(minimumX * 0.5 - maximumY / denominator) - 1;
            int maximumR = (int) Math.ceil(maximumX * 0.5 - minimumY / denominator) + 1;
            coordinatesWide = maximumQ - minimumQ + 1;
            workCount = coordinatesWide * (maximumR - minimumR + 1);
        } else {
            workCount = tilesWide * tilesHigh;
        }
        final int firstQ = minimumQ;
        final int firstR = minimumR;
        final int qCount = coordinatesWide;
        AtomicInteger nextWork = new AtomicInteger();
        AtomicInteger completedWork = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(workerCount);
        for (int worker = 0; worker < workerCount; worker++) {
            workers.execute(() -> {
                try {
                    while (tickets.get() == ticket) {
                        int work = nextWork.getAndIncrement();
                        if (work >= workCount) break;
                        if (rasterizeHexes)
                            renderHexCell(ticket, target, firstQ + work % qCount, firstR + work / qCount);
                        else renderTile(ticket, target, work % tilesWide, work / tilesWide);
                        int completed = completedWork.incrementAndGet();
                        int publishInterval = rasterizeHexes ? 256 : PUBLISH_TILE_INTERVAL;
                        if (completed % publishInterval == 0) publish(ticket, target);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
        }
        if (tickets.get() == ticket && isBlockHeightmap(target.stage)) colorizeHeightmap(target);
        if (tickets.get() == ticket && hasRegionLabels(target.stage)) colorizeTerrainRegionContours(target);
        if (hasRegionLabels(target.stage)) {
            target.hoveredRegionKey = Long.MIN_VALUE;
            target.hoveredRegionOverlay = null;
        }
        if (tickets.get() == ticket && hasArrows(target.stage)) renderMotionArrows(target);
        if (tickets.get() == ticket && timingListener != null) {
            Map<GenerationStep, Double> values = target.stage instanceof StepDefinition
                ? ((WorldGenerator) target.generation).continentTimingsMicrosPerHex()
                : target.timingSession.cumulativeMicrosPerSample();
            SwingUtilities.invokeLater(() -> timingListener.accept(values));
        }
        boolean finished = tickets.get() == ticket;
        target.rendered = finished;
        publish(ticket, target);
        if (finished && buffer == target && target.stage instanceof StepDefinition) preloadColumns(target);
    }

    private void preloadColumns(RenderBuffer visibleHexes) {
        if (columnStage == null || buffer != visibleHexes || !(visibleHexes.stage instanceof StepDefinition)) return;
        RenderBuffer prepared = columnBuffer;
        if (prepared == null || !prepared.sameProjection(visibleHexes)) {
            prepared = new RenderBuffer(
                visibleHexes.width,
                visibleHexes.height,
                visibleHexes.originX,
                visibleHexes.originY,
                visibleHexes.unitsPerPixel,
                visibleHexes.seed,
                columnStage,
                visibleHexes.generation);
            columnBuffer = prepared;
        }
        if (prepared.rendered) return;
        long ticket = tickets.incrementAndGet();
        RenderBuffer target = prepared;
        coordinator.execute(() -> generate(ticket, target));
    }

    private void renderHexCell(long ticket, RenderBuffer target, int q, int r) {
        double centerX = q + r;
        double centerY = (q - r) * Math.sqrt(3.0) / 3.0;
        double radius = Math.sqrt(3.0) / 3.0;
        if (centerX + 2.0 / 3.0 < target.minimumX() || centerX - 2.0 / 3.0 > target.maximumX()
            || centerY + radius < target.minimumY()
            || centerY - radius > target.maximumY()) return;
        long key = (long) q & 0xffffffffL | (long) r << 32;
        HexSample sample = target.samples.computeIfAbsent(key, ignored -> sampleHex(target, q, r));
        if (tickets.get() != ticket) return;
        double screenCenterY = (target.originY - centerY) / target.unitsPerPixel;
        int minimumY = Math.max(0, (int) Math.ceil(screenCenterY - radius / target.unitsPerPixel - 0.5));
        int maximumY = Math.min(target.height, (int) Math.ceil(screenCenterY + radius / target.unitsPerPixel - 0.5));
        for (int y = minimumY; y < maximumY; y++) {
            double worldY = target.originY - (y + 0.5) * target.unitsPerPixel;
            double distanceY = Math.abs(worldY - centerY);
            double halfWidth = 2.0 / 3.0 - distanceY / Math.sqrt(3.0);
            if (halfWidth <= 0.0) continue;
            int minimumX = Math
                .max(0, (int) Math.ceil((centerX - halfWidth - target.originX) / target.unitsPerPixel - 0.5));
            int maximumX = Math.min(
                target.width,
                (int) Math.ceil((centerX + halfWidth - target.originX) / target.unitsPerPixel - 0.5));
            if (minimumX >= maximumX) continue;
            int from = y * target.width + minimumX;
            int to = y * target.width + maximumX;
            Arrays.fill(target.pixels, from, to, sample.color);
            Arrays.fill(target.elevations, from, to, sample.elevation);
            Arrays.fill(target.sourceElevations, from, to, sample.sourceElevation);
            Arrays.fill(target.finalElevations, from, to, sample.finalElevation);
            Arrays.fill(target.terrainRegionIds, from, to, sample.terrainRegionId);
            Arrays.fill(target.terrainRegionKeys, from, to, sample.terrainRegionKey);
            Arrays.fill(target.tooltips, from, to, sample.tooltip);
            Arrays.fill(target.complete, from, to, sample.complete);
            Arrays.fill(target.valid, from, to, true);
        }
    }

    private void renderTile(long ticket, RenderBuffer target, int tileX, int tileY) {
        int minimumX = tileX * TILE_SIZE;
        int minimumY = tileY * TILE_SIZE;
        int maximumX = Math.min(minimumX + TILE_SIZE, target.width);
        int maximumY = Math.min(minimumY + TILE_SIZE, target.height);
        for (int y = minimumY; y < maximumY; y++) {
            if (tickets.get() != ticket) return;
            double worldY = target.originY - (y + .5) * target.unitsPerPixel;
            for (int x = minimumX; x < maximumX; x++) {
                int index = y * target.width + x;
                if (target.valid[index]) continue;
                double worldX = target.originX + (x + .5) * target.unitsPerPixel;
                HexSample sample;
                if (isBlockHeightmap(target.stage)) {
                    ComposedSample composed = ((WorldGenerator) target.generation)
                        .sample(worldX, worldY, target.stage, target.timingSession);
                    sample = new HexSample(
                        color(composed, target.stage),
                        composed.elevation,
                        composed.sourceElevation,
                        composed.finalElevation,
                        composed.complete,
                        0.0,
                        0.0,
                        0,
                        0L,
                        ((WorldStepDefinition) target.stage).tooltip == null ? null
                            : ((WorldStepDefinition) target.stage).tooltip.apply(composed));
                } else {
                    long key = AxialCoordinate.packedFromWorld(worldX, worldY);
                    int q = (int) key, r = (int) (key >>> 32);
                    sample = target.unitsPerPixel < 0.75 || hasArrows(target.stage)
                        ? target.samples.computeIfAbsent(key, ignored -> sampleHex(target, q, r))
                        : sampleHex(target, q, r);
                }
                if (tickets.get() != ticket) return;
                target.pixels[index] = sample.color;
                target.elevations[index] = sample.elevation;
                target.sourceElevations[index] = sample.sourceElevation;
                target.finalElevations[index] = sample.finalElevation;
                target.terrainRegionIds[index] = sample.terrainRegionId;
                target.terrainRegionKeys[index] = sample.terrainRegionKey;
                target.tooltips[index] = sample.tooltip;
                target.complete[index] = sample.complete;
                target.valid[index] = true;
                if (isBlockHeightmap(target.stage)) colorizeHeightmapDependencies(target, x, y);
            }
        }
    }

    private static HexSample sampleHex(RenderBuffer target, int q, int r) {
        double centerX = q + r;
        double centerY = (q - r) * Math.sqrt(3.0) / 3.0;
        ComposedSample sample = ((WorldGenerator) target.generation).sample(centerX, centerY, target.stage);
        Hex hex = hex(sample);
        Vec2 arrow = target.stage instanceof StepDefinition && ((StepDefinition) target.stage).arrow != null
            ? ((StepDefinition) target.stage).arrow.apply(hex)
            : Vec2.ZERO;
        String tooltip = target.stage instanceof StepDefinition && ((StepDefinition) target.stage).tooltip != null
            ? ((StepDefinition) target.stage).tooltip.apply(hex)
            : null;
        long regionKey = sample.terrainRegionId == 0 ? 0L
            : sample.siteId * 0x9E3779B97F4A7C15L ^ Integer.toUnsignedLong(sample.terrainRegionId);
        return new HexSample(
            color(sample, target.stage),
            sample.elevation,
            sample.complete,
            arrow.x,
            arrow.y,
            sample.terrainRegionId,
            regionKey,
            tooltip);
    }

    private void publish(long ticket, RenderBuffer target) {
        SwingUtilities.invokeLater(() -> { if (tickets.get() == ticket && buffer == target) repaint(); });
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        RenderBuffer current = buffer;
        if (current == null) return;
        int x = (int) Math.round(getWidth() / 2.0 + (current.originX - centerX) / current.unitsPerPixel);
        int y = (int) Math.round(getHeight() / 2.0 - (current.originY - centerY) / current.unitsPerPixel);
        graphics.drawImage(current.image, x, y, null);
        if (hasRegionLabels(current.stage)) {
            BufferedImage hovered = hoveredRegionOverlay(current, x, y);
            if (hovered != null) graphics.drawImage(hovered, x, y, null);
        }
        BufferedImage motionArrows = current.motionArrows;
        if (motionArrows != null) graphics.drawImage(motionArrows, x, y, null);
        if (hasRegionLabels(current.stage)) {
            paintTerrainLabels(graphics);
            paintTerrainPresetInfo(graphics, current, x, y);
        }
        paintHeightInfo(graphics, current, x, y);
    }

    private BufferedImage hoveredRegionOverlay(RenderBuffer current, int imageX, int imageY) {
        Point cursor = hoverPoint;
        long key = 0L;
        if (cursor != null) {
            int pixelX = cursor.x - imageX, pixelY = cursor.y - imageY;
            if (pixelX >= 0 && pixelY >= 0 && pixelX < current.width && pixelY < current.height) {
                int index = pixelY * current.width + pixelX;
                if (current.valid[index]) key = current.terrainRegionKeys[index];
            }
        }
        if (current.hoveredRegionKey == key) return current.hoveredRegionOverlay;
        current.hoveredRegionKey = key;
        if (key == 0L) {
            current.hoveredRegionOverlay = null;
            return null;
        }
        BufferedImage overlay = new BufferedImage(current.width, current.height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) overlay.getRaster()
            .getDataBuffer()).getData();
        int tint = Color.getHSBColor((float) ((key >>> 11) * 0x1.0p-53), 0.52f, 0.95f)
            .getRGB() & 0x00FFFFFF;
        int colored = 0x52000000 | tint;
        for (int i = 0; i < pixels.length; i++) if (current.terrainRegionKeys[i] == key) pixels[i] = colored;
        current.hoveredRegionOverlay = overlay;
        return overlay;
    }

    private void paintTerrainLabels(Graphics graphics) {
        WorldGenerator source = composer;
        if (source == null) return;
        List<TerrainLabel> labels = source.terrainLabels(worldBounds());
        Graphics2D overlay = (Graphics2D) graphics.create();
        overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float fontSize = (float) Math.max(10.0, Math.min(17.0, 10.0 + Math.log1p(zoom) * 2.5));
        overlay.setFont(
            overlay.getFont()
                .deriveFont(Font.BOLD, fontSize));
        FontMetrics metrics = overlay.getFontMetrics();
        Font presetFont = overlay.getFont();
        FontMetrics presetMetrics = overlay.getFontMetrics(presetFont);
        double scale = pixelsPerUnit();
        for (TerrainLabel label : labels) {
            int x = (int) Math.round(getWidth() * 0.5 + (label.worldX - centerX) * scale);
            int y = (int) Math.round(getHeight() * 0.5 - (label.worldY - centerY) * scale);
            if (x < -150 || y < -30 || x > getWidth() + 150 || y > getHeight() + 30) continue;
            String text = label.text;
            int textX = x - metrics.stringWidth(text) / 2;
            int textY = y;
            overlay.setColor(new Color(0, 0, 0, 210));
            overlay.drawString(text, textX - 1, textY);
            overlay.drawString(text, textX + 1, textY);
            overlay.drawString(text, textX, textY - 1);
            overlay.drawString(text, textX, textY + 1);
            overlay.setColor(new Color(255, 250, 224, 245));
            overlay.drawString(text, textX, textY);
            overlay.setFont(presetFont);
            int presetX = x - presetMetrics.stringWidth(label.presetText) / 2;
            int presetY = textY + presetMetrics.getHeight();
            overlay.setColor(new Color(0, 0, 0, 210));
            overlay.drawString(label.presetText, presetX - 1, presetY);
            overlay.drawString(label.presetText, presetX + 1, presetY);
            overlay.drawString(label.presetText, presetX, presetY - 1);
            overlay.drawString(label.presetText, presetX, presetY + 1);
            overlay.setColor(new Color(190, 194, 198, 235));
            overlay.drawString(label.presetText, presetX, presetY);
            overlay.setFont(metrics.getFont());
        }
        overlay.dispose();
    }

    private void paintTerrainPresetInfo(Graphics graphics, RenderBuffer current, int imageX, int imageY) {
        Point cursor = hoverPoint;
        WorldGenerator source = composer;
        if (cursor == null || source == null) return;
        int pixelX = cursor.x - imageX, pixelY = cursor.y - imageY;
        if (pixelX < 0 || pixelY < 0 || pixelX >= current.width || pixelY >= current.height) return;
        int index = pixelY * current.width + pixelX;
        if (!current.valid[index] || current.terrainRegionKeys[index] == 0L) return;

        double worldX = current.originX + (pixelX + 0.5) * current.unitsPerPixel;
        double worldY = current.originY - (pixelY + 0.5) * current.unitsPerPixel;
        double erosion = Math.max(0.0, Math.min(1.0, source.noiseErosionWeight(worldX, worldY)));
        String regionText = current.tooltips[index];
        String erosionText = String.format(Locale.ROOT, "%.1f%% Phacelle Noise Erosion", erosion * 100.0);
        String baseText = String.format(Locale.ROOT, "%.1f%% Base Smooth Heightmap", (1.0 - erosion) * 100.0);

        Graphics2D overlay = (Graphics2D) graphics.create();
        overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int crossRadius = 6, crossGap = 2;
        overlay.setColor(new Color(0, 0, 0, 220));
        overlay.setStroke(new BasicStroke(3.0f));
        overlay.drawLine(cursor.x - crossRadius, cursor.y, cursor.x - crossGap, cursor.y);
        overlay.drawLine(cursor.x + crossGap, cursor.y, cursor.x + crossRadius, cursor.y);
        overlay.drawLine(cursor.x, cursor.y - crossRadius, cursor.x, cursor.y - crossGap);
        overlay.drawLine(cursor.x, cursor.y + crossGap, cursor.x, cursor.y + crossRadius);
        overlay.setColor(Color.WHITE);
        overlay.setStroke(new BasicStroke(1.0f));
        overlay.drawLine(cursor.x - crossRadius, cursor.y, cursor.x - crossGap, cursor.y);
        overlay.drawLine(cursor.x + crossGap, cursor.y, cursor.x + crossRadius, cursor.y);
        overlay.drawLine(cursor.x, cursor.y - crossRadius, cursor.x, cursor.y - crossGap);
        overlay.drawLine(cursor.x, cursor.y + crossGap, cursor.x, cursor.y + crossRadius);
        FontMetrics metrics = overlay.getFontMetrics();
        int paddingX = 9, paddingY = 7, lineGap = 3;
        int boxWidth = Math.max(
            metrics.stringWidth(regionText),
            Math.max(metrics.stringWidth(erosionText), metrics.stringWidth(baseText))) + paddingX * 2;
        int boxHeight = metrics.getHeight() * 3 + lineGap * 2 + paddingY * 2;
        int boxX = cursor.x + 14, boxY = cursor.y + 18;
        if (boxX + boxWidth > getWidth() - 4) boxX = cursor.x - boxWidth - 14;
        if (boxY + boxHeight > getHeight() - 4) boxY = cursor.y - boxHeight - 14;
        boxX = Math.max(4, boxX);
        boxY = Math.max(4, boxY);
        overlay.setColor(new Color(0, 0, 0, 205));
        overlay.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 10, 10);
        int textX = boxX + paddingX;
        int firstY = boxY + paddingY + metrics.getAscent();
        overlay.setColor(new Color(255, 250, 224));
        overlay.drawString(regionText, textX, firstY);
        overlay.setColor(new Color(235, 235, 235));
        overlay.drawString(erosionText, textX, firstY + metrics.getHeight() + lineGap);
        overlay.setColor(new Color(190, 194, 198));
        overlay.drawString(baseText, textX, firstY + (metrics.getHeight() + lineGap) * 2);
        overlay.dispose();
    }

    private static void renderMotionArrows(RenderBuffer target) {
        BufferedImage overlay = new BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D arrows = overlay.createGraphics();
        arrows.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        arrows.setColor(new Color(255, 255, 255, 225));
        double scale = 1.0 / target.unitsPerPixel;
        for (Entry<Long, HexSample> entry : target.samples.entrySet()) {
            long key = entry.getKey();
            int q = (int) key, r = (int) (key >>> 32);
            // This parity class is an independent set on the axial grid: none of
            // its six direct neighbors is selected, and it contains 1/4 of cells.
            if ((q & 1) != 0 || (r & 1) != 0) continue;
            double worldX = q + r;
            double worldY = (q - r) * Math.sqrt(3.0) / 3.0;
            double sx = (worldX - target.originX) * scale;
            double sy = (target.originY - worldY) * scale;
            if (sx < -20 || sy < -20 || sx > target.width + 20 || sy > target.height + 20) continue;
            HexSample sample = entry.getValue();
            double speed = Math.hypot(sample.velocityX, sample.velocityY);
            if (speed < 1.0e-9) continue;
            double length = Math.min(scale * 0.78, scale * (0.22 + speed * 0.14));
            double dx = sample.velocityX / speed * length;
            double dy = -sample.velocityY / speed * length;
            double startX = sx - dx * 0.42, startY = sy - dy * 0.42;
            double endX = sx + dx * 0.58, endY = sy + dy * 0.58;
            arrows.drawLine(
                (int) Math.round(startX),
                (int) Math.round(startY),
                (int) Math.round(endX),
                (int) Math.round(endY));
            double head = Math.max(2.0, Math.min(5.0, scale * 0.3));
            double ux = dx / length, uy = dy / length;
            arrows.drawLine(
                (int) Math.round(endX),
                (int) Math.round(endY),
                (int) Math.round(endX - ux * head - uy * head * 0.55),
                (int) Math.round(endY - uy * head + ux * head * 0.55));
            arrows.drawLine(
                (int) Math.round(endX),
                (int) Math.round(endY),
                (int) Math.round(endX - ux * head + uy * head * 0.55),
                (int) Math.round(endY - uy * head - ux * head * 0.55));
        }
        arrows.dispose();
        target.motionArrows = overlay;
    }

    private void paintHeightInfo(Graphics graphics, RenderBuffer current, int imageX, int imageY) {
        Point cursor = hoverPoint;
        if (cursor == null || !(current.stage instanceof WorldStepDefinition)
            || ((WorldStepDefinition) current.stage).tooltip == null) return;
        int pixelX = cursor.x - imageX;
        int pixelY = cursor.y - imageY;
        if (pixelX < 0 || pixelY < 0 || pixelX >= current.width || pixelY >= current.height) return;
        int index = pixelY * current.width + pixelX;
        if (!current.valid[index]) return;

        double worldX = current.originX + pixelX * current.unitsPerPixel;
        double worldY = current.originY - pixelY * current.unitsPerPixel;
        long blockX = (long) Math.floor(worldX * WorldGenerator.BLOCKS_PER_WORLD_UNIT);
        long blockY = (long) Math.floor(current.elevations[index]);
        long blockZ = (long) Math.floor(-worldY * WorldGenerator.BLOCKS_PER_WORLD_UNIT);
        String text = current.tooltips[index] + " — X: " + blockX + "   Y: " + blockY + "   Z: " + blockZ;

        Graphics2D overlay = (Graphics2D) graphics.create();
        overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        FontMetrics metrics = overlay.getFontMetrics();
        int paddingX = 8;
        int paddingY = 6;
        int boxWidth = metrics.stringWidth(text) + paddingX * 2;
        int boxHeight = metrics.getHeight() + paddingY * 2;
        int boxX = cursor.x + 14;
        int boxY = cursor.y + 18;
        if (boxX + boxWidth > getWidth() - 4) boxX = cursor.x - boxWidth - 14;
        if (boxY + boxHeight > getHeight() - 4) boxY = cursor.y - boxHeight - 14;
        boxX = Math.max(4, boxX);
        boxY = Math.max(4, boxY);
        overlay.setColor(new Color(0, 0, 0, 190));
        overlay.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 10, 10);
        overlay.setColor(Color.WHITE);
        overlay.drawString(text, boxX + paddingX, boxY + paddingY + metrics.getAscent());
        overlay.dispose();
    }

    private static int color(ComposedSample sample, GenerationStep stage) {
        if (stage instanceof StepDefinition) {
            StepDefinition continentStage = (StepDefinition) stage;
            if (!sample.loadedCategory && continentStage.index < 3) return BACKGROUND.getRGB();
            return continentStage.colour.applyAsInt(hex(sample));
        }
        return ((WorldStepDefinition) stage).colour.applyAsInt(sample);
    }

    private static boolean isBlockHeightmap(GenerationStep stage) {
        return stage instanceof WorldStepDefinition;
    }

    private static boolean hasArrows(GenerationStep stage) {
        return stage instanceof StepDefinition && ((StepDefinition) stage).arrow != null;
    }

    private static boolean hasRegionLabels(GenerationStep stage) {
        return stage instanceof StepDefinition && ((StepDefinition) stage).labels != null;
    }

    private static Hex hex(ComposedSample sample) {
        Hex hex = new Hex();
        hex.plateId = sample.plateId;
        hex.oceanic = sample.oceanic;
        hex.velocity = new Vec2(sample.velocityX, sample.velocityY);
        hex.convergence = sample.convergence;
        hex.divergence = sample.divergence;
        hex.height = sample.elevation;
        hex.terrainRegionId = sample.terrainRegionId;
        hex.erosionWeight = sample.noiseErosionWeight;
        return hex;
    }

    private static void colorizeTerrainRegionContours(RenderBuffer target) {
        for (int y = 0; y < target.height - 1; y++) {
            for (int x = 0; x < target.width - 1; x++) {
                int index = y * target.width + x;
                if (!target.valid[index]) continue;
                int east = index + 1, south = index + target.width;
                int region = target.terrainRegionIds[index];
                boolean boundary = region != target.terrainRegionIds[east] || region != target.terrainRegionIds[south];
                if (boundary) target.pixels[index] = shade(target.pixels[index], 0.55);
            }
        }
    }

    private static void colorizeHeightmap(RenderBuffer target) {
        for (int y = 0; y < target.height; y++) {
            for (int x = 0; x < target.width; x++) {
                colorizeHeightmapPixel(target, x, y);
            }
        }
    }

    private static void colorizeHeightmapDependencies(RenderBuffer target, int x, int y) {
        colorizeHeightmapPixel(target, x, y);
        colorizeHeightmapPixel(target, x - 2, y);
        colorizeHeightmapPixel(target, x + 2, y);
        colorizeHeightmapPixel(target, x, y - 2);
        colorizeHeightmapPixel(target, x, y + 2);
    }

    private static void colorizeHeightmapPixel(RenderBuffer target, int x, int y) {
        if (x < 0 || y < 0 || x >= target.width || y >= target.height) return;
        int index = y * target.width + x;
        if (!target.valid[index]) return;
        int west = neighbor(target, x - 2, y, index);
        int east = neighbor(target, x + 2, y, index);
        int north = neighbor(target, x, y - 2, index);
        int south = neighbor(target, x, y + 2, index);
        double denominator = Math.max(1.0, 4.0 * target.unitsPerPixel * WorldGenerator.BLOCKS_PER_WORLD_UNIT);
        double dx = (target.elevations[east] - target.elevations[west]) / denominator;
        double dy = (target.elevations[south] - target.elevations[north]) / denominator;
        double light = Math.max(0.55, Math.min(1.35, 0.95 - dx * 0.45 - dy * 0.45));
        int color = shade(PreviewDecorations.worldHeight(target.elevations[index]), light);
        int contour = (int) Math.floor(target.elevations[index] / 10.0);
        if (contour != (int) Math.floor(target.elevations[east] / 10.0)
            || contour != (int) Math.floor(target.elevations[south] / 10.0)) {
            color = shade(color, contour % 5 == 0 ? 0.48 : 0.70);
        }
        target.pixels[index] = color;
    }

    private static int neighbor(RenderBuffer target, int x, int y, int fallback) {
        if (x < 0 || y < 0 || x >= target.width || y >= target.height) return fallback;
        int index = y * target.width + x;
        return target.valid[index] ? index : fallback;
    }

    private static int shade(int color, double light) {
        int red = Math.min(255, Math.max(0, (int) (((color >> 16) & 255) * light)));
        int green = Math.min(255, Math.max(0, (int) (((color >> 8) & 255) * light)));
        int blue = Math.min(255, Math.max(0, (int) ((color & 255) * light)));
        return red << 16 | green << 8 | blue;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void close() {
        tickets.incrementAndGet();
        panTimer.stop();
        planRefreshTimer.stop();
        coordinator.shutdownNow();
        workers.shutdownNow();
    }
}
