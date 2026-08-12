package weblogic.utils.classloaders;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for the WebLogic {@code ChangeAwareClassLoader}, which reports its classpath as two path strings: the
 * classpath its class finder reads from, and the classpath it was configured with.
 */
public class ChangeAwareClassLoader extends ClassLoader {
    /** The classpath the class finder reads from, or null if the classloader does not report one. */
    private final @Nullable String finderClassPath;

    /** The classpath the classloader was configured with, or null if the classloader does not report one. */
    private final @Nullable String classPath;

    /**
     * Constructor.
     *
     * @param finderClassPath
     *            the classpath the class finder reads from, or null if the classloader does not report one.
     * @param classPath
     *            the classpath the classloader was configured with, or null if the classloader does not report one.
     */
    public ChangeAwareClassLoader(final @Nullable String finderClassPath, final @Nullable String classPath) {
        super(/* parent = */ null);
        this.finderClassPath = finderClassPath;
        this.classPath = classPath;
    }

    /**
     * The classpath the class finder reads from.
     *
     * @return the classpath, or null if the classloader does not report one.
     */
    public @Nullable String getFinderClassPath() {
        return finderClassPath;
    }

    /**
     * The classpath the classloader was configured with.
     *
     * @return the classpath, or null if the classloader does not report one.
     */
    public @Nullable String getClassPath() {
        return classPath;
    }
}
