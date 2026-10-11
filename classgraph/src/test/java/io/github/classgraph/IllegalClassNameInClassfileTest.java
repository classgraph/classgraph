package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.tools.ToolProvider;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A class name in a classfile is a sequence of segments separated by {@code '/'}, none of them empty, and none of
 * them containing {@code '.'}, {@code ';'} or {@code '['} (JVMS 4.2.1). A classfile that breaks this rule must not
 * make the scan fail, or give a class with an empty name. The JVM refuses to load a class that uses such a name as
 * its superclass, an interface, or a nested or enclosing class, and the scan skips that class. A name that only
 * records a dependency, and an annotation whose type has an illegal name, which the JVM does not check, are
 * skipped, and the class is kept.
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
     *            {@code "RuntimeInvisibleAnnotations"}, {@code "Exceptions"},
     *            {@code "RuntimeVisibleParameterAnnotations"}, or null for no attribute.
     * @param attributeNames
     *            for {@code InnerClasses}, the names of the inner and the outer class of its one entry; for
     *            {@code EnclosingMethod}, the name of the enclosing class; for {@code RuntimeVisibleAnnotations}
     *            and {@code RuntimeInvisibleAnnotations}, the type descriptors of its annotations, which have no
     *            elements; for {@code Exceptions}, the name of the exception class thrown by the method
     *            {@code m()}; for {@code RuntimeVisibleParameterAnnotations}, the type descriptors of the
     *            annotations, which have no elements, on the one parameter of the method {@code m(int)}. For no
     *            attribute, the names held by {@code CONSTANT_Class} entries that nothing refers to.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfile(final String internalName, final String superclassName,
            final @Nullable String interfaceName, final @Nullable String attributeName,
            final String... attributeNames) throws IOException {
        final var isAnnotation = "RuntimeVisibleAnnotations".equals(attributeName)
                || "RuntimeInvisibleAnnotations".equals(attributeName);
        final var isExceptions = "Exceptions".equals(attributeName);
        final var isParameterAnnotation = "RuntimeVisibleParameterAnnotations".equals(attributeName);
        // A CONSTANT_Utf8 and a CONSTANT_Class for each class name, so the class ref for name i is at 2 + 2i
        final List<String> classNames = new ArrayList<>(List.of(internalName, superclassName));
        if (interfaceName != null) {
            classNames.add(interfaceName);
        }
        final var firstAttributeClassIdx = classNames.size();
        if (!isAnnotation && !isParameterAnnotation) {
            classNames.addAll(List.of(attributeNames));
        }
        // Then CONSTANT_Utf8 entries for the annotation type descriptors, then for the method name and descriptor,
        // and for the attribute name
        final List<String> strings = new ArrayList<>();
        if (isAnnotation || isParameterAnnotation) {
            strings.addAll(List.of(attributeNames));
        }
        if (isExceptions) {
            strings.addAll(List.of("m", "()V"));
        } else if (isParameterAnnotation) {
            strings.addAll(List.of("m", "(I)V"));
        }
        if (attributeName != null) {
            strings.add(attributeName);
        }
        final var firstStringCpIdx = 1 + 2 * classNames.size();
        final var attributeNameCpIdx = firstStringCpIdx + strings.size() - 1;
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(firstStringCpIdx + strings.size()); // Constant pool count
            for (var i = 0; i < classNames.size(); i++) {
                out.writeByte(1); // #1 + 2i
                out.writeUTF(classNames.get(i));
                out.writeByte(7); // #2 + 2i: class ref to #1 + 2i
                out.writeShort(1 + 2 * i);
            }
            for (final var string : strings) {
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
            if (isExceptions || isParameterAnnotation) {
                out.writeShort(1); // Number of methods
                out.writeShort(0x0401); // ACC_PUBLIC | ACC_ABSTRACT
                out.writeShort(attributeNameCpIdx - 2); // "m"
                out.writeShort(attributeNameCpIdx - 1); // The method descriptor
                out.writeShort(1); // attributes_count
                out.writeShort(attributeNameCpIdx);
                if (isExceptions) {
                    out.writeInt(2 + 2);
                    out.writeShort(1); // number_of_exceptions
                    out.writeShort(2 + 2 * firstAttributeClassIdx);
                } else {
                    out.writeInt(1 + 2 + 4 * attributeNames.length);
                    out.writeByte(1); // num_parameters
                    out.writeShort(attributeNames.length); // num_annotations
                    for (var i = 0; i < attributeNames.length; i++) {
                        out.writeShort(firstStringCpIdx + i); // type_index
                        out.writeShort(0); // num_element_value_pairs
                    }
                }
                out.writeShort(0); // Number of attributes
            } else {
                out.writeShort(0); // Number of methods
                if (attributeName == null) {
                    out.writeShort(0); // Number of attributes
                } else {
                    out.writeShort(1);
                    out.writeShort(attributeNameCpIdx);
                    if (isAnnotation) {
                        out.writeInt(2 + 4 * attributeNames.length);
                        out.writeShort(attributeNames.length); // num_annotations
                        for (var i = 0; i < attributeNames.length; i++) {
                            out.writeShort(firstStringCpIdx + i); // type_index
                            out.writeShort(0); // num_element_value_pairs
                        }
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
        final var badClassfiles = List.of( //
                classfile("p/Bad", "p/S;", null, null), //
                classfile("p/Bad", "Lp/S;", null, null), //
                classfile("p/Bad", "", null, null), //
                classfile("p/Bad", "p//S", null, null), //
                classfile("p/Bad", "/S", null, null), //
                classfile("p/Bad", "S/", null, null), //
                classfile("p/Bad", "java/lang/Object", "p/I;", null), //
                classfile("p/Bad", "java/lang/Object", "", null), //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "p/Bad", "p/X;"), //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "p/Bad$I;", "p/Bad"), //
                classfile("p/Bad", "java/lang/Object", null, "InnerClasses", "", "p/Bad"), //
                classfile("p/Bad", "java/lang/Object", null, "EnclosingMethod", "p/E;"), //
                classfile("p/Bad", "java/lang/Object", null, "EnclosingMethod", "p//E"));
        for (var i = 0; i < badClassfiles.size(); i++) {
            final var badClassfile = badClassfiles.get(i);
            assertThatThrownBy(() -> new ByteClassLoader().define(badClassfile)).as("classfile " + i)
                    .isInstanceOf(ClassFormatError.class);
            final var dir = writeClasses(tempDir.resolve("dir" + i), badClassfile);
            try (var scanResult = new ClassGraph().enableClassInfo().enableExternalClasses()
                    .enableClasspathEntries(dir).scan()) {
                assertThat(scanResult.getAllClasses().getNames()).as("classfile " + i)
                        .containsExactlyInAnyOrder("java.lang.Object", "p.Good");
            }
        }
    }

    /**
     * A class with a {@code CONSTANT_Class} entry with an illegal name, which only records a dependency, is kept in
     * a scan that finds the dependencies of each class, and the name is not recorded as a dependency. Whether a
     * class is in the scan result does not depend on whether dependencies are found.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aClassReferenceWithAnIllegalNameIsNotRecordedAsADependency(@TempDir final Path tempDir)
            throws IOException {
        final var badClassfile = classfile("p/Bad", "java/lang/Object", null, null, "Q;", "", "p//S", "[Lp/S;;",
                "[L/S;", "java/lang/String");
        final var dir = writeClasses(tempDir, badClassfile);
        try (var scanResult = new ClassGraph().enableClassInfo().enableInterClassDependencies()
                .enableExternalClasses().enableClasspathEntries(dir).scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder("p.Bad", "p.Good",
                    "java.lang.Object", "java.lang.String");
            assertThat(scanResult.getClassInfo("p.Bad").getClassDependencies().getNames())
                    .containsExactly("java.lang.String");
        }
    }

    /**
     * An annotation whose type has an illegal name is skipped, and the class and its other annotations are kept, as
     * the JVM loads such a class and leaves out an annotation whose type it cannot find. No class is created for
     * the annotation type, so in particular no class has an empty name.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void anAnnotationWhoseTypeHasAnIllegalNameIsSkipped(@TempDir final Path tempDir) throws IOException {
        var dirIdx = 0;
        for (final var attributeName : List.of("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations")) {
            for (final var illegalName : List.of("L;", "Lp/A;;", "Lp//A;")) {
                final var desc = attributeName + " " + illegalName;
                final var badClassfile = classfile("p/Bad", "java/lang/Object", null, attributeName, "Lp/Good;",
                        illegalName);
                assertThat(new ByteClassLoader().define(badClassfile).getName()).as(desc).isEqualTo("p.Bad");
                final var dir = writeClasses(tempDir.resolve("dir" + dirIdx++), badClassfile);
                try (var scanResult = new ClassGraph().enableClassInfo().enableAnnotationInfo()
                        .enableExternalClasses().enableClasspathEntries(dir).scan()) {
                    assertThat(scanResult.getAllClasses().getNames()).as(desc).containsExactlyInAnyOrder("p.Bad",
                            "p.Good", "java.lang.Object");
                    assertThat(scanResult.getClassInfo("p.Bad").getAllAnnotationInfo().getNames()).as(desc)
                            .containsExactly("p.Good");
                }
            }
        }
    }

    /**
     * A method parameter annotation whose type has an illegal name is skipped, and the other annotations on the
     * parameter are kept, whether they come before or after it, with no empty slot left in its place.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aParameterAnnotationWhoseTypeHasAnIllegalNameIsSkipped(@TempDir final Path tempDir)
            throws IOException {
        var dirIdx = 0;
        for (final var illegalName : List.of("L;", "Lp/A;;", "Lp//A;")) {
            for (final var annotationTypes : List.of(new String[] { "Lp/Good;", illegalName },
                    new String[] { illegalName, "Lp/Good;" })) {
                final var desc = String.join(" ", annotationTypes);
                final var badClassfile = classfile("p/Bad", "java/lang/Object", null,
                        "RuntimeVisibleParameterAnnotations", annotationTypes);
                assertThat(new ByteClassLoader().define(badClassfile).getName()).as(desc).isEqualTo("p.Bad");
                final var dir = writeClasses(tempDir.resolve("dir" + dirIdx++), badClassfile);
                try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableAnnotationInfo()
                        .enableExternalClasses().enableClasspathEntries(dir).scan()) {
                    final var parameterInfo = scanResult.getClassInfo("p.Bad").getMethodInfo("m").get(0)
                            .getParameterInfo();
                    assertThat(parameterInfo).as(desc).hasSize(1);
                    assertThat(parameterInfo.get(0).annotationInfo).as(desc).doesNotContainNull();
                    assertThat(parameterInfo.get(0).getAllAnnotationInfo().getNames()).as(desc)
                            .containsExactly("p.Good");
                }
            }
        }
    }

    /**
     * Sources for the nested annotation tests. {@code p.In} is the annotation that is given an illegal class name,
     * by {@link #renameIn(Path)}, in the classfiles that use it.
     */
    private static final List<String> NESTED_ANNOTATION_SOURCES = List.of( //
            "package p; public @interface Good {}", //
            "package p; public @interface In {}", //
            "package p; public @interface Holder { In[] value() default {}; }", //
            "package p; public @interface Outer { In nested() default @In; Holder[] array() default {}; }", //
            "package p; @Good @Outer(nested = @In) public abstract class BadNested {}", //
            "package p; @Good @Outer(array = { @Holder, @Holder(@In) }) public abstract class BadArray {}", //
            "package p; public @interface Defaults { int x() default 1; In nested() default @In; "
                    + "In[] array() default { @In }; }");

    /** The {@code CONSTANT_Utf8} entry that holds the type descriptor of {@code p.In}: tag, length, bytes. */
    private static final String IN_DESCRIPTOR_ENTRY = "\u0001\u0000\u0006Lp/In;";

    /**
     * The same entry with an illegal class name of the same length, so the constant pool keeps its layout. A method
     * descriptor such as {@code ()Lp/In;} is a different entry, and is not changed.
     */
    private static final String ILLEGAL_DESCRIPTOR_ENTRY = "\u0001\u0000\u0006Lp/I;;";

    /**
     * Compile {@link #NESTED_ANNOTATION_SOURCES}, then give {@code p.In} an illegal class name in the classfiles of
     * {@code p.BadNested}, {@code p.BadArray} and {@code p.Defaults}.
     *
     * @param tempDir
     *            a temporary directory.
     * @return the directory that holds the classfiles.
     * @throws IOException
     *             if a file could not be written.
     */
    private static Path compileNestedAnnotationClasses(final Path tempDir) throws IOException {
        final var srcDir = Files.createDirectories(tempDir.resolve("src/p"));
        final var classesDir = Files.createDirectories(tempDir.resolve("classes"));
        final List<String> args = new ArrayList<>(List.of("-d", classesDir.toString()));
        for (final var source : NESTED_ANNOTATION_SOURCES) {
            final var nameMatcher = Pattern.compile("(?:class|@interface) (\\w+)").matcher(source);
            assertThat(nameMatcher.find()).as(source).isTrue();
            final var srcFile = srcDir.resolve(nameMatcher.group(1) + ".java");
            Files.writeString(srcFile, source);
            args.add(srcFile.toString());
        }
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        assertThat(compiler.run(null, null, null, args.toArray(new String[0]))).as("javac exit code").isZero();
        for (final var className : List.of("BadNested", "BadArray", "Defaults")) {
            renameIn(classesDir.resolve("p/" + className + ".class"));
        }
        return classesDir;
    }

    /**
     * Replace the {@code CONSTANT_Utf8} entry {@link #IN_DESCRIPTOR_ENTRY} in a classfile with
     * {@link #ILLEGAL_DESCRIPTOR_ENTRY}.
     *
     * @param classfile
     *            the classfile.
     * @throws IOException
     *             if the classfile could not be read or written.
     */
    private static void renameIn(final Path classfile) throws IOException {
        final var bytes = Files.readAllBytes(classfile);
        // ISO-8859-1 maps each byte to one char, so string positions are byte positions
        final var str = new String(bytes, StandardCharsets.ISO_8859_1);
        final var idx = str.indexOf(IN_DESCRIPTOR_ENTRY);
        assertThat(idx).as(classfile.toString()).isNotNegative();
        assertThat(str.indexOf(IN_DESCRIPTOR_ENTRY, idx + 1)).as(classfile.toString()).isNegative();
        System.arraycopy(ILLEGAL_DESCRIPTOR_ENTRY.getBytes(StandardCharsets.ISO_8859_1), 0, bytes, idx,
                ILLEGAL_DESCRIPTOR_ENTRY.length());
        Files.write(classfile, bytes);
    }

    /**
     * An annotation that holds a nested annotation whose type has an illegal name, directly or in an array, is
     * skipped as a whole, even if the array also holds legal annotations, and the other annotations of the class
     * are kept. The JVM loads such a class.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void anAnnotationThatHoldsAnAnnotationWhoseTypeHasAnIllegalNameIsSkipped(@TempDir final Path tempDir)
            throws IOException {
        final var dir = compileNestedAnnotationClasses(tempDir);
        for (final var className : List.of("p.BadNested", "p.BadArray")) {
            final var classfile = Files.readAllBytes(dir.resolve(className.replace('.', '/') + ".class"));
            assertThat(new ByteClassLoader().define(classfile).getName()).isEqualTo(className);
        }
        try (var scanResult = new ClassGraph().enableClassInfo().enableAnnotationInfo().enableClasspathEntries(dir)
                .scan()) {
            for (final var className : List.of("p.BadNested", "p.BadArray")) {
                assertThat(scanResult.getClassInfo(className).getAllAnnotationInfo().getNames()).as(className)
                        .containsExactly("p.Good");
            }
        }
    }

    /**
     * The default value of an annotation element that is an annotation whose type has an illegal name, or an array
     * that holds one, is left out, and the other default values of the annotation are kept.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aDefaultValueThatHoldsAnAnnotationWhoseTypeHasAnIllegalNameIsLeftOut(@TempDir final Path tempDir)
            throws IOException {
        final var dir = compileNestedAnnotationClasses(tempDir);
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableAnnotationInfo()
                .enableClasspathEntries(dir).scan()) {
            final var defaults = scanResult.getClassInfo("p.Defaults");
            assertThat(defaults.getDeclaredMethodInfo().getNames()).containsExactlyInAnyOrder("x", "nested",
                    "array");
            assertThat(defaults.getAnnotationDefaultParameterValues().getNames()).containsExactly("x");
            assertThat(defaults.getAnnotationDefaultParameterValues().getValue("x")).isEqualTo(1);
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
        final var dir = writeClasses(tempDir, classfile("p/Bad", "java/lang/Object", null, "Exceptions", "Q;"));
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableExternalClasses()
                .enableClasspathEntries(dir).scan()) {
            final var methodInfo = scanResult.getClassInfo("p.Bad").getMethodInfo("m").get(0);
            assertThat(methodInfo.getThrownExceptionNames()).containsExactly("Q;");
            assertThat(methodInfo.getThrownExceptions()).isEmpty();
        }
    }
}
