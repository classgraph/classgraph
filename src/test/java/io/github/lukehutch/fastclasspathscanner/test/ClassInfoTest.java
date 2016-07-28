package io.github.lukehutch.fastclasspathscanner.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.StrictAssertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1Sub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1SubSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl2;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl2Sub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl2SubSub;

public class ClassInfoTest {
    @Test
    public void useClassNameToClassInfo() {
        final ScanResult scanResult = new FastClasspathScanner().scan();
        final Map<String, ClassInfo> classNameToClassInfo = scanResult.getClassNameToClassInfo();
        final ClassInfo callableClassInfo = classNameToClassInfo.get(Iface.class.getName());
        final List<String> impls = callableClassInfo.getNamesOfClassesImplementing();
        assertThat(impls.contains(Impl1.class.getName())).isTrue();
    }

    @Test
    public void stream() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().scan();
        assertThat( //
                scanResult.getClassNameToClassInfo().values().stream()
                        .filter(c -> c.getClassName().contains("ClsSub")) //
                        .map(c -> c.getClassName()) //
                        .collect(Collectors.toSet())) //
                                .containsOnly(ClsSub.class.getName(), ClsSubSub.class.getName());
    }

    @Test
    public void streamHasSuperInterface() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().scan();
        assertThat( //
                scanResult.getClassNameToClassInfo().values().stream() //
                        .filter(c -> c.hasSuperinterface(Iface.class.getName())) //
                        .map(c -> c.getClassName()) //
                        .collect(Collectors.toSet())) //
                                .containsOnly(IfaceSub.class.getName(), IfaceSubSub.class.getName());
    }

    @Test
    public void implementsInterface() {
        final ScanResult scanResult = new FastClasspathScanner().scan();
        assertThat( //
                scanResult.getClassNameToClassInfo().values().stream() //
                        .filter(c -> c.implementsInterface(Iface.class.getName())) //
                        .map(c -> c.getClassName()) //
                        .collect(Collectors.toSet())) //
                                .containsOnly(Impl1.class.getName(), Impl1Sub.class.getName(),
                                        Impl1SubSub.class.getName(), Impl2.class.getName(),
                                        Impl2Sub.class.getName(), Impl2SubSub.class.getName());
    }

    @Test
    public void multiCriteria() {
        final ScanResult scanResult = new FastClasspathScanner().scan();
        final Set<String> matches = scanResult.getClassNameToClassInfo().values().stream() //
                .filter(c -> c.implementsInterface(Iface.class.getName())) //
                .filter(c -> c.hasSuperclass(Impl1.class.getName())) //
                .map(c -> c.getClassName()) //
                .collect(Collectors.toSet());
        assertThat(matches).containsOnly(Impl1Sub.class.getName(), Impl1SubSub.class.getName());
    }
}
