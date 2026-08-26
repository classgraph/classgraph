package io.github.classgraph.classpath.internal;

import java.util.function.Supplier;

/**
 * Reads the call stack from a class that a test classloader loaded, so that the classloader appears in the call
 * stack as the classloader of an outer frame.
 */
public class CallStackProbe implements Supplier<Object> {
    /** Constructor. */
    public CallStackProbe() {
    }

    /**
     * Read the call stack.
     *
     * @return the {@link CallStackInfo}, as an {@link Object}, since the caller loaded this class reflectively.
     */
    @Override
    public Object get() {
        return CallStackInfo.read();
    }
}
