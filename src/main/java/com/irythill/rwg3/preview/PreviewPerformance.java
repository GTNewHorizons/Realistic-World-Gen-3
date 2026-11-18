package com.irythill.rwg3.preview;

import java.awt.geom.Rectangle2D;
import java.util.concurrent.CountDownLatch;

import com.irythill.rwg3.procgen.util.hex.AxialCoordinate;
import com.irythill.rwg3.procgen.util.hex.Vec2;
import com.irythill.rwg3.procgen.world.WorldGenerator;

/** Deterministic headless throughput rig for infinite preview composition. */
public final class PreviewPerformance {

    private static final long SEED = 0x5247573350455246L;
    private static final int WARMUP_POINTS = 2_000;
    private static final int MEASURE_POINTS = 10_000;

    private PreviewPerformance() {}

    public static void main(String[] arguments) throws InterruptedException {
        WorldGenerator ocean = new WorldGenerator(SEED);
        sample(ocean, WARMUP_POINTS, 0);
        Result oceanResult = measure(() -> sample(ocean, MEASURE_POINTS, 1), MEASURE_POINTS);

        WorldGenerator loaded = new WorldGenerator(SEED);
        loaded.prepare(new Rectangle2D.Double(-30_000, -30_000, 60_000, 60_000));
        sample(loaded, WARMUP_POINTS, 2);
        Result loadedResult = measure(() -> sample(loaded, MEASURE_POINTS, 3), MEASURE_POINTS);
        spatialSample(loaded, WARMUP_POINTS);
        Result spatialResult = measure(() -> spatialSample(loaded, MEASURE_POINTS), MEASURE_POINTS);

        int threads = Math.max(
            2,
            Math.min(
                8,
                Runtime.getRuntime()
                    .availableProcessors()));
        Result parallelResult = measureParallel(loaded, threads, MEASURE_POINTS);
        Result parallelSpatialResult = measureParallelSpatial(loaded, threads, MEASURE_POINTS);
        System.out.printf(
            "PREVIEW_PERF ocean_samples_per_second=%.1f loaded_samples_per_second=%.1f "
                + "spatial_samples_per_second=%.1f parallel_samples_per_second=%.1f "
                + "parallel_spatial_samples_per_second=%.1f threads=%d checksum=0x%016X%n",
            oceanResult.perSecond,
            loadedResult.perSecond,
            spatialResult.perSecond,
            parallelResult.perSecond,
            parallelSpatialResult.perSecond,
            threads,
            oceanResult.checksum ^ loadedResult.checksum
                ^ spatialResult.checksum
                ^ parallelResult.checksum
                ^ parallelSpatialResult.checksum);
    }

    private static Result measureParallel(WorldGenerator composer, int threads, int total) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long[] checksums = new long[threads];
        int perThread = total / threads;
        for (int thread = 0; thread < threads; thread++) {
            final int index = thread;
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    checksums[index] = sample(composer, perThread, index + 11);
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                } finally {
                    done.countDown();
                }
            }, "preview-perf-" + thread);
            worker.setDaemon(true);
            worker.start();
        }
        ready.await();
        long begin = System.nanoTime();
        start.countDown();
        done.await();
        long elapsed = System.nanoTime() - begin;
        long checksum = 0L;
        for (long value : checksums) checksum ^= value;
        return new Result(total * 1.0e9 / elapsed, checksum);
    }

    private static Result measureParallelSpatial(WorldGenerator composer, int threads, int total)
        throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long[] checksums = new long[threads];
        int perThread = total / threads;
        for (int thread = 0; thread < threads; thread++) {
            final int index = thread;
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    checksums[index] = spatialSample(composer, perThread, index * perThread);
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                } finally {
                    done.countDown();
                }
            }, "preview-spatial-perf-" + thread);
            worker.setDaemon(true);
            worker.start();
        }
        ready.await();
        long begin = System.nanoTime();
        start.countDown();
        done.await();
        long elapsed = System.nanoTime() - begin;
        long checksum = 0L;
        for (long value : checksums) checksum ^= value;
        return new Result(total * 1.0e9 / elapsed, checksum);
    }

    private static Result measure(Measured measured, int points) {
        long begin = System.nanoTime();
        long checksum = measured.run();
        long elapsed = System.nanoTime() - begin;
        return new Result(points * 1.0e9 / elapsed, checksum);
    }

    private static long sample(WorldGenerator composer, int count, int salt) {
        long checksum = 0L;
        for (int i = 0; i < count; i++) {
            int sequence = i + salt * 104729;
            double x = (sequence * 73 % 997) - 498.0 + (sequence % 7) * 0.13;
            double y = (sequence * 193 % 991) - 495.0 + (sequence % 11) * 0.09;
            double elevation = composer.sample(
                x,
                y,
                composer.worldSteps()
                    .get(5)).elevation;
            checksum = Long.rotateLeft(checksum, 7) ^ Double.doubleToLongBits(elevation);
        }
        return checksum;
    }

    private static long spatialSample(WorldGenerator composer, int count) {
        return spatialSample(composer, count, 0);
    }

    private static long spatialSample(WorldGenerator composer, int count, int offset) {
        long checksum = 0L;
        int width = (int) Math.ceil(Math.sqrt(count));
        for (int i = 0; i < count; i++) {
            int sequence = i + offset;
            Vec2 point = new AxialCoordinate(sequence % width - width / 2, sequence / width - width / 2).toWorld();
            double elevation = composer.sample(
                point.x,
                point.y,
                composer.worldSteps()
                    .get(5)).elevation;
            checksum = Long.rotateLeft(checksum, 7) ^ Double.doubleToLongBits(elevation);
        }
        return checksum;
    }

    private interface Measured {

        long run();
    }

    private static final class Result {

        final double perSecond;
        final long checksum;

        Result(double perSecond, long checksum) {
            this.perSecond = perSecond;
            this.checksum = checksum;
        }
    }
}
