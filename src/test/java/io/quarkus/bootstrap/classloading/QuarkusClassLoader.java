package io.quarkus.bootstrap.classloading;

import java.util.List;

/**
 * A stand-in for the {@code io.quarkus.bootstrap.classloading.QuarkusClassLoader} of Quarkus 3.16 and later, used
 * to test that {@code QuarkusClassLoaderHandler} skips null elements.
 *
 * <p>
 * This class must be in this exact package, with this exact name, because {@code QuarkusClassLoaderHandler}
 * dispatches on the fully-qualified classloader class name.
 */
public class QuarkusClassLoader extends ClassLoader {
    /** The classpath elements, read by {@code QuarkusClassLoaderHandler}. */
    @SuppressWarnings("unused")
    private final List<?> normalPriorityElements;

    /**
     * Constructor.
     *
     * @param normalPriorityElements
     *            the classpath elements.
     */
    public QuarkusClassLoader(final List<?> normalPriorityElements) {
        super(QuarkusClassLoader.class.getClassLoader());
        this.normalPriorityElements = normalPriorityElements;
    }
}
