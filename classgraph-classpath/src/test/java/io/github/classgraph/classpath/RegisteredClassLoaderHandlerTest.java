package io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;

/** Tests for {@link ClasspathFinder#registerClassLoaderHandler(ClassLoaderHandler)}. */
public class RegisteredClassLoaderHandlerTest {
    /** A classloader that no built-in {@link ClassLoaderHandler} knows about. */
    private static class UnknownClassLoader extends ClassLoader {
        /** The one classpath entry this classloader loads from. */
        final String classpathEntry;

        /**
         * Constructor.
         *
         * @param classpathEntry
         *            the one classpath entry this classloader loads from.
         */
        UnknownClassLoader(final String classpathEntry) {
            // Use the bootstrap classloader as the parent, so that the classpath of the classloader that loaded
            // this test does not end up in the result
            super(null);
            this.classpathEntry = classpathEntry;
        }
    }

    /** A {@link ClassLoaderHandler} for {@link UnknownClassLoader}. */
    private static class UnknownClassLoaderHandler implements ClassLoaderHandler {
        @Override
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return classIsOrExtendsOrImplements(classLoaderClass, UnknownClassLoader.class.getName());
        }

        @Override
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderOrder.add(classLoader, log);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            classpathOrder.addClasspathEntry(((UnknownClassLoader) classLoader).classpathEntry, classLoader, log);
        }
    }

    /**
     * A {@link ClassLoaderHandler} for {@link URLClassLoader}, which the built-in {@code URLClassLoaderHandler}
     * also handles. It adds its classpath entries in the reverse of the order the URLs are declared in, so that the
     * resulting order shows which of the two handlers placed them.
     */
    private static class ReversingURLClassLoaderHandler implements ClassLoaderHandler {
        @Override
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return classIsOrExtendsOrImplements(classLoaderClass, URLClassLoader.class.getName());
        }

        @Override
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderOrder.add(classLoader, log);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            final var urls = ((URLClassLoader) classLoader).getURLs();
            for (var i = urls.length - 1; i >= 0; i--) {
                classpathOrder.addClasspathEntry(urls[i], classLoader, log);
            }
        }
    }

    /**
     * Create a directory that can be used as a classpath entry.
     *
     * @param tempDir
     *            the directory to create it in.
     * @param name
     *            the name of the directory to create.
     * @return the created directory.
     * @throws Exception
     *             if the directory could not be created.
     */
    private static Path classesDir(final Path tempDir, final String name) throws Exception {
        return Files.createDirectory(tempDir.resolve(name)).toRealPath();
    }

    /**
     * The classpath entry locations found by a {@link ClasspathFinder}, as {@link Path}s, so that they compare
     * equal whichever way the classloader spelled them.
     *
     * @param classpathFinder
     *            the classpath finder to run.
     * @return the classpath entry paths, in classpath order.
     */
    private static List<Path> find(final ClasspathFinder classpathFinder) {
        try (var classpath = classpathFinder.ignoreModules().find()) {
            return classpath.getLocations().stream().map(Path::of).toList();
        }
    }

    /**
     * Without a registered handler, an unknown classloader is only read by the fallback handler, which cannot find
     * anything in it. With one registered, its classpath entry is found.
     */
    @Test
    public void registeredHandlerReadsAnOtherwiseUnreadableClassLoader(@TempDir final Path tempDir)
            throws Exception {
        final var classesDir = classesDir(tempDir, "classes");
        final var classLoader = new UnknownClassLoader(classesDir.toString());

        assertThat(find(new ClasspathFinder().overrideClassLoaders(classLoader))).doesNotContain(classesDir);

        assertThat(find(new ClasspathFinder().overrideClassLoaders(classLoader)
                .registerClassLoaderHandler(new UnknownClassLoaderHandler()))).containsExactly(classesDir);
    }

    /**
     * A registered handler is offered each classloader before the built-in handlers are, so when both can handle a
     * classloader, the registered handler is the one that decides where the classpath entries go.
     */
    @Test
    public void registeredHandlerOverridesABuiltInHandler(@TempDir final Path tempDir) throws Exception {
        final var dirA = classesDir(tempDir, "a");
        final var dirB = classesDir(tempDir, "b");
        final var classLoader = new URLClassLoader(new URL[] { dirA.toUri().toURL(), dirB.toUri().toURL() },
                /* parent = */ null);

        // The built-in URLClassLoaderHandler adds the URLs in declaration order
        assertThat(find(new ClasspathFinder().overrideClassLoaders(classLoader))).containsExactly(dirA, dirB);

        // The registered handler runs first, so its reversed order is the one that survives
        assertThat(find(new ClasspathFinder().overrideClassLoaders(classLoader)
                .registerClassLoaderHandler(new ReversingURLClassLoaderHandler()))).containsExactly(dirB, dirA);
    }
}
