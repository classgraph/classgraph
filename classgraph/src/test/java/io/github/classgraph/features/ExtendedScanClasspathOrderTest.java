package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.ToolProvider;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;

/**
 * When a scanned class refers to a class outside the accepted packages, scanning is extended to that class. If the
 * class has more than one classfile, the one that is read must be the one the JVM loads: the copy in a module, if
 * the class's package is in a module, otherwise the first copy in classpath order. The test classes are compiled
 * when the test runs.
 */
public class ExtendedScanClasspathOrderTest {
    /**
     * Compile classes.
     *
     * @param dir
     *            the directory to write the source files to, and to compile the classes into.
     * @param sources
     *            the source of each class, keyed by the path of its source file relative to the source root.
     * @param options
     *            the other options to pass to the compiler.
     * @return the directory holding the compiled classes.
     * @throws IOException
     *             if a source file could not be written.
     */
    private static Path compile(final Path dir, final Map<String, String> sources, final String... options)
            throws IOException {
        final var sourceRoot = dir.resolve("src");
        final var classesDir = dir.resolve("classes");
        final List<String> args = new ArrayList<>(List.of("-nowarn", "-d", classesDir.toString()));
        args.addAll(List.of(options));
        for (final var ent : sources.entrySet()) {
            final var sourceFile = sourceRoot.resolve(ent.getKey());
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, ent.getValue());
            args.add(sourceFile.toString());
        }
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the JDK's compiler, which the test JVM has to be running on").isNotNull();
        assertThat(compiler.run(null, null, null, args.toArray(String[]::new))).as("javac exit code").isZero();
        return classesDir;
    }

    /**
     * Compile the marker interfaces {@code ext.MarkerA}, {@code ext.MarkerB} and {@code ext.MarkerC}.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @return the directory holding the compiled classes.
     * @throws IOException
     *             if a source file could not be written.
     */
    private static Path compileMarkers(final Path tempDir) throws IOException {
        return compile(tempDir.resolve("markers"), Map.of( //
                "ext/MarkerA.java", "package ext; public interface MarkerA {}", //
                "ext/MarkerB.java", "package ext; public interface MarkerB {}", //
                "ext/MarkerC.java", "package ext; public interface MarkerC {}"));
    }

    /**
     * Compile a copy of {@code ext.Base} that implements the given marker interface, optionally together with a
     * class in another package that extends it.
     *
     * @param dir
     *            the directory to compile into.
     * @param markers
     *            the directory holding the marker interfaces.
     * @param marker
     *            the simple name of the marker interface that this copy of {@code ext.Base} implements.
     * @param subclassPackage
     *            the package of the class {@code Sub} that extends {@code ext.Base}, or the empty string to compile
     *            no subclass.
     * @return the directory holding the compiled classes.
     * @throws IOException
     *             if a source file could not be written.
     */
    private static Path compileBase(final Path dir, final Path markers, final String marker,
            final String subclassPackage) throws IOException {
        final var base = Map.entry("ext/Base.java", "package ext; public class Base implements " + marker + " {}");
        return compile(dir,
                subclassPackage.isEmpty() ? Map.ofEntries(base)
                        : Map.ofEntries(base,
                                Map.entry(subclassPackage + "/Sub.java",
                                        "package " + subclassPackage + "; public class Sub extends ext.Base {}")),
                "-cp", markers.toString());
    }

    /**
     * Write every file under a directory to a jar.
     *
     * @param classesDir
     *            the directory holding the files.
     * @param jarFile
     *            the jar to write.
     * @return the jar.
     * @throws IOException
     *             if the jar could not be written.
     */
    private static Path jar(final Path classesDir, final Path jarFile) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(jarFile)); var files = Files.walk(classesDir)) {
            for (final var file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new ZipEntry(classesDir.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jarFile;
    }

    /**
     * Get the name of the directory that holds the classes directory or jar that {@code ext.Base} was read from.
     *
     * @param scanResult
     *            the scan result.
     * @return the name of the directory above the classes directory or jar.
     */
    private static String baseCopy(final ScanResult scanResult) {
        final var base = scanResult.getClassInfo("ext.Base");
        assertThat(base).isNotNull();
        return base.getClasspathElementFile().getParentFile().getName();
    }

    /**
     * Scanning is extended to the first copy of a class on the classpath, even when the scanned class that refers
     * to it has its own copy.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void theFirstCopyInClasspathOrderIsRead(@TempDir final Path tempDir) throws IOException {
        final var markers = compileMarkers(tempDir);
        final var lib1 = compileBase(tempDir.resolve("lib1"), markers, "MarkerA", "");
        final var lib2 = compileBase(tempDir.resolve("lib2"), markers, "MarkerC", "");
        final var app = compileBase(tempDir.resolve("app"), markers, "MarkerB", "app");

        try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(lib1, app, markers)).enableClassInfo()
                .acceptPackages("app").scan()) {
            assertThat(baseCopy(scanResult)).isEqualTo("lib1");
            assertThat(scanResult.getAllClassesImplementing("ext.MarkerA").getNames()).containsExactly("app.Sub");
            assertThat(scanResult.getAllClassesImplementing("ext.MarkerB").getNames()).isEmpty();
        }
        try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(lib2, lib1, app, markers))
                .enableClassInfo().acceptPackages("app").scan()) {
            assertThat(baseCopy(scanResult)).isEqualTo("lib2");
            assertThat(scanResult.getAllClassesImplementing("ext.MarkerC").getNames()).containsExactly("app.Sub");
        }
    }

    /**
     * Scanning is extended to the first copy of a class on the classpath when the copies are in jars.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void theFirstCopyInClasspathOrderIsReadFromJars(@TempDir final Path tempDir) throws IOException {
        final var markers = compileMarkers(tempDir);
        final var lib1 = jar(compileBase(tempDir.resolve("lib1"), markers, "MarkerA", ""),
                tempDir.resolve("lib1").resolve("lib1.jar"));
        final var app = jar(compileBase(tempDir.resolve("app"), markers, "MarkerB", "app"),
                tempDir.resolve("app").resolve("app.jar"));

        try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(lib1, app, markers)).enableClassInfo()
                .acceptPackages("app").scan()) {
            assertThat(baseCopy(scanResult)).isEqualTo("lib1");
            assertThat(scanResult.getAllClassesImplementing("ext.MarkerA").getNames()).containsExactly("app.Sub");
            assertThat(scanResult.getAllClassesImplementing("ext.MarkerB").getNames()).isEmpty();
        }
    }

    /**
     * When scanned classes in two classpath elements refer to a class that both elements hold a copy of, the copy
     * that is read does not depend on which thread reaches the class first.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void theCopyThatIsReadDoesNotDependOnThreadOrder(@TempDir final Path tempDir) throws IOException {
        final var markers = compileMarkers(tempDir);
        final var lib1 = compileBase(tempDir.resolve("lib1"), markers, "MarkerA", "");
        final var app1 = compileBase(tempDir.resolve("app1"), markers, "MarkerB", "app1");
        final var app2 = compileBase(tempDir.resolve("app2"), markers, "MarkerC", "app2");
        for (var i = 0; i < 20; i++) {
            try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(lib1, app1, app2, markers))
                    .enableClassInfo().acceptPackages("app1", "app2").scan()) {
                assertThat(baseCopy(scanResult)).as("scan " + i).isEqualTo("lib1");
                assertThat(scanResult.getAllClassesImplementing("ext.MarkerA").getNames()).as("scan " + i)
                        .containsExactly("app1.Sub", "app2.Sub");
            }
        }
    }

    /**
     * Directories that come before the copy of an external class, and do not hold its package, are passed over,
     * whether the package directory is missing or is a file, and a package root within a directory is searched.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void directoriesWithoutThePackageArePassedOver(@TempDir final Path tempDir) throws IOException {
        final var markers = compileMarkers(tempDir);
        final var lib1 = compileBase(tempDir.resolve("lib1"), markers, "MarkerA", "");
        final var app = compileBase(tempDir.resolve("app"), markers, "MarkerB", "app");
        final var emptyDir = Files.createDirectories(tempDir.resolve("empty"));
        final var extIsAFile = Files.createDirectories(tempDir.resolve("extfile"));
        Files.createFile(extIsAFile.resolve("ext"));
        final var warClasses = Files.createDirectories(tempDir.resolve("war/WEB-INF/classes/ext"));
        Files.copy(lib1.resolve("ext/Base.class"), warClasses.resolve("Base.class"));

        try (var scanResult = new ClassGraph()
                .enableClasspathEntries(List.of(emptyDir, extIsAFile, lib1, app, markers)).enableClassInfo()
                .acceptPackages("app").scan()) {
            assertThat(baseCopy(scanResult)).isEqualTo("lib1");
            assertThat(scanResult.getAllClassesImplementing("ext.MarkerA").getNames()).containsExactly("app.Sub");
        }
        // A package root within a directory is found only for a classloader whose handler declares it
        try (var classLoader = new WebappClassLoader(emptyDir, tempDir.resolve("war"), app, markers);
                var scanResult = new ClassGraph().enableClassLoaders(classLoader)
                        .registerClassLoaderHandler(new WebappClassLoaderHandler()).enableClassInfo()
                        .acceptPackages("app").scan()) {
            assertThat(scanResult.getClassInfo("ext.Base").getClasspathElementFile().toPath())
                    .endsWith(Path.of("war", "WEB-INF", "classes"));
            assertThat(scanResult.getAllClassesImplementing("ext.MarkerA").getNames()).containsExactly("app.Sub");
        }
    }

    /** A stand-in for a servlet container's classloader, which looks for classes in each war's package root. */
    private static final class WebappClassLoader extends URLClassLoader {
        /**
         * Constructor.
         *
         * @param classpath
         *            the directories to load classes from.
         * @throws IOException
         *             if a directory cannot be represented as a URL.
         */
        WebappClassLoader(final Path... classpath) throws IOException {
            super(urls(classpath), /* parent = */ null);
        }

        /**
         * Convert directories to URLs.
         *
         * @param classpath
         *            the directories.
         * @return the URLs.
         * @throws IOException
         *             if a directory cannot be represented as a URL.
         */
        private static URL[] urls(final Path... classpath) throws IOException {
            final var urls = new URL[classpath.length];
            for (var i = 0; i < classpath.length; i++) {
                urls[i] = classpath[i].toUri().toURL();
            }
            return urls;
        }
    }

    /** A {@link ClassLoaderHandler} for {@link WebappClassLoader}, declaring the package root of a war. */
    private static final class WebappClassLoaderHandler implements ClassLoaderHandler {
        /** Constructor. */
        WebappClassLoaderHandler() {
        }

        @Override
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return classLoaderClass == WebappClassLoader.class;
        }

        @Override
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderOrder.add(classLoader, log);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            for (final var url : ((WebappClassLoader) classLoader).getURLs()) {
                classpathOrder.addClasspathEntry(url, classLoader, log);
            }
        }

        @Override
        public List<String> getPackageRootPrefixes() {
            return List.of("WEB-INF/classes/");
        }
    }

    /**
     * A class whose package is in a module is read from the module, even when a classpath element holds a copy of
     * it, since the JVM never loads such a class from the classpath. This is the case of a jar such as
     * {@code xml-apis}, which holds old copies of classes in the {@code java.xml} module.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void aClassInAModulePackageIsReadFromTheModule(@TempDir final Path tempDir) throws IOException {
        // A copy of javax.xml.transform.TransformerException that extends RuntimeException, rather than Exception
        final var xmlApis = compile(tempDir.resolve("xmlapis"),
                Map.of("javax/xml/transform/TransformerException.java",
                        "package javax.xml.transform; public class TransformerException extends RuntimeException { "
                                + "public TransformerException(String message) { super(message); } }"),
                "--patch-module", "java.xml=" + tempDir.resolve("xmlapis").resolve("src"));
        assertThat(xmlApis.resolve("javax/xml/transform/TransformerException.class")).isRegularFile();
        final var app = compile(tempDir.resolve("app"),
                Map.of("app/AppException.java",
                        "package app; public class AppException extends javax.xml.transform.TransformerException { "
                                + "public AppException() { super(\"\"); } }"));

        try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(xmlApis, app)).enableClassInfo()
                .acceptPackages("app").scan()) {
            final var transformerException = scanResult.getClassInfo("javax.xml.transform.TransformerException");
            assertThat(transformerException).isNotNull();
            assertThat(transformerException.getClasspathElementFile()).isNull();
            assertThat(transformerException.getModuleReference()).isNotNull();
            assertThat(transformerException.getModuleReference().descriptor().name()).isEqualTo("java.xml");
            assertThat(scanResult.getAllSuperclasses("app.AppException").getNames())
                    .doesNotContain("java.lang.RuntimeException");
        }
        try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(xmlApis, app)).enableClassInfo()
                .enableExternalClasses().acceptPackages("app").scan()) {
            assertThat(
                    scanResult.getClassInfo("javax.xml.transform.TransformerException").getSuperclass().getName())
                    .isEqualTo("java.lang.Exception");
        }
    }
}
