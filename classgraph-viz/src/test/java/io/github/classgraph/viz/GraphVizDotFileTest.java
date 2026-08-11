package io.github.classgraph.viz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /** The class that is graphed. */
    @Location("C:\\Windows")
    public static class Derived extends Base implements Marker {
    }

    /** The prefix that all the fixture class names share. */
    private static final String FIXTURE = GraphVizDotFileTest.class.getName() + "$";

    /** A scan of the fixture classes. */
    private static ScanResult scanResult;

    /** Scan the fixture classes. */
    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph()
                .acceptClasses(Base.class.getName(), Marker.class.getName(), Derived.class.getName())
                .enableAllInfo().enableInterClassDependencies().scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
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
        assertThat(GraphVizDotFile.generate(scanResult, scanResult.getAllClasses()))
                .contains("C:&#x5C;&#x5C;Windows").doesNotContain("&lsol;", "&bsol;");
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
