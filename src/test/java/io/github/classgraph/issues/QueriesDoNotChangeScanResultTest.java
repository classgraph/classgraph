package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ArrayClassInfo;
import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Looking up the class of an array type must not add a class to the scan result.
 */
public class QueriesDoNotChangeScanResultTest {
    /** A class with a field of array type. */
    public static class Target {
        /** A field of array type. */
        public AtomicLong[] field;
    }

    /**
     * Get the {@link ArrayClassInfo} of a field's type, and check the scan result's classes are unchanged.
     */
    @Test
    public void lookingUpAnArrayTypeDoesNotAddAClass() {
        try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptClasses(Target.class.getName())
                .scan()) {
            final Set<String> classNamesBefore = new HashSet<>(scanResult.getAllClassesAsMap().keySet());
            final ArrayTypeSignature fieldType = (ArrayTypeSignature) scanResult.getClassInfo(Target.class.getName())
                    .getFieldInfo("field").getTypeSignatureOrTypeDescriptor();
            final ArrayClassInfo arrayClassInfo = fieldType.getArrayClassInfo();
            assertThat(arrayClassInfo.getName()).isEqualTo("java.util.concurrent.atomic.AtomicLong[]");
            assertThat(scanResult.getAllClassesAsMap().keySet()).isEqualTo(classNamesBefore);
            assertThat(scanResult.getClassInfo("java.util.concurrent.atomic.AtomicLong[]")).isNull();
            assertThat(fieldType.getArrayClassInfo()).isSameAs(arrayClassInfo);
        }
    }
}
