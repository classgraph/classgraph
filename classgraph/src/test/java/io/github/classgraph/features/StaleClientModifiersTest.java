package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * A classfile names other classes, and says things about them: that a class it implements is an interface, that a
 * class it is annotated with is an annotation, and (in its {@code InnerClasses} attribute) what the modifiers of a
 * nested class are. These claims are only as current as the version of the other class that the classfile was
 * compiled against. Here the client classes are compiled against version 1 of a library, and scanned with version
 * 2, in which every one of those claims is false. The kind and modifiers of a scanned library class must come from
 * the library's own classfiles, whichever classfile is linked first. The test classes are compiled when the test
 * runs.
 */
public class StaleClientModifiersTest {
    /** Version 1 of the library, which the client is compiled against. */
    private static final Map<String, String> LIB_V1 = Map.of( //
            "Outer",
            "public class Outer { public static final class Inner {} protected static class Prot {} "
                    + "public abstract static class Abs {} }", //
            "Foo", "public interface Foo { default void m() {} }", //
            "AnnT", annotation("AnnT", "TYPE"), //
            "AnnF", annotation("AnnF", "FIELD"), //
            "AnnM", annotation("AnnM", "METHOD"), //
            "AnnP", annotation("AnnP", "PARAMETER"));

    /** Version 2 of the library, which is scanned. */
    private static final Map<String, String> LIB_V2 = Map.of( //
            "Outer",
            "public class Outer { public static class Inner {} public static class Prot {} "
                    + "public static class Abs {} }", //
            "Foo", "public abstract class Foo { public void m() {} }", //
            "AnnT", "public class AnnT {}", //
            "AnnF", "public class AnnF {}", //
            "AnnM", "public class AnnM {}", //
            "AnnP", "public class AnnP {}");

    /**
     * The client, which uses every claim that version 2 of the library makes false. The annotated field and method
     * are public, since by default the scan reads only public fields and methods.
     */
    private static final Map<String, String> CLIENT = Map.of( //
            "C",
            "@lib.AnnT public class C implements lib.Foo { @lib.AnnF public int f; "
                    + "@lib.AnnM public void g(@lib.AnnP int p) {} lib.Outer.Inner i; lib.Outer.Abs a; }", //
            "D", "public class D extends lib.Outer { Prot p; }");

    /**
     * The source of a runtime-retained annotation type.
     *
     * @param name
     *            the simple name of the annotation type.
     * @param target
     *            the {@link java.lang.annotation.ElementType} it applies to.
     * @return the source.
     */
    private static String annotation(final String name, final String target) {
        return "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                + "@java.lang.annotation.Target(java.lang.annotation.ElementType." + target + ") public @interface "
                + name + " {}";
    }

    /**
     * Compile classes in one package.
     *
     * @param dir
     *            the directory to write the source files to, and to compile the classes into.
     * @param pkg
     *            the package name.
     * @param sources
     *            the source of each class, without the package declaration, keyed by its simple name.
     * @param classpath
     *            the classpath to compile against, or null for none.
     * @return the directory holding the compiled classes.
     * @throws IOException
     *             if a source file could not be written.
     */
    private static Path compile(final Path dir, final String pkg, final Map<String, String> sources,
            final @Nullable Path classpath) throws IOException {
        final var sourceDir = Files.createDirectories(dir.resolve("src").resolve(pkg));
        final var classesDir = dir.resolve("classes");
        final List<String> args = new ArrayList<>(List.of("-nowarn", "-d", classesDir.toString()));
        if (classpath != null) {
            args.addAll(List.of("-cp", classpath.toString()));
        }
        for (final var ent : sources.entrySet()) {
            final var sourceFile = sourceDir.resolve(ent.getKey() + ".java");
            Files.writeString(sourceFile, "package " + pkg + "; " + ent.getValue());
            args.add(sourceFile.toString());
        }
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the JDK's compiler, which the test JVM has to be running on").isNotNull();
        assertThat(compiler.run(null, null, null, args.toArray(String[]::new))).as("javac exit code").isZero();
        return classesDir;
    }

    /**
     * Compile version 2 of the library, and the client against version 1.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @return the classes directories of version 2 of the library and of the client, in that order.
     * @throws IOException
     *             if a source file could not be written.
     */
    private static Path[] compileLibV2AndClient(final Path tempDir) throws IOException {
        final var libV1 = compile(tempDir.resolve("v1"), "lib", LIB_V1, null);
        final var client = compile(tempDir.resolve("client"), "client", CLIENT, libV1);
        final var libV2 = compile(tempDir.resolve("v2"), "lib", LIB_V2, null);
        return new Path[] { libV2, client };
    }

    /**
     * Scan with one thread, so that the classfiles are linked in classpath order.
     *
     * @param classpath
     *            the classpath, in order.
     * @param rejectedClasses
     *            the classes not to scan.
     * @return the scan result.
     */
    private static ScanResult scan(final List<Path> classpath, final String... rejectedClasses) {
        return new ClassGraph().enableClasspathEntries(classpath).enableClassInfo().enableMethodInfo()
                .enableFieldInfo().enableAnnotationInfo().acceptPackages("lib", "client")
                .rejectClasses(rejectedClasses).scan(1);
    }

    /**
     * The kind and modifiers of each scanned library class are those of version 2, whether the library or the
     * client is linked first.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void aScannedClassTakesItsKindAndModifiersFromItsOwnClassfile(@TempDir final Path tempDir)
            throws IOException {
        final var dirs = compileLibV2AndClient(tempDir);
        for (final var classpath : List.of(List.of(dirs[0], dirs[1]), List.of(dirs[1], dirs[0]))) {
            try (var scanResult = scan(classpath)) {
                final var order = "classpath " + classpath;
                assertThat(scanResult.getClassInfo("lib.Outer$Inner").getModifiers()).as(order)
                        .isEqualTo(Modifier.PUBLIC | Modifier.STATIC);
                assertThat(scanResult.getClassInfo("lib.Outer$Prot").getModifiers()).as(order)
                        .isEqualTo(Modifier.PUBLIC | Modifier.STATIC);
                assertThat(scanResult.getClassInfo("lib.Outer$Abs").getModifiers()).as(order)
                        .isEqualTo(Modifier.PUBLIC | Modifier.STATIC);

                final var foo = scanResult.getClassInfo("lib.Foo");
                assertThat(foo.isInterface()).as(order).isFalse();
                assertThat(foo.getModifiers()).as(order).isEqualTo(Modifier.PUBLIC | Modifier.ABSTRACT);
                assertThat(foo.getMethodInfo("m").getSingleMethod("m").isDefault()).as(order).isFalse();
                assertThat(scanResult.getAllInterfaces().getNames()).as(order).doesNotContain("lib.Foo");
                // The reference itself is real: the client still names lib.Foo as an interface it implements
                assertThat(scanResult.getClassInfo("client.C").getDirectSuperinterfaces().getNames()).as(order)
                        .containsExactly("lib.Foo");

                for (final var ann : List.of("lib.AnnT", "lib.AnnF", "lib.AnnM", "lib.AnnP")) {
                    final var annClassInfo = scanResult.getClassInfo(ann);
                    assertThat(annClassInfo.isAnnotation()).as(ann + ", " + order).isFalse();
                    assertThat(annClassInfo.getModifiers()).as(ann + ", " + order).isEqualTo(Modifier.PUBLIC);
                }
            }
        }
    }

    /**
     * A nested class that is not scanned gets its modifiers from the {@code InnerClasses} entry in its enclosing
     * class's classfile, whether the enclosing class or the client is linked first.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void anUnscannedNestedClassTakesItsModifiersFromItsEnclosingClass(@TempDir final Path tempDir)
            throws IOException {
        final var dirs = compileLibV2AndClient(tempDir);
        for (final var classpath : List.of(List.of(dirs[0], dirs[1]), List.of(dirs[1], dirs[0]))) {
            try (var scanResult = scan(classpath, "lib.Outer$Inner", "lib.Outer$Prot", "lib.Outer$Abs")) {
                final var order = "classpath " + classpath;
                for (final var nested : List.of("lib.Outer$Inner", "lib.Outer$Prot", "lib.Outer$Abs")) {
                    final var classInfo = scanResult.getClassInfo(nested);
                    assertThat(classInfo).as(nested + ", " + order).isNotNull();
                    assertThat(classInfo.getModifiers()).as(nested + ", " + order)
                            .isEqualTo(Modifier.PUBLIC | Modifier.STATIC);
                }
            }
        }
    }

    /**
     * When neither a nested class nor its enclosing class is scanned, the only {@code InnerClasses} entries for the
     * nested class are those of client classfiles, which can disagree about its access level. Here one client is
     * compiled against version 1 of the library, in which {@code Prot} is {@code protected}, and another against
     * version 2, in which it is {@code public}. The widest access level is kept, whichever client is linked first.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if a source file could not be written.
     */
    @Test
    public void unscannedNestedClassModifiersDoNotDependOnLinkOrder(@TempDir final Path tempDir)
            throws IOException {
        final var libV1 = compile(tempDir.resolve("v1"), "lib", LIB_V1, null);
        final var libV2 = compile(tempDir.resolve("v2"), "lib", LIB_V2, null);
        final var client = Map.of("D", "public class D extends lib.Outer { Prot p; }");
        final var clientV1 = compile(tempDir.resolve("client1"), "client1", client, libV1);
        final var clientV2 = compile(tempDir.resolve("client2"), "client2", client, libV2);
        for (final var classpath : List.of(List.of(clientV1, clientV2), List.of(clientV2, clientV1))) {
            try (var scanResult = new ClassGraph().enableClasspathEntries(classpath).enableClassInfo()
                    .acceptPackages("lib", "client1", "client2").scan(1)) {
                final var order = "classpath " + classpath;
                final var prot = scanResult.getClassInfo("lib.Outer$Prot");
                assertThat(prot).as(order).isNotNull();
                assertThat(prot.getModifiers()).as(order).isEqualTo(Modifier.PUBLIC | Modifier.STATIC);
            }
        }
    }
}
