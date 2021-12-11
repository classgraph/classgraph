package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Test.
 */
public class EnumTest {
    /** Enum */
    private static enum MyEnum {
        A, B, C;
    }

    /** Test */
    @Test
    public void enumConsts() throws Exception {
        try (ScanResult scanResult = new ClassGraph().acceptClasses(MyEnum.class.getName()).enableAllInfo()
                .scan()) {
            assertThat(scanResult.getAllEnums().size() == 1);
            final ClassInfo myEnum = scanResult.getAllEnums().get(0);
            assertThat(myEnum.getName().equals(MyEnum.class.getName()));
            assertThat(myEnum.getEnumConstants().getNames()).containsExactly("A", "B", "C");
        }
    }
}
