package io.github.classgraph.issues.issue797;

import static org.assertj.core.api.Assertions.assertThat;

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
        try (ScanResult result = new ClassGraph().enableAllInfo().acceptClasses(Bar.class.getName()).scan()) {
            final ClassInfo bar = result.getClassInfo(Bar.class.getName());
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
