package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Concurrent map which atomically checks if a given key exists in the map, and if not, runs an initializer method
 * CollectionAdapter.newCollection() to produce a value holder of type H, which is stored as the value for the key.
 * The value of type V is then stored in the value holder.
 * 
 * This can be used when the value initializer is a costly operation, since putIfAbsent() requires the value to
 * already be constructed when it is passed in, but the value will be discarded if the key already exists in the
 * map.
 */
public class ConcurrentMultiMapWithInitializer<K, V, C extends Collection<V>> {
    private final ConcurrentMap<K, C> map = new ConcurrentHashMap<>();
    private final ConcurrentMap<K, Object> keyToLock = new ConcurrentHashMap<>();
    private final CollectionAdapter<K, V, C> collectionAdapter;

    public static abstract class CollectionAdapter<K, V, C extends Collection<V>> {
        public abstract C newCollection(K key);

        public boolean put(final K key, final V value, final C collection) {
            return collection.add(value);
        }

        public boolean putAll(final K key, final Collection<V> values, final C collection) {
            return collection.addAll(values);
        }
    }

    public ConcurrentMultiMapWithInitializer(final CollectionAdapter<K, V, C> collectionAdapter) {
        this.collectionAdapter = collectionAdapter;
    }

    private Object getLock(final K key) {
        final Object object = new Object();
        Object lock = keyToLock.putIfAbsent(key, object);
        if (lock == null) {
            lock = object;
        }
        return lock;
    }

    private C getValueHolder(final K key) {
        synchronized (getLock(key)) {
            C holder = map.get(key);
            if (holder == null) {
                map.put(key, holder = collectionAdapter.newCollection(key));
            }
            return holder;
        }
    }

    public boolean put(final K key, final V value) {
        return collectionAdapter.put(key, value, getValueHolder(key));
    }

    public boolean putAll(final K key, final Collection<V> values) {
        return collectionAdapter.putAll(key, values, getValueHolder(key));
    }

    public C remove(final K key) {
        synchronized (getLock(key)) {
            return map.remove(key);
        }
    }

    public C get(final K key) {
        return map.get(key);
    }

    public Set<K> getKeySet() {
        return map.keySet();
    }

    public int size() {
        return map.size();
    }
}
