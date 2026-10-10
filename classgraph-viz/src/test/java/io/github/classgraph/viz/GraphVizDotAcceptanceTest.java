package io.github.classgraph.viz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Tests that GraphViz can read the graphs written by {@link GraphVizDotFile}, by running {@code dot} on them. The
 * other tests check parts of the output as text, and so cannot find a way of breaking the DOT or HTML-like label
 * syntax that nobody has thought of. The test is skipped if {@code dot} is not on the path.
 */
public class GraphVizDotAcceptanceTest {
    /** How long {@code dot} is given to lay out one graph. Each graph here takes well under a second. */
    private static final long DOT_TIMEOUT_SECONDS = 60;

    /** The option sets that every class graph is written with. */
    private static final Map<String, GraphVizDotFileOptions> CLASS_GRAPH_OPTIONS = Map.of( //
            "default", new GraphVizDotFileOptions(), //
            "qualified", new GraphVizDotFileOptions().useFullyQualifiedNames(), //
            "bare", new GraphVizDotFileOptions().hideFields().hideMethods().hideAnnotations());

    /** The option sets that every dependency graph is written with. */
    private static final Map<String, GraphVizDotFileOptions> DEPENDENCY_GRAPH_OPTIONS = Map.of( //
            "default", new GraphVizDotFileOptions(), //
            "listed", new GraphVizDotFileOptions().hideUnlistedDependencies());

    /**
     * Check whether {@code dot} can be run.
     *
     * @return true if {@code dot -V} ran and exited with status 0.
     */
    private static boolean dotIsInstalled() {
        try {
            final var process = new ProcessBuilder("dot", "-V").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(DOT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (final IOException _) {
            // Not installed
            return false;
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Run {@code dot} on a graph, and check that it read the graph without an error.
     *
     * @param dotFile
     *            the .dot file.
     * @throws IOException
     *             if {@code dot} could not be run, or its output could not be read.
     * @throws InterruptedException
     *             if the thread was interrupted while waiting for {@code dot}.
     */
    private static void assertDotAccepts(final Path dotFile) throws IOException, InterruptedException {
        // The output goes to a file rather than a pipe, so that a dot that hangs cannot block the read, and the
        // timeout still applies
        final var outputFile = Path.of(dotFile + ".out");
        final var process = new ProcessBuilder("dot", "-Tplain", dotFile.toString()).redirectErrorStream(true)
                .redirectOutput(outputFile.toFile()).start();
        final var finished = process.waitFor(DOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        assertThat(finished).as("dot -Tplain %s did not finish in %d seconds", dotFile, DOT_TIMEOUT_SECONDS)
                .isTrue();
        assertThat(process.exitValue())
                .as("dot -Tplain %s exited with an error:%n%s", dotFile, Files.readString(outputFile)).isZero();
    }

    /**
     * Write the class graph of every class in a scan, and the dependency graph of every scanned class, with each
     * option set, and check that {@code dot} reads each of them.
     *
     * @param scanResult
     *            the scan result.
     * @param name
     *            a name for the scan, used in the names of the .dot files.
     * @param dir
     *            the directory to write the .dot files to.
     * @throws IOException
     *             if a file could not be written, or {@code dot} could not be run.
     * @throws InterruptedException
     *             if the thread was interrupted while waiting for {@code dot}.
     */
    private static void assertDotAcceptsGraphsOf(final ScanResult scanResult, final String name, final Path dir)
            throws IOException, InterruptedException {
        // The class graph has a node for each external class too, which is labeled differently
        final var allClasses = scanResult.getAllClasses();
        for (final var options : CLASS_GRAPH_OPTIONS.entrySet()) {
            assertDotAccepts(GraphVizDotFile.write(scanResult, allClasses,
                    dir.resolve(name + "-classes-" + options.getKey() + ".dot"), options.getValue()));
        }
        // The dependency graph lists only the scanned classes, so that hiding unlisted dependencies leaves out the
        // external classes
        final var scannedClasses = allClasses.filter(ci -> !ci.isExternalClass());
        assertThat(scannedClasses).isNotEmpty();
        for (final var options : DEPENDENCY_GRAPH_OPTIONS.entrySet()) {
            assertDotAccepts(GraphVizDotFile.writeFromInterClassDependencies(scanResult, scannedClasses,
                    dir.resolve(name + "-dependencies-" + options.getKey() + ".dot"), options.getValue()));
        }
    }

    /**
     * Start a scan that collects everything that a graph can show, including non-public classes and members.
     *
     * @return the {@link ClassGraph} to scan with.
     */
    private static ClassGraph scanEverything() {
        return new ClassGraph().enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().enableInterClassDependencies().enableExternalClasses();
    }

    /**
     * GraphViz reads the graphs of the classes of this package, which are the classes of the viz module and the
     * fixtures of its tests: among them a record, nested classes with the same simple name, a class whose name ends
     * in '$', and classes with long annotations and with annotations whose values have to be escaped.
     *
     * @param tempDir
     *            the directory to write the .dot files to.
     * @throws Exception
     *             if a file could not be written, or {@code dot} could not be run.
     */
    @Test
    public void dotReadsTheGraphsOfThisPackage(@TempDir final Path tempDir) throws Exception {
        assumeTrue(dotIsInstalled(), "dot is not installed");
        try (var scanResult = scanEverything().enableClasspath()
                .acceptPackagesNonRecursive(GraphVizDotAcceptanceTest.class.getPackageName()).scan()) {
            assertDotAcceptsGraphsOf(scanResult, "package", tempDir);
        }
    }

    /**
     * GraphViz reads the graphs of classfiles that javac cannot write: names with a lone surrogate or U+FFFE,
     * method parameters with no type, and members whose type descriptor does not parse.
     *
     * @param tempDir
     *            the directory to write the classfiles and the .dot files to.
     * @throws Exception
     *             if a file could not be written, or {@code dot} could not be run.
     */
    @Test
    public void dotReadsTheGraphsOfUnusualClassfiles(@TempDir final Path tempDir) throws Exception {
        assumeTrue(dotIsInstalled(), "dot is not installed");
        final var classesDir = Files.createDirectory(tempDir.resolve("classes"));
        GraphVizDotFileTest.writeHostileClassfile(classesDir);
        GraphVizDotFileTest.writeUntypedParameterClassfile(classesDir);
        GraphVizDotFileTest.writeBrokenTypeDescriptorClassfiles(classesDir);
        try (var scanResult = scanEverything().enableClasspathEntries(classesDir).scan()) {
            assertDotAcceptsGraphsOf(scanResult, "classfiles", tempDir);
        }
    }
}
