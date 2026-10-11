package io.github.classgraph.viz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodParameterInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.viz.switchmap.SwitchOnEnum;

/** Tests the graphs written by {@link GraphVizDotFile}. */
public class GraphVizDotFileTest {
    /** DOT identifiers escape syntax and control characters without changing ordinary class names. */
    @Test
    public void dotIdentifiersAreEscaped() {
        final var identifier = new StringBuilder();
        GraphVizDotFile.appendQuotedIdentifier("p.A\"\\B\n\u0001", identifier);
        assertThat(identifier).hasToString("\"p.A\\\"\\\\B\\n\\u0001\"");
    }

    /** The superclass of the graphed class. */
    public static class Base {
    }

    /** The interface implemented by the graphed class. */
    public interface Marker {
    }

    /** An annotation whose parameter value contains characters that have to be escaped in the graph. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Location {
        /**
         * The location.
         *
         * @return the location.
         */
        String value();
    }

    /** An annotation on a field. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Indexed {
    }

    /** An annotation on a method. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Cached {
    }

    /** A second annotation on a method, so that the annotations of a method are wide enough to wrap. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Deprecated2 {
    }

    /** An annotation on a method parameter. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NotBlank {
    }

    /** The class that is graphed. */
    @Location("C:\\Windows")
    // This fixture is graphed, not called, so its members and their parameters are never used
    @SuppressWarnings("unused")
    public static class Derived extends Base implements Marker, Serializable {
        /** Removed from the graph, since it is an implementation detail of serialization. */
        private static final long serialVersionUID = 1L;

        /** Set by a static initializer, so that the class has a {@code <clinit>} method. */
        static final int INITIALIZED;

        static {
            INITIALIZED = 1;
        }

        /**
         * A field with a generic type, an annotation, a modifier beyond its visibility, and a name that has to be
         * escaped in the graph. Transient because {@link Map} is not {@link Serializable}.
         */
        @Indexed
        public transient Map<String, List<Base>> \u00A3prices = Map.of();

        /** A field that is shown only if field visibility is ignored. */
        private int count;

        /** A method with two annotations, and with an annotated parameter. */
        @Cached
        @Deprecated2
        public Base find(@NotBlank final String key, final int maximumNumberOfResults) {
            count++;
            return new Base();
        }

        /** A method that is shown only if method visibility is ignored. */
        private void reset() {
            count = 0;
        }

        @Override
        public int hashCode() {
            return count;
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof Derived && ((Derived) obj).count == count;
        }

        @Override
        public String toString() {
            return "Derived";
        }
    }

    /** A class whose annotation parameter value contains every character that the graph escapes. */
    @Location("&<>\"'/")
    public static class Escaped {
    }

    /** The prefix that all the fixture class names share. */
    private static final String FIXTURE = GraphVizDotFileTest.class.getName() + "$";

    /** A scan of the fixture classes, showing their non-public members too. */
    private static ScanResult scanResult;

    /** A scan of the fixture classes that ignores nothing but the visibility modifiers. */
    private static ScanResult publicOnlyScanResult;

    /**
     * Start a scan of the fixture classes.
     *
     * @return the {@link ClassGraph} to scan with.
     */
    private static ClassGraph scanFixture() {
        return new ClassGraph().enableClasspath().acceptClasses(Base.class.getName(), Marker.class.getName(),
                Derived.class.getName(), Escaped.class.getName(), Location.class.getName(), Indexed.class.getName(),
                Cached.class.getName(), Deprecated2.class.getName(), NotBlank.class.getName());
    }

    /** Scan the fixture classes. */
    @BeforeAll
    static void scan() {
        scanResult = scanFixture().enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().enableInterClassDependencies().scan();
        // The visibility modifiers are not ignored here, so only the public members are collected
        publicOnlyScanResult = scanFixture().enableClassInfo().enableFieldInfo().enableMethodInfo()
                .enableAnnotationInfo().scan();
    }

    /** Close the scan results. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
        publicOnlyScanResult.close();
    }

    /**
     * Generate the class graph of the fixture classes.
     *
     * @param scanResultToGraph
     *            the scan result to graph.
     * @param options
     *            the graph options.
     * @return the .dot file contents.
     */
    private static String graph(final ScanResult scanResultToGraph, final GraphVizDotFileOptions options) {
        return GraphVizDotFile.generate(scanResultToGraph, scanResultToGraph.getAllClasses(), options);
    }

    /**
     * Generate the class graph of the fixture classes, with the default options.
     *
     * @return the .dot file contents.
     */
    private static String graph() {
        return graph(scanResult, new GraphVizDotFileOptions());
    }

    /**
     * Assert that a call throws {@link NullPointerException} because an argument was null.
     *
     * @param call
     *            the call to make.
     */
    private static void rejectsNull(final ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(NullPointerException.class).hasMessageContaining("must not be null");
    }

    /** A class is joined to its superclass and to the interfaces it implements. */
    @Test
    public void classesAreJoinedToTheirSupertypes() {
        final var dotFile = GraphVizDotFile.generate(scanResult, scanResult.getAllClasses(),
                new GraphVizDotFileOptions().setLayoutSize(20, 20));
        assertThat(dotFile).contains("\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Base\" [arrowsize=2.5]",
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Marker\" [arrowhead=diamond, arrowsize=2.5]");
    }

    /** The dependency graph joins a class to the classes it depends upon. */
    @Test
    public void classesAreJoinedToTheirDependencies() {
        final var dotFile = GraphVizDotFile.generateFromInterClassDependencies(scanResult,
                scanResult.getAllClasses());
        assertThat(dotFile).contains("\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Base\" [arrowsize=2.5]");
    }

    /**
     * A backslash is escaped as a numeric character reference. GraphViz resolves only the named entities it knows,
     * and there is no name for a backslash -- an unknown entity makes GraphViz report "undefined entity" and exit
     * with an error, and the label of the node that contained it is then not rendered as a table at all.
     */
    @Test
    public void backslashesAreEscapedAsANumericCharacterReference() {
        // AnnotationInfo#toString() escapes the backslash of the parameter value as a Java escape sequence first,
        // so the graph shows the two backslashes of "C:\\Windows" as they would be written in source code
        assertThat(graph()).contains("C:&#x5C;&#x5C;Windows").doesNotContain("&lsol;", "&bsol;");
    }

    /**
     * Every character that could be read as HTML markup, or that could end an attribute value, is escaped, so that
     * a label cannot break out of the table that GraphViz lays the class node out as.
     */
    @Test
    public void charactersThatAreUnsafeInHtmlAreEscaped() {
        // The value of @Location on Escaped is the seven characters &<>"'/ (the double quote written as \" by
        // AnnotationInfo#toString(), which escapes it as a Java escape sequence first)
        assertThat(graph()).contains("@Location(&quot;&amp;&lt;&gt;&#x5C;&quot;&#x27;&#x2F;&quot;)");
    }

    /**
     * A character with a named HTML entity is written as that entity, since not every charset that GraphViz might
     * read the file in can represent the character itself.
     */
    @Test
    public void charactersWithANamedHtmlEntityAreWrittenAsThatEntity() {
        assertThat(graph()).contains("<b>&pound;prices</b>").doesNotContain("\u00A3prices");
    }

    /** The angle brackets of a generic type do not turn into markup. */
    @Test
    public void genericTypesAreEscaped() {
        assertThat(graph()).contains("Map&lt;String, List&lt;Base&gt;&gt;");
    }

    /** A class node lists the annotations, fields and methods of the class. */
    @Test
    public void classNodesListTheMembersOfTheClass() {
        assertThat(graph()).contains(
                // Section headers
                "<b>ANNOTATIONS</b>", "<b>FIELDS</b>", "<b>METHODS</b>",
                // The class annotation
                "<td align='center' valign='top'>@Location(&quot;C:&#x5C;&#x5C;Windows&quot;)</td>",
                // A field, with its annotation, its modifiers and its type, with no space at the start of the cell
                "<td align='right' valign='top'>@Indexed public transient Map&lt;String, List&lt;Base&gt;&gt;</td>",
                // A method, with its annotations, its modifiers and its return type
                "<td align='right' valign='top'>@Cached @Deprecated2 public Base</td>",
                // The parameters of the method, with their annotations, types and names
                "<b>find</b>", "(@NotBlank String <b>key</b>, int <b>maximumNumberOfResults</b>)",
                // A constructor is named after its class, and has no return type
                "<b>&lt;constructor&gt;</b></td><td align='left' valign='top'><b>Derived</b>");
    }

    /** The members that are noise in a class diagram are left out of the graph. */
    @Test
    public void theMembersThatWouldOnlyBeNoiseAreOmitted() {
        assertThat(graph()).doesNotContain("serialVersionUID", "&lt;clinit&gt;", "<b>hashCode</b>", "<b>equals</b>",
                "<b>toString</b>");
    }

    /**
     * A scan that did not ignore the visibility modifiers shows only public members, and the graph says so, since
     * otherwise the class would look as if it had no non-public members.
     */
    @Test
    public void theGraphSaysWhenOnlyPublicMembersWereScanned() {
        assertThat(graph(publicOnlyScanResult, new GraphVizDotFileOptions()))
                .contains("<b>PUBLIC FIELDS</b>", "<b>PUBLIC METHODS</b>", "<b>&pound;prices</b>", "<b>find</b>")
                .doesNotContain("<b>count</b>", "<b>reset</b>", "<b>INITIALIZED</b>");
    }

    /** Type names in a class node are shown as simple names by default, and fully qualified names on request. */
    @Test
    public void typeNamesCanBeShownFullyQualified() {
        assertThat(graph(scanResult, new GraphVizDotFileOptions().useFullyQualifiedNames())).contains(
                "public transient java.util.Map&lt;java.lang.String, java.util.List&lt;" + FIXTURE + "Base&gt;&gt;")
                .contains("java.lang.String <b>key</b>");
        assertThat(graph()).contains("public transient Map&lt;String, List&lt;Base&gt;&gt;")
                .contains("String <b>key</b>").doesNotContain("java.lang.String <b>key</b>");
    }

    /**
     * A row of annotations or of parameters that is wider than the wrap width is continued in the same column of a
     * new row, rather than stretching the class node out sideways.
     */
    @Test
    public void wideRowsOfAnnotationsAndParametersAreWrapped() {
        // With fully qualified names, the two annotations of Derived#find() are each wider than the wrap width, so
        // the second one starts a new row of the method table. The first parameter is wider than the wrap width as
        // well, so the second parameter starts a new row, but a parameter's annotation stays on the same row as the
        // parameter's type.
        assertThat(graph(scanResult, new GraphVizDotFileOptions().useFullyQualifiedNames())).contains(
                "@" + FIXTURE + "Cached</td><td></td><td></td></tr><tr>",
                "(@" + FIXTURE + "NotBlank java.lang.String <b>key</b>, "
                        + "</td></tr><tr><td></td><td></td><td align='left' valign='top'>"
                        + "int <b>maximumNumberOfResults</b>)");
    }

    /** Annotations are shown with simple names by default, and with fully qualified names on request. */
    @Test
    public void annotationNamesCanBeShownFullyQualified() {
        assertThat(graph()).contains("@Location(", "@Indexed ", "@Cached ", "(@NotBlank ")
                .doesNotContain("@" + FIXTURE);
        assertThat(graph(scanResult, new GraphVizDotFileOptions().useFullyQualifiedNames())).contains(
                "@" + FIXTURE + "Location(", "@" + FIXTURE + "Indexed ", "@" + FIXTURE + "Cached",
                "(@" + FIXTURE + "NotBlank ");
    }

    /** Each part of a class node, and each kind of edge, can be switched off. */
    @Test
    public void thePartsOfTheGraphCanBeHidden() {
        final var dotFile = graph(scanResult,
                new GraphVizDotFileOptions().hideAnnotations().hideFields().hideMethods()
                        .hideAnnotationDependencyEdges().hideFieldTypeDependencyEdges()
                        .hideMethodTypeDependencyEdges());
        assertThat(dotFile).doesNotContain("ANNOTATIONS", "FIELDS", "METHODS", "arrowhead=dot", "arrowhead=odot",
                "arrowtail=obox", "arrowtail=box");
        // The class nodes and the edges to the supertypes are still there
        assertThat(dotFile).contains("<b>GraphVizDotFileTest$Derived</b>",
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Base\" [arrowsize=2.5]");
    }

    /** A class node lists only what the scan collected. */
    @Test
    public void aClassNodeListsOnlyWhatTheScanCollected() {
        try (var classInfoOnly = scanFixture().enableClassInfo().scan()) {
            assertThat(graph(classInfoOnly, new GraphVizDotFileOptions()))
                    .contains("<b>GraphVizDotFileTest$Derived</b>")
                    .doesNotContain("ANNOTATIONS", "FIELDS", "METHODS");
        }
    }

    /**
     * The edges from an annotation say whether it annotates the class itself, or one of its fields, methods or
     * method parameters.
     */
    @Test
    public void annotationEdgesSayWhatTheAnnotationAnnotates() {
        assertThat(graph()).contains(
                // Class annotation
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Location\" [arrowhead=dot, arrowsize=2.5]",
                // Field annotation
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Indexed\" [arrowhead=odot, arrowsize=2.5]",
                // Method annotation
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Cached\" [arrowhead=odot, arrowsize=2.5]",
                // Method parameter annotation
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "NotBlank\" [arrowhead=odot, arrowsize=2.5]");
    }

    /** A class is joined to the types of its fields and to the types of its methods, by different edges. */
    @Test
    public void classesAreJoinedToTheTypesOfTheirMembers() {
        final var dotFile = graph();
        assertThat(dotFile).contains(
                // Type argument of the type of a field
                "\"" + FIXTURE + "Base\" -> \"" + FIXTURE + "Derived\" [arrowtail=obox, arrowsize=2.5, dir=back]",
                // Return type of a method
                "\"" + FIXTURE + "Base\" -> \"" + FIXTURE + "Derived\" [arrowtail=box, arrowsize=2.5, dir=back]");
        // The annotation on a method parameter is not a type of the method
        assertThat(dotFile).doesNotContain("\"" + FIXTURE + "NotBlank\" -> \"" + FIXTURE + "Derived\"");
    }

    /** A class that is the type of fields, and of method parameters and return values. */
    public static class Entity {
    }

    /** An interface with members, which is joined to the types of its members as a class is. */
    public interface Repository {
        /** A constant of type {@link Entity}. */
        Entity DEFAULT_ENTITY = new Entity();

        /**
         * A method that takes and returns an {@link Entity}.
         *
         * @param entity
         *            an entity.
         * @return an entity.
         */
        Entity find(Entity entity);
    }

    /** An enum, used as the type of an annotation parameter. */
    public enum Color {
        /** Red. */
        RED,
        /** Green. */
        GREEN
    }

    /** An annotation with a parameter, which is joined to the type of its parameter as a class is. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Colored {
        /**
         * The color.
         *
         * @return the color.
         */
        Color value();
    }

    /** The bound of the type parameter of a method. */
    public static class Bound {
    }

    /** An exception that a method declares that it throws. */
    public static class Failure extends Exception {
        /** Removed from the graph, since it is an implementation detail of serialization. */
        private static final long serialVersionUID = 1L;
    }

    /**
     * A class with several members of the same type, and with members whose annotations, annotation parameter
     * values and thrown exceptions are graphed classes.
     */
    public static class Holder {
        /** The first field of type {@link Entity}. */
        public Entity first;

        /** The second field of type {@link Entity}. */
        public Entity second;

        /** A field whose type argument is {@link Entity}. */
        public List<Entity> all;

        /** An annotated field. */
        @Colored(Color.RED)
        public int annotatedField;

        /**
         * A method that returns an {@link Entity}.
         *
         * @return an entity.
         */
        public Entity get() {
            return first;
        }

        /**
         * A method that takes an {@link Entity}.
         *
         * @param entity
         *            an entity.
         */
        public void set(final Entity entity) {
            first = entity;
        }

        /**
         * An annotated method with an annotated parameter, that declares that it throws an exception.
         *
         * @param annotatedParameter
         *            an annotated parameter.
         * @throws Failure
         *             never.
         */
        @Colored(Color.GREEN)
        public void annotatedMethod(@Colored(Color.RED) final int annotatedParameter) throws Failure {
            // Empty
        }

        /**
         * A method with a bounded type parameter.
         *
         * @param <T>
         *            the type parameter.
         * @param bounded
         *            a parameter of the type of the type parameter.
         */
        public <T extends Bound> void bounded(final T bounded) {
            // Empty
        }
    }

    /**
     * A class whose members have the type of a type variable of the class.
     *
     * @param <E>
     *            the type variable.
     */
    public static class Generic<E extends Entity> {
        /** A field whose type is the type variable of the class. */
        public E value;

        /**
         * A method whose return type is the type variable of the class.
         *
         * @return the value.
         */
        public E get() {
            return value;
        }
    }

    /**
     * A generic class with a generic inner class.
     *
     * @param <X>
     *            the type variable of the class.
     */
    public static class Outer<X> {
        /**
         * A generic inner class.
         *
         * @param <Y>
         *            the type variable of the inner class.
         */
        public class Inner<Y> {
        }
    }

    /** A class whose fields name the graphed classes only inside other types. */
    public static class Nested {
        /** A field whose array element type is {@link Entity}. */
        public Entity[][] grid;

        /** A field whose type argument is a wildcard bounded by {@link Bound}. */
        public Map<String, ? extends Bound> bounded;

        /** A field whose type arguments are {@link Color}, for the outer class, and {@link Failure}. */
        public Outer<Color>.Inner<Failure> inner;
    }

    /** The end of the line of an edge from a class named in the type of a field to the class with the field. */
    private static final String FIELD_TYPE_EDGE = " [arrowtail=obox, arrowsize=2.5, dir=back]\n";

    /** The end of the line of an edge from a class named in the type of a method to the class with the method. */
    private static final String METHOD_TYPE_EDGE = " [arrowtail=box, arrowsize=2.5, dir=back]\n";

    /** The end of the line of an edge from a class to an annotation on its fields, methods or method parameters. */
    private static final String MEMBER_ANNOTATION_EDGE = " [arrowhead=odot, arrowsize=2.5]\n";

    /**
     * Get an edge between two fixture classes, without its attributes.
     *
     * @param from
     *            the simple name of the fixture class the edge starts at.
     * @param to
     *            the simple name of the fixture class the edge ends at.
     * @return the edge, as far as its attributes.
     */
    private static String edge(final String from, final String to) {
        return "\"" + FIXTURE + from + "\" -> \"" + FIXTURE + to + "\"";
    }

    /**
     * Count the times that a string occurs in a graph.
     *
     * @param dotFile
     *            the graph.
     * @param text
     *            the string to count.
     * @return the number of times that {@code text} occurs in {@code dotFile}.
     */
    private static int count(final String dotFile, final String text) {
        return dotFile.split(Pattern.quote(text), -1).length - 1;
    }

    /**
     * Generate the class graph of the fixture classes that test the edges to the types of class members.
     *
     * @param options
     *            the graph options.
     * @return the .dot file contents.
     */
    private static String memberTypeGraph(final GraphVizDotFileOptions options) {
        try (var memberTypeScanResult = new ClassGraph().enableClasspath()
                .acceptClasses(Entity.class.getName(), Repository.class.getName(), Color.class.getName(),
                        Colored.class.getName(), Bound.class.getName(), Failure.class.getName(),
                        Holder.class.getName(), Generic.class.getName(), Nested.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility().scan()) {
            return graph(memberTypeScanResult, options);
        }
    }

    /**
     * A class is joined to a class named in the types of its fields by one edge, and to a class named in the types
     * of its methods by one edge, however many of its members name the class.
     */
    @Test
    public void classesAreJoinedToEachTypeOfTheirMembersOnce() {
        final var dotFile = memberTypeGraph(new GraphVizDotFileOptions());
        assertThat(count(dotFile, edge("Entity", "Holder") + FIELD_TYPE_EDGE)).isEqualTo(1);
        assertThat(count(dotFile, edge("Entity", "Holder") + METHOD_TYPE_EDGE)).isEqualTo(1);
    }

    /**
     * The annotations on the members of a class, the classes named in the parameter values of those annotations,
     * and the exceptions that its methods throw are not types of its members.
     */
    @Test
    public void annotationsAndThrownExceptionsAreNotTypesOfMembers() {
        assertThat(memberTypeGraph(new GraphVizDotFileOptions())).doesNotContain(
                // An annotation on a field, on a method and on a method parameter
                edge("Colored", "Holder"),
                // An enum constant named in the parameter value of an annotation on a member
                edge("Color", "Holder"),
                // An exception that a method throws
                edge("Failure", "Holder"));
    }

    /** A class is not joined to itself, as the constants and the methods of an enum would otherwise join it. */
    @Test
    public void classesAreNotJoinedToThemselves() {
        assertThat(memberTypeGraph(new GraphVizDotFileOptions())).doesNotContain(edge("Color", "Color"));
    }

    /**
     * A class is joined to the classes named in the type arguments and array element types of the types of its
     * members.
     */
    @Test
    public void typeArgumentsAndArrayElementTypesAreTypesOfMembers() {
        assertThat(memberTypeGraph(new GraphVizDotFileOptions())).contains(
                // Array element type
                edge("Entity", "Nested") + FIELD_TYPE_EDGE,
                // Bound of a wildcard type argument
                edge("Bound", "Nested") + FIELD_TYPE_EDGE,
                // Type argument of the outer class of an inner class type
                edge("Color", "Nested") + FIELD_TYPE_EDGE,
                // Type argument of the inner class of an inner class type
                edge("Failure", "Nested") + FIELD_TYPE_EDGE);
    }

    /**
     * A class is joined to the bounds of the type parameters of its methods, and to the bound of a type variable of
     * the class that is the whole type of a member, since that bound is the type the member has at run time.
     */
    @Test
    public void typeVariablesAreJoinedToTheirBounds() {
        final var dotFile = memberTypeGraph(new GraphVizDotFileOptions());
        assertThat(dotFile).contains(edge("Bound", "Holder") + METHOD_TYPE_EDGE,
                edge("Entity", "Generic") + FIELD_TYPE_EDGE, edge("Entity", "Generic") + METHOD_TYPE_EDGE);
    }

    /** Interfaces and annotations are joined to the types of their members, as classes are. */
    @Test
    public void interfacesAndAnnotationsAreJoinedToTheTypesOfTheirMembers() {
        assertThat(memberTypeGraph(new GraphVizDotFileOptions())).contains(
                // The type of an interface constant
                edge("Entity", "Repository") + FIELD_TYPE_EDGE,
                // The return type and parameter type of an interface method
                edge("Entity", "Repository") + METHOD_TYPE_EDGE,
                // The type of an annotation parameter
                edge("Color", "Colored") + METHOD_TYPE_EDGE);
    }

    /**
     * A class is joined to an annotation on its members by one edge, however many of its fields, methods and method
     * parameters the annotation is on.
     */
    @Test
    public void classesAreJoinedToEachAnnotationOfTheirMembersOnce() {
        assertThat(count(memberTypeGraph(new GraphVizDotFileOptions()),
                edge("Holder", "Colored") + MEMBER_ANNOTATION_EDGE)).isEqualTo(1);
    }

    /** Hiding the annotation edges leaves no edge between a class and the annotations on its members. */
    @Test
    public void hidingTheAnnotationEdgesHidesTheEdgesToTheAnnotationsOfMembers() {
        assertThat(memberTypeGraph(new GraphVizDotFileOptions().hideAnnotationDependencyEdges()))
                .doesNotContain(edge("Holder", "Colored"), edge("Colored", "Holder"));
    }

    /** A class whose only annotated members are members that its class node does not list. */
    public static class UnlistedAnnotated implements Serializable {
        /** Not listed, since it is an implementation detail of serialization. */
        @Colored(Color.RED)
        private static final long serialVersionUID = 1L;

        /** Not listed, since every class has it. */
        @Colored(Color.RED)
        @Override
        public String toString() {
            return "";
        }

        /** Not listed, since every class has it. */
        @Colored(Color.RED)
        @Override
        public int hashCode() {
            return 0;
        }

        /** Not listed, since every class has it. */
        @Colored(Color.RED)
        @Override
        public boolean equals(@Colored(Color.GREEN) final Object obj) {
            return obj == this;
        }
    }

    /**
     * A class is not joined to an annotation that is only on members its class node does not list, as it is not
     * joined to the types of those members.
     */
    @Test
    public void annotationsOfUnlistedMembersAreNotJoinedToTheClass() {
        try (var unlistedScanResult = new ClassGraph().enableClasspath()
                .acceptClasses(Color.class.getName(), Colored.class.getName(), UnlistedAnnotated.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility().scan()) {
            final var dotFile = graph(unlistedScanResult, new GraphVizDotFileOptions());
            // Both classes have a node, so only the members the annotation is on keep them from being joined
            assertThat(dotFile).contains("\"" + FIXTURE + "UnlistedAnnotated\"[shape=",
                    "\"" + FIXTURE + "Colored\"[shape=");
            assertThat(dotFile).doesNotContain(edge("UnlistedAnnotated", "Colored"));
        }
    }

    /** A class for which the compiler generates synthetic and bridge members. */
    // This fixture is graphed, not called, so its members and their parameters are never used
    @SuppressWarnings("unused")
    public static class Synth implements Comparable<Synth> {
        /** A lambda, whose body is compiled into a synthetic method. */
        private final Runnable task = () -> {
            // Empty
        };

        /** The value compared by {@link #compareTo(Synth)}. */
        private int value;

        /** Compiled together with a bridge method {@code compareTo(Object)}. */
        @Override
        public int compareTo(final Synth other) {
            return Integer.compare(value, other.value);
        }

        /**
         * A method with an assert statement, which makes the compiler add a synthetic field.
         *
         * @param x
         *            a positive number.
         */
        public void check(final int x) {
            assert x > 0;
        }

        /** An inner class, which has a synthetic field holding its enclosing instance. */
        public class Inner {
            /**
             * Use the enclosing instance, so that the compiler keeps the field that holds it.
             *
             * @return the value of the enclosing instance.
             */
            public int outerValue() {
                return value;
            }
        }

        /** An enum, which has a synthetic field and method holding and making the array of its constants. */
        public enum Shade {
            /** Dark. */
            DARK
        }
    }

    /**
     * Members and method parameters that the compiler generated, rather than ones declared in the source code, are
     * left out of the class nodes and do not produce type edges.
     */
    @Test
    public void compilerGeneratedMembersAreOmitted() {
        try (var synthScanResult = new ClassGraph().enableClasspath()
                .acceptClasses(Synth.class.getName(), Synth.Inner.class.getName(), Synth.Shade.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().ignoreClassVisibility()
                .ignoreFieldVisibility().ignoreMethodVisibility().scan()) {
            // The names of the generated members and parameters depend on the compiler, so they are taken from the
            // scan
            final Set<String> generatedMembers = new TreeSet<>();
            final Set<String> generatedParameters = new TreeSet<>();
            for (final var ci : synthScanResult.getAllClasses()) {
                for (final FieldInfo fi : ci.getDeclaredFieldInfo()) {
                    if (fi.isSynthetic()) {
                        generatedMembers.add(fi.getName());
                    }
                }
                for (final MethodInfo mi : ci.getDeclaredMethodAndConstructorInfo()) {
                    if (mi.isSynthetic() || mi.isBridge()) {
                        generatedMembers.add(mi.getName());
                    } else {
                        // The parameters of a generated method are generated too, but are named after the
                        // parameters of the method it calls
                        for (final MethodParameterInfo mpi : mi.getParameterInfo()) {
                            if (mpi.isSynthetic()) {
                                generatedParameters.add(mpi.getName());
                            }
                        }
                    }
                }
            }
            // The bridge method has the same name as the method it bridges to, which is listed once
            assertThat(generatedMembers).contains("compareTo").hasSizeGreaterThan(1);
            generatedMembers.remove("compareTo");
            // The name and ordinal parameters of the enum constructor
            assertThat(generatedParameters).isNotEmpty();

            final var dotFile = graph(synthScanResult, new GraphVizDotFileOptions());
            // Field and method names are written at the start of a cell, and parameter names after their type
            final var memberRow = "valign='top'><b>";
            assertThat(count(dotFile, memberRow + "compareTo</b>")).isEqualTo(1);
            for (final var name : generatedMembers) {
                assertThat(dotFile).doesNotContain(memberRow + name + "</b>");
            }
            for (final var name : generatedParameters) {
                assertThat(dotFile).doesNotContain(" <b>" + name + "</b>");
            }
            assertThat(dotFile).contains(memberRow + "Shade</b>&nbsp;</td><td align='left' valign='top'>()");
            // The field that holds the enclosing instance does not join the inner class to the enclosing class
            assertThat(dotFile).doesNotContain(edge("Synth", "Synth$Inner") + FIELD_TYPE_EDGE);
            // The methods that the language requires of an enum are not generated by the compiler, so are listed
            assertThat(dotFile).contains(memberRow + "values</b>", memberRow + "valueOf</b>");
        }
    }

    /**
     * A class that the compiler generated, rather than one declared in the source code, gets no node in the class
     * graph or in the dependency graph, even when it is a dependency of a class in the graph.
     */
    @Test
    public void compilerGeneratedClassesAreOmitted() {
        try (var switchScanResult = new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(SwitchOnEnum.class.getPackageName()).enableClassInfo()
                .ignoreClassVisibility().enableInterClassDependencies().scan()) {
            final var generatedClasses = switchScanResult.getAllClasses().filter(ClassInfo::isSynthetic);
            // The class that maps the enum constants to the cases of the switch
            assertThat(generatedClasses).isNotEmpty();
            final var classGraph = graph(switchScanResult, new GraphVizDotFileOptions());
            final var dependencyGraph = GraphVizDotFile.generateFromInterClassDependencies(switchScanResult,
                    switchScanResult.getAllClasses());
            // The generated class is still a dependency of a listed class, so would be shown as an unlisted one
            final var dependencyGraphOfDeclaredClasses = GraphVizDotFile.generateFromInterClassDependencies(
                    switchScanResult, switchScanResult.getAllClasses().filter(ci -> !ci.isSynthetic()));
            for (final var dotFile : List.of(classGraph, dependencyGraph, dependencyGraphOfDeclaredClasses)) {
                assertThat(node(dotFile, SwitchOnEnum.class.getName())).isNotEmpty();
                for (final var generatedClass : generatedClasses) {
                    assertThat(dotFile).doesNotContain("\"" + generatedClass.getName() + "\"");
                }
            }
        }
    }

    /**
     * An annotation that applies both to a declaration and to the type in it, so that javac records it on the
     * member and on the member's type.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE })
    public @interface Checked {
    }

    /** An annotation that applies only to a use of a type. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface Element {
    }

    /** A class whose members carry {@link Checked} on the declaration and on its type. */
    public static class DualAnnotated {
        /** A field of class type. */
        public @Checked String name;

        /** A field of array type: the annotation applies to the element type. */
        public @Checked String[] names;

        /** A field whose type is a static nested interface of another class. */
        public @Checked Map.Entry<String, Integer> entry;

        /** A field whose type argument carries a type annotation. */
        public List<@Element String> tags;

        /** A field whose type, not the field, is annotated. */
        public @Element String label;

        /**
         * A method whose result and parameter carry the annotation.
         *
         * @param key
         *            the key.
         * @return the key.
         */
        public @Checked String find(@Checked final String key) {
            return key;
        }
    }

    /**
     * An annotation that javac records both on a field, method or parameter and on its type is shown once, as an
     * annotation of the member, and hiding annotations hides it. An annotation that applies only to a use of a type
     * is part of the type, so it is shown either way.
     */
    @Test
    public void annotationsOnAMemberAndOnItsTypeAreShownOnce() {
        try (var dualScanResult = new ClassGraph().enableClasspath().acceptClasses(DualAnnotated.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo().scan()) {
            final var shown = node(graph(dualScanResult, new GraphVizDotFileOptions()),
                    DualAnnotated.class.getName());
            // Once each for the three fields, the method and the parameter
            assertThat(count(shown, "@Checked")).isEqualTo(5);
            assertThat(shown).contains("@Checked String</td>", "@Checked String[]</td>",
                    "@Checked Entry&lt;String, Integer&gt;</td>", "(@Checked String <b>key");
            assertThat(shown).contains("List&lt;@Element String&gt;</td>", "@Element String</td>");

            final var hidden = node(graph(dualScanResult, new GraphVizDotFileOptions().hideAnnotations()),
                    DualAnnotated.class.getName());
            assertThat(hidden).doesNotContain("@Checked");
            assertThat(hidden).contains("List&lt;@Element String&gt;</td>", "@Element String</td>");
        }
    }

    /** A class that overrides {@link Object#equals(Object)} with a type annotation on the parameter type. */
    public static class AnnotatedEquals {
        @Override
        public boolean equals(final @Element Object obj) {
            return obj == this;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    /** The {@code equals} method of a class is left out of its node even if its parameter type is annotated. */
    @Test
    public void equalsWithAnAnnotatedParameterTypeIsLeftOut() {
        try (var equalsScanResult = new ClassGraph().enableClasspath()
                .acceptClasses(AnnotatedEquals.class.getName()).enableClassInfo().enableMethodInfo()
                .enableAnnotationInfo().scan()) {
            assertThat(node(graph(equalsScanResult, new GraphVizDotFileOptions()), AnnotatedEquals.class.getName()))
                    .doesNotContain("<b>equals</b>");
        }
    }

    /** A class with generic methods, a generic constructor and variadic methods. */
    public static class GenericMethods {
        /**
         * A constructor with a type parameter.
         *
         * @param <K>
         *            the type parameter.
         * @param key
         *            a key.
         */
        public <K> GenericMethods(final K key) {
            // Empty
        }

        /**
         * A variadic method with a bounded type parameter.
         *
         * @param <T>
         *            the type parameter.
         * @param list
         *            a list.
         * @param more
         *            more values.
         * @return the first value of the list.
         */
        @SafeVarargs
        public static <T extends Comparable<T>> T max(final List<T> list, final T... more) {
            return list.get(0);
        }

        /**
         * A method with a type parameter, whose last parameter is an array but not variadic.
         *
         * @param <E>
         *            the type parameter.
         * @param list
         *            a list.
         * @param elements
         *            the elements to add.
         */
        public static <E> void addAll(final List<E> list, final E[] elements) {
            // Empty
        }

        /**
         * A variadic method with a parameter of primitive array type.
         *
         * @param values
         *            the values.
         */
        public static void sum(final int... values) {
            // Empty
        }
    }

    /**
     * The type parameters of a method or constructor are shown before its return type, as they are declared, and
     * the variadic parameter of a variadic method is shown as {@code T...} rather than as the array type
     * {@code T[]} that it has.
     */
    @Test
    public void methodTypeParametersAndVarargsAreShownAsDeclared() {
        try (var genericScanResult = new ClassGraph().enableClasspath()
                .acceptClasses(GenericMethods.class.getName()).enableClassInfo().enableMethodInfo().scan()) {
            final var simple = node(graph(genericScanResult, new GraphVizDotFileOptions()),
                    GenericMethods.class.getName());
            assertThat(simple).contains(
                    "valign='top'>&lt;T extends Comparable&lt;T&gt;&gt; T</td><td align='left' valign='top'>"
                            + "<b>max</b>",
                    "(List&lt;T&gt; <b>list</b>, T... <b>more</b>)",
                    "valign='top'>&lt;E&gt; void</td><td align='left' valign='top'><b>addAll</b>",
                    // An array parameter that is not variadic keeps its array type
                    "(List&lt;E&gt; <b>list</b>, E[] <b>elements</b>)", "(int... <b>values</b>)",
                    "valign='top'>&lt;K&gt; <b>&lt;constructor&gt;</b>");

            final var qualified = node(
                    graph(genericScanResult, new GraphVizDotFileOptions().useFullyQualifiedNames()),
                    GenericMethods.class.getName());
            assertThat(qualified).contains("valign='top'>&lt;T extends java.lang.Comparable&lt;T&gt;&gt; T</td>",
                    "(java.util.List&lt;T&gt; <b>list</b>, T... <b>more</b>)");
        }
    }

    /**
     * Write an abstract class {@code Untyped} with two methods {@code void named(int)} and
     * {@code void annotated(int)}, whose parameter metadata lists two parameters, one more than the type descriptor
     * has. The method {@code named} has a {@code MethodParameters} attribute naming the parameters {@code extra}
     * and {@code x}, and the method {@code annotated} has a {@code RuntimeVisibleParameterAnnotations} attribute
     * that puts {@link NotBlank @NotBlank} on the first parameter. ClassGraph aligns the metadata with the last
     * parameters of the type descriptor, so the first parameter of each method has no type.
     *
     * @param dir
     *            the directory to write the classfile to.
     * @throws IOException
     *             if the classfile could not be written.
     */
    static void writeUntypedParameterClassfile(final Path dir) throws IOException {
        final var untyped = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(untyped)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(13); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF("Untyped");
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5
            out.writeByte(1);
            out.writeUTF("MethodParameters");
            // #6
            out.writeByte(1);
            out.writeUTF("RuntimeVisibleParameterAnnotations");
            // #7
            out.writeByte(1);
            out.writeUTF("named");
            // #8
            out.writeByte(1);
            out.writeUTF("annotated");
            // #9
            out.writeByte(1);
            out.writeUTF("(I)V");
            // #10
            out.writeByte(1);
            out.writeUTF("extra");
            // #11
            out.writeByte(1);
            out.writeUTF("x");
            // #12
            out.writeByte(1);
            out.writeUTF("L" + NotBlank.class.getName().replace('.', '/') + ";");

            out.writeShort(0x0421); // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields

            out.writeShort(2); // Number of methods
            // public abstract void named(int), with two entries in its MethodParameters attribute
            out.writeShort(0x0401);
            out.writeShort(7);
            out.writeShort(9);
            out.writeShort(1); // attributes_count
            out.writeShort(5); // "MethodParameters"
            out.writeInt(9);
            out.writeByte(2); // parameters_count
            out.writeShort(10); // "extra"
            out.writeShort(0); // access_flags
            out.writeShort(11); // "x"
            out.writeShort(0); // access_flags
            // public abstract void annotated(int), with two entries in its RuntimeVisibleParameterAnnotations
            // attribute
            out.writeShort(0x0401);
            out.writeShort(8);
            out.writeShort(9);
            out.writeShort(1); // attributes_count
            out.writeShort(6); // "RuntimeVisibleParameterAnnotations"
            out.writeInt(9);
            out.writeByte(2); // num_parameters
            out.writeShort(1); // num_annotations of the first parameter
            out.writeShort(12); // @NotBlank
            out.writeShort(0); // num_element_value_pairs
            out.writeShort(0); // num_annotations of the second parameter

            out.writeShort(0); // Number of attributes
        }
        Files.write(dir.resolve("Untyped.class"), untyped.toByteArray());
    }

    /**
     * A method parameter with no type, which a classfile whose parameter metadata lists more parameters than the
     * type descriptor of the method gives, is shown with its name and annotations only, and does not make the graph
     * fail.
     *
     * @param tempDir
     *            the directory to write the classfile to.
     * @throws IOException
     *             if the classfile could not be written.
     */
    @Test
    public void parametersWithNoTypeAreShownWithoutAType(@TempDir final Path tempDir) throws IOException {
        writeUntypedParameterClassfile(tempDir);
        try (var untypedScanResult = new ClassGraph().enableClasspathEntries(tempDir).enableClassInfo()
                .enableMethodInfo().enableAnnotationInfo().scan()) {
            final var named = untypedScanResult.getClassInfo("Untyped").getMethodInfo("named").get(0);
            assertThat(named.getParameterInfo()).hasSize(2);
            assertThat(named.getParameterInfo().get(0).getTypeSignatureOrTypeDescriptor()).isNull();

            final var untyped = node(graph(untypedScanResult, new GraphVizDotFileOptions()), "Untyped");
            assertThat(untyped).contains(
                    "<b>named</b>&nbsp;</td><td align='left' valign='top'>" + "(<b>extra</b>, int <b>x</b>)",
                    "<b>annotated</b>&nbsp;</td><td align='left' valign='top'>(@NotBlank, int)");
        }
    }

    /**
     * Write a class {@code Target} with no members, and an abstract class {@code BrokenTypeDescriptors} with the
     * fields {@code typed} and {@code untyped} and the methods {@code typed()}, {@code untyped()} and
     * {@code equals(Object)}, all of which have a type descriptor that does not parse. Only the members named
     * {@code typed} and the method {@code equals} have a type signature. The type signatures of the members named
     * {@code typed} name {@code Target}. The JVM rejects such a classfile, but ClassGraph can still scan it.
     *
     * @param dir
     *            the directory to write the classfiles to.
     * @throws IOException
     *             if a classfile could not be written.
     */
    static void writeBrokenTypeDescriptorClassfiles(final Path dir) throws IOException {
        final var target = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(target)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(5); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF("Target");
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods
            out.writeShort(0); // Number of attributes
        }
        Files.write(dir.resolve("Target.class"), target.toByteArray());

        final var broken = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(broken)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(15); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF("BrokenTypeDescriptors");
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5
            out.writeByte(1);
            out.writeUTF("Signature");
            // #6
            out.writeByte(1);
            out.writeUTF("typed");
            // #7
            out.writeByte(1);
            out.writeUTF("untyped");
            // #8
            out.writeByte(1);
            out.writeUTF("LTarget"); // A field type descriptor, without its closing ';'
            // #9
            out.writeByte(1);
            out.writeUTF("LTarget;");
            // #10
            out.writeByte(1);
            out.writeUTF("()LTarget"); // A method type descriptor, without the closing ';' of its return type
            // #11
            out.writeByte(1);
            out.writeUTF("()LTarget;");
            // #12
            out.writeByte(1);
            out.writeUTF("equals");
            // #13
            out.writeByte(1);
            out.writeUTF("(Ljava/lang/Object)Z"); // A method type descriptor, without the ';' of its parameter type
            // #14
            out.writeByte(1);
            out.writeUTF("(Ljava/lang/Object;)Z");

            out.writeShort(0x0421); // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces

            out.writeShort(2); // Number of fields
            // public Target typed, with a type signature
            out.writeShort(0x0001);
            out.writeShort(6);
            out.writeShort(8);
            out.writeShort(1); // attributes_count
            out.writeShort(5); // "Signature"
            out.writeInt(2);
            out.writeShort(9);
            // public Target untyped, with no type signature
            out.writeShort(0x0001);
            out.writeShort(7);
            out.writeShort(8);
            out.writeShort(0); // attributes_count

            out.writeShort(3); // Number of methods
            // public abstract Target typed(), with a type signature
            out.writeShort(0x0401);
            out.writeShort(6);
            out.writeShort(10);
            out.writeShort(1); // attributes_count
            out.writeShort(5); // "Signature"
            out.writeInt(2);
            out.writeShort(11);
            // public abstract Target untyped(), with no type signature
            out.writeShort(0x0401);
            out.writeShort(7);
            out.writeShort(10);
            out.writeShort(0); // attributes_count
            // public abstract boolean equals(Object), with a type signature, which gives it one parameter
            out.writeShort(0x0401);
            out.writeShort(12);
            out.writeShort(13);
            out.writeShort(1); // attributes_count
            out.writeShort(5); // "Signature"
            out.writeInt(2);
            out.writeShort(14);

            out.writeShort(0); // Number of attributes
        }
        Files.write(dir.resolve("BrokenTypeDescriptors.class"), broken.toByteArray());
    }

    /**
     * A member whose type descriptor does not parse is joined to the classes named in its type signature, if it has
     * one, and does not make the graph fail.
     *
     * @param tempDir
     *            the directory to write the classfiles to.
     * @throws IOException
     *             if a classfile could not be written.
     */
    @Test
    public void typeEdgesSkipTypeDescriptorsThatDoNotParse(@TempDir final Path tempDir) throws IOException {
        writeBrokenTypeDescriptorClassfiles(tempDir);
        try (var brokenScanResult = new ClassGraph().enableClasspathEntries(tempDir).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().scan()) {
            final var brokenClass = brokenScanResult.getClassInfo("BrokenTypeDescriptors");
            assertThatThrownBy(() -> brokenClass.getFieldInfo("typed").getTypeDescriptor())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> brokenClass.getMethodInfo("typed").get(0).getTypeDescriptor())
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(graph(brokenScanResult, new GraphVizDotFileOptions())).contains(
                    "\"Target\" -> \"BrokenTypeDescriptors\"" + FIELD_TYPE_EDGE,
                    "\"Target\" -> \"BrokenTypeDescriptors\"" + METHOD_TYPE_EDGE);
        }
    }

    /**
     * A member whose type descriptor does not parse, and that has no type signature, is shown with its name but no
     * type, and does not make the graph fail. A member with a type signature is shown with the type in its type
     * signature.
     *
     * @param tempDir
     *            the directory to write the classfiles to.
     * @throws IOException
     *             if a classfile could not be written.
     */
    @Test
    public void membersWhoseTypeDoesNotParseAreShownWithoutAType(@TempDir final Path tempDir) throws IOException {
        writeBrokenTypeDescriptorClassfiles(tempDir);
        try (var brokenScanResult = new ClassGraph().enableClasspathEntries(tempDir).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().scan()) {
            final var broken = node(graph(brokenScanResult, new GraphVizDotFileOptions()), "BrokenTypeDescriptors");
            assertThat(broken).contains(
                    "<td align='right' valign='top'>Target</td><td align='left' valign='top'><b>typed</b></td>",
                    "<td align='right' valign='top'></td><td align='left' valign='top'><b>untyped</b></td>",
                    "<td align='right' valign='top'>Target</td><td align='left' valign='top'><b>typed</b>&nbsp;</td>"
                            + "<td align='left' valign='top'>()</td>",
                    "<td align='right' valign='top'></td><td align='left' valign='top'><b>untyped</b>&nbsp;</td>"
                            + "<td align='left' valign='top'>()</td>",
                    // Not left out as a method of Object, since its type descriptor is not that of Object.equals
                    "<td align='right' valign='top'>boolean</td><td align='left' valign='top'><b>equals</b>&nbsp;"
                            + "</td><td align='left' valign='top'>(Object)</td>");
        }
    }

    /** A class whose name ends in '$', as the name of every Scala {@code object} does. */
    public static class Reg$ extends Base {
        /** A field of the class's own type. */
        public Reg$ self;

        /** A constructor, which is listed under the simple name of the class. */
        public Reg$() {
            self = this;
        }
    }

    /**
     * A class whose name ends in '$' is labeled with its name, including the '$'. An empty name would be written as
     * {@code <b></b>}, which GraphViz rejects as a syntax error.
     */
    @Test
    public void aClassNameEndingInADollarSignIsNotDroppedFromTheGraph() {
        try (var regScanResult = new ClassGraph().enableClasspath()
                .acceptClasses(Reg$.class.getName(), Base.class.getName()).enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableInterClassDependencies().scan()) {
            final var classGraph = graph(regScanResult, new GraphVizDotFileOptions());
            assertThat(classGraph).doesNotContain("<b></b>");
            // The constructor row, and the type of the field
            assertThat(classGraph).contains("valign='top'><b>Reg$</b>", "valign='top'>Reg$</td>");
            final var dependencyGraph = GraphVizDotFile.generateFromInterClassDependencies(regScanResult,
                    regScanResult.getAllClasses());
            assertThat(dependencyGraph).doesNotContain("<b></b>").contains("Reg$</b>");
        }
    }

    /** A class with a nested class whose simple name is the same as that of a nested class of {@link A2}. */
    public static class A1 {
        /** A nested class with the same simple name as {@link A2.Builder}. */
        public static class Builder {
        }
    }

    /** A class with a nested class whose simple name is the same as that of a nested class of {@link A1}. */
    public static class A2 {
        /** A nested class with the same simple name as {@link A1.Builder}. */
        public static class Builder {
        }
    }

    /**
     * A record, which is labeled as a record.
     *
     * @param x
     *            the x coordinate.
     * @param y
     *            the y coordinate.
     */
    public record Point(int x, int y) {
    }

    /** An inner class with no modifiers. */
    class PlainInner {
    }

    /** A class that refers to two classes outside the scan, an interface it implements and the type of a field. */
    public static class ExternalUser implements Supplier<String> {
        /** A field whose type is an interface that no scanned class implements. */
        public Map<String, String> map;

        @Override
        public String get() {
            return "";
        }
    }

    /** The start of the row of a class node that holds the modifiers and the kind of the class. */
    private static final String HEADER_ROW = "<tr><td><font point-size='12'>";

    /**
     * Get the line of a graph that defines the node of a class.
     *
     * @param dotFile
     *            the graph.
     * @param className
     *            the name of the class.
     * @return the line that defines the node.
     */
    private static String node(final String dotFile, final String className) {
        final var start = "\"" + className + "\"[";
        return dotFile.lines().filter(line -> line.startsWith(start)).findFirst()
                .orElseThrow(() -> new AssertionError("No node for " + className + " in:\n" + dotFile));
    }

    /**
     * A class node is labeled with the name of the class relative to its package, so that two nested classes with
     * the same simple name do not get the same label, and so that a nested class does not look like a top-level
     * class of the package.
     */
    @Test
    public void nestedClassesAreLabeledWithTheirNameRelativeToTheirPackage() {
        try (var nestedScanResult = new ClassGraph().enableClasspath().acceptClasses(A1.class.getName(),
                A1.Builder.class.getName(), A2.class.getName(), A2.Builder.class.getName()).enableClassInfo()
                .enableInterClassDependencies().scan()) {
            final var classGraph = graph(nestedScanResult, new GraphVizDotFileOptions());
            final var dependencyGraph = GraphVizDotFile.generateFromInterClassDependencies(nestedScanResult,
                    nestedScanResult.getAllClasses());
            for (final var dotFile : List.of(classGraph, dependencyGraph)) {
                assertThat(node(dotFile, FIXTURE + "A1$Builder")).contains("<b>GraphVizDotFileTest$A1$Builder</b>");
                assertThat(node(dotFile, FIXTURE + "A2$Builder")).contains("<b>GraphVizDotFileTest$A2$Builder</b>");
            }
        }
    }

    /**
     * The name of a generic class in its class node is followed by the type parameters of the class, as they are
     * declared, with the type names shown as the options ask.
     */
    @Test
    public void classTypeParametersAreShownAfterTheClassName() {
        assertThat(node(memberTypeGraph(new GraphVizDotFileOptions()), Generic.class.getName()))
                .contains("<b>GraphVizDotFileTest$Generic&lt;E extends Entity&gt;</b>");
        assertThat(node(memberTypeGraph(new GraphVizDotFileOptions().useFullyQualifiedNames()),
                Generic.class.getName()))
                .contains("<b>GraphVizDotFileTest$Generic&lt;E extends " + FIXTURE + "Entity&gt;</b>");
        // A class with no type parameters is labeled with its name alone
        assertThat(node(memberTypeGraph(new GraphVizDotFileOptions()), Holder.class.getName()))
                .contains("<b>GraphVizDotFileTest$Holder</b>");
    }

    /**
     * The first row of a class node gives the modifiers and the kind of the class, with no space before or after
     * them, and a class with no modifiers is labeled with its kind alone.
     */
    @Test
    public void theHeaderGivesTheModifiersAndTheKindOfTheClass() {
        try (var kindScanResult = new ClassGraph().enableClasspath()
                .acceptClasses(Point.class.getName(), PlainInner.class.getName(), Marker.class.getName(),
                        Location.class.getName(), Color.class.getName())
                .enableClassInfo().ignoreClassVisibility().enableInterClassDependencies().scan()) {
            final var point = kindScanResult.getClassInfo(Point.class.getName());
            final var plainInner = kindScanResult.getClassInfo(PlainInner.class.getName());
            assertThat(point.isRecord()).isTrue();
            assertThat(plainInner.getModifiersString()).isEmpty();

            final var classGraph = graph(kindScanResult, new GraphVizDotFileOptions());
            final var dependencyGraph = GraphVizDotFile.generateFromInterClassDependencies(kindScanResult,
                    kindScanResult.getAllClasses());
            for (final var dotFile : List.of(classGraph, dependencyGraph)) {
                assertThat(node(dotFile, Point.class.getName()))
                        .contains(HEADER_ROW + point.getModifiersString() + " record</font>");
                assertThat(node(dotFile, PlainInner.class.getName())).contains(HEADER_ROW + "class</font>");
                assertThat(node(dotFile, Marker.class.getName())).contains(" interface</font>");
                assertThat(node(dotFile, Location.class.getName())).contains(" @interface</font>");
                assertThat(node(dotFile, Color.class.getName())).contains(" enum</font>");
                assertThat(dotFile).doesNotContain(HEADER_ROW + " ", " </font>");
            }
        }
    }

    /**
     * A class that was referred to but not scanned is labeled with its kind only if the reference showed what kind
     * of class it is: an implemented interface is an interface, but the type of a field could be a class or an
     * interface. A header row that would be empty is left out, since GraphViz rejects an empty {@code <font>}
     * element.
     */
    @Test
    public void aClassThatWasNotScannedIsOnlyLabeledWithAKindThatIsKnown() {
        // The module java.base is rejected, since otherwise the scan would read the classfile of Supplier, as the
        // classfile of an interface that an accepted class implements. Supplier itself is not rejected, since a
        // rejected class is never listed as a dependency.
        try (var externalScanResult = new ClassGraph().enableClasspath().acceptClasses(ExternalUser.class.getName())
                .rejectModules("java.base").enableClassInfo().enableFieldInfo().enableInterClassDependencies()
                .enableExternalClasses().scan()) {
            final var supplier = externalScanResult.getClassInfo(Supplier.class.getName());
            final var map = externalScanResult.getClassInfo(Map.class.getName());
            assertThat(supplier.getResource()).isNull();
            assertThat(supplier.isInterface()).isTrue();
            assertThat(map.getResource()).isNull();
            assertThat(map.isInterface()).isFalse();

            final var user = externalScanResult.getAllClasses()
                    .filter(ci -> ExternalUser.class.getName().equals(ci.getName()));
            final var classGraph = GraphVizDotFile.generate(externalScanResult,
                    user.union(user.get(0).getClassDependencies()));
            final var dependencyGraph = GraphVizDotFile.generateFromInterClassDependencies(externalScanResult, user,
                    new GraphVizDotFileOptions().showUnlistedDependencies());
            for (final var dotFile : List.of(classGraph, dependencyGraph)) {
                assertThat(node(dotFile, Supplier.class.getName())).contains(HEADER_ROW + "interface</font>");
                assertThat(node(dotFile, Map.class.getName())).doesNotContain(HEADER_ROW)
                        .doesNotContain("class</font>");
                assertThat(dotFile).doesNotContain("<font point-size='12'></font>");
            }
        }
    }

    /**
     * A class that a graphed class depends upon, but that is not itself in the list of classes to graph, is given a
     * node of its own by default, whether or not the scan enabled external classes, and can be left out. Leaving it
     * out never removes a class that is in the list, external or not.
     */
    @Test
    public void unlistedDependenciesCanBeShownOrHidden() {
        final var derivedOnly = scanResult.getAllClasses()
                .filter(ci -> Derived.class.getName().equals(ci.getName()));
        final var baseNode = '"' + FIXTURE + "Base\"[";
        final var edgeToBase = '"' + Derived.class.getName() + "\" -> \"" + FIXTURE + "Base\"";

        // Base is accepted, so it is a dependency of Derived although the scan did not enable external classes
        assertThat(GraphVizDotFile.generateFromInterClassDependencies(scanResult, derivedOnly)).contains(baseNode,
                edgeToBase);
        assertThat(GraphVizDotFile.generateFromInterClassDependencies(scanResult, derivedOnly,
                new GraphVizDotFileOptions().showUnlistedDependencies())).contains(baseNode, edgeToBase);
        assertThat(GraphVizDotFile.generateFromInterClassDependencies(scanResult, derivedOnly,
                new GraphVizDotFileOptions().hideUnlistedDependencies())).doesNotContain(baseNode, edgeToBase);

        try (var withExternalClasses = scanFixture().enableClassInfo().enableFieldInfo().enableMethodInfo()
                .enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility()
                .ignoreFieldVisibility().ignoreMethodVisibility().enableInterClassDependencies()
                .enableExternalClasses().scan()) {
            assertThat(GraphVizDotFile.generateFromInterClassDependencies(withExternalClasses,
                    withExternalClasses.getAllClasses().filter(ci -> Derived.class.getName().equals(ci.getName()))))
                    .contains(baseNode);
            // The external classes that getAllClasses() returns are in the list, so they are still drawn
            final var allClasses = withExternalClasses.getAllClasses();
            assertThat(allClasses.filter(ClassInfo::isExternalClass)).isNotEmpty();
            final var hidden = GraphVizDotFile.generateFromInterClassDependencies(withExternalClasses, allClasses,
                    new GraphVizDotFileOptions().hideUnlistedDependencies());
            for (final var ci : allClasses) {
                assertThat(hidden).contains('"' + ci.getName() + "\"[");
            }
        }
    }

    /** A graph can only be generated from a scan that collected what the graph shows. */
    @Test
    public void theScanMustHaveCollectedWhatTheGraphShows() {
        final var classes = scanResult.getAllClasses();
        // Accepting classes or packages would enable class info, so a resource path is accepted instead
        try (var withoutClassInfo = new ClassGraph().enableClasspath().acceptPaths("io/github/classgraph/viz")
                .scan()) {
            assertThatThrownBy(() -> GraphVizDotFile.generate(withoutClassInfo, classes))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("enableClassInfo");
        }
        try (var withoutDependencies = scanFixture().enableClassInfo().scan()) {
            assertThatThrownBy(
                    () -> GraphVizDotFile.generateFromInterClassDependencies(withoutDependencies, classes))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("enableInterClassDependencies");
        }
    }

    /**
     * Make a string from code points. A surrogate code point becomes a lone surrogate. The characters these tests
     * need are written as numbers, since most of them are invisible in the source.
     *
     * @param codePoints
     *            the code points.
     * @return the string.
     */
    private static String str(final int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    /**
     * HTML-encode a string for a label.
     *
     * @param str
     *            the string.
     * @return the encoded string.
     */
    private static String htmlEncode(final String str) {
        final var buf = new StringBuilder();
        GraphVizDotFile.htmlEncode(str, buf);
        return buf.toString();
    }

    /**
     * Quote a string as a node identifier.
     *
     * @param str
     *            the string.
     * @return the quoted identifier.
     */
    private static String quotedIdentifier(final String str) {
        final var buf = new StringBuilder();
        GraphVizDotFile.appendQuotedIdentifier(str, buf);
        return buf.toString();
    }

    /**
     * Check that a string holds no character that XML 1.0 does not allow above U+001F, i.e. no lone surrogate, and
     * neither U+FFFE nor U+FFFF. GraphViz parses HTML labels as XML, and a lone surrogate cannot be written as
     * UTF-8.
     *
     * @param str
     *            the string.
     * @return true if the string holds no such character.
     */
    private static boolean isXmlSafe(final String str) {
        // codePoints() returns a lone surrogate as its own value
        return str.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF || cp == 0xFFFE || cp == 0xFFFF);
    }

    /**
     * A lone surrogate, U+FFFE and U+FFFF, which XML 1.0 does not allow, are written in labels as the text
     * {@code \}{@code uXXXX}, while a valid surrogate pair is kept.
     */
    @Test
    public void labelsEscapeCharactersThatXmlDoesNotAllow() {
        assertThat(htmlEncode(str('a', 0xD800, 'b'))).isEqualTo("a&#x5C;ud800b");
        assertThat(htmlEncode(str('a', 0xDC00, 'b'))).isEqualTo("a&#x5C;udc00b");
        assertThat(htmlEncode(str('a', 0xD800))).isEqualTo("a&#x5C;ud800");
        assertThat(htmlEncode(str('a', 0xDC00, 0xD800, 'b'))).isEqualTo("a&#x5C;udc00&#x5C;ud800b");
        assertThat(htmlEncode(str('a', 0xFFFE, 'b'))).isEqualTo("a&#x5C;ufffeb");
        assertThat(htmlEncode(str('a', 0xFFFF, 'b'))).isEqualTo("a&#x5C;uffffb");
        // A valid surrogate pair
        assertThat(htmlEncode(str('a', 0x1F600, 'b'))).isEqualTo(str('a', 0x1F600, 'b'));
    }

    /** Control characters and Unicode whitespace become a plain space in labels, except U+00A0. */
    @Test
    public void labelsTurnWhitespaceIntoSpaces() {
        assertThat(htmlEncode(str('a', 0x2003, 0x85, 0x180E, '\t', 0x3000, 'b'))).isEqualTo("a     b");
        assertThat(htmlEncode(str('a', 0xA0, 'b'))).isEqualTo("a&nbsp;b");
        // ZERO WIDTH SPACE is not a space separator
        assertThat(htmlEncode(str('a', 0x200B, 'b'))).isEqualTo(str('a', 0x200B, 'b'));
    }

    /**
     * A lone surrogate is written in a node identifier as the text {@code \}{@code uXXXX}, while a valid surrogate
     * pair is kept.
     */
    @Test
    public void dotIdentifiersEscapeLoneSurrogates() {
        assertThat(quotedIdentifier(str('a', 0xD800, 'b'))).isEqualTo("\"a\\ud800b\"");
        assertThat(quotedIdentifier(str('a', 0xDC00))).isEqualTo("\"a\\udc00\"");
        assertThat(quotedIdentifier(str('a', 0x1F600, 'b'))).isEqualTo("\"" + str('a', 0x1F600, 'b') + "\"");
    }

    /**
     * Write a class {@code Hostile} with the public int fields {@code f\}{@code uD800x} and
     * {@code g\}{@code uFFFEz}, and a public field {@code h} of type {@code p.Dep\}{@code uDC00}. A modified UTF-8
     * name in a classfile can hold a lone surrogate, and javac cannot write any of these names.
     *
     * @param dir
     *            the directory to write the classfile to.
     * @throws IOException
     *             if the classfile could not be written.
     */
    static void writeHostileClassfile(final Path dir) throws IOException {
        final var hostile = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(hostile)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(10); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF("Hostile");
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5 (writeUTF writes modified UTF-8, which encodes a lone surrogate on its own)
            out.writeByte(1);
            out.writeUTF(str('f', 0xD800, 'x'));
            // #6
            out.writeByte(1);
            out.writeUTF("I");
            // #7
            out.writeByte(1);
            out.writeUTF(str('g', 0xFFFE, 'z'));
            // #8
            out.writeByte(1);
            out.writeUTF("h");
            // #9
            out.writeByte(1);
            out.writeUTF("Lp/Dep" + str(0xDC00) + ";");

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces

            out.writeShort(3); // Number of fields
            for (final int[] field : new int[][] { { 5, 6 }, { 7, 6 }, { 8, 9 } }) {
                out.writeShort(0x0001); // ACC_PUBLIC
                out.writeShort(field[0]); // name
                out.writeShort(field[1]); // descriptor
                out.writeShort(0); // attributes_count
            }

            out.writeShort(0); // Number of methods
            out.writeShort(0); // Number of attributes
        }
        Files.write(dir.resolve("Hostile.class"), hostile.toByteArray());
    }

    /**
     * A class or member name that holds a lone surrogate, U+FFFE or U+FFFF does not stop a graph from being written
     * as UTF-8, and does not put a character that XML does not allow into a label.
     *
     * @param tempDir
     *            the directory to write the classfile and the graphs to.
     * @throws IOException
     *             if a file could not be written or read.
     */
    @Test
    public void namesWithCharactersThatXmlDoesNotAllowCanBeGraphed(@TempDir final Path tempDir) throws IOException {
        final var classesDir = Files.createDirectory(tempDir.resolve("classes"));
        writeHostileClassfile(classesDir);
        try (var hostileScanResult = new ClassGraph().enableClasspathEntries(classesDir).enableClassInfo()
                .enableFieldInfo().enableInterClassDependencies().enableExternalClasses().scan()) {
            final var classes = hostileScanResult.getAllClasses().filter(ci -> "Hostile".equals(ci.getName()));
            assertThat(hostileScanResult.getClassInfo("Hostile").getFieldInfo().getNames())
                    .containsExactlyInAnyOrder(str('f', 0xD800, 'x'), str('g', 0xFFFE, 'z'), "h");

            final var classGraphFile = GraphVizDotFile.write(hostileScanResult, classes,
                    tempDir.resolve("classes.dot"));
            final var classGraph = Files.readString(classGraphFile);
            assertThat(isXmlSafe(classGraph)).as(classGraph).isTrue();
            assertThat(classGraph).contains("<b>f&#x5C;ud800x</b>", "<b>g&#x5C;ufffez</b>",
                    "<td align='right' valign='top'>Dep&#x5C;udc00</td>");

            final var dependencyGraphFile = GraphVizDotFile.writeFromInterClassDependencies(hostileScanResult,
                    classes, tempDir.resolve("dependencies.dot"),
                    new GraphVizDotFileOptions().showUnlistedDependencies());
            final var dependencyGraph = Files.readString(dependencyGraphFile);
            assertThat(isXmlSafe(dependencyGraph)).as(dependencyGraph).isTrue();
            assertThat(dependencyGraph).contains("\"p.Dep\\udc00\"[", "\"Hostile\" -> \"p.Dep\\udc00\"");
        }
    }

    /**
     * A graph can be written to a file.
     *
     * @param tempDir
     *            a temporary directory to write to.
     * @throws Exception
     *             if the file could not be written or read back.
     */
    @Test
    public void graphsCanBeWrittenToAFile(@TempDir final Path tempDir) throws Exception {
        final var classGraphFile = tempDir.resolve("classes.dot");
        assertThat(GraphVizDotFile.write(scanResult, scanResult.getAllClasses(), classGraphFile))
                .isEqualTo(classGraphFile);
        assertThat(Files.readString(classGraphFile))
                .isEqualTo(GraphVizDotFile.generate(scanResult, scanResult.getAllClasses()));

        final var dependencyGraphFile = tempDir.resolve("dependencies.dot");
        assertThat(GraphVizDotFile.writeFromInterClassDependencies(scanResult, scanResult.getAllClasses(),
                dependencyGraphFile)).isEqualTo(dependencyGraphFile);
        assertThat(Files.readString(dependencyGraphFile)).isEqualTo(
                GraphVizDotFile.generateFromInterClassDependencies(scanResult, scanResult.getAllClasses()));
    }

    /** Ten 'x' characters. */
    private static final String X10 = "xxxxxxxxxx";

    /** 100 'x' characters. */
    private static final String X100 = X10 + X10 + X10 + X10 + X10 + X10 + X10 + X10 + X10 + X10;

    /** 1000 'x' characters. */
    private static final String X1000 = X100 + X100 + X100 + X100 + X100 + X100 + X100 + X100 + X100 + X100;

    /** 20000 'x' characters, as a compile-time constant, so that it can be an annotation parameter value. */
    private static final String X20000 = X1000 + X1000 + X1000 + X1000 + X1000 + X1000 + X1000 + X1000 + X1000
            + X1000 + X1000 + X1000 + X1000 + X1000 + X1000 + X1000 + X1000 + X1000 + X1000 + X1000;

    /** Ten backslashes. */
    private static final String B10 = "\\\\\\\\\\\\\\\\\\\\";

    /** 100 backslashes. */
    private static final String B100 = B10 + B10 + B10 + B10 + B10 + B10 + B10 + B10 + B10 + B10;

    /** 1000 backslashes. */
    private static final String B1000 = B100 + B100 + B100 + B100 + B100 + B100 + B100 + B100 + B100 + B100;

    /**
     * 3000 backslashes. {@link io.github.classgraph.AnnotationInfo#toString()} writes each as two, and the graph
     * writes each of those as a six-character entity.
     */
    private static final String B3000 = B1000 + B1000 + B1000;

    /** An annotation that is given a long parameter value. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface LongValue {
        /**
         * The value.
         *
         * @return the value.
         */
        String value();
    }

    /** A class with long annotations on itself, a field, a method and a method parameter. */
    @LongValue(X20000)
    public static class LongAnnotated {
        /** A field with an annotation that is long after escaping. */
        @LongValue(B3000)
        public int field;

        /**
         * A method with a long annotation, and a parameter with a long annotation.
         *
         * @param parameter
         *            the parameter.
         */
        @LongValue(X20000)
        public void method(@LongValue(X20000) final int parameter) {
            // Empty
        }
    }

    /**
     * Annotations are cut short in labels. GraphViz cannot read a run of text between two tags of 16382 characters
     * or more, and rejects the whole file, so one long annotation, such as the {@code ScalaSignature} of a Scala
     * class or the {@code Metadata} of a Kotlin class, would otherwise keep the whole graph from being drawn.
     */
    @Test
    public void longAnnotationsAreCutShort() {
        try (var longScanResult = new ClassGraph().enableClasspath()
                .acceptClasses(LongAnnotated.class.getName(), LongValue.class.getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo().scan()) {
            final var classGraph = graph(longScanResult, new GraphVizDotFileOptions());
            for (final var textRun : classGraph.split("[<>]")) {
                assertThat(textRun).hasSizeLessThan(16382);
            }
            // The annotation is cut after 200 characters, the first 12 of which are '@LongValue("'. It is cut on the
            // class, the method and the method parameter, and the backslashes of the field annotation are cut too.
            final var cutXs = "@LongValue(&quot;" + "x".repeat(188) + "...";
            assertThat(classGraph.split(Pattern.quote(cutXs), -1)).hasSize(4);
            assertThat(classGraph).contains("@LongValue(&quot;" + "&#x5C;".repeat(188) + "...")
                    .doesNotContain("x".repeat(189), "&#x5C;".repeat(189));
        }
    }

    /** An annotation is cut short after 200 characters, but never inside a surrogate pair. */
    @Test
    public void annotationsAreCutShortOutsideSurrogatePairs() {
        final var x200 = X100 + X100;
        assertThat(GraphVizDotFile.truncateAnnotation(x200)).isEqualTo(x200);
        assertThat(GraphVizDotFile.truncateAnnotation(x200 + "y")).isEqualTo(x200 + "...");
        final var surrogatePairAtTheCut = X100 + X100.substring(1) + str(0x1F600) + "y";
        assertThat(GraphVizDotFile.truncateAnnotation(surrogatePairAtTheCut))
                .isEqualTo(X100 + X100.substring(1) + "...");
    }

    /** Null arguments are rejected. */
    @Test
    public void nullArgumentsAreRejected() {
        final var classes = scanResult.getAllClasses();
        final var options = new GraphVizDotFileOptions();
        final var file = Path.of("classes.dot");

        rejectsNull(() -> GraphVizDotFile.generate(null, classes));
        rejectsNull(() -> GraphVizDotFile.generate(scanResult, null));
        rejectsNull(() -> GraphVizDotFile.generate(scanResult, classes, null));
        rejectsNull(() -> GraphVizDotFile.write(scanResult, classes, null));
        rejectsNull(() -> GraphVizDotFile.write(scanResult, classes, null, options));
        rejectsNull(() -> GraphVizDotFile.write(scanResult, classes, file, null));

        rejectsNull(() -> GraphVizDotFile.generateFromInterClassDependencies(null, classes));
        rejectsNull(() -> GraphVizDotFile.generateFromInterClassDependencies(scanResult, null));
        rejectsNull(() -> GraphVizDotFile.generateFromInterClassDependencies(scanResult, classes, null));
        rejectsNull(() -> GraphVizDotFile.writeFromInterClassDependencies(scanResult, classes, null));
        rejectsNull(() -> GraphVizDotFile.writeFromInterClassDependencies(scanResult, classes, null, options));
        rejectsNull(() -> GraphVizDotFile.writeFromInterClassDependencies(scanResult, classes, file, null));
    }
}
