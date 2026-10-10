package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An array class cannot be the superclass, an interface, a nested class or an enclosing class of a class. The JVM
 * refuses to load a class whose superclass, interface or {@code InnerClasses} outer class is an array class, but it
 * loads a class whose {@code InnerClasses} inner class or {@code EnclosingMethod} class is one. A scan skips the
 * first kind of class, and ignores the array class in the second. Neither fails the scan, even when the array's
 * element class has a name that a type signature cannot express: JVMS 4.2.1 allows a class to be named
 * {@code a<}.
 */
public class ArrayClassInClassfileTest {
    /** A classloader that defines a class from the bytes of its classfile. */
    private static final class ByteClassLoader extends ClassLoader {
        /**
         * Define a class.
         *
         * @param classfile
         *            the bytes of the classfile.
         * @return the class.
         */
        Class<?> define(final byte[] classfile) {
            return defineClass(null, classfile, 0, classfile.length);
        }
    }

    /**
     * Write a minimal classfile with no fields or methods.
     *
     * @param internalName
     *            the name of the class, with {@code '/'} as the separator.
     * @param superclassName
     *            the name of the superclass, as it is written in a classfile.
     * @param interfaceName
     *            the name of the interface the class implements, as it is written in a classfile, or null for none.
     * @param attributeName
     *            {@code "InnerClasses"}, {@code "EnclosingMethod"}, or null for no class attribute.
     * @param attributeClassNames
     *            for {@code InnerClasses}, the names of the inner and the outer class of its one entry; for
     *            {@code EnclosingMethod}, the name of the enclosing class.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfile(final String internalName, final String superclassName,
            final String interfaceName, final String attributeName, final String... attributeClassNames)
            throws IOException {
        // A CONSTANT_Utf8 and a CONSTANT_Class for each class name, so the class ref for name i is at 2 + 2i
        final List<String> classNames = new ArrayList<>(Arrays.asList(internalName, superclassName));
        if (interfaceName != null) {
            classNames.add(interfaceName);
        }
        final int firstAttributeClassIdx = classNames.size();
        classNames.addAll(Arrays.asList(attributeClassNames));
        final int attributeNameCpIdx = 1 + 2 * classNames.size();
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(attributeNameCpIdx + (attributeName == null ? 0 : 1)); // Constant pool count
            for (int i = 0; i < classNames.size(); i++) {
                out.writeByte(1); // #1 + 2i
                out.writeUTF(classNames.get(i));
                out.writeByte(7); // #2 + 2i: class ref to #1 + 2i
                out.writeShort(1 + 2 * i);
            }
            if (attributeName != null) {
                out.writeByte(1);
                out.writeUTF(attributeName);
            }
            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            if (interfaceName != null) {
                out.writeShort(1);
                out.writeShort(6);
            } else {
                out.writeShort(0);
            }
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods
            if (attributeName == null) {
                out.writeShort(0); // Number of attributes
            } else {
                out.writeShort(1);
                out.writeShort(attributeNameCpIdx);
                if (attributeName.equals("InnerClasses")) {
                    out.writeInt(2 + 8);
                    out.writeShort(1); // number_of_classes
                    out.writeShort(2 + 2 * firstAttributeClassIdx); // inner_class_info_index
                    out.writeShort(2 + 2 * (firstAttributeClassIdx + 1)); // outer_class_info_index
                    out.writeShort(1); // inner_name_index (any CONSTANT_Utf8)
                    out.writeShort(0x0009); // ACC_PUBLIC | ACC_STATIC
                } else {
                    out.writeInt(4);
                    out.writeShort(2 + 2 * firstAttributeClassIdx); // class_index
                    out.writeShort(0); // method_index: declared in an initializer
                }
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Write a classpath directory holding the class {@code p.Good} and the given classfile for {@code p.Bad}.
     *
     * @param dir
     *            the directory.
     * @param badClassfile
     *            the classfile for {@code p.Bad}.
     * @return the directory.
     * @throws IOException
     *             if a file could not be written.
     */
    private static Path writeClasses(final Path dir, final byte[] badClassfile) throws IOException {
        Files.createDirectories(dir.resolve("p"));
        Files.write(dir.resolve("p/Good.class"), classfile("p/Good", "java/lang/Object", null, null));
        Files.write(dir.resolve("p/Bad.class"), badClassfile);
        return dir;
    }

    /**
     * A class whose superclass, interface or {@code InnerClasses} outer class is an array class is skipped, as the
     * JVM refuses to load it. The scan still finds the other classes, whether or not the element class has a name
     * that a type signature can express.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aClassWithAnArraySuperclassInterfaceOrOuterClassIsSkipped(@TempDir final Path tempDir)
            throws IOException {
        final List<byte[]> badClassfiles = Arrays.asList( //
                classfile("p/Bad", "[La<;", null, null), //
                classfile("p/Bad", "[I", null, null), //
                classfile("p/Bad", "[Ljava/lang/Object;", null, null), //
                classfile("p/Bad", "java/lang/Object", "[La<;", null), //
                classfile("p/Bad", "java/lang/Object", "[I", null), //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "p/Bad", "[La<;"), //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "p/Bad", "[Ljava/lang/String;"));
        for (int i = 0; i < badClassfiles.size(); i++) {
            final byte[] badClassfile = badClassfiles.get(i);
            assertThatThrownBy(() -> new ByteClassLoader().define(badClassfile)).as("classfile " + i)
                    .isInstanceOf(ClassFormatError.class);
            final Path dir = writeClasses(tempDir.resolve("dir" + i), badClassfile);
            try (ScanResult scanResult = new ClassGraph().enableClassInfo().overrideClasspath(dir.toString())
                    .scan()) {
                assertThat(scanResult.getAllClasses().getNames()).as("classfile " + i).containsExactly("p.Good");
            }
        }
    }

    /**
     * An {@code InnerClasses} entry whose inner class is an array class, or an {@code EnclosingMethod} attribute
     * that names an array class, is ignored. The JVM loads the class, and so does the scan, and the array class is
     * neither nested in the class nor encloses it.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void anInnerClassesOrEnclosingMethodEntryThatNamesAnArrayClassIsIgnored(@TempDir final Path tempDir)
            throws IOException {
        final List<byte[]> badClassfiles = Arrays.asList( //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "[La<;", "p/Bad"), //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "[Ljava/lang/String;", "p/Bad"), //
                classfile("p/Bad", "java/lang/Object", null, "EnclosingMethod", "[La<;"), //
                classfile("p/Bad", "java/lang/Object", null, "EnclosingMethod", "[Ljava/lang/String;"));
        for (int i = 0; i < badClassfiles.size(); i++) {
            final byte[] badClassfile = badClassfiles.get(i);
            assertThat(new ByteClassLoader().define(badClassfile).getName()).as("classfile " + i)
                    .isEqualTo("p.Bad");
            final Path dir = writeClasses(tempDir.resolve("dir" + i), badClassfile);
            try (ScanResult scanResult = new ClassGraph().enableClassInfo().overrideClasspath(dir.toString())
                    .scan()) {
                assertThat(scanResult.getAllClasses().getNames()).as("classfile " + i)
                        .containsExactlyInAnyOrder("p.Bad", "p.Good");
                final ClassInfo classInfo = scanResult.getClassInfo("p.Bad");
                assertThat(classInfo).isNotNull();
                assertThat(classInfo.getInnerClasses()).as("classfile " + i).isEmpty();
                assertThat(classInfo.getOuterClasses()).as("classfile " + i).isEmpty();
            }
        }
    }

    /**
     * The class info for an array class has an element type that names the whole element class, whether or not that
     * name can be written in a type signature.
     */
    @Test
    public void anArrayOfAClassWhoseNameATypeSignatureCannotExpressHasAClassInfo() {
        final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
        for (final String elementClassName : Arrays.asList("a<", "a>", "a:b", "p.a<$b")) {
            final ClassInfo classInfo = ClassInfo
                    .getOrCreateClassInfo("[L" + elementClassName.replace('.', '/') + ";", classNameToClassInfo);
            assertThat(classInfo).isInstanceOf(ArrayClassInfo.class);
            assertThat(classInfo.getName()).isEqualTo(elementClassName + "[]");
            final TypeSignature elementTypeSignature = ((ArrayClassInfo) classInfo).getElementTypeSignature();
            assertThat(((ClassRefTypeSignature) elementTypeSignature).getFullyQualifiedClassName())
                    .isEqualTo(elementClassName);
        }
    }

    /**
     * An array of a nested class still has an element type that is split into its parts, as in a type signature.
     */
    @Test
    public void anArrayOfANestedClassHasTheElementTypeOfATypeSignature() {
        final ClassInfo classInfo = ClassInfo.getOrCreateClassInfo("[Lp/Outer$Inner;",
                new HashMap<String, ClassInfo>());
        final ClassRefTypeSignature elementTypeSignature = (ClassRefTypeSignature) ((ArrayClassInfo) classInfo)
                .getElementTypeSignature();
        assertThat(elementTypeSignature.getBaseClassName()).isEqualTo("p.Outer");
        assertThat(elementTypeSignature.getSuffixes()).containsExactly("Inner");
        assertThat(elementTypeSignature.getFullyQualifiedClassName()).isEqualTo("p.Outer$Inner");
    }
}
