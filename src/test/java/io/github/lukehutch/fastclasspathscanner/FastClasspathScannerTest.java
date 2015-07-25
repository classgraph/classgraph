package io.github.lukehutch.fastclasspathscanner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;


public class FastClasspathScannerTest {

  @Test
  public void scanAll() throws Exception {
    final FastClasspathScanner scanner = new FastClasspathScanner() ;
    scanner.scan() ;
    final List< String > subclasses = scanner.getSubclassesOf( FastClasspathScannerTest.class ) ;

    assertTrue( subclasses.size() > 0 ) ;
    assertTrue( subclasses.contains( Subclass.class.getName() ) ) ;
  }

  @Test
  public void scanWithWhitelist() throws Exception {
    final FastClasspathScanner scanner = new FastClasspathScanner( PACKAGE_NAME ) ;
    scanner.scan() ;
    final List< String > subclasses = scanner.getSubclassesOf( FastClasspathScannerTest.class ) ;

    assertTrue( subclasses.size() > 0 ) ;
    assertTrue( subclasses.contains( Subclass.class.getName() ) ) ;
  }

  @Test
  public void scanWithFileFilter() throws Exception {
    final FastClasspathScanner scanner = new FastClasspathScanner() ;
    scanner
        .matchFilenamePattern(
            ".*" + PACKAGE_NAME + "[$_a-zA-Z0-9]+\\.class",
            ( absolutePath, relativePath, inputStream ) -> { }
        )
        .scan() ;
    final List< String > subclasses = scanner.getSubclassesOf( FastClasspathScannerTest.class ) ;

    assertTrue( subclasses.size() > 0 ) ;
    assertTrue( subclasses.contains( Subclass.class.getName() ) ) ;
  }

  @Test
  public void match() throws Exception {
    final List< Class > collector = new ArrayList<>() ;
    final FastClasspathScanner scanner = new FastClasspathScanner()
        .matchSubclassesOf( FastClasspathScanner.class, collector::add )
        ;
    scanner.scan() ;

    assertTrue( collector.size() > 0 ) ;
    assertTrue( collector.contains( Subclass.class ) ) ;
  }

  @Test
  public void matchWithWhitelist() throws Exception {
    final List< Class > collector = new ArrayList<>() ;
    final FastClasspathScanner scanner = new FastClasspathScanner( PACKAGE_NAME )
        .matchSubclassesOf( FastClasspathScanner.class, collector::add )
        ;
    scanner.scan() ;

    assertTrue( collector.size() > 0 ) ;
    assertTrue( collector.contains( Subclass.class ) ) ;
  }


  public static class Subclass extends FastClasspathScannerTest { }

  private static final String PACKAGE_NAME = FastClasspathScannerTest.class.getPackage().getName() ;


}