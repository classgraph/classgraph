package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ModuleInfo;
import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * A jarfile that declares a module name through the {@code Automatic-Module-Name} attribute of its manifest
 * declares the same name once it has been exploded into a directory, since the attribute is part of the content of
 * the jarfile rather than of the way the content is packaged.
 */
public class ExplodedJarModuleNameTest {
    /** The module name that the test manifest declares. */
    private static final String MODULE_NAME = "com.example.mylib";

    /** The manifest that declares it. */
    private static final String MANIFEST = "Manifest-Version: 1.0\r\nAutomatic-Module-Name: " + MODULE_NAME
            + "\r\n\r\n";

    /** A class to scan, so that the scan finds something to attribute to the module. */
    public static class Widget {
    }

    /**
     * Write the manifest, and a copy of {@link Widget}'s classfile, into a directory.
     *
     * @param dir
     *            the directory to write into.
     * @return the path of {@link Widget}'s classfile relative to the directory.
     * @throws IOException
     *             if the files could not be written.
     */
    private static String explode(final File dir) throws IOException {
        final var classfilePath = Widget.class.getName().replace('.', '/') + ".class";
        final var manifestFile = new File(dir, "META-INF/MANIFEST.MF");
        assertThat(manifestFile.getParentFile().mkdirs()).isTrue();
        Files.writeString(manifestFile.toPath(), MANIFEST, StandardCharsets.UTF_8);
        final var classfile = new File(dir, classfilePath);
        assertThat(classfile.getParentFile().mkdirs()).isTrue();
        try (var out = new FileOutputStream(classfile)) {
            copyClassfile(classfilePath, out);
        }
        return classfilePath;
    }

    /**
     * Package the same manifest and classfile as a jarfile.
     *
     * @param jar
     *            the jarfile to write.
     * @param entryPrefix
     *            a prefix to store the classfile under, e.g. {@code "BOOT-INF/classes/"}, or the empty string to
     *            store it at the root of the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final File jar, final String entryPrefix) throws IOException {
        final var classfilePath = Widget.class.getName().replace('.', '/') + ".class";
        try (var zipOut = new ZipOutputStream(new FileOutputStream(jar))) {
            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zipOut.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry(entryPrefix + classfilePath));
            copyClassfile(classfilePath, zipOut);
            zipOut.closeEntry();
        }
    }

    /**
     * Copy a classfile from the test classpath to an output stream.
     *
     * @param classfilePath
     *            the path of the classfile.
     * @param out
     *            the stream to copy the classfile to.
     * @throws IOException
     *             if the classfile could not be read.
     */
    private static void copyClassfile(final String classfilePath, final OutputStream out) throws IOException {
        try (var in = ExplodedJarModuleNameTest.class.getClassLoader().getResourceAsStream(classfilePath)) {
            assertThat(in).as(classfilePath).isNotNull();
            in.transferTo(out);
        }
    }

    /**
     * A jarfile and the directory it was exploded into declare the same module name.
     *
     * @param tempDir
     *            a temporary directory to build the jarfile and the exploded copy in.
     * @throws IOException
     *             if the jarfile or the exploded copy could not be written.
     */
    @Test
    public void anExplodedJarDeclaresTheModuleNameThatTheJarfileDeclares(@TempDir final File tempDir)
            throws IOException {
        final var jar = new File(tempDir, "mylib.jar");
        writeJar(jar, /* entryPrefix = */ "");
        final var dir = new File(tempDir, "exploded");
        explode(dir);

        assertThat(moduleNamesOf(jar.getPath())).containsExactly(MODULE_NAME);
        assertThat(moduleNamesOf(dir.getPath())).containsExactly(MODULE_NAME);
    }

    /**
     * Scan one classpath element, and return the names of the modules the scan found.
     *
     * @param classpathEntry
     *            the classpath element to scan.
     * @return the module names.
     */
    private static List<String> moduleNamesOf(final String classpathEntry) {
        try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(classpathEntry)).enableClassInfo()
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(Widget.class.getName());
            return scanResult.getModuleInfo().stream().map(ModuleInfo::getName).toList();
        }
    }

    /**
     * A package root within a jarfile is described by the manifest at the root of the jarfile, not by anything
     * under the package root, and the same is true of a package root within an exploded copy of that jarfile: the
     * classpath element that the package root becomes is described by the manifest of the directory it lies within.
     *
     * @param tempDir
     *            a temporary directory to build the jarfile and the exploded copy in.
     * @throws IOException
     *             if the jarfile or the exploded copy could not be written, or the classloader could not be closed.
     */
    @Test
    public void aPackageRootIsDescribedByTheManifestOfWhatContainsIt(@TempDir final File tempDir)
            throws IOException {
        final var jar = new File(tempDir, "myapp.jar");
        writeJar(jar, "BOOT-INF/classes/");
        final var dir = new File(tempDir, "exploded");
        final var classfilePath = explode(dir);
        // Move the classfile under the package root, so that the directory is an exploded copy of the jarfile
        final var packageRootClassfile = new File(dir, "BOOT-INF/classes/" + classfilePath);
        assertThat(packageRootClassfile.getParentFile().mkdirs()).isTrue();
        assertThat(new File(dir, classfilePath).renameTo(packageRootClassfile)).isTrue();

        assertThat(moduleNamesThroughAPackageRootDeclaringClassLoader(jar)).containsExactly(MODULE_NAME);
        assertThat(moduleNamesThroughAPackageRootDeclaringClassLoader(dir)).containsExactly(MODULE_NAME);
    }

    /**
     * Scan one classpath element through a classloader whose {@link ClassLoaderHandler} declares
     * {@code "BOOT-INF/classes/"} as a package root, and return the names of the modules the scan found.
     *
     * @param classpathEntry
     *            the classpath element to scan.
     * @return the module names.
     * @throws IOException
     *             if the classloader could not be closed.
     */
    private static List<String> moduleNamesThroughAPackageRootDeclaringClassLoader(final File classpathEntry)
            throws IOException {
        try (var classLoader = new AppClassLoader(classpathEntry.toURI().toURL());
                var scanResult = new ClassGraph().enableClassLoaders(classLoader)
                        .registerClassLoaderHandler(new AppClassLoaderHandler()).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(Widget.class.getName());
            return scanResult.getModuleInfo().stream().map(ModuleInfo::getName).toList();
        }
    }

    /**
     * A stand-in for a launcher's classloader, which serves a whole executable archive as one classpath element.
     */
    private static final class AppClassLoader extends URLClassLoader {
        /**
         * Constructor.
         *
         * @param app
         *            the URL of the executable archive, or of an exploded copy of it.
         */
        AppClassLoader(final URL app) {
            super(new URL[] { app }, /* parent = */ null);
        }
    }

    /** A {@link ClassLoaderHandler} for {@link AppClassLoader}, declaring the package root of an executable jar. */
    private static final class AppClassLoaderHandler implements ClassLoaderHandler {
        /** Constructor. */
        AppClassLoaderHandler() {
        }

        @Override
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return classLoaderClass == AppClassLoader.class;
        }

        @Override
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderOrder.add(classLoader, log);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            for (final var url : ((AppClassLoader) classLoader).getURLs()) {
                classpathOrder.addClasspathEntry(url, classLoader, log);
            }
        }

        @Override
        public List<String> getPackageRootPrefixes() {
            return List.of("BOOT-INF/classes/");
        }
    }
}
