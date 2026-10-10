package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.ToolProvider;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AnnotationInfo#getParameterValues()} lists one value for each element of the annotation class, the way the
 * JDK does: the value given at the use site if there is one, otherwise the element's default value. A value given
 * for an element that the annotation class no longer declares is left out, whether or not the annotation class
 * declares any default values.
 */
public class AnnotationDefaultMergeTest {
    /** An annotation with an element named {@code equals}, which is legal, and default values. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface WithDefaults {
        /**
         * An element named like {@link Object#equals(Object)}, but with no parameter.
         *
         * @return the value.
         */
        int equals() default 7;

        /**
         * Another element.
         *
         * @return the value.
         */
        int other() default 3;
    }

    /** An annotation with an element named {@code equals}, and no default values. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface WithoutDefaults {
        /**
         * An element named like {@link Object#equals(Object)}, but with no parameter.
         *
         * @return the value.
         */
        int equals();
    }

    /** A class carrying both annotations. */
    @WithDefaults(equals = 42)
    @WithoutDefaults(equals = 5)
    public static class Annotated {
    }

    /** An element named {@code equals} is listed, whether or not the annotation declares default values. */
    @Test
    public void elementNamedEquals() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableAnnotationInfo()
                .acceptClasses(Annotated.class.getName(), WithDefaults.class.getName(),
                        WithoutDefaults.class.getName())
                .scan()) {
            final var annotated = scanResult.getClassInfo(Annotated.class.getName());
            assertThat(annotated).isNotNull();

            final var withDefaults = annotated.getAllAnnotationInfo().get(WithDefaults.class.getName());
            assertThat(withDefaults).isNotNull();
            assertThat(withDefaults.getParameterValues()).extracting(AnnotationParameterValue::toString)
                    .containsExactly("equals=42", "other=3");
            assertThat(withDefaults.getParameterValues().getValue("equals")).isEqualTo(42);
            assertThat(withDefaults.toString()).contains("equals=42");

            final var withoutDefaults = annotated.getAllAnnotationInfo().get(WithoutDefaults.class.getName());
            assertThat(withoutDefaults).isNotNull();
            assertThat(withoutDefaults.getParameterValues()).extracting(AnnotationParameterValue::toString)
                    .containsExactly("equals=5");
        }
    }

    /** The source of the annotated class, compiled against {@link #ANNOTATION_V1}. */
    private static final String USER = """
            package p;
            @A(x = 1, y = 2)
            public class User {
            }
            """;

    /** The annotation the annotated class is compiled against. */
    private static final String ANNOTATION_V1 = """
            package p;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface A {
                int x();
                int y();
            }
            """;

    /** Directory for the sources and classes of a test. */
    @TempDir
    Path tempDir;

    /**
     * Compile source files into a new directory.
     *
     * @param dirName
     *            the name of the directory to create in {@link #tempDir} and write the classfiles to
     * @param classpath
     *            the directory holding classes the sources refer to, or null
     * @param sources
     *            the source of each class, in package {@code p}
     * @return the directory holding the classfiles
     * @throws IOException
     *             if a source file could not be written
     */
    private Path compile(final String dirName, final @Nullable Path classpath, final String... sources)
            throws IOException {
        final var outputDir = Files.createDirectory(tempDir.resolve(dirName));
        final var srcDir = Files.createDirectories(tempDir.resolve("src-" + dirName).resolve("p"));
        final List<String> args = new ArrayList<>(List.of("-d", outputDir.toString()));
        if (classpath != null) {
            args.addAll(List.of("-cp", classpath.toString()));
        }
        for (final String source : sources) {
            final var name = source.contains("@interface A") ? "A" : "User";
            final var srcFile = srcDir.resolve(name + ".java");
            Files.writeString(srcFile, source);
            args.add(srcFile.toString());
        }
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        assertThat(compiler.run(/* in = */ null, /* out = */ null, /* err = */ null, args.toArray(new String[0])))
                .as("javac exit code").isZero();
        return outputDir;
    }

    /**
     * Compile {@link #USER} against {@link #ANNOTATION_V1}, scan it together with another version of the
     * annotation, and return the parameter values of its annotation.
     *
     * @param annotationV2
     *            the source of the annotation to scan with, or null to scan without the annotation class
     * @return the parameter values, each as {@code name=value}
     * @throws IOException
     *             if compiling failed
     */
    private List<String> parameterValuesAgainst(final @Nullable String annotationV2) throws IOException {
        final var userDir = compile("user", compile("v1", null, ANNOTATION_V1), USER);
        final List<Object> classpath = new ArrayList<>();
        if (annotationV2 != null) {
            classpath.add(compile("v2", null, annotationV2));
        }
        classpath.add(userDir);
        try (var scanResult = new ClassGraph().enableClasspathEntries(classpath.toArray()).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            final var user = scanResult.getClassInfo("p.User");
            assertThat(user).isNotNull();
            final var annotation = user.getAllAnnotationInfo().get("p.A");
            assertThat(annotation).isNotNull();
            assertThat(annotation.getDeclaredParameterValues()).extracting(AnnotationParameterValue::toString)
                    .containsExactly("x=1", "y=2");
            final var values = new ArrayList<String>();
            for (final AnnotationParameterValue value : annotation.getParameterValues()) {
                values.add(value.toString());
            }
            return values;
        }
    }

    /** The unchanged annotation class gives the values given at the use site. */
    @Test
    public void unchangedAnnotation() throws IOException {
        assertThat(parameterValuesAgainst(ANNOTATION_V1)).containsExactly("x=1", "y=2");
    }

    /** A value for a removed element is left out when the annotation class declares no default values. */
    @Test
    public void elementRemovedWithoutDefaults() throws IOException {
        assertThat(parameterValuesAgainst(ANNOTATION_V1.replace("    int y();\n", ""))).containsExactly("x=1");
    }

    /** A value for a removed element is left out when the annotation class declares a default value. */
    @Test
    public void elementRemovedWithDefault() throws IOException {
        assertThat(parameterValuesAgainst(ANNOTATION_V1.replace("int y();", "int z() default 3;")))
                .containsExactly("x=1", "z=3");
    }

    /** No value is listed when the annotation class no longer declares any element. */
    @Test
    public void allElementsRemoved() throws IOException {
        assertThat(parameterValuesAgainst(ANNOTATION_V1.replace("    int x();\n    int y();\n", ""))).isEmpty();
    }

    /** If the annotation class was not scanned, its elements are not known, so the use site's values are given. */
    @Test
    public void annotationClassNotScanned() throws IOException {
        assertThat(parameterValuesAgainst(null)).containsExactly("x=1", "y=2");
    }
}
