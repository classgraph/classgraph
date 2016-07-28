package io.github.lukehutch.fastclasspathscanner.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.StrictAssertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.ClsSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.ClsSubSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Iface;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.IfaceSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.IfaceSubSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1;

public class ClassInfoTest {
    @Test
    public void stream() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().scan();
        assertThat( //
                scanResult.getClassNameToClassInfo().values().stream()
                        .filter(c -> c.getClassName().contains("ClsSub")) //
                        .map(c -> c.getClassName()) //
                        .collect(Collectors.toSet())) //
                                .containsExactly(ClsSub.class.getName(), ClsSubSub.class.getName());
    }

    @Test
    public void streamHasSuperInterface() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().scan();
        assertThat( //
                scanResult.getClassNameToClassInfo().values().stream() //
                        .filter(c -> c.hasSuperinterface(Iface.class.getName())) //
                        .map(c -> c.getClassName()) //
                        .collect(Collectors.toSet())) //
                                .containsExactly(IfaceSub.class.getName(), IfaceSubSub.class.getName());
    }

    @Test
    public void useClassNameToClassInfo() {
        ScanResult scanResult = new FastClasspathScanner().scan();
        Map<String, ClassInfo> classNameToClassInfo = scanResult.getClassNameToClassInfo();
        ClassInfo callableClassInfo = classNameToClassInfo.get(Iface.class.getName());
        List<String> impls = callableClassInfo.getNamesOfClassesImplementing();
        assertThat(impls.contains(Impl1.class.getName())).isTrue();
    }
}
