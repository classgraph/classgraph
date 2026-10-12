package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Forward and reverse queries share the same effective-member inheritance rules. */
public class EffectiveMemberAnnotationTest {
    /** The fixture package. */
    private static final String PKG = "effective";

    /**
     * Write a fixture source and append it to the compiler arguments.
     *
     * @param root
     *            the source root.
     * @param name
     *            the class name.
     * @param body
     *            the declaration, excluding its package.
     * @param args
     *            the compiler arguments.
     * @throws IOException
     *             if the source cannot be written.
     */
    private static void source(final Path root, final String name, final String body, final List<String> args)
            throws IOException {
        final var file = root.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
        args.add(file.toString());
    }

    /**
     * Compile members with independent annotations so that a surviving member cannot mask a false positive.
     *
     * @param dir
     *            the fixture directory.
     * @return the compiled classpath root.
     * @throws IOException
     *             if the fixture cannot be written.
     */
    private static Path fixture(final Path dir) throws IOException {
        final var output = Files.createDirectory(dir.resolve("classes"));
        final List<String> args = new ArrayList<>(List.of("-d", output.toString()));
        for (final var annotation : List.of("MethodMark", "FieldMark", "ParamMark", "PackageMark", "PrivateMark",
                "ProtectedMark", "ConstructorMark")) {
            source(dir, PKG + "." + annotation,
                    "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                            + "public @interface " + annotation + " {}",
                    args);
        }
        source(dir, PKG + ".p.Base", "import effective.*; public class Base {"
                + " @MethodMark public void ping(@ParamMark String x) {}" + " @FieldMark public int value;"
                + " @PackageMark void packageMethod(@PackageMark String x) {}" + " @PackageMark int packageField;"
                + " @PrivateMark private void privateMethod(@PrivateMark String x) {}"
                + " @PrivateMark private int privateField;" + " @ProtectedMark protected void protectedMethod() {}"
                + " @ProtectedMark protected int protectedField;" + " @ConstructorMark public Base() {} }", args);
        source(dir, PKG + ".p.Inherited", "public class Inherited extends Base {}", args);
        source(dir, PKG + ".p.Overridden", "public class Overridden extends Base {"
                + " @Override public void ping(String x) {} public int value; }", args);
        source(dir, PKG + ".q.Foreign", "public class Foreign extends effective.p.Base {}", args);
        source(dir, PKG + ".p.Returned", "public class Returned extends effective.q.Foreign {}", args);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)))
                .isZero();
        return output;
    }

    /**
     * The receiver's package, member hiding and method overriding all affect the reverse query.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation fails.
     */
    @Test
    public void reverseQueriesValidateEffectiveMembers(@TempDir final Path dir) throws IOException {
        final var output = fixture(dir);
        try (var result = new ClassGraph().enableClasspathEntries(output).acceptPackages(PKG).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            for (final var annotation : List.of("MethodMark", "ParamMark")) {
                final var matches = annotation.equals("ParamMark")
                        ? result.getClassesWithMethodParameterAnnotation(PKG + "." + annotation)
                        : result.getClassesWithMethodAnnotation(PKG + "." + annotation);
                assertThat(matches.getNames()).contains(PKG + ".p.Base", PKG + ".p.Inherited")
                        .doesNotContain(PKG + ".p.Overridden");
                assertThat(matches.directOnly().getNames()).containsExactly(PKG + ".p.Base");
            }
            assertThat(result.getClassesWithFieldAnnotation(PKG + ".FieldMark").getNames())
                    .doesNotContain(PKG + ".p.Overridden");
            for (final var type : List.of("p.Base", "p.Inherited", "p.Overridden", "q.Foreign", "p.Returned")) {
                final var info = result.getClassInfo(PKG + "." + type);
                for (final var annotation : List.of("MethodMark", "FieldMark", "ParamMark", "PackageMark",
                        "PrivateMark", "ProtectedMark", "ConstructorMark")) {
                    final var name = PKG + "." + annotation;
                    assertThat(result.getClassesWithFieldAnnotation(name).contains(info))
                            .as(type + " fields / " + annotation)
                            .isEqualTo(info.getFieldInfo().stream().anyMatch(fi -> fi.hasAnnotation(name)));
                    assertThat(result.getClassesWithMethodAnnotation(name).contains(info))
                            .as(type + " methods / " + annotation).isEqualTo(info.getMethodAndConstructorInfo()
                                    .stream().anyMatch(mi -> mi.hasAnnotation(name)));
                    assertThat(result.getClassesWithMethodParameterAnnotation(name).contains(info))
                            .as(type + " parameters / " + annotation).isEqualTo(info.getMethodAndConstructorInfo()
                                    .stream().anyMatch(mi -> mi.hasParameterAnnotation(name)));
                }
            }
            assertThat(result.getClassesWithMethodAnnotation(PKG + ".PackageMark").getNames())
                    .containsExactly(PKG + ".p.Base", PKG + ".p.Inherited", PKG + ".p.Overridden");
            final var foreign = result.getClassInfo(PKG + ".q.Foreign");
            assertThat(foreign.hasMethod("packageMethod")).isFalse();
            assertThat(foreign.hasField("packageField")).isFalse();
            assertThat(foreign.getFieldInfo("privateField")).isNull();
            assertThat(foreign.getMethodInfo("protectedMethod")).hasSize(1);
            assertThat(result.getClassesWithMethodAnnotation(PKG + ".ConstructorMark").getNames())
                    .containsExactly(PKG + ".p.Base");
        }
    }

    /**
     * Rejected or external declaring classes can still contribute genuinely inherited annotations.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation fails.
     */
    @Test
    public void externalAndRejectedDeclaringClassesRemainCandidates(@TempDir final Path dir) throws IOException {
        final var output = fixture(dir);
        for (final var rejectBase : new boolean[] { false, true }) {
            final var scanner = new ClassGraph().enableClasspathEntries(output).acceptClasses(PKG + ".p.Inherited")
                    .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo();
            if (rejectBase) {
                scanner.rejectClasses(PKG + ".p.Base");
            }
            try (var result = scanner.scan()) {
                assertThat(result.getClassesWithMethodAnnotation(PKG + ".MethodMark").getNames())
                        .containsExactly(PKG + ".p.Inherited");
                assertThat(result.getClassesWithFieldAnnotation(PKG + ".FieldMark").getNames())
                        .containsExactly(PKG + ".p.Inherited");
            }
        }
    }
}
