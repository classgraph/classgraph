package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A class depends on the classes named by the descriptors in its constant pool, as well as on the classes named by
 * its {@code CONSTANT_Class} entries. The test classes are compiled when the test runs, as top-level classes in
 * package {@code pd}: javac gives a class a {@code CONSTANT_Class} entry for each nested class whose name it uses,
 * so a nested class would be found as a dependency whether or not the descriptors are read.
 */
public class DescriptorDependencyTest {
    /**
     * Compile classes in package {@code pd}.
     *
     * @param dir
     *            the directory to write the source files to, and to compile the classes into.
     * @param sources
     *            the source of each class, without the package declaration, keyed by its simple name.
     * @return the directory holding the compiled classes.
     * @throws IOException
     *             if a source file could not be written.
     */
    private static Path compile(final Path dir, final Map<String, String> sources) throws IOException {
        final var sourceDir = Files.createDirectories(dir.resolve("src").resolve("pd"));
        final var classesDir = dir.resolve("classes");
        final List<String> args = new ArrayList<>(List.of("-nowarn", "-d", classesDir.toString()));
        for (final var ent : sources.entrySet()) {
            final var sourceFile = sourceDir.resolve(ent.getKey() + ".java");
            Files.writeString(sourceFile, "package pd; " + ent.getValue());
            args.add(sourceFile.toString());
        }
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the JDK's compiler, which the test JVM has to be running on").isNotNull();
        assertThat(compiler.run(null, null, null, args.toArray(String[]::new))).as("javac exit code").isZero();
        return classesDir;
    }

    /**
     * Scan a directory of classes with inter-class dependencies enabled, and get the dependencies of one class.
     *
     * @param classesDir
     *            the directory of classes.
     * @param className
     *            the name of the class.
     * @return the names of the classes that the class depends on.
     */
    private static List<String> dependencies(final Path classesDir, final String className) {
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspathEntries(classesDir.toString())
                .enableInterClassDependencies().scan()) {
            final var classInfo = scanResult.getClassInfo(className);
            assertThat(classInfo).as(className).isNotNull();
            return classInfo.getClassDependencies().getNames();
        }
    }

    /**
     * javac compiles a method reference to an {@code invokedynamic} instruction whose bootstrap arguments include a
     * {@code CONSTANT_MethodType} entry, the type of the functional interface's method with the interface's type
     * arguments filled in. Resolving that entry loads every class its descriptor names (JVMS 5.4.3.5), so a class
     * named only there is a dependency: evaluating the method reference here fails with a
     * {@link NoClassDefFoundError} if {@code Bar} cannot be loaded.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void aClassNamedOnlyByAMethodTypeConstantIsADependency(@TempDir final Path tempDir) throws IOException {
        final var classesDir = compile(tempDir, Map.of( //
                "Bar", "public class Bar {}", //
                "UsesMethodType", "public class UsesMethodType { "
                        + "void m() { java.util.function.Function<Bar, String> f = Object::toString; } }"));
        // The only place UsesMethodType names Bar is the method type "(Lpd/Bar;)Ljava/lang/String;"
        assertThat(dependencies(classesDir, "pd.UsesMethodType")).contains("pd.Bar");
    }

    /**
     * A field descriptor may be an array type. Reading a field of type {@code C[]} or {@code E[][]} names {@code C}
     * and {@code E} only in the descriptors {@code [Lpd/C;} and {@code [[Lpd/E;}, so the element class has to be
     * read from the array descriptor.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void theElementClassOfAnArrayTypedFieldIsADependency(@TempDir final Path tempDir) throws IOException {
        final var classesDir = compile(tempDir, Map.of( //
                "C", "public class C {}", //
                "E", "public class E {}", //
                "Holder", "public class Holder { public static C[] cs; public static E[][] es; }", //
                "UsesArrays",
                "public class UsesArrays { int n() { return Holder.cs.length + Holder.es.length; } }"));
        // Holder is named by the CONSTANT_Class entry of each field reference
        assertThat(dependencies(classesDir, "pd.UsesArrays")).contains("pd.Holder", "pd.C", "pd.E");
    }
}
