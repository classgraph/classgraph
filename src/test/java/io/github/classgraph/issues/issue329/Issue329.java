package io.github.classgraph.issues.issue329;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Unit test.
 */
public class Issue329 {
    /** The Class Foo. */
    public class Foo {
        /** Constructor. */
        public Foo() {
            new Bar();
        }
    }

    /** The Class Bar. */
    public class Bar {
    }

    /** Test. */
    @Test
    public void test() {
        try (ScanResult scanResult = new ClassGraph().enableAllInfo().enableInterClassDependencies()
                .enableExternalClasses().whitelistClasses(Foo.class.getName()).scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(Foo.class.getName());
            assertThat(classInfo.getClassDependencies().getNames()).containsOnly(Issue329.class.getName(),
                    Bar.class.getName());
        }
    }
}
