package io.github.lukehutch.fastclasspathscanner.issues.issue172.a;

public class TestB<T> {
    private T data;

    public TestB(T data) {
        this.data = data;
    }

    public T getData() {
        return data;
    }
}
