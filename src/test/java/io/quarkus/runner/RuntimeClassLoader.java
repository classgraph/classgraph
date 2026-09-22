package io.quarkus.runner;

import java.util.List;

/**
 * A stand-in for the {@code io.quarkus.runner.RuntimeClassLoader} of Quarkus 1.2 and earlier, used to test that
 * {@code QuarkusClassLoaderHandler} skips elements of the wrong type.
 *
 * <p>
 * This class must be in this exact package, with this exact name, because {@code QuarkusClassLoaderHandler}
 * dispatches on the fully-qualified classloader class name.
 */
public class RuntimeClassLoader extends ClassLoader {
    /** The application class directories, read by {@code QuarkusClassLoaderHandler}. */
    @SuppressWarnings("unused")
    private final List<?> applicationClassDirectories;

    /**
     * Constructor.
     *
     * @param applicationClassDirectories
     *            the application class directories.
     */
    public RuntimeClassLoader(final List<?> applicationClassDirectories) {
        super(RuntimeClassLoader.class.getClassLoader());
        this.applicationClassDirectories = applicationClassDirectories;
    }
}
