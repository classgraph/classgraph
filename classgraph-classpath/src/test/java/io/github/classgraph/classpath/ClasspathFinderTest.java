package io.github.classgraph.classpath;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jspecify.annotations.Nullable;

import com.sun.net.httpserver.HttpServer;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;

/** Tests for the public API of the classpath finder. */
public class ClasspathFinderTest {
    /** The classpath of the JVM running the tests contains the directory the test classes were compiled to. */
    @Test
    public void theEnvironmentClasspathIsFound() {
        try (var classpath = new ClasspathFinder().enableClasspath().find()) {
            assertThat(classpath.getLocations()).anyMatch(location -> location.endsWith("/target/test-classes"));
        }
    }

    /**
     * Every entry records the classloader it was found through, and the package roots to look for within it. The
     * classloaders of the JVM running the tests load only from the classpath elements they were given, so none of
     * their entries has a package root to look for.
     */
    @Test
    public void entriesRecordTheirClassLoaderAndPackageRoots() {
        try (var classpath = new ClasspathFinder().enableClasspath().find()) {
            final var entries = classpath.getEntries();
            assertThat(entries).isNotEmpty();
            for (final ClasspathEntry entry : entries) {
                assertThat(entry.getLocation()).isNotEmpty();
                assertThat(entry.getPackageRootPrefixes()).isEmpty();
                assertThat(entry.toString()).startsWith(entry.getLocation());
            }
        }
    }

    /** An overridden classpath is reported verbatim, and nothing from the environment is added to it. */
    @Test
    public void anOverriddenClasspathIsUsedInsteadOfTheEnvironment(@TempDir final Path tempDir) throws IOException {
        final var first = writeJarWithManifest(tempDir.resolve("first.jar"));
        final var second = writeJarWithManifest(tempDir.resolve("second.jar"));
        try (var classpath = new ClasspathFinder().enableClasspathEntries(first + File.pathSeparator + second)
                .find()) {
            assertThat(classpath.getLocations()).containsExactly(location(first), location(second));
            // Modules are not scanned when the classpath is overridden
            assertThat(classpath.getModules()).isEmpty();
        }
    }

    /** Each classpath element of an overridden classpath is passed through unsplit by the non-String overloads. */
    @Test
    public void theClasspathCanBeOverriddenWithIndividualElements(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJarWithManifest(tempDir.resolve("only.jar"));
        final var expected = List.of(location(jar));
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) jar).find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries(List.of(jar)).find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
        // A single Path is one classpath entry, not a sequence of its name elements
        try (var classpath = new ClasspathFinder().enableClasspathEntries(jar.toPath()).find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
    }

    /**
     * A collection or array among the classpath elements is expanded into its own elements, to any depth, rather
     * than read by its {@code toString()}, which names no classpath element.
     */
    @Test
    public void nestedCollectionsAndArraysAreExpanded(@TempDir final Path tempDir) throws IOException {
        final var a = writeJarWithManifest(tempDir.resolve("a.jar"));
        final var b = writeJarWithManifest(tempDir.resolve("b.jar"));
        final var expected = List.of(location(a), location(b));
        try (var classpath = new ClasspathFinder().enableClasspathEntries(List.of(a), b).find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries(List.of(a), List.of(b)).find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries(new Object[] { new File[] { a } }, b)
                .find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries(List.of(List.of(a), b.toPath())).find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
        // An Iterable whose toString() names none of its elements, like a build tool's file collection
        final Iterable<File> files = () -> List.of(a).iterator();
        try (var classpath = new ClasspathFinder().enableClasspathEntries(files, b).find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
        // A String inside a collection is one classpath element, as it is outside one, and is not split
        final var c = writeJarWithManifest(tempDir.resolve("c" + File.pathSeparator + "d.jar"));
        try (var classpath = new ClasspathFinder().enableClasspathEntries(List.of(List.of(c.toString()))).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(c));
        }
    }

    /**
     * A collection that contains itself, directly or through another collection, contributes each classpath element
     * it contains, rather than overflowing the stack.
     */
    @Test
    public void aCollectionThatContainsItselfIsExpandedOnce(@TempDir final Path tempDir) throws IOException {
        final var a = writeJarWithManifest(tempDir.resolve("a.jar"));
        final var b = writeJarWithManifest(tempDir.resolve("b.jar"));
        final List<Object> self = new ArrayList<>();
        self.add(self);
        self.add(a);
        try (var classpath = new ClasspathFinder().enableClasspathEntries(self).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(a));
        }
        final List<Object> first = new ArrayList<>();
        final List<Object> second = new ArrayList<>();
        first.add(second);
        first.add(a);
        second.add(first);
        second.add(b);
        try (var classpath = new ClasspathFinder().enableClasspathEntries(first).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(b), location(a));
        }
    }

    /**
     * An {@link Iterable} that is not a collection and yields itself, as an Ant {@code Resource} does, is one
     * classpath element, read by its {@code toString()}, rather than a collection that holds nothing.
     */
    @Test
    public void anIterableThatYieldsItselfIsOneClasspathElement(@TempDir final Path tempDir) throws IOException {
        final var a = writeJarWithManifest(tempDir.resolve("a.jar"));
        final var b = writeJarWithManifest(tempDir.resolve("b.jar"));
        final var expected = List.of(location(a), location(b));
        try (var classpath = new ClasspathFinder().enableClasspathEntries(new SelfIterable(a)).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(a));
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries(new SelfIterable(a), b).find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
        // Like an Ant Path, which yields one Resource per classpath element
        try (var classpath = new ClasspathFinder()
                .enableClasspathEntries(List.of(new SelfIterable(a), new SelfIterable(b))).find()) {
            assertThat(classpath.getLocations()).isEqualTo(expected);
        }
    }

    /**
     * An {@link Iterable} that yields only itself, as an Ant {@code Resource} does, and whose {@code toString()} is
     * the path of a file.
     *
     * @param file
     *            the file.
     */
    private record SelfIterable(File file) implements Iterable<Object> {
        @Override
        public Iterator<Object> iterator() {
            return List.<Object> of(this).iterator();
        }

        @Override
        public String toString() {
            return file.toString();
        }
    }

    /**
     * The classpath entry {@code "*"} adds the jarfiles in the current directory even when {@code user.dir} cannot
     * be read, which leaves the current directory path empty. This has to run in a child JVM, since the current
     * directory is read only once per JVM.
     *
     * @param tempDir
     *            the directory to run the child JVM in.
     * @throws Exception
     *             if the jarfile could not be written, or the child JVM could not be run.
     */
    @Test
    public void aWildcardFindsTheJarfilesInTheCurrentDirectoryWhenUserDirCannotBeRead(@TempDir final Path tempDir)
            throws Exception {
        final var jar = writeJarWithManifest(tempDir.resolve("a.jar"));
        final var command = List.of(
                ProcessHandle.current().info().command()
                        .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                "-cp", System.getProperty("java.class.path"), CurrentDirWildcardPrinter.class.getName());
        final var process = new ProcessBuilder(command).directory(tempDir.toFile()).redirectErrorStream(true)
                .start();
        final String output;
        try (var inputStream = process.getInputStream()) {
            output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(process.waitFor()).as("Child JVM output:%n%s", output).isZero();
        assertThat(output.lines()).as("Child JVM output:%n%s", output).containsExactly(location(jar));
    }

    /** An empty classpath override is a caller error, rather than a silent scan of nothing. */
    @Test
    public void anEmptyClasspathOverrideIsRejected() {
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspathEntries(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspathEntries(new Object[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspathEntries(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        // Nested collections and arrays that hold no classpath element are empty too
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspathEntries(List.of(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspathEntries(new Object[] { new File[0] }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClasspathFinder().enableClassLoaders())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A classpath handed over as a single string is split when the classpath is found, not when it is handed over,
     * so a URL scheme registered afterwards still keeps its own {@code ':'} from being read as a path separator.
     */
    @Test
    public void aURLSchemeCanBeRegisteredAfterTheClasspathThatNamesIt() {
        final var location = "s3://bucket/widget.jar";
        try (var classpath = new ClasspathFinder().enableClasspathEntries(location).allowURLScheme("s3").find()) {
            assertThat(classpath.getLocations()).containsExactly(location);
        }
    }

    /**
     * Ignoring the parent classloaders says which classloaders are searched, so it is refused when no classloader
     * is searched, rather than being silently ignored.
     */
    @Test
    public void ignoringTheParentClassLoadersNeedsAClassLoaderToBeSearched() {
        final var message = "ClasspathFinder#ignoreParentClassLoaders() has no effect unless "
                + "ClasspathFinder#enableClasspath() or ClasspathFinder#enableClassLoaders() is also called";
        assertThatThrownBy(() -> new ClasspathFinder().ignoreParentClassLoaders().find())
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(
                () -> new ClasspathFinder().enableClasspathEntries("a.jar").ignoreParentClassLoaders().find())
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        try (var classpath = new ClasspathFinder().enableClassLoaders(ClasspathFinderTest.class.getClassLoader())
                .ignoreParentClassLoaders().find()) {
            assertThat(classpath.getLocations()).isNotEmpty();
        }
    }

    /**
     * A registered classloader handler is only offered the classloaders that are searched, so registering one is
     * refused when no classloader is searched, rather than being silently ignored. Searching only the modules, or
     * only named classpath entries, searches no classloader.
     *
     * @param tempDir
     *            a temporary directory, which is the classpath element of the classloader that is searched.
     * @throws IOException
     *             if the classloader could not be closed.
     */
    @Test
    public void registeringAClassLoaderHandlerNeedsAClassLoaderToBeSearched(@TempDir final Path tempDir)
            throws IOException {
        final var message = "ClasspathFinder#registerClassLoaderHandler() has no effect unless "
                + "ClasspathFinder#enableClasspath() or ClasspathFinder#enableClassLoaders() is also called";
        final var handler = libDirHandler("BOOT-INF/lib/");
        assertThatThrownBy(() -> new ClasspathFinder().registerClassLoaderHandler(handler).find())
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(
                () -> new ClasspathFinder().enableSystemModules().registerClassLoaderHandler(handler).find())
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspathEntries((Object) tempDir.toFile())
                .registerClassLoaderHandler(handler).find()).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        try (var classLoader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() }, /* parent = */ null);
                var classpath = new ClasspathFinder().enableClassLoaders(classLoader)
                        .registerClassLoaderHandler(handler).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(tempDir.toFile()));
        }
    }

    /** Realtime logging says when the log is written, so it is refused when there is no log. */
    @Test
    public void realtimeLoggingNeedsVerbose() {
        assertThatThrownBy(
                () -> new ClasspathFinder().enableRealtimeLogging().enableClasspathEntries("a.jar").find())
                .isInstanceOf(IllegalArgumentException.class).hasMessage(
                        "ClasspathFinder#enableRealtimeLogging() has no effect unless ClasspathFinder#verbose() "
                                + "is also called");
    }

    /**
     * With realtime logging, each log entry is written as soon as it is made, as well as in the log written once
     * the classpath has been found. Without it, only that last log is written.
     */
    @Test
    public void realtimeLoggingWritesEachEntryAsItIsMade(@TempDir final Path dir) {
        final var messages = new ArrayList<String>();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord logRecord) {
                messages.add(logRecord.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        final var logger = Logger.getLogger("io.github.classgraph.ClassGraph");
        final var useParentHandlers = logger.getUseParentHandlers();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            new ClasspathFinder().verbose().enableClasspathEntries(dir.toString()).find().close();
            assertThat(messages).singleElement().asString().contains("ClassGraph version");
            messages.clear();
            new ClasspathFinder().verbose().enableRealtimeLogging().enableClasspathEntries(dir.toString()).find()
                    .close();
            assertThat(messages).hasSizeGreaterThan(1);
            assertThat(messages.getFirst()).contains("ClassGraph version");
        } finally {
            logger.setUseParentHandlers(useParentHandlers);
            logger.removeHandler(handler);
        }
    }

    /** A classloader is passed to {@code enableClassLoaders}, not to {@code enableClasspathEntries}. */
    @Test
    public void aClassLoaderIsNotAClasspathElement() {
        final var classLoader = ClasspathFinderTest.class.getClassLoader();
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspathEntries((Object) classLoader))
                .isInstanceOf(IllegalArgumentException.class);
        // Nor is a classloader inside a collection or array
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspathEntries(List.of(List.of(classLoader))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> new ClasspathFinder().enableClasspathEntries((Object) new ClassLoader[] { classLoader }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The modules the JVM can see are found, and split into the JDK's own modules and everything else. */
    @Test
    public void theModulesAreFoundAndSplitIntoSystemAndNonSystem() {
        final var classpath = new ClasspathFinder().enableSystemModules().enableNonSystemModules().find();
        assertThat(classpath.getSystemModules()).anyMatch(module -> "java.base".equals(module.descriptor().name()));
        assertThat(classpath.getNonSystemModules())
                .noneMatch(module -> module.descriptor().name().startsWith("java."));
        // getModules() lists the system modules first, then the rest
        assertThat(classpath.getModules()).startsWith(classpath.getSystemModules().get(0));
        assertThat(classpath.getModules())
                .hasSize(classpath.getSystemModules().size() + classpath.getNonSystemModules().size());
    }

    /** Only the kind of module that was enabled is listed. */
    @Test
    public void theSystemModulesAreNotListedUnlessEnabled() {
        final var classpath = new ClasspathFinder().enableNonSystemModules().find();
        assertThat(classpath.getSystemModules()).isEmpty();
        assertThat(classpath.getModules()).containsExactlyElementsOf(classpath.getNonSystemModules());
    }

    /**
     * The non-system modules are not listed unless they are enabled, even though the module finder lists them
     * either way, so that a scan can read a superclass from a module it does not scan.
     */
    @Test
    public void theNonSystemModulesAreNotListedUnlessEnabled(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJarWithEntry(tempDir.resolve("nonsystem-module.jar"), "nonsystem/module/A.class");
        final var finder = java.lang.module.ModuleFinder.of(jar.toPath());
        final var bootLayer = ModuleLayer.boot();
        final var layer = bootLayer.defineModulesWithOneLoader(bootLayer.configuration().resolve(finder,
                java.lang.module.ModuleFinder.of(), Set.of("nonsystem.module")),
                ClassLoader.getSystemClassLoader());
        try (var classpath = new ClasspathFinder().enableModuleLayers(layer).enableSystemModules().find()) {
            assertThat(classpath.getSystemModules()).isNotEmpty();
            assertThat(classpath.getNonSystemModules()).isEmpty();
            assertThat(classpath.getModules()).containsExactlyElementsOf(classpath.getSystemModules());
        }
        try (var classpath = new ClasspathFinder().enableModuleLayers(layer).enableNonSystemModules().find()) {
            assertThat(classpath.getNonSystemModules())
                    .anyMatch(module -> "nonsystem.module".equals(module.descriptor().name()));
        }
    }

    /**
     * The classes on the traditional classpath are in the unnamed module, which is not in any module layer, so
     * asking for the non-system modules does not find the classpath, even though the caller is on the classpath.
     */
    @Test
    public void theNonSystemModulesDoNotIncludeTheClasspath() {
        try (var classpath = new ClasspathFinder().enableNonSystemModules().find()) {
            assertThat(classpath.getEntries()).isEmpty();
        }
    }

    /**
     * Asking for the non-system modules adds no classpath entries, so it does not bring back the entries of a
     * parent classloader that {@link ClasspathFinder#ignoreParentClassLoaders()} left out.
     */
    @Test
    public void theNonSystemModulesDoNotAddTheEntriesOfAnIgnoredParentClassLoader(@TempDir final Path dir)
            throws IOException {
        try (var child = new URLClassLoader(new URL[] { dir.toUri().toURL() }, ClassLoader.getSystemClassLoader());
                var classpath = new ClasspathFinder().enableClassLoaders(child).ignoreParentClassLoaders()
                        .enableNonSystemModules().find()) {
            assertThat(classpath.getLocations()).containsExactly(location(dir));
        }
    }

    /** Modules are not looked for unless a module source is enabled. */
    @Test
    public void modulesAreNotFoundUnlessEnabled() {
        assertThat(new ClasspathFinder().enableClasspath().find().getModules()).isEmpty();
    }

    /**
     * The module layers the caller names replace the ones that are visible from the caller, so asking for the
     * system modules of the named layers does not search the boot layer as well. An empty {@link ModuleLayer} has
     * no modules and no parents, so any module found below came from a layer that the caller did not name.
     */
    @Test
    public void namedModuleLayersReplaceTheDetectedOnes() {
        assertThat(new ClasspathFinder().enableModuleLayers(ModuleLayer.empty()).enableSystemModules()
                .enableNonSystemModules().find().getModules()).isEmpty();
    }

    /** Naming a module layer and asking for the detected layers as well searches both. */
    @Test
    public void theDetectedModuleLayersCanBeSearchedAlongsideANamedOne() {
        assertThat(new ClasspathFinder().enableModuleLayers(ModuleLayer.empty()).enableDetectedModuleLayers()
                .enableSystemModules().enableNonSystemModules().find().getModules())
                .anyMatch(module -> "java.base".equals(module.descriptor().name()));
    }

    /**
     * Ignoring the parent module layers leaves out the boot layer when a layer below it is searched. The child
     * layer here resolves no modules of its own, so every module found without the option came from the boot layer.
     */
    @Test
    public void ignoringTheParentModuleLayersLeavesOutTheBootLayer() {
        final var bootLayer = ModuleLayer.boot();
        final var childLayer = bootLayer.defineModulesWithOneLoader(bootLayer.configuration()
                .resolve(java.lang.module.ModuleFinder.of(), java.lang.module.ModuleFinder.of(), Set.of()),
                ClassLoader.getSystemClassLoader());
        try (var classpath = new ClasspathFinder().enableModuleLayers(childLayer).enableSystemModules()
                .enableNonSystemModules().find()) {
            assertThat(classpath.getModules()).anyMatch(module -> "java.base".equals(module.descriptor().name()));
        }
        try (var classpath = new ClasspathFinder().enableModuleLayers(childLayer).enableSystemModules()
                .enableNonSystemModules().ignoreParentModuleLayers().find()) {
            assertThat(classpath.getModules()).isEmpty();
        }
    }

    /**
     * Naming the module layers, or asking for the detected ones, says which layers are searched, so each is refused
     * when no modules are searched, rather than being silently ignored.
     */
    @Test
    public void theModuleLayerOptionsNeedAKindOfModule() {
        final var message = " has no effect unless ClasspathFinder#enableSystemModules() or "
                + "ClasspathFinder#enableNonSystemModules() is also called";
        assertThatThrownBy(
                () -> new ClasspathFinder().enableClasspath().enableModuleLayers(ModuleLayer.boot()).find())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ClasspathFinder#enableModuleLayers()" + message);
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspath().enableDetectedModuleLayers().find())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ClasspathFinder#enableDetectedModuleLayers()" + message);
    }

    /**
     * Ignoring the parent module layers says which layers are searched, so it is refused when no modules are
     * searched, rather than being silently ignored.
     */
    @Test
    public void ignoringTheParentModuleLayersNeedsModulesToBeSearched() {
        assertThatThrownBy(() -> new ClasspathFinder().enableClasspath().ignoreParentModuleLayers().find())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ClasspathFinder#ignoreParentModuleLayers() has no effect unless "
                        + "ClasspathFinder#enableSystemModules() or ClasspathFinder#enableNonSystemModules() "
                        + "is also called");
    }

    /**
     * The module path switches the JVM was launched with are reachable from the result, as the one instance that
     * describes this JVM.
     */
    @Test
    public void theModulePathInfoIsReachable() {
        try (var classpath = new ClasspathFinder().enableClasspath().find()) {
            assertThat(classpath.getModulePathInfo()).isSameAs(ModulePathInfo.current());
        }
    }

    /**
     * The jarfiles on the classpath are read through a {@link io.github.classgraph.vfs.Vfs} that the result hands
     * out, so that a classpath element can be read without opening it a second time, and closing the result closes
     * that virtual filesystem.
     */
    @Test
    public void theVfsTheClasspathWasReadThroughIsReachable(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJarWithEntry(tempDir.resolve("lib.jar"), "com/xyz/Widget.class");
        final Vfs vfs;
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) jar).find()) {
            vfs = classpath.getVfs();
            final var location = classpath.getLocations().get(0);
            final var root = vfs.open(location);
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly("com/xyz/Widget.class");
            // Opening the same location again hands back the root that is already open, rather than reading the
            // jarfile a second time
            assertThat(vfs.open(location)).isSameAs(root);
        }
        // Closing the Classpath closed the Vfs it was read through
        assertThatThrownBy(() -> vfs.open(location(jar))).isInstanceOf(IOException.class);
    }

    /**
     * The {@link io.github.classgraph.vfs.Vfs} of a {@link Classpath} keeps the settings the finder had when
     * {@link ClasspathFinder#find()} was called, so changing the finder afterward only changes the next
     * {@link Classpath} it finds.
     */
    @Test
    public void changingTheFinderAfterFindDoesNotChangeTheClasspathItFound(@TempDir final Path tempDir)
            throws IOException {
        final var jar = writeJarWithManifest(tempDir.resolve("only.jar"));
        final var finder = new ClasspathFinder().enableClasspathEntries((Object) jar);
        try (var classpath = finder.find()) {
            finder.allowURLScheme("https").denyURLScheme("s3");
            assertThat(classpath.getVfs().getVfsSpec().getDeniedURLSchemes()).containsExactly("ftp", "http",
                    "https", "mailto");
            try (var nextClasspath = finder.find()) {
                assertThat(nextClasspath.getVfs().getVfsSpec().getDeniedURLSchemes()).containsExactly("ftp", "http",
                        "mailto", "s3");
            }
        }
    }

    /** The result prints one classpath element or module per line. */
    @Test
    public void theClassPathPrintsOneEntryPerLine(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJarWithManifest(tempDir.resolve("only.jar"));
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) jar).find()) {
            assertThat(classpath).hasToString(location(jar) + "\n");
        }
    }

    /**
     * Write a jarfile that contains nothing but a manifest, with the given main attributes.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param attributeNamesAndValues
     *            the names and values of the main attributes, in alternating order.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static File writeJarWithManifest(final Path jarFile, final String... attributeNamesAndValues)
            throws IOException {
        final var manifest = new Manifest();
        final var mainAttributes = manifest.getMainAttributes();
        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (var i = 0; i < attributeNamesAndValues.length; i += 2) {
            mainAttributes.putValue(attributeNamesAndValues[i], attributeNamesAndValues[i + 1]);
        }
        try (var outputStream = Files.newOutputStream(jarFile)) {
            // No entries -- the manifest is the whole jar, so all that is left is to write the central
            // directory. finish() does that without closing outputStream, which the try does.
            new JarOutputStream(outputStream, manifest).finish();
        }
        return jarFile.toFile();
    }

    /**
     * Write a jarfile that contains nothing but an empty entry at the given path.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryPath
     *            the path of the entry.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static File writeJarWithEntry(final Path jarFile, final String entryPath) throws IOException {
        Files.createDirectories(jarFile.getParent());
        try (var outputStream = Files.newOutputStream(jarFile);
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(entryPath));
            zipOutputStream.closeEntry();
        }
        return jarFile.toFile();
    }

    /**
     * The classpath elements named by a jarfile's {@code Class-Path} manifest entry are part of the classpath, and
     * so are the ones that those in turn name. Each of them takes its position directly after the jarfile that
     * named it.
     */
    @Test
    public void manifestClassPathEntriesAreAddedToTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var last = writeJarWithManifest(tempDir.resolve("last.jar"));
        final var middle = writeJarWithManifest(tempDir.resolve("middle.jar"), "Class-Path", "last.jar");
        final var first = writeJarWithManifest(tempDir.resolve("first.jar"), "Class-Path", "middle.jar");
        final var other = writeJarWithManifest(tempDir.resolve("other.jar"));
        try (var classpath = new ClasspathFinder().enableClasspathEntries(first, other).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(first), location(middle), location(last),
                    location(other));
        }
    }

    /**
     * A classpath element is reported under the path it is stored at, with any symbolic link in the path resolved,
     * and so are the classpath elements its manifest names. This is what makes the same jarfile reached directly
     * and through a symbolic link one classpath element rather than two.
     */
    @Test
    public void aJarReachedThroughASymlinkIsReportedUnderThePathItIsStoredAt(@TempDir final Path tempDir)
            throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("real"));
        final var named = writeJarWithManifest(dir.resolve("named.jar"));
        final var namesAnother = writeJarWithManifest(dir.resolve("names-another.jar"), "Class-Path", "named.jar");
        final Path linkedDir;
        try {
            linkedDir = Files.createSymbolicLink(tempDir.resolve("link"), dir);
        } catch (IOException | UnsupportedOperationException e) {
            // Creating a symlink needs a privilege that is not granted by default on Windows
            abort("Symlinks cannot be created: " + e);
            return;
        }
        final var namesAnotherViaLink = linkedDir.resolve("names-another.jar").toFile();
        try (var classpath = new ClasspathFinder().enableClasspathEntries(namesAnotherViaLink, namesAnother)
                .find()) {
            // The jarfile reached through the symlink and the same jarfile reached directly are one element, and the
            // jarfile named by its manifest is reported the same way
            assertThat(classpath.getLocations()).containsExactly(location(namesAnother), location(named));
        }
    }

    /**
     * A classpath element that a manifest names through a symbolic link is reported under the path it is stored at,
     * so a jarfile reached directly and through a manifest entry that goes through a symbolic link is one classpath
     * element rather than two.
     */
    @Test
    public void aJarNamedByAManifestThroughASymlinkIsOneElement(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("real"));
        final var shared = writeJarWithManifest(dir.resolve("shared.jar"));
        final var namesShared = writeJarWithManifest(dir.resolve("names-shared.jar"), "Class-Path",
                "../link/shared.jar");
        try {
            Files.createSymbolicLink(tempDir.resolve("link"), dir);
        } catch (IOException | UnsupportedOperationException e) {
            // Creating a symlink needs a privilege that is not granted by default on Windows
            abort("Symlinks cannot be created: " + e);
            return;
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries(shared, namesShared).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(shared), location(namesShared));
        }
    }

    /**
     * On a filesystem that ignores case, a classpath element named with a different case is the same file, so it is
     * one classpath element rather than two, and it is reported with the case it is stored with rather than the
     * case it was asked for.
     */
    @Test
    public void aJarNamedWithADifferentCaseIsOneElementOnACaseFoldingFilesystem(@TempDir final Path tempDir)
            throws IOException {
        final var jar = writeJarWithManifest(tempDir.resolve("MixedCase.jar"));
        final var lowercased = tempDir.resolve("mixedcase.jar").toFile();
        if (!lowercased.exists()) {
            abort("The filesystem does not ignore case");
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries(lowercased, jar).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(jar));
            // The name the file is stored with is reported, not the name it was asked for
            assertThat(classpath.getLocations().get(0)).endsWith("/MixedCase.jar");
        }
    }

    /**
     * A classpath element that is reached more than once is listed only at the first position it is reached at,
     * which is the position that decides which copy of a duplicated class is loaded. Here the jarfile that names it
     * comes first, so it is reached through that jarfile's manifest before it is reached directly.
     */
    @Test
    public void aClasspathElementReachedTwiceKeepsItsFirstPosition(@TempDir final Path tempDir) throws IOException {
        final var shared = writeJarWithManifest(tempDir.resolve("shared.jar"));
        final var namesShared = writeJarWithManifest(tempDir.resolve("names-shared.jar"), "Class-Path",
                "shared.jar");
        final var last = writeJarWithManifest(tempDir.resolve("last.jar"));
        try (var classpath = new ClasspathFinder().enableClasspathEntries(namesShared, last, shared).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(namesShared), location(shared),
                    location(last));
        }
    }

    /** A jarfile does not name itself as a classpath element, however it refers to itself in its manifest. */
    @Test
    public void aJarThatNamesItselfDoesNotLoop(@TempDir final Path tempDir) throws IOException {
        final var self = writeJarWithManifest(tempDir.resolve("self.jar"), "Class-Path", "self.jar");
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) self).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(self));
        }
    }

    /**
     * The classpath elements named by an OSGi bundle jar's {@code Bundle-ClassPath} manifest entry are part of the
     * classpath. Those paths are relative to the root of the bundle jar, so they are reported in the nested form.
     */
    @Test
    public void bundleClassPathEntriesAreAddedToTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var bundle = writeJarWithManifest(tempDir.resolve("bundle.jar"), "Bundle-ClassPath",
                ".,embedded.jar");
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) bundle).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(bundle),
                    location(bundle) + "!/embedded.jar");
        }
    }

    /**
     * The jarfiles in the lib dirs a classloader declares are part of the classpath, whether the classpath element
     * that contains them is a jarfile or a directory, since the classloader that declared the lib dir does not list
     * them as classpath elements of their own.
     */
    @Test
    public void libDirJarsAreAddedToTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("exploded"));
        final var libJar = writeJarWithEntry(dir.resolve("BOOT-INF").resolve("lib").resolve("in-lib-dir.jar"),
                "resource.txt");
        final var classLoader = new URLClassLoader(new URL[] { dir.toUri().toURL() }, /* parent = */ null);
        try (var classpath = new ClasspathFinder().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(libDirHandler("BOOT-INF/lib/")).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(dir.toFile()), location(libJar));
        }
    }

    /**
     * A classpath element that no classloader was involved in finding has no lib dirs, since a lib dir is only
     * searched because the classloader that declared it loads from the jarfiles it holds. An overridden classpath
     * is therefore reported without the jarfiles in any directory within it, whatever that directory is called.
     */
    @Test
    public void libDirJarsAreNotAddedToAnOverriddenClasspath(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("exploded"));
        writeJarWithEntry(dir.resolve("BOOT-INF").resolve("lib").resolve("in-lib-dir.jar"), "resource.txt");
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) dir.toFile()).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(dir.toFile()));
        }
    }

    /**
     * The jarfiles of the classpath element filter tests: {@code a.jar} names {@code b.jar}, which names
     * {@code c.jar}, and {@code wild/w2.jar} names {@code c.jar} too, while {@code wild/w1.jar} names nothing. The
     * classpath is {@code a.jar} followed by the wildcard {@code wild/*}.
     *
     * @param a
     *            a.jar.
     * @param b
     *            b.jar.
     * @param c
     *            c.jar.
     * @param w1
     *            wild/w1.jar.
     * @param w2
     *            wild/w2.jar.
     * @param wildcard
     *            the wildcarded directory, {@code wild/*}.
     */
    private record FilterFixture(File a, File b, File c, File w1, File w2, String wildcard) {
        /**
         * Write the jarfiles.
         *
         * @param dir
         *            the directory to write them in.
         * @return the fixture.
         * @throws IOException
         *             if a jarfile could not be written.
         */
        static FilterFixture write(final Path dir) throws IOException {
            final var wildDir = Files.createDirectory(dir.resolve("wild"));
            return new FilterFixture(writeJarWithManifest(dir.resolve("a.jar"), "Class-Path", "b.jar"),
                    writeJarWithManifest(dir.resolve("b.jar"), "Class-Path", "c.jar"),
                    writeJarWithManifest(dir.resolve("c.jar")), writeJarWithManifest(wildDir.resolve("w1.jar")),
                    writeJarWithManifest(wildDir.resolve("w2.jar"), "Class-Path", "../c.jar"), wildDir + "/*");
        }

        /**
         * Find the classpath with a filter, and return the locations found.
         *
         * @param filter
         *            the filter.
         * @return the locations.
         */
        List<String> find(final Predicate<? super ClasspathEntry> filter) {
            try (var classpath = new ClasspathFinder().enableClasspathEntries(a, wildcard)
                    .filterClasspathEntries(filter).find()) {
                return classpath.getLocations();
            }
        }
    }

    /**
     * A classpath element that the filter rejects is not reported and not opened, so the classpath elements that
     * only it declares are not found either. A classpath element that an accepted element also declares is still
     * found.
     */
    @Test
    public void aRejectedClasspathElementIsNotOpened(@TempDir final Path tempDir) throws IOException {
        final var f = FilterFixture.write(tempDir);
        final var seen = new ArrayList<String>();
        assertThat(f.find(entry -> {
            seen.add(entry.getLocation());
            return !entry.getLocation().equals(location(f.a()));
        })).containsExactlyInAnyOrder(location(f.w1()), location(f.w2()), location(f.c()));
        // b.jar is only declared by a.jar, which was never opened, so the filter never saw it
        assertThat(seen).doesNotContain(location(f.b()));
    }

    /** The filter is applied to the classpath elements that a manifest's {@code Class-Path} entry declares. */
    @Test
    public void theFilterSeesManifestClassPathEntries(@TempDir final Path tempDir) throws IOException {
        final var f = FilterFixture.write(tempDir);
        assertThat(f.find(entry -> !entry.getLocation().equals(location(f.c()))))
                .containsExactlyInAnyOrder(location(f.a()), location(f.b()), location(f.w1()), location(f.w2()));
    }

    /** The filter is applied to each jarfile that a wildcarded directory is expanded into. */
    @Test
    public void theFilterSeesTheJarfilesInAWildcardedDirectory(@TempDir final Path tempDir) throws IOException {
        final var f = FilterFixture.write(tempDir);
        // w2.jar is not opened, so c.jar is reached only through a.jar and b.jar
        assertThat(f.find(entry -> !entry.getLocation().equals(location(f.w2()))))
                .containsExactlyInAnyOrder(location(f.a()), location(f.b()), location(f.c()), location(f.w1()));
    }

    /**
     * The filter is applied to the jarfiles in a lib dir that a classloader handler declares.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a jarfile could not be written, or the classloader could not be closed.
     */
    @Test
    public void theFilterSeesLibDirJars(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("exploded"));
        final var libDir = dir.resolve("BOOT-INF").resolve("lib");
        final var kept = writeJarWithEntry(libDir.resolve("kept.jar"), "resource.txt");
        final var rejected = writeJarWithEntry(libDir.resolve("rejected.jar"), "resource.txt");
        try (var classLoader = new URLClassLoader(new URL[] { dir.toUri().toURL() }, /* parent = */ null);
                var classpath = new ClasspathFinder().enableClassLoaders(classLoader)
                        .registerClassLoaderHandler(libDirHandler("BOOT-INF/lib/"))
                        .filterClasspathEntries(entry -> !entry.getLocation().equals(location(rejected))).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(dir.toFile()), location(kept));
        }
    }

    /**
     * The filter is given the location a classpath element is reported under, which is the path it is stored at, so
     * a filter written against that location rejects the classpath element even when it was named through a
     * symbolic link.
     */
    @Test
    public void theFilterSeesTheLocationAClasspathElementIsStoredAt(@TempDir final Path tempDir)
            throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("real"));
        final var rejected = writeJarWithManifest(dir.resolve("rejected.jar"));
        final var kept = writeJarWithManifest(dir.resolve("kept.jar"));
        final Path linkedDir;
        try {
            linkedDir = Files.createSymbolicLink(tempDir.resolve("link"), dir);
        } catch (IOException | UnsupportedOperationException e) {
            // Creating a symlink needs a privilege that is not granted by default on Windows
            abort("Symlinks cannot be created: " + e);
            return;
        }
        try (var classpath = new ClasspathFinder()
                .enableClasspathEntries(linkedDir.resolve("rejected.jar").toFile(), kept)
                .filterClasspathEntries(entry -> !entry.getLocation().equals(location(rejected))).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(kept));
        }
    }

    /** A classpath element is reported only if it passes every filter. */
    @Test
    public void aClasspathElementMustPassEveryFilter(@TempDir final Path tempDir) throws IOException {
        final var f = FilterFixture.write(tempDir);
        try (var classpath = new ClasspathFinder().enableClasspathEntries(f.c(), f.w1(), f.w2())
                .filterClasspathEntries(entry -> !entry.getLocation().equals(location(f.c())))
                .filterClasspathEntries(entry -> !entry.getLocation().equals(location(f.w1()))).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(f.w2()));
        }
    }

    /**
     * A null filter is refused, and a filter is refused when no classpath element is searched, rather than being
     * silently ignored.
     */
    @Test
    public void aFilterNeedsAClasspathSource() {
        assertThatThrownBy(() -> new ClasspathFinder().filterClasspathEntries(null))
                .isInstanceOf(NullPointerException.class);
        final var message = "ClasspathFinder#filterClasspathEntries() has no effect unless "
                + "ClasspathFinder#enableClasspath() or ClasspathFinder#enableClassLoaders() or "
                + "ClasspathFinder#enableClasspathEntries() is also called";
        assertThatThrownBy(() -> new ClasspathFinder().filterClasspathEntries(_ -> true).find())
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(
                () -> new ClasspathFinder().enableSystemModules().filterClasspathEntries(_ -> true).find())
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    /**
     * The jarfiles in a lib dir of a jarfile are nested jarfiles, so they are left out once nested jarfiles are
     * disabled, and only the jarfile that contains them is reported.
     */
    @Test
    public void libDirJarsWithinAJarAreNotAddedWhenNestedJarsAreDisabled(@TempDir final Path tempDir)
            throws IOException {
        final var innerJar = writeJarWithEntry(tempDir.resolve("inner.jar"), "resource.txt");
        final var outerJar = tempDir.resolve("outer.jar").toFile();
        try (var outputStream = Files.newOutputStream(outerJar.toPath());
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry("lib/inner.jar"));
            zipOutputStream.write(Files.readAllBytes(innerJar.toPath()));
            zipOutputStream.closeEntry();
        }
        final var classLoader = new URLClassLoader(new URL[] { outerJar.toURI().toURL() }, /* parent = */ null);
        try (var classpath = new ClasspathFinder().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(libDirHandler("lib/")).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(outerJar),
                    location(outerJar) + "!/lib/inner.jar");
        }
        try (var classpath = new ClasspathFinder().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(libDirHandler("lib/")).disableNestedJarScanning().find()) {
            assertThat(classpath.getLocations()).containsExactly(location(outerJar));
        }
    }

    /**
     * Disabling multi-release versions makes the virtual filesystem that the result hands out report each version
     * of a resource under the path it is stored under.
     */
    @Test
    public void multiReleaseVersionsCanBeDisabled(@TempDir final Path tempDir) throws IOException {
        final var jar = tempDir.resolve("multi-release.jar").toFile();
        final var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var outputStream = Files.newOutputStream(jar.toPath());
                var jarOutputStream = new JarOutputStream(outputStream, manifest)) {
            jarOutputStream.putNextEntry(new ZipEntry("resource.txt"));
            jarOutputStream.closeEntry();
            jarOutputStream.putNextEntry(new ZipEntry("META-INF/versions/9/resource.txt"));
            jarOutputStream.closeEntry();
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) jar).find()) {
            assertThat(classpath.getVfs().open(location(jar)).getEntries()).extracting(VfsEntry::getPathFromRoot)
                    .contains("resource.txt").doesNotContain("META-INF/versions/9/resource.txt");
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) jar)
                .disableMultiReleaseVersions().find()) {
            assertThat(classpath.getVfs().open(location(jar)).getEntries()).extracting(VfsEntry::getPathFromRoot)
                    .contains("resource.txt", "META-INF/versions/9/resource.txt");
        }
    }

    /**
     * The limit on how much of a jarfile is held in RAM is passed to the virtual filesystem the result hands out.
     */
    @Test
    public void theMaxBufferedJarRAMSizeIsPassedToTheVfs() {
        try (var classpath = new ClasspathFinder().enableClasspath().setMaxBufferedJarRAMSize(12345).find()) {
            assertThat(classpath.getVfs().getVfsSpec().getMaxBufferedJarRAMSize()).isEqualTo(12345);
        }
        assertThatThrownBy(() -> new ClasspathFinder().setMaxBufferedJarRAMSize(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A {@link ClassLoaderHandler} for {@link URLClassLoader} that declares the given lib dirs, so that a test can
     * exercise a lib dir without needing a real classloader that has one.
     *
     * @param libDirPrefixes
     *            the lib dir prefixes to declare.
     * @return the handler.
     */
    private static ClassLoaderHandler libDirHandler(final String... libDirPrefixes) {
        return new ClassLoaderHandler() {
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
                for (final URL url : ((URLClassLoader) classLoader).getURLs()) {
                    classpathOrder.addClasspathEntry(url, classLoader, log);
                }
            }

            @Override
            public List<String> getLibDirPrefixes() {
                return List.of(libDirPrefixes);
            }
        };
    }

    /**
     * A classpath element that is there but is not a jarfile is still reported, since whether it can be opened is
     * only found out when the scan tries to open it.
     */
    @Test
    public void aClasspathElementThatCannotBeOpenedIsStillReported(@TempDir final Path tempDir) throws IOException {
        final var notAJar = Files.writeString(tempDir.resolve("not-a-jar.jar"), "this is not a zipfile").toFile();
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) notAJar).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(notAJar));
        }
    }

    /**
     * A classpath element that the filesystem says is not there is skipped when it is found, rather than added and
     * then failed on during the scan, since it can contribute no class.
     */
    @Test
    public void aClasspathElementThatIsNotThereIsSkipped(@TempDir final Path tempDir) throws IOException {
        final var present = writeJarWithManifest(tempDir.resolve("present.jar"));
        final var missing = tempDir.resolve("missing.jar").toFile();
        try (var classpath = new ClasspathFinder().enableClasspathEntries(present, missing).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(present));
        }
    }

    /** A classpath element that a manifest names, and that the filesystem says is not there, is skipped too. */
    @Test
    public void aClasspathElementThatAManifestNamesAndIsNotThereIsSkipped(@TempDir final Path tempDir)
            throws IOException {
        final var namesMissing = writeJarWithManifest(tempDir.resolve("names-missing.jar"), "Class-Path",
                "missing.jar");
        try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) namesMissing).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(namesMissing));
        }
    }

    /**
     * A classpath element that cannot be read is skipped when it is found, since it too can contribute no class.
     */
    @Test
    public void aClasspathElementThatCannotBeReadIsSkipped(@TempDir final Path tempDir) throws IOException {
        final var present = writeJarWithManifest(tempDir.resolve("present.jar"));
        final var unreadable = writeJarWithManifest(tempDir.resolve("unreadable.jar"));
        try {
            Files.setPosixFilePermissions(unreadable.toPath(), Set.of());
        } catch (IOException | UnsupportedOperationException e) {
            // Windows filesystems have no POSIX permissions
            abort("Permissions cannot be cleared: " + e);
            return;
        }
        if (unreadable.canRead()) {
            // The superuser can read a file whatever its permissions say
            abort("A file with no permissions can still be read");
            return;
        }
        try (var classpath = new ClasspathFinder().enableClasspathEntries(present, unreadable).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(present));
        }
    }

    /**
     * Serve the given bytes over HTTP on the loopback interface, so that a jarfile can be reached by URL without
     * touching the network.
     *
     * @param path
     *            the path to serve the bytes at, e.g. {@code "/lib.jar"}.
     * @param body
     *            the bytes to serve.
     * @return the server, which the caller must stop.
     * @throws IOException
     *             if the server could not be started.
     */
    private static HttpServer serve(final String path, final byte[] body) throws IOException {
        final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                /* backlog = */ 1);
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        server.start();
        return server;
    }

    /**
     * A classpath element named by a URL is only read if its scheme has been allowed. It is reported either way,
     * but until the scheme is allowed the jarfile it names is not fetched, so the elements it declares are not
     * found.
     *
     * @param tempDir
     *            a temporary directory to build the jarfile in.
     * @throws IOException
     *             if the jarfile could not be built, or the server could not be started.
     */
    @Test
    public void aUrlClasspathElementIsOnlyReadIfItsSchemeIsAllowed(@TempDir final Path tempDir) throws IOException {
        final var jarBytes = Files.readAllBytes(
                writeJarWithManifest(tempDir.resolve("served.jar"), "Class-Path", "declared.jar").toPath());
        final var server = serve("/served.jar", jarBytes);
        try {
            final var jarURL = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                    + "/served.jar";
            final var declaredURL = jarURL.replace("served.jar", "declared.jar");

            // The scheme has not been allowed, so the jarfile is not fetched and its manifest is not read
            try (var classpath = new ClasspathFinder().enableClasspathEntries((Object) jarURL).find()) {
                assertThat(classpath.getLocations()).containsExactly(jarURL);
            }

            // With the scheme allowed, the jarfile is fetched, and the element its manifest declares is found too
            try (var classpath = new ClasspathFinder().allowURLScheme("http")
                    .enableClasspathEntries((Object) jarURL).find()) {
                assertThat(classpath.getLocations()).containsExactly(jarURL, declaredURL);
            }

            // Denying the scheme again stops the jarfile from being fetched
            try (var classpath = new ClasspathFinder().allowURLScheme("http").denyURLScheme("http")
                    .enableClasspathEntries((Object) jarURL).find()) {
                assertThat(classpath.getLocations()).containsExactly(jarURL);
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * A {@code Class-Path} manifest entry of a jarfile in the filesystem that is an {@code http:} URL is ignored,
     * as the JVM's own classloader ignores it, so it is neither reported nor fetched, even when the {@code http}
     * scheme has been allowed for the classpath elements named directly.
     *
     * @param tempDir
     *            a temporary directory to build the jarfile in.
     * @throws IOException
     *             if the jarfile could not be built, or the server could not be started.
     */
    @Test
    public void aClassPathManifestEntryURLOfALocalJarIsNotFetched(@TempDir final Path tempDir) throws IOException {
        final var requests = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                /* backlog = */ 1);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            final var remoteURL = "http://" + server.getAddress().getHostString() + ":"
                    + server.getAddress().getPort() + "/remote.jar";
            final var local = writeJarWithManifest(tempDir.resolve("local.jar"), "Class-Path",
                    remoteURL + " declared.jar");
            final var declared = writeJarWithManifest(tempDir.resolve("declared.jar"));
            try (var classpath = new ClasspathFinder().allowURLScheme("http").enableClasspathEntries((Object) local)
                    .find()) {
                assertThat(classpath.getLocations()).containsExactly(location(local), location(declared));
            }
            assertThat(requests.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    /**
     * A jarfile fetched from a URL can only name classpath elements relative to that URL in its {@code Class-Path}
     * manifest attribute, as for the JVM's own classloader, so it cannot name a file of the local filesystem,
     * either by a {@code file:} URL or by an absolute path.
     *
     * @param tempDir
     *            a temporary directory to build the jarfiles in.
     * @throws IOException
     *             if the jarfiles could not be built, or the server could not be started.
     */
    @Test
    public void aJarFetchedFromAURLCannotNameALocalFile(@TempDir final Path tempDir) throws IOException {
        final var local = writeJarWithManifest(tempDir.resolve("local.jar"));
        final var jarBytes = Files.readAllBytes(writeJarWithManifest(tempDir.resolve("served.jar"), "Class-Path",
                "declared.jar " + local.toURI() + " " + local.toURI().getPath()).toPath());
        final var server = serve("/served.jar", jarBytes);
        try {
            final var jarURL = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                    + "/served.jar";
            try (var classpath = new ClasspathFinder().allowURLScheme("http")
                    .enableClasspathEntries((Object) jarURL).find()) {
                assertThat(classpath.getLocations()).containsExactly(jarURL,
                        jarURL.replace("served.jar", "declared.jar"));
            }
        } finally {
            server.stop(0);
        }
    }

    /** Closing the result more than once has no further effect. */
    @Test
    public void theClassPathCanBeClosedTwice(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJarWithManifest(tempDir.resolve("closed-twice.jar"));
        final var classpath = new ClasspathFinder().enableClasspathEntries((Object) jar).find();
        classpath.close();
        classpath.close();
        // The classpath elements can still be read after the jarfiles have been closed
        assertThat(classpath.getLocations()).containsExactly(location(jar));
    }
}
