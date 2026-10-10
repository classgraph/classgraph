package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A classfile is scanned only if its path, without the extension, is a class name in internal form that the JVM
 * accepts. JVMS 4.2.1 excludes only {@code '.'}, {@code ';'}, {@code '['} and {@code '/'} from the segments of a
 * class name, so a package such as {@code my-pkg} or {@code 1pkg}, which is not a Java identifier, still holds
 * classes the JVM loads. A path with a {@code '.'} before the extension, such as {@code x.Y.class}, holds no class
 * the JVM can load from there, and would give the same class name as {@code x/Y.class}.
 */
public class ClassfilePathNameTest {
    /** Bytes that are not a classfile. */
    private static final byte[] JUNK = "not a classfile".getBytes(StandardCharsets.UTF_8);

    /**
     * Write a minimal classfile with no fields or methods.
     *
     * @param internalName
     *            the name of the class, with {@code '/'} as the separator.
     * @param superclassInternalName
     *            the name of the superclass, with {@code '/'} as the separator.
     * @param interfaceInternalNames
     *            the names of the interfaces the class implements, with {@code '/'} as the separator.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfile(final String internalName, final String superclassInternalName,
            final String... interfaceInternalNames) throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            // Constant pool count (one greater than the number of entries): a CONSTANT_Utf8 and a CONSTANT_Class
            // for the class, its superclass, and each interface
            out.writeShort(1 + 2 * (2 + interfaceInternalNames.length));
            out.writeByte(1); // #1
            out.writeUTF(internalName);
            out.writeByte(7); // #2: class ref to #1 (this_class)
            out.writeShort(1);
            out.writeByte(1); // #3
            out.writeUTF(superclassInternalName);
            out.writeByte(7); // #4: class ref to #3 (super_class)
            out.writeShort(3);
            for (var i = 0; i < interfaceInternalNames.length; i++) {
                out.writeByte(1); // #5 + 2i
                out.writeUTF(interfaceInternalNames[i]);
                out.writeByte(7); // #6 + 2i: class ref to #5 + 2i
                out.writeShort(5 + 2 * i);
            }
            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(interfaceInternalNames.length);
            for (var i = 0; i < interfaceInternalNames.length; i++) {
                out.writeShort(6 + 2 * i);
            }
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods
            out.writeShort(0); // Number of attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Write files into a directory.
     *
     * @param dir
     *            the directory.
     * @param files
     *            the content of each file, keyed by its path relative to the directory.
     * @return the directory.
     * @throws IOException
     *             if a file could not be written.
     */
    private static Path writeDir(final Path dir, final Map<String, byte[]> files) throws IOException {
        for (final var ent : files.entrySet()) {
            final var file = dir.resolve(ent.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, ent.getValue());
        }
        return dir;
    }

    /**
     * Write entries into a jarfile, in iteration order.
     *
     * @param jar
     *            the path of the jarfile.
     * @param entries
     *            the content of each entry, keyed by its path.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static Path writeJar(final Path jar, final Map<String, byte[]> entries) throws IOException {
        try (var zipOutputStream = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (final var ent : entries.entrySet()) {
                zipOutputStream.putNextEntry(new ZipEntry(ent.getKey()));
                zipOutputStream.write(ent.getValue());
                zipOutputStream.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Scan the given classpath elements, and get the names of all classes found.
     *
     * @param classGraph
     *            the configured {@link ClassGraph} instance.
     * @param classpathElements
     *            the classpath elements, in order.
     * @return the names of the classes found.
     */
    private static List<String> classNames(final ClassGraph classGraph, final Path... classpathElements) {
        try (var scanResult = classGraph.enableClassInfo().enableClasspathEntries((Object[]) classpathElements)
                .scan()) {
            return scanResult.getAllClasses().getNames();
        }
    }

    /**
     * A file at {@code x.Y.class} next to the real {@code x/Y.class} is listed as a resource, but not scanned as a
     * classfile, so the scan finds the one class {@code x.Y}, whichever order the two are found in, and whether
     * they are in a directory, in a jarfile, or in two classpath elements.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aClassfileNameWithADotDoesNotCollideWithTheClassOfThatName(@TempDir final Path tempDir)
            throws IOException {
        final var realClassfile = classfile("x/Y", "java/lang/Object");
        final var junkFirst = new LinkedHashMap<String, byte[]>();
        junkFirst.put("x.Y.class", JUNK);
        junkFirst.put("x/Y.class", realClassfile);
        final var realFirst = new LinkedHashMap<String, byte[]>();
        realFirst.put("x/Y.class", realClassfile);
        realFirst.put("x.Y.class", JUNK);

        final var dir = writeDir(tempDir.resolve("dir"), junkFirst);
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspathEntries(dir).scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly("x.Y");
            assertThat(scanResult.getResourcesWithPath("x.Y.class")).hasSize(1);
        }
        assertThat(classNames(new ClassGraph(), writeJar(tempDir.resolve("junkFirst.jar"), junkFirst)))
                .containsExactly("x.Y");
        assertThat(classNames(new ClassGraph(), writeJar(tempDir.resolve("realFirst.jar"), realFirst)))
                .containsExactly("x.Y");

        final var junkDir = writeDir(tempDir.resolve("junk"), Map.of("x.Y.class", JUNK));
        final var realDir = writeDir(tempDir.resolve("real"), Map.of("x/Y.class", realClassfile));
        assertThat(classNames(new ClassGraph(), junkDir, realDir)).containsExactly("x.Y");
        assertThat(classNames(new ClassGraph(), realDir, junkDir)).containsExactly("x.Y");
    }

    /**
     * A file at {@code x.Y.class} in an accepted package does not stop the scan from reaching the real class
     * {@code x.Y} in a package that is not accepted, when an accepted class extends it.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aClassfileNameWithADotDoesNotHideAnExternalSuperclass(@TempDir final Path tempDir)
            throws IOException {
        final var dir = writeDir(tempDir, Map.of( //
                "x/Y.class", classfile("x/Y", "java/lang/Object", "java/io/Serializable"), //
                "a/A.class", classfile("a/A", "x/Y"), //
                "x.Y.class", JUNK));
        try (var scanResult = new ClassGraph().enableClassInfo().enableExternalClasses()
                .acceptPackagesNonRecursive("", "a").enableClasspathEntries(dir).scan()) {
            final var classInfo = scanResult.getClassInfo("a.A");
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getAllSuperinterfaces().getNames()).containsExactly("java.io.Serializable");
        }
    }

    /**
     * Classes in packages whose names are not Java identifiers are loaded by the JVM, and found by a scan of a
     * directory or a jarfile, including a scan that accepts only that package.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws Exception
     *             if a file could not be written, or a class could not be loaded.
     */
    @Test
    public void classesInPackagesThatAreNotJavaIdentifiersAreScanned(@TempDir final Path tempDir) throws Exception {
        final Map<String, byte[]> files = Map.of( //
                "my-pkg/Foo.class", classfile("my-pkg/Foo", "java/lang/Object"), //
                "1pkg/Bar.class", classfile("1pkg/Bar", "java/lang/Object"));
        final var dir = writeDir(tempDir.resolve("dir"), files);
        final var jar = writeJar(tempDir.resolve("classes.jar"), files);

        try (var classLoader = new URLClassLoader(new URL[] { dir.toUri().toURL() }, null)) {
            assertThat(Class.forName("my-pkg.Foo", false, classLoader).getName()).isEqualTo("my-pkg.Foo");
            assertThat(Class.forName("1pkg.Bar", false, classLoader).getName()).isEqualTo("1pkg.Bar");
        }

        for (final var classpathElement : List.of(dir, jar)) {
            assertThat(classNames(new ClassGraph(), classpathElement)).as(classpathElement.toString())
                    .containsExactlyInAnyOrder("my-pkg.Foo", "1pkg.Bar");
            assertThat(classNames(new ClassGraph().acceptPackages("my-pkg"), classpathElement))
                    .as(classpathElement.toString()).containsExactly("my-pkg.Foo");
        }
    }
}
