import java.io.IOException;
import java.nio.file.Path;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.viz.GraphVizDotFile;
import io.github.classgraph.viz.GraphVizDotFileOptions;

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
                // .enableInterClassDependencies() // .verbose() //
                .scan()) {
            final var file = Path.of("/tmp/graph.dot");
            GraphVizDotFile.write(scanResult, scanResult.getAllClasses(), file,
                    new GraphVizDotFileOptions().layoutSize(12, 8).hideFields().hideMethods());
            System.out.println("Wrote " + file);
        }
    }
}
