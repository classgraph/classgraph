import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class FCSGraph {

    public static void main(final String[] args) {
        System.out.println(new FastClasspathScanner( //
                "io.github.lukehutch.fastclasspathscanner", //
                "-io.github.lukehutch.fastclasspathscanner.issues",
                "-io.github.lukehutch.fastclasspathscanner.test") //
                        .enableMethodInfo() //
                        .ignoreMethodVisibility() //
                        .enableFieldInfo() //
                        .ignoreFieldVisibility() //
                        .scan() //
                        .generateClassGraphDotFile(12, 8, false, false));
    }

}
