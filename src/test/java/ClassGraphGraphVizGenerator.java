import java.io.IOException;
import java.io.PrintWriter;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.GraphVizDotFileOptions;

/**
 * ClassGraphGraphVizGenerator.
 */
public class ClassGraphGraphVizGenerator {
    /**
     * The main method.
     *
     * @param args
     *            the arguments
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public static void main(final String[] args) throws IOException {
        try (var scanResult = new ClassGraph() //
                .acceptPackagesNonRecursive("io.github.classgraph") //
                .enableMethodInfo() //
                .ignoreMethodVisibility() //
                .enableFieldInfo() //
                .ignoreFieldVisibility() //
                .enableAnnotationInfo() //
                // .enableInterClassDependencies() //
                // .verbose() //
                .scan()) {
            final var fileName = "/tmp/graph.dot";
            try (var writer = new PrintWriter(fileName)) {
                writer.print(scanResult.getAllClasses()
                        // .generateGraphVizDotFileFromClassDependencies()
                        .generateGraphVizDotFile(
                                new GraphVizDotFileOptions().layoutSize(12, 8).hideFields().hideMethods()));
            }
            System.out.println("Wrote " + fileName);
        }
    }
}
