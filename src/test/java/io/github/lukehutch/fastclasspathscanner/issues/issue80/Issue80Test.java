package io.github.lukehutch.fastclasspathscanner.issues.issue80;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

public class Issue80Test {
    @Test
    public void issue80() {
        // TODO: this test will fail in JDK9+, because there is no rt.jar.
        // TODO: Need to scan system modules when "!!" is in the scan spec.
        if (!ReflectionUtils.JAVA_VERSION_IS_9_PLUS) {
            assertThat(new FastClasspathScanner("!!", "java.util").scan().getNamesOfAllStandardClasses())
                    .contains("java.util.ArrayList");
        }
        assertThat(new FastClasspathScanner("java.util").scan().getNamesOfAllStandardClasses()).isEmpty();
    }
}
