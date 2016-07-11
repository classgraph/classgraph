package io.github.lukehutch.fastclasspathscanner.scanner;

public class ScanInterruptedException extends RuntimeException {
    public ScanInterruptedException() {
        super();
    }

    public ScanInterruptedException(String msg) {
        super(msg);
    }
}
