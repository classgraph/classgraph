import java.io.IOException;
import java.io.PrintWriter;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.ScanResult;

public class FCSGraph {
    public static void main(final String[] args) throws IOException {
        final ScanResult scanResult = new FastClasspathScanner() //
                .whitelistPackages("io.github.lukehutch.fastclasspathscanner")
                .blacklistPackages("io.github.lukehutch.fastclasspathscanner.issues",
                        "io.github.lukehutch.fastclasspathscanner.test",
                        "io.github.lukehutch.fastclasspathscanner.json",
                        "io.github.lukehutch.fastclasspathscanner.utils",
                        "io.github.lukehutch.fastclasspathscanner.classloaderhandler") //
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
