package io.github.classgraph.viz;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/** Tests that each option of {@link GraphVizDotFileOptions} changes the generated .dot file as documented. */
public class GraphVizDotFileOptionsTest {
    /** An annotation on the graphed class. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface GraphClassAnnotation {
    }

    /** An annotation on the field of the graphed class. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface GraphFieldAnnotation {
    }

    /** An annotation on the method of the graphed class. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface GraphMethodAnnotation {
    }

    /** An annotation on the method parameter of the graphed class. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface GraphParamAnnotation {
    }

    /** The type of the field, the method return value and the method parameter of the graphed class. */
    public interface GraphType {
    }

    /** The class that is graphed. */
    @GraphClassAnnotation
    public static class GraphNode {
        /** A field, so that the graph has a field to show. */
        @GraphFieldAnnotation
        public GraphType graphField;

        /**
         * A method, so that the graph has a method to show.
         *
         * @param graphParam
         *            a parameter.
         * @return null.
         */
        @GraphMethodAnnotation
        public GraphType graphMethod(@GraphParamAnnotation final GraphType graphParam) {
            return null;
        }
    }

    /** The prefix that all the fixture class names share. */
    private static final String FIXTURE = GraphVizDotFileOptionsTest.class.getName() + "$";

    /** The edge drawn from a class to the type of one of its fields. */
    private static final String FIELD_TYPE_EDGE = "[arrowtail=obox, arrowsize=2.5, dir=back]";

    /** The edge drawn from a class to the return type or a parameter type of one of its methods. */
    private static final String METHOD_TYPE_EDGE = "[arrowtail=box, arrowsize=2.5, dir=back]";

    /** The edge drawn from a class to an annotation on it. */
    private static final String ANNOTATION_EDGE = "\"" + FIXTURE + "GraphNode\" -> \"" + FIXTURE
            + "GraphClassAnnotation\" [arrowhead=dot, arrowsize=2.5]";

    /** The edge drawn from a class to an annotation on a parameter of one of its methods. */
    private static final String PARAMETER_ANNOTATION_EDGE = "\"" + FIXTURE + "GraphNode\" -> \"" + FIXTURE
            + "GraphParamAnnotation\" [arrowhead=odot, arrowsize=2.5]";

    /**
     * Generate the class graph for the fixture classes.
     *
     * @param options
     *            the graph options.
     * @return the .dot file contents.
     */
    private static String classGraph(final GraphVizDotFileOptions options) {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptClasses(GraphClassAnnotation.class.getName(), GraphParamAnnotation.class.getName(),
                        GraphType.class.getName(), GraphNode.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            return GraphVizDotFile.generate(scanResult, scanResult.getAllClasses(), options);
        }
    }

    /**
     * Generate the inter-class dependency graph for the fixture classes. External classes are enabled in the scan,
     * so that the fixture classes depend on classes, such as {@code java.lang.annotation.Retention}, that are not
     * in the list, and the options that show or hide those have something to show or hide.
     *
     * @param options
     *            the graph options.
     * @return the .dot file contents.
     */
    private static String dependencyGraph(final GraphVizDotFileOptions options) {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptClasses(GraphClassAnnotation.class.getName(), GraphType.class.getName(),
                        GraphNode.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().enableInterClassDependencies().enableExternalClasses().scan()) {
            return GraphVizDotFile.generateFromInterClassDependencies(scanResult,
                    scanResult.getAllClasses().filter(ci -> ci.getName().startsWith(FIXTURE)), options);
        }
    }

    /** The layout size is written into the graph, and defaults to 10.5 by 8 inches. */
    @Test
    public void theLayoutSizeIsWrittenIntoTheGraph() {
        assertThat(classGraph(new GraphVizDotFileOptions())).contains("size=\"10.5,8.0\";");
        assertThat(classGraph(new GraphVizDotFileOptions().setLayoutSize(20, 15))).contains("size=\"20.0,15.0\";");
        assertThat(dependencyGraph(new GraphVizDotFileOptions().setLayoutSize(20, 15)))
                .contains("size=\"20.0,15.0\";");
    }

    /** Fields are listed inside the class node by default, and can be hidden. */
    @Test
    public void fieldsCanBeHidden() {
        assertThat(classGraph(new GraphVizDotFileOptions())).contains("<b>FIELDS</b>", "<b>graphField</b>");
        assertThat(classGraph(new GraphVizDotFileOptions().hideFields())).doesNotContain("<b>FIELDS</b>",
                "<b>graphField</b>");
    }

    /** Methods are listed inside the class node by default, and can be hidden. */
    @Test
    public void methodsCanBeHidden() {
        assertThat(classGraph(new GraphVizDotFileOptions())).contains("<b>METHODS</b>", "<b>graphMethod</b>");
        assertThat(classGraph(new GraphVizDotFileOptions().hideMethods())).doesNotContain("<b>METHODS</b>",
                "<b>graphMethod</b>");
    }

    /** An edge is drawn from a class to the type of each of its fields by default, and the edges can be hidden. */
    @Test
    public void fieldTypeDependencyEdgesCanBeHidden() {
        assertThat(classGraph(new GraphVizDotFileOptions())).contains(FIELD_TYPE_EDGE);
        final var noFieldEdges = classGraph(new GraphVizDotFileOptions().hideFieldTypeDependencyEdges());
        assertThat(noFieldEdges).doesNotContain(FIELD_TYPE_EDGE);
        // Hiding the field type edges leaves the method type edges alone
        assertThat(noFieldEdges).contains(METHOD_TYPE_EDGE);
    }

    /**
     * An edge is drawn from a class to the return type and parameter types of each of its methods by default, and
     * the edges can be hidden.
     */
    @Test
    public void methodTypeDependencyEdgesCanBeHidden() {
        assertThat(classGraph(new GraphVizDotFileOptions())).contains(METHOD_TYPE_EDGE);
        final var noMethodEdges = classGraph(new GraphVizDotFileOptions().hideMethodTypeDependencyEdges());
        assertThat(noMethodEdges).doesNotContain(METHOD_TYPE_EDGE);
        // Hiding the method type edges leaves the field type edges alone
        assertThat(noMethodEdges).contains(FIELD_TYPE_EDGE);
    }

    /** Annotations on the class, its fields, its methods and their parameters are listed, and can be hidden. */
    @Test
    public void annotationsCanBeHidden() {
        assertThat(classGraph(new GraphVizDotFileOptions())).contains("<b>ANNOTATIONS</b>", "@GraphClassAnnotation",
                "@GraphFieldAnnotation", "@GraphMethodAnnotation", "@GraphParamAnnotation");

        final var noAnnotations = classGraph(new GraphVizDotFileOptions().hideAnnotations());
        assertThat(noAnnotations).doesNotContain("<b>ANNOTATIONS</b>", "@GraphClassAnnotation",
                "@GraphFieldAnnotation", "@GraphMethodAnnotation", "@GraphParamAnnotation");
        // The fields and methods that the annotations were on are still listed, and so are the annotation edges
        assertThat(noAnnotations).contains("<b>graphField</b>", "<b>graphMethod</b>", ANNOTATION_EDGE);
    }

    /**
     * An edge is drawn from a class to each annotation on it, and on its members and their parameters, and the
     * edges can be hidden.
     */
    @Test
    public void annotationDependencyEdgesCanBeHidden() {
        assertThat(classGraph(new GraphVizDotFileOptions())).contains(ANNOTATION_EDGE, PARAMETER_ANNOTATION_EDGE);
        final var noAnnotationEdges = classGraph(new GraphVizDotFileOptions().hideAnnotationDependencyEdges());
        assertThat(noAnnotationEdges).doesNotContain(ANNOTATION_EDGE, PARAMETER_ANNOTATION_EDGE);
        // Hiding the annotation edges still lists the annotations inside the class node
        assertThat(noAnnotationEdges).contains("@GraphClassAnnotation");
    }

    /**
     * The meta-annotations of an annotation class are not listed, so an annotation class that has only
     * meta-annotations on it gets no annotations section at all, rather than an empty one.
     */
    @Test
    public void anEmptyAnnotationsSectionIsNotWritten() {
        // The only annotation on GraphClassAnnotation is @Retention, which is not listed
        assertThat(classGraph(new GraphVizDotFileOptions()))
                .doesNotContain("<b>ANNOTATIONS</b></font></td></tr></table>");
    }

    /** Class names in field and method type signatures are simple names by default, and can be fully qualified. */
    @Test
    public void classNamesInTypeSignaturesCanBeShownFullyQualified() {
        assertThat(classGraph(new GraphVizDotFileOptions())).contains(" GraphType</td>")
                .doesNotContain("$GraphType</td>");
        assertThat(classGraph(new GraphVizDotFileOptions().useFullyQualifiedNames()))
                .contains(FIXTURE + "GraphType</td>");
    }

    /**
     * Every option can be set either way round, so an options object handed on with options already switched off
     * can be switched back on, rather than only away from its default.
     */
    @Test
    public void everyOptionCanBeSetEitherWayRound() {
        final var defaults = classGraph(new GraphVizDotFileOptions());

        final var options = new GraphVizDotFileOptions().setLayoutSize(20, 15).hideFields()
                .hideFieldTypeDependencyEdges().hideMethods().hideMethodTypeDependencyEdges().hideAnnotations()
                .hideAnnotationDependencyEdges().useFullyQualifiedNames();
        assertThat(classGraph(options)).isNotEqualTo(defaults);

        assertThat(classGraph(options.setLayoutSize(10.5f, 8.0f).showFields().showFieldTypeDependencyEdges()
                .showMethods().showMethodTypeDependencyEdges().showAnnotations().showAnnotationDependencyEdges()
                .useSimpleNames())).isEqualTo(defaults);

        final var dependencyGraphDefaults = dependencyGraph(new GraphVizDotFileOptions());
        final var dependencyGraphOptions = new GraphVizDotFileOptions().hideUnlistedDependencies();
        assertThat(dependencyGraph(dependencyGraphOptions)).isNotEqualTo(dependencyGraphDefaults);
        assertThat(dependencyGraph(dependencyGraphOptions.showUnlistedDependencies()))
                .isEqualTo(dependencyGraphDefaults);
    }

    /**
     * The dependency graph shows the classes that the graphed classes depend on but that are not in the list of
     * classes to graph by default, and this can be switched either way.
     */
    @Test
    public void unlistedDependenciesCanBeShownOrHiddenInTheDependencyGraph() {
        // The external class java.lang.annotation.Retention is a dependency of the fixture annotation classes,
        // which are annotated with it
        assertThat(dependencyGraph(new GraphVizDotFileOptions()))
                .contains("\"java.lang.annotation.Retention\"[shape=");
        assertThat(dependencyGraph(new GraphVizDotFileOptions().showUnlistedDependencies()))
                .contains("\"java.lang.annotation.Retention\"[shape=");
        assertThat(dependencyGraph(new GraphVizDotFileOptions().hideUnlistedDependencies()))
                .doesNotContain("java.lang.annotation.Retention");
    }
}
