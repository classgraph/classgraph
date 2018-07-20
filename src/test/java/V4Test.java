import java.io.IOException;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.Resource;
import io.github.lukehutch.fastclasspathscanner.ScanResult;

public class V4Test {

    public static void main(final String[] args) throws IOException {
        final ScanResult scanResult = new FastClasspathScanner("!!") //
                // .verbose() //
                .scan();
        for (final Resource res : scanResult.getAllResources()) {
            if (!res.getPathRelativeToPackageRoot().endsWith(".class")) {
                final String string = new String(res.load());
                System.out.println("======================================");
                System.out.println(res);
                System.out.println(string.substring(0, Math.min(40, string.length())));
            }
        }
    }

}
