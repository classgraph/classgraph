import java.io.IOException;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class V4Test {

    public static void main(final String[] args) throws IOException {
        //        new FastClasspathScanner() //
        //                // .verbose() //
        //                .unBlacklistSystemPackages() //
        //                .enableClassInfo() //
        //                .scan() //
        //                .getResourcesWithExtension("png") // 
        //                .forEachByteArrayThenClose((res, arr) -> {
        //                    final String string = new String(arr);
        //                    System.out.println("======================================");
        //                    System.out.println(res.getPathRelativeToPackageRoot());
        //                    System.out.println(string.substring(0, Math.min(120, string.length())));
        //                });

        final long t0 = System.nanoTime();
        new FastClasspathScanner() //
                // .verbose() //
                .unBlacklistSystemPackages() //
                .enableClassInfo()
                //.enableMethodInfo().enableFieldInfo() //
                .ignoreClassVisibility() //
                // .ignoreMethodVisibility().ignoreFieldVisibility() //
                // .whitelistPackages("com.xyz") //
                .scan() //
        // .getAllClasses() //
        // .getClassNames() //
        ;//.forEach(System.out::println);
        System.out.println((System.nanoTime() - t0) * 1.0e-9);
    }

}
