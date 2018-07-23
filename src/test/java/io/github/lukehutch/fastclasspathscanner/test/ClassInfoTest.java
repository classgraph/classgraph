package io.github.lukehutch.fastclasspathscanner.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.ScanResult;
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
    final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(Impl1.class.getPackage().getName())
            .scan();

    @Test
    public void useClassNameToClassInfo() {
        final List<String> impls = scanResult.getClassInfo(Iface.class.getName()).getClassesImplementing()
                .getNames();
        assertThat(impls.contains(Impl1.class.getName())).isTrue();
    }

    @Test
    public void stream() throws Exception {
        assertThat( //
                scanResult.getAllClasses().getNames().stream().filter(n -> n.contains("ClsSub")) //
                        .collect(Collectors.toSet())) //
                                .containsOnly(ClsSub.class.getName(), ClsSubSub.class.getName());
    }

    @Test
    public void streamHasSuperInterfaceDirect() throws Exception {
        assertThat( //
                scanResult.getAllClasses().stream() //
                        .filter(ci -> ci.getInterfaces().directOnly().getNames().contains(Iface.class.getName())) //
                        .map(ci -> ci.getName()) //
                        .collect(Collectors.toSet())) //
                                .containsOnly(IfaceSub.class.getName(), Impl2.class.getName());
    }

    @Test
    public void streamHasSuperInterface() throws Exception {
        assertThat( //
                scanResult.getAllClasses().stream() //
                        .filter(ci -> ci.getInterfaces().getNames().contains(Iface.class.getName())) //
                        .map(ci -> ci.getName()) //
                        .collect(Collectors.toSet())) //
                                .containsOnly(IfaceSub.class.getName(), IfaceSubSub.class.getName(),
                                        Impl2.class.getName(), Impl2Sub.class.getName(),
                                        Impl2SubSub.class.getName(), Impl1.class.getName(),
                                        Impl1Sub.class.getName(), Impl1SubSub.class.getName());
    }

    @Test
    public void implementsInterfaceDirect() {
        assertThat( //
                scanResult.getClassesImplementing(Iface.class.getName()).directOnly().stream() //
                        .map(ci -> ci.getName()) //
                        .collect(Collectors.toList())) //
                                .containsOnly(IfaceSub.class.getName(), Impl2.class.getName());
    }

    @Test
    public void implementsInterface() {
        assertThat( //
                scanResult.getClassesImplementing(Iface.class.getName()).stream() //
                        .map(ci -> ci.getName()) //
                        .collect(Collectors.toList())) //
                                .containsOnly(Impl1.class.getName(), Impl1Sub.class.getName(),
                                        Impl1SubSub.class.getName(), Impl2.class.getName(),
                                        Impl2Sub.class.getName(), Impl2SubSub.class.getName(),
                                        IfaceSub.class.getName(), IfaceSubSub.class.getName());
    }

    @Test
    public void multiCriteria() {
        final Set<String> matches = scanResult.getAllClasses().stream() //
                .filter(ci -> ci.getInterfaces().getNames().contains(Iface.class.getName())) //
                .filter(ci -> ci.getSuperclasses().getNames().contains(Impl1.class.getName())) //
                .map(ci -> ci.getName()) //
                .collect(Collectors.toSet());
        assertThat(matches).containsOnly(Impl1Sub.class.getName(), Impl1SubSub.class.getName());
    }
}
