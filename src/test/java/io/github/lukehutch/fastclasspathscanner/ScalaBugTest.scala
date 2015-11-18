import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner

package io.github.lukehutch.fastclasspathscanner {
  trait Inside
  object G extends Inside
  object F extends otherpackage.Outside

  class ScalaBugTest {
    @Test
    def findObjectsTraitOtherPackage = {
      val scanner = new FastClasspathScanner("io.github.lukehutch.fastclasspathscanner").scan()
      assertTrue(scanner.getNamesOfClassesImplementing("io.github.lukehutch.fastclasspathscanner.Inside") //
        .contains("io.github.lukehutch.fastclasspathscanner.G"))
      assertFalse(scanner.getNamesOfClassesImplementing("otherpackage.Outside").isEmpty)
    }
  }
}

package otherpackage {
  trait Outside
}
