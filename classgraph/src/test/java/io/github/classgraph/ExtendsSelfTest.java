package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo#extendsSuperclass(String)} answered true for every standard class asked about
 * {@code java.lang.Object}, including {@link Object} itself, so {@code Object} appeared to extend itself.
 * {@link ClassInfo#getAllSubclasses()} already excludes the class itself in the same situation.
 */
public class ExtendsSelfTest {
    /** A class does not extend itself, and in particular {@link Object} does not extend {@link Object}. */
    @Test
    public void classDoesNotExtendItself() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(ExtendsSelfTest.class.getPackage().getName()).enableAllInfo()
                .enableExternalClasses().scan()) {
            final var self = scanResult.getClassInfo(ExtendsSelfTest.class.getName());
            assertThat(self).isNotNull();
            assertThat(self.extendsSuperclass(Object.class)).isTrue();
            assertThat(self.extendsSuperclass(ExtendsSelfTest.class)).isFalse();

            // Object is reachable as an external class, since it is the superclass of the accepted classes
            final var object = scanResult.getClassInfo(Object.class.getName());
            assertThat(object).isNotNull();
            assertThat(object.extendsSuperclass(Object.class)).isFalse();
            assertThat(object.getAllSubclasses()).doesNotContain(object);
        }
    }
}
