package io.github.lukehutch.fastclasspathscanner.issues.issue172;

import java.io.IOException;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.utils.TypeParser;

public class Issue172Test {
    @Test
    public void classAnnotationParameters() throws IOException {
        TypeParser.parseMethodSignature("<T:Ljava/lang/Object;>(Lcom/xyz/TestC<TT;>;)Lcom/xyz/TestB<TT;>;");
    }
}
