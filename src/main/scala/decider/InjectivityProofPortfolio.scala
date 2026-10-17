package viper.silicon.decider

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.util.control.NonFatal
import viper.silicon.state.Identifier
import viper.silicon.state.terms._

/** Proof-only extensions: each original model expands with an inverse on the guarded image. */
private[decider] object InjectivityProofPortfolio {
  case class Inverse(index: Var, domain: Term, image: Term, boundVars: Seq[Var], parameters: Set[Var], resolutionNames: Set[String]) {
    def commands(name: String, binder: String, converter: TermToSMTLib2Converter): Seq[String] = {
      val fresh = Var(Identifier(binder), index.sort, false)
      def rename(t: Term): Term = t.replace[Var](viper.silicon.toMap(Seq(index -> (fresh: Term))))
      val renamedImage = rename(image)
      val inverse = Fun(Identifier(name), Seq(image.sort), index.sort)
      val axiom = Quantification(Forall, Seq(fresh),
        Implies(rename(domain), BuiltinEquals(App(inverse, renamedImage), fresh)),
        Seq(Trigger(Seq(renamedImage))), "", false, None)
      Seq(converter.convert(FunctionDecl(inverse)), s"(assert ${converter.convert(axiom)})")
    }
  }

  private def conjuncts(t: Term): Seq[Term] = t match {
    case And(ts) => ts.flatMap(conjuncts)
    case True => Seq.empty
    case _ => Seq(t)
  }

  def recognize(term: Term, converter: TermToSMTLib2Converter): Option[Inverse] =
    try recognizeSupported(term, converter) catch { case NonFatal(_) => None }

  private def recognizeSupported(term: Term, converter: TermToSMTLib2Converter): Option[Inverse] = term match {
    case q: Quantification if q.q == Forall && q.vars.size == 2 =>
      val Seq(x, y) = q.vars
      if (x == y || x.sort != y.sort) return None
      val names = q.vars.map(v => converter.render(v.id))
      def plain(s: String): Boolean = s.matches("[a-zA-Z_$][a-zA-Z0-9_$@.]*")
      if (names.distinct.size != 2 || !q.vars.forall(v => plain(v.id.name) && converter.render(v.id) == v.id.name)) return None
      val symbols = mutable.Map.empty[String, Applicable]
      var safe = true
      (q.body +: q.triggers.flatMap(_.p)).foreach(_.visit {
        case _: Quantification | _: Let | _: HasVarRepr => safe = false
        case v: Var =>
          val name = converter.render(v.id)
          if (!plain(v.id.name) || name != v.id.name || (v != x && v != y && names.contains(name))) safe = false
          if (symbols.get(name).exists(_ != v)) safe = false
          symbols(name) = v
        case a: App =>
          val f = a.applicable
          val name = converter.render(f.id)
          if (!plain(f.id.name) || name != f.id.name || names.contains(name) || f.isInstanceOf[SMTFun]) safe = false
          if (symbols.get(name).exists(_ != f)) safe = false
          symbols(name) = f
        case _: AppHint => safe = false
      })
      // Sort names and term names share the emitted namespace in binder-sensitive syntax.
      val sorts = (q.body +: q.triggers.flatMap(_.p)).flatMap(_.deepCollect { case t => t.sort }) ++ q.vars.map(_.sort)
      if (sorts.exists(s => tokens(converter.convert(s)).exists(names.contains))) safe = false
      if (!safe) return None
      def rename(t: Term): Term = t.replace[Var](viper.silicon.toMap(Seq(y -> (x: Term))))
      def independent(t: Term, v: Var): Boolean = !t.hasSubterm(v)
      def finish(guards: Seq[Term], a: Term, b: Term): Option[Inverse] = {
        if (a.sort != b.sort || independent(a, x) || !independent(a, y) || !independent(b, x) || a != rename(b)) return None
        val left = guards.filter(_.hasSubterm(x))
        val right = guards.filter(_.hasSubterm(y))
        if (guards.size != left.size + right.size || left.exists(_.hasSubterm(y)) || right.exists(_.hasSubterm(x))) return None
        if (left != right.map(rename)) return None
        val rendered = tokens(converter.convert(q.body))
        val heads = rendered.sliding(2).collect { case Vector("(", h) => semantic(h) }.toSet - "as"
        Some(Inverse(x, And(left), a, q.vars, q.freeVariables.toSet, heads ++ symbols.keySet.diff(names.toSet)))
      }
      q.body match {
        case Implies(guard, Not(BuiltinEquals(a, b))) =>
          val gs = conjuncts(guard)
          val neq = Not(BuiltinEquals(x, y))
          if (gs.count(_ == neq) != 1) None else finish(gs.filterNot(_ == neq), a, b)
        case Implies(guard, BuiltinEquals(a, b)) if a == x && b == y =>
          val gs = conjuncts(guard)
          gs.collectFirst { case e @ BuiltinEquals(l, r) if l.sort == r.sort && l.hasSubterm(x) && r.hasSubterm(y) => (e, l, r) }
            .flatMap { case (e, l, r) => finish(gs.filterNot(_ == e), l, r) }
        case _ => None
      }
    case _ => None
  }

  def eligibleGoal(t: Term): Boolean = t match {
    case q: Quantification if q.q == Forall && q.vars.size == 2 && q.vars.head.sort == q.vars.last.sort =>
      q.body match {
        case Implies(_, BuiltinEquals(a, b)) => a == q.vars.head && b == q.vars.last
        case Implies(_, Not(_: BuiltinEquals)) => true
        case _ => false
      }
    case _ => false
  }

  // This lexer handles replay syntax only; proof recognition operates exclusively on typed Terms.
  def tokens(s: String, deadline: Long = Long.MaxValue): Vector[String] = {
    val out = Vector.newBuilder[String]
    var i = 0
    while (i < s.length) {
      if (Thread.currentThread().isInterrupted) throw new InterruptedException()
      require(System.nanoTime() < deadline)
      val start = i
      s(i) match {
        case c if c.isWhitespace => i += 1
        case ';' => while (i < s.length && s(i) != '\n') i += 1
        case '(' | ')' => out += s(i).toString; i += 1
        case '|' =>
          i += 1
          while (i < s.length && s(i) != '|') { require(s(i) != '\\'); i += 1 }
          require(i < s.length); i += 1; out += s.substring(start, i)
        case '"' =>
          i += 1
          var done = false
          while (i < s.length && !done) {
            if (s(i) == '"') {
              i += 1
              if (i < s.length && s(i) == '"') i += 1 else done = true
            } else i += 1
          }
          require(done); out += s.substring(start, i)
        case _ =>
          while (i < s.length && !s(i).isWhitespace && !"();|\"".contains(s(i))) i += 1
          require(i > start); out += s.substring(start, i)
      }
    }
    out.result()
  }

  private def semantic(t: String): String = if (t.startsWith("|")) t.drop(1).dropRight(1) else t
  private val options = Set(":print-success", ":global-decls", ":auto_config", ":smt.case_split",
    ":smt.delay_units", ":type_check", ":smt.mbqi", ":pp.bv_literals", ":smt.qi.eager_threshold",
    ":smt.arith.solver", ":model.v2", ":model.partial", ":smt.qi.max_multi_patterns", ":timeout",
    ":rlimit", ":smt.random_seed", ":sat.random_seed", ":smt.arith.nl")

  /** Recording appends one reference per command; scanning is deferred until a side attempt. */
  final class Capture(val limit: Int = 8 * 1024 * 1024) {
    private val history = mutable.ArrayBuffer.empty[String]
    private val external = mutable.Set.empty[Int]
    def markExternal(): Unit = if (valid) external += history.size - 1
    private var scopes = List(List.empty[Int])
    private val active = mutable.Map.empty[Int, Inverse]
    private val globals = mutable.Set.empty[Var]
    def global(v: Var): Unit = if (valid) globals += v
    private var size = 0L
    private var valid = true
    private var prepared: Option[(String, Long)] = None
    def enabled: Boolean = valid
    def eligible: Boolean = valid && active.nonEmpty
    def clear(): Unit = { history.clear(); external.clear(); active.clear(); globals.clear(); prepared = None; scopes = List(Nil); size = 0; valid = false }
    def record(s: String): Unit = if (valid) {
      prepared = None
      if (!s.startsWith("(get-") && !s.startsWith("(check-sat")) {
        size += s.length.toLong * 2 + 64
        if (size > limit) clear() else history += s
      }
    }
    def register(inv: Inverse): Unit = if (valid && inv.parameters.forall(globals.contains)) {
      val pos = history.size - 1
      active(pos) = inv
      scopes = (pos :: scopes.head) :: scopes.tail
    }
    def push(n: Int): Unit = if (valid) { (0 until n).foreach(_ => scopes = Nil :: scopes) }
    def pop(n: Int): Unit = if (valid) {
      (0 until n).foreach { _ => scopes.head.foreach(active.remove); scopes = scopes.tail }
    }

    def ready(goal: String, converter: TermToSMTLib2Converter): Boolean = {
      prepared = None
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      try {
        val lexed = history.map(tokens(_, deadline))
        val captured = mutable.Set.empty[String] ++ lexed.iterator.flatten.map(semantic) ++ tokens(goal, deadline).map(semantic)
        val declarations = mutable.ArrayBuffer.empty[Vector[String]]
        val functions = mutable.ArrayBuffer.empty[(String, Vector[String])]
        val opaque = mutable.Set.empty[String]
        val selectors = mutable.Map.empty[String, (String, String)]
        val datatypes = mutable.ArrayBuffer.empty[String]
        val aliases = mutable.Map.empty[String, Vector[String]]
        var depth = 0
        lexed.zipWithIndex.foreach { case (ts, index) =>
          require(System.nanoTime() < deadline)
          require(ts.size >= 3 && ts.head == "(" && ts.last == ")")
          var balance = 0
          ts.zipWithIndex.foreach { case (t, i) =>
            if (t == "(") balance += 1 else if (t == ")") balance -= 1
            require(balance > 0 || i == ts.size - 1)
          }
          require(balance == 0)
          ts(1) match {
            case "set-option" =>
              require(ts.size == 5 && options(ts(2)))
              require(!external(index) || (ts(2) != ":timeout" && ts(2) != ":rlimit"))
              if (Set(":print-success", ":global-decls")(ts(2))) require(ts(3) == "true")
            case "push" => require(!external(index)); depth += 1
            case "pop" => require(!external(index)); depth -= 1; require(depth >= 0)
            case "assert" => ()
            case "declare-fun" | "declare-const" | "declare-sort" | "define-fun" | "define-const" | "define-sort" | "declare-datatypes" =>
              declarations += ts
              val name = semantic(ts(2))
              ts(1) match {
                case "declare-fun" =>
                  require(ts(3) == "(")
                  var end = 4
                  var nesting = 1
                  while (nesting > 0) {
                    if (ts(end) == "(") nesting += 1 else if (ts(end) == ")") nesting -= 1
                    end += 1
                  }
                  functions += name -> ts.slice(4, end - 1).map(semantic)
                case "declare-const" => functions += name -> Vector.empty
                case "define-sort" =>
                  require(ts(3) == "(" && ts(4) == ")")
                  aliases(name) = ts.slice(5, ts.size - 1).map(semantic)
                case "define-fun" | "define-const" => opaque += name
                case "declare-datatypes" =>
                  // Resolve only single monomorphic datatypes with atomic field sorts.
                  def atom(t: String): Boolean = t.matches("[a-zA-Z_$][a-zA-Z0-9_$@.<>]*")
                  require(ts.take(4) == Vector("(", "declare-datatypes", "(", "(") && atom(ts(4)))
                  require(ts.slice(5, 10) == Vector("0", ")", ")", "(", "(") && ts.takeRight(3) == Vector(")", ")", ")"))
                  val datatype = ts(4)
                  datatypes += datatype
                  var at = 10
                  var constructors = 0
                  while (at < ts.size - 3) {
                    require(System.nanoTime() < deadline)
                    require(ts(at) == "(" && atom(ts(at + 1)) && !opaque(ts(at + 1)))
                    opaque += ts(at + 1); at += 2; constructors += 1
                    while (ts(at) == "(") {
                      require(System.nanoTime() < deadline)
                      require(atom(ts(at + 1)) && atom(ts(at + 2)) && ts(at + 3) == ")")
                      val selector = ts(at + 1)
                      require(!selectors.contains(selector))
                      selectors(selector) = datatype -> ts(at + 2)
                      at += 4
                    }
                    require(ts(at) == ")"); at += 1
                  }
                  require(constructors > 0 && at == ts.size - 3)
                case _ => ()
              }
            case _ => throw new IllegalArgumentException("Unsupported replay command")
          }
        }
        // Only a plain, same-sort nullary declaration may be shadowed by a recognized Var.
        active.values.flatMap(_.boundVars).foreach { v =>
          val name = converter.render(v.id)
          val sort = tokens(converter.convert(v.sort), deadline)
          declarations.filter(_.map(semantic).contains(name)).foreach { ts =>
            require(ts == Vector("(", "declare-const", name) ++ sort ++ Vector(")") ||
              ts == Vector("(", "declare-fun", name, "(", ")") ++ sort ++ Vector(")"))
          }
        }
        def canonical(ts: Vector[String], seen: Set[String] = Set.empty): Vector[String] = ts.flatMap { t =>
          if (aliases.contains(t)) { require(seen.size < 32 && !seen(t)); canonical(aliases(t), seen + t) } else Vector(t)
        }
        val used = active.values.flatMap(_.resolutionNames).toSet
        val primitives = Set("=", "not", "and", "or", "=>", "ite", "+", "-", "*", "div", "mod", "<", "<=", ">", ">=")
        functions.groupBy(_._1).foreach { case (name, declarations) =>
          if (used(name)) {
            require(!primitives(name) && !opaque(name))
            val args = declarations.map(d => canonical(d._2))
            if (args.size > 1) {
              // Distinct nonnumeric first arguments resolve the supported polymorphic encodings.
              val first = args.map(_.headOption.getOrElse(""))
              require(first.distinct.size == args.size && first.forall(n => n.nonEmpty && n != "(" && n != "Int" && n != "Real"))
            }
          }
        }
        require(used.intersect(opaque.toSet).isEmpty)
        val sortNames = Seq("Int", "Real", "Bool") ++ datatypes ++ declarations.collect {
          case ts if ts(1) == "define-sort" || ts(1) == "declare-sort" => semantic(ts(2))
        }
        def resolved(sort: Vector[String]): Vector[String] = {
          val result = canonical(sort)
          require(result.size == 1 && sortNames.count(_ == result.head) == 1)
          require(aliases.keys.forall(n => sortNames.count(_ == n) == 1))
          require(!declarations.exists(ts => ts(1) == "declare-sort" && semantic(ts(2)) == result.head && ts(3) != "0"))
          result
        }
        val expected = active.values.toSeq.flatMap(inv => Seq(inv.domain, inv.image).flatMap(_.deepCollect {
          case t @ First(a) => ("$Snap.first", Seq(a.sort), t.sort)
          case t @ Second(a) => ("$Snap.second", Seq(a.sort), t.sort)
          case a: App => (converter.render(a.applicable.id), a.applicable.argSorts, a.sort)
        }))
        selectors.foreach { case (name, (arg, result)) =>
          require(System.nanoTime() < deadline)
          val signature = resolved(Vector(arg)) -> resolved(Vector(result))
          if (used(name)) {
            require(!primitives(name) && !opaque(name) && !functions.exists(_._1 == name) && !sortNames.contains(name))
            val occurrences = expected.filter(_._1 == name)
            require(occurrences.nonEmpty && occurrences.forall { case (_, args, res) =>
              args.size == 1 && (resolved(tokens(converter.convert(args.head), deadline)) -> resolved(tokens(converter.convert(res), deadline))) == signature
            })
          }
        }
        active.values.foreach { inv =>
          captured ++= (Seq(inv.domain, inv.image) ++ inv.boundVars).flatMap(t => tokens(converter.convert(t), deadline)).map(semantic)
        }
        def fresh(base: String): String = {
          var name = base
          while (captured(name)) name += "$"
          captured += name
          name
        }
        val replacements = active.toSeq.sortBy(_._1).zipWithIndex.map { case ((pos, inv), i) =>
          require(System.nanoTime() < deadline)
          val name = fresh(s"$$injectivityInverse$i")
          val binder = fresh(s"$$injectivityIndex$i")
          val commands = inv.commands(name, binder, converter)
          captured ++= commands.flatMap(tokens(_, deadline)).map(semantic)
          pos -> commands.mkString("\n")
        }.toMap
        val replay = history.indices.map(i => replacements.getOrElse(i, history(i))).mkString("\n")
        prepared = Some((replay, deadline))
        System.nanoTime() < deadline
      } catch { case NonFatal(_) => false }
    }

    def prove(goal: String, executable: String): Boolean = try {
      prepared.exists { case (replay, deadline) =>
        Seq(0, 3, 7, 19).exists { offset =>
          if (Thread.currentThread().isInterrupted) throw new InterruptedException()
          System.nanoTime() < deadline && {
            val sideReplay = rename(replay, offset, deadline)
            val sideGoal = rename(goal, offset, deadline)
            val remaining = deadline - System.nanoTime()
            remaining > 0 && run(executable, sideReplay, sideGoal, remaining)
          }
        }
      }
    } catch {
      case e: InterruptedException => Thread.currentThread().interrupt(); throw e
      case NonFatal(_) => false
    }
  }

  private val suffix = "@([0-9]{2})(?=@|$)".r
  private[decider] def rename(s: String, offset: Int, deadline: Long = Long.MaxValue): String = tokens(s, deadline).map { token =>
    if (token.startsWith("\"")) token
    else {
      val quoted = token.startsWith("|")
      val name = semantic(token)
      val mapped = suffix.replaceAllIn(name, m => {
        val n = m.group(1).toInt
        if (n == 0) m.matched else f"@${(n - 1 + offset) % 99 + 1}%02d"
      })
      if (quoted) s"|$mapped|" else mapped
    }
  }.mkString(" ")

  private def run(executable: String, replay: String, goal: String, remaining: Long): Boolean = {
    // Linux setsid establishes ownership before the wrapper can execute. Wrappers
    // must retain this session/group; deliberate daemonization is not supported.
    if (!System.getProperty("os.name").equals("Linux") ||
        !Files.isExecutable(java.nio.file.Paths.get("/usr/bin/setsid")) ||
        !Files.isExecutable(java.nio.file.Paths.get("/bin/kill"))) return false
    val dir = Files.createTempDirectory("silicon-injectivity-")
    val in = dir.resolve("input.smt2")
    val out = dir.resolve("stdout")
    val err = dir.resolve("stderr")
    val ready = dir.resolve("ready")
    val status = dir.resolve("status")
    var process: Process = null
    var owned = false
    val deadline = System.nanoTime() + remaining
    try {
      val script = replay + s"\n(set-option :rlimit 0)\n(set-option :timeout 400)\n" +
        s"(assert (not $goal))\n" +
        "(echo \"silicon-proof-begin\")\n(check-sat)\n(echo \"silicon-proof-end\")\n(exit)\n"
      Files.write(in, script.getBytes(UTF_8))
      // The supervisor holds the group ID until cleanup, including after solver
      // exit. The input handshake prevents execution under an unverified group.
      val supervisor = "trap ':' TERM; echo $$ > \"$1\"; read start || exit; " +
        "status=$2; shift 2; \"$@\" & wait $!; echo $? > \"$status\"; " +
        "read finish"
      process = new ProcessBuilder("/usr/bin/setsid", "/bin/sh", "-c", supervisor,
        "silicon-side", ready.toString, status.toString, executable, "-smt2", in.toString)
        .redirectOutput(out.toFile).redirectError(err.toFile).start()
      def available(path: java.nio.file.Path): Boolean = Files.exists(path) && Files.size(path) > 0
      while (!available(ready) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(2)
      if (!available(ready) || !process.isAlive) return false
      val pid = process.pid()
      val stat = new String(Files.readAllBytes(java.nio.file.Paths.get(s"/proc/$pid/stat")), UTF_8)
      val fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ")
      if (new String(Files.readAllBytes(ready), UTF_8).trim != pid.toString ||
          fields(2) != pid.toString || fields(3) != pid.toString) return false
      owned = true
      process.getOutputStream.write("start\n".getBytes(UTF_8)); process.getOutputStream.flush()
      while (!available(status) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(2)
      if (!available(status) || new String(Files.readAllBytes(status), UTF_8).trim != "0" ||
          Files.size(err) != 0 || Files.size(out) > 1024 * 1024) return false
      val lines = new String(Files.readAllBytes(out), UTF_8).linesIterator.map(_.trim).filterNot(_ == "success").filter(_.nonEmpty).toVector
      lines == Vector("silicon-proof-begin", "unsat", "silicon-proof-end")
    } finally {
      var interrupted = Thread.interrupted()
      val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
      def await(p: Process): Boolean = {
        val until = math.min(cleanupDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250))
        while (p.isAlive && System.nanoTime() < until) {
          try p.waitFor(math.max(1L, until - System.nanoTime()), TimeUnit.NANOSECONDS)
          catch { case _: InterruptedException => interrupted = true }
        }
        !p.isAlive
      }
      try {
        if (process != null) {
          try {
            if (owned) {
              val killer = new ProcessBuilder("/bin/kill", "-KILL", "--", s"-${process.pid()}").start()
              try {
                if (!await(killer) || killer.exitValue() != 0)
                  throw new java.io.IOException("Side process group cleanup failed")
              } finally {
                if (killer.isAlive) killer.destroyForcibly()
                if (!await(killer)) throw new java.io.IOException("Side cleanup helper not reaped")
              }
            }
          } finally {
            if (process.isAlive) process.destroyForcibly()
            if (!await(process)) throw new java.io.IOException("Side supervisor not reaped")
          }
        }
      } finally {
        try {
          Seq(in, out, err, ready, status).foreach(Files.deleteIfExists(_))
          Files.deleteIfExists(dir)
        } finally {
          if (interrupted) { Thread.currentThread().interrupt(); throw new InterruptedException() }
        }
      }
    }
  }
}
