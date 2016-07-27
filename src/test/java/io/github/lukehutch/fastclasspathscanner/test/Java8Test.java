package io.github.lukehutch.fastclasspathscanner.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class Java8Test {
    @Test
    public void stream() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().scan();
        assertThat( //
                scanResult.getClassNameToClassInfo().values().stream()
                        .filter(c -> c.getClassName().contains("ClsSub")) //
                        .map(c -> c.getClassName()) //
                        .collect(Collectors.toSet())) //
                                .containsExactly("io.github.lukehutch.fastclasspathscanner.test.whitelisted.ClsSub",
                                        "io.github.lukehutch.fastclasspathscanner.test.whitelisted.ClsSubSub");
    }
}
