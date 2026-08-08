package io.quarkus.bootstrap.runner;

/**
 * A stand-in for Quarkus'
 * {@code io.quarkus.bootstrap.runner.RunnerClassLoader}, used to test that
 * {@code QuarkusClassLoaderHandler} degrades gracefully when the fields it
 * reads by reflection are not present (Quarkus has renamed such fields between
 * releases).
 *
 * <p>
 * This class must be in this exact package, with this exact name, because
 * {@code QuarkusClassLoaderHandler} dispatches on the fully-qualified
 * classloader class name.
 */
public class RunnerClassLoader extends ClassLoader {
    /** Constructor. */
    public RunnerClassLoader() {
        super(RunnerClassLoader.class.getClassLoader());
    }
}
