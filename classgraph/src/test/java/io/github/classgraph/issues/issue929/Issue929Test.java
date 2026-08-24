package io.github.classgraph.issues.issue929;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A package named {@code classes} or {@code test-classes} was invisible to scanning, because a directory with that
 * name was assumed to be a package root (as in the Ant layout {@code <root>/classes/com/xyz/Foo.class}), so
 * {@code classes/} was stripped from the relative path of every classfile beneath it, and the resulting relative
 * path then no longer matched the name of the class the classfile declares.
 *
 * <p>
 * The classfiles used by these tests are generated rather than compiled, since the tests are compiled into a fixed
 * package, and the layouts being tested need classes in a package named {@code classes}.
 */
public class Issue929Test {

    /**
     * Generate a minimal classfile for a public class with the given name that extends {@link Object}.
     *
     * @param className
     *            the fully-qualified name of the class.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes(final String className) throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(5); // Constant pool count (one greater than the number of entries)
            out.writeByte(1); // #1: modified UTF8
            out.writeUTF(className.replace('.', '/'));
            out.writeByte(7); // #2: class ref to #1
            out.writeShort(1);
            out.writeByte(1); // #3: modified UTF8
            out.writeUTF("java/lang/Object");
            out.writeByte(7); // #4: class ref to #3
            out.writeShort(3);
            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods
            out.writeShort(0); // Number of class attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Write classfiles into a directory.
     *
     * @param dir
     *            the directory to write the classfiles into.
     * @param classfiles
     *            the classfiles to write, each given as {@code { relativePath, className }} (the relative path is
     *            not always the path that corresponds to the class name -- that is the whole point of a package
     *            root).
     * @return the directory.
     * @throws IOException
     *             if a classfile could not be written.
     */
    private static File buildDir(final File dir, final String[]... classfiles) throws IOException {
        for (final String[] classfile : classfiles) {
            final var classfilePath = new File(dir, classfile[0]);
            classfilePath.getParentFile().mkdirs();
            Files.write(classfilePath.toPath(), classfileBytes(classfile[1]));
        }
        return dir;
    }

    /**
     * Write a jar containing classfiles.
     *
     * @param jarFile
     *            the jar to write.
     * @param classfiles
     *            the classfiles to write, each given as {@code { relativePath, className }}.
     * @return the jar.
     * @throws IOException
     *             if the jar could not be written.
     */
    private static File buildJar(final File jarFile, final String[]... classfiles) throws IOException {
        try (var outputStream = Files.newOutputStream(jarFile.toPath());
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            for (final String[] classfile : classfiles) {
                zipOutputStream.putNextEntry(new ZipEntry(classfile[0]));
                zipOutputStream.write(classfileBytes(classfile[1]));
                zipOutputStream.closeEntry();
            }
        }
        return jarFile;
    }

    /**
     * A package named {@code classes} in a directory classpath element must be scannable.
     *
     * @param tempDir
     *            the temp dir.
     * @throws IOException
     *             if the classfiles could not be written.
     */
    @Test
    public void packageNamedClassesInDirIsScannable(@TempDir final File tempDir) throws IOException {
        final var dir = buildDir(new File(tempDir, "target/classes"),
                new String[] { "classes/AlphaImpl.class", "classes.AlphaImpl" },
                new String[] { "test/Alpha.class", "test.Alpha" });
        try (var scanResult = new ClassGraph() //
                .enableClasspathEntries(dir) //
                .enableClassInfo() //
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder("classes.AlphaImpl",
                    "test.Alpha");
        }
    }

    /**
     * A package named {@code classes} in a jarfile classpath element must be scannable.
     *
     * @param tempDir
     *            the temp dir.
     * @throws IOException
     *             if the jar could not be written.
     */
    @Test
    public void packageNamedClassesInJarIsScannable(@TempDir final File tempDir) throws IOException {
        final var jarFile = buildJar(new File(tempDir, "issue929.jar"),
                new String[] { "classes/AlphaImpl.class", "classes.AlphaImpl" },
                new String[] { "test/Alpha.class", "test.Alpha" });
        try (var scanResult = new ClassGraph() //
                .enableClasspathEntries(jarFile) //
                .enableClassInfo() //
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder("classes.AlphaImpl",
                    "test.Alpha");
        }
    }

    /**
     * A directory named {@code classes} that really is a package root (the Ant layout) is a package root only when
     * it is named as a classpath element. No classloader loads classes from a {@code classes} directory it was
     * never given, so a {@code classes} directory that turns up inside a classpath element is a package.
     *
     * @param tempDir
     *            the temp dir.
     * @throws IOException
     *             if the classfiles could not be written.
     */
    @Test
    public void anAntPackageRootIsOnlyAPackageRootWhenItIsNamed(@TempDir final File tempDir) throws IOException {
        // The class name is "com.xyz.Beta", and the classfile is at "classes/com/xyz/Beta.class", so "classes/" is
        // the package root of the Ant project
        final var dir = buildDir(new File(tempDir, "antproject"),
                new String[] { "classes/com/xyz/Beta.class", "com.xyz.Beta" });
        // The Ant project directory itself is not a package root, so the classfile is beneath a package named
        // "classes", and the class it declares does not match the path it is at
        try (var scanResult = new ClassGraph().enableClasspathEntries(dir).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).isEmpty();
        }
        // Naming the package root gives the class its real name
        try (var scanResult = new ClassGraph().enableClasspathEntries(new File(dir, "classes")).enableClassInfo()
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly("com.xyz.Beta");
        }
    }

    /**
     * The same, for a jarfile classpath element with a {@code classes/} package root.
     *
     * @param tempDir
     *            the temp dir.
     * @throws IOException
     *             if the jar could not be written.
     */
    @Test
    public void anAntPackageRootInAJarIsOnlyAPackageRootWhenItIsNamed(@TempDir final File tempDir)
            throws IOException {
        final var jarFile = buildJar(new File(tempDir, "issue929-antproject.jar"),
                new String[] { "classes/com/xyz/Beta.class", "com.xyz.Beta" });
        try (var scanResult = new ClassGraph().enableClasspathEntries(jarFile).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).isEmpty();
        }
        try (var scanResult = new ClassGraph().enableClasspathEntries(jarFile + "!/classes").enableClassInfo()
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly("com.xyz.Beta");
        }
    }
}
