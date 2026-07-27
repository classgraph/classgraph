package io.github.classgraph.issues.issue791;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Issue 791: the access level of a nested class was OR'd together from two sources, rather than being taken from the
 * authoritative one. A nested class's own {@code access_flags} cannot express its source-level access level (the JVM
 * requires the class to be reachable from its enclosing class, so javac emits {@code ACC_PUBLIC} there); the real
 * access level lives in the {@code InnerClasses} attribute of the enclosing class. OR-ing the two left a
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

    /** Exactly one access level bit should be set on a nested class, and it should be the right one. */
    @Test
    public void nestedClassAccessLevelIsNotContaminatedByClassfileAccessFlags() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(Issue791Test.class.getPackage().getName())
                .ignoreClassVisibility().enableAllInfo().scan()) {

            final ClassInfo publicSubclass = scanResult.getClassInfo(DummyPublicSubclass.class.getName());
            assertThat(publicSubclass.getModifiers() & ACCESS_LEVEL_MODIFIERS).isEqualTo(Modifier.PUBLIC);
            assertThat(publicSubclass.isPublic()).isTrue();
            assertThat(publicSubclass.isProtected()).isFalse();
            assertThat(publicSubclass.isPrivate()).isFalse();
            assertThat(publicSubclass.isStatic()).isTrue();

            final ClassInfo protectedSubclass = scanResult.getClassInfo(DummyProtectedSubclass.class.getName());
            assertThat(protectedSubclass.getModifiers() & ACCESS_LEVEL_MODIFIERS).isEqualTo(Modifier.PROTECTED);
            assertThat(protectedSubclass.isPublic()).isFalse();
            assertThat(protectedSubclass.isProtected()).isTrue();
            assertThat(protectedSubclass.isPrivate()).isFalse();
            assertThat(protectedSubclass.isStatic()).isTrue();

            final ClassInfo privateSubclass = scanResult.getClassInfo(DummyPrivateSubclass.class.getName());
            assertThat(privateSubclass.getModifiers() & ACCESS_LEVEL_MODIFIERS).isEqualTo(Modifier.PRIVATE);
            assertThat(privateSubclass.isPublic()).isFalse();
            assertThat(privateSubclass.isProtected()).isFalse();
            assertThat(privateSubclass.isPrivate()).isTrue();
            assertThat(privateSubclass.isStatic()).isTrue();

            final ClassInfo packagePrivateSubclass = scanResult
                    .getClassInfo(DummyPackagePrivateSubclass.class.getName());
            assertThat(packagePrivateSubclass.getModifiers() & ACCESS_LEVEL_MODIFIERS).isZero();
            assertThat(packagePrivateSubclass.isPublic()).isFalse();
            assertThat(packagePrivateSubclass.isProtected()).isFalse();
            assertThat(packagePrivateSubclass.isPrivate()).isFalse();
            assertThat(packagePrivateSubclass.isStatic()).isTrue();
        }
    }

    /** A top-level class's access level should be unaffected. */
    @Test
    public void topLevelClassAccessLevelIsUnchanged() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(Issue791Test.class.getPackage().getName())
                .ignoreClassVisibility().enableAllInfo().scan()) {
            final ClassInfo topLevel = scanResult.getClassInfo(Issue791Test.class.getName());
            assertThat(topLevel.getModifiers() & ACCESS_LEVEL_MODIFIERS).isEqualTo(Modifier.PUBLIC);
            assertThat(topLevel.isPublic()).isTrue();
            assertThat(topLevel.isStatic()).isFalse();
        }
    }
}
