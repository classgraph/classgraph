package io.quarkus.bootstrap.runner;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A stand-in for Quarkus' {@code io.quarkus.bootstrap.runner.RunnerClassLoader}, which serves an application
 * packaged as a fast jar: every resource directory in the application maps to the resources that serve it.
 *
 * <p>
 * This class must be in this exact package, with this exact name, because {@code QuarkusClassLoaderHandler}
 * dispatches on the fully-qualified classloader class name.
 */
public class RunnerClassLoader extends ClassLoader {
    /** The resources that serve each resource directory, or null if this classloader does not report them. */
    public @Nullable Map<String, Object[]> resourceDirectoryMap;

    /** Constructor. */
    public RunnerClassLoader() {
        super(/* parent = */ null);
    }

    /**
     * Record the resources that serve a resource directory.
     *
     * @param resourceDirectory
     *            the resource directory.
     * @param resources
     *            the resources that serve it.
     * @return this, for chaining.
     */
    public RunnerClassLoader serving(final String resourceDirectory, final Object... resources) {
        if (resourceDirectoryMap == null) {
            resourceDirectoryMap = new LinkedHashMap<>();
        }
        resourceDirectoryMap.put(resourceDirectory, resources);
        return this;
    }
}
