package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassMemberInfo#getClassDependencies()} leaves out the same classes as
 * {@link ClassInfo#getClassDependencies()}.
 */
class ClassMemberDependencyTest {
    /** A class whose members refer to accepted classes, external classes and {@link Object}. */
    public static class Target {
        /** A field that names the declaring class, an external class and {@link Object}. */
        public java.util.Map<Target, CRC32> field;

        /**
         * A method that names {@link Object} and an external class.
         *
         * @param object
         *            an object
         * @return a checksum
         */
        public CRC32 method(final Object object) {
            return null;
        }
    }

    /** External classes are left out unless external classes are enabled. */
    @Test
    void externalClassesAreLeftOutUnlessEnabled() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo()
                .enableMethodInfo().acceptClasses(Target.class.getName()).scan()) {
            final var classInfo = scanResult.getClassInfo(Target.class.getName());
            assertThat(classInfo.getFieldInfo("field").getClassDependencies().getNames())
                    .containsExactly(Target.class.getName());
            assertThat(classInfo.getMethodInfo().getSingleMethod("method").getClassDependencies().getNames())
                    .isEmpty();
        }
    }

    /** With external classes enabled, external classes are returned, but {@link Object} still is not. */
    @Test
    void objectIsLeftOut() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableExternalClasses().acceptClasses(Target.class.getName()).scan()) {
            final var classInfo = scanResult.getClassInfo(Target.class.getName());
            assertThat(classInfo.getFieldInfo("field").getClassDependencies().getNames()).containsExactlyInAnyOrder(
                    CRC32.class.getName(), Target.class.getName(), java.util.Map.class.getName());
            assertThat(classInfo.getMethodInfo().getSingleMethod("method").getClassDependencies().getNames())
                    .containsExactly(CRC32.class.getName());
        }
    }
}
