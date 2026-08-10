package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/** Tests for {@link ClassInfoList#generateGraphVizDotFile()}. */
public class GraphvizDotfileGeneratorTest {
    /** An annotation whose only annotation is a meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MetaAnnotatedAnnotation {
    }

    /** A class annotated with an annotation that is listed in the graph. */
    @MetaAnnotatedAnnotation
    public static class AnnotatedClass {
    }

    /**
     * Generate the class graph for the fixture classes.
     *
     * @return the .dot file contents.
     */
    private static String classGraph() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptClasses(MetaAnnotatedAnnotation.class.getName(), AnnotatedClass.class.getName())
                .enableAllInfo().scan()) {
            return scanResult.getAllClasses().generateGraphVizDotFile();
        }
    }

    /**
     * The meta-annotations of an annotation class are not listed, so an annotation class that has only
     * meta-annotations on it gets no annotations section at all, rather than an empty one.
     */
    @Test
    public void anEmptyAnnotationsSectionIsNotWritten() {
        final String dotFile = classGraph();
        // The annotated class still gets an annotations section, listing the annotation on it
        assertThat(dotFile).contains("<b>ANNOTATIONS</b>", "@" + MetaAnnotatedAnnotation.class.getName());
        // The only annotation on MetaAnnotatedAnnotation is @Retention, which is not listed, so its annotations
        // section header would have nothing under it
        assertThat(dotFile).doesNotContain("<b>ANNOTATIONS</b></font></td></tr></table>");
    }
}
