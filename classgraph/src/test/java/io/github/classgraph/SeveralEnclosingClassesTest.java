package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Classfiles can name more than one enclosing class for a class: the Scala compiler names both {@code T} and
 * {@code T$} for an anonymous function. Classfiles are linked in no fixed order, so the enclosing classes of such a
 * class must not be listed in the order they were found.
 */
public class SeveralEnclosingClassesTest {
    /**
     * Write a classfile with an {@code InnerClasses} attribute that has two entries for the class itself, first
     * with the outer class {@code p/Z}, then with the outer class {@code p/A}.
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfile() throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(52);
            final var utf8s = List.of("p/Holder", "java/lang/Object", "p/Z", "p/A");
            out.writeShort(1 + 2 * utf8s.size() + 2);
            for (var i = 0; i < utf8s.size(); i++) {
                out.writeByte(1);
                out.writeUTF(utf8s.get(i));
                out.writeByte(7);
                out.writeShort(2 * i + 1);
            }
            final var innerClassesAttributeName = 2 * utf8s.size() + 1;
            out.writeByte(1);
            out.writeUTF("InnerClasses");
            final var innerName = innerClassesAttributeName + 1;
            out.writeByte(1);
            out.writeUTF("Inner");
            // Public class, this class, superclass, then no interfaces, fields or methods
            out.writeShort(0x21);
            out.writeShort(2);
            out.writeShort(4);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(1);
            out.writeShort(innerClassesAttributeName);
            out.writeInt(2 + 2 * 8);
            out.writeShort(2);
            for (final var outerClass : List.of(6, 8)) {
                out.writeShort(2);
                out.writeShort(outerClass);
                out.writeShort(innerName);
                out.writeShort(0x9);
            }
        }
        return bytes.toByteArray();
    }

    /**
     * The enclosing classes of a class that has several are sorted by name.
     *
     * @param tempDir
     *            the directory to scan.
     * @throws IOException
     *             if the classfile could not be written.
     */
    @Test
    public void severalEnclosingClassesAreSortedByName(@TempDir final Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("p"));
        Files.write(tempDir.resolve("p/Holder.class"), classfile());
        try (var scanResult = new ClassGraph().enableClassInfo().enableExternalClasses()
                .enableClasspathEntries(tempDir).scan()) {
            final var inner = scanResult.getClassInfo("p.Holder");
            assertThat(inner).isNotNull();
            assertThat(inner.enclosingClassesIncludingExternal().getNames()).containsExactly("p.A", "p.Z");
            assertThat(inner.enclosingClassesIncludingExternal().directOnly().getNames()).containsExactly("p.A",
                    "p.Z");
        }
    }

    /**
     * Write a classfile with an {@code InnerClasses} attribute that names {@code p/Nested}, which is not scanned,
     * as a nested class of this class.
     *
     * @param dir
     *            the directory to write the classfile to.
     * @param className
     *            the internal name of the class.
     * @param accessFlags
     *            the access flags of the entry.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static void writeEnclosingClassfile(final Path dir, final String className, final int accessFlags)
            throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(52);
            final var utf8s = List.of(className, "java/lang/Object", "p/Nested");
            out.writeShort(1 + 2 * utf8s.size() + 2);
            for (var i = 0; i < utf8s.size(); i++) {
                out.writeByte(1);
                out.writeUTF(utf8s.get(i));
                out.writeByte(7);
                out.writeShort(2 * i + 1);
            }
            final var innerClassesAttributeName = 2 * utf8s.size() + 1;
            out.writeByte(1);
            out.writeUTF("InnerClasses");
            out.writeByte(1);
            out.writeUTF("Nested");
            // Public class, this class, superclass, then no interfaces, fields or methods
            out.writeShort(0x21);
            out.writeShort(2);
            out.writeShort(4);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(1);
            out.writeShort(innerClassesAttributeName);
            out.writeInt(2 + 8);
            out.writeShort(1);
            out.writeShort(6);
            out.writeShort(2);
            out.writeShort(innerClassesAttributeName + 1);
            out.writeShort(accessFlags);
        }
        final var file = dir.resolve(className + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.toByteArray());
    }

    /**
     * The modifiers of a class that was not scanned do not depend on which of its enclosing classes was linked
     * last.
     *
     * @param tempDir
     *            the directory to scan.
     * @throws IOException
     *             if a classfile could not be written.
     */
    @Test
    public void widestAccessLevelOfSeveralEnclosingClassesWins(@TempDir final Path tempDir) throws IOException {
        final var first = tempDir.resolve("first");
        final var second = tempDir.resolve("second");
        writeEnclosingClassfile(first, "p/A", Modifier.STATIC);
        writeEnclosingClassfile(second, "p/B", Modifier.PUBLIC | Modifier.STATIC);
        for (final var dirs : new Path[][] { { first, second }, { second, first } }) {
            try (var scanResult = new ClassGraph().enableClassInfo().enableExternalClasses().ignoreClassVisibility()
                    .enableClasspathEntries(dirs[0], dirs[1]).scan(1)) {
                assertThat(scanResult.getClassInfo("p.Nested").isPublic()).isTrue();
            }
        }
    }
}
