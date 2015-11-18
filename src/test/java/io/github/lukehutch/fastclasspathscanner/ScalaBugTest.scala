package io.github.lukehutch.fastclasspathscanner {

import org.junit.Test
import org.junit.Assert._


object F extends someothersillypackage.Outside

class ScalaBugTest {

  @Test
  def findObjectsTraitOtherPackage = {
    //This reroduces #29. The line below should work, but we have to add the outside trait's package to make it all compile
    val scanner = new FastClasspathScanner("io.github.lukehutch.fastclasspathscanner")
//    val scanner = new FastClasspathScanner("io.github.lukehutch.fastclasspathscanner", "someothersillypackage")
    assertFalse(
      scanner.verbose().scan()
      .getNamesOfClassesImplementing("someothersillypackage.Outside").isEmpty
    )
  }
}

}

package someothersillypackage {

trait Outside

}
