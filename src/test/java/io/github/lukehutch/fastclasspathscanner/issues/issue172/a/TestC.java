package io.github.lukehutch.fastclasspathscanner.issues.issue172.a;

public class TestC<T> {
    private T data;

    public TestC(T data) {
        this.data = data;
    }

    public T getData() {
        return data;
    }
}
