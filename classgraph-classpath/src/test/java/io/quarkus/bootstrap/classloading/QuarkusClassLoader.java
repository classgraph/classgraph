package io.quarkus.bootstrap.classloading;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A stand-in for Quarkus' {@code io.quarkus.bootstrap.classloading.QuarkusClassLoader}, which serves an application
 * from a list of classpath elements. Quarkus 3.16 split that single list into a normal-priority and a
 * lesser-priority list, so this stand-in can report its elements either way.
 *
 * <p>
 * This class must be in this exact package, with this exact name, because {@code QuarkusClassLoaderHandler}
 * dispatches on the fully-qualified classloader class name.
 */
public class QuarkusClassLoader extends ClassLoader {
    /** The classpath elements, or null if this classloader reports them by priority instead. */
    public @Nullable Collection<Object> elements;

    /** The normal-priority classpath elements, or null if this classloader does not report them. */
    public @Nullable Collection<Object> normalPriorityElements;

    /** The lesser-priority classpath elements, or null if this classloader does not report them. */
    public @Nullable Collection<Object> lesserPriorityElements;

    /** Constructor. */
    public QuarkusClassLoader() {
        super(/* parent = */ null);
    }

    /**
     * Serve the application from a single list of classpath elements, the way Quarkus did before 3.16.
     *
     * @param classpathElements
     *            the classpath elements.
     * @return this, for chaining.
     */
    public QuarkusClassLoader serving(final Object... classpathElements) {
        elements = List.of(classpathElements);
        return this;
    }

    /**
     * Serve the application from a normal-priority and a lesser-priority list of classpath elements, the way
     * Quarkus has since 3.16.
     *
     * @param normalPriority
     *            the normal-priority classpath elements.
     * @param lesserPriority
     *            the lesser-priority classpath elements.
     * @return this, for chaining.
     */
    public QuarkusClassLoader servingByPriority(final Collection<Object> normalPriority,
            final Collection<Object> lesserPriority) {
        normalPriorityElements = new ArrayList<>(normalPriority);
        lesserPriorityElements = new ArrayList<>(lesserPriority);
        return this;
    }
}
