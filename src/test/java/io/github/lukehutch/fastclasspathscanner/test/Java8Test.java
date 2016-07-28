package io.github.lukehutch.fastclasspathscanner.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.ClsSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.ClsSubSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Iface;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.IfaceSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.IfaceSubSub;

public class Java8Test {
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
}
