/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers, per typed value <em>instance</em>, the envelope that was written for it or read into
 * it. The engine detects implicit changes of mutable values (deserialized objects, JSON) by
 * serializing the value again into a nameless scratch record and comparing the bytes with the
 * stored ones. With a random IV every encryption differs, so without this memo every unchanged
 * value would look modified in every command. An unchanged value reproduces its stored envelope
 * (same plaintext digest); a changed one is encrypted anew. Entries disappear with their value
 * (weak references, identity comparison). Thread-safe. Not API.
 */
final class ValueMemo {

    /**
     * What is known about a value.
     *
     * @param variableName the variable it was written for or read from
     * @param payloadDigest SHA-256 of the plaintext payload
     * @param envelope the stored envelope
     * @param keyId the key of the envelope
     */
    record Entry(String variableName, byte[] payloadDigest, byte[] envelope, String keyId) {}

    private static final class Key extends WeakReference<Object> {
        private final int hash;

        Key(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            Object mine = get();
            return mine != null && mine == key.get();
        }
    }

    private final Map<Key, Entry> entries = new HashMap<>();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    synchronized Entry get(Object value) {
        purge();
        return entries.get(new Key(value, null));
    }

    synchronized void put(Object value, Entry entry) {
        purge();
        entries.put(new Key(value, queue), entry);
    }

    private void purge() {
        Object stale;
        while ((stale = queue.poll()) != null) {
            entries.remove(stale);
        }
    }
}
