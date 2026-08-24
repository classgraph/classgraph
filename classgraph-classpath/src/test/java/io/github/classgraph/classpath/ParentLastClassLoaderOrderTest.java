package io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.classgraph.base.ClassGraphLog;
import org.jspecify.annotations.Nullable;

/**
 * Test that a classloader that delegates to its parent last has its own classpath elements ordered before its
 * parent's, even when the parent is also one of the classloaders found in the environment.
 */
public class ParentLastClassLoaderOrderTest {
    /** The name of the temporary directory that only the child classloader can see. */
    private static final String CHILD_ONLY_DIR_PREFIX = "classGraphChildOnly";

    /** A classloader that loads {@link Driver} itself rather than delegating to its parent. */
    public static class ParentLastLoader extends URLClassLoader {
        /**
         * Constructor.
         *
         * @param urls
         *            The URLs to load classes from.
         * @param parent
         *            The parent classloader.
         */
        public ParentLastLoader(final URL[] urls, final ClassLoader parent) {
            super("parentLast", urls, parent);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!name.equals(Driver.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                var cls = findLoadedClass(name);
                if (cls == null) {
                    cls = findClass(name);
                }
                if (resolve) {
                    resolveClass(cls);
                }
                return cls;
            }
        }
    }

    /** A {@link ClassLoaderHandler} that places a {@link ParentLastLoader} before its parent. */
    public static class ParentLastHandler implements ClassLoaderHandler {
        @Override
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return ParentLastLoader.class.getName().equals(classLoaderClass.getName());
        }

        @Override
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderOrder.add(classLoader, log);
            classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            for (final URL url : ((URLClassLoader) classLoader).getURLs()) {
                classpathOrder.addClasspathEntry(url.toString(), classLoader, log);
            }
        }
    }

    /**
     * Runs {@link ClasspathFinder} from within the child classloader, and returns the located classpath element
     * paths. Loaded by {@link ParentLastLoader} itself, so the classloader of the caller of ClassGraph is the child
     * classloader, while the thread context classloader is still the parent.
     */
    public static class Driver implements Supplier<List<String>> {
        @Override
        public List<String> get() {
            final List<String> locations = new ArrayList<>();
            try (var classpath = new ClasspathFinder().registerClassLoaderHandler(new ParentLastHandler())
                    .enableClasspath().find()) {
                for (final var classpathElement : classpath) {
                    locations.add(classpathElement.getLocation());
                }
            }
            return locations;
        }
    }

    /**
     * A parent-last classloader's own classpath elements are ordered before its parent's, even though the parent is
     * the thread context classloader.
     *
     * @throws Exception
     *             If the child classloader could not be set up.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void parentLastClassLoaderIsOrderedBeforeItsParent() throws Exception {
        final var childOnlyDir = Files.createTempDirectory(CHILD_ONLY_DIR_PREFIX);
        final var testClasses = ParentLastClassLoaderOrderTest.class.getProtectionDomain().getCodeSource()
                .getLocation();
        final List<String> locations;
        try (var child = new ParentLastLoader(new URL[] { childOnlyDir.toUri().toURL(), testClasses },
                ParentLastClassLoaderOrderTest.class.getClassLoader())) {
            final var driverClass = child.loadClass(Driver.class.getName());
            assertThat(driverClass.getClassLoader()).isSameAs(child);
            locations = ((Supplier<List<String>>) driverClass.getDeclaredConstructor().newInstance()).get();
        }

        // The child classloader is the only classloader that can see this directory, and its handler adds the
        // child before delegating to the parent, so the directory must be the first classpath element of all
        assertThat(locations).isNotEmpty();
        assertThat(locations.get(0)).contains(CHILD_ONLY_DIR_PREFIX);
    }
}
