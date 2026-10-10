package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

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
     * Get the name of the directory that {@code ext.Base} was read from.
     *
     * @param scanResult
     *            the scan result.
     * @return the name of the directory above the classes directory.
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
