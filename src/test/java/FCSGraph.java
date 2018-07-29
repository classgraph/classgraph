import java.io.IOException;
import java.io.PrintWriter;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class FCSGraph {
    public static void main(final String[] args) throws IOException {
        final ScanResult scanResult = new ClassGraph() //
                .whitelistPackages("io.github.classgraph")
                .blacklistPackages("io.github.classgraph.issues", "io.github.classgraph.test",
                        "io.github.classgraph.json", "io.github.classgraph.utils",
                        "io.github.classgraph.classloaderhandler") //
                .enableMethodInfo() //
                .ignoreMethodVisibility() //
                .enableFieldInfo() //
                .ignoreFieldVisibility() //
                .enableAnnotationInfo() //
                // .verbose() //
                .scan();
        try (PrintWriter writer = new PrintWriter("/tmp/graph.dot")) {
            writer.print(scanResult.getAllClasses().generateGraphVizDotFile(12, 8, false, false, true));
        }
    }
}
