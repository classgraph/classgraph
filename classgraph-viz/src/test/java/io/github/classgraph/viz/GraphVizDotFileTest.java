package io.github.classgraph.viz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/** Tests the graphs written by {@link GraphVizDotFile}. */
public class GraphVizDotFileTest {
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
    public static class Derived extends Base implements Marker, Serializable {
        /** Removed from the graph, since it is an implementation detail of serialization. */
        private static final long serialVersionUID = 1L;

        /** Set by a static initializer, so that the class has a {@code <clinit>} method. */
        static final int INITIALIZED;

        static {
            INITIALIZED = 1;
        }

        /** A field with a generic type, an annotation, and a name that has to be escaped in the graph. */
        @Indexed
        public Map<String, List<Base>> \u00A3prices = Map.of();

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
        return new ClassGraph().acceptClasses(Base.class.getName(), Marker.class.getName(), Derived.class.getName(),
                Escaped.class.getName(), Location.class.getName(), Indexed.class.getName(), Cached.class.getName(),
                Deprecated2.class.getName(), NotBlank.class.getName());
    }

    /** Scan the fixture classes. */
    @BeforeAll
    static void scan() {
        scanResult = scanFixture().enableAllInfo().enableInterClassDependencies().scan();
        // enableInterClassDependencies() would ignore the visibility modifiers, so it is not called here
        publicOnlyScanResult = scanFixture().enableFieldInfo().enableMethodInfo().enableAnnotationInfo().scan();
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
                new GraphVizDotFileOptions().layoutSize(20, 20));
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
        assertThat(graph()).contains("@" + FIXTURE + "Location(&quot;&amp;&lt;&gt;&#x5C;&quot;&#x27;&#x2F;&quot;)");
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
                "@" + FIXTURE + "Location(&quot;C:&#x5C;&#x5C;Windows&quot;)",
                // A field, with its annotation, its modifiers and its type
                "@" + FIXTURE + "Indexed public Map&lt;String, List&lt;Base&gt;&gt;",
                // A method, with its parameter types and parameter names
                "<b>find</b>", "String <B>key</B>, int <B>maximumNumberOfResults</B>",
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
        assertThat(graph(scanResult, new GraphVizDotFileOptions().useFullyQualifiedNames()))
                .contains("public java.util.Map&lt;java.lang.String, java.util.List&lt;" + FIXTURE + "Base&gt;&gt;")
                .contains("java.lang.String <B>key</B>");
        assertThat(graph()).contains("public Map&lt;String, List&lt;Base&gt;&gt;").contains("String <B>key</B>")
                .doesNotContain("java.lang.String <B>key</B>");
    }

    /**
     * A row of annotations or of parameters that is wider than the wrap width is continued in the same column of a
     * new row, rather than stretching the class node out sideways.
     */
    @Test
    public void wideRowsOfAnnotationsAndParametersAreWrapped() {
        // The two annotations of Derived#find() are far wider than the wrap width, so the second one starts a new
        // row of the method table, and the parameters of the method are then also pushed onto a row of their own
        assertThat(graph()).contains("@" + FIXTURE + "Cached</td><td></td><td></td></tr><tr>", "@" + FIXTURE
                + "NotBlank</td></tr><tr><td></td><td></td><td align='left' valign='top'>" + "String <B>key</B>");
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
        assertThat(dotFile).contains("<b>Derived</b>",
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Base\" [arrowsize=2.5]");
    }

    /** A class node lists only what the scan collected. */
    @Test
    public void aClassNodeListsOnlyWhatTheScanCollected() {
        try (var classInfoOnly = scanFixture().enableClassInfo().scan()) {
            assertThat(graph(classInfoOnly, new GraphVizDotFileOptions())).contains("<b>Derived</b>")
                    .doesNotContain("ANNOTATIONS", "FIELDS", "METHODS");
        }
    }

    /**
     * The edges from an annotation say whether it annotates the class itself, or one of its fields or methods.
     */
    @Test
    public void annotationEdgesSayWhatTheAnnotationAnnotates() {
        assertThat(graph()).contains(
                // Class annotation
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Location\" [arrowhead=dot, arrowsize=2.5]",
                // Field annotation
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Indexed\" [arrowhead=odot, arrowsize=2.5]",
                // Method annotation
                "\"" + FIXTURE + "Derived\" -> \"" + FIXTURE + "Cached\" [arrowhead=odot, arrowsize=2.5]");
    }

    /** A class is joined to the types of its fields and to the types of its methods, by different edges. */
    @Test
    public void classesAreJoinedToTheTypesOfTheirMembers() {
        assertThat(graph()).contains(
                // Field type
                "\"" + FIXTURE + "Base\" -> \"" + FIXTURE + "Derived\" [arrowtail=obox, arrowsize=2.5, dir=back]",
                // Parameter type of a method
                "\"" + FIXTURE + "NotBlank\" -> \"" + FIXTURE
                        + "Derived\" [arrowtail=box, arrowsize=2.5, dir=back]");
    }

    /**
     * A class that a graphed class depends upon, but that is not itself in the list of classes to graph, is only
     * given a node of its own if the graph is asked to include external classes.
     */
    @Test
    public void externalClassesCanBeShownOrHiddenInTheDependencyGraph() {
        final var derivedOnly = scanResult.getAllClasses()
                .filter(ci -> Derived.class.getName().equals(ci.getName()));
        final var baseNode = '"' + FIXTURE + "Base\"[";

        assertThat(GraphVizDotFile.generateFromInterClassDependencies(scanResult, derivedOnly,
                new GraphVizDotFileOptions().includeExternalClasses())).contains(baseNode);
        assertThat(GraphVizDotFile.generateFromInterClassDependencies(scanResult, derivedOnly,
                new GraphVizDotFileOptions().excludeExternalClasses())).doesNotContain(baseNode);

        // With no instruction either way, the graph follows the scan, which did not enable external classes
        assertThat(GraphVizDotFile.generateFromInterClassDependencies(scanResult, derivedOnly))
                .doesNotContain(baseNode);
        try (var withExternalClasses = scanFixture().enableAllInfo().enableInterClassDependencies()
                .enableExternalClasses().scan()) {
            assertThat(GraphVizDotFile.generateFromInterClassDependencies(withExternalClasses,
                    withExternalClasses.getAllClasses().filter(ci -> Derived.class.getName().equals(ci.getName()))))
                    .contains(baseNode);
        }
    }

    /** A graph can only be generated from a scan that collected what the graph shows. */
    @Test
    public void theScanMustHaveCollectedWhatTheGraphShows() {
        final var classes = scanResult.getAllClasses();
        // Accepting classes or packages would enable class info, so a resource path is accepted instead
        try (var withoutClassInfo = new ClassGraph().acceptPaths("io/github/classgraph/viz").scan()) {
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
