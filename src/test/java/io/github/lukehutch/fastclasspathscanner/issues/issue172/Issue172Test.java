package io.github.lukehutch.fastclasspathscanner.issues.issue172;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.issues.issue172.a.TestA;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.MethodInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

import java.io.IOException;
import java.util.Map;

import org.junit.Test;

public class Issue172Test {
    @Test
    public void classAnnotationParameters() throws IOException {
        ScanResult result =
                new FastClasspathScanner(TestA.class.getPackage().getName())
                        .enableMethodInfo()
                        .verbose()
                        .scan();

        Map<String, ClassInfo> allInfo = result.getClassNameToClassInfo();
        for (String name : result.getNamesOfAllClasses()) {
            for (MethodInfo method : allInfo.get(name).getMethodAndConstructorInfo()) {
                System.out.println(method.toString());
            }
        }
    }
}
