import java.io.IOException;
import java.io.PrintWriter;

import io.github.fastclasspathscanner.FastClasspathScanner;
import io.github.fastclasspathscanner.ScanResult;

public class FCSGraph {
    public static void main(final String[] args) throws IOException {
        final ScanResult scanResult = new FastClasspathScanner() //
                .whitelistPackages("io.github.fastclasspathscanner")
                .blacklistPackages("io.github.fastclasspathscanner.issues", "io.github.fastclasspathscanner.test",
                        "io.github.fastclasspathscanner.json", "io.github.fastclasspathscanner.utils",
                        "io.github.fastclasspathscanner.classloaderhandler") //
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
