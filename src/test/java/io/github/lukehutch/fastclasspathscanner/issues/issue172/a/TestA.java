package io.github.lukehutch.fastclasspathscanner.issues.issue172.a;

public class TestA {
    public <T> TestB<T> toTestB(TestC<T> other)
    {
        return new TestB(other.getData());
    }
}
