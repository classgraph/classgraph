package io.github.lukehutch.fastclasspathscanner.issues.issue172.a;

public class TestB<T> {
    private final T data;

    public TestB(final T data) {
        this.data = data;
    }

    public T getData() {
        return data;
    }
}
