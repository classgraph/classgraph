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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * A class name in a classfile is a sequence of segments separated by {@code '/'}, none of them empty, and none of
 * them containing {@code '.'}, {@code ';'} or {@code '['} (JVMS 4.2.1). A classfile that breaks this rule must not
 * make the scan fail, or give a class with an empty name. The JVM refuses to load a class that uses such a name as
 * its superclass, an interface, or a nested or enclosing class (JDK 8 checks only for an empty name, ';' and '[',
 * not for an empty segment), and the scan skips that class. A name that only records a dependency is skipped, and
 * the class is kept. A class whose annotation type has an illegal name, which the JVM does not check, is skipped.
 */
public class IllegalClassNameInClassfileTest {
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
     * Write a minimal classfile for an abstract class.
     *
     * @param internalName
     *            the name of the class, with {@code '/'} as the separator.
     * @param superclassName
     *            the name of the superclass, as it is written in a classfile.
     * @param interfaceName
     *            the name of the interface the class implements, or null for none.
     * @param attributeName
     *            {@code "InnerClasses"}, {@code "EnclosingMethod"}, {@code "RuntimeVisibleAnnotations"},
     *            {@code "Exceptions"}, or null for no attribute.
     * @param attributeNames
     *            for {@code InnerClasses}, the names of the inner and the outer class of its one entry; for
     *            {@code EnclosingMethod}, the name of the enclosing class; for {@code RuntimeVisibleAnnotations},
     *            the type descriptor of its one annotation; for {@code Exceptions}, the name of the exception class
     *            thrown by the method {@code m()}. For no attribute, the names held by {@code CONSTANT_Class}
     *            entries that nothing refers to.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfile(final String internalName, final String superclassName,
            final String interfaceName, final String attributeName, final String... attributeNames)
            throws IOException {
        final boolean isAnnotation = "RuntimeVisibleAnnotations".equals(attributeName);
        final boolean isExceptions = "Exceptions".equals(attributeName);
        // A CONSTANT_Utf8 and a CONSTANT_Class for each class name, so the class ref for name i is at 2 + 2i
        final List<String> classNames = new ArrayList<>(Arrays.asList(internalName, superclassName));
        if (interfaceName != null) {
            classNames.add(interfaceName);
        }
        final int firstAttributeClassIdx = classNames.size();
        if (!isAnnotation) {
            classNames.addAll(Arrays.asList(attributeNames));
        }
        // Then CONSTANT_Utf8 entries for the annotation type descriptor, or for the method name and descriptor,
        // and for the attribute name
        final List<String> strings = new ArrayList<>();
        if (isAnnotation) {
            strings.add(attributeNames[0]);
        } else if (isExceptions) {
            strings.addAll(Arrays.asList("m", "()V"));
        }
        if (attributeName != null) {
            strings.add(attributeName);
        }
        final int firstStringCpIdx = 1 + 2 * classNames.size();
        final int attributeNameCpIdx = firstStringCpIdx + strings.size() - 1;
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(firstStringCpIdx + strings.size()); // Constant pool count
            for (int i = 0; i < classNames.size(); i++) {
                out.writeByte(1); // #1 + 2i
                out.writeUTF(classNames.get(i));
                out.writeByte(7); // #2 + 2i: class ref to #1 + 2i
                out.writeShort(1 + 2 * i);
            }
            for (final String string : strings) {
                out.writeByte(1);
                out.writeUTF(string);
            }
            out.writeShort(0x0421); // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            if (interfaceName != null) {
                out.writeShort(1);
                out.writeShort(6);
            } else {
                out.writeShort(0);
            }
            out.writeShort(0); // Number of fields
            if (isExceptions) {
                out.writeShort(1); // Number of methods
                out.writeShort(0x0401); // ACC_PUBLIC | ACC_ABSTRACT
                out.writeShort(firstStringCpIdx); // "m"
                out.writeShort(firstStringCpIdx + 1); // "()V"
                out.writeShort(1); // attributes_count
                out.writeShort(attributeNameCpIdx);
                out.writeInt(2 + 2);
                out.writeShort(1); // number_of_exceptions
                out.writeShort(2 + 2 * firstAttributeClassIdx);
                out.writeShort(0); // Number of attributes
            } else {
                out.writeShort(0); // Number of methods
                if (attributeName == null) {
                    out.writeShort(0); // Number of attributes
                } else {
                    out.writeShort(1);
                    out.writeShort(attributeNameCpIdx);
                    if (isAnnotation) {
                        out.writeInt(2 + 4);
                        out.writeShort(1); // num_annotations
                        out.writeShort(firstStringCpIdx); // type_index
                        out.writeShort(0); // num_element_value_pairs
                    } else if (attributeName.equals("InnerClasses")) {
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
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Write a classpath directory holding the classfile of {@code p.Bad}, and the class {@code p.Good}, which
     * extends {@code java.lang.Object} and has no attributes.
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
     * A class whose superclass, interface, or {@code InnerClasses} or {@code EnclosingMethod} class has an illegal
     * name is skipped, as the JVM refuses to load it, and the scan still finds the other classes.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aClassWhoseSuperclassInterfaceNestedOrEnclosingClassHasAnIllegalNameIsSkipped(
            @TempDir final Path tempDir) throws IOException {
        final List<byte[]> badClassfiles = Arrays.asList( //
                classfile("p/Bad", "p/S;", null, null), //
                classfile("p/Bad", "Lp/S;", null, null), //
                classfile("p/Bad", "", null, null), //
                classfile("p/Bad", "java/lang/Object", "p/I;", null), //
                classfile("p/Bad", "java/lang/Object", "", null), //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "p/Bad", "p/X;"), //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "p/Bad$I;", "p/Bad"), //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "", "p/Bad"), //
                classfile("p/Bad", "java/lang/Object", null, "EnclosingMethod", "p/E;"), //
                // JDK 8 does not check for empty segments, but JDK 9 and later throw ClassFormatError for them
                classfile("p/Bad", "p//S", null, null), //
                classfile("p/Bad", "/S", null, null), //
                classfile("p/Bad", "S/", null, null), //
                classfile("p/Bad", "java/lang/Object", null, "EnclosingMethod", "p//E"));
        final int firstEmptySegmentIdx = 9;
        for (int i = 0; i < badClassfiles.size(); i++) {
            final byte[] badClassfile = badClassfiles.get(i);
            if (i < firstEmptySegmentIdx || VersionFinder.JAVA_MAJOR_VERSION >= 9) {
                assertThatThrownBy(() -> new ByteClassLoader().define(badClassfile)).as("classfile " + i)
                        .isInstanceOf(ClassFormatError.class);
            }
            final Path dir = writeClasses(tempDir.resolve("dir" + i), badClassfile);
            try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableExternalClasses()
                    .overrideClasspath(dir.toString()).scan()) {
                assertThat(scanResult.getAllClasses().getNames()).as("classfile " + i)
                        .containsExactly("p.Good");
            }
        }
    }

    /**
     * A class with a {@code CONSTANT_Class} entry with an illegal name, which only records a dependency, is kept in
     * a scan that finds the dependencies of each class, and the name is not recorded as a dependency. This includes
     * an array class with more than 255 dimensions. An array class whose element class name the type signature
     * parser cannot read, but which is legal, records its element class. Whether a class is in the scan result does
     * not depend on whether dependencies are found.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aClassReferenceWithAnIllegalNameIsNotRecordedAsADependency(@TempDir final Path tempDir)
            throws IOException {
        final StringBuilder tooManyDims = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            tooManyDims.append('[');
        }
        tooManyDims.append("Lp/S;");
        final List<byte[]> badClassfiles = Arrays.asList( //
                classfile("p/Bad", "java/lang/Object", null, null, "Q;", "", "p//S", "java/lang/String"), //
                classfile("p/Bad", "java/lang/Object", null, null, "[Lp/S;;", "[L/S;", tooManyDims.toString(),
                        "[Lp/A<x>;", "java/lang/String"));
        final List<List<String>> dependencies = Arrays.asList( //
                Arrays.asList("java.lang.String"), //
                Arrays.asList("java.lang.String", "p.A<x>"));
        for (int i = 0; i < badClassfiles.size(); i++) {
            final Path dir = writeClasses(tempDir.resolve("dir" + i), badClassfiles.get(i));
            try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableInterClassDependencies()
                    .enableExternalClasses().overrideClasspath(dir.toString()).scan()) {
                final List<String> classNames = new ArrayList<>(dependencies.get(i));
                classNames.addAll(Arrays.asList("p.Bad", "p.Good", "java.lang.Object"));
                assertThat(scanResult.getAllClasses().getNames()).as("classfile " + i)
                        .containsExactlyInAnyOrderElementsOf(classNames);
                assertThat(scanResult.getClassInfo("p.Bad").getClassDependencies().getNames())
                        .as("classfile " + i).containsExactlyInAnyOrderElementsOf(dependencies.get(i));
            }
        }
    }

    /**
     * A class whose annotation type has an illegal name is skipped, and the scan does not fail. The JVM loads such a
     * class, since it does not check annotation type names, but the classfile reader cannot skip one annotation on
     * its own. No class is created for the annotation type, so in particular no class has an empty name.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aClassWithAnAnnotationWhoseTypeHasAnIllegalNameIsSkipped(@TempDir final Path tempDir)
            throws IOException {
        final List<byte[]> badClassfiles = Arrays.asList( //
                classfile("p/Bad", "java/lang/Object", null, "RuntimeVisibleAnnotations", "L;"), //
                classfile("p/Bad", "java/lang/Object", null, "RuntimeVisibleAnnotations", "Lp/A;;"), //
                classfile("p/Bad", "java/lang/Object", null, "RuntimeVisibleAnnotations", "Lp//A;"));
        for (int i = 0; i < badClassfiles.size(); i++) {
            final byte[] badClassfile = badClassfiles.get(i);
            assertThat(new ByteClassLoader().define(badClassfile).getName()).as("classfile " + i)
                    .isEqualTo("p.Bad");
            final Path dir = writeClasses(tempDir.resolve("dir" + i), badClassfile);
            try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableAnnotationInfo()
                    .enableExternalClasses().overrideClasspath(dir.toString()).scan()) {
                assertThat(scanResult.getAllClasses().getNames()).as("classfile " + i)
                        .containsExactly("p.Good");
            }
        }
    }

    /**
     * The name of a thrown exception class is only looked up, and no class is created for it, so a method whose
     * {@code Exceptions} attribute names a class with an illegal name keeps its class, and the name is kept as it
     * is in the classfile.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aThrownExceptionWithAnIllegalNameKeepsItsClass(@TempDir final Path tempDir) throws IOException {
        final Path dir = writeClasses(tempDir,
                classfile("p/Bad", "java/lang/Object", null, "Exceptions", "Q;"));
        try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableExternalClasses()
                .overrideClasspath(dir.toString()).scan()) {
            final MethodInfo methodInfo = scanResult.getClassInfo("p.Bad").getMethodInfo("m").get(0);
            assertThat(methodInfo.getThrownExceptionNames()).containsExactly("Q;");
            assertThat(methodInfo.getThrownExceptions()).isEmpty();
        }
    }
}
