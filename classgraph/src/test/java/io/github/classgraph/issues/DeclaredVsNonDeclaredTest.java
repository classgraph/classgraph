package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;

/**
 * Test.
 */
public class DeclaredVsNonDeclaredTest {
    /**
     * SuperClass.
     */
    public static class SuperClass {
        /** Public superclass field. */
        public int publicSuperClassField;

        /** Private superclass field. */
        @SuppressWarnings("unused")
        private int privateSuperClassField;

        /**
         * Public superclass method.
         */
        public void publicSuperClassMethod() {
        }

        /**
         * Private superclass method.
         */
        @SuppressWarnings("unused")
        private void privateSuperClassMethod() {
        }
    }

    /**
     * SubClass.
     */
    public static class SubClass extends SuperClass {
        /** Public subclass field. */
        public int publicSubClassField;

        /** Private subclass field. */
        @SuppressWarnings("unused")
        private int privateSubClassField;

        /**
         * Public subclass method.
         */
        public void publicSubClassMethod() {
        }

        /**
         * Private subclass method.
         */
        @SuppressWarnings("unused")
        private void privateSubClassMethod() {
        }
    }

    /**
     * Compare results.
     *
     * @param superClassInfo
     *            the superclass info
     * @param subClassInfo
     *            the subclass info
     * @param ignoreVisibility
     *            whether or not to ignore method and field visibility
     */
    private static void compareResults(final ClassInfo superClassInfo, final ClassInfo subClassInfo,
            final boolean ignoreVisibility) {
        final Predicate<String> filterOutClassMethods = name -> !"wait".equals(name) && !"equals".equals(name)
                && !"toString".equals(name) && !"hashCode".equals(name) && !"getClass".equals(name)
                && !"notify".equals(name) && !"notifyAll".equals(name);

        // METHODS

        final Function<ClassInfo, List<String>> getClassGraphMethodNames = classInfo -> classInfo.getMethodInfo()
                .stream().map(MethodInfo::getName).toList();

        final Function<ClassInfo, List<String>> getClassGraphDeclaredMethodNames = classInfo -> classInfo
                .getDeclaredMethodInfo().stream().map(MethodInfo::getName).toList();

        final Function<Class<?>, String[]> getClassMethodNames = clazz -> Arrays.stream(clazz.getMethods())
                .map(Method::getName).filter(filterOutClassMethods).toArray(String[]::new);

        final Function<Class<?>, String[]> getClassDeclaredMethodNames = clazz -> Arrays
                .stream(clazz.getDeclaredMethods()).map(Method::getName).filter(filterOutClassMethods)
                .toArray(String[]::new);

        // Non-"declared" methods, superclass

        assertThat(getClassGraphMethodNames.apply(superClassInfo)).containsExactlyInAnyOrder(
                ignoreVisibility ? new String[] { "publicSuperClassMethod", "privateSuperClassMethod" }
                        : new String[] { "publicSuperClassMethod" });
        assertThat(getClassMethodNames.apply(SuperClass.class)).containsExactlyInAnyOrder("publicSuperClassMethod");

        // Non-"declared" methods, subclass

        assertThat(getClassGraphMethodNames.apply(subClassInfo)).containsExactlyInAnyOrder(ignoreVisibility
                ? new String[] { "publicSuperClassMethod", "publicSubClassMethod", "privateSuperClassMethod",
                        "privateSubClassMethod" }
                : new String[] { "publicSuperClassMethod", "publicSubClassMethod" });
        assertThat(getClassMethodNames.apply(SubClass.class)).containsExactlyInAnyOrder("publicSuperClassMethod",
                "publicSubClassMethod");

        // "Declared" methods, superclass

        assertThat(getClassGraphDeclaredMethodNames.apply(superClassInfo)).containsExactlyInAnyOrder(
                ignoreVisibility ? new String[] { "publicSuperClassMethod", "privateSuperClassMethod" }
                        : new String[] { "publicSuperClassMethod" });
        assertThat(getClassDeclaredMethodNames.apply(SuperClass.class))
                .containsExactlyInAnyOrder("publicSuperClassMethod", "privateSuperClassMethod");

        // "Declared" methods, subclass

        assertThat(getClassGraphDeclaredMethodNames.apply(subClassInfo)).containsExactlyInAnyOrder(
                ignoreVisibility ? new String[] { "publicSubClassMethod", "privateSubClassMethod" }
                        : new String[] { "publicSubClassMethod" });
        assertThat(getClassDeclaredMethodNames.apply(SubClass.class))
                .containsExactlyInAnyOrder("publicSubClassMethod", "privateSubClassMethod");

        // FIELDS

        final Function<ClassInfo, List<String>> getClassGraphFieldNames = classInfo -> classInfo.getFieldInfo()
                .stream().map(FieldInfo::getName).toList();

        final Function<ClassInfo, List<String>> getClassGraphDeclaredFieldNames = classInfo -> classInfo
                .getDeclaredFieldInfo().stream().map(FieldInfo::getName).toList();

        final Function<Class<?>, List<String>> getClassFieldNames = clazz -> Arrays.stream(clazz.getFields())
                .map(Field::getName).filter(filterOutClassMethods).toList();

        final Function<Class<?>, List<String>> getClassDeclaredFieldNames = clazz -> Arrays
                .stream(clazz.getDeclaredFields()).map(Field::getName).filter(filterOutClassMethods).toList();

        // Non-"declared" fields, superclass

        assertThat(getClassGraphFieldNames.apply(superClassInfo)).containsExactlyInAnyOrder(
                ignoreVisibility ? new String[] { "publicSuperClassField", "privateSuperClassField" }
                        : new String[] { "publicSuperClassField" });
        assertThat(getClassFieldNames.apply(SuperClass.class)).containsExactlyInAnyOrder("publicSuperClassField");

        // Non-"declared" fields, subclass

        assertThat(getClassGraphFieldNames.apply(subClassInfo))
                .containsExactlyInAnyOrder(ignoreVisibility
                        ? new String[] { "publicSuperClassField", "publicSubClassField", "privateSuperClassField",
                                "privateSubClassField" }
                        : new String[] { "publicSuperClassField", "publicSubClassField" });
        assertThat(getClassFieldNames.apply(SubClass.class)).containsExactlyInAnyOrder("publicSuperClassField",
                "publicSubClassField");

        // "Declared" fields, superclass

        assertThat(getClassGraphDeclaredFieldNames.apply(superClassInfo)).containsExactlyInAnyOrder(
                ignoreVisibility ? new String[] { "publicSuperClassField", "privateSuperClassField" }
                        : new String[] { "publicSuperClassField" });
        assertThat(getClassDeclaredFieldNames.apply(SuperClass.class))
                .containsExactlyInAnyOrder("publicSuperClassField", "privateSuperClassField");

        // "Declared" fields, subclass

        assertThat(getClassGraphDeclaredFieldNames.apply(subClassInfo)).containsExactlyInAnyOrder(
                ignoreVisibility ? new String[] { "publicSubClassField", "privateSubClassField" }
                        : new String[] { "publicSubClassField" });
        assertThat(getClassDeclaredFieldNames.apply(SubClass.class))
                .containsExactlyInAnyOrder("publicSubClassField", "privateSubClassField");
    }

    /**
     * Test ClassGraph's "declared" vs. non-"declared" method/field retrieval against the Java reflection API,
     * without calling {@link ClassGraph#ignoreMethodVisibility()} or {@link ClassGraph#ignoreFieldVisibility()}.
     */
    @Test
    public void publicDeclaredVsNonDeclared() {
        try (var scanResult = new ClassGraph().enableClassInfo() //
                .enableMethodInfo() //
                .enableFieldInfo() //
                .acceptPackages(DeclaredVsNonDeclaredTest.class.getPackage().getName()).scan()) {
            final var superClassInfo = scanResult.getClassInfo(SuperClass.class.getName());
            final var subClassInfo = scanResult.getClassInfo(SubClass.class.getName());
            compareResults(superClassInfo, subClassInfo, /* ignoreVisibility = */ false);
        }
    }

    /**
     * Test ClassGraph's "declared" vs. non-"declared" method/field retrieval against the Java reflection API,
     * without calling {@link ClassGraph#ignoreMethodVisibility()} or {@link ClassGraph#ignoreFieldVisibility()}.
     */
    @Test
    public void publicAndPrivateDeclaredVsNonDeclared() {
        try (var scanResult = new ClassGraph().enableClassInfo() //
                .enableMethodInfo().ignoreMethodVisibility() //
                .enableFieldInfo().ignoreFieldVisibility() //
                .acceptPackages(DeclaredVsNonDeclaredTest.class.getPackage().getName()).scan()) {
            final var superClassInfo = scanResult.getClassInfo(SuperClass.class.getName());
            final var subClassInfo = scanResult.getClassInfo(SubClass.class.getName());
            compareResults(superClassInfo, subClassInfo, /* ignoreVisibility = */ true);
        }
    }
}
