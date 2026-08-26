package io.github.classgraph.issues.issue797;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue797Test {
    @Test
    public void getResourcesWithPathShouldNeverReturnNull() {
        // Jar is precompiled, since it uses a JDK 17 feature (records)
        final var url = Issue797Test.class.getResource("/issue797.jar");
        try (var result = new ClassGraph().enableClasspathEntries(url).enableAllInfo().scan()) {
            final var bar = result.getClassInfo("io.github.classgraph.issues.issue797.Bar");
            assertThat(bar.toString()).isEqualTo(
                    "public final record io.github.classgraph.issues.issue797.Bar(" + "java.lang.String baz, "
                            + "java.util.List<@jakarta.validation.constraints.NotNull java.lang.String> value)");
            final var baz = bar.getMethodInfo("baz").get(0);
            assertThat(baz.toString()).isEqualTo("public java.lang.String baz()");
            final var value = bar.getMethodInfo("value").get(0);
            assertThat(value.toString()).isEqualTo(
                    "public java.util.List<@jakarta.validation.constraints.NotNull java.lang.String> " + "value()");
        }
    }
}
