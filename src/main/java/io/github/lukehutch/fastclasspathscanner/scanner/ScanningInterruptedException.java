package io.github.lukehutch.fastclasspathscanner.scanner;

public class ScanningInterruptedException extends RuntimeException {
    public ScanningInterruptedException() {
        super();
    }

    public ScanningInterruptedException(String msg) {
        super(msg);
    }
}
