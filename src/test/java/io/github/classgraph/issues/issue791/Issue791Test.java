package io.github.classgraph.issues.issue791;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;

/**
 * Issue 791: the access level of a nested class was OR'd together from two
 * sources, rather than being taken from the authoritative one. A nested class's
 * own {@code access_flags} cannot express its source-level access level (the
 * JVM requires the class to be reachable from its enclosing class, so javac
 * emits {@code ACC_PUBLIC} there); the real access level lives in the
 * {@code InnerClasses} attribute of the enclosing class. OR-ing the two left a
 * {@code protected} nested class marked both public and protected.
 */
public class Issue791Test {
    /** A public nested class. */
    public static class DummyPublicSubclass {
    }

    /** A protected nested class. */
    protected static class DummyProtectedSubclass {
    }

    /** A private nested class. */
    private static class DummyPrivateSubclass {
    }

    /** A package-private nested class. */
    static class DummyPackagePrivateSubclass {
    }

    /** The access level bits: public, private and protected. */
    private static final int ACCESS_LEVEL_MODIFIERS = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED;

    /**
     * Exactly one access level bit should be set on a nested class, and it should
     * be the right one.
     */
    @Test
    public void nestedClassAccessLevelIsNotContaminatedByClassfileAccessFlags() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue791Test.class.getPackage().getName())
                .ignoreClassVisibility().enableAllInfo().scan()) {

            final var publicSubclass = scanResult.getClassInfo(DummyPublicSubclass.class.getName());
            assertThat(publicSubclass.getModifiers() & ACCESS_LEVEL_MODIFIERS).isEqualTo(Modifier.PUBLIC);
            assertThat(publicSubclass.isPublic()).isTrue();
            assertThat(publicSubclass.isProtected()).isFalse();
            assertThat(publicSubclass.isPrivate()).isFalse();
            assertThat(publicSubclass.isStatic()).isTrue();

            final var protectedSubclass = scanResult.getClassInfo(DummyProtectedSubclass.class.getName());
            assertThat(protectedSubclass.getModifiers() & ACCESS_LEVEL_MODIFIERS).isEqualTo(Modifier.PROTECTED);
            assertThat(protectedSubclass.isPublic()).isFalse();
            assertThat(protectedSubclass.isProtected()).isTrue();
            assertThat(protectedSubclass.isPrivate()).isFalse();
            assertThat(protectedSubclass.isStatic()).isTrue();

            final var privateSubclass = scanResult.getClassInfo(DummyPrivateSubclass.class.getName());
            assertThat(privateSubclass.getModifiers() & ACCESS_LEVEL_MODIFIERS).isEqualTo(Modifier.PRIVATE);
            assertThat(privateSubclass.isPublic()).isFalse();
            assertThat(privateSubclass.isProtected()).isFalse();
            assertThat(privateSubclass.isPrivate()).isTrue();
            assertThat(privateSubclass.isStatic()).isTrue();

            final var packagePrivateSubclass = scanResult.getClassInfo(DummyPackagePrivateSubclass.class.getName());
            assertThat(packagePrivateSubclass.getModifiers() & ACCESS_LEVEL_MODIFIERS).isZero();
            assertThat(packagePrivateSubclass.isPublic()).isFalse();
            assertThat(packagePrivateSubclass.isProtected()).isFalse();
            assertThat(packagePrivateSubclass.isPrivate()).isFalse();
            assertThat(packagePrivateSubclass.isStatic()).isTrue();
        }
    }

    /** The ACC_SUPER bit of the classfile access_flags field. */
    private static final int ACC_SUPER = 0x0020;

    /**
     * Part 1 of the issue: {@code ACC_SUPER} is not a modifier -- it selects the
     * JVM's treatment of {@code invokespecial}, and the same bit in
     * {@link Modifier} is {@link Modifier#SYNCHRONIZED}, which is not a legal class
     * modifier. javac sets it on almost every class, and
     * {@link Class#getModifiers()} masks it out, so
     * {@link ClassInfo#getModifiers()} must mask it out too. These are the exact
     * values requested in the issue.
     */
    @Test
    public void accSuperIsMaskedOutOfGetModifiers() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue791Test.class.getPackage().getName())
                .ignoreClassVisibility().enableAllInfo().scan()) {

            final Class<?>[] classes = { Issue791Test.class, DummyPublicSubclass.class, DummyProtectedSubclass.class,
                    DummyPrivateSubclass.class, DummyPackagePrivateSubclass.class };
            for (final Class<?> cls : classes) {
                final var classInfo = scanResult.getClassInfo(cls.getName());
                assertThat(classInfo.getModifiers() & ACC_SUPER).as("ACC_SUPER set for %s", cls.getName()).isZero();
                assertThat(classInfo.getModifiers()).as("getModifiers() for %s", cls.getName())
                        .isEqualTo(cls.getModifiers());
            }

            // The literal values asked for in the issue
            assertThat(scanResult.getClassInfo(DummyPublicSubclass.class.getName()).getModifiers()).isEqualTo(0x0009);
            assertThat(scanResult.getClassInfo(DummyProtectedSubclass.class.getName()).getModifiers())
                    .isEqualTo(0x000C);
            assertThat(scanResult.getClassInfo(DummyPrivateSubclass.class.getName()).getModifiers()).isEqualTo(0x000A);
        }
    }

    /**
     * Masking {@code ACC_SUPER} out of {@link ClassInfo#getModifiers()} must not
     * disturb the string rendering, which already ignored the bit.
     */
    @Test
    public void modifiersStrIsUnaffected() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue791Test.class.getPackage().getName())
                .ignoreClassVisibility().enableAllInfo().scan()) {
            assertThat(scanResult.getClassInfo(DummyProtectedSubclass.class.getName()).getModifiersStr())
                    .isEqualTo("protected static");
            assertThat(scanResult.getClassInfo(Issue791Test.class.getName()).getModifiersStr()).isEqualTo("public");
        }
    }

    /** A top-level class's access level should be unaffected. */
    @Test
    public void topLevelClassAccessLevelIsUnchanged() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue791Test.class.getPackage().getName())
                .ignoreClassVisibility().enableAllInfo().scan()) {
            final var topLevel = scanResult.getClassInfo(Issue791Test.class.getName());
            assertThat(topLevel.getModifiers() & ACCESS_LEVEL_MODIFIERS).isEqualTo(Modifier.PUBLIC);
            assertThat(topLevel.isPublic()).isTrue();
            assertThat(topLevel.isStatic()).isFalse();
        }
    }
}
