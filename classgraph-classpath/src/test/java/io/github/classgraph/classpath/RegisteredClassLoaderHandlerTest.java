package io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
     * A subclass of {@link URLClassLoader}, so that a handler written for it is more specific than the built-in
     * handler for {@link URLClassLoader}.
     */
    private static class SubURLClassLoader extends URLClassLoader {
        /**
         * Constructor.
         *
         * @param urls
         *            the URLs this classloader loads from.
         */
        SubURLClassLoader(final URL[] urls) {
            // Use the bootstrap classloader as the parent, so that the classpath of the classloader that loaded
            // this test does not end up in the result
            super(urls, /* parent = */ null);
        }
    }

    /**
     * A {@link ClassLoaderHandler} for a given classloader class that adds one fixed classpath entry of its own, so
     * that the entries in the result show which handlers ran.
     */
    private static class FixedEntryHandler implements ClassLoaderHandler {
        /** The name of the classloader class this handler handles. */
        private final String classLoaderClassName;

        /** The one classpath entry this handler adds. */
        private final Path classpathEntry;

        /**
         * Constructor.
         *
         * @param classLoaderClass
         *            the classloader class this handler handles.
         * @param classpathEntry
         *            the one classpath entry this handler adds.
         */
        FixedEntryHandler(final Class<?> classLoaderClass, final Path classpathEntry) {
            this.classLoaderClassName = classLoaderClass.getName();
            this.classpathEntry = classpathEntry;
        }

        @Override
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return classIsOrExtendsOrImplements(classLoaderClass, classLoaderClassName);
        }

        @Override
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderOrder.add(classLoader, log);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            classpathOrder.addClasspathEntry(classpathEntry.toString(), classLoader, log);
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
        try (var classpath = classpathFinder.disableModuleScanning().find()) {
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
     * A handler builds its prefix lists itself, so the lists it hands over are copied rather than kept. Otherwise a
     * handler that refilled one list per classloader would silently change the prefixes of every classpath entry
     * already found, including the values their {@link ClasspathEntry#equals(Object)} and
     * {@link ClasspathEntry#hashCode()} are computed from.
     */
    @Test
    public void thePrefixListsAHandlerHandsOverAreCopied(@TempDir final Path tempDir) throws Exception {
        final var classesDir = classesDir(tempDir, "classes");
        final List<String> packageRootPrefixes = new ArrayList<>(List.of("BOOT-INF/classes/"));
        final List<String> libDirPrefixes = new ArrayList<>(List.of("BOOT-INF/lib/"));
        final var handler = new UnknownClassLoaderHandler() {
            @Override
            public List<String> getPackageRootPrefixes() {
                return packageRootPrefixes;
            }

            @Override
            public List<String> getLibDirPrefixes() {
                return libDirPrefixes;
            }
        };

        try (var classpath = new ClasspathFinder()
                .overrideClassLoaders(new UnknownClassLoader(classesDir.toString()))
                .registerClassLoaderHandler(handler).disableModuleScanning().find()) {
            final var entry = classpath.getEntries().get(0);
            packageRootPrefixes.add("surprise/classes/");
            libDirPrefixes.clear();

            assertThat(entry.getPackageRootPrefixes()).containsExactly("BOOT-INF/classes/");
            assertThat(entry.getLibDirPrefixes()).containsExactly("BOOT-INF/lib/");
            assertThatThrownBy(() -> entry.getPackageRootPrefixes().add("surprise/classes/"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
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

    /**
     * When two handlers can handle the same classloader, only the one that handles the most specific classloader
     * class runs, so a handler written for a subclass of {@link URLClassLoader}, which knows where that subclass
     * really loads from, decides where the classpath entries go. The URLs of the classloader are still read
     * afterwards, since they are the paths the classloader really loads from either way.
     */
    @Test
    public void aMoreSpecificHandlerPlacesTheClasspathEntriesFirst(@TempDir final Path tempDir) throws Exception {
        final var urlDir = classesDir(tempDir, "url");
        final var handlerDir = classesDir(tempDir, "handler");
        final var classLoader = new SubURLClassLoader(new URL[] { urlDir.toUri().toURL() });

        // Without the registered handler, the built-in URLClassLoaderHandler reads the classloader's URLs
        assertThat(find(new ClasspathFinder().overrideClassLoaders(classLoader))).containsExactly(urlDir);

        // With it, the registered handler's entry comes first, and the classloader's own URLs follow it
        assertThat(find(new ClasspathFinder().overrideClassLoaders(classLoader)
                .registerClassLoaderHandler(new FixedEntryHandler(SubURLClassLoader.class, handlerDir))))
                .containsExactly(handlerDir, urlDir);
    }

    /**
     * A handler for a subclass of {@link URLClassLoader} does not have to read the URLs of the classloader itself:
     * whatever handlers run for a {@link URLClassLoader}, its URLs are read unless one of them reads them already.
     * Otherwise naming a classloader class in {@code canHandle} would silently turn off the reading of the URLs of
     * every classloader of that class, which is a way to lose classpath entries without any sign that they were
     * there.
     */
    @Test
    public void theURLsOfAURLClassLoaderAreReadWhicheverHandlerRuns(@TempDir final Path tempDir) throws Exception {
        final var urlDir = classesDir(tempDir, "url");
        final var handlerDir = classesDir(tempDir, "handler");

        // A handler that adds no entries at all still leaves the classloader's URLs to be read
        final var readsNothing = new FixedEntryHandler(SubURLClassLoader.class, handlerDir) {
            @Override
            public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                    final @Nullable ClassGraphLog log) {
                // Add no classpath entries
            }
        };
        assertThat(find(new ClasspathFinder()
                .overrideClassLoaders(new SubURLClassLoader(new URL[] { urlDir.toUri().toURL() }))
                .registerClassLoaderHandler(readsNothing))).containsExactly(urlDir);

        // A classloader that is not a URLClassLoader has no URLs to read, so only the handler's entry is found
        assertThat(find(new ClasspathFinder().overrideClassLoaders(new UnknownClassLoader(urlDir.toString()))
                .registerClassLoaderHandler(new FixedEntryHandler(UnknownClassLoader.class, handlerDir))))
                .containsExactly(handlerDir);
    }

    /**
     * The URLs of a {@link URLClassLoader} are read once, not once per handler that ran for the classloader.
     */
    @Test
    public void theURLsOfAURLClassLoaderAreNotReadTwice(@TempDir final Path tempDir) throws Exception {
        final var dirA = classesDir(tempDir, "a");
        final var dirB = classesDir(tempDir, "b");
        final var classLoader = new URLClassLoader(new URL[] { dirA.toUri().toURL(), dirB.toUri().toURL() },
                /* parent = */ null);

        // The registered handler reverses the URLs, and the built-in handler is equally specific, so it also runs
        // and reads the URLs; if the URLs were then read a third time, the reversed order would not survive
        assertThat(find(new ClasspathFinder().overrideClassLoaders(classLoader)
                .registerClassLoaderHandler(new ReversingURLClassLoaderHandler()))).containsExactly(dirB, dirA);
    }

    /**
     * Two handlers that handle the same classloader class are equally specific, so neither suppresses the other and
     * both run, the registered handler first.
     */
    @Test
    public void equallySpecificHandlersAllRun(@TempDir final Path tempDir) throws Exception {
        final var urlDir = classesDir(tempDir, "url");
        final var handlerDir = classesDir(tempDir, "handler");
        final var classLoader = new URLClassLoader(new URL[] { urlDir.toUri().toURL() }, /* parent = */ null);

        assertThat(find(new ClasspathFinder().overrideClassLoaders(classLoader)
                .registerClassLoaderHandler(new FixedEntryHandler(URLClassLoader.class, handlerDir))))
                .containsExactly(handlerDir, urlDir);
    }

    /**
     * A registered handler is never suppressed by a more specific handler, since the caller registered it in order
     * to have it run. Only built-in handlers are suppressed.
     */
    @Test
    public void aRegisteredHandlerIsNeverSuppressed(@TempDir final Path tempDir) throws Exception {
        final var urlDir = classesDir(tempDir, "url");
        final var generalDir = classesDir(tempDir, "general");
        final var specificDir = classesDir(tempDir, "specific");
        final var classLoader = new SubURLClassLoader(new URL[] { urlDir.toUri().toURL() });

        // The registered handler for SubURLClassLoader is more specific than the registered handler for
        // URLClassLoader, but both were registered, so both run, followed by the reading of the classloader's own
        // URLs, which neither of them does
        assertThat(find(new ClasspathFinder().overrideClassLoaders(classLoader)
                .registerClassLoaderHandler(new FixedEntryHandler(URLClassLoader.class, generalDir))
                .registerClassLoaderHandler(new FixedEntryHandler(SubURLClassLoader.class, specificDir))))
                .containsExactly(generalDir, specificDir, urlDir);
    }
}
