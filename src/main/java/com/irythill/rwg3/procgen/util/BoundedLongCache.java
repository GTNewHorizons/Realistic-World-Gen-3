package com.irythill.rwg3.procgen.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongFunction;

/** Fixed-size set-associative cache with primitive keys and lock-free reads. */
public final class BoundedLongCache<V> {

    private static final int WAYS = 8;
    private final int setMask;
    private final AtomicLongArray keys;
    private final AtomicReferenceArray<V> values;
    private final Object[] locks = new Object[256];
    private final AtomicInteger victim = new AtomicInteger();

    public BoundedLongCache(int capacity) {
        if (capacity < WAYS) throw new IllegalArgumentException("capacity is too small");
        int sets = 1;
        while (sets * WAYS < capacity) sets <<= 1;
        setMask = sets - 1;
        keys = new AtomicLongArray(sets * WAYS);
        values = new AtomicReferenceArray<>(sets * WAYS);
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    public V getOrCreate(long key, LongFunction<? extends V> factory) {
        int first = firstSlot(key);
        V cached = find(first, key);
        if (cached != null) return cached;
        Object lock = locks[(first / WAYS) & (locks.length - 1)];
        synchronized (lock) {
            cached = find(first, key);
            if (cached != null) return cached;
            int selected = -1;
            for (int way = 0; way < WAYS; way++) if (values.get(first + way) == null) {
                selected = first + way;
                break;
            }
            if (selected < 0) selected = first + (victim.getAndIncrement() & (WAYS - 1));
            V created = factory.apply(key);
            values.set(selected, null);
            keys.set(selected, key);
            values.set(selected, created);
            return created;
        }
    }

    private V find(int first, long key) {
        for (int way = 0; way < WAYS; way++) {
            int index = first + way;
            if (keys.get(index) != key) continue;
            V value = values.get(index);
            if (value != null && keys.get(index) == key) return value;
        }
        return null;
    }

    private int firstSlot(long key) {
        long mixed = key ^ key >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return ((int) mixed & setMask) * WAYS;
    }
}
