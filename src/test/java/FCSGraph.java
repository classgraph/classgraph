import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class FCSGraph {

    public static void main(final String[] args) {
        final ScanResult scanResult = new FastClasspathScanner( //
                "io.github.lukehutch.fastclasspathscanner", //
                "-io.github.lukehutch.fastclasspathscanner.issues",
                "-io.github.lukehutch.fastclasspathscanner.test") //
                        .enableMethodInfo() //
                        .ignoreMethodVisibility() //
                        .enableFieldInfo() //
                        .ignoreFieldVisibility() //
                        .scan();
        System.out.println(scanResult.generateClassGraphDotFile(12, 8, false, false));
    }

}
