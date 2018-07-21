import java.io.IOException;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class V4Test {

    public static void main(final String[] args) throws IOException {
        //final ScanResult scanResult = //
        new FastClasspathScanner() //
                // .verbose() //
                .unBlacklistSystemPackages() //
                .disableClassfileScanning() //
                .scan() //
                .getResourcesWithExtension("png") // 
                .forEachByteArrayThenClose((res, arr) -> {
                    final String string = new String(arr);
                    System.out.println("======================================");
                    System.out.println(res.getPathRelativeToPackageRoot());
                    System.out.println(string.substring(0, Math.min(120, string.length())));
                });
    }

}
