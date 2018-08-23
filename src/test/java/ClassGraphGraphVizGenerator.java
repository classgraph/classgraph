import java.io.IOException;
import java.io.PrintWriter;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class ClassGraphGraphVizGenerator {
    public static void main(final String[] args) throws IOException {
        try (ScanResult scanResult = new ClassGraph() //
                .whitelistPackagesNonRecursive("io.github.classgraph") //
                .enableMethodInfo() //
                .ignoreMethodVisibility() //
                .enableFieldInfo() //
                .ignoreFieldVisibility() //
                .enableAnnotationInfo() //
                // .verbose() //
                .scan()) {
            final String fileName = "/tmp/graph.dot";
            try (PrintWriter writer = new PrintWriter(fileName)) {
                writer.print(
                        scanResult.getAllClasses().generateGraphVizDotFile(12, 8, false, true, false, true, true));
            }
            System.out.println("Wrote " + fileName);
        }
    }
}
