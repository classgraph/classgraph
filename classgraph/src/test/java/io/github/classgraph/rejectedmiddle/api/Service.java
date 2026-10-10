package io.github.classgraph.rejectedmiddle.api;

/**
 * An interface that an accepted class implements only through a superclass in a rejected package.
 */
public interface Service {
    /**
     * A default method, which the implementing classes inherit.
     */
    default void start() {
    }
}
