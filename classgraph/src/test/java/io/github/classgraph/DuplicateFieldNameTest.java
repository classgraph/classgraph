package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A classfile may declare two fields with the same name if their types differ (JVMS 4.5 forbids only two fields
 * with the same name and the same descriptor). javac never does this, but obfuscators do. Both fields are distinct
 * fields: neither hides the other, and their {@link FieldInfo} objects are not equal. A field still hides a field
 * of the same name in a superclass, whatever the types of the two fields.
 */
public class DuplicateFieldNameTest {
    /** The name of the superclass. */
    private static final String SUPERCLASS_NAME = "dup.Super";

    /** The name of the class that declares two fields named {@code x}. */
    private static final String CLASS_NAME = "dup.X";

    /**
     * Generate a public class with public fields.
     *
     * @param className
     *            the name of the class
     * @param superclassName
     *            the name of the superclass
     * @param fields
     *            the name and type descriptor of each field, in the order to write them
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes(final String className, final String superclassName,
            final List<String[]> fields) throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            // Constant pool count (one greater than the number of entries)
            out.writeShort(5 + 2 * fields.size());
            // #1
            out.writeByte(1);
            out.writeUTF(className.replace('.', '/'));
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF(superclassName.replace('.', '/'));
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5 onward: the name and the descriptor of each field
            for (final String[] field : fields) {
                out.writeByte(1);
                out.writeUTF(field[0]);
                out.writeByte(1);
                out.writeUTF(field[1]);
            }

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces

            out.writeShort(fields.size()); // Number of fields
            for (var i = 0; i < fields.size(); i++) {
                out.writeShort(0x0001); // ACC_PUBLIC
                out.writeShort(5 + 2 * i); // name_index
                out.writeShort(6 + 2 * i); // descriptor_index
                out.writeShort(0); // attributes_count
            }

            out.writeShort(0); // Number of methods
            out.writeShort(0); // Number of attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Generate the superclass, which declares {@code long x} and {@code int z}.
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] superclassBytes() throws IOException {
        return classfileBytes(SUPERCLASS_NAME, "java.lang.Object",
                List.of(new String[] { "x", "J" }, new String[] { "z", "I" }));
    }

    /**
     * Generate the class, which extends the superclass and declares {@code String x} and {@code int x}, in that
     * order.
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classBytes() throws IOException {
        return classfileBytes(CLASS_NAME, SUPERCLASS_NAME,
                List.of(new String[] { "x", "Ljava/lang/String;" }, new String[] { "x", "I" }));
    }

    /** A class loader that defines the two generated classes. */
    private static class GeneratedClassLoader extends ClassLoader {
        /**
         * Define the two generated classes.
         *
         * @return the class that declares two fields named {@code x}
         * @throws IOException
         *             if a classfile could not be written
         */
        Class<?> defineClasses() throws IOException {
            final var superclassBytes = superclassBytes();
            defineClass(SUPERCLASS_NAME, superclassBytes, 0, superclassBytes.length);
            final var classBytes = classBytes();
            return defineClass(CLASS_NAME, classBytes, 0, classBytes.length);
        }
    }

    /**
     * The JVM loads the generated class, and reflection reports both of its fields named {@code x}.
     *
     * @throws Exception
     *             if the class could not be loaded
     */
    @Test
    public void theJvmLoadsBothFields() throws Exception {
        final var cls = new GeneratedClassLoader().defineClasses();
        assertThat(cls.getDeclaredFields()).hasSize(2);
        assertThat(cls.getFields()).hasSize(4);
    }

    /**
     * Scan the generated classes.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @return the scan result
     * @throws IOException
     *             if a classfile could not be written
     */
    private static ScanResult scan(final File tempDir) throws IOException {
        final var packageDir = new File(tempDir, "dup");
        Files.createDirectories(packageDir.toPath());
        Files.write(new File(packageDir, "Super.class").toPath(), superclassBytes());
        Files.write(new File(packageDir, "X.class").toPath(), classBytes());
        return new ClassGraph().enableClassInfo().enableFieldInfo().enableClasspathEntries(tempDir).scan();
    }

    /**
     * The two fields named {@code x} are distinct: they are not equal, they do not compare as equal, and a set
     * keeps both of them.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void fieldsWithOneNameAndDifferentTypesAreNotEqual(@TempDir final File tempDir) throws IOException {
        try (var scanResult = scan(tempDir)) {
            final var declaredFields = scanResult.getClassInfo(CLASS_NAME).getDeclaredFieldInfo();
            assertThat(declaredFields).hasSize(2);
            final var first = declaredFields.get(0);
            final var second = declaredFields.get(1);
            assertThat(first.getName()).isEqualTo("x");
            assertThat(second.getName()).isEqualTo("x");
            assertThat(first).isNotEqualTo(second);
            assertThat(first.compareTo(second)).isNotZero();
            assertThat(Integer.signum(first.compareTo(second))).isEqualTo(-Integer.signum(second.compareTo(first)));
            assertThat(new HashSet<>(declaredFields)).hasSize(2);
        }
    }

    /**
     * {@link ClassInfo#getFieldInfo()} returns both fields named {@code x} of the class, since neither hides the
     * other, and leaves out the superclass's field named {@code x}, which they hide. The fields of one class are
     * sorted by name, then by type descriptor, and a lookup by name returns the first of them.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void getFieldInfoKeepsBothFieldsOfTheClass(@TempDir final File tempDir) throws IOException {
        try (var scanResult = scan(tempDir)) {
            final var classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo.getDeclaredFieldInfo()).extracting(FieldInfo::getTypeDescriptorString)
                    .containsExactly("I", "Ljava/lang/String;");
            final var fields = classInfo.getFieldInfo();
            assertThat(fields)
                    .extracting(fi -> fi.getClassName() + "." + fi.getName() + ":" + fi.getTypeDescriptorString())
                    .containsExactly("dup.X.x:I", "dup.X.x:Ljava/lang/String;", "dup.Super.z:I");
            assertThat(classInfo.getFieldInfo("x")).isSameAs(classInfo.getDeclaredFieldInfo().get(0));
            assertThat(classInfo.getDeclaredFieldInfo("x")).isSameAs(classInfo.getDeclaredFieldInfo().get(0));
            assertThat(classInfo.getFieldInfo().get("x")).isSameAs(classInfo.getDeclaredFieldInfo().get(0));
        }
    }
}
