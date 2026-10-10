package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An annotated class can be compiled against one version of an annotation class and scanned together with another
 * version, in which an element changed its type. A classfile can also hold an annotation value that javac would
 * never write. The JDK reports such a value as a type mismatch when it is read, and still reads the other elements.
 * ClassGraph keeps such a value as it is stored in the classfile, an {@code Object[]}, instead of failing the scan
 * or every read of the annotation.
 */
public class VersionSkewedAnnotationValueTest {
    /** The source of the annotated class, compiled against {@link #ANNOTATION_V1}. */
    private static final String USER = "package p;\n" //
            + "@A(v = {\"a\"}, x = 1)\n" //
            + "public class User {\n" //
            + "}\n";

    /** The first version of the annotation, with an array-typed element {@code v}. */
    private static final String ANNOTATION_V1 = "package p;\n" //
            + "import java.lang.annotation.*;\n" //
            + "@Retention(RetentionPolicy.RUNTIME)\n" //
            + "public @interface A {\n" //
            + "    String[] v();\n" //
            + "    int x();\n" //
            + "}\n";

    /** A class with no annotations, which every scan must still find. */
    private static final String GOOD = "package p;\n" //
            + "public class Good {\n" //
            + "}\n";

    /** The repeatable annotation, compiled against the first version of its container, {@link #TAGS_V1}. */
    private static final String TAG = "package p;\n" //
            + "import java.lang.annotation.*;\n" //
            + "@Repeatable(Tags.class)\n" //
            + "@Retention(RetentionPolicy.RUNTIME)\n" //
            + "public @interface Tag {\n" //
            + "}\n";

    /** The first version of the container of {@link #TAG}. */
    private static final String TAGS_V1 = "package p;\n" //
            + "import java.lang.annotation.*;\n" //
            + "@Retention(RetentionPolicy.RUNTIME)\n" //
            + "public @interface Tags {\n" //
            + "    Tag[] value();\n" //
            + "}\n";

    /** A class with two repeats of {@link #TAG}, so the classfile holds the container {@code @Tags}. */
    private static final String TAGGED = "package p;\n" //
            + "@Tag\n" //
            + "@Tag\n" //
            + "public class Tagged {\n" //
            + "}\n";

    /**
     * A stand-in for {@code java.lang.annotation.Repeatable} whose name has the same length, so that its name can
     * be replaced in a classfile without changing the length of the constant pool entry. Its value is an array,
     * where the real {@code @Repeatable} holds a single class.
     */
    private static final String FAKE_REPEATABLE = "package fake.lang.annotation;\n" //
            + "import java.lang.annotation.*;\n" //
            + "@Retention(RetentionPolicy.RUNTIME)\n" //
            + "public @interface Repeatable {\n" //
            + "    Class<?>[] value();\n" //
            + "}\n";

    /** Finds the name of the class declared in a source. */
    private static final Pattern CLASS_NAME = Pattern.compile("(?:class|@interface) (\\w+)");

    /** Directory for the sources and classes of a test. */
    @TempDir
    Path tempDir;

    /**
     * Compile source files.
     *
     * @param outputDir
     *            the directory to write the classfiles to
     * @param classpath
     *            the directory holding classes the sources refer to, or null
     * @param sources
     *            the source of each class
     * @throws IOException
     *             if a source file could not be written
     */
    private void compile(final Path outputDir, final Path classpath, final String... sources) throws IOException {
        final Path srcDir = Files.createTempDirectory(tempDir, "src");
        final List<String> args = new ArrayList<>(Arrays.asList("-d", outputDir.toString()));
        if (classpath != null) {
            args.addAll(Arrays.asList("-cp", classpath.toString()));
        }
        for (final String source : sources) {
            final String packageName = source.substring("package ".length(), source.indexOf(';'));
            final Matcher nameMatcher = CLASS_NAME.matcher(source);
            assertThat(nameMatcher.find()).isTrue();
            final Path srcFile = srcDir.resolve(packageName.replace('.', '/'))
                    .resolve(nameMatcher.group(1) + ".java");
            Files.createDirectories(srcFile.getParent());
            Files.write(srcFile, source.getBytes(StandardCharsets.UTF_8));
            args.add(srcFile.toString());
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        assertThat(compiler.run(/* in = */ null, /* out = */ null, /* err = */ null, args.toArray(new String[0])))
                .as("javac exit code").isZero();
    }

    /**
     * Replace the only occurrence of a string in a classfile with another string of the same length.
     *
     * @param classfile
     *            the classfile
     * @param from
     *            the string to replace
     * @param to
     *            the replacement
     * @throws IOException
     *             if the classfile could not be read or written
     */
    private static void replaceInClassfile(final Path classfile, final String from, final String to)
            throws IOException {
        assertThat(to).hasSameSizeAs(from);
        final byte[] bytes = Files.readAllBytes(classfile);
        // ISO-8859-1 maps each byte to one char, so string positions are byte positions
        final String str = new String(bytes, StandardCharsets.ISO_8859_1);
        final int idx = str.indexOf(from);
        assertThat(idx).as(from + " in " + classfile).isNotNegative();
        assertThat(str.indexOf(from, idx + 1)).as("second " + from + " in " + classfile).isNegative();
        System.arraycopy(to.getBytes(StandardCharsets.ISO_8859_1), 0, bytes, idx, to.length());
        Files.write(classfile, bytes);
    }

    /**
     * Compile an annotated class against the first version of its annotation, then compile a second version of
     * the annotation.
     *
     * @param annotationV1
     *            the source of the first version of the annotation
     * @param user
     *            the source of the annotated class
     * @param annotationV2
     *            the source of the second version of the annotation
     * @return the classpath directories, the one holding the second version of the annotation first
     * @throws IOException
     *             if compiling failed
     */
    private Path[] compileUserAgainstChangedAnnotation(final String annotationV1, final String user,
            final String annotationV2) throws IOException {
        final Path v1Dir = Files.createDirectory(tempDir.resolve("v1"));
        compile(v1Dir, null, annotationV1);
        final Path userDir = Files.createDirectory(tempDir.resolve("user"));
        compile(userDir, v1Dir, user, GOOD);
        final Path v2Dir = Files.createDirectory(tempDir.resolve("v2"));
        compile(v2Dir, null, annotationV2);
        return new Path[] { v2Dir, userDir };
    }

    /**
     * Compile {@link #USER} against {@link #ANNOTATION_V1}, then compile a second version of the annotation.
     *
     * @param annotationV2
     *            the source of the second version of the annotation
     * @return the classpath directories, the one holding the second version of the annotation first
     * @throws IOException
     *             if compiling failed
     */
    private Path[] compileUserAgainstChangedAnnotation(final String annotationV2) throws IOException {
        return compileUserAgainstChangedAnnotation(ANNOTATION_V1, USER, annotationV2);
    }

    /**
     * Scan the classpath, and check that the annotation of {@code p.User} can be read: the element {@code x} holds
     * 1, and the element {@code v} holds the {@code Object[]} array stored in the classfile.
     *
     * @param expectedV
     *            the value of the element {@code v}
     * @param classpath
     *            the classpath
     */
    private static void assertUserAnnotationReadable(final Object[] expectedV, final Path... classpath) {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath((Object[]) classpath).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            final ClassInfo user = scanResult.getClassInfo("p.User");
            assertThat(user).isNotNull();
            final AnnotationInfo annotation = user.getAnnotationInfo("p.A");
            assertThat(annotation).isNotNull();
            final AnnotationParameterValueList values = annotation.getParameterValues();
            assertThat(values.getValue("x")).isEqualTo(1);
            assertThat(values.getValue("v")).isEqualTo(expectedV);
            assertThat(annotation.toString()).contains("x=1");
            assertThat(user.toString()).contains("p.User");
            assertThat(scanResult.getClassInfo("p.Good")).isNotNull();
        }
    }

    /**
     * Scan the classpath, and check that the annotation of {@code p.User} can be instantiated as a proxy, and that
     * the element {@code x} of the proxy holds 1.
     *
     * @param classpath
     *            the classpath
     * @throws ReflectiveOperationException
     *             if {@code x()} could not be called
     */
    private static void assertUserAnnotationProxyReadable(final Path... classpath)
            throws ReflectiveOperationException {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath((Object[]) classpath).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            final Annotation proxy = scanResult.getClassInfo("p.User").getAnnotationInfo("p.A")
                    .loadClassAndInstantiate();
            assertThat(proxy.annotationType().getMethod("x").invoke(proxy)).isEqualTo(1);
        }
    }

    /**
     * Scan the classpath, and check that the annotation of {@link #USER} can be read, with the element {@code v}
     * holding {@code Object[] { "a" }}.
     *
     * @param classpath
     *            the classpath
     */
    private static void assertUserAnnotationReadable(final Path... classpath) {
        assertUserAnnotationReadable(new Object[] { "a" }, classpath);
    }

    /** An array-typed element that became a scalar keeps its array value. */
    @Test
    public void arrayElementChangedToScalar() throws IOException, ReflectiveOperationException {
        final Path[] classpath = compileUserAgainstChangedAnnotation(ANNOTATION_V1.replace("String[] v()", //
                "String v()"));
        assertUserAnnotationReadable(classpath);
        assertUserAnnotationProxyReadable(classpath);
    }

    /** An array-typed element whose element type changed keeps its array value. */
    @Test
    public void arrayElementTypeChanged() throws IOException, ReflectiveOperationException {
        final Path[] classpath = compileUserAgainstChangedAnnotation(ANNOTATION_V1.replace("String[] v()", //
                "int[] v()"));
        assertUserAnnotationReadable(classpath);
        assertUserAnnotationProxyReadable(classpath);
    }

    /** An int[] element changed to String[] keeps its array of integers, instead of holding nulls. */
    @Test
    public void intArrayElementChangedToStringArray() throws IOException, ReflectiveOperationException {
        final String annotationV1 = ANNOTATION_V1.replace("String[] v()", "int[] v()");
        final Path[] classpath = compileUserAgainstChangedAnnotation(annotationV1, USER.replace("{\"a\"}", "{2}"),
                ANNOTATION_V1);
        assertUserAnnotationReadable(new Object[] { 2 }, classpath);
        assertUserAnnotationProxyReadable(classpath);
    }

    /** An annotation class with two methods of the same name, which javac cannot write. */
    @Test
    public void duplicatedElementMethod() throws IOException {
        final Path[] classpath = compileUserAgainstChangedAnnotation(ANNOTATION_V1.replace("int x();", //
                "int x();\n    int[] w();"));
        // Rename w() to v(): the CONSTANT_Utf8 entry "w" is tag 1, length 1, 'w'
        replaceInClassfile(classpath[0].resolve("p/A.class"), "\u0001\u0000\u0001w", "\u0001\u0000\u0001v");
        assertUserAnnotationReadable(classpath);
    }

    /** An element whose type is a multi-dimensional array, which javac cannot write. */
    @Test
    public void multiDimensionalElement() throws IOException, ReflectiveOperationException {
        final Path[] classpath = compileUserAgainstChangedAnnotation(
                ANNOTATION_V1.replace("String[] v()", "Z v()").replace("public @interface A {",
                        "public @interface A {\n    @interface Z {}"));
        // Change the descriptor of v() from returning p.A.Z to returning int[][][][][][]
        replaceInClassfile(classpath[0].resolve("p/A.class"), "()Lp/A$Z;", "()[[[[[[I");
        assertUserAnnotationReadable(classpath);
        assertUserAnnotationProxyReadable(classpath);
    }

    /**
     * Compile {@link #TAGGED} against {@link #TAGS_V1}, then replace the container with a second version.
     *
     * @param tagsV2
     *            the source of the second version of the container
     * @return the classpath directories
     * @throws IOException
     *             if compiling failed
     */
    private Path[] compileTaggedAgainstChangedContainer(final String tagsV2) throws IOException {
        final Path v1Dir = Files.createDirectory(tempDir.resolve("v1"));
        compile(v1Dir, null, TAG, TAGS_V1, TAGGED, GOOD);
        Files.delete(v1Dir.resolve("p/Tags.class"));
        final Path v2Dir = Files.createDirectory(tempDir.resolve("v2"));
        compile(v2Dir, v1Dir, tagsV2);
        return new Path[] { v1Dir, v2Dir };
    }

    /**
     * Scan the classpath, and check that the scan succeeds and that the annotations of {@link #TAGGED} can be read.
     * The container's value is still an array of annotations, so the repeats are unpacked from the container.
     *
     * @param classpath
     *            the classpath
     */
    private static void assertTaggedAnnotationsReadable(final Path... classpath) {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath((Object[]) classpath).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            assertThat(scanResult.getClassInfo("p.Good")).isNotNull();
            final ClassInfo tagged = scanResult.getClassInfo("p.Tagged");
            assertThat(tagged).isNotNull();
            assertThat(tagged.getAnnotationInfo().directOnly().getNames()).containsExactly("p.Tag", "p.Tag");
            assertThat(tagged.toString()).contains("p.Tagged");
        }
    }

    /** A container whose value() is no longer an array does not fail the scan. */
    @Test
    public void containerValueChangedToScalar() throws IOException {
        assertTaggedAnnotationsReadable(compileTaggedAgainstChangedContainer(TAGS_V1.replace("Tag[] value()", //
                "Tag value()")));
    }

    /** A container whose value() is an array of another type does not fail the scan. */
    @Test
    public void containerValueChangedToIntArray() throws IOException {
        assertTaggedAnnotationsReadable(compileTaggedAgainstChangedContainer(TAGS_V1.replace("Tag[] value()", //
                "int[] value()")));
    }

    /**
     * A {@code @Repeatable} whose value is an array, here {@code @Repeatable({})}, does not fail the scan. The
     * classfile of the annotation {@code p.R} is compiled with the stand-in {@link #FAKE_REPEATABLE}, which is then
     * renamed to {@code java.lang.annotation.Repeatable}. The JDK's {@code java.lang.annotation} package is
     * scanned too, so that the declared type of {@code Repeatable.value()}, {@code Class}, is known.
     */
    @Test
    public void repeatableWithArrayValue() throws IOException {
        final Path fakeDir = Files.createDirectory(tempDir.resolve("fake"));
        compile(fakeDir, null, FAKE_REPEATABLE);
        final Path dir = Files.createDirectory(tempDir.resolve("classes"));
        compile(dir, fakeDir, "package p;\n" //
                + "import java.lang.annotation.*;\n" //
                + "@fake.lang.annotation.Repeatable({})\n" //
                + "@Retention(RetentionPolicy.RUNTIME)\n" //
                + "public @interface R {\n" //
                + "}\n", GOOD);
        replaceInClassfile(dir.resolve("p/R.class"), "Lfake/lang/annotation/Repeatable;",
                "Ljava/lang/annotation/Repeatable;");
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dir.toString()).enableSystemJarsAndModules()
                .acceptPackages("p", "java.lang.annotation").enableClassInfo().enableAnnotationInfo().scan()) {
            assertThat(scanResult.getClassInfo("java.lang.annotation.Repeatable")).isNotNull();
            assertThat(scanResult.getAllClasses().getNames()).contains("p.Good", "p.R");
            final ClassInfo r = scanResult.getClassInfo("p.R");
            assertThat(r).isNotNull();
            final AnnotationInfo repeatable = r.getAnnotationInfo("java.lang.annotation.Repeatable");
            assertThat(repeatable).isNotNull();
            assertThat(repeatable.getParameterValues().getValue("value")).isEqualTo(new Object[0]);
            assertThat(r.toString()).contains("p.R");
        }
    }
}
