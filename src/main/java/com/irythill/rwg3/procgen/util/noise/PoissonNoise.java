package com.irythill.rwg3.procgen.util.noise;

import com.irythill.rwg3.procgen.util.BoundedLongCache;

/** Deterministic, fixed-round Poisson dart throwing over an infinite hashed grid. */
public final class PoissonNoise implements ICoherentNoise {

    private static final double SQRT_TWO = 1.4142135623730951;
    private final long seed;
    private final double minimumDistance;
    private final double minimumDistanceSquared;
    private final double cellSize;
    private final int neighborCells;
    private final int rounds;
    private final BoundedLongCache<Cell> cells = new BoundedLongCache<>(32768);
    private final ThreadLocal<LocalCellCache> localCells = ThreadLocal.withInitial(LocalCellCache::new);

    public PoissonNoise(long seed, double minimumDistance, int rounds) {
        if (!(minimumDistance > 0.0) || rounds < 1) throw new IllegalArgumentException("Invalid Poisson parameters");
        this.seed = seed;
        this.minimumDistance = minimumDistance;
        minimumDistanceSquared = minimumDistance * minimumDistance;
        cellSize = minimumDistance / SQRT_TWO;
        neighborCells = (int) Math.ceil(minimumDistance / cellSize) + 1;
        this.rounds = rounds;
    }

    @Override
    public void nearest(double x, double y, int count, Query result) {
        if (count < 1) throw new IllegalArgumentException("count must be positive");
        result.ensureCapacity(count);
        result.count = 0;
        int queryX = floor(x / cellSize);
        int queryY = floor(y / cellSize);
        search: for (int ring = 0;; ring++) {
            for (int oy = -ring; oy <= ring; oy++) {
                for (int ox = -ring; ox <= ring; ox++) {
                    if (ring != 0 && Math.abs(ox) != ring && Math.abs(oy) != ring) continue;
                    for (int round = 0; round < rounds; round++) {
                        Candidate candidate = candidate(queryX + ox, queryY + oy, round);
                        if (isAccepted(candidate)) insert(result, count, candidate, x, y);
                    }
                }
            }
            double unsearched = minimumUnsearchedDistance(x, y, queryX, queryY, ring);
            if (result.count == count && unsearched * unsearched > result.distance[count - 1]) break search;
        }
        for (int i = 0; i < result.count; i++) result.distance[i] = Math.sqrt(result.distance[i]);
    }

    private static void insert(Query result, int limit, Candidate candidate, double x, double y) {
        double dx = candidate.x - x;
        double dy = candidate.y - y;
        double distance = dx * dx + dy * dy;
        int position = Math.min(result.count, limit);
        while (position > 0 && (distance < result.distance[position - 1] || distance == result.distance[position - 1]
            && Long.compareUnsigned(candidate.id, result.ids[position - 1]) < 0)) position--;
        if (position >= limit) return;
        int end = Math.min(result.count, limit - 1);
        for (int i = end; i > position; i--) {
            result.ids[i] = result.ids[i - 1];
            result.x[i] = result.x[i - 1];
            result.y[i] = result.y[i - 1];
            result.distance[i] = result.distance[i - 1];
        }
        result.ids[position] = candidate.id;
        result.x[position] = candidate.x;
        result.y[position] = candidate.y;
        result.distance[position] = distance;
        if (result.count < limit) result.count++;
    }

    private double minimumUnsearchedDistance(double x, double y, int cellX, int cellY, int ring) {
        double left = (cellX - ring) * cellSize;
        double right = (cellX + ring + 1) * cellSize;
        double bottom = (cellY - ring) * cellSize;
        double top = (cellY + ring + 1) * cellSize;
        return Math.min(Math.min(x - left, right - x), Math.min(y - bottom, top - y));
    }

    private boolean isAccepted(Candidate candidate) {
        if (candidate.accepted != 0) return candidate.accepted > 0;
        synchronized (candidate) {
            if (candidate.accepted != 0) return candidate.accepted > 0;
            if (!isClearOfEarlierRounds(candidate)) {
                candidate.accepted = -1;
                return false;
            }
            for (int oy = -neighborCells; oy <= neighborCells; oy++) {
                for (int ox = -neighborCells; ox <= neighborCells; ox++) {
                    if (ox == 0 && oy == 0) continue;
                    Candidate neighbor = candidate(candidate.cellX + ox, candidate.cellY + oy, candidate.round);
                    if (withinDistance(candidate, neighbor) && lowerPriority(neighbor, candidate)
                        && isClearOfEarlierRounds(neighbor)) {
                        candidate.accepted = -1;
                        return false;
                    }
                }
            }
            candidate.accepted = 1;
            return true;
        }
    }

    private boolean isClearOfEarlierRounds(Candidate candidate) {
        for (int round = 0; round < candidate.round; round++) {
            for (int oy = -neighborCells; oy <= neighborCells; oy++) {
                for (int ox = -neighborCells; ox <= neighborCells; ox++) {
                    Candidate earlier = candidate(candidate.cellX + ox, candidate.cellY + oy, round);
                    if (withinDistance(candidate, earlier) && isAccepted(earlier)) return false;
                }
            }
        }
        return true;
    }

    private boolean withinDistance(Candidate left, Candidate right) {
        double dx = left.x - right.x;
        double dy = left.y - right.y;
        return dx * dx + dy * dy < minimumDistanceSquared;
    }

    private static boolean lowerPriority(Candidate left, Candidate right) {
        int comparison = Long.compareUnsigned(left.priority, right.priority);
        return comparison < 0 || comparison == 0 && Long.compareUnsigned(left.id, right.id) < 0;
    }

    private Candidate candidate(int cellX, int cellY, int round) {
        long key = mix(cellKey(cellX, cellY) ^ 0x43454C4C43414348L);
        LocalCellCache local = localCells.get();
        Cell cell = local.get(key);
        if (cell == null) {
            cell = cells.getOrCreate(key, ignored -> new Cell(rounds));
            local.put(key, cell);
        }
        Candidate value = cell.candidates[round];
        if (value == null) synchronized (cell) {
            value = cell.candidates[round];
            if (value == null) {
                int salt = round * 3;
                double x = (cellX + unit(hash(cellX, cellY, salt))) * cellSize;
                double y = (cellY + unit(hash(cellX, cellY, salt + 1))) * cellSize;
                long priority = hash(cellX, cellY, salt + 2);
                value = new Candidate(cellX, cellY, round, x, y, priority, siteId(cellX, cellY, round));
                cell.candidates[round] = value;
            }
        }
        return value;
    }

    private long siteId(int x, int y, int round) {
        return mix(seed ^ cellKey(x, y) ^ (long) round * 0x9E3779B97F4A7C15L ^ 0x534954454944L);
    }

    private long hash(int x, int y, int salt) {
        return mix(seed ^ (long) x * 341873128712L ^ (long) y * 132897987541L ^ (long) salt * 42317861L);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    private static long cellKey(int x, int y) {
        return (long) x & 0xffffffffL | (long) y << 32;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    public double minimumDistance() {
        return minimumDistance;
    }

    public double cellSize() {
        return cellSize;
    }

    public int rounds() {
        return rounds;
    }

    private static final class Cell {

        final Candidate[] candidates;

        Cell(int rounds) {
            candidates = new Candidate[rounds];
        }
    }

    private static final class Candidate {

        final int cellX, cellY, round;
        final double x, y;
        final long priority, id;
        volatile byte accepted;

        Candidate(int cellX, int cellY, int round, double x, double y, long priority, long id) {
            this.cellX = cellX;
            this.cellY = cellY;
            this.round = round;
            this.x = x;
            this.y = y;
            this.priority = priority;
            this.id = id;
        }
    }

    private static final class LocalCellCache {

        private static final int MASK = 1023;
        private final long[] keys = new long[MASK + 1];
        private final Cell[] values = new Cell[MASK + 1];

        Cell get(long key) {
            int index = (int) key & MASK;
            return keys[index] == key ? values[index] : null;
        }

        void put(long key, Cell value) {
            int index = (int) key & MASK;
            keys[index] = key;
            values[index] = value;
        }
    }

}
