package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.ArrayList;

/**
 * Recycle instances of type T. The method T#close() is called when this class' own close() method is called. Use
 * RuntimeException for type E if the newInstance() method does not throw an exception.
 */
public abstract class Recycler<T extends AutoCloseable> extends KeyLocker implements AutoCloseable {
    /** Instances that have been allocated. */
    public ArrayList<T> allocatedInstances = new ArrayList<>();

    /** Instances that have been allocated but are unused. */
    public ArrayList<T> unusedInstances = new ArrayList<>();

    /** Acquire or allocate an instance. */
    public T acquire() {
        synchronized (unusedInstances) {
            if (!unusedInstances.isEmpty()) {
                final T instance = unusedInstances.get(unusedInstances.size() - 1);
                unusedInstances.remove(unusedInstances.size() - 1);
                return instance;
            }
        }
        final T instance = newInstance();
        if (instance != null) {
            synchronized (allocatedInstances) {
                allocatedInstances.add(instance);
            }
        }
        return instance;
    }

    /** Release/recycle an instance. */
    public void release(final T instance) {
        if (instance != null) {
            synchronized (unusedInstances) {
                unusedInstances.add(instance);
            }
        }
    }

    /** Call this only after all instances have been released. */
    @Override
    public void close() {
        synchronized (allocatedInstances) {
            synchronized (unusedInstances) {
                final int unreleasedInstances = allocatedInstances.size() - unusedInstances.size();
                if (unreleasedInstances != 0) {
                    throw new RuntimeException("Unreleased instances: " + unreleasedInstances);
                }
                for (final T instance : allocatedInstances) {
                    try {
                        instance.close();
                    } catch (final Exception e) {
                        // Ignore
                    }
                }
                allocatedInstances.clear();
            }
        }
    }

    public abstract T newInstance();
}
