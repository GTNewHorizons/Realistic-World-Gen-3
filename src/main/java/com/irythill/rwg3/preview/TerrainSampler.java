package com.irythill.rwg3.preview;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.irythill.rwg3.procgen.world.Column;
import com.irythill.rwg3.procgen.world.WorldGenerator;

/** Headless block-coordinate sampler for inspecting the final terrain pipeline. */
final class TerrainSampler {

    private static final long MAXIMUM_SAMPLES = 1_000_000L;

    private TerrainSampler() {}

    static void run(String[] arguments) {
        Parsed parsed = parse(arguments);
        WorldGenerator engine = new WorldGenerator(parsed.seed);
        engine.prepare(parsed.worldBounds());
        for (Request request : parsed.requests) request.prepare(engine);

        System.out.println(
            "series,index,block_x,block_z,source_y,eroded_y,recovered_y,pure_sediment_y,sedimented_y,source_grade,pure_sediment_grade,sediment_mix,erosion_delta,incision_recovery_delta,pure_sediment_delta,applied_sediment_delta,complete,site_id,plate_id,oceanic");
        for (Request request : parsed.requests) {
            for (long index = 0; index < request.count(); index++) {
                Point point = request.point(index);
                Column profile = engine.sampleColumn(point.x, point.z);
                System.out.printf(
                    Locale.ROOT,
                    "%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s,%d,%d,%s%n",
                    request.name(),
                    index,
                    point.x,
                    point.z,
                    profile.sourceHeight,
                    profile.erodedHeight,
                    profile.recoveredHeight,
                    profile.sedimentHeight,
                    profile.finalHeight,
                    profile.sourceGrade,
                    profile.sedimentGrade,
                    profile.sedimentMix,
                    profile.erodedHeight - profile.sourceHeight,
                    profile.recoveredHeight - profile.erodedHeight,
                    profile.sedimentHeight - profile.erodedHeight,
                    profile.finalHeight - profile.erodedHeight,
                    profile.complete,
                    profile.continentId,
                    profile.plateId,
                    profile.oceanic);
                if (!profile.complete) {
                    throw new IllegalStateException("Incomplete terrain sample at " + point.x + ", " + point.z);
                }
            }
        }
    }

    static String usage() {
        return "Usage: --sample [--seed <long>] " + "[--point <x> <z>] [--line <x1> <z1> <x2> <z2> <spacing>] "
            + "[--grid <minX> <minZ> <maxX> <maxZ> <spacing>] "
            + "[--north-path <startX> <startZ> <steps> <spacing>]";
    }

    private static Parsed parse(String[] arguments) {
        long seed = 123L;
        List<Request> requests = new ArrayList<>();
        int points = 0, lines = 0, grids = 0, paths = 0;
        long total = 0L;
        for (int index = 1; index < arguments.length;) {
            String option = arguments[index++];
            switch (option) {
                case "--seed":
                    requireRemaining(arguments, index, 1, option);
                    seed = parseLong(arguments[index++], "seed");
                    break;
                case "--point": {
                    requireRemaining(arguments, index, 2, option);
                    Point point = new Point(parseDouble(arguments[index++], "x"), parseDouble(arguments[index++], "z"));
                    requests.add(new PointRequest("point-" + points++, point));
                    total++;
                    break;
                }
                case "--line": {
                    requireRemaining(arguments, index, 5, option);
                    Point start = new Point(
                        parseDouble(arguments[index++], "x1"),
                        parseDouble(arguments[index++], "z1"));
                    Point end = new Point(parseDouble(arguments[index++], "x2"), parseDouble(arguments[index++], "z2"));
                    double spacing = positive(parseDouble(arguments[index++], "spacing"), "spacing");
                    Request request = new LineRequest("line-" + lines++, start, end, spacing);
                    requests.add(request);
                    total += request.count();
                    break;
                }
                case "--grid": {
                    requireRemaining(arguments, index, 5, option);
                    double minimumX = parseDouble(arguments[index++], "minX");
                    double minimumZ = parseDouble(arguments[index++], "minZ");
                    double maximumX = parseDouble(arguments[index++], "maxX");
                    double maximumZ = parseDouble(arguments[index++], "maxZ");
                    double spacing = positive(parseDouble(arguments[index++], "spacing"), "spacing");
                    Request request = new GridRequest(
                        "grid-" + grids++,
                        Math.min(minimumX, maximumX),
                        Math.min(minimumZ, maximumZ),
                        Math.max(minimumX, maximumX),
                        Math.max(minimumZ, maximumZ),
                        spacing);
                    requests.add(request);
                    total += request.count();
                    break;
                }
                case "--north-path": {
                    requireRemaining(arguments, index, 4, option);
                    Point start = new Point(
                        parseDouble(arguments[index++], "startX"),
                        parseDouble(arguments[index++], "startZ"));
                    long steps = parseLong(arguments[index++], "steps");
                    if (steps < 1L || steps >= MAXIMUM_SAMPLES) {
                        throw new IllegalArgumentException("steps must be between 1 and " + (MAXIMUM_SAMPLES - 1L));
                    }
                    double spacing = positive(parseDouble(arguments[index++], "spacing"), "spacing");
                    Request request = new NorthPathRequest("north-path-" + paths++, start, steps, spacing);
                    requests.add(request);
                    total += request.count();
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unknown sampling option: " + option);
            }
            if (total > MAXIMUM_SAMPLES) {
                throw new IllegalArgumentException("Sampling request exceeds " + MAXIMUM_SAMPLES + " points");
            }
        }
        if (requests.isEmpty()) throw new IllegalArgumentException("At least one point, line or grid is required");
        return new Parsed(seed, requests);
    }

    private static void requireRemaining(String[] arguments, int index, int count, String option) {
        if (arguments.length - index < count) throw new IllegalArgumentException("Missing values for " + option);
    }

    private static long parseLong(String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value, exception);
        }
    }

    private static double parseDouble(String value, String label) {
        try {
            double result = Double.parseDouble(value);
            if (!Double.isFinite(result)) throw new NumberFormatException("non-finite");
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value, exception);
        }
    }

    private static double positive(double value, String label) {
        if (!(value > 0.0)) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }

    private static long intervals(double distance, double spacing) {
        if (distance == 0.0) return 0L;
        double intervals = Math.ceil(distance / spacing);
        if (!Double.isFinite(intervals) || intervals > MAXIMUM_SAMPLES) return MAXIMUM_SAMPLES + 1L;
        return (long) intervals;
    }

    private static final class Parsed {

        final long seed;
        final List<Request> requests;

        Parsed(long seed, List<Request> requests) {
            this.seed = seed;
            this.requests = requests;
        }

        Rectangle2D worldBounds() {
            double minimumX = Double.POSITIVE_INFINITY, maximumX = Double.NEGATIVE_INFINITY;
            double minimumY = Double.POSITIVE_INFINITY, maximumY = Double.NEGATIVE_INFINITY;
            for (Request request : requests) {
                minimumX = Math.min(minimumX, request.minimumX());
                maximumX = Math.max(maximumX, request.maximumX());
                minimumY = Math.min(minimumY, request.minimumZ());
                maximumY = Math.max(maximumY, request.maximumZ());
            }
            return new Rectangle2D.Double(minimumX, minimumY, maximumX - minimumX, maximumY - minimumY);
        }
    }

    private interface Request {

        String name();

        default void prepare(WorldGenerator engine) {}

        long count();

        Point point(long index);

        double minimumX();

        double maximumX();

        double minimumZ();

        double maximumZ();

    }

    private abstract static class NamedRequest implements Request {

        final String name;

        NamedRequest(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }
    }

    private static final class PointRequest extends NamedRequest {

        final Point point;

        PointRequest(String name, Point point) {
            super(name);
            this.point = point;
        }

        @Override
        public long count() {
            return 1L;
        }

        @Override
        public Point point(long index) {
            return point;
        }

        @Override
        public double minimumX() {
            return point.x;
        }

        @Override
        public double maximumX() {
            return point.x;
        }

        @Override
        public double minimumZ() {
            return point.z;
        }

        @Override
        public double maximumZ() {
            return point.z;
        }
    }

    private static final class LineRequest extends NamedRequest {

        final Point start;
        final Point end;
        final long intervals;

        LineRequest(String name, Point start, Point end, double spacing) {
            super(name);
            this.start = start;
            this.end = end;
            intervals = TerrainSampler.intervals(Math.hypot(end.x - start.x, end.z - start.z), spacing);
        }

        @Override
        public long count() {
            return intervals + 1L;
        }

        @Override
        public Point point(long index) {
            double amount = intervals == 0L ? 0.0 : (double) index / intervals;
            return new Point(lerp(start.x, end.x, amount), lerp(start.z, end.z, amount));
        }

        @Override
        public double minimumX() {
            return Math.min(start.x, end.x);
        }

        @Override
        public double maximumX() {
            return Math.max(start.x, end.x);
        }

        @Override
        public double minimumZ() {
            return Math.min(start.z, end.z);
        }

        @Override
        public double maximumZ() {
            return Math.max(start.z, end.z);
        }
    }

    private static final class NorthPathRequest extends NamedRequest {

        private static final double[] SIDEWAYS = { 0.0, -0.5, 0.5, -1.0, 1.0 };

        final Point start;
        final long steps;
        final double spacing;
        List<Point> path;

        NorthPathRequest(String name, Point start, long steps, double spacing) {
            super(name);
            this.start = start;
            this.steps = steps;
            this.spacing = spacing;
        }

        @Override
        public void prepare(WorldGenerator engine) {
            path = new ArrayList<>((int) steps + 1);
            Point current = start;
            path.add(current);
            double currentHeight = height(engine, current);
            for (long step = 0; step < steps; step++) {
                Point selected = null;
                double selectedHeight = Double.POSITIVE_INFINITY;
                double selectedSideways = Double.POSITIVE_INFINITY;
                boolean foundDescent = false;
                for (double sideways : SIDEWAYS) {
                    Point candidate = new Point(current.x + sideways * spacing, current.z - spacing);
                    double candidateHeight = height(engine, candidate);
                    boolean descends = candidateHeight < currentHeight - 1.0e-9;
                    if (descends && !foundDescent
                        || descends == foundDescent && (candidateHeight < selectedHeight - 1.0e-9
                            || Math.abs(candidateHeight - selectedHeight) <= 1.0e-9
                                && Math.abs(sideways) < selectedSideways)) {
                        selected = candidate;
                        selectedHeight = candidateHeight;
                        selectedSideways = Math.abs(sideways);
                        foundDescent = descends;
                    }
                }
                current = selected;
                currentHeight = selectedHeight;
                path.add(current);
            }
        }

        private static double height(WorldGenerator engine, Point point) {
            Column profile = engine.sampleColumn(point.x, point.z);
            if (!profile.complete) {
                throw new IllegalStateException("Incomplete north-path sample at " + point.x + ", " + point.z);
            }
            return profile.finalHeight;
        }

        @Override
        public long count() {
            return steps + 1L;
        }

        @Override
        public Point point(long index) {
            return path.get((int) index);
        }

        @Override
        public double minimumX() {
            return start.x - steps * spacing;
        }

        @Override
        public double maximumX() {
            return start.x + steps * spacing;
        }

        @Override
        public double minimumZ() {
            return start.z - steps * spacing;
        }

        @Override
        public double maximumZ() {
            return start.z;
        }
    }

    private static final class GridRequest extends NamedRequest {

        final double minimumX;
        final double minimumZ;
        final double maximumX;
        final double maximumZ;
        final long xIntervals;
        final long zIntervals;

        GridRequest(String name, double minimumX, double minimumZ, double maximumX, double maximumZ, double spacing) {
            super(name);
            this.minimumX = minimumX;
            this.minimumZ = minimumZ;
            this.maximumX = maximumX;
            this.maximumZ = maximumZ;
            xIntervals = intervals(maximumX - minimumX, spacing);
            zIntervals = intervals(maximumZ - minimumZ, spacing);
        }

        @Override
        public long count() {
            try {
                return Math.multiplyExact(xIntervals + 1L, zIntervals + 1L);
            } catch (ArithmeticException exception) {
                return MAXIMUM_SAMPLES + 1L;
            }
        }

        @Override
        public Point point(long index) {
            long width = xIntervals + 1L;
            long xIndex = index % width;
            long zIndex = index / width;
            double xAmount = xIntervals == 0L ? 0.0 : (double) xIndex / xIntervals;
            double zAmount = zIntervals == 0L ? 0.0 : (double) zIndex / zIntervals;
            return new Point(lerp(minimumX, maximumX, xAmount), lerp(minimumZ, maximumZ, zAmount));
        }

        @Override
        public double minimumX() {
            return minimumX;
        }

        @Override
        public double maximumX() {
            return maximumX;
        }

        @Override
        public double minimumZ() {
            return minimumZ;
        }

        @Override
        public double maximumZ() {
            return maximumZ;
        }
    }

    private static final class Point {

        final double x;
        final double z;

        Point(double x, double z) {
            this.x = x;
            this.z = z;
        }
    }

    private static double lerp(double start, double end, double amount) {
        return start + (end - start) * amount;
    }
}
