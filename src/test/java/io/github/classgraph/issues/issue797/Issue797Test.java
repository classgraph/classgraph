package io.github.classgraph.issues.issue797;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

public class Issue797Test {
    /**
     * Issue 797.
     */
    @Test
    public void getResourcesWithPathShouldNeverReturnNull() {
        // Jar is precompiled, since it uses a JDK 17 feature (records)
        final URL url = Issue797Test.class.getResource("/issue797.jar");
        try (ScanResult result = new ClassGraph().overrideClasspath(url).enableAllInfo().scan()) {
            final ClassInfo bar = result.getClassInfo("io.github.classgraph.issues.issue797.Bar");
            assertThat(bar.toString()).isEqualTo(
                    "public final record io.github.classgraph.issues.issue797.Bar(" + "java.lang.String baz, "
                            + "java.util.List<@jakarta.validation.constraints.NotNull java.lang.String> value) "
                            + "extends java.lang.Record");
            final MethodInfo baz = bar.getMethodInfo("baz").get(0);
            assertThat(baz.toString()).isEqualTo("public java.lang.String baz()");
            final MethodInfo value = bar.getMethodInfo("value").get(0);
            assertThat(value.toString()).isEqualTo(
                    "public java.util.List<@jakarta.validation.constraints.NotNull java.lang.String> " + "value()");
        }
    }
}
