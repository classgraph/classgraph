package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bits of a class's {@code access_flags} that JVMS 4.1 does not define, and the bits of an {@code InnerClasses}
 * entry's {@code inner_class_access_flags} that JVMS 4.7.6 does not define, are reserved, and the JVM ignores them.
 * They must not appear in the class's modifiers.
 */
public class ReservedAccessFlagsTest {
    /**
     * An entry of an {@code InnerClasses} attribute.
     *
     * @param innerClass
     *            the internal name of the nested class.
     * @param outerClass
     *            the internal name of its enclosing class.
     * @param accessFlags
     *            the {@code inner_class_access_flags} of the entry.
     */
    private record InnerClassEntry(String innerClass, String outerClass, int accessFlags) {
    }

    /**
     * Write a classfile for a class that extends {@code java.lang.Object} and has no fields or methods.
     *
     * @param internalName
     *            the name of the class, with {@code '/'} as the separator.
     * @param accessFlags
     *            the {@code access_flags} of the class.
     * @param entries
     *            the entries of the {@code InnerClasses} attribute, which is written only if there are any.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfile(final String internalName, final int accessFlags,
            final InnerClassEntry... entries) throws IOException {
        // Each class name i has a CONSTANT_Utf8 entry at 2i + 1 and a CONSTANT_Class entry at 2i + 2
        final List<String> classNames = new ArrayList<>(List.of(internalName, "java/lang/Object"));
        for (final var entry : entries) {
            for (final var name : List.of(entry.innerClass(), entry.outerClass())) {
                if (!classNames.contains(name)) {
                    classNames.add(name);
                }
            }
        }
        final var innerClassesIdx = 2 * classNames.size() + 1;
        final var simpleNameIdx = innerClassesIdx + 1;
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(simpleNameIdx + 1); // Constant pool count
            for (var i = 0; i < classNames.size(); i++) {
                out.writeByte(1);
                out.writeUTF(classNames.get(i));
                out.writeByte(7);
                out.writeShort(2 * i + 1);
            }
            out.writeByte(1);
            out.writeUTF("InnerClasses");
            out.writeByte(1);
            out.writeUTF("N");
            out.writeShort(accessFlags);
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods
            if (entries.length == 0) {
                out.writeShort(0); // Number of attributes
            } else {
                out.writeShort(1); // Number of attributes
                out.writeShort(innerClassesIdx);
                out.writeInt(2 + 8 * entries.length);
                out.writeShort(entries.length);
                for (final var entry : entries) {
                    out.writeShort(2 * classNames.indexOf(entry.innerClass()) + 2);
                    out.writeShort(2 * classNames.indexOf(entry.outerClass()) + 2);
                    out.writeShort(simpleNameIdx);
                    out.writeShort(entry.accessFlags());
                }
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * The reserved bits are dropped from the access flags of a top-level class, from the access flags of a nested
     * class with an {@code InnerClasses} entry of its own, and from the {@code InnerClasses} entries that give the
     * modifiers of a nested class that is not scanned.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void reservedAccessFlagBitsAreNotModifiers(@TempDir final Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("p"));
        // ACC_PUBLIC | ACC_SUPER, with the reserved bits 0x0002, 0x0004, 0x0008, 0x0040, 0x0080, 0x0100, 0x0800
        Files.write(tempDir.resolve("p/Top.class"), classfile("p/Top", 0x09EF));
        // ACC_FINAL | ACC_SUPER, with the reserved bit 0x0002, as in the java.lang.invoke.*$Holder classes
        Files.write(tempDir.resolve("p/Holder.class"), classfile("p/Holder", 0x0032));
        // A nested class whose access_flags set the reserved bit 0x0008, and whose own InnerClasses entry is
        // ACC_PRIVATE with the reserved bits 0x0020, 0x0040, 0x0080 and 0x0800
        Files.write(tempDir.resolve("p/Outer$In.class"),
                classfile("p/Outer$In", 0x0029, new InnerClassEntry("p/Outer$In", "p/Outer", 0x08E2)));
        // A class whose InnerClasses attribute gives the modifiers of a nested class of its own that is not
        // scanned (ACC_PUBLIC | ACC_STATIC, with the reserved bits 0x0080 and 0x0800), and of a nested class of
        // another class that is not scanned (ACC_PROTECTED, with the reserved bit 0x0100)
        Files.write(tempDir.resolve("p/Outer.class"),
                classfile("p/Outer", 0x0021, new InnerClassEntry("p/Outer$In", "p/Outer", 0x08E2),
                        new InnerClassEntry("p/Outer$Ext", "p/Outer", 0x0889),
                        new InnerClassEntry("q/Lib$Nested", "q/Lib", 0x0104)));
        // p/Outer only names q/Lib$Nested, so q.Lib$Nested is in the class graph only if dependencies are enabled
        try (var scanResult = new ClassGraph().enableClassInfo().ignoreClassVisibility()
                .enableInterClassDependencies().enableClasspathEntries(tempDir).scan()) {
            final var top = scanResult.getClassInfo("p.Top");
            assertThat(top).isNotNull();
            assertThat(top.getModifiers()).isEqualTo(0x0001);
            assertThat(top.getModifiersString()).isEqualTo("public");
            assertThat(top.isPrivate()).isFalse();
            assertThat(top.isProtected()).isFalse();
            assertThat(top.isStatic()).isFalse();

            final var holder = scanResult.getClassInfo("p.Holder");
            assertThat(holder).isNotNull();
            assertThat(holder.getModifiers()).isEqualTo(0x0010);
            assertThat(holder.getModifiersString()).isEqualTo("final");
            assertThat(holder.isPrivate()).isFalse();

            final var in = scanResult.getClassInfo("p.Outer$In");
            assertThat(in).isNotNull();
            assertThat(in.getModifiers()).isEqualTo(0x0002);
            assertThat(in.getModifiersString()).isEqualTo("private");
            assertThat(in.isStatic()).isFalse();

            final var ext = scanResult.getClassInfo("p.Outer$Ext");
            assertThat(ext).isNotNull();
            assertThat(ext.getModifiers()).isEqualTo(0x0009);
            assertThat(ext.getModifiersString()).isEqualTo("public static");

            final var nested = scanResult.getClassInfo("q.Lib$Nested");
            assertThat(nested).isNotNull();
            assertThat(nested.getModifiers()).isEqualTo(0x0004);
            assertThat(nested.getModifiersString()).isEqualTo("protected");
        }
    }
}
