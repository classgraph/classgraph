package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;

/**
 * The predicates that tell the kinds of nested class apart agree with the ones {@link Class} has.
 */
public class NestedClassKindsTest {
    /** A class that declares one nested class of every kind. */
    static class Outer {
        /** A static member class. */
        static class StaticMember {
        }

        /** A non-static member class. */
        class InnerMember {
        }

        /** A member interface, which is implicitly static. */
        interface MemberInterface {
        }

        /** A member record, which is implicitly static. */
        record MemberRecord() {
        }

        /**
         * Declare local and anonymous classes in an instance method.
         *
         * @return the classes.
         */
        Class<?>[] instanceContext() {
            class LocalInInstance {
            }
            // A local record is implicitly static
            record LocalRecord() {
            }
            final Object anonymous = new Object() {
            };
            return new Class<?>[] { LocalInInstance.class, LocalRecord.class, anonymous.getClass() };
        }

        /**
         * Declare local and anonymous classes in a static method.
         *
         * @return the classes.
         */
        static Class<?>[] staticContext() {
            class LocalInStatic {
            }
            final Object anonymous = new Object() {
            };
            return new Class<?>[] { LocalInStatic.class, anonymous.getClass() };
        }
    }

    /**
     * Each predicate returns what the {@link Class} method of the same meaning returns.
     */
    @Test
    public void predicatesMatchClass() {
        final List<Class<?>> classes = new ArrayList<>(
                Arrays.asList(NestedClassKindsTest.class, Outer.class, Outer.StaticMember.class,
                        Outer.InnerMember.class, Outer.MemberInterface.class, Outer.MemberRecord.class));
        classes.addAll(Arrays.asList(new Outer().instanceContext()));
        classes.addAll(Arrays.asList(Outer.staticContext()));
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo()
                .acceptClasses(classes.stream().map(Class::getName).toArray(String[]::new)).ignoreClassVisibility()
                .scan()) {
            for (final Class<?> cls : classes) {
                final ClassInfo classInfo = scanResult.getClassInfo(cls.getName());
                assertThat(classInfo).as(cls.getName()).isNotNull();
                assertThat(classInfo.isNestedClass()).as(cls.getName() + " nested")
                        .isEqualTo(cls.getEnclosingClass() != null);
                assertThat(classInfo.isMemberClass()).as(cls.getName() + " member").isEqualTo(cls.isMemberClass());
                assertThat(classInfo.isLocalClass()).as(cls.getName() + " local").isEqualTo(cls.isLocalClass());
                assertThat(classInfo.isAnonymousClass()).as(cls.getName() + " anonymous")
                        .isEqualTo(cls.isAnonymousClass());
                assertThat(classInfo.isStatic()).as(cls.getName() + " static")
                        .isEqualTo(Modifier.isStatic(cls.getModifiers()));
                final var enclosingClasses = classInfo.getEnclosingClasses().directOnly().getNames();
                if (cls.getEnclosingClass() == null) {
                    assertThat(enclosingClasses).as(cls.getName() + " enclosing").isEmpty();
                } else {
                    assertThat(enclosingClasses).as(cls.getName() + " enclosing")
                            .containsExactly(cls.getEnclosingClass().getName());
                }
            }
            // The nested classes of a class include its local and anonymous classes, which
            // Class#getDeclaredClasses() leaves out
            final var outer = scanResult.getClassInfo(Outer.class.getName());
            assertThat(outer.hasNestedClasses()).isTrue();
            assertThat(outer.getNestedClasses().getNames()).containsExactlyInAnyOrderElementsOf(classes.stream()
                    .filter(cls -> cls.getEnclosingClass() == Outer.class).map(Class::getName).toList());
            assertThat(scanResult.getClassInfo(Outer.StaticMember.class.getName()).hasNestedClasses()).isFalse();
            // Enclosing classes are listed from innermost to outermost
            assertThat(scanResult.getClassInfo(Outer.StaticMember.class.getName()).getEnclosingClasses().getNames())
                    .containsExactly(Outer.class.getName(), NestedClassKindsTest.class.getName());
        }
    }
}
