package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A class that refers to an array of a class whose name starts with '$' (javac writes a {@code CONSTANT_Class}
 * entry {@code [L$Foo;} for a cast to {@code $Foo[]}), or of a class whose name contains a character that a type
 * signature cannot hold, is still scanned, and the element class is one of its dependencies.
 */
public class LeadingDollarClassNameTest {
    /**
     * Generate a minimal public class {@code Holder} whose constant pool holds the array class references
     * {@code [L$Foo;}, {@code [[Lp/A<b;} and {@code [I}, and which has a field {@code f} of type {@code $Foo[]}.
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(13); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1); // modified UTF8: binary class name of the scanned class
            out.writeUTF("Holder");
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1); // modified UTF8: binary class name of the superclass
            out.writeUTF("java/lang/Object");
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5
            out.writeByte(1); // modified UTF8: an array of a class whose name starts with '$'
            out.writeUTF("[L$Foo;");
            // #6
            out.writeByte(7); // class ref to #5
            out.writeShort(5);
            // #7
            out.writeByte(1); // modified UTF8: an array of a class whose name contains '<', which the JVM allows
            out.writeUTF("[[Lp/A<b;");
            // #8
            out.writeByte(7); // class ref to #7
            out.writeShort(7);
            // #9
            out.writeByte(1); // modified UTF8: an array of a primitive type, which names no class
            out.writeUTF("[I");
            // #10
            out.writeByte(7); // class ref to #9
            out.writeShort(9);
            // #11
            out.writeByte(1); // modified UTF8: field name
            out.writeUTF("f");
            // #12
            out.writeByte(1); // modified UTF8: field descriptor
            out.writeUTF("[L$Foo;");

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(1); // Number of fields
            out.writeShort(0x0001); // ACC_PUBLIC
            out.writeShort(11); // name_index => "f"
            out.writeShort(12); // descriptor_index => "[L$Foo;"
            out.writeShort(0); // Number of field attributes
            out.writeShort(0); // Number of methods
            out.writeShort(0); // Number of class attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /** The class is scanned, and the element classes of its array class references are its dependencies. */
    @Test
    public void arrayClassRefsNameTheirElementClasses(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, "Holder.class").toPath(), classfileBytes());
        try (var scanResult = new ClassGraph().enableClasspathEntries(tempDir).enableClassInfo().enableFieldInfo()
                .enableInterClassDependencies().enableExternalClasses().scan()) {
            final var classInfo = scanResult.getClassInfo("Holder");
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getClassDependencies().getNames()).contains("$Foo", "p.A<b");
            assertThat(classInfo.getFieldInfo("f").getTypeSignatureOrTypeDescriptor()).hasToString("$Foo[]");
        }
    }
}
