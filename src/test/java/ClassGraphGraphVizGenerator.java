import java.io.IOException;
import java.io.PrintWriter;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

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
        try (ScanResult scanResult = new ClassGraph() //
                .whitelistPackagesNonRecursive("io.github.classgraph") //
                .enableMethodInfo() //
                .ignoreMethodVisibility() //
                .enableFieldInfo() //
                .ignoreFieldVisibility() //
                .enableAnnotationInfo() //
                // .enableInterClassDependencies() //
                // .verbose() //
                .scan()) {
            final String fileName = "/tmp/graph.dot";
            try (PrintWriter writer = new PrintWriter(fileName)) {
                writer.print(scanResult.getAllClasses()
                        // .generateGraphVizDotFileFromClassDependencies()
                        .generateGraphVizDotFile(12, 8, false, true, false, true, true));
            }
            System.out.println("Wrote " + fileName);
        }
    }
}
