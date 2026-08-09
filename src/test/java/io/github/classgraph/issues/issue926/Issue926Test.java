package io.github.classgraph.issues.issue926;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.MethodInfo;

/**
 * A javac bridge-method bug (JDK-8385663): javac copies a parameter's TYPE_USE annotation onto the synthetic bridge
 * method, but the bridge's erased descriptor makes the copied type path invalid. For
 * {@code class BridgeImpl implements BridgeInterface<@BridgeAnno String[]>}, the real method {@code f(String[])}
 * gets {@code location=[ARRAY]} (correct), and javac copies that same path onto the bridge {@code f(Object)} where
 * it is bogus.
 *
 * <p>
 * ClassGraph cannot fix an inherently-incorrect type path, but it must not crash on it: the unmatchable annotation
 * is skipped. Verified against a checked-in fixture {@code BridgeImpl.class} (compiled with a javac that exhibits
 * JDK-8385663; see {@code BridgeImpl.java} alongside it). Before the fix, reading the bridge method threw
 * {@code IllegalArgumentException: Bad typePathKind: 0}.
 */
public class Issue926Test {
    private static final String PKG = Issue926Test.class.getPackage().getName();

    @Test
    public void bridgeMethodWithInvalidTypeAnnotationPathDoesNotThrow() {
        try (var scanResult = new ClassGraph().acceptPackages(PKG) //
                .ignoreClassVisibility().enableMethodInfo().enableAnnotationInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(PKG + ".BridgeImpl");
            assertThat(classInfo).isNotNull();

            var sawBridge = false;
            var sawReal = false;
            for (final MethodInfo methodInfo : classInfo.getDeclaredMethodInfo()) {
                if (!"f".equals(methodInfo.getName())) {
                    continue;
                }
                // Neither the real method nor the synthetic bridge may throw while parsing its type (#926).
                assertThatCode(methodInfo::getTypeSignatureOrTypeDescriptor).doesNotThrowAnyException();
                assertThatCode(methodInfo::getTypeDescriptor).doesNotThrowAnyException();
                if (methodInfo.isBridge()) {
                    sawBridge = true;
                } else {
                    sawReal = true;
                }
            }
            assertThat(sawReal).as("real f(String[]) method present").isTrue();
            assertThat(sawBridge).as("synthetic bridge f(Object) method present").isTrue();
        }
    }
}
