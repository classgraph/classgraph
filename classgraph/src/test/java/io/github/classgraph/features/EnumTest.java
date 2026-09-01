package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Test.
 */
public class EnumTest {
    /** Regular enum */
    private enum MyEnumWithoutMethod {
        A, B, C
    }

    private enum EnumWithMethod {
        P(1), Q(2);

        int val;

        EnumWithMethod(final int val) {
            this.val = val;
        }

        int getVal() {
            return val;
        }
    }

    /** An enum whose constants are not declared in alphabetical order. */
    private enum UnsortedEnum {
        ZEBRA, APPLE, MANGO
    }

    /** Test regular enum */
    @Test
    public void enumWithoutMethod() throws Exception {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(MyEnumWithoutMethod.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getAllEnums()).hasSize(1);
            final var myEnum = scanResult.getAllEnums().get(0);
            assertThat(myEnum.getName()).isEqualTo(MyEnumWithoutMethod.class.getName());
            assertThat(myEnum.getEnumConstants().getNames()).containsExactly("A", "B", "C");
        }
    }

    /** Test enum with method */
    @Test
    public void enumWithMethod() throws Exception {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(EnumWithMethod.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getAllEnums()).hasSize(1);
            final var myEnum = scanResult.getAllEnums().get(0);
            assertThat(myEnum.getName()).isEqualTo(EnumWithMethod.class.getName());
            final var constantNames = myEnum.getEnumConstants().getNames();
            assertThat(constantNames).containsExactly("P", "Q");
            // The reported names are the real constant names, in declaration order -- Enum#valueOf throws if a name
            // is not a constant of the enum
            assertThat(constantNames.stream().map(EnumWithMethod::valueOf).map(EnumWithMethod::getVal).toList())
                    .containsExactly(1, 2);
        }
    }

    /**
     * Enum constants are listed in declaration order, which is their ordinal order, even though the fields of a
     * class are otherwise listed in name order.
     */
    @Test
    public void enumConstantsAreInOrdinalOrder() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(UnsortedEnum.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var myEnum = scanResult.getClassInfo(UnsortedEnum.class.getName());
            assertThat(myEnum.getEnumConstants().getNames()).containsExactly("ZEBRA", "APPLE", "MANGO");
            // The fields of the enum class, in contrast, are sorted by name (the synthetic $VALUES field is
            // visible here because ignoreFieldVisibility() was called)
            assertThat(myEnum.getDeclaredFieldInfo().getNames()).containsExactly("$VALUES", "APPLE", "MANGO",
                    "ZEBRA");
        }
    }
}
