package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * A jarfile or directory on the classpath is in the unnamed module, so the JVM loads the classes in its default
 * package, whether or not it has a {@code module-info.class} or an {@code Automatic-Module-Name} manifest
 * attribute, and the scan has to find them too. Only a module on the module path cannot contain classes in the
 * default package.
 */
public class DefaultPackageInClasspathModuleTest {
    /** The class in the default package. */
    private static final String DEFAULT_PACKAGE_CLASS = "Foo";

    /** The class in a named package. */
    private static final String PACKAGE_CLASS = "p.Bar";

    /** The compiled classes, with no module descriptor. */
    private static Path classesDir;

    /** The compiled module descriptor of module {@code m}, which exports package {@code p}. */
    private static Path moduleInfoClass;

    /** The directory to write the fixtures to. */
    private static Path fixturesDir;

    /**
     * Compile {@code Foo} in the default package and {@code p.Bar}, and separately a module descriptor for a module
     * that contains {@code p}. javac does not allow a class in the default package to be compiled together with a
     * module descriptor, so the descriptor is compiled on its own and copied in later.
     *
     * @param tempDir
     *            the temporary directory to compile in.
     * @throws IOException
     *             if the sources could not be written.
     */
    @BeforeAll
    public static void compileClasses(@TempDir final Path tempDir) throws IOException {
        final var srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("p"));
        final var fooSrc = srcDir.resolve("Foo.java");
        Files.writeString(fooSrc, "public class Foo {\n}\n");
        final var barSrc = srcDir.resolve("p/Bar.java");
        Files.writeString(barSrc, "package p;\n\npublic class Bar {\n}\n");
        final var modSrcDir = tempDir.resolve("modsrc");
        Files.createDirectories(modSrcDir.resolve("p"));
        final var moduleInfoSrc = modSrcDir.resolve("module-info.java");
        Files.writeString(moduleInfoSrc, "module m {\n    exports p;\n}\n");
        final var modBarSrc = modSrcDir.resolve("p/Bar.java");
        Files.copy(barSrc, modBarSrc);

        classesDir = tempDir.resolve("classes");
        final var modClassesDir = tempDir.resolve("modclasses");
        compile(classesDir, fooSrc, barSrc);
        compile(modClassesDir, moduleInfoSrc, modBarSrc);
        moduleInfoClass = modClassesDir.resolve("module-info.class");
        fixturesDir = tempDir.resolve("fixtures");
        Files.createDirectories(fixturesDir);
    }

    /**
     * Compile source files.
     *
     * @param outputDir
     *            the directory to write the classfiles to.
     * @param sources
     *            the source files.
     */
    private static void compile(final Path outputDir, final Path... sources) {
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        final var args = Stream
                .concat(Stream.of("-d", outputDir.toString()), Stream.of(sources).map(Path::toString))
                .toArray(String[]::new);
        assertThat(compiler.run(/* in = */ null, /* out = */ null, /* err = */ null, args)).as("javac exit code")
                .isZero();
    }

    /**
     * Write a directory fixture holding the compiled classes, plus a module descriptor or a manifest.
     *
     * @param name
     *            the name of the directory.
     * @param withModuleInfo
     *            true to add the module descriptor of module {@code m}.
     * @param automaticModuleName
     *            the {@code Automatic-Module-Name} to declare in the manifest, or null for no manifest.
     * @return the directory.
     * @throws IOException
     *             if the directory could not be written.
     */
    private static Path writeDir(final String name, final boolean withModuleInfo,
            final @Nullable String automaticModuleName) throws IOException {
        final var dir = fixturesDir.resolve(name);
        Files.createDirectories(dir.resolve("p"));
        Files.copy(classesDir.resolve("Foo.class"), dir.resolve("Foo.class"));
        Files.copy(classesDir.resolve("p/Bar.class"), dir.resolve("p/Bar.class"));
        if (withModuleInfo) {
            Files.copy(moduleInfoClass, dir.resolve("module-info.class"));
        }
        if (automaticModuleName != null) {
            Files.createDirectories(dir.resolve("META-INF"));
            try (var out = Files.newOutputStream(dir.resolve(JarFile.MANIFEST_NAME))) {
                manifest(automaticModuleName).write(out);
            }
        }
        return dir;
    }

    /**
     * Write a jarfile fixture holding the compiled classes, plus a module descriptor or a manifest.
     *
     * @param name
     *            the name of the jarfile.
     * @param withModuleInfo
     *            true to add the module descriptor of module {@code m}.
     * @param automaticModuleName
     *            the {@code Automatic-Module-Name} to declare in the manifest, or null for no manifest.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static Path writeJar(final String name, final boolean withModuleInfo,
            final @Nullable String automaticModuleName) throws IOException {
        final var jar = fixturesDir.resolve(name);
        try (var out = automaticModuleName == null ? new JarOutputStream(Files.newOutputStream(jar))
                : new JarOutputStream(Files.newOutputStream(jar), manifest(automaticModuleName))) {
            if (withModuleInfo) {
                addJarEntry(out, "module-info.class", moduleInfoClass);
            }
            addJarEntry(out, "Foo.class", classesDir.resolve("Foo.class"));
            addJarEntry(out, "p/Bar.class", classesDir.resolve("p/Bar.class"));
        }
        return jar;
    }

    /**
     * Add a file to a jarfile.
     *
     * @param out
     *            the jarfile being written.
     * @param entryName
     *            the name of the entry.
     * @param file
     *            the file to copy into the entry.
     * @throws IOException
     *             if the entry could not be written.
     */
    private static void addJarEntry(final JarOutputStream out, final String entryName, final Path file)
            throws IOException {
        out.putNextEntry(new JarEntry(entryName));
        Files.copy(file, out);
        out.closeEntry();
    }

    /**
     * A manifest that declares an automatic module name.
     *
     * @param automaticModuleName
     *            the automatic module name.
     * @return the manifest.
     */
    private static Manifest manifest(final String automaticModuleName) {
        final var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticModuleName);
        return manifest;
    }

    /**
     * Scan one classpath element, and check that both classes are found.
     *
     * @param classpathElement
     *            the jarfile or directory to scan.
     * @param expectedModuleName
     *            the module name that the classpath element declares.
     */
    private static void assertBothClassesAreFound(final Path classpathElement, final String expectedModuleName) {
        try (var scanResult = new ClassGraph().enableClasspathEntries(classpathElement.toString()).enableClassInfo()
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).as(classpathElement.toString())
                    .containsExactlyInAnyOrder(DEFAULT_PACKAGE_CLASS, PACKAGE_CLASS);
            assertThat(scanResult.getResourcesWithPath("Foo.class")).as(classpathElement.toString()).hasSize(1);
            assertThat(scanResult.getClassInfo(PACKAGE_CLASS).getModuleInfo().getName())
                    .as(classpathElement.toString()).isEqualTo(expectedModuleName);
        }
    }

    /**
     * A jarfile with a module descriptor is still in the unnamed module when it is on the classpath.
     *
     * @throws IOException
     *             if the fixture could not be written.
     */
    @Test
    public void aModularJarOnTheClasspathKeepsItsDefaultPackageClasses() throws IOException {
        assertBothClassesAreFound(writeJar("modular.jar", /* withModuleInfo = */ true, null), "m");
    }

    /**
     * A directory with a module descriptor is still in the unnamed module when it is on the classpath.
     *
     * @throws IOException
     *             if the fixture could not be written.
     */
    @Test
    public void aModularDirOnTheClasspathKeepsItsDefaultPackageClasses() throws IOException {
        assertBothClassesAreFound(writeDir("modular-dir", /* withModuleInfo = */ true, null), "m");
    }

    /**
     * A jarfile that declares an automatic module name is still in the unnamed module when it is on the classpath.
     *
     * @throws IOException
     *             if the fixture could not be written.
     */
    @Test
    public void aJarWithAnAutomaticModuleNameKeepsItsDefaultPackageClasses() throws IOException {
        assertBothClassesAreFound(writeJar("automatic.jar", /* withModuleInfo = */ false, "m.auto"), "m.auto");
    }

    /**
     * A directory that declares an automatic module name is still in the unnamed module when it is on the
     * classpath.
     *
     * @throws IOException
     *             if the fixture could not be written.
     */
    @Test
    public void aDirWithAnAutomaticModuleNameKeepsItsDefaultPackageClasses() throws IOException {
        assertBothClassesAreFound(writeDir("automatic-dir", /* withModuleInfo = */ false, "m.auto"), "m.auto");
    }

    /**
     * A module on the module path cannot contain classes in the default package, so a classfile in the root of a
     * module, other than {@code module-info.class}, is not scanned. The module system refuses to load such a
     * module, so the module here is read through a {@link ModuleReader} of the test's own.
     *
     * @throws IOException
     *             if the fixture could not be written, or the virtual filesystem could not be closed.
     */
    @Test
    public void aModuleOnTheModulePathDoesNotScanDefaultPackageClasses() throws IOException {
        final var moduleDir = writeDir("module-path-module", /* withModuleInfo = */ true, null);
        final var moduleReference = new ModuleReference(
                ModuleDescriptor.newModule("m").packages(Set.of("p")).build(), moduleDir.toUri()) {
            @Override
            public ModuleReader open() {
                return new DirModuleReader(moduleDir);
            }
        };
        final var scanSpec = new ScanSpec();
        scanSpec.enableClassInfo = true;
        try (var vfs = new Vfs()) {
            final var classpathElement = new ClasspathElementModule(moduleReference, vfs,
                    /* classLoaderStr = */ null, /* isLookupOnly = */ false, scanSpec);
            classpathElement.open(/* workQueue = */ null, /* log = */ null);
            classpathElement.scanPaths(/* log = */ null);
            assertThat(classpathElement.acceptedClassfileResources).extracting(Resource::getPath)
                    .containsExactlyInAnyOrder("module-info.class", "p/Bar.class");
        }
    }

    /** A {@link ModuleReader} that reads the files of a directory. */
    private static final class DirModuleReader implements ModuleReader {
        /** The directory. */
        private final Path dir;

        /**
         * Constructor.
         *
         * @param dir
         *            the directory.
         */
        DirModuleReader(final Path dir) {
            this.dir = dir;
        }

        @Override
        public Optional<URI> find(final String name) {
            final var file = dir.resolve(name);
            return Files.isRegularFile(file) ? Optional.of(file.toUri()) : Optional.empty();
        }

        @Override
        public Optional<InputStream> open(final String name) throws IOException {
            final var file = dir.resolve(name);
            return Files.isRegularFile(file) ? Optional.of(Files.newInputStream(file)) : Optional.empty();
        }

        @Override
        public Stream<String> list() throws IOException {
            try (var paths = Files.walk(dir)) {
                final List<String> names = paths.filter(Files::isRegularFile)
                        .map(path -> dir.relativize(path).toString().replace('\\', '/')).toList();
                return names.stream();
            }
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }
}
