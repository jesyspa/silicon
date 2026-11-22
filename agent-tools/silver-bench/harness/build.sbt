scalaVersion := "2.13.18"
// Compiles against the classpath of the FULL Silver build, exported by build-arms.sh into $BENCH_DIR/cp-full.txt
Compile / unmanagedJars ++= scala.io.Source.fromFile(sys.env("BENCH_DIR") + "/cp-full.txt").mkString.trim.split(":").toSeq.map(f => Attributed.blank(file(f)))
