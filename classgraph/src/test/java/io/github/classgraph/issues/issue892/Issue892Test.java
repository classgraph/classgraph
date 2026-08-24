package io.github.classgraph.issues.issue892;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Some classloaders do not expose their classpath through any of the field or method names that
 * {@code FallbackClassLoaderHandler} knows about, but they can still enumerate the resources they serve. Ask such a
 * classloader for resources that are present in the root of most classpath elements, and strip the resource path
 * from the returned URLs to recover the classpath elements.
 */
class Issue892Test {
    /**
     * A classloader that exposes its classpath only through {@link ClassLoader#getResources(String)}.
     */
    private static class OpaqueClassLoader extends ClassLoader implements Closeable {
        /**
         * The field name is not one of the names probed by {@code FallbackClassLoaderHandler}.
         */
        private final URLClassLoader resourceSource;

        OpaqueClassLoader(final URL jarURL) {
            // Use the bootstrap classloader as the parent, so that the jar is only reachable through this
            // classloader, and not through a parent classloader
            super(null);
            this.resourceSource = new URLClassLoader(new URL[] { jarURL }, null);
        }

        @Override
        protected URL findResource(final String name) {
            return resourceSource.findResource(name);
        }

        @Override
        protected Enumeration<URL> findResources(final String name) throws IOException {
            return resourceSource.findResources(name);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            return resourceSource.loadClass(name);
        }

        /** Release the jar file, so that it can be deleted on Windows. */
        @Override
        public void close() throws IOException {
            resourceSource.close();
        }
    }

    /** Build a jar containing a manifest and the classfile of the given class. */
    private static void buildJar(final File jarFile, final Class<?> classToInclude) throws IOException {
        final var classfilePath = classToInclude.getName().replace('.', '/') + ".class";
        final var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var outputStream = new FileOutputStream(jarFile);
                var jarOutputStream = new JarOutputStream(outputStream, manifest);
                var classfileStream = classToInclude.getClassLoader().getResourceAsStream(classfilePath)) {
            assertThat(classfileStream).isNotNull();
            jarOutputStream.putNextEntry(new JarEntry(classfilePath));
            final var buf = new byte[8192];
            for (var read = classfileStream.read(buf); read > 0; read = classfileStream.read(buf)) {
                jarOutputStream.write(buf, 0, read);
            }
            jarOutputStream.closeEntry();
        }
    }

    /**
     * A classpath element is found even if the classloader only exposes it through {@code getResources()}.
     */
    @Test
    void classpathElementIsFoundByProbingForResources() throws IOException {
        // Not @TempDir: on Windows, the jar cannot be deleted while it is still open, and it may be held open by a
        // memory mapping until it is garbage collected, which would fail the temp directory cleanup
        final var jarFile = File.createTempFile("issue892-", ".jar");
        jarFile.deleteOnExit();
        buildJar(jarFile, ClassInProbedJar.class);

        try (var classLoader = new OpaqueClassLoader(jarFile.toURI().toURL())) {
            // Only scan the opaque classloader, so that the class can only be found within the jar it serves, and
            // not in the directory of test classes that it was copied from
            try (var scanResult = new ClassGraph().enableClassLoaders(classLoader).enableClassInfo().scan()) {
                assertThat(scanResult.getAllClasses().getNames()).contains(ClassInProbedJar.class.getName());
                // Compare by filename, since the canonical form of the temp directory is platform-dependent (a
                // symlink on macOS, an 8.3 short name on Windows)
                assertThat(scanResult.getClasspathFiles()).hasSize(1);
                assertThat(scanResult.getClasspathFiles().get(0).getName()).isEqualTo(jarFile.getName());
            }
        }
    }
}
