package io.github.classgraph.issues.issue329;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Unit test.
 */
public class Issue329Test {
    /** The Class Foo. */
    public class Foo {
        /** Constructor. */
        // The allocation is the fixture: it is what makes Foo reference Bar in its constant pool
        @SuppressWarnings("unused")
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
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility()
                .enableInterClassDependencies().enableExternalClasses().acceptClasses(Foo.class.getName()).scan()) {
            final var classInfo = scanResult.getClassInfo(Foo.class.getName());
            assertThat(classInfo.getClassDependencies().getNames()).containsOnly(Issue329Test.class.getName(),
                    Bar.class.getName());
        }
    }
}
