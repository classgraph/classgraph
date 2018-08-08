package com.xyz;

import java.io.IOException;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class ScanEverything {

//    @Test
//    public void scanEverything() throws IOException {
//        final long t0 = System.nanoTime();
//        final ScanResult scanResult = new ClassGraph() //
//                // .verbose() //
//                // .whitelistPackages("io.github") //
//                // .enableAllInfo() //
//                .enableSystemPackages() //
//                .disableJarScanning() //
//                .disableModuleScanning() //
//                .scan() //
//        //.getAllClasses() //
//        // .loadClasses() //
//        ;
//        final long t1 = System.nanoTime();
//        System.out.println((t1 - t0) * 1.0e-9);
//
//        System.out.println(scanResult.getAllResources().size());
//
//        for (int i = 0; i < 500; i++) {
//            scanResult.getAllResources().forEachByteArray((r, b) -> {
//            });
//        }
//
//        final long t2 = System.nanoTime();
//        System.out.println((t2 - t1) * 1.0e-9);
//    }

}
