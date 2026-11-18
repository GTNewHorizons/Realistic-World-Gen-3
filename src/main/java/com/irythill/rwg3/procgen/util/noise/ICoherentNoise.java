package com.irythill.rwg3.procgen.util.noise;

import java.util.Arrays;

/** A deterministic point field supporting allocation-free nearest-site queries. */
public interface ICoherentNoise {

    void nearest(double x, double y, int count, Query result);

    /** Reusable output storage. Entries are ordered by increasing distance. */
    final class Query {

        public long[] ids;
        public double[] x;
        public double[] y;
        public double[] distance;
        public int count;

        public Query(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
            ids = new long[capacity];
            x = new double[capacity];
            y = new double[capacity];
            distance = new double[capacity];
        }

        public void ensureCapacity(int capacity) {
            if (ids.length >= capacity) return;
            int size = Math.max(capacity, ids.length * 2);
            ids = Arrays.copyOf(ids, size);
            x = Arrays.copyOf(x, size);
            y = Arrays.copyOf(y, size);
            distance = Arrays.copyOf(distance, size);
        }
    }
}
