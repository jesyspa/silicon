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

class VerificationPoolManager(mainVerifier: MainVerifier) extends StatefulComponent {
  private val numberOfWorkers: Int = Verifier.config.numberOfParallelVerifiers()

  /*private*/ var workerVerifiers: Seq[WorkerVerifier] = _
  /*private*/ var threadPool: ForkJoinPool = _
  /*private*/ var workerVerifierPool: ObjectPool[WorkerVerifier] = _

  private[verifier] object pooledVerifiers extends ProverLike {
    def emit(content: String): Unit = workerVerifiers foreach (_.decider.prover.emit(content))
    override def emit(contents: Iterable[String]): Unit = workerVerifiers foreach (_.decider.prover.emit(contents))
    def assume(term: Term): Unit = workerVerifiers foreach (_.decider.prover.assume(term))
    override def assumeAxioms(terms: InsertionOrderedSet[Term], description: String): Unit = workerVerifiers foreach (_.decider.prover.assumeAxioms(terms, description))
    def declare(decl: Decl): Unit =  workerVerifiers foreach (_.decider.prover.declare(decl))
    def comment(content: => String): Unit = {
      lazy val c = content
      workerVerifiers foreach (_.decider.prover.comment(c))
    }

    def saturate(data: Option[Config.ProverStateSaturationTimeout]): Unit =
      workerVerifiers foreach (_.decider.prover.saturate(data))

    def saturate(timeout: Int, comment: String): Unit =
      workerVerifiers foreach (_.decider.prover.saturate(timeout, comment))

    override def emitSettings(contents: Iterable[String]): Unit =
      workerVerifiers foreach (_.decider.prover.emitSettings(contents))

    override def setOption(name: String, value: String): String =
      (workerVerifiers map (_.decider.prover.setOption(name, value))).head
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
      workerVerifiers foreach (_.start())

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
    workerVerifiers foreach (_.reset())
  }

  private def teardownWorkerPool(): Unit = {
    // Detach the components first, so that a repeated teardown is a no-op.
    val pool = threadPool
    val workers = workerVerifiers
    val verifierPool = workerVerifierPool
    threadPool = null
    workerVerifiers = null
    workerVerifierPool = null

    try {
      Cleanup.run(
        // Cancel queued work before stopping provers: it must not start on a stopped verifier.
        () => if (pool != null) { pool.shutdownNow(); () },
        () => if (workers != null) {
          Cleanup.run(workers.map(worker => () => worker.stop()): _*)
        },
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
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerBorrowingForkJoinWorkerThread]){
      new WorkerAwareForkJoinTask(task).fork
    } else {
      threadPool.submit(new WorkerAwareForkJoinTask(task))
    }
  }

  /* Lifetime */

  def start(): Unit = {
    setupWorkerPool()
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


