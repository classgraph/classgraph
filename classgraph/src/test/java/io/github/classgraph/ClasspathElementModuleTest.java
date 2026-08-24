package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * Tests for the classpath element of a module, and for the resources read from it. A module is read through a
 * {@link ModuleReader} rather than from the filesystem or a zipfile, so it reports less about each resource than
 * the other kinds of classpath element do, and a reader has to be acquired and released around every read.
 */
public class ClasspathElementModuleTest {
    /** The virtual filesystem that the classpath elements under test are created against. */
    @AutoClose
    private static final Vfs VFS = new Vfs();

    /** A package in the {@code java.base} system module that holds only classfiles. */
    private static final String PACKAGE_PATH = "java/util/function";

    /** The path of a classfile in {@link #PACKAGE_PATH}. */
    private static final String CLASSFILE_PATH = PACKAGE_PATH + "/Function.class";

    /** The path of a second classfile in {@link #PACKAGE_PATH}. */
    private static final String OTHER_CLASSFILE_PATH = PACKAGE_PATH + "/Supplier.class";

    /** The path of the one resource of a module that cannot be read. */
    private static final String UNREADABLE_PATH = "unreadable/resource.txt";

    /**
     * Scan one package of the {@code java.base} system module.
     *
     * @return the scan result.
     */
    private static ScanResult scanSystemModulePackage() {
        return new ClassGraph().enableSystemJars().enableSystemModules().acceptPathsNonRecursive(PACKAGE_PATH)
                .scan();
    }

    /**
     * Get the one resource with the given path from a scan result.
     *
     * @param scanResult
     *            the scan result.
     * @param path
     *            the resource path.
     * @return the resource.
     */
    private static Resource resource(final ScanResult scanResult, final String path) {
        final var resources = scanResult.getResourcesWithPath(path);
        assertThat(resources).as("resources with path " + path).hasSize(1);
        return resources.get(0);
    }

    /** The first four bytes of a classfile, which is what every resource read here should start with. */
    private static byte[] classfileMagic() {
        return new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE };
    }

    /**
     * The content of a resource in a module is the same however it is read, and every accessor returns it in full.
     *
     * @throws IOException
     *             if the resource could not be read.
     */
    @Test
    public void aResourceInAModuleIsReadThroughEveryAccessor() throws IOException {
        try (var scanResult = scanSystemModulePackage()) {
            final byte[] expected = resource(scanResult, CLASSFILE_PATH).load();
            assertThat(expected).startsWith(classfileMagic());

            final var forOpen = resource(scanResult, CLASSFILE_PATH);
            try (var inputStream = forOpen.open()) {
                assertThat(inputStream.readAllBytes()).isEqualTo(expected);
            }

            final var forCloseResource = resource(scanResult, CLASSFILE_PATH);
            final var byteBuffer = forCloseResource.read().getByteBuffer();
            final var readBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(readBytes);
            assertThat(readBytes).isEqualTo(expected);
            forCloseResource.close();

            final var forCloseBuffer = resource(scanResult, CLASSFILE_PATH);
            try (var closeableByteBuffer = forCloseBuffer.read()) {
                final var closeableBytes = new byte[closeableByteBuffer.getByteBuffer().remaining()];
                closeableByteBuffer.getByteBuffer().get(closeableBytes);
                assertThat(closeableBytes).isEqualTo(expected);
            }
        }
    }

    /**
     * A module reader is acquired for the duration of a read and released when the resource is closed, so the same
     * resource can be read any number of times, and two resources can be read at once.
     *
     * @throws IOException
     *             if a resource could not be read.
     */
    @Test
    public void aResourceInAModuleCanBeReadAgainAfterItIsClosed() throws IOException {
        try (var scanResult = scanSystemModulePackage()) {
            final var resource = resource(scanResult, CLASSFILE_PATH);
            final var firstRead = resource.load();
            assertThat(resource.load()).isEqualTo(firstRead);

            // Two resources of the same module that are read at the same time each need their own module reader
            try (var inputStream = resource(scanResult, OTHER_CLASSFILE_PATH).open()) {
                assertThat(resource(scanResult, CLASSFILE_PATH).load()).isEqualTo(firstRead);
                assertThat(inputStream.readAllBytes()).startsWith(classfileMagic());
            }
        }
    }

    /**
     * A {@link ModuleReader} does not report the length of a resource before it is read, so the length is only
     * known once the resource has been read into a buffer.
     *
     * @throws IOException
     *             if the resource could not be read.
     */
    @Test
    public void theLengthOfAResourceInAModuleIsOnlyKnownOnceItHasBeenRead() throws IOException {
        try (var scanResult = scanSystemModulePackage()) {
            final var unread = resource(scanResult, CLASSFILE_PATH);
            assertThat(unread.getLength()).isEqualTo(-1L);

            // Opening a stream on the resource does not reveal the length either
            try (var inputStream = unread.open()) {
                assertThat(unread.getLength()).isEqualTo(-1L);
                assertThat(inputStream.readAllBytes()).startsWith(classfileMagic());
            }

            final var read = resource(scanResult, CLASSFILE_PATH);
            assertThat(read.read().getByteBuffer().remaining()).isEqualTo((int) read.getLength());
            read.close();

            final var loaded = resource(scanResult, CLASSFILE_PATH);
            assertThat(loaded.load()).hasSize((int) loaded.getLength());
        }
    }

    /**
     * A {@link ModuleReader} reports neither a last modified time nor file permissions for a resource, since a
     * module need not be stored on a filesystem.
     */
    @Test
    public void aResourceInAModuleHasNoLastModifiedTimeOrFilePermissions() {
        try (var scanResult = scanSystemModulePackage()) {
            final var resource = resource(scanResult, CLASSFILE_PATH);
            assertThat(resource.getLastModifiedMillis()).isZero();
            assertThat(resource.getPosixFilePermissions()).isNull();
            // A resource in a module has no package root, so the two paths are the same
            assertThat(resource.getPath()).isEqualTo(CLASSFILE_PATH);
            assertThat(resource.getPathRelativeToClasspathElement()).isEqualTo(CLASSFILE_PATH);
        }
    }

    /**
     * A module layer containing the automatic module of the given jar, and nothing else.
     *
     * @param jar
     *            the jar to resolve as an automatic module.
     * @return the module layer.
     */
    private static ModuleLayer moduleLayerFor(final Path jar) {
        final var finder = ModuleFinder.of(jar);
        final var moduleReferences = finder.findAll();
        assertThat(moduleReferences).hasSize(1);
        final var moduleName = moduleReferences.iterator().next().descriptor().name();
        final var bootLayer = ModuleLayer.boot();
        final var configuration = bootLayer.configuration().resolve(finder, ModuleFinder.of(), Set.of(moduleName));
        return ModuleLayer.defineModulesWithOneLoader(configuration, List.of(bootLayer),
                ClasspathElementModuleTest.class.getClassLoader()).layer();
    }

    /**
     * A module that is not a system module is a jar or directory on disk, so unlike a system module it has both a
     * classpath element file and a {@code "file:"} URI, and its resources are read from that file.
     *
     * @param tempDir
     *            a temporary directory to write the module jar to.
     * @throws IOException
     *             if the module jar could not be written or read.
     */
    @Test
    public void aResourceInANonSystemModuleIsReadFromItsJarOnDisk(@TempDir final Path tempDir) throws IOException {
        final var content = "Read from a module on disk";
        final var resourcePath = "modulescan/greeting.txt";
        final var jar = tempDir.resolve("classpathelementmodule.jar");
        try (var zipOutputStream = new ZipOutputStream(Files.newOutputStream(jar))) {
            zipOutputStream.putNextEntry(new ZipEntry(resourcePath));
            zipOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }

        try (var scanResult = new ClassGraph().enableModuleLayers(moduleLayerFor(jar)).ignoreParentModuleLayers()
                .acceptPaths("modulescan").scan()) {
            final var resource = resource(scanResult, resourcePath);
            assertThat(resource.loadAsString()).isEqualTo(content);
            assertThat(resource.load()).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
            assertThat(resource.getLength()).isEqualTo(content.length());
            assertThat(resource.getClasspathElementFile()).isEqualTo(jar.toFile());
            assertThat(resource.getClasspathElementURI()).isEqualTo(jar.toUri());
        }
    }

    /** Nothing is scanned from the module path unless a module source is enabled. */
    @Test
    public void nothingIsScannedFromAModuleUnlessModulesAreEnabled() {
        try (var scanResult = new ClassGraph().enableSystemJars().acceptPathsNonRecursive(PACKAGE_PATH).scan()) {
            assertThat(scanResult.getResourcesWithPath(CLASSFILE_PATH)).isEmpty();
        }
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * A classpath element for the named system module, without a module reader, so only the module's identity and
     * location can be asked for.
     *
     * @param moduleName
     *            the name of the system module.
     * @return the classpath element.
     */
    private static ClasspathElementModule systemModuleClasspathElement(final String moduleName) {
        final var moduleReference = ModuleFinder.ofSystem().find(moduleName).orElseThrow();
        return classpathElementFor(moduleReference);
    }

    /**
     * A classpath element for a module, without a module reader, so only the module's identity and location can be
     * asked for.
     *
     * @param moduleReference
     *            the module.
     * @return the classpath element.
     */
    private static ClasspathElementModule classpathElementFor(final ModuleReference moduleReference) {
        // The classpath element is not opened, so the module is never read -- it is only asked for its identity and
        // location
        return new ClasspathElementModule(moduleReference, VFS,
                new Scanner.ClasspathEntryWorkUnit(null, null, null, 0, "", List.of(), List.of()),
                /* isLookupOnly = */ false, new ScanSpec());
    }

    /**
     * A system module is located by the {@code "jrt:"} URI of its module reference, and is not a file on disk.
     */
    @Test
    public void aSystemModuleIsLocatedByItsJrtUriAndIsNotAFile() {
        final var classpathElement = systemModuleClasspathElement("java.base");
        assertThat(classpathElement.getURI()).isEqualTo(URI.create("jrt:/java.base"));
        assertThat(classpathElement.getAllURIs()).containsExactly(URI.create("jrt:/java.base"));
        assertThat(classpathElement.getFile()).isNull();
        assertThat(classpathElement.getModuleName()).isEqualTo("java.base");
        assertThat(classpathElement.getModuleReference().descriptor().name()).isEqualTo("java.base");
    }

    /**
     * A module reference need not report where the module came from, and a module that does not is neither a URI
     * nor a file. Asking for its URI fails rather than returning null, since a classpath element without a location
     * cannot be reported in a scan result.
     */
    @Test
    public void aModuleWithNoLocationHasNoUriAndNoFile() {
        final var classpathElement = classpathElementFor(moduleReferenceWithNoLocation("unlocated.module"));
        assertThat(classpathElement.getFile()).isNull();
        assertThatThrownBy(classpathElement::getURI).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unlocated.module");
    }

    /**
     * Two classpath elements are the same classpath element when they are for the same module, since a module can
     * only be scanned once however many times it is reached.
     */
    @Test
    public void twoClasspathElementsForTheSameModuleAreEqual() {
        final var classpathElement = systemModuleClasspathElement("java.base");
        final var sameModule = systemModuleClasspathElement("java.base");
        final var otherModule = systemModuleClasspathElement("java.sql");

        assertThat(sameModule).isNotSameAs(classpathElement).isEqualTo(classpathElement)
                .hasSameHashCodeAs(classpathElement);
        assertThat(classpathElement).isEqualTo(classpathElement).isNotEqualTo(otherModule)
                .isNotEqualTo(classpathElement.toString());
        // The classpath element prints as its module reference, which names the module and where it came from
        assertThat(classpathElement.toString()).contains("java.base");
    }

    /**
     * A {@link ModuleReader} that contains one resource, but throws {@link IOException} whenever that resource is
     * read.
     */
    private static class UnreadableModuleReader implements ModuleReader {
        @Override
        public Stream<String> list() {
            return Stream.of(UNREADABLE_PATH);
        }

        @Override
        public Optional<URI> find(final String name) {
            return name.equals(UNREADABLE_PATH) ? Optional.of(URI.create("module:/unreadable.module/" + name))
                    : Optional.empty();
        }

        @Override
        public Optional<InputStream> open(final String name) throws IOException {
            throw new IOException("Simulated read failure");
        }

        @Override
        public Optional<ByteBuffer> read(final String name) throws IOException {
            throw new IOException("Simulated read failure");
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }

    /**
     * A resource in a module that cannot be read reports the failure as an {@link IOException}, and every attempt
     * to read it fails the same way -- a resource left marked as open by the first attempt would throw
     * {@link IllegalStateException} instead. Each failed attempt also returns its {@link ModuleReader} to the
     * recycler, rather than leaving it checked out, so no further readers have to be opened.
     *
     * @throws IOException
     *             if the virtual filesystem could not be closed.
     */
    @Test
    public void aResourceInAModuleThatCannotBeReadFailsTheSameWayEveryTime() throws IOException {
        final var moduleReadersOpened = new AtomicInteger();
        // A virtual filesystem of this test's own, so that the readers it opens for the unreadable module are closed
        // as soon as the test is over
        try (var vfs = new Vfs()) {
            final var classpathElement = new ClasspathElementModule(
                    unreadableModuleReference("unreadable.module", moduleReadersOpened), vfs,
                    new Scanner.ClasspathEntryWorkUnit(null, null, null, 0, "", List.of(), List.of()),
                    /* isLookupOnly = */ true, new ScanSpec());
            classpathElement.open(/* workQueue = */ null, /* log = */ null);

            final var resource = classpathElement.getResource(UNREADABLE_PATH);
            assertThat(resource).as("resource with path " + UNREADABLE_PATH).isNotNull();
            for (var attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(resource::read).as("read").isInstanceOf(IOException.class)
                        .hasRootCauseMessage("Simulated read failure");
                assertThatThrownBy(resource::open).as("open").isInstanceOf(IOException.class)
                        .hasRootCauseMessage("Simulated read failure");
                assertThatThrownBy(resource::load).as("load").isInstanceOf(IOException.class)
                        .hasRootCauseMessage("Simulated read failure");
            }
            assertThat(moduleReadersOpened).as("module readers opened").hasValue(1);
        }
    }

    /**
     * A module reference that reports no location, and that cannot be read.
     *
     * @param moduleName
     *            the name of the module.
     * @return the module reference.
     */
    private static ModuleReference moduleReferenceWithNoLocation(final String moduleName) {
        final var descriptor = ModuleDescriptor.newModule(moduleName).build();
        return new ModuleReference(descriptor, /* location = */ null) {
            @Override
            public ModuleReader open() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String toString() {
                return "module " + moduleName;
            }
        };
    }

    /**
     * A module reference that reports no location, and whose one resource cannot be read.
     *
     * @param moduleName
     *            the name of the module.
     * @param moduleReadersOpened
     *            a counter to increment each time a {@link ModuleReader} is opened for the module.
     * @return the module reference.
     */
    private static ModuleReference unreadableModuleReference(final String moduleName,
            final AtomicInteger moduleReadersOpened) {
        final var descriptor = ModuleDescriptor.newModule(moduleName).build();
        return new ModuleReference(descriptor, /* location = */ null) {
            @Override
            public ModuleReader open() {
                moduleReadersOpened.incrementAndGet();
                return new UnreadableModuleReader();
            }

            @Override
            public String toString() {
                return "module " + moduleName;
            }
        };
    }
}
