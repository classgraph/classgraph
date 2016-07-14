package io.github.lukehutch.fastclasspathscanner.scanner;

public class ScanInterruptedException extends RuntimeException {
    public ScanInterruptedException() {
        super();
    }

    public ScanInterruptedException(final String msg) {
        super(msg);
    }
}
