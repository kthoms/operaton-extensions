/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/cibseven/cibseven/blob/main/engine-plugins/identity-scim/src/main/java/org/cibseven/bpm/identity/impl/scim/ScimSimpleCache.java (Apache-2.0)
 * Changes: TTL in milliseconds with an injectable clock, least-recently-inserted eviction under one
 * lock instead of a scan of a concurrent map, a disabled mode.
 */
package org.operaton.extensions.plugin.identityscim.internal;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A small thread-safe cache with a time to live and a maximum size. The oldest entry is evicted
 * first. A zero TTL disables it: nothing is stored and every lookup misses.
 *
 * @param <V> the cached value type; values must not be modified after they are stored
 */
public final class ScimTtlCache<V> {

    private record Entry<V>(V value, long expiresAtNanos) {}

    private final long ttlNanos;
    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final LinkedHashMap<String, Entry<V>> entries = new LinkedHashMap<>();

    /**
     * Creates a cache.
     *
     * @param ttl how long an entry stays valid; zero disables the cache
     * @param maxEntries the maximum number of entries
     * @param nanoClock a monotonic clock in nanoseconds ({@link System#nanoTime()} in production)
     */
    public ScimTtlCache(Duration ttl, int maxEntries, LongSupplier nanoClock) {
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = maxEntries;
        this.nanoClock = nanoClock;
    }

    /**
     * Returns the cached value.
     *
     * @param key the cache key
     * @return the value, or {@code null} when there is none or it expired
     */
    public synchronized V get(String key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (nanoClock.getAsLong() - entry.expiresAtNanos() >= 0) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    /**
     * Stores a value.
     *
     * @param key the cache key
     * @param value the value; never {@code null}
     */
    public synchronized void put(String key, V value) {
        if (ttlNanos <= 0) {
            return;
        }
        long now = nanoClock.getAsLong();
        entries.remove(key);
        if (entries.size() >= maxEntries) {
            for (Iterator<Map.Entry<String, Entry<V>>> it = entries.entrySet().iterator(); it.hasNext(); ) {
                if (now - it.next().getValue().expiresAtNanos() >= 0) {
                    it.remove();
                }
            }
            while (entries.size() >= maxEntries) {
                Iterator<String> oldest = entries.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
        entries.put(key, new Entry<>(value, now + ttlNanos));
    }

    /** Removes every entry. */
    public synchronized void clear() {
        entries.clear();
    }

    /**
     * Returns the number of stored entries, expired ones included until they are looked up or evicted.
     *
     * @return the entry count
     */
    public synchronized int size() {
        return entries.size();
    }
}
