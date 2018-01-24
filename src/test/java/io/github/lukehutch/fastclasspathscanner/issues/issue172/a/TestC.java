package io.github.lukehutch.fastclasspathscanner.issues.issue172.a;

public class TestC<T> {
    private final T data;

    public TestC(final T data) {
        this.data = data;
    }

    public T getData() {
        return data;
    }
}
