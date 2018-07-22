import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.ScanResult;

public class FCSGraph {

    public static void main(final String[] args) {
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
                .verbose().scan();
        System.out.println(scanResult.generateClassGraphDotFile(12, 8, false, false));
    }

}
