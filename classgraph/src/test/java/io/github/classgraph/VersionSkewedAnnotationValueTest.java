package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

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
 * An annotated class can be compiled against one version of an annotation class and scanned together with another
 * version, in which an element changed its type. A classfile can also hold an annotation value that javac would
 * never write. The JDK reports such a value as a type mismatch when it is read, and still reads the other elements.
 * ClassGraph keeps such a value as it is stored in the classfile, an {@code Object[]}, instead of failing the scan
 * or every read of the annotation.
 */
public class VersionSkewedAnnotationValueTest {
    /** The source of the annotated class, compiled against {@link #ANNOTATION_V1}. */
    private static final String USER = """
            package p;
            @A(v = {"a"}, x = 1)
            public class User {
            }
            """;

    /** The first version of the annotation, with an array-typed element {@code v}. */
    private static final String ANNOTATION_V1 = """
            package p;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface A {
                String[] v();
                int x();
            }
            """;

    /** A class with no annotations, which every scan must still find. */
    private static final String GOOD = """
            package p;
            public class Good {
            }
            """;

    /** The repeatable annotation, compiled against the first version of its container, {@link #TAGS_V1}. */
    private static final String TAG = """
            package p;
            import java.lang.annotation.*;
            @Repeatable(Tags.class)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Tag {
            }
            """;

    /** The first version of the container of {@link #TAG}. */
    private static final String TAGS_V1 = """
            package p;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Tags {
                Tag[] value();
            }
            """;

    /** A class with two repeats of {@link #TAG}, so the classfile holds the container {@code @Tags}. */
    private static final String TAGGED = """
            package p;
            @Tag
            @Tag
            public class Tagged {
            }
            """;

    /**
     * A stand-in for {@code java.lang.annotation.Repeatable} whose name has the same length, so that its name can
     * be replaced in a classfile without changing the length of the constant pool entry. Its value is an array,
     * where the real {@code @Repeatable} holds a single class.
     */
    private static final String FAKE_REPEATABLE = """
            package fake.lang.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Repeatable {
                Class<?>[] value();
            }
            """;

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
    private void compile(final Path outputDir, final @Nullable Path classpath, final String... sources)
            throws IOException {
        final var srcDir = Files.createTempDirectory(tempDir, "src");
        final List<String> args = new ArrayList<>(List.of("-d", outputDir.toString()));
        if (classpath != null) {
            args.addAll(List.of("-cp", classpath.toString()));
        }
        for (final String source : sources) {
            final var packageName = source.substring("package ".length(), source.indexOf(';'));
            final var nameMatcher = CLASS_NAME.matcher(source);
            assertThat(nameMatcher.find()).isTrue();
            final var srcFile = srcDir.resolve(packageName.replace('.', '/'))
                    .resolve(nameMatcher.group(1) + ".java");
            Files.createDirectories(srcFile.getParent());
            Files.writeString(srcFile, source);
            args.add(srcFile.toString());
        }
        final var compiler = ToolProvider.getSystemJavaCompiler();
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
        final var bytes = Files.readAllBytes(classfile);
        // ISO-8859-1 maps each byte to one char, so string positions are byte positions
        final var str = new String(bytes, StandardCharsets.ISO_8859_1);
        final var idx = str.indexOf(from);
        assertThat(idx).as(from + " in " + classfile).isNotNegative();
        assertThat(str.indexOf(from, idx + 1)).as("second " + from + " in " + classfile).isNegative();
        System.arraycopy(to.getBytes(StandardCharsets.ISO_8859_1), 0, bytes, idx, to.length());
        Files.write(classfile, bytes);
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
        final var v1Dir = Files.createDirectory(tempDir.resolve("v1"));
        compile(v1Dir, null, ANNOTATION_V1);
        final var userDir = Files.createDirectory(tempDir.resolve("user"));
        compile(userDir, v1Dir, USER, GOOD);
        final var v2Dir = Files.createDirectory(tempDir.resolve("v2"));
        compile(v2Dir, null, annotationV2);
        return new Path[] { v2Dir, userDir };
    }

    /**
     * Scan the classpath, and check that the annotation of {@link #USER} can be read: the element {@code x} holds
     * 1, and the element {@code v} holds the {@code Object[]} array stored in the classfile.
     *
     * @param classpath
     *            the classpath
     */
    private static void assertUserAnnotationReadable(final Path... classpath) {
        try (var scanResult = new ClassGraph().enableClasspathEntries((Object[]) classpath).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            final var user = scanResult.getClassInfo("p.User");
            assertThat(user).isNotNull();
            final var annotation = user.getAllAnnotationInfo().get("p.A");
            assertThat(annotation).isNotNull();
            final var values = annotation.getParameterValues();
            assertThat(values.getValue("x")).isEqualTo(1);
            assertThat(values.getValue("v")).isEqualTo(new Object[] { "a" });
            assertThat(annotation.toString()).contains("x=1");
            assertThat(user.toString()).contains("p.User");
            assertThat(scanResult.getClassInfo("p.Good")).isNotNull();
        }
    }

    /** An array-typed element that became a scalar keeps its array value. */
    @Test
    public void arrayElementChangedToScalar() throws IOException {
        assertUserAnnotationReadable(compileUserAgainstChangedAnnotation(ANNOTATION_V1.replace("String[] v()", //
                "String v()")));
    }

    /** An array-typed element whose element type changed keeps its array value. */
    @Test
    public void arrayElementTypeChanged() throws IOException {
        assertUserAnnotationReadable(compileUserAgainstChangedAnnotation(ANNOTATION_V1.replace("String[] v()", //
                "int[] v()")));
    }

    /** An annotation class with two methods of the same name, which javac cannot write. */
    @Test
    public void duplicatedElementMethod() throws IOException {
        final var classpath = compileUserAgainstChangedAnnotation(ANNOTATION_V1.replace("int x();", //
                "int x();\n    int[] w();"));
        // Rename w() to v(): the CONSTANT_Utf8 entry "w" is tag 1, length 1, 'w'
        replaceInClassfile(classpath[0].resolve("p/A.class"), "\u0001\u0000\u0001w", "\u0001\u0000\u0001v");
        assertUserAnnotationReadable(classpath);
    }

    /** An element whose type is a multi-dimensional array, which javac cannot write. */
    @Test
    public void multiDimensionalElement() throws IOException {
        final var classpath = compileUserAgainstChangedAnnotation(ANNOTATION_V1.replace("String[] v()", "Z v()")
                .replace("public @interface A {", "public @interface A {\n    @interface Z {}"));
        // Change the descriptor of v() from returning p.A.Z to returning int[][][][][][]
        replaceInClassfile(classpath[0].resolve("p/A.class"), "()Lp/A$Z;", "()[[[[[[I");
        assertUserAnnotationReadable(classpath);
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
        final var v1Dir = Files.createDirectory(tempDir.resolve("v1"));
        compile(v1Dir, null, TAG, TAGS_V1, TAGGED, GOOD);
        Files.delete(v1Dir.resolve("p/Tags.class"));
        final var v2Dir = Files.createDirectory(tempDir.resolve("v2"));
        compile(v2Dir, v1Dir, tagsV2);
        return new Path[] { v1Dir, v2Dir };
    }

    /**
     * Scan the classpath, and check that the scan succeeds and that the annotations of {@link #TAGGED} can be read.
     * The container's value is still an array of annotations, so the repeats are reported beside the container.
     *
     * @param classpath
     *            the classpath
     */
    private static void assertTaggedAnnotationsReadable(final Path... classpath) {
        try (var scanResult = new ClassGraph().enableClasspathEntries((Object[]) classpath).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            assertThat(scanResult.getClassInfo("p.Good")).isNotNull();
            final var tagged = scanResult.getClassInfo("p.Tagged");
            assertThat(tagged).isNotNull();
            assertThat(tagged.getAllAnnotationInfo().directOnly().getNames()).containsExactlyInAnyOrder("p.Tags",
                    "p.Tag", "p.Tag");
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
     * renamed to {@code java.lang.annotation.Repeatable}.
     */
    @Test
    public void repeatableWithArrayValue() throws IOException {
        final var fakeDir = Files.createDirectory(tempDir.resolve("fake"));
        compile(fakeDir, null, FAKE_REPEATABLE);
        final var dir = Files.createDirectory(tempDir.resolve("classes"));
        compile(dir, fakeDir, """
                package p;
                import java.lang.annotation.*;
                @fake.lang.annotation.Repeatable({})
                @Retention(RetentionPolicy.RUNTIME)
                public @interface R {
                }
                """, GOOD);
        replaceInClassfile(dir.resolve("p/R.class"), "Lfake/lang/annotation/Repeatable;",
                "Ljava/lang/annotation/Repeatable;");
        try (var scanResult = new ClassGraph().enableClasspathEntries(dir.toString()).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder("p.Good", "p.R");
            final var r = scanResult.getClassInfo("p.R");
            assertThat(r).isNotNull();
            final var repeatable = r.getAllAnnotationInfo().get("java.lang.annotation.Repeatable");
            assertThat(repeatable).isNotNull();
            assertThat(repeatable.getParameterValues().getValue("value")).isEqualTo(new Object[0]);
            assertThat(r.toString()).contains("p.R");
        }
    }

    /**
     * A {@code @Repeatable} whose class reference is not a valid type descriptor does not fail the scan. The class
     * reference {@code Lp/Rs;} of the annotation {@code p.R} is replaced with a malformed descriptor, and with a
     * type variable, which names no class.
     */
    @Test
    public void repeatableWithMalformedClassRef() throws IOException {
        for (final String malformedClassRef : new String[] { "Xp/Rs;", "TAbcd;" }) {
            final var dir = Files.createDirectory(tempDir.resolve("classes-" + malformedClassRef.charAt(0)));
            compile(dir, null, """
                    package p;
                    import java.lang.annotation.*;
                    @Repeatable(Rs.class)
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface R {
                    }
                    """, """
                    package p;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Rs {
                        R[] value();
                    }
                    """, GOOD);
            replaceInClassfile(dir.resolve("p/R.class"), "Lp/Rs;", malformedClassRef);
            try (var scanResult = new ClassGraph().enableClasspathEntries(dir.toString()).enableClassInfo()
                    .enableAnnotationInfo().scan()) {
                assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder("p.Good", "p.R",
                        "p.Rs");
                final var r = scanResult.getClassInfo("p.R");
                assertThat(r).isNotNull();
                final var repeatable = r.getAllAnnotationInfo().get("java.lang.annotation.Repeatable");
                assertThat(repeatable).isNotNull();
                assertThat(repeatable.getParameterValues().getValue("value")).isInstanceOf(AnnotationClassRef.class)
                        .hasToString(malformedClassRef + ".class");
                assertThat(r.toString()).contains("p.R");
            }
        }
    }
}
