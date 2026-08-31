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

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;
import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * A package root within a classpath element, e.g. the {@code "WEB-INF/classes/"} of a war, is a classpath element
 * in its own right, whether it was named on the classpath or found automatically, and whether it lies within a
 * jarfile or within a directory that the jarfile was exploded into. So a resource beneath a package root is
 * addressed relative to the package root, and names the package root as the classpath element it came from.
 *
 * <p>
 * This is what the classloaders that serve such an archive do: an exploded war served by Tomcat's
 * {@code ParallelWebappClassLoader} reports {@code WEB-INF/classes/} as one of the URLs of its classpath, rather
 * than the root of the war, and a packed war serves a class from it under a URL that names the package root.
 */
public class PackageRootIsAClasspathElementTest {
    /** The package root that the classloader of this test looks for classes in. */
    private static final String PACKAGE_ROOT_PREFIX = "WEB-INF/classes/";

    /** The lib dir whose jarfiles the classloader of this test adds to the classpath. */
    private static final String LIB_DIR_PREFIX = "WEB-INF/lib/";

    /** A class to scan, so that the scan finds a resource beneath the package root. */
    public static class Widget {
    }

    /** The path of {@link Widget}'s classfile, relative to the package root. */
    private static final String CLASSFILE_PATH = Widget.class.getName().replace('.', '/') + ".class";

    /**
     * Write a jarfile that holds {@link Widget}'s classfile beneath the package root.
     *
     * @param jar
     *            the jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final Path jar) throws IOException {
        try (var zipOut = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            zipOut.putNextEntry(new ZipEntry(PACKAGE_ROOT_PREFIX + CLASSFILE_PATH));
            zipOut.write(classfileContent());
            zipOut.closeEntry();
        }
    }

    /**
     * Write the same content into a directory, as exploding the jarfile would.
     *
     * @param dir
     *            the directory to write into.
     * @throws IOException
     *             if the files could not be written.
     */
    private static void explodeJar(final Path dir) throws IOException {
        final var classfile = dir.resolve(PACKAGE_ROOT_PREFIX + CLASSFILE_PATH);
        Files.createDirectories(classfile.getParent());
        Files.write(classfile, classfileContent());
    }

    /**
     * Resolve a temporary directory to its canonical form, since ClassGraph reports the canonical path of a
     * classpath element -- on Windows a temporary directory is named by its 8.3 short name ("RUNNER~1" rather than
     * "runneradmin"), and on macOS it lies below the "/var" symlink to "/private/var".
     *
     * @param dir
     *            the directory.
     * @return the canonical form of the directory.
     * @throws IOException
     *             if the directory could not be resolved.
     */
    private static Path canonicalize(final Path dir) throws IOException {
        return dir.toRealPath();
    }

    /**
     * Read {@link Widget}'s classfile from the test classpath.
     *
     * @return the content of the classfile.
     * @throws IOException
     *             if the classfile could not be read.
     */
    private static byte[] classfileContent() throws IOException {
        try (var in = PackageRootIsAClasspathElementTest.class.getClassLoader()
                .getResourceAsStream(CLASSFILE_PATH)) {
            assertThat(in).as(CLASSFILE_PATH).isNotNull();
            return in.readAllBytes();
        }
    }

    /**
     * A package root named as part of a classpath entry is the classpath element, for a jarfile and for a directory
     * alike.
     *
     * @param rawTempDir
     *            a temporary directory to build the jarfile and the exploded copy in.
     * @throws IOException
     *             if the jarfile or the exploded copy could not be written.
     */
    @Test
    public void aPackageRootNamedOnTheClasspathIsTheClasspathElement(@TempDir final Path rawTempDir)
            throws IOException {
        final var tempDir = canonicalize(rawTempDir);
        final var jar = tempDir.resolve("app.jar");
        writeJar(jar);
        final var dir = tempDir.resolve("app");
        explodeJar(dir);

        final var jarPackageRootURI = "jar:" + jar.toUri() + "!/WEB-INF/classes";
        try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(jar + "!/WEB-INF/classes")).scan()) {
            assertThat(uriStrings(scanResult)).containsExactly(jarPackageRootURI);
            assertPackageRootIsTheClasspathElement(scanResult, jarPackageRootURI);
        }

        final var dirPackageRootURI = dir.resolve(PACKAGE_ROOT_PREFIX).toUri().toString();
        try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(dir.resolve(PACKAGE_ROOT_PREFIX)))
                .scan()) {
            assertThat(uriStrings(scanResult)).containsExactly(dirPackageRootURI);
            assertPackageRootIsTheClasspathElement(scanResult, dirPackageRootURI);
        }
    }

    /**
     * A package root found automatically within a jarfile, because the classloader that yielded the jarfile
     * declares that it loads classes from a dir of that name, is the classpath element too. The jarfile it was
     * found within stays on the classpath, since a classloader that looks in a package root also loads the classes
     * stored outside it.
     *
     * @param rawTempDir
     *            a temporary directory to build the jarfile in.
     * @throws IOException
     *             if the jarfile could not be written, or the classloader could not be closed.
     */
    @Test
    public void aPackageRootFoundAutomaticallyInAJarIsAlsoTheClasspathElement(@TempDir final Path rawTempDir)
            throws IOException {
        final var tempDir = canonicalize(rawTempDir);
        final var jar = tempDir.resolve("app.jar");
        writeJar(jar);

        final var packageRootURI = "jar:" + jar.toUri() + "!/WEB-INF/classes";
        try (var classLoader = new WebappClassLoader(jar.toUri().toURL()); var scanResult = scan(classLoader)) {
            assertThat(uriStrings(scanResult)).containsExactly(jar.toUri().toString(), packageRootURI);
            assertPackageRootIsTheClasspathElement(scanResult, packageRootURI);
        }
    }

    /**
     * The same package root, found automatically within a directory that the jarfile was exploded into, is likewise
     * the classpath element.
     *
     * @param rawTempDir
     *            a temporary directory to build the exploded copy in.
     * @throws IOException
     *             if the exploded copy could not be written, or the classloader could not be closed.
     */
    @Test
    public void aPackageRootFoundAutomaticallyInAnExplodedJarIsAlsoTheClasspathElement(
            @TempDir final Path rawTempDir) throws IOException {
        final var tempDir = canonicalize(rawTempDir);
        final var dir = tempDir.resolve("app");
        explodeJar(dir);

        final var packageRootURI = dir.resolve(PACKAGE_ROOT_PREFIX).toUri().toString();
        try (var classLoader = new WebappClassLoader(dir.toUri().toURL()); var scanResult = scan(classLoader)) {
            assertThat(uriStrings(scanResult)).containsExactly(dir.toUri().toString(), packageRootURI);
            assertPackageRootIsTheClasspathElement(scanResult, packageRootURI);
        }
    }

    /**
     * The package root comes before the jarfiles of the lib dir on the classpath, because that is the order the
     * classloader looks in them in -- Tomcat serves {@code WEB-INF/classes/} ahead of {@code WEB-INF/lib/}, so a
     * class in the webapp's own classes masks a copy of it in a bundled dependency, which is what lets a webapp
     * override a class of a library it bundles.
     *
     * @param rawTempDir
     *            a temporary directory to build the war and the exploded copy in.
     * @throws IOException
     *             if the war or the exploded copy could not be written, or a classloader could not be closed.
     */
    @Test
    public void thePackageRootComesBeforeTheLibDirJars(@TempDir final Path rawTempDir) throws IOException {
        final var tempDir = canonicalize(rawTempDir);
        final var war = tempDir.resolve("app.war");
        try (var zipOut = new ZipOutputStream(new FileOutputStream(war.toFile()))) {
            // The lib jar is written first, so that the order of the classpath cannot come from the order of the
            // entries in the war
            zipOut.putNextEntry(new ZipEntry(LIB_DIR_PREFIX + "dep.jar"));
            zipOut.write(emptyJarContent());
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry(PACKAGE_ROOT_PREFIX + CLASSFILE_PATH));
            zipOut.write(classfileContent());
            zipOut.closeEntry();
        }
        try (var classLoader = new WebappClassLoader(war.toUri().toURL()); var scanResult = scan(classLoader)) {
            assertThat(uriStrings(scanResult)).containsExactly(war.toUri().toString(),
                    "jar:" + war.toUri() + "!/WEB-INF/classes", "jar:" + war.toUri() + "!/WEB-INF/lib/dep.jar");
        }

        final var dir = tempDir.resolve("app");
        explodeJar(dir);
        final var libJar = dir.resolve(LIB_DIR_PREFIX + "dep.jar");
        Files.createDirectories(libJar.getParent());
        Files.write(libJar, emptyJarContent());
        try (var classLoader = new WebappClassLoader(dir.toUri().toURL()); var scanResult = scan(classLoader)) {
            assertThat(uriStrings(scanResult)).containsExactly(dir.toUri().toString(),
                    dir.resolve(PACKAGE_ROOT_PREFIX).toUri().toString(), libJar.toUri().toString());
        }
    }

    /**
     * The content of a jarfile with no entries, which is enough to be opened as a classpath element.
     *
     * @return the content of the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static byte[] emptyJarContent() throws IOException {
        final var bytes = new ByteArrayOutputStream();
        new ZipOutputStream(bytes).close();
        return bytes.toByteArray();
    }

    /**
     * Scan the classpath of a {@link WebappClassLoader}.
     *
     * @param classLoader
     *            the classloader to scan.
     * @return the scan result.
     */
    private static ScanResult scan(final WebappClassLoader classLoader) {
        return new ClassGraph().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(new WebappClassLoaderHandler()).scan();
    }

    /**
     * The URIs of the classpath elements of a scan, as strings.
     *
     * @param scanResult
     *            the scan result.
     * @return the URIs, in classpath order.
     */
    private static List<String> uriStrings(final ScanResult scanResult) {
        return scanResult.getClasspathURIs().stream().map(Object::toString).toList();
    }

    /**
     * Assert that the one resource the scan found is addressed relative to the package root, and names the package
     * root as the classpath element it came from.
     *
     * @param scanResult
     *            the scan result.
     * @param packageRootURI
     *            the URI of the package root.
     */
    private static void assertPackageRootIsTheClasspathElement(final ScanResult scanResult,
            final String packageRootURI) {
        final var resources = scanResult.getAllResources();
        assertThat(resources.getPaths()).containsExactly(CLASSFILE_PATH);
        final Resource resource = resources.get(0);
        assertThat(resource.getPathRelativeToClasspathElement()).isEqualTo(CLASSFILE_PATH);
        assertThat(resource.getClasspathElementURI().toString()).isEqualTo(packageRootURI);
    }

    /** A stand-in for a servlet container's classloader, which serves a whole war as one classpath element. */
    private static final class WebappClassLoader extends URLClassLoader {
        /**
         * Constructor.
         *
         * @param webapp
         *            the URL of the war, or of an exploded copy of it.
         */
        WebappClassLoader(final URL webapp) {
            super(new URL[] { webapp }, /* parent = */ null);
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
            return List.of(PACKAGE_ROOT_PREFIX);
        }

        @Override
        public List<String> getLibDirPrefixes() {
            return List.of(LIB_DIR_PREFIX);
        }
    }
}
