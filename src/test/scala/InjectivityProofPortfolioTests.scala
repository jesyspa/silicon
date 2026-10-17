package viper.silicon.decider

import java.io._
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Config
import viper.silicon.state.{DefaultIdentifierFactory, Identifier, SimpleIdentifier, SuffixedIdentifier}
import viper.silicon.state.terms._
import viper.silicon.verifier.Verifier
import viper.silver.reporter.NoopReporter

class InjectivityProofPortfolioTests extends AnyFunSuite with BeforeAndAfter {
  private val converter = new TermToSMTLib2Converter
  before { Verifier.config = new Config(Seq("--proverEnableTimeBounds", "test.vpr")); converter.start() }
  after { converter.stop(); System.clearProperty("silicon.injectivityProofPortfolio") }
  private def variable(n: String, s: Sort = sorts.Int): Var = Var(Identifier(n), s, false)
  private def x = variable("i")
  private def y = variable("j")
  private def f = Fun(Identifier("f"), Seq(sorts.Int), sorts.Int)
  private def domain(v: Term): Term = AtMost(IntLiteral(0), v)
  private def ax(a: Var = x, b: Var = y, guard: Option[Term] = None,
                 left: Option[Term] = None, right: Option[Term] = None): Quantification =
    Quantification(Forall, Seq(a, b), Implies(
      guard.getOrElse(And(domain(a), domain(b), Not(BuiltinEquals(a, b)))),
      Not(BuiltinEquals(left.getOrElse(App(f, a)), right.getOrElse(App(f, b))))), Nil, "", false, None)
  private def recognized(q: Term) = InjectivityProofPortfolio.recognize(q, converter)
  private def captured(q: Term = ax()): InjectivityProofPortfolio.Capture = {
    val c = new InjectivityProofPortfolio.Capture
    c.record("(set-option :print-success true)")
    c.record("(set-option :global-decls true)")
    c.record(converter.convert(FunctionDecl(f)))
    c.record(s"(assert ${converter.convert(q)})")
    recognized(q).foreach(c.register)
    c
  }
  private def prove(c: InjectivityProofPortfolio.Capture, goal: String, exe: String = "z3"): Boolean =
    c.ready(goal, converter) && c.prove(goal, exe)

  test("typed positive assumptions and independently copied domains; negative assumptions are not candidates") {
    assert(recognized(ax()).nonEmpty)
    assert(recognized(Not(ax())).isEmpty)
    assert(recognized(And(ax(), True)).nonEmpty) // Term simplification preserves a directly positive root.
    assert(recognized(Implies(variable("enabled", sorts.Bool), ax())).isEmpty)
    assert(recognized(ax(guard = Some(And(domain(x), Less(y, IntLiteral(10)), Not(BuiltinEquals(x, y)))))).isEmpty)
    assert(recognized(ax(guard = Some(And(Less(x, y), Not(BuiltinEquals(x, y)))))).isEmpty)
  }

  test("typed Applicable identity, result sorts and index sorts remain significant") {
    val g = DomainFun(Identifier("f"), Seq(sorts.Int), sorts.Int)
    assert(recognized(ax(right = Some(App(g, y)))).isEmpty)
    val b = variable("j", sorts.Ref)
    val refMap = Fun(Identifier("refMap"), Seq(sorts.Ref), sorts.Int)
    assert(recognized(ax(b = b, guard = Some(Not(BuiltinEquals(App(f, x), App(refMap, b)))), right = Some(App(refMap, b)))).isEmpty)
    val other = Fun(Identifier("g"), Seq(sorts.Int), sorts.Int)
    assert(recognized(ax(right = Some(App(other, y)))).isEmpty)
  }

  test("closed global constants are supported only after declaration; varying parameter and nested binder contexts fail closed") {
    val n = variable("n")
    val q = ax(guard = Some(And(Less(x, n), Less(y, n), Not(BuiltinEquals(x, y)))))
    val c = captured(q)
    assert(!c.eligible)
    c.global(n)
    c.record(s"(assert ${converter.convert(q)})")
    c.register(recognized(q).get)
    assert(c.eligible)
    val nested = Quantification(Forall, Seq(n), q, Nil, "", false, None)
    assert(recognized(nested).isEmpty)
    val localGuard = Quantification(Forall, Seq(n), Less(n, x), Nil, "", false, None)
    assert(recognized(ax(guard = Some(And(localGuard, Not(BuiltinEquals(x, y)))))).isEmpty)
    val localLet = Let(n, IntLiteral(0), Less(n, x))
    assert(recognized(ax(guard = Some(And(localLet, Not(BuiltinEquals(x, y)))))).isEmpty)
    assert(recognized(ax(guard = Some(And(domain(x), domain(y), Less(n, IntLiteral(5)), Not(BuiltinEquals(x, y)))))).isEmpty)
  }

  test("binder aliases, quoted semantic names, sanitization aliases and shadowed Applicables are rejected") {
    val sameName = Var(SuffixedIdentifier(SimpleIdentifier("i"), "", ""), sorts.Int, false)
    assert(x != sameName)
    assert(recognized(ax(b = sameName)).isEmpty)
    assert(recognized(ax(a = variable("|i|"))).isEmpty)
    assert(recognized(ax(a = variable("i bad"))).isEmpty)
    val shadow = Fun(Identifier("i"), Seq(sorts.Int), sorts.Int)
    assert(recognized(ax(left = Some(App(shadow, x)), right = Some(App(shadow, y)))).isEmpty)
    val nullary = Fun(Identifier("i"), Nil, sorts.Int)
    val pair = Fun(Identifier("pair"), Seq(sorts.Int, sorts.Int), sorts.Int)
    assert(recognized(ax(left = Some(App(pair, Seq(x, App(nullary, Nil)))),
      right = Some(App(pair, Seq(y, App(nullary, Nil)))))).isEmpty)
    assert(recognized(ax(left = Some(App(pair, Seq(x, sameName))),
      right = Some(App(pair, Seq(y, sameName))))).isEmpty)
    val bad = variable("i", sorts.Ref)
    val mixed = Fun(Identifier("mixed"), Seq(sorts.Int, sorts.Ref), sorts.Int)
    assert(recognized(ax(left = Some(App(mixed, Seq(x, bad))), right = Some(App(mixed, Seq(y, bad))))).isEmpty)
  }

  test("scope tracking, reset lifecycle, replay memory overflow and historical query omission") {
    val c = new InjectivityProofPortfolio.Capture
    c.push(2)
    c.record(s"(assert ${converter.convert(ax())})"); c.register(recognized(ax()).get)
    assert(c.eligible)
    c.pop(1); assert(!c.eligible)
    c.pop(1); c.clear(); assert(!c.enabled)
    val small = new InjectivityProofPortfolio.Capture(100)
    small.record("(get-model)" * 1000)
    assert(small.enabled)
    small.record("x" * 100)
    assert(!small.enabled)
    val normal = captured()
    normal.record("(check-sat)"); normal.record("(get-model)")
    assert(normal.ready("true", converter))
  }

  test("sort-name capture and unsupported command, option, soft-query and temporary-assumption classes fail closed") {
    val nullary = captured()
    nullary.record("(declare-fun i () Int)"); nullary.record("(declare-const j Int)")
    assert(nullary.ready(converter.convert(ax()), converter))
    val c = captured()
    c.record("(declare-sort i 0)")
    assert(!c.ready("true", converter))
    Seq("(reset-assertions)", "(set-option :produce-proofs true)", "(set-option :produce-unsat-cores true)",
      "(set-option :produce-assignments true)", "(set-option :regular-output-channel \"out\")",
      "(set-option :global-decls false)", "(set-option :print-success false)",
      "(check-sat-assuming ()) (assert false)").foreach { cmd =>
      val invalid = captured(); invalid.record(s"(assert true) $cmd")
      assert(!invalid.ready("true", converter), cmd)
    }
    Seq("(declare-const |i| Int)", "(declare-const i Bool)", "(declare-fun i (Int) Int)",
      "(define-sort i () Int)", "(define-const i Int 0)",
      "(define-const |i| Int 0)", "(define-const harmless Int (+ i 1))").foreach { declaration =>
      val alias = captured(); alias.record(declaration)
      assert(!alias.ready("true", converter), declaration)
    }
    val defined = captured()
    defined.record("(define-sort Integer () Int)")
    defined.record("(define-const harmless Integer (+ 1 2))")
    assert(defined.ready(converter.convert(ax()), converter))
  }

  test("return overloads, numeric-coercion overloads, aliases and datatype collisions cannot redirect resolution") {
    Seq("(declare-fun f (Int) Bool)", "(declare-fun |f| (Int) Bool)",
      "(declare-fun f (Real) Int)", "(define-fun f () Int 0)",
      "(define-const f Int 0)", "(define-const |f| Int 0)",
      "(declare-datatypes ((D 0)) (((mk (f Int)))))").foreach { declaration =>
      val c = captured(); c.record(declaration)
      assert(!c.ready(converter.convert(ax()), converter), declaration)
    }
    val alias = captured()
    alias.record("(define-sort Integer () Int)")
    alias.record("(declare-fun f (Integer) Bool)")
    assert(!alias.ready(converter.convert(ax()), converter))
    val overloaded = captured()
    overloaded.record("(declare-fun missing (Int) Bool)")
    val missing = Fun(Identifier("missing"), Seq(sorts.Int), sorts.Int)
    val q = ax(left = Some(App(missing, x)), right = Some(App(missing, y)))
    overloaded.record(s"(assert ${converter.convert(q)})"); overloaded.register(recognized(q).get)
    overloaded.record("(declare-fun missing (Int) Int)")
    assert(!overloaded.ready(converter.convert(ax()), converter))

    val datatype = "(declare-datatypes (($Snap 0)) ((($Snap.unit) ($Snap.combine ($Snap.first $Snap) ($Snap.second $Snap)))))"
    val selectorQ = ax(left = Some(First(SortWrapper(x, sorts.Snap))), right = Some(First(SortWrapper(y, sorts.Snap))))
    def selectorReady(term: Term, commands: String*): Boolean = {
      val c = new InjectivityProofPortfolio.Capture
      commands.foreach(c.record)
      c.record(s"(assert ${converter.convert(term)})"); c.register(recognized(term).get)
      c.ready(converter.convert(term), converter)
    }
    assert(selectorReady(selectorQ, datatype))
    val wrong = Fun(Identifier("$Snap.first"), Seq(sorts.Int), sorts.Int)
    assert(!selectorReady(ax(left = Some(App(wrong, x)), right = Some(App(wrong, y))), datatype))
    val selector = Fun(Identifier("$Snap.first"), Seq(sorts.Snap), sorts.Snap)
    assert(selectorReady(ax(left = Some(App(selector, SortWrapper(x, sorts.Snap))),
      right = Some(App(selector, SortWrapper(y, sorts.Snap)))), datatype))
    Seq(
      Seq(datatype, "(declare-fun $Snap.first ($Snap) Int)"),
      Seq(datatype, "(declare-fun |$Snap.first| ($Snap) $Snap)"),
      Seq(datatype, "(define-fun $Snap.first ((s $Snap)) $Snap s)"),
      Seq(datatype, "(declare-sort $Snap.first 0)"),
      Seq(datatype, "(define-sort $Snap.first () Int)"),
      Seq(datatype.replace("$Snap.first", "i")),
      Seq(datatype, "(define-sort $Snap () Int)"),
      Seq(datatype, "(declare-sort $Snap 1)"),
      Seq(datatype.replace("$Snap.first $Snap", "$Snap.first Int")),
      Seq(datatype.replace("$Snap.second $Snap", "$Snap.first Int")),
      Seq(datatype.replace("$Snap.second $Snap", "$Snap.first $Snap")),
      Seq(datatype.replace("$Snap.first $Snap", "|$Snap.first| $Snap")),
      Seq(datatype.replace("$Snap.first $Snap", "$Snap.first Unknown")),
      Seq(datatype.replace("$Snap.first $Snap", "$Snap.first Alias"), "(define-sort Alias () Int)"),
      Seq(datatype.replace("$Snap 0", "$Snap 1")),
      Seq(datatype.replace("(($Snap 0))", "(($Snap 0) (Other 0))")),
      Seq(datatype.replace("$Snap.first $Snap", "$Snap.first ($Snap)"))
    ).foreach(commands => assert(!selectorReady(selectorQ, commands: _*), commands.mkString("\n")))
  }

  test("global renaming preserves quoted/unquoted aliases, strings, and the full numeric-suffix bijection") {
    val renamed = InjectivityProofPortfolio.rename("(= x@01 |x@01|) ; comment\n (echo \"x@01\")", 3)
    assert(renamed.contains("x@04 |x@04|"))
    assert(renamed.contains("\"x@01\""))
    val names = (1 to 99).map(i => f"x@$i%02d").mkString(" ")
    assert(InjectivityProofPortfolio.tokens(InjectivityProofPortfolio.rename(names, 19)).toSet == InjectivityProofPortfolio.tokens(names).toSet)
  }

  test("native Z3 proves only unchanged negative queries; unrelated invalid goals remain unproved") {
    val goal = converter.convert(ax())
    assert(prove(captured(), goal))
    assert(!prove(captured(), "false"))

    def rejectsWithRealSAT(c: InjectivityProofPortfolio.Capture, invalid: Term): Unit = {
      val query = converter.convert(invalid)
      assert(InjectivityProofPortfolio.eligibleGoal(invalid))
      assert(c.eligible && c.ready(query, converter))
      val dir = Files.createTempDirectory("silicon-negative-side-")
      val script = dir.resolve("solver")
      val attempts = mutable.ArrayBuffer.empty[java.nio.file.Path]
      try {
        Files.write(script, s"""#!/bin/sh
attempt=$$(mktemp -d "$dir/attempt-XXXXXX")
cp "$$2" "$$attempt/replay"
z3 "$$@" > "$$attempt/response"
status=$$?
cat "$$attempt/response"
exit $$status
""".getBytes("UTF-8"))
        assert(script.toFile.setExecutable(true))
        assert(!c.prove(query, script.toString))
        val entries = Files.list(dir)
        try entries.forEach(p => if (Files.isDirectory(p)) attempts += p) finally entries.close()
        assert(attempts.size == 4)
        attempts.foreach { attempt =>
          val output = new String(Files.readAllBytes(attempt.resolve("response")), "UTF-8")
          info("real side replay: " + new String(Files.readAllBytes(attempt.resolve("replay")), "UTF-8"))
          info("real side response: " + output)
          assert(output.linesIterator.map(_.trim).filterNot(s => s.isEmpty || s == "success").toVector ==
            Vector("silicon-proof-begin", "sat", "silicon-proof-end"))
        }
      } finally {
        attempts.foreach { p => Files.deleteIfExists(p.resolve("replay")); Files.deleteIfExists(p.resolve("response")); Files.deleteIfExists(p) }
        Files.deleteIfExists(script); Files.deleteIfExists(dir)
      }
    }
    val finite = ax(guard = Some(And(domain(x), AtMost(x, IntLiteral(1)),
      domain(y), AtMost(y, IntLiteral(1)), Not(BuiltinEquals(x, y)))))
    val outside = captured(finite)
    outside.record("(assert (and (= (f 0) 0) (= (f 1) 1) (= (f (- 1)) 7) (= (f (- 2)) 7)))")
    val weakened = ax(guard = Some(And(AtMost(IntLiteral(-2), x), AtMost(x, IntLiteral(1)),
      AtMost(IntLiteral(-2), y), AtMost(y, IntLiteral(1)), Not(BuiltinEquals(x, y)))))
    // The feasible map is injective on {0,1}, but collides at -1 and -2.
    rejectsWithRealSAT(outside, weakened)

    val snapMap = Fun(Identifier("snapMap"), Seq(sorts.Int), sorts.Snap)
    val selector = ax(guard = Some(And(domain(x), AtMost(x, IntLiteral(1)),
      domain(y), AtMost(y, IntLiteral(1)), Not(BuiltinEquals(x, y)))),
      left = Some(First(App(snapMap, x))), right = Some(First(App(snapMap, y))))
    val selectors = new InjectivityProofPortfolio.Capture
    Seq("(set-option :print-success true)", "(set-option :global-decls true)",
      "(declare-datatypes (($Snap 0)) ((($Snap.unit) ($Snap.combine ($Snap.first $Snap) ($Snap.second $Snap)))))",
      converter.convert(FunctionDecl(snapMap)),
      "(assert (= (snapMap 0) ($Snap.combine $Snap.unit $Snap.unit)))",
      "(assert (= (snapMap 1) ($Snap.combine ($Snap.combine $Snap.unit $Snap.unit) $Snap.unit)))",
      "(assert (= ($Snap.first $Snap.unit) $Snap.unit))",
      "(assert (= ($Snap.second $Snap.unit) $Snap.unit))",
      s"(assert ${converter.convert(selector)})").foreach(selectors.record)
    selectors.register(recognized(selector).get)
    val freedom = Quantification(Forall, Seq(x, y), Implies(
      And(BuiltinEquals(x, IntLiteral(0)), BuiltinEquals(y, IntLiteral(1))),
      Not(BuiltinEquals(First(viper.silicon.state.terms.Unit), Second(viper.silicon.state.terms.Unit)))), Nil, "", false, None)
    // Selectors on unit are unconstrained, despite injectivity of the guarded map.
    rejectsWithRealSAT(selectors, freedom)
    val freshCollision = captured()
    freshCollision.record("(declare-const |$injectivityInverse0| Int)")
    freshCollision.record("(define-const |$injectivityInverse0$| Int (+ 1 2))")
    assert(prove(freshCollision, goal))
  }

  test("a declared global sequence parameter supports guarded unary maps and the generated equality-form goal") {
    val srt = sorts.Seq(sorts.Ref)
    val otherSort = sorts.Seq(sorts.Int)
    val s = variable("$t@4@01", sorts.Snap)
    val wrapped = SortWrapper(s, srt)
    def at(v: Term): Term = SeqAt(wrapped, v)
    assert(converter.convert(wrapped) == "($SortWrappers.$SnapToSeq<$Ref> $t@4@01)")
    val q = ax(left = Some(at(x)), right = Some(at(y)))
    val c = new InjectivityProofPortfolio.Capture
    Seq("(set-option :print-success true)", "(set-option :global-decls true)",
      converter.convert(SortDecl(sorts.Snap)), converter.convert(SortDecl(sorts.Ref)),
      converter.convert(SortDecl(srt)), converter.convert(SortDecl(otherSort)),
      converter.convert(FunctionDecl(s)), converter.convert(SortWrapperDecl(sorts.Snap, srt)),
      "(declare-fun Seq_index (Seq<$Ref> Int) $Ref)",
      "(declare-fun Seq_index (Seq<Int> Int) Int)").foreach(c.record)
    c.global(s); c.record(s"(assert ${converter.convert(q)})")
    info(s"typed assumption: ${converter.convert(q)}")
    assert(recognized(q).nonEmpty, converter.convert(q))
    c.register(recognized(q).get)
    assert(c.eligible)
    info("typed recognition=true; registered capture eligible=true")
    val goal = Quantification(Forall, Seq(x, y),
      Implies(And(domain(x), domain(y), BuiltinEquals(at(x), at(y))), BuiltinEquals(x, y)), Nil, "", false, None)
    assert(InjectivityProofPortfolio.eligibleGoal(goal))
    assert(c.ready(converter.convert(goal), converter))
    info(s"eligibleGoal=true; capture.ready=true; goal: ${converter.convert(goal)}")
    assert(prove(c, converter.convert(goal)))

    val a = variable("i@8@01"); val b = variable("j@9@01")
    val liveSeq = SortWrapper(First(First(s)), srt)
    def liveDomain(v: Term): Term = SeqIn(SeqRanged(IntLiteral(0), SeqLength(liveSeq)), v)
    def liveAt(v: Term): Term = SeqAt(liveSeq, v)
    val live = Quantification(Forall, Seq(a, b), Implies(
      And(liveDomain(a), And(liveDomain(b), Not(BuiltinEquals(a, b)))),
      Not(BuiltinEquals(liveAt(a), liveAt(b)))), Seq(Trigger(Seq(liveAt(a), liveAt(b)))), "", false, None)
    val inv = recognized(live).get
    val shadowed = new InjectivityProofPortfolio.Capture
    shadowed.global(s)
    Seq("(declare-const i@8@01 Int)", "(declare-const j@9@01 Int)",
      "(declare-const |$injectivityIndex0| Int)", "(declare-const |$injectivityInverse0| Int)",
      s"(assert ${converter.convert(live)})").foreach(shadowed.record)
    shadowed.register(inv)
    assert(shadowed.ready(converter.convert(live), converter))
    val preparedField = shadowed.getClass.getDeclaredFields.find(_.getName.endsWith("prepared")).get
    preparedField.setAccessible(true)
    val replay = preparedField.get(shadowed).asInstanceOf[Option[(String, Long)]].get._1
    assert(replay.contains("(declare-const i@8@01 Int)\n(declare-const j@9@01 Int)"))
    val replayTokens = InjectivityProofPortfolio.tokens(replay)
    assert(replayTokens.contains("$injectivityIndex0$") && replayTokens.contains("$injectivityInverse0$"))
    assert(replayTokens.count(_ == "i@8@01") == 1 && replayTokens.count(_ == "j@9@01") == 1)
    val generated = inv.commands("sideInverse", "sideIndex", converter).mkString("\n")
    val ts = InjectivityProofPortfolio.tokens(generated)
    assert(!ts.contains("i@8@01") && !ts.contains("j@9@01"))
    assert(ts.count(_ == "sideIndex") == 5)
    assert(generated.contains("Seq_range") && generated.contains("$Snap.first") && generated.contains(":pattern"))
    Seq("(declare-const i@8@01 Bool)", "(declare-fun j@9@01 (Int) Int)",
      "(define-const i@8@01 Int 0)", "(declare-sort j@9@01 0)").foreach { declaration =>
      val invalid = new InjectivityProofPortfolio.Capture
      invalid.global(s); invalid.record(declaration)
      invalid.record(s"(assert ${converter.convert(live)})"); invalid.register(inv)
      assert(!invalid.ready(converter.convert(live), converter), declaration)
    }
  }

  test("candidate UNKNOWN, malformed output, errors plus UNSAT and wall censors never establish a proof") {
    Seq("printf 'silicon-proof-begin\\nunknown\\nsilicon-proof-end\\n'",
      "printf 'silicon-proof-begin\\nunsat\\nsilicon-proof-end\\n(error bad)\\n'",
      "echo bad >&2; printf 'silicon-proof-begin\\nunsat\\nsilicon-proof-end\\n'",
      "exec sleep 3").foreach { body =>
      val script = Files.createTempFile("silicon-portfolio-test-", ".sh")
      try {
        Files.write(script, ("#!/bin/sh\n" + body + "\n").getBytes("UTF-8")); script.toFile.setExecutable(true)
        val start = System.nanoTime()
        assert(!prove(captured(), converter.convert(ax()), script.toString))
        assert((System.nanoTime() - start) / 1e9 < 2.6)
      } finally Files.delete(script)
    }
  }

  test("timeout and cancellation terminate a non-exec wrapper's child and preserve interruption") {
    val dir = Files.createTempDirectory("silicon-cancel-test-")
    val pid = dir.resolve("pid")
    val script = dir.resolve("solver")
    Files.write(script, s"#!/bin/sh\nsleep 4 &\necho $$! > $pid\nwait\n".getBytes("UTF-8"))
    script.toFile.setExecutable(true)
    val c = captured(); val goal = converter.convert(ax()); assert(c.ready(goal, converter))
    def assertStopped(): Unit = {
      assert(Files.exists(pid))
      val child = new String(Files.readAllBytes(pid), "UTF-8").trim.toLong
      def running: Boolean = {
        val stat = java.nio.file.Paths.get(s"/proc/$child/stat")
        if (!Files.exists(stat)) false
        else {
          val text = new String(Files.readAllBytes(stat), "UTF-8")
          !Set('Z', 'X').contains(text.charAt(text.lastIndexOf(')') + 2))
        }
      }
      val deadline = System.nanoTime() + 200000000L
      while (running && System.nanoTime() < deadline) Thread.sleep(2)
      assert(!running)
    }
    @volatile var cancelled = false
    val worker = new Thread(() => {
      try c.prove(goal, script.toString)
      catch { case _: InterruptedException => cancelled = Thread.currentThread().isInterrupted }
      ()
    })
    try {
      val start = System.nanoTime()
      assert(!c.prove(goal, script.toString))
      assert((System.nanoTime() - start) / 1e9 < 2.6)
      assertStopped()
      Files.delete(pid)
      assert(c.ready(goal, converter))
      worker.start()
      val deadline = System.nanoTime() + 1000000000L
      while (!Files.exists(pid) && System.nanoTime() < deadline) Thread.sleep(5)
      assert(Files.exists(pid))
      worker.interrupt(); worker.join(1000)
      assert(!worker.isAlive && cancelled)
      assertStopped()
    } finally {
      worker.interrupt(); worker.join(2500)
      Files.deleteIfExists(script); Files.deleteIfExists(pid); Files.deleteIfExists(dir)
    }
  }

  /** Answers each command line as a print-success Z3 would; an empty answer ends the stream. */
  private class ScriptedProcess(answer: String => Seq[String], stubborn: Boolean) extends Process {
    val commands = mutable.ArrayBuffer.empty[String]
    val released = new java.util.concurrent.CountDownLatch(1)
    private val replies = mutable.Queue.empty[Byte]
    private val line = new ByteArrayOutputStream
    @volatile private var alive = true
    var destroyFailure: Throwable = null
    var closeFailure: Throwable = null
    var interruptOn: String = null
    var closeAttempts = 0
    private def open: Boolean = { val l = line.toString("UTF-8"); l.count(_ == '(') > l.count(_ == ')') }
    val getOutputStream: OutputStream = (b: Int) => if (b != '\n' || open) line.write(b) else {
      commands += line.toString("UTF-8"); line.reset()
      answer(commands.last).foreach(r => replies ++= (r + "\n").getBytes("UTF-8"))
    }
    val getInputStream: InputStream = new InputStream {
      def read(): Int = if (replies.isEmpty) -1 else {
        val b = replies.dequeue() & 0xff
        if (replies.isEmpty && commands.lastOption.contains(interruptOn)) Thread.currentThread().interrupt()
        b
      }
      override def available(): Int = replies.size
      override def close(): Unit = { closeAttempts += 1; if (closeFailure != null) throw closeFailure; if (stubborn) released.await() }
    }
    def getErrorStream: InputStream = new ByteArrayInputStream(Array.emptyByteArray)
    def waitFor(): Int = { while (alive) Thread.sleep(1); 0 }
    def exitValue(): Int = if (alive) throw new IllegalThreadStateException() else 0
    def destroy(): Unit = { if (destroyFailure != null) throw destroyFailure; if (!stubborn) alive = false }
  }
  private class Primary(answer: String => Seq[String], stubborn: Boolean) extends Z3ProverStdIO("test", converter,
      new DefaultIdentifierFactory("test"), NoopReporter) {
    val process = new ScriptedProcess(answer, stubborn)
    def commands: mutable.ArrayBuffer[String] = process.commands
    override protected def createProverInstance(args: Option[String]): Process = process
    override protected def getProverPath = Paths.get("/bin/false")
    var closerFailure: Throwable = null
    var failStart = false
    var suppressionFailure = false
    var closer: Thread = null
    var attachments = 0
    override protected def attachCleanupFailure(failure: Throwable, cleanup: Throwable): Unit = {
      attachments += 1
      if (suppressionFailure) throw new AssertionError("suppression")
      super.attachCleanupFailure(failure, cleanup)
    }
    override protected def disposalThread(action: Runnable): Thread = {
      if (closerFailure != null && !failStart) throw closerFailure
      closer = new Thread(action) { override def start(): Unit = {
        if (closerFailure != null) throw closerFailure else super.start()
      } }
      closer
    }
    def complete: Boolean = poisonDisposalComplete
    def awaitCloser(): Unit = if (closer != null) { closer.join(500); InjectivityProofPortfolioTests.this.assert(!closer.isAlive) }
    def timeoutState: Int = lastTimeout
  }
  private val modes = Seq(Seq("test.vpr"), Seq("--proverEnableTimeBounds", "test.vpr"))
  private def budget(timeBounds: Boolean, ms: Int) =
    if (timeBounds) s"(set-option :timeout $ms)" else s"(set-option :rlimit ${ms * 10000})"
  /** Runs `run` on a started primary holding one eligible assumption. `fault` overrides answers to chosen commands. */
  private def primary(on: Boolean, checks: Seq[String], fault: PartialFunction[String, Seq[String]] = PartialFunction.empty,
                      stubborn: Boolean = false)(run: Primary => Unit): Unit = {
    System.setProperty("silicon.injectivityProofPortfolio", on.toString)
    val pending = mutable.Queue.from(checks)
    val options = mutable.Map("timeout" -> "4294967295", "rlimit" -> "0")
    val setOption = """\(set-option :(timeout|rlimit) ([0-9]+)\)""".r
    val getOption = """\(get-option :(timeout|rlimit)\)""".r
    val p = new Primary(fault.orElse {
      case setOption(name, value) => options(name) = value; Seq("success")
      case getOption(name) => Seq(options(name))
      case "(check-sat)" => Seq(pending.dequeue())
      case "(get-info :reason-unknown)" => Seq("(:reason-unknown \"unknown\")")
      case _ => Seq("success")
    }, stubborn)
    p.start(None)
    try {
      p.emit("(set-option :print-success true)"); p.emit("(set-option :global-decls true)")
      p.declare(FunctionDecl(f)); p.assume(ax()); run(p)
    } finally { p.stop(); p.process.released.countDown() }
  }
  private def setup = Vector("(set-option :print-success true)", "(set-option :global-decls true)",
    converter.convert(FunctionDecl(f)), s"(assert ${converter.convert(ax())})")
  private def probe(timeBounds: Boolean, check: String) = Vector("(get-option :timeout)", "(get-option :rlimit)",
    "(push) ; 1", "(set-option :rlimit 0)", "(set-option :timeout 10)", s"(assert (not ${converter.convert(ax())}))",
    "(check-sat)") ++ (if (check == "sat" && Verifier.config.reportReasonUnknown()) Vector("(get-info :reason-unknown)") else Vector()) ++
    Vector("(pop) ; 1", "(set-option :timeout 4294967295)", "(set-option :rlimit 0)")
  private def original(timeBounds: Boolean, goal: String, diagnostics: Boolean) = Vector(budget(timeBounds, 0), "(push) ; 1",
    s"(assert (not $goal))", "(check-sat)") ++ (if (diagnostics && Verifier.config.reportReasonUnknown()) Vector("(get-info :reason-unknown)") else Vector()) :+ "(pop) ; 1"

  test("OFF and noneligible assertions issue exactly the original primary commands") {
    for (args <- modes; timeBounds = args.size == 2; on <- Seq(false, true)) {
      Verifier.config = new Config(args)
      primary(on, Seq("sat")) { p =>
        assert(!p.assert(True, Some(0)))
        assert(p.commands == setup ++ original(timeBounds, "true", diagnostics = true))
      }
      if (!on) primary(on, Seq("sat")) { p =>
        assert(!p.assert(ax(), Some(0)))
        assert(p.commands == setup ++ original(timeBounds, converter.convert(ax()), diagnostics = true))
      }
    }
    primary(true, Seq("unsat")) { p =>
      assert(p.assert(ax(), Some(50)))
      assert(!p.commands.contains("(set-option :timeout 10)"))
      assert(p.commands.contains(budget(timeBounds = true, 50)))
    }
    primary(true, Seq("unsat")) { p =>
      p.emit("(set-option   :timeout 777)")
      assert(p.assert(ax(), Some(0)))
      assert(!p.commands.contains("(set-option :timeout 10)"))
    }
    primary(true, Seq("unsat")) { p =>
      p.emit("(set-option :produce-proofs true)")
      assert(p.assert(ax(), Some(0)))
      assert(!p.commands.contains("(set-option :timeout 10)"))
    }
  }

  test("probes in both timeout modes restore physical budgets, cache and depth; UNKNOWN alone falls back") {
    for (args <- modes; timeBounds = args.size == 2) {
      Verifier.config = new Config(args)
      primary(true, Seq("sat")) { p =>
        assert(!p.assert(ax(), Some(0)))
        assert(p.commands == setup ++ probe(timeBounds, "sat"))
        assert(p.timeoutState == -1 && p.pushPopScopeDepth == 0)
      }
      primary(true, Seq("unsat")) { p =>
        assert(p.assert(ax(), Some(0)))
        assert(p.commands == setup ++ probe(timeBounds, "unsat"))
        assert(p.timeoutState == -1)
      }
      primary(true, Seq("unknown", "unsat")) { p =>
        p.emit("(define-sort $Perm () Real)")
        p.emit("(define-const $Perm.Write $Perm 1.0)")
        p.emit("(define-const $Perm.No $Perm 0.0)")
        assert(p.assert(ax(), Some(0)))
        assert(p.commands.drop(7) == probe(timeBounds, "unknown") ++ original(timeBounds, converter.convert(ax()), diagnostics = false))
        assert(p.timeoutState == 0)
      }
      // A finite installed budget and a physical value beyond Int range are restored verbatim.
      primary(true, Seq("sat", "unsat"), {
        case "(get-option :timeout)" if timeBounds => Seq("50")
        case "(get-option :rlimit)" if !timeBounds => Seq("4294967295")
      }) { p =>
        assert(!p.assert(True, Some(50)))
        p.commands.clear()
        assert(p.assert(ax(), Some(0)))
        assert(p.commands.takeRight(2) == (if (timeBounds) Seq("(set-option :timeout 50)", "(set-option :rlimit 0)")
          else Seq("(set-option :timeout 4294967295)", "(set-option :rlimit 4294967295)")))
        assert(p.timeoutState == 50)
      }
    }
  }

  test("uncertain probe exchanges poison the primary: original failure, no further I/O, bounded disposal") {
    val goal = converter.convert(ax())
    val steps = Seq("(get-option :timeout)", "(get-option :rlimit)", "(push) ; 1", "(set-option :rlimit 0)",
      "(set-option :timeout 10)", s"(assert (not $goal))", "(check-sat)", "(pop) ; 1",
      "(set-option :timeout 4294967295)", "(set-option :rlimit 0)").zipWithIndex.map {
      case (step, i) => step -> (if (i == 9) 2 else 1) // Restoring rlimit repeats the probe's own write.
    }
    val faults = Seq[(String, String => Seq[String])](
      "eof" -> (_ => Seq()),
      "malformed" -> (step => Seq(if (step.startsWith("(get-option")) "unsupported" else "(error \"line 1\")")),
      "overflow" -> (_ => Seq("4294967296")),
      "interrupt" -> (step => { Thread.currentThread().interrupt(); if (step == "(check-sat)") Seq("unknown") else Seq("success") }))
    for ((step, occurrence) <- steps; (kind, fault) <- faults if kind != "overflow" || step.startsWith("(get-option")) {
      var seen = 0
      primary(true, Seq("unknown"), { case `step` if { seen += 1; seen == occurrence } => fault(step) }) { p =>
        val failure = intercept[Exception](p.assert(ax(), Some(0)))
        val interrupted = Thread.interrupted()
        assert(interrupted == (kind == "interrupt"), s"$kind $step")
        if (kind == "interrupt") assert(failure.isInstanceOf[InterruptedException], step)
        else assert(failure.isInstanceOf[viper.silicon.reporting.ProverInteractionFailed], s"$kind $step")
        if (kind == "eof") assert(failure.getMessage.contains("yielded null"), step)
        assert(p.commands.last == step && !p.process.isAlive, s"$kind $step")
        val issued = p.commands.size
        intercept[viper.silicon.reporting.ProverInteractionFailed](p.assert(True, Some(0)))
        intercept[viper.silicon.reporting.ProverInteractionFailed](p.reset())
        assert(p.commands.size == issued)
      }
    }
    primary(true, Seq(), { case "(check-sat)" => Seq() }, stubborn = true) { p =>
      val start = System.nanoTime()
      intercept[viper.silicon.reporting.ProverInteractionFailed](p.assert(ax(), Some(0)))
      assert(!p.complete && (System.nanoTime() - start) / 1e9 < 1.5)
      for (_ <- 1 to 2) {
        val retry = System.nanoTime(); p.stop()
        assert(!p.complete && (System.nanoTime() - retry) / 1e9 < 1.5)
      }
      assert(p.process.isAlive)
      intercept[viper.silicon.reporting.ProverInteractionFailed](p.start(None))
    }
  }

  test("real SAT reason-info framing is validated before restoration, preserving ordinary diagnostics") {
    for (args <- modes) {
      Verifier.config = new Config(args.dropRight(1) ++ Seq("--reportReasonUnknown", "test.vpr"))
      for (reason <- Seq("", "unknown", "a \"\"quoted\"\" reason")) primary(true, Seq("sat"), {
        case "(get-info :reason-unknown)" => Seq(s"(:reason-unknown \"$reason\")")
      }) { p =>
        assert(!p.assert(ax(), Some(0)))
        assert(p.lastReasonUnknown == reason && p.commands == setup ++ probe(args.size == 2, "sat"))
      }
      for (response <- Seq(Seq(), Seq("(error \"reason\")"), Seq("(:reason-unknown \"unterminated)"), Seq("garbage"),
        Seq("(:reason-unknown \"interrupted\")"))) {
        val interrupt = response.lastOption.exists(_.contains("interrupted"))
        primary(true, Seq("sat"), { case "(get-info :reason-unknown)" => response }) { p =>
          if (interrupt) p.process.interruptOn = "(get-info :reason-unknown)"
          val failure = intercept[Exception](p.assert(ax(), Some(0)))
          assert(Thread.interrupted() == interrupt)
          assert(failure.isInstanceOf[InterruptedException] == interrupt)
          assert(p.commands.last == "(get-info :reason-unknown)" && !p.process.isAlive)
          intercept[viper.silicon.reporting.ProverInteractionFailed](p.assert(True, Some(0)))
        }
      }
      primary(false, Seq("sat"), { case "(get-info :reason-unknown)" => Seq("ordinary opaque reason") }) { p =>
        assert(!p.assert(ax(), Some(0)) && p.lastReasonUnknown == "ordinary opaque reason")
        assert(p.commands == setup ++ original(args.size == 2, converter.convert(ax()), diagnostics = true))
      }
    }
  }

  test("poison preserves exact Throwable through cleanup Errors, failed closer launch and unsafe suppression") {
    for (kind <- Seq("destroy", "construct", "start", "close", "suppression", "self", "disabled", "interrupt")) {
      val original = if (kind == "disabled") new Throwable("exchange", null, false, true) {}
        else if (kind == "interrupt") new InterruptedException("exchange") else new AssertionError("exchange")
      val cleanup = new AssertionError("cleanup")
      primary(true, Seq(), { case "(check-sat)" => Thread.currentThread().interrupt(); throw original }) { p =>
        if (Set("destroy", "suppression", "self", "disabled", "interrupt")(kind)) p.process.destroyFailure = if (kind == "self") original else cleanup
        if (kind == "construct" || kind == "start") p.closerFailure = cleanup
        p.failStart = kind == "start"; p.suppressionFailure = kind == "suppression"
        if (kind == "close") p.process.closeFailure = cleanup
        val start = System.nanoTime()
        val caught = intercept[Throwable](p.assert(ax(), Some(0)))
        assert(caught eq original)
        assert(Thread.interrupted() && (System.nanoTime() - start) / 1e9 < 1.5)
        p.awaitCloser()
        assert(!p.complete)
        if (!Set("self", "suppression", "disabled")(kind)) assert(original.getSuppressed.toSeq.nonEmpty && original.getSuppressed.forall(_ eq cleanup))
        else assert(original.getSuppressed.isEmpty)
        assert(p.attachments == (if (kind == "self") 0 else if (kind == "close") 2 else 1))
        val issued = p.commands.size
        intercept[viper.silicon.reporting.ProverInteractionFailed](p.reset())
        assert(p.commands.size == issued)
        val closeAttempts = p.process.closeAttempts
        p.process.destroyFailure = null; p.process.closeFailure = null; p.closerFailure = null
        p.stop(); p.awaitCloser()
        assert(p.complete && !p.process.isAlive)
        if (kind == "close") assert(p.process.closeAttempts > closeAttempts)
        intercept[viper.silicon.reporting.ProverInteractionFailed](p.start(None))
      }
    }
  }

  test("reset clears eligible assumptions and side capture without changing primary declarations") {
    primary(true, Seq("sat")) { p =>
      p.reset()
      assert(!p.assert(ax(), Some(0)))
      assert(!p.commands.contains("(set-option :timeout 10)"))
    }
  }
}
