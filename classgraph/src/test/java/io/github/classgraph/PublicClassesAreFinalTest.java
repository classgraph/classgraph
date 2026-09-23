package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ClassGraph's public classes are not designed to be extended, so any that user code can construct is final.
 */
public class PublicClassesAreFinalTest {
    /** Every public class outside an internal package that has a public constructor is final. */
    @Test
    public void publicClassesWithPublicConstructorsAreFinal() {
        final List<String> notFinal = new ArrayList<>();
        try (var scanResult = new ClassGraph().enableClasspath().acceptPackages("io.github.classgraph")
                .enableClassInfo().enableMethodInfo().scan()) {
            for (final ClassInfo classInfo : scanResult.getAllStandardClasses()) {
                final var file = classInfo.getClasspathElementFile();
                if (file == null || file.getPath().contains("test-classes")
                        || classInfo.getPackageName().contains(".internal") || !classInfo.isPublic()
                        || classInfo.isAbstract() || classInfo.isFinal()
                        || classInfo.getConstructorInfo().isEmpty()) {
                    continue;
                }
                notFinal.add(classInfo.getName());
            }
        }
        assertThat(notFinal).isEmpty();
    }
}
