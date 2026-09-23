package io.github.classgraph.localclasses;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Tests that anonymous classes are told apart from local classes, and that the method or constructor they are
 * declared in is reported, the same way as {@link Class#isAnonymousClass()}, {@link Class#getEnclosingMethod()}
 * and {@link Class#getEnclosingConstructor()} report them.
 */
public class LocalAndAnonymousClassTest {
    /** A class that declares local and anonymous classes in every kind of place they can be declared. */
    @SuppressWarnings("unused")
    public static class Declarer {
        /** An anonymous class in an instance field initializer. */
        final Runnable anonymousInInstanceField = new Runnable() {
            @Override
            public void run() {
            }
        };

        /** An anonymous class in a static field initializer. */
        static final Runnable ANONYMOUS_IN_STATIC_FIELD = new Runnable() {
            @Override
            public void run() {
            }
        };

        {
            class LocalInInstanceInitializer {
            }
            new LocalInInstanceInitializer();
        }

        static {
            class LocalInStaticInitializer {
            }
            new LocalInStaticInitializer();
        }

        /** Constructor, which declares an anonymous class. */
        public Declarer() {
            new Object() {
            };
        }

        /** A method that declares a local class and an anonymous class. */
        void method() {
            class LocalInMethod {
            }
            new LocalInMethod();
            new Object() {
            };
        }

        /** A member class, which is neither local nor anonymous. */
        static class Member {
        }
    }

    /**
     * The name of the method or constructor a class is declared in, as the JDK reports it.
     *
     * @param cls
     *            the class
     * @return the fully-qualified method name, or null if the class is not declared in a method or constructor
     */
    private static String jdkDefiningMethodName(final Class<?> cls) {
        if (cls.getEnclosingMethod() != null) {
            return cls.getEnclosingMethod().getDeclaringClass().getName() + "."
                    + cls.getEnclosingMethod().getName();
        } else if (cls.getEnclosingConstructor() != null) {
            return cls.getEnclosingConstructor().getDeclaringClass().getName() + ".<init>";
        }
        return null;
    }

    /**
     * Every class in this package is described the same way by ClassGraph and by the JDK.
     *
     * @throws ClassNotFoundException
     *             if a class cannot be loaded
     */
    @Test
    public void matchesTheJdk() throws ClassNotFoundException {
        final List<String> classGraphDescriptions = new ArrayList<>();
        final List<String> jdkDescriptions = new ArrayList<>();
        try (ScanResult scanResult = new ClassGraph().enableClassInfo()
                .acceptPackages(LocalAndAnonymousClassTest.class.getPackage().getName()).ignoreClassVisibility()
                .scan()) {
            for (final ClassInfo classInfo : scanResult.getAllClasses()) {
                classGraphDescriptions.add(classInfo.getName() + " anonymous=" + classInfo.isAnonymousInnerClass()
                        + " in=" + classInfo.getFullyQualifiedDefiningMethodName());
                final Class<?> cls = Class.forName(classInfo.getName());
                jdkDescriptions.add(cls.getName() + " anonymous=" + cls.isAnonymousClass() + " in="
                        + jdkDefiningMethodName(cls));
            }
        }
        // Declarer, its 8 local, anonymous and member classes, and this test class
        assertThat(classGraphDescriptions).hasSize(10);
        assertThat(classGraphDescriptions).containsExactlyElementsOf(jdkDescriptions);
    }
}
