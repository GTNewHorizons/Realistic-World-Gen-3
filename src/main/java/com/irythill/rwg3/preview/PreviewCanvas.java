package com.irythill.rwg3.preview;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JToolTip;

import com.irythill.rwg3.util.hex.AxialCoordinate;
import com.irythill.rwg3.util.hex.HexGrid;
import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.Biome;
import com.irythill.rwg3.world.Hex;
import com.irythill.rwg3.world.World;
import com.irythill.rwg3.world.WorldGenerator.StepDefinition;

/** Interactive view of one already-generated fixed world. */
public final class PreviewCanvas extends JComponent {

    private static final int RASTER_SIZE = 1080;
    private static final double RASTER_PIXELS_PER_UNIT = RASTER_SIZE / World.WIDTH;
    private static final double VECTOR_RENDER_PIXELS_PER_UNIT = RASTER_PIXELS_PER_UNIT * 4.0;
    private static final double HEX_HALF_HEIGHT = Math.sqrt(3.0) / 3.0;
    private static final double HEX_OVERDRAW_PIXELS = 0.75;
    private static final double SLOPE_SAMPLE_DISTANCE = 2.0;
    private static final double TREE_ICON_SIZE_PER_UNIT = 1.17;
    private static final int OVERVIEW_TREE_ICON_SIZE = 16;
    private static final int OVERVIEW_TREE_STRIDE = 5;

    private final Map<Integer, BufferedImage> stageImages = new HashMap<>();
    private final Map<Integer, BufferedImage> stageImagesWithTrees = new HashMap<>();
    private final Map<Integer, Path2D> stageContours = new HashMap<>();
    private final Map<Biome, BufferedImage> treeIcons = loadTreeIcons();
    private final JToolTip hoverTip = createToolTip();
    private Point hoverPoint;
    private boolean showTreeIcons = true;
    private World world;
    private StepDefinition stage;
    private double centerX = World.WIDTH * 0.5;
    private double centerY = World.WIDTH * 0.5;
    private double zoom = 1.0;
    private Point dragStart;
    private double dragCenterX, dragCenterY;

    public PreviewCanvas() {
        setOpaque(true);
        setBackground(new Color(8, 10, 16));
        MouseAdapter navigation = new MouseAdapter() {

            @Override
            public void mouseEntered(MouseEvent event) {
                mouseMoved(event);
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
            public void mousePressed(MouseEvent event) {
                dragStart = event.getPoint();
                hoverPoint = dragStart;
                dragCenterX = centerX;
                dragCenterY = centerY;
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragStart == null) return;
                hoverPoint = event.getPoint();
                double scale = pixelsPerUnit();
                centerX = wrap(dragCenterX - (event.getX() - dragStart.x) / scale);
                centerY = wrap(dragCenterY + (event.getY() - dragStart.y) / scale);
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragStart = null;
                hoverPoint = contains(event.getPoint()) ? event.getPoint() : null;
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                hoverPoint = event.getPoint();
                zoomAt(event.getX(), event.getY(), Math.pow(1.12, -event.getPreciseWheelRotation()));
            }
        };
        addMouseListener(navigation);
        addMouseMotionListener(navigation);
        addMouseWheelListener(navigation);
        addComponentListener(new ComponentAdapter() {

            @Override
            public void componentResized(ComponentEvent event) {
                repaint();
            }
        });
    }

    public void setWorld(World value, StepDefinition initialStage) {
        world = value;
        stage = initialStage;
        stageImages.clear();
        stageImagesWithTrees.clear();
        stageContours.clear();
        resetView();
    }

    public void setStage(StepDefinition value) {
        stage = value;
        repaint();
    }

    public void setShowTreeIcons(boolean show) {
        showTreeIcons = show;
        repaint();
    }

    public void resetView() {
        centerX = centerY = World.WIDTH * 0.5;
        zoom = 1.0;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (world == null || stage == null || getWidth() <= 0 || getHeight() <= 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        double scale = pixelsPerUnit();
        double left = centerX - getWidth() / (2.0 * scale);
        double right = centerX + getWidth() / (2.0 * scale);
        double bottom = centerY - getHeight() / (2.0 * scale);
        double top = centerY + getHeight() / (2.0 * scale);
        int firstX = (int) Math.floor(left / World.WIDTH);
        int lastX = (int) Math.floor(right / World.WIDTH);
        int firstY = (int) Math.floor(bottom / World.WIDTH);
        int lastY = (int) Math.floor(top / World.WIDTH);
        boolean vector = scale > VECTOR_RENDER_PIXELS_PER_UNIT;
        if (vector) drawVectorHexes(g, scale, firstX, lastX, firstY, lastY);
        else drawRasterTiles(g, scale, firstX, lastX, firstY, lastY);
        drawTileBoundaries(g, scale, firstX, lastX, firstY, lastY);
        drawArrows(g, scale, firstX, lastX, firstY, lastY);
        drawContours(g, scale, firstX, lastX, firstY, lastY);
        if (vector && showTreeIcons && hasTreeIcons(stage)) drawTreeIcons(g, scale, firstX, lastX, firstY, lastY);
        drawHoverDetails(g);
        g.dispose();
    }

    private void drawRasterTiles(Graphics2D g, double scale, int firstX, int lastX, int firstY, int lastY) {
        BufferedImage base = stageImages.computeIfAbsent(stage.index, ignored -> rasterize(world, stage));
        BufferedImage image = showTreeIcons && hasTreeIcons(stage)
            ? stageImagesWithTrees.computeIfAbsent(stage.index, ignored -> bakeOverviewTreeIcons(base))
            : base;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int tileY = firstY; tileY <= lastY; tileY++) {
            for (int tileX = firstX; tileX <= lastX; tileX++) {
                double x = getWidth() * 0.5 + (tileX * World.WIDTH - centerX) * scale;
                double y = getHeight() * 0.5 - ((tileY + 1) * World.WIDTH - centerY) * scale;
                int size = (int) Math.ceil(World.WIDTH * scale);
                g.drawImage(image, (int) Math.floor(x), (int) Math.floor(y), size, size, null);
            }
        }
    }

    private void drawVectorHexes(Graphics2D g, double scale, int firstX, int lastX, int firstY, int lastY) {
        HexGrid<Hex> grid = world.preview(stage.index);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        for (int tileY = firstY; tileY <= lastY; tileY++) {
            double offsetY = tileY * World.WIDTH;
            for (int tileX = firstX; tileX <= lastX; tileX++) {
                double offsetX = tileX * World.WIDTH;
                for (AxialCoordinate coordinate : grid.coordinates) {
                    Vec2 point = coordinate.toWorld();
                    double worldX = point.x + offsetX;
                    double worldY = point.y + offsetY;
                    int centerScreenX = screenX(worldX, scale);
                    int centerScreenY = screenY(worldY, scale);
                    int halfWidth = (int) Math.ceil(scale * 2.0 / 3.0);
                    int halfHeight = (int) Math.ceil(scale * HEX_HALF_HEIGHT);
                    if (centerScreenX + halfWidth < 0 || centerScreenX - halfWidth > getWidth()
                        || centerScreenY + halfHeight < 0
                        || centerScreenY - halfHeight > getHeight()) continue;
                    int colour = stage.colour.applyAsInt(grid.get(coordinate));
                    if (stage.slopeShading) colour = slopeShade(grid, point, colour);
                    g.setColor(new Color(colour));
                    g.fill(hexagon(centerScreenX, centerScreenY, scale));
                }
            }
        }
    }

    private static Path2D hexagon(int centerX, int centerY, double scale) {
        double wide = scale * 2.0 / 3.0 + HEX_OVERDRAW_PIXELS;
        double narrow = scale / 3.0 + HEX_OVERDRAW_PIXELS * 0.5;
        double high = scale * HEX_HALF_HEIGHT + HEX_OVERDRAW_PIXELS;
        Path2D path = new Path2D.Double();
        path.moveTo(centerX - wide, centerY);
        path.lineTo(centerX - narrow, centerY - high);
        path.lineTo(centerX + narrow, centerY - high);
        path.lineTo(centerX + wide, centerY);
        path.lineTo(centerX + narrow, centerY + high);
        path.lineTo(centerX - narrow, centerY + high);
        path.closePath();
        return path;
    }

    private void drawTileBoundaries(Graphics2D g, double scale, int firstX, int lastX, int firstY, int lastY) {
        g.setColor(new Color(255, 255, 255, 90));
        g.setStroke(new BasicStroke(1.5f));
        for (int tileY = firstY; tileY <= lastY; tileY++) for (int tileX = firstX; tileX <= lastX; tileX++) {
            int x = screenX(tileX * World.WIDTH, scale);
            int y = screenY((tileY + 1) * World.WIDTH, scale);
            int size = (int) Math.round(World.WIDTH * scale);
            g.drawRect(x, y, size, size);
        }
    }

    private void drawArrows(Graphics2D g, double scale, int firstX, int lastX, int firstY, int lastY) {
        if (stage.arrow == null || scale < 2.0) return;
        HexGrid<Hex> grid = world.preview(stage.index);
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(new Color(255, 255, 255, 175));
        int spacing = scale > 8.0 ? 6 : 12;
        for (int tileY = firstY; tileY <= lastY; tileY++) for (int tileX = firstX; tileX <= lastX; tileX++) {
            for (int y = spacing / 2; y < World.WIDTH; y += spacing)
                for (int x = spacing / 2; x < World.WIDTH; x += spacing) {
                    Hex hex = grid.getWrapped(AxialCoordinate.fromWorld(new Vec2(x, y)));
                    Vec2 arrow = stage.arrow.apply(hex);
                    double length = Math.hypot(arrow.x, arrow.y);
                    if (length <= 1.0e-9) continue;
                    double factor = Math.min(spacing * 0.38, length * 2.0) / length;
                    int sx = screenX(tileX * World.WIDTH + x, scale);
                    int sy = screenY(tileY * World.WIDTH + y, scale);
                    int ex = screenX(tileX * World.WIDTH + x + arrow.x * factor, scale);
                    int ey = screenY(tileY * World.WIDTH + y + arrow.y * factor, scale);
                    g.drawLine(sx, sy, ex, ey);
                    double screenLength = Math.hypot(ex - sx, ey - sy);
                    if (screenLength < 2.0) continue;
                    double headLength = Math.min(5.0, screenLength * 0.45);
                    double dx = (ex - sx) / screenLength * headLength;
                    double dy = (ey - sy) / screenLength * headLength;
                    g.draw(new Line2D.Double(ex, ey, ex - dx - dy * 0.55, ey - dy + dx * 0.55));
                    g.draw(new Line2D.Double(ex, ey, ex - dx + dy * 0.55, ey - dy - dx * 0.55));
                }
        }
    }

    private void drawContours(Graphics2D g, double scale, int firstX, int lastX, int firstY, int lastY) {
        if (stage.contourGroup == null) return;
        Path2D contours = stageContours.computeIfAbsent(stage.index, ignored -> buildContours(world, stage));
        Graphics2D overlay = (Graphics2D) g.create();
        overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        overlay.translate(getWidth() * 0.5 - centerX * scale, getHeight() * 0.5 + centerY * scale);
        overlay.scale(scale, -scale);
        overlay.setStroke(new BasicStroke((float) (1.15 / scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        overlay.setColor(new Color(248, 239, 210, 210));
        for (int tileY = firstY - 1; tileY <= lastY + 1; tileY++) {
            for (int tileX = firstX - 1; tileX <= lastX + 1; tileX++) {
                double x = tileX * World.WIDTH;
                double y = tileY * World.WIDTH;
                if (screenX(x + World.WIDTH + 1, scale) < 0 || screenX(x - 1, scale) > getWidth()
                    || screenY(y - 1, scale) < 0
                    || screenY(y + World.WIDTH + 1, scale) > getHeight()) continue;
                overlay.translate(x, y);
                overlay.draw(contours);
                overlay.translate(-x, -y);
            }
        }
        overlay.dispose();
    }

    static Path2D buildContours(World world, StepDefinition stage) {
        HexGrid<Hex> grid = world.preview(stage.index);
        Path2D path = new Path2D.Double();
        double[] vertexX = { 2.0 / 3.0, 1.0 / 3.0, -1.0 / 3.0, -2.0 / 3.0, -1.0 / 3.0, 1.0 / 3.0 };
        double[] vertexY = { 0.0, HEX_HALF_HEIGHT, HEX_HALF_HEIGHT, 0.0, -HEX_HALF_HEIGHT, -HEX_HALF_HEIGHT };
        for (AxialCoordinate coordinate : grid.coordinates) {
            int group = stage.contourGroup.applyAsInt(grid.get(coordinate));
            Vec2 center = coordinate.toWorld();
            for (int direction = 0; direction < 6; direction++) {
                AxialCoordinate neighbor = coordinate.neighbor(direction);
                Hex adjacent = grid.get(neighbor);
                // Interior edges are shared. At the wrap seam each tile needs its own edge.
                if (adjacent != null && direction >= 3) continue;
                if (adjacent == null) adjacent = grid.getWrapped(neighbor);
                if (group == stage.contourGroup.applyAsInt(adjacent)) continue;
                int next = (direction + 1) % 6;
                path.moveTo(center.x + vertexX[direction], center.y + vertexY[direction]);
                path.lineTo(center.x + vertexX[next], center.y + vertexY[next]);
            }
        }
        return path;
    }

    static boolean hasTreeIcons(StepDefinition stage) {
        return "biomes".equals(stage.id) || "terrain-regions".equals(stage.id) || "terrain-biomes".equals(stage.id);
    }

    private void drawTreeIcons(Graphics2D g, double scale, int firstX, int lastX, int firstY, int lastY) {
        HexGrid<Hex> grid = world.preview(stage.index);
        int size = (int) Math.round(scale * TREE_ICON_SIZE_PER_UNIT);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int tileY = firstY; tileY <= lastY; tileY++) for (int tileX = firstX; tileX <= lastX; tileX++) {
            for (AxialCoordinate coordinate : grid.coordinates) {
                Vec2 point = coordinate.toWorld();
                int x = screenX(tileX * World.WIDTH + point.x, scale) - size / 2;
                int y = screenY(tileY * World.WIDTH + point.y, scale) - size / 2;
                if (x + size < 0 || x >= getWidth() || y + size < 0 || y >= getHeight()) continue;
                BufferedImage icon = treeIcons.get(grid.get(coordinate).dominantBiome);
                if (icon != null) g.drawImage(icon, x, y, size, size, null);
            }
        }
    }

    private BufferedImage bakeOverviewTreeIcons(BufferedImage base) {
        BufferedImage image = new BufferedImage(RASTER_SIZE, RASTER_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.drawImage(base, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        HexGrid<Hex> grid = world.preview(stage.index);
        int size = OVERVIEW_TREE_ICON_SIZE;
        for (AxialCoordinate coordinate : grid.coordinates) {
            if (coordinate.q % OVERVIEW_TREE_STRIDE != 0 || coordinate.r % OVERVIEW_TREE_STRIDE != 0) continue;
            BufferedImage icon = treeIcons.get(grid.get(coordinate).dominantBiome);
            if (icon == null) continue;
            Vec2 point = coordinate.toWorld();
            int x = (int) Math.round(point.x * RASTER_PIXELS_PER_UNIT) - size / 2;
            int y = (int) Math.round((World.WIDTH - point.y) * RASTER_PIXELS_PER_UNIT) - size / 2;
            int wrapX = x < 0 ? RASTER_SIZE : x + size > RASTER_SIZE ? -RASTER_SIZE : 0;
            int wrapY = y < 0 ? RASTER_SIZE : y + size > RASTER_SIZE ? -RASTER_SIZE : 0;
            g.drawImage(icon, x, y, size, size, null);
            if (wrapX != 0) g.drawImage(icon, x + wrapX, y, size, size, null);
            if (wrapY != 0) g.drawImage(icon, x, y + wrapY, size, size, null);
            if (wrapX != 0 && wrapY != 0) g.drawImage(icon, x + wrapX, y + wrapY, size, size, null);
        }
        g.dispose();
        return image;
    }

    private static Map<Biome, BufferedImage> loadTreeIcons() {
        Map<Biome, BufferedImage> icons = new EnumMap<>(Biome.class);
        BufferedImage boreal = loadTreeIcon("boreal");
        icons.put(Biome.BOREAL_FOREST, boreal);
        icons.put(Biome.SNOWY_BOREAL_FOREST, boreal);
        icons.put(Biome.TEMPERATE_FOREST, loadTreeIcon("temperate"));
        icons.put(Biome.SAVANNA, loadTreeIcon("savanna"));
        icons.put(Biome.JUNGLE, loadTreeIcon("jungle"));
        return icons;
    }

    private static BufferedImage loadTreeIcon(String name) {
        String path = "/assets/rwg3/preview/trees/" + name + ".png";
        try (InputStream stream = PreviewCanvas.class.getResourceAsStream(path)) {
            BufferedImage image = stream == null ? null : ImageIO.read(stream);
            if (image == null) throw new IllegalStateException("Missing or invalid tree icon " + path);
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read tree icon " + path, exception);
        }
    }

    private int screenX(double worldX, double scale) {
        return (int) Math.round(getWidth() * 0.5 + (worldX - centerX) * scale);
    }

    private int screenY(double worldY, double scale) {
        return (int) Math.round(getHeight() * 0.5 - (worldY - centerY) * scale);
    }

    private void zoomAt(double screenX, double screenY, double factor) {
        double oldScale = pixelsPerUnit();
        double worldX = centerX + (screenX - getWidth() * 0.5) / oldScale;
        double worldY = centerY - (screenY - getHeight() * 0.5) / oldScale;
        zoom = Math.max(0.3, Math.min(30.0, zoom * factor));
        double scale = pixelsPerUnit();
        centerX = wrap(worldX - (screenX - getWidth() * 0.5) / scale);
        centerY = wrap(worldY + (screenY - getHeight() * 0.5) / scale);
        repaint();
    }

    private double pixelsPerUnit() {
        return Math.max(0.01, Math.min(getWidth(), getHeight()) / World.WIDTH * 0.94 * zoom);
    }

    private void drawHoverDetails(Graphics2D g) {
        if (hoverPoint == null || dragStart != null || !contains(hoverPoint)) return;
        hoverTip.setTipText(hoverText(hoverPoint));
        Dimension size = hoverTip.getPreferredSize();
        int x = Math.max(0, Math.min(hoverPoint.x + 14, getWidth() - size.width));
        int y = hoverPoint.y + 18;
        if (y + size.height > getHeight()) y = Math.max(0, hoverPoint.y - size.height - 6);
        hoverTip.setSize(size);
        Graphics tipGraphics = g.create(x, y, size.width, size.height);
        hoverTip.paint(tipGraphics);
        tipGraphics.dispose();
    }

    private String hoverText(Point point) {
        double scale = pixelsPerUnit();
        double x = wrap(centerX + (point.x - getWidth() * 0.5) / scale);
        double y = wrap(centerY - (point.y - getHeight() * 0.5) / scale);
        Hex hex = world.preview(stage.index)
            .getWrapped(AxialCoordinate.fromWorld(new Vec2(x, y)));
        double blockX = (x - World.WIDTH * 0.5) * World.BLOCKS_PER_WORLD_UNIT;
        double blockZ = -(y - World.WIDTH * 0.5) * World.BLOCKS_PER_WORLD_UNIT;
        String detail = stage.tooltip == null ? "" : stage.tooltip.apply(hex);
        return String.format(
            Locale.ROOT,
            "X %.0f, Z %.0f%s",
            blockX,
            blockZ,
            detail == null || detail.isEmpty() ? "" : " — " + detail);
    }

    static BufferedImage rasterize(World world, StepDefinition stage) {
        BufferedImage image = new BufferedImage(RASTER_SIZE, RASTER_SIZE, BufferedImage.TYPE_INT_RGB);
        HexGrid<Hex> grid = world.preview(stage.index);
        for (int py = 0; py < RASTER_SIZE; py++) {
            double y = (RASTER_SIZE - py - 0.5) * World.WIDTH / RASTER_SIZE;
            for (int px = 0; px < RASTER_SIZE; px++) {
                double x = (px + 0.5) * World.WIDTH / RASTER_SIZE;
                Vec2 point = new Vec2(x, y);
                Hex hex = grid.getWrapped(AxialCoordinate.fromWorld(point));
                int colour = stage.colour.applyAsInt(hex);
                if (stage.slopeShading) colour = slopeShade(grid, point, colour);
                image.setRGB(px, py, colour);
            }
        }
        return image;
    }

    private static int slopeShade(HexGrid<Hex> grid, Vec2 point, int colour) {
        double west = sampledBlockHeight(grid, point.x - SLOPE_SAMPLE_DISTANCE, point.y);
        double east = sampledBlockHeight(grid, point.x + SLOPE_SAMPLE_DISTANCE, point.y);
        double south = sampledBlockHeight(grid, point.x, point.y - SLOPE_SAMPLE_DISTANCE);
        double north = sampledBlockHeight(grid, point.x, point.y + SLOPE_SAMPLE_DISTANCE);
        double denominator = 2.0 * SLOPE_SAMPLE_DISTANCE * World.BLOCKS_PER_WORLD_UNIT;
        double dx = (east - west) / denominator;
        double dy = (south - north) / denominator;
        double light = Math.max(0.55, Math.min(1.35, 0.95 - dx * 0.45 - dy * 0.45));
        return shade(colour, light);
    }

    private static double sampledBlockHeight(HexGrid<Hex> grid, double x, double y) {
        Hex sample = grid.getWrapped(AxialCoordinate.fromWorld(new Vec2(x, y)));
        return World.blockHeight(sample.height);
    }

    private static int shade(int colour, double light) {
        int red = clampColour((int) Math.round(((colour >> 16) & 255) * light));
        int green = clampColour((int) Math.round(((colour >> 8) & 255) * light));
        int blue = clampColour((int) Math.round((colour & 255) * light));
        return red << 16 | green << 8 | blue;
    }

    private static int clampColour(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static double wrap(double value) {
        double result = value % World.WIDTH;
        return result < 0.0 ? result + World.WIDTH : result;
    }
}
