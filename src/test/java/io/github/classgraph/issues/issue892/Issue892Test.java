package io.github.classgraph.issues.issue892;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Some classloaders do not expose their classpath through any of the field or method names that
 * {@code FallbackClassLoaderHandler} knows about, but they can still enumerate the resources they serve. Ask such a
 * classloader for resources that are present in the root of most classpath elements, and strip the resource path
 * from the returned URLs to recover the classpath elements (#892).
 */
class Issue892Test {
    /** A classloader that exposes its classpath only through {@link ClassLoader#getResources(String)}. */
    private static class OpaqueClassLoader extends ClassLoader {
        /** The field name is not one of the names probed by {@code FallbackClassLoaderHandler}. */
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
    }

    /** Build a jar containing a manifest and the classfile of the given class. */
    private static File buildJar(final File dir, final Class<?> classToInclude) throws IOException {
        final File jarFile = new File(dir, "issue892.jar");
        final String classfilePath = classToInclude.getName().replace('.', '/') + ".class";
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream outputStream = new FileOutputStream(jarFile);
                JarOutputStream jarOutputStream = new JarOutputStream(outputStream, manifest);
                InputStream classfileStream = classToInclude.getClassLoader()
                        .getResourceAsStream(classfilePath)) {
            assertThat(classfileStream).isNotNull();
            jarOutputStream.putNextEntry(new JarEntry(classfilePath));
            final byte[] buf = new byte[8192];
            for (int read = classfileStream.read(buf); read > 0; read = classfileStream.read(buf)) {
                jarOutputStream.write(buf, 0, read);
            }
            jarOutputStream.closeEntry();
        }
        return jarFile;
    }

    /** A classpath element is found even if the classloader only exposes it through {@code getResources()}. */
    @Test
    void classpathElementIsFoundByProbingForResources(@TempDir final File tempDir) throws IOException {
        final File jarFile = buildJar(tempDir, ClassInProbedJar.class);
        final ClassLoader classLoader = new OpaqueClassLoader(jarFile.toURI().toURL());

        // Only scan the opaque classloader, so that the class can only be found within the jar it serves, and
        // not in the directory of test classes that it was copied from
        try (ScanResult scanResult = new ClassGraph().overrideClassLoaders(classLoader).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(ClassInProbedJar.class.getName());
            assertThat(scanResult.getClasspathFiles()).containsExactly(jarFile.getCanonicalFile());
        }
    }
}
