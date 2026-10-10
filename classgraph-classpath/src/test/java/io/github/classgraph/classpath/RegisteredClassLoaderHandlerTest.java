package io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            this(classpathEntry, null);
        }

        /**
         * Constructor.
         *
         * @param classpathEntry
         *            the one classpath entry this classloader loads from.
         * @param parent
         *            the parent classloader, or null for the bootstrap classloader.
         */
        UnknownClassLoader(final String classpathEntry, final @Nullable ClassLoader parent) {
            super(parent);
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
        try (var classpath = classpathFinder.find()) {
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

        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader))).doesNotContain(classesDir);

        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(new UnknownClassLoaderHandler()))).containsExactly(classesDir);
    }

    /**
     * A handler for {@link UnknownClassLoader} that declares the given package root and lib dir prefixes.
     *
     * @param packageRootPrefixes
     *            the package root prefixes to declare.
     * @param libDirPrefixes
     *            the lib dir prefixes to declare.
     * @return the handler.
     */
    private static ClassLoaderHandler handlerWithPrefixes(final List<String> packageRootPrefixes,
            final List<String> libDirPrefixes) {
        return new UnknownClassLoaderHandler() {
            @Override
            public List<String> getPackageRootPrefixes() {
                return packageRootPrefixes;
            }

            @Override
            public List<String> getLibDirPrefixes() {
                return libDirPrefixes;
            }
        };
    }

    /**
     * A prefix a handler declares is taken in the form ClassGraph uses, a relative path ending in a slash, however
     * the handler spells it. So a classpath entry that names the package root inside a classpath element is still
     * recognized as that package root, and the package root is stripped from it, rather than the entry being
     * dropped.
     *
     * @param prefix
     *            a spelling of the package root prefix {@code "classes/"}.
     */
    @ParameterizedTest
    @ValueSource(strings = { "classes/", "classes", "/classes/", "classes//", "\\classes\\", "//classes" })
    public void aPrefixIsTakenInCanonicalForm(final String prefix, @TempDir final Path tempDir) throws Exception {
        final var dir = classesDir(tempDir, "d");
        Files.createDirectories(dir.resolve("classes/pkg"));
        final var handler = handlerWithPrefixes(List.of(prefix), List.of(prefix.replace("classes", "lib")));

        try (var classpath = new ClasspathFinder().enableClassLoaders(new UnknownClassLoader(dir + "!/classes"))
                .registerClassLoaderHandler(handler).find()) {
            assertThat(classpath.getLocations().stream().map(Path::of)).containsExactly(dir);
            assertThat(classpath.getEntries().get(0).getPackageRootPrefixes()).containsExactly("classes/");
            assertThat(classpath.getEntries().get(0).getLibDirPrefixes()).containsExactly("lib/");
        }
    }

    /**
     * A prefix that names no dir at all, the empty string or a path made only of slashes, is dropped. It is not the
     * root of the classpath element: that is scanned anyway, and as a lib dir it would add every jarfile at the top
     * level of the classpath element.
     *
     * @param prefix
     *            a prefix that names no dir.
     */
    @ParameterizedTest
    @ValueSource(strings = { "", "/", "//", "\\" })
    public void aPrefixThatNamesNoDirIsDropped(final String prefix, @TempDir final Path tempDir) throws Exception {
        final var dir = classesDir(tempDir, "d");
        final var handler = handlerWithPrefixes(List.of(prefix), List.of(prefix));

        try (var classpath = new ClasspathFinder().enableClassLoaders(new UnknownClassLoader(dir.toString()))
                .registerClassLoaderHandler(handler).find()) {
            assertThat(classpath.getLocations().stream().map(Path::of)).containsExactly(dir);
            assertThat(classpath.getEntries().get(0).getPackageRootPrefixes()).isEmpty();
            assertThat(classpath.getEntries().get(0).getLibDirPrefixes()).isEmpty();
        }
    }

    /**
     * The prefixes a handler hands over are copied when its classpath entries are found, so each classpath entry
     * keeps the prefixes that were declared for it, even when the handler hands over one list that it refills for
     * each classloader.
     */
    @Test
    public void aRefilledPrefixListDoesNotChangeTheEntriesAlreadyFound(@TempDir final Path tempDir)
            throws Exception {
        final var dirA = classesDir(tempDir, "a");
        final var dirB = classesDir(tempDir, "b");
        final List<String> packageRootPrefixes = new ArrayList<>();
        final List<String> libDirPrefixes = new ArrayList<>();
        final var handler = new UnknownClassLoaderHandler() {
            /** The number of times the package root prefixes have been asked for. */
            private int packageRootCalls;

            /** The number of times the lib dir prefixes have been asked for. */
            private int libDirCalls;

            @Override
            public List<String> getPackageRootPrefixes() {
                packageRootPrefixes.clear();
                packageRootPrefixes.add("p" + packageRootCalls++ + "/");
                return packageRootPrefixes;
            }

            @Override
            public List<String> getLibDirPrefixes() {
                libDirPrefixes.clear();
                libDirPrefixes.add("l" + libDirCalls++ + "/");
                return libDirPrefixes;
            }
        };

        try (var classpath = new ClasspathFinder().enableClassLoaders(new UnknownClassLoader(dirA.toString()),
                new UnknownClassLoader(dirB.toString())).registerClassLoaderHandler(handler).find()) {
            assertThat(classpath.getLocations().stream().map(Path::of)).containsExactly(dirA, dirB);
            assertThat(classpath.getEntries().get(0).getPackageRootPrefixes()).containsExactly("p0/");
            assertThat(classpath.getEntries().get(0).getLibDirPrefixes()).containsExactly("l0/");
            assertThat(classpath.getEntries().get(1).getPackageRootPrefixes()).containsExactly("p1/");
            assertThat(classpath.getEntries().get(1).getLibDirPrefixes()).containsExactly("l1/");
        }
    }

    /**
     * A handler may pass the list of URLs it reads from its classloader to
     * {@link ClasspathOrder#addClasspathEntry(Object, ClassLoader, ClassGraphLog)} as it is, and each URL is found.
     * The string form of the list used to be added as a single path instead, which named no file, so nothing was
     * found.
     */
    @Test
    public void aHandlerMayAddAListOfClasspathEntries(@TempDir final Path tempDir) throws Exception {
        final var dirA = classesDir(tempDir, "a");
        final var dirB = classesDir(tempDir, "b");
        final var handler = new UnknownClassLoaderHandler() {
            @Override
            public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                    final @Nullable ClassGraphLog log) {
                try {
                    classpathOrder.addClasspathEntry(List.of(dirA.toUri().toURL(), dirB.toUri().toURL()),
                            classLoader, log);
                } catch (final MalformedURLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        assertThat(find(new ClasspathFinder().enableClassLoaders(new UnknownClassLoader(dirA.toString()))
                .registerClassLoaderHandler(handler))).containsExactly(dirA, dirB);
    }

    /**
     * A handler that delegates to the parent but never places its own classloader with
     * {@link ClassLoaderOrder#add(ClassLoader, ClassGraphLog)} must not make that classloader's classpath entries
     * vanish. The classloader is placed after the classloaders it delegated to.
     */
    @Test
    public void aClassLoaderThatItsHandlerNeverAddsIsStillRead(@TempDir final Path tempDir) throws Exception {
        final var parentDir = classesDir(tempDir, "parent");
        final var childDir = classesDir(tempDir, "child");
        final var parent = new URLClassLoader(new URL[] { parentDir.toUri().toURL() }, /* parent = */ null);
        final var child = new UnknownClassLoader(childDir.toString(), parent);
        final var handler = new UnknownClassLoaderHandler() {
            @Override
            public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                    final @Nullable ClassGraphLog log) {
                classLoaderOrder.delegateToParent(classLoader.getParent(), log);
            }
        };

        assertThat(find(new ClasspathFinder().enableClassLoaders(child).registerClassLoaderHandler(handler)))
                .containsExactly(parentDir, childDir);
    }

    /**
     * A handler builds its prefix lists itself, so the lists it hands over are copied rather than kept. Otherwise a
     * handler that changed a list after handing it over would silently change the prefixes of every classpath entry
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

        try (var classpath = new ClasspathFinder().enableClassLoaders(new UnknownClassLoader(classesDir.toString()))
                .registerClassLoaderHandler(handler).find()) {
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
        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader))).containsExactly(dirA, dirB);

        // The registered handler runs first, so its reversed order is the one that survives
        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(new ReversingURLClassLoaderHandler()))).containsExactly(dirB, dirA);
    }

    /**
     * When two handlers can handle the same classloader, only the one that handles the most specific classloader
     * class runs. A handler written for a subclass of {@link URLClassLoader} knows where that subclass really loads
     * from, so the built-in {@code URLClassLoaderHandler}, which does not, must not run alongside it.
     */
    @Test
    public void aMoreSpecificHandlerSuppressesAMoreGeneralOne(@TempDir final Path tempDir) throws Exception {
        final var urlDir = classesDir(tempDir, "url");
        final var handlerDir = classesDir(tempDir, "handler");
        final var classLoader = new SubURLClassLoader(new URL[] { urlDir.toUri().toURL() });

        // Without the registered handler, the built-in URLClassLoaderHandler reads the classloader's URLs
        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader))).containsExactly(urlDir);

        // With it, the built-in handler is suppressed, so the classloader's URLs are not read
        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(new FixedEntryHandler(SubURLClassLoader.class, handlerDir))))
                .containsExactly(handlerDir);
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

        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader)
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
        // URLClassLoader, but both were registered, so both run. The built-in URLClassLoaderHandler is the only
        // handler suppressed, so the classloader's own URLs are not in the result.
        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(new FixedEntryHandler(URLClassLoader.class, generalDir))
                .registerClassLoaderHandler(new FixedEntryHandler(SubURLClassLoader.class, specificDir))))
                .containsExactly(generalDir, specificDir);
    }

    /**
     * A {@link ClassLoaderHandler} for {@link UnknownClassLoader} that adds one fixed classpath entry of its own,
     * but only the first time it claims the key "system-bundles" in a scan.
     */
    private static class ClaimingHandler extends FixedEntryHandler {
        /**
         * Constructor.
         *
         * @param classpathEntry
         *            the one classpath entry this handler adds.
         */
        ClaimingHandler(final Path classpathEntry) {
            super(UnknownClassLoader.class, classpathEntry);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            if (classpathOrder.claimOncePerScan("system-bundles")) {
                super.findClasspathOrder(classLoader, classpathOrder, log);
            }
        }
    }

    /**
     * Two handlers of different classes that claim the same key each get their own claim, since a key only has to
     * be unique within the handler that claims it. Otherwise the first handler to run would silently stop the other
     * one from adding its classpath entries.
     */
    @Test
    public void twoHandlersMayClaimTheSameKey(@TempDir final Path tempDir) throws Exception {
        final var dirA = classesDir(tempDir, "a");
        final var dirB = classesDir(tempDir, "b");
        final var classLoader = new UnknownClassLoader(classesDir(tempDir, "unknown").toString());

        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(new ClaimingHandler(dirA) {
                    // A class of its own
                }).registerClassLoaderHandler(new ClaimingHandler(dirB) {
                    // A class of its own
                }))).containsExactly(dirA, dirB);
    }

    /**
     * A handler whose {@code canHandle} throws is treated as not handling the classloader, so the classloaders it
     * is offered are still read by the other handlers.
     */
    @Test
    public void aHandlerWhoseCanHandleThrowsIsSkipped(@TempDir final Path tempDir) throws Exception {
        final var urlDir = classesDir(tempDir, "url");
        final var classLoader = new URLClassLoader(new URL[] { urlDir.toUri().toURL() }, /* parent = */ null);
        final var handler = new UnknownClassLoaderHandler() {
            @Override
            public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
                throw new IllegalStateException("canHandle failed");
            }
        };

        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader).registerClassLoaderHandler(handler)))
                .containsExactly(urlDir);
    }

    /**
     * A handler whose {@code canHandle} throws for a superclass of the classloader, when the handlers that can
     * handle the classloader are ranked by how specific they are, is ranked as if it did not handle that
     * superclass.
     */
    @Test
    public void aHandlerWhoseCanHandleThrowsWhileRankingHandlersIsSkipped(@TempDir final Path tempDir)
            throws Exception {
        final var urlDir = classesDir(tempDir, "url");
        final var handlerDir = classesDir(tempDir, "handler");
        final var classLoader = new SubURLClassLoader(new URL[] { urlDir.toUri().toURL() });
        final var handler = new FixedEntryHandler(SubURLClassLoader.class, handlerDir) {
            @Override
            public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
                if (classLoaderClass == SubURLClassLoader.class) {
                    return true;
                }
                throw new IllegalStateException("canHandle failed");
            }
        };

        // The registered handler handles only SubURLClassLoader, so the built-in URLClassLoaderHandler is dropped
        assertThat(find(new ClasspathFinder().enableClassLoaders(classLoader).registerClassLoaderHandler(handler)))
                .containsExactly(handlerDir);
    }

    /**
     * A handler whose {@code findClassLoaderOrder} throws leaves the classloaders it had already delegated to in
     * place, its own classloader is placed after them, and every other classloader is still read.
     */
    @Test
    public void aHandlerWhoseFindClassLoaderOrderThrowsIsSkipped(@TempDir final Path tempDir) throws Exception {
        final var parentDir = classesDir(tempDir, "parent");
        final var childDir = classesDir(tempDir, "child");
        final var otherDir = classesDir(tempDir, "other");
        final var parent = new URLClassLoader(new URL[] { parentDir.toUri().toURL() }, /* parent = */ null);
        final var child = new UnknownClassLoader(childDir.toString(), parent);
        final var other = new URLClassLoader(new URL[] { otherDir.toUri().toURL() }, /* parent = */ null);
        final var handler = new UnknownClassLoaderHandler() {
            @Override
            public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                    final @Nullable ClassGraphLog log) {
                classLoaderOrder.delegateToParent(classLoader.getParent(), log);
                throw new NoClassDefFoundError("org/example/ContainerInternals");
            }
        };

        assertThat(find(new ClasspathFinder().enableClassLoaders(child, other).registerClassLoaderHandler(handler)))
                .containsExactly(parentDir, childDir, otherDir);
    }

    /**
     * A handler whose {@code findClasspathOrder} throws keeps the classpath entries it added before it threw, and
     * every other classloader is still read.
     */
    @Test
    public void aHandlerWhoseFindClasspathOrderThrowsIsSkipped(@TempDir final Path tempDir) throws Exception {
        final var unknownDir = classesDir(tempDir, "unknown");
        final var otherDir = classesDir(tempDir, "other");
        final var unknown = new UnknownClassLoader(unknownDir.toString());
        final var other = new URLClassLoader(new URL[] { otherDir.toUri().toURL() }, /* parent = */ null);
        final var handler = new UnknownClassLoaderHandler() {
            @Override
            public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                    final @Nullable ClassGraphLog log) {
                super.findClasspathOrder(classLoader, classpathOrder, log);
                throw new IllegalStateException("findClasspathOrder failed");
            }
        };

        assertThat(
                find(new ClasspathFinder().enableClassLoaders(unknown, other).registerClassLoaderHandler(handler)))
                .containsExactly(unknownDir, otherDir);
    }

    /**
     * A handler whose {@code getPackageRootPrefixes} throws adds nothing, and every other classloader is still
     * read.
     */
    @Test
    public void aHandlerWhosePrefixGetterThrowsIsSkipped(@TempDir final Path tempDir) throws Exception {
        final var unknownDir = classesDir(tempDir, "unknown");
        final var otherDir = classesDir(tempDir, "other");
        final var unknown = new UnknownClassLoader(unknownDir.toString());
        final var other = new URLClassLoader(new URL[] { otherDir.toUri().toURL() }, /* parent = */ null);
        final var handler = new UnknownClassLoaderHandler() {
            @Override
            public List<String> getPackageRootPrefixes() {
                throw new IllegalStateException("getPackageRootPrefixes failed");
            }
        };

        assertThat(
                find(new ClasspathFinder().enableClassLoaders(unknown, other).registerClassLoaderHandler(handler)))
                .containsExactly(otherDir);
    }
}
