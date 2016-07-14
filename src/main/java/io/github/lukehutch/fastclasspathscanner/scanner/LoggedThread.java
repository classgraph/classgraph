package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.concurrent.Callable;

import io.github.lukehutch.fastclasspathscanner.utils.ThreadLog;

public abstract class LoggedThread<T> implements Callable<T> {
    protected ThreadLog log = new ThreadLog();

    @Override
    public T call() throws Exception {
        try {
            return doWork();
        } finally {
            log.flush();
        }
    }

    public abstract T doWork() throws Exception;
}
