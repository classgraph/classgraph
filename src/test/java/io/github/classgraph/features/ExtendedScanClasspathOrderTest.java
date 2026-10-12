/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * When a scanned class refers to a class outside the accepted packages, scanning is extended to that class. If the
 * class has more than one classfile, the one that is read must be the one the JVM loads: the first copy in
 * classpath order. The test classes are compiled when the test runs.
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
        final Path sourceRoot = dir.resolve("src");
        final Path classesDir = dir.resolve("classes");
        Files.createDirectories(classesDir);
        final List<String> args = new ArrayList<>(Arrays.asList("-nowarn", "-d", classesDir.toString()));
        args.addAll(Arrays.asList(options));
        for (final Entry<String, String> ent : sources.entrySet()) {
            final Path sourceFile = sourceRoot.resolve(ent.getKey());
            Files.createDirectories(sourceFile.getParent());
            Files.write(sourceFile, ent.getValue().getBytes(StandardCharsets.UTF_8));
            args.add(sourceFile.toString());
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the JDK's compiler, which the test JVM has to be running on").isNotNull();
        assertThat(compiler.run(null, null, null, args.toArray(new String[0]))).as("javac exit code").isZero();
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
        final Map<String, String> sources = new LinkedHashMap<>();
        sources.put("ext/MarkerA.java", "package ext; public interface MarkerA {}");
        sources.put("ext/MarkerB.java", "package ext; public interface MarkerB {}");
        sources.put("ext/MarkerC.java", "package ext; public interface MarkerC {}");
        return compile(tempDir.resolve("markers"), sources);
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
        final Map<String, String> sources = new LinkedHashMap<>();
        sources.put("ext/Base.java", "package ext; public class Base implements " + marker + " {}");
        if (!subclassPackage.isEmpty()) {
            sources.put(subclassPackage + "/Sub.java",
                    "package " + subclassPackage + "; public class Sub extends ext.Base {}");
        }
        return compile(dir, sources, "-cp", markers.toString());
    }

    /**
     * Get the name of the directory that {@code ext.Base} was read from.
     *
     * @param scanResult
     *            the scan result.
     * @return the name of the directory above the classes directory.
     */
    private static String baseCopy(final ScanResult scanResult) {
        final ClassInfo base = scanResult.getClassInfo("ext.Base");
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
        final Path markers = compileMarkers(tempDir);
        final Path lib1 = compileBase(tempDir.resolve("lib1"), markers, "MarkerA", "");
        final Path lib2 = compileBase(tempDir.resolve("lib2"), markers, "MarkerC", "");
        final Path app = compileBase(tempDir.resolve("app"), markers, "MarkerB", "app");

        try (ScanResult scanResult = new ClassGraph().overrideClasspath(lib1, app, markers).enableClassInfo()
                .acceptPackages("app").scan()) {
            assertThat(baseCopy(scanResult)).isEqualTo("lib1");
            assertThat(scanResult.getClassesImplementing("ext.MarkerA").getNames()).containsExactly("app.Sub",
                    "ext.Base");
            assertThat(scanResult.getClassesImplementing("ext.MarkerB").getNames()).isEmpty();
        }
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(lib2, lib1, app, markers)
                .enableClassInfo().acceptPackages("app").scan()) {
            assertThat(baseCopy(scanResult)).isEqualTo("lib2");
            assertThat(scanResult.getClassesImplementing("ext.MarkerC").getNames()).containsExactly("app.Sub",
                    "ext.Base");
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
        final Path markers = compileMarkers(tempDir);
        final Path lib1 = compileBase(tempDir.resolve("lib1"), markers, "MarkerA", "");
        final Path app1 = compileBase(tempDir.resolve("app1"), markers, "MarkerB", "app1");
        final Path app2 = compileBase(tempDir.resolve("app2"), markers, "MarkerC", "app2");
        for (int i = 0; i < 20; i++) {
            try (ScanResult scanResult = new ClassGraph().overrideClasspath(lib1, app1, app2, markers)
                    .enableClassInfo().acceptPackages("app1", "app2").scan()) {
                assertThat(baseCopy(scanResult)).as("scan " + i).isEqualTo("lib1");
                assertThat(scanResult.getClassesImplementing("ext.MarkerA").getNames()).as("scan " + i)
                        .containsExactly("app1.Sub", "app2.Sub", "ext.Base");
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
        final Path markers = compileMarkers(tempDir);
        final Path lib1 = compileBase(tempDir.resolve("lib1"), markers, "MarkerA", "");
        final Path app = compileBase(tempDir.resolve("app"), markers, "MarkerB", "app");
        final Path emptyDir = Files.createDirectories(tempDir.resolve("empty"));
        final Path extIsAFile = Files.createDirectories(tempDir.resolve("extfile"));
        Files.createFile(extIsAFile.resolve("ext"));
        final Path warClasses = Files.createDirectories(tempDir.resolve("war/WEB-INF/classes/ext"));
        Files.copy(lib1.resolve("ext/Base.class"), warClasses.resolve("Base.class"));

        try (ScanResult scanResult = new ClassGraph().overrideClasspath(emptyDir, extIsAFile, lib1, app, markers)
                .enableClassInfo().acceptPackages("app").scan()) {
            assertThat(baseCopy(scanResult)).isEqualTo("lib1");
            assertThat(scanResult.getClassesImplementing("ext.MarkerA").getNames()).containsExactly("app.Sub",
                    "ext.Base");
        }
        try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath(emptyDir, tempDir.resolve("war"), app, markers).enableClassInfo()
                .acceptPackages("app").scan()) {
            // The classpath element's path is canonical, and the temporary directory's may not be (an 8.3 short
            // name on Windows, a symlink on macOS), so only the end of the path is compared
            assertThat(scanResult.getClassInfo("ext.Base").getClasspathElementFile().toPath())
                    .endsWith(Paths.get("war", "WEB-INF", "classes"));
            assertThat(scanResult.getClassesImplementing("ext.MarkerA").getNames()).containsExactly("app.Sub",
                    "ext.Base");
        }
    }
}
