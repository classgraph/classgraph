package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Class for locking in a fine-grained way, at the level of individual keys. */
public class KeyLocker {
    private final ConcurrentMap<Object, Object> keyToLock = new ConcurrentHashMap<>();

    /** Get or create a key-specific lock to synchronize on. */
    protected Object getLock(final Object key) {
        final Object object = new Object();
        Object lock = keyToLock.putIfAbsent(key, object);
        if (lock == null) {
            lock = object;
        }
        return lock;
    }
}
