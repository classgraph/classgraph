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

    /** An annotation whose parameter value contains a character that has to be escaped in the graph. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Location {
        /**
         * The location.
         *
         * @return the location.
         */
        String value();
    }

    /** A class annotated with an annotation that is listed in the graph. */
    @MetaAnnotatedAnnotation
    @Location("C:\\Windows")
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

    /**
     * A backslash is escaped as a numeric character reference. GraphViz resolves only the named entities it knows,
     * and there is no name for a backslash -- an unknown entity makes GraphViz report "undefined entity" and exit
     * with an error, and the label of the node that contained it is then not rendered as a table at all.
     */
    @Test
    public void backslashesAreEscapedAsANumericCharacterReference() {
        assertThat(classGraph()).contains("C:&#x5C;Windows").doesNotContain("&lsol;", "&bsol;");
    }
}
