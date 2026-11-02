package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

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
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(52);
            final List<String> utf8s = Arrays.asList("p/Holder", "java/lang/Object", "p/Z", "p/A");
            out.writeShort(1 + 2 * utf8s.size() + 2);
            for (int i = 0; i < utf8s.size(); i++) {
                out.writeByte(1);
                out.writeUTF(utf8s.get(i));
                out.writeByte(7);
                out.writeShort(2 * i + 1);
            }
            final int innerClassesAttributeName = 2 * utf8s.size() + 1;
            out.writeByte(1);
            out.writeUTF("InnerClasses");
            final int innerName = innerClassesAttributeName + 1;
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
            for (final int outerClass : new int[] { 6, 8 }) {
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
        try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableExternalClasses()
                .overrideClasspath(tempDir.toString()).scan()) {
            final ClassInfo inner = scanResult.getClassInfo("p.Holder");
            assertThat(inner).isNotNull();
            assertThat(inner.getOuterClasses().getNames()).containsExactly("p.A", "p.Z");
            assertThat(inner.getOuterClasses().directOnly().getNames()).containsExactly("p.A", "p.Z");
        }
    }
}
