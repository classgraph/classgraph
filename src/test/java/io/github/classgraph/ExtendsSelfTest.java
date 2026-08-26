package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo#extendsSuperclass(String)} answered true for every standard class asked about
 * {@code java.lang.Object}, including {@link Object} itself, so {@code Object} appeared to extend itself, and
 * {@link ClassInfo#getSubclasses()} listed {@code Object} among the subclasses of {@code Object}.
 */
public class ExtendsSelfTest {
    /**
     * A field of type {@link Object}, so that {@link Object} is given a {@link ClassInfo} object when inter-class
     * dependencies are enabled. ({@link Object} itself is never scanned, since it has no superclass.)
     */
    public Object objectField;

    /** A class does not extend itself, and in particular {@link Object} does not extend {@link Object}. */
    @Test
    public void classDoesNotExtendItself() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackagesNonRecursive(ExtendsSelfTest.class.getPackage().getName()).enableAllInfo()
                .enableInterClassDependencies().enableExternalClasses().scan()) {
            final ClassInfo self = scanResult.getClassInfo(ExtendsSelfTest.class.getName());
            assertThat(self).isNotNull();
            assertThat(self.extendsSuperclass(Object.class)).isTrue();
            assertThat(self.extendsSuperclass(ExtendsSelfTest.class)).isFalse();

            final ClassInfo object = scanResult.getClassInfo(Object.class.getName());
            assertThat(object).isNotNull();
            assertThat(object.extendsSuperclass(Object.class)).isFalse();
            assertThat(object.getSubclasses()).contains(self).doesNotContain(object);
        }
    }
}
