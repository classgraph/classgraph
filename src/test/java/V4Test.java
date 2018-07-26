import java.io.IOException;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class V4Test {

    public static void main(final String[] args) throws IOException {
        final long t0 = System.nanoTime();
        new FastClasspathScanner() //
                // .verbose() //
                // .enableSystemPackages() //
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
