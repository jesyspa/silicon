// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.verifier

import java.util.concurrent._
import org.apache.commons.pool2.{BasePooledObjectFactory, ObjectPool, PoolUtils, PooledObject}
import org.apache.commons.pool2.impl.{DefaultPooledObject, GenericObjectPool, GenericObjectPoolConfig}
import viper.silicon.Config
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.interfaces.VerificationResult
import viper.silver.components.StatefulComponent
import viper.silicon.interfaces.decider.ProverLike
import viper.silicon.state.terms.{Decl, Term}

/** Experimental startup switches, all off by default:
  *   -Dsilicon.exp.capWorkers=true         start the pool when the program is known, with at most one worker per
  *                                         method and CFG (all workers if branches are parallelised)
  *   -Dsilicon.exp.asyncBroadcast=true     send the preamble to each worker from that worker's own thread, so that
  *                                         the workers' provers process it concurrently
  *   -Dsilicon.exp.parallelLifecycle=true  start and stop the workers' provers concurrently
  */
object VerificationPoolManager {
  val capWorkers: Boolean = java.lang.Boolean.getBoolean("silicon.exp.capWorkers")
  val asyncBroadcast: Boolean = java.lang.Boolean.getBoolean("silicon.exp.asyncBroadcast")
  val parallelLifecycle: Boolean = java.lang.Boolean.getBoolean("silicon.exp.parallelLifecycle")
}

class VerificationPoolManager(mainVerifier: MainVerifier) extends StatefulComponent {
  import VerificationPoolManager._

  private val configuredWorkers: Int = Verifier.config.numberOfParallelVerifiers()
  private var numberOfWorkers: Int = configuredWorkers

  /*private*/ var workerVerifiers: Seq[WorkerVerifier] = _
  /*private*/ var threadPool: ForkJoinPool = _
  /*private*/ var workerVerifierPool: ObjectPool[WorkerVerifier] = _
  /* One single-threaded executor per worker under asyncBroadcast, and the last command queued on it. */
  private var workerExecutors: Seq[ExecutorService] = Seq.empty
  @volatile private var pendingCommands: Seq[Future[_]] = Seq.empty

  /** Applies `command` to each worker's prover, in order per worker; under asyncBroadcast, without waiting for it. */
  private def broadcast(command: ProverLike => Unit): Unit = {
    if (workerExecutors.isEmpty) workerVerifiers foreach (worker => command(worker.decider.prover))
    else pendingCommands = workerVerifiers.zip(workerExecutors).map { case (worker, executor) =>
      executor.submit((() => command(worker.decider.prover)): Runnable)
    }
  }

  /** Waits for the commands that [[broadcast]] queued, and rethrows the first failure. */
  def awaitBroadcast(): Unit = {
    val pending = pendingCommands
    pendingCommands = Seq.empty
    pending foreach (future =>
      try future.get()
      catch { case e: ExecutionException => throw e.getCause })
  }

  private[verifier] object pooledVerifiers extends ProverLike {
    def emit(content: String): Unit = broadcast(_.emit(content))
    override def emit(contents: Iterable[String]): Unit = broadcast(_.emit(contents))
    def assume(term: Term): Unit = broadcast(_.assume(term))
    override def assumeAxioms(terms: InsertionOrderedSet[Term], description: String): Unit = broadcast(_.assumeAxioms(terms, description))
    def declare(decl: Decl): Unit = broadcast(_.declare(decl))
    def comment(content: => String): Unit = {
      // Evaluated here, so that queued comments do not read state that the caller changes later.
      if (Verifier.config.outputProverLog) {
        val c = content
        broadcast(_.comment(c))
      }
    }

    def saturate(data: Option[Config.ProverStateSaturationTimeout]): Unit =
      broadcast(_.saturate(data))

    def saturate(timeout: Int, comment: String): Unit =
      broadcast(_.saturate(timeout, comment))

    override def emitSettings(contents: Iterable[String]): Unit =
      broadcast(_.emitSettings(contents))

    override def setOption(name: String, value: String): String = {
      awaitBroadcast()
      (workerVerifiers map (_.decider.prover.setOption(name, value))).head
    }
  }

  /** Runs `actions` concurrently under parallelLifecycle, otherwise in order, and rethrows the first failure. */
  private def runAll(actions: Seq[() => Unit]): Unit = {
    if (!parallelLifecycle || actions.length < 2) Cleanup.run(actions: _*)
    else {
      val executor = Executors.newFixedThreadPool(actions.length)
      try {
        val futures = actions.map(action => executor.submit((() => action()): Runnable))
        Cleanup.run(futures.map(future => () => {
          try future.get()
          catch { case e: ExecutionException => throw e.getCause }
          ()
        }): _*)
      } finally executor.shutdown()
    }
  }

  /* Verifier pool management */

  private def setupWorkerPool(): Unit = {
    workerVerifiers = Vector.empty

    try {
      val poolConfig: GenericObjectPoolConfig[WorkerVerifier] = new GenericObjectPoolConfig()
      poolConfig.setMaxTotal(numberOfWorkers)
      poolConfig.setMaxIdle(numberOfWorkers) /* Prevent pool from shutting down idle prover instances */
      poolConfig.setBlockWhenExhausted(true)

      val factory = PoolUtils.synchronizedPooledFactory(workerVerifierPoolableObjectFactory)

      workerVerifierPool = new GenericObjectPool[WorkerVerifier](factory, poolConfig)

      workerVerifierPool.addObjects(poolConfig.getMaxTotal)

      assert(workerVerifiers.length == poolConfig.getMaxTotal)
      if (parallelLifecycle) runAll(workerVerifiers.map(worker => () => worker.start()))
      else workerVerifiers foreach (_.start())
      if (asyncBroadcast) workerExecutors = workerVerifiers.map(_ => Executors.newSingleThreadExecutor())

      threadPool = new ForkJoinPool(poolConfig.getMaxTotal, new WorkerBorrowingForkJoinThreadFactory(), null, false)
    } catch {
      case e: Throwable =>
        // Stop the workers that did start, so that a failed setup does not leak their provers.
        try teardownWorkerPool()
        catch { case suppressed: Exception => e.addSuppressed(suppressed) }
        throw e
    }
  }
  
  private def resetWorkerPool(): Unit = {
    if (capWorkers) teardownWorkerPool() /* [[ensureStarted]] sizes the pool for the next program */
    else workerVerifiers foreach (_.reset())
  }

  private def teardownWorkerPool(): Unit = {
    // Detach the components first, so that a repeated teardown is a no-op.
    val pool = threadPool
    val workers = workerVerifiers
    val verifierPool = workerVerifierPool
    val executors = workerExecutors
    threadPool = null
    workerVerifiers = null
    workerVerifierPool = null
    workerExecutors = Seq.empty
    pendingCommands = Seq.empty

    try {
      Cleanup.run(
        // Cancel queued work before stopping provers: it must not start on a stopped verifier.
        () => if (pool != null) { pool.shutdownNow(); () },
        () => executors foreach (_.shutdownNow()),
        () => if (workers != null) runAll(workers.map(worker => () => worker.stop())),
        // Destroying the provers above also releases workers blocked on solver output.
        () => if (pool != null && !pool.awaitTermination(10, TimeUnit.SECONDS)) {
          mainVerifier.logger.warn("verifier pool threads did not terminate within 10 seconds")
        },
        () => if (verifierPool != null) verifierPool.close()
      )
    } catch {
      case e: InterruptedException =>
        // Cleanup restores the flag and prioritises non-interruption failures, if any.
        mainVerifier.logger.warn("interrupted while tearing down the verifier pool", e)
    }
  }

  private object workerVerifierPoolableObjectFactory extends BasePooledObjectFactory[WorkerVerifier] {
    def create(): WorkerVerifier = {
      val worker = new WorkerVerifier(mainVerifier, mainVerifier.nextUniqueVerifierId(), mainVerifier.reporter, Verifier.config.enableDebugging())
      workerVerifiers = worker +: workerVerifiers

      worker
    }

    def wrap(arg: WorkerVerifier): PooledObject[WorkerVerifier] = new DefaultPooledObject(arg)
  }

  /* Verification task management */


  def queueVerificationTask(task: WorkerVerifier => Seq[VerificationResult])
                           : Future[Seq[VerificationResult]] = {
    awaitBroadcast()
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerBorrowingForkJoinWorkerThread]){
      new WorkerAwareForkJoinTask(task).fork
    } else {
      threadPool.submit(new WorkerAwareForkJoinTask(task))
    }
  }

  /* Lifetime */

  def start(): Unit = {
    if (!capWorkers) setupWorkerPool()
  }

  /** Under capWorkers, starts the pool for a program with `tasks` top-level verification tasks. */
  def ensureStarted(tasks: Int): Unit = {
    if (capWorkers && workerVerifiers == null) {
      numberOfWorkers =
        if (Verifier.config.parallelizeBranches()) configuredWorkers
        else math.max(1, math.min(configuredWorkers, tasks))
      setupWorkerPool()
    }
  }

  def reset(): Unit = {
    resetWorkerPool()
  }

  def stop(): Unit = {
    teardownWorkerPool()
  }

  class WorkerBorrowingForkJoinThreadFactory extends ForkJoinPool.ForkJoinWorkerThreadFactory {
    override def newThread(forkJoinPool: ForkJoinPool): ForkJoinWorkerThread = {
      new WorkerBorrowingForkJoinWorkerThread(forkJoinPool)
    }
  }

  class WorkerBorrowingForkJoinWorkerThread(pool: ForkJoinPool) extends ForkJoinWorkerThread(pool) {
    // each thread has its own worker verifier that does not change.
    var worker: WorkerVerifier = null

    override def onStart(): Unit = {
      worker = workerVerifierPool.borrowObject()
    }

    override def onTermination(exception: Throwable): Unit = {
      // The pool is detached when the verifier pool is torn down before this thread terminates.
      val pool = workerVerifierPool
      if (worker != null && pool != null) {
        pool.returnObject(worker)
      }
    }

  }

  class WorkerAwareForkJoinTask(task: WorkerVerifier => Seq[VerificationResult])
    extends RecursiveTask[Seq[VerificationResult]]{

    override def compute(): Seq[VerificationResult] = {
      // get the worker verifier of the current thread
      val workerThread = Thread.currentThread().asInstanceOf[WorkerBorrowingForkJoinWorkerThread]
      val workerVerifier = workerThread.worker
      task(workerVerifier)
    }
  }
}


