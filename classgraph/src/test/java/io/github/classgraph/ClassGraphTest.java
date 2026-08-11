package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.utils.LogNode;

/**
 * {@link ClassGraph} is the entry point of the API: it builds up the scan specification, and then either runs a
 * scan or just reports the classpath.
 */
public class ClassGraphTest {
    /** The package that the fixture classes are in. */
    private static final String PACKAGE_NAME = "com.xyz.cgfixture";

    /** A directory classpath element, containing two classes and a resource. */
    private static Path classesDir;

    /** A jar classpath element, containing one class and a resource. */
    private static Path jarFile;

    /** A directory classpath element that contains only a marker resource. */
    private static Path markerDir;

    /**
     * Compile the fixture classes into a directory and a jar, and write a resource into each of them.
     *
     * @param tempDir
     *            the temporary directory to build the fixture in.
     * @throws IOException
     *             if the fixture could not be written.
     */
    @BeforeAll
    public static void buildFixture(@TempDir final Path tempDir) throws IOException {
        classesDir = Files.createDirectory(tempDir.resolve("classes"));
        compile(tempDir, classesDir, "InDir", "RejectMe");
        Files.createDirectory(classesDir.resolve("res"));
        Files.writeString(classesDir.resolve("res/indir.txt"), "in dir");

        final var jarContentDir = Files.createDirectory(tempDir.resolve("jarcontent"));
        compile(tempDir, jarContentDir, "InJar");
        Files.createDirectory(jarContentDir.resolve("res"));
        Files.writeString(jarContentDir.resolve("res/injar.txt"), "in jar");
        jarFile = tempDir.resolve("fixture.jar");
        zip(jarContentDir, jarFile);

        markerDir = Files.createDirectory(tempDir.resolve("marker"));
        Files.writeString(markerDir.resolve("marker.txt"), "marker");
    }

    /**
     * Compile one class per given name into the given output directory.
     *
     * @param tempDir
     *            the directory to write the sources into.
     * @param outputDir
     *            the directory to write the classfiles into.
     * @param classNames
     *            the simple names of the classes to compile.
     * @throws IOException
     *             if a source file could not be written.
     */
    private static void compile(final Path tempDir, final Path outputDir, final String... classNames)
            throws IOException {
        final var sourcePaths = new ArrayList<String>();
        for (final var className : classNames) {
            final var sourcePath = tempDir.resolve(className + ".java");
            Files.writeString(sourcePath, "package " + PACKAGE_NAME + ";\n\npublic class " + className + " {\n}\n");
            sourcePaths.add(sourcePath.toString());
        }
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        final var args = new ArrayList<>(List.of("-d", outputDir.toString()));
        args.addAll(sourcePaths);
        assertThat(compiler.run(null, null, null, args.toArray(new String[0]))).as("javac exit code").isZero();
    }

    /**
     * Zip up the content of a directory.
     *
     * @param contentDir
     *            the directory whose content should be zipped.
     * @param zipPath
     *            the zipfile to write.
     * @throws IOException
     *             if the zipfile could not be written.
     */
    private static void zip(final Path contentDir, final Path zipPath) throws IOException {
        try (var jarOut = new JarOutputStream(Files.newOutputStream(zipPath)); var paths = Files.walk(contentDir)) {
            for (final var path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                jarOut.putNextEntry(
                        new JarEntry(contentDir.relativize(path).toString().replace(File.separatorChar, '/')));
                Files.copy(path, jarOut);
                jarOut.closeEntry();
            }
        }
    }

    /**
     * Run an action with a log handler attached to ClassGraph's logger, and return the messages it logged. The
     * logger's own handlers are disabled for the duration, so that the verbose log is not written to stderr.
     *
     * @param action
     *            the action to run.
     * @return the messages logged while the action ran.
     */
    private static List<String> captureLog(final Runnable action) {
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
        final var logger = Logger.getLogger(ClassGraph.class.getName());
        final var useParentHandlers = logger.getUseParentHandlers();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            action.run();
        } finally {
            logger.setUseParentHandlers(useParentHandlers);
            logger.removeHandler(handler);
        }
        return messages;
    }

    /** ClassGraph reports its own version. */
    @Test
    public void theVersionIsReported() {
        // Running from target/classes there is no jar manifest and no Maven metadata, so the version has to come
        // from the pom.xml -- which, in the multi-module build, inherits its version from the parent pom
        assertThat(ClassGraph.getVersion()).matches("\\d+\\.\\d+\\.\\d+.*");
    }

    /** Verbose logging is written to the {@code io.github.classgraph.ClassGraph} logger when a scan completes. */
    @Test
    public void verboseLoggingIsWrittenToTheLogger() {
        final var quiet = captureLog(
                () -> new ClassGraph().verbose(false).overrideClasspath(markerDir.toString()).scan().close());
        assertThat(quiet).isEmpty();

        final var verbose = captureLog(
                () -> new ClassGraph().verbose(true).overrideClasspath(markerDir.toString()).scan().close());
        assertThat(verbose).hasSize(1);
        assertThat(verbose.get(0)).contains("ClassGraph version").contains("marker.txt");
    }

    /** Realtime logging writes each log entry as it is created, rather than only when the scan completes. */
    @Test
    public void realtimeLoggingWritesEntriesAsTheyAreCreated() {
        try {
            final var messages = captureLog(() -> new ClassGraph().enableRealtimeLogging()
                    .overrideClasspath(markerDir.toString()).scan().close());
            // Every log entry is written twice: once in realtime, and once in the tree flushed at the end of the
            // scan
            assertThat(messages).hasSizeGreaterThan(1);
            assertThat(messages).anyMatch(message -> message.contains("ClassGraph version"));
        } finally {
            // Realtime logging is a global setting, so switch it off again
            LogNode.logInRealtime(false);
        }
    }

    /** Jar scanning and directory scanning can each be disabled without affecting the other. */
    @Test
    public void jarAndDirScanningCanBeDisabledIndependently() {
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString(), jarFile.toString())
                .acceptPackages(PACKAGE_NAME).scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder(PACKAGE_NAME + ".InDir",
                    PACKAGE_NAME + ".RejectMe", PACKAGE_NAME + ".InJar");
        }
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString(), jarFile.toString())
                .acceptPackages(PACKAGE_NAME).disableJarScanning().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder(PACKAGE_NAME + ".InDir",
                    PACKAGE_NAME + ".RejectMe");
        }
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString(), jarFile.toString())
                .acceptPackages(PACKAGE_NAME).disableDirScanning().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(PACKAGE_NAME + ".InJar");
        }
    }

    /** Module scanning can be disabled, which leaves an accepted module visible but unscanned. */
    @Test
    public void moduleScanningCanBeDisabled() {
        try (var scanResult = new ClassGraph().enableSystemJarsAndModules().acceptModules("java.base")
                .acceptPackages("java.time.chrono").disableJarScanning().disableDirScanning().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains("java.time.chrono.JapaneseEra");
        }
        try (var scanResult = new ClassGraph().enableSystemJarsAndModules().acceptModules("java.base")
                .acceptPackages("java.time.chrono").disableJarScanning().disableDirScanning()
                .disableModuleScanning().scan()) {
            assertThat(scanResult.getAllClasses()).isEmpty();
            // The module is still listed as visible, it is simply not scanned
            assertThat(scanResult.getModuleReferences())
                    .extracting(moduleReference -> moduleReference.descriptor().name())
                    .containsExactly("java.base");
        }
    }

    /**
     * A {@link Path} is an {@link Iterable} of its own name elements, so a single {@link Path} is passed to the
     * {@code Iterable<?>} overload of {@code overrideClasspath()}, rather than to the {@code Object...} overload.
     * It must still be treated as one classpath entry.
     */
    @Test
    public void aSinglePathIsOneClasspathEntry() throws IOException {
        try (var scanResult = new ClassGraph().overrideClasspath(markerDir).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("marker.txt");
        }
        // A list of Paths is still one classpath entry per element. Classpath entries are canonicalized, so the
        // expected paths have to be canonicalized too -- on macOS the temp directory is reached through the symlink
        // /var -> /private/var, and on Windows through an 8.3 short name (C:\Users\RUNNER~1).
        try (var scanResult = new ClassGraph().overrideClasspath(List.of(markerDir, classesDir)).scan()) {
            assertThat(scanResult.getClasspathFiles()).containsExactly(markerDir.toRealPath().toFile(),
                    classesDir.toRealPath().toFile());
        }
    }

    /** A classpath element is only scanned if every path filter accepts its path. */
    @Test
    public void classpathElementsCanBeFilteredByPath() {
        final var paths = new ArrayList<String>();
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString(), markerDir.toString())
                .filterClasspathElements(path -> {
                    paths.add(path);
                    return path.endsWith("/marker");
                }).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("marker.txt");
        }
        // A filter is given the path with '/' as its separator, whatever the platform's own separator is
        assertThat(paths).containsExactly(withForwardSlashes(classesDir), withForwardSlashes(markerDir));
    }

    /**
     * Get the path of a file with '/' as the separator, which is the form ClassGraph normalizes paths to.
     *
     * @param path
     *            the path.
     * @return the path, with any platform-specific separator replaced by '/'.
     */
    private static String withForwardSlashes(final Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    /** A classpath element is only scanned if every {@link URL} filter accepts its {@link URL}. */
    @Test
    public void classpathElementsCanBeFilteredByURL() throws IOException {
        final var urls = new ArrayList<URL>();
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString(), markerDir.toString())
                .filterClasspathElementsByURL(url -> {
                    urls.add(url);
                    return url.toString().endsWith("/marker/");
                }).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("marker.txt");
        }
        // A directory or jarfile classpath element is offered to the filter as a "file:" URL
        assertThat(urls).extracting(URL::toString).containsExactly(classesDir.toFile().toURI().toString(),
                markerDir.toFile().toURI().toString());
    }

    /** A classpath element with a scheme other than {@code "file:"} is offered to the {@link URL} filter as-is. */
    @Test
    public void aClasspathElementWithACustomSchemeIsFilteredByURL() throws IOException {
        final var handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(final URL url) {
                throw new UnsupportedOperationException("The rejected URL must never be opened");
            }
        };
        final var customURL = new URL("cgtest", "host", -1, "/lib.jar", handler);
        final var urls = new ArrayList<URL>();
        try (var scanResult = new ClassGraph().overrideClasspath(customURL, markerDir.toString())
                .filterClasspathElementsByURL(url -> {
                    urls.add(url);
                    return !"cgtest".equals(url.getProtocol());
                }).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("marker.txt");
        }
        assertThat(urls).extracting(URL::toString).containsExactly(customURL.toString(),
                markerDir.toFile().toURI().toString());
    }

    /** Classpath elements can be selected by the resources they contain. */
    @Test
    public void classpathElementsCanBeSelectedByTheResourcesTheyContain() {
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString(), markerDir.toString())
                .acceptClasspathElementsContainingResourcePath("marker.txt").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("marker.txt");
        }
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString(), markerDir.toString())
                .rejectClasspathElementsContainingResourcePath("marker.txt").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder(
                    "com/xyz/cgfixture/InDir.class", "com/xyz/cgfixture/RejectMe.class", "res/indir.txt");
        }
    }

    /** Paths can be rejected, but rejecting the package root would leave nothing to scan. */
    @Test
    public void pathsCanBeRejected() {
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString(), jarFile.toString())
                .acceptPaths("res").rejectPaths("res/**").scan()) {
            assertThat(scanResult.getAllResources()).isEmpty();
        }
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString(), jarFile.toString())
                .acceptPaths("res").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("res/indir.txt",
                    "res/injar.txt");
        }
        for (final var rootPath : new String[] { "", "/", "/**" }) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ClassGraph().rejectPaths(rootPath))
                    .withMessageContaining("will cause nothing to be scanned");
        }
    }

    /** Individual classes can be rejected, even if the package that contains them is accepted. */
    @Test
    public void classesCanBeRejected() {
        try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString()).acceptPackages(PACKAGE_NAME)
                .rejectClasses(PACKAGE_NAME + ".RejectMe").scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(PACKAGE_NAME + ".InDir");
        }
    }

    /** The classpath can be listed without running a scan. */
    @Test
    public void theClasspathCanBeListedWithoutScanning() throws IOException {
        final var classGraph = new ClassGraph().overrideClasspath(classesDir.toString(), jarFile.toString());

        // ClassGraph canonicalizes a classpath element, so the listed paths are the real paths, which differ from
        // the paths the temp directory was handed out as on macOS (/var is a symlink to /private/var) and on
        // Windows (a path can be handed out in 8.3 short form)
        final var realClassesDir = classesDir.toRealPath();
        final var realJarFile = jarFile.toRealPath();

        assertThat(classGraph.getClasspathFiles()).containsExactly(realClassesDir.toFile(), realJarFile.toFile());
        assertThat(classGraph.getClasspath()).isEqualTo(realClassesDir + File.pathSeparator + realJarFile);
        assertThat(classGraph.getClasspathURIs()).containsExactly(realClassesDir.toUri(), realJarFile.toUri());
        assertThat(classGraph.getClasspathURLs()).containsExactly(realClassesDir.toUri().toURL(),
                realJarFile.toUri().toURL());
        // Modules are not scanned when the classpath is overridden
        assertThat(classGraph.getModuleReferences()).isEmpty();
    }

    /** The visible modules can be listed without running a scan. */
    @Test
    public void theVisibleModulesCanBeListed() {
        assertThat(new ClassGraph().enableSystemJarsAndModules().getModuleReferences())
                .extracting(moduleReference -> moduleReference.descriptor().name()).contains("java.base");
    }

    /** The module layers to scan can be overridden. */
    @Test
    public void moduleLayersCanBeOverridden() {
        try (var scanResult = new ClassGraph().overrideModuleLayers(ModuleLayer.boot()).ignoreParentModuleLayers()
                .enableSystemJarsAndModules().acceptModules("java.base").acceptPackages("java.time.chrono")
                .disableJarScanning().disableDirScanning().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains("java.time.chrono.JapaneseEra");
        }
    }

    /** A scan can be run on a caller-supplied {@link java.util.concurrent.ExecutorService}. */
    @Test
    public void aScanCanBeRunOnACallerSuppliedExecutorService() throws InterruptedException, ExecutionException {
        final var executorService = Executors.newFixedThreadPool(3);
        try {
            try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString())
                    .acceptPackages(PACKAGE_NAME).scan(executorService, 3)) {
                assertThat(scanResult.getAllClasses().getNames()).contains(PACKAGE_NAME + ".InDir");
            }
            try (var scanResult = new ClassGraph().overrideClasspath(classesDir.toString())
                    .acceptPackages(PACKAGE_NAME).scanAsync(executorService, 3).get()) {
                assertThat(scanResult.getAllClasses().getNames()).contains(PACKAGE_NAME + ".InDir");
            }
        } finally {
            executorService.shutdown();
        }
    }

    /** An asynchronous scan passes its {@link ScanResult} to the scan result processor. */
    @Test
    public void anAsyncScanCallsTheScanResultProcessor() throws InterruptedException {
        final var classNames = new AtomicReference<List<String>>();
        final var failure = new AtomicReference<Throwable>();
        final var done = new CountDownLatch(1);
        final var executorService = Executors.newFixedThreadPool(3);
        try {
            new ClassGraph().overrideClasspath(classesDir.toString()).acceptPackages(PACKAGE_NAME)
                    .scanAsync(executorService, 3, scanResult -> {
                        try (scanResult) {
                            classNames.set(scanResult.getAllClasses().getNames());
                        }
                        done.countDown();
                    }, throwable -> {
                        failure.set(throwable);
                        done.countDown();
                    });
            assertThat(done.await(60, TimeUnit.SECONDS)).as("the scan completed").isTrue();
        } finally {
            executorService.shutdown();
        }
        assertThat(failure.get()).isNull();
        assertThat(classNames.get()).contains(PACKAGE_NAME + ".InDir");
    }

    /** An asynchronous scan that fails passes the exception to the failure handler. */
    @Test
    public void aFailedAsyncScanCallsTheFailureHandler() throws InterruptedException {
        final var failure = new AtomicReference<Throwable>();
        final var done = new CountDownLatch(1);
        final var executorService = Executors.newFixedThreadPool(3);
        try {
            new ClassGraph().overrideClasspath(classesDir.toString()).filterClasspathElements(path -> {
                throw new IllegalStateException("classpath element filter failed");
            }).scanAsync(executorService, 3, scanResult -> {
                scanResult.close();
                done.countDown();
            }, throwable -> {
                failure.set(throwable);
                done.countDown();
            });
            assertThat(done.await(60, TimeUnit.SECONDS)).as("the failure handler was called").isTrue();
        } finally {
            executorService.shutdown();
        }
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                .hasMessage("classpath element filter failed");
    }

    /** A deflated nested jar is spilled to disk, rather than buffered in RAM, if the RAM limit is exceeded. */
    @Test
    public void aDeflatedNestedJarIsSpilledToDiskWhenTheRamLimitIsExceeded() {
        final var jarURL = ClassGraphTest.class.getClassLoader().getResource("nested-jars-level1.zip");
        assertThat(jarURL).isNotNull();
        final var nestedJarPath = "jar:file://" + jarURL.getPath()
                + "!/level2.jar!/level3.jar!/classpath1/classpath2";
        try (var scanResult = new ClassGraph().overrideClasspath(nestedJarPath).setMaxBufferedJarRAMSize(0)
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly("com.test.Test");
        }
    }
}
