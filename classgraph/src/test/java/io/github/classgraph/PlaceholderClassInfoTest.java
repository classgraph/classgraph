package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Timer;
import java.util.TimerTask;

import org.junit.jupiter.api.Test;

/**
 * A class that was not scanned gets a placeholder {@link ClassInfo} only if it is named in a position that forms an
 * edge of the class graph: a superclass, an implemented interface, an outer or inner class, or an annotation. A
 * class named only in a type descriptor or type signature does not, since that would force every descriptor and
 * signature of every scanned class to be parsed before the {@link ScanResult} could be returned, rather than lazily
 * on demand. {@link ClassGraph#enableInterClassDependencies()} is the supported way to ask for the latter.
 */
public class PlaceholderClassInfoTest {
    /** Names an unscanned class as a superclass, and others only in field type descriptors. */
    public static class NamesUnscannedClasses extends TimerTask {
        /** Names an unscanned class in a type descriptor. */
        public Timer inATypeDescriptor;

        /** Names an unscanned class as the element type of an array. */
        public Timer[] asAnArrayElementType;

        /** Has a primitive element type. */
        public int[] primitiveArray;

        @Override
        public void run() {
        }
    }

    /** Scan the one fixture class only, so that everything it names in {@code java.util} is unscanned. */
    private static ClassGraph classGraph() {
        return new ClassGraph().enableClasspath().acceptClasses(NamesUnscannedClasses.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility();
    }

    /** Get the {@link ArrayClassInfo} for the type of one of the array fields. */
    private static ArrayClassInfo arrayClassInfoForField(final ScanResult scanResult, final String fieldName) {
        final var fieldType = scanResult.getClassInfo(NamesUnscannedClasses.class.getName()).getFieldInfo(fieldName)
                .getTypeSignatureOrTypeDescriptor();
        return ((ArrayTypeSignature) fieldType).getArrayClassInfo();
    }

    /** A class named as a superclass gets a {@link ClassInfo}; a class named only as a field type does not. */
    @Test
    public void onlyClassGraphEdgesGetAPlaceholder() {
        try (var scanResult = classGraph().scan()) {
            assertThat(scanResult.getClassInfo(TimerTask.class.getName())).isNotNull();
            assertThat(scanResult.getClassInfo(Timer.class.getName())).isNull();

            // An array element type is named in a type descriptor, so it is treated the same way
            assertThat(arrayClassInfoForField(scanResult, "asAnArrayElementType").getElementClassInfo()).isNull();
        }
    }

    /** With inter-class dependencies enabled, a class named only as a field type does get a {@link ClassInfo}. */
    @Test
    public void interClassDependenciesGivePlaceholdersToTypesToo() {
        try (var scanResult = classGraph().enableInterClassDependencies().scan()) {
            assertThat(scanResult.getClassInfo(Timer.class.getName())).isNotNull();
            assertThat(arrayClassInfoForField(scanResult, "asAnArrayElementType").getElementClassInfo())
                    .isNotNull();
        }
    }

    /**
     * A primitive type never gets a {@link ClassInfo}: it has no classfile to scan, so there would be nothing to
     * put in one, and it takes no part in the class graph.
     */
    @Test
    public void primitiveTypesNeverGetAClassInfo() {
        try (var scanResult = classGraph().enableInterClassDependencies().enableExternalClasses().scan()) {
            assertThat(scanResult.getClassInfo("int")).isNull();
            assertThat(scanResult.getAllClasses().getNames()).doesNotContain("int", "void");
            assertThat(arrayClassInfoForField(scanResult, "primitiveArray").getElementClassInfo()).isNull();
        }
    }
}
