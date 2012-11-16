/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.io.ObjectStreamException
import java.util.{ LinkedList ⇒ JLinkedList, Queue ⇒ JQueue }
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import scala.annotation.tailrec
import scala.concurrent.forkjoin.ThreadLocalRandom

import akka.actor.dungeon.ChildrenContainer
import akka.event.Logging.Warning
import akka.util.Unsafe
import akka.dispatch._

/**
 * This actor ref starts out with some dummy cell (by default just enqueuing
 * messages into vectors protected by ReentrantLock), it must be initialize()’d
 * before it can be sent to, and it will be activate()’d by its supervisor in
 * response to the Supervise() message, which will replace the contained Cell
 * with a fully functional one, transfer all messages from dummy to real queue
 * and swap out the cell ref.
 */
private[akka] class RepointableActorRef(
  val system: ActorSystemImpl,
  val props: Props,
  val supervisor: InternalActorRef,
  val path: ActorPath)
  extends ActorRefWithCell with RepointableRef {

  import AbstractActorRef.cellOffset

  @volatile private var _cellDoNotCallMeDirectly: Cell = _

  def underlying: Cell = Unsafe.instance.getObjectVolatile(this, cellOffset).asInstanceOf[Cell]

  @tailrec final def swapCell(next: Cell): Cell = {
    val old = underlying
    if (Unsafe.instance.compareAndSwapObject(this, cellOffset, old, next)) old else swapCell(next)
  }

  /**
   * Initialize: make a dummy cell which holds just a mailbox, then tell our
   * supervisor that we exist so that he can create the real Cell in
   * handleSupervise().
   *
   * Call twice on your own peril!
   *
   * This is protected so that others can have different initialization.
   */
  def initialize(): this.type =
    underlying match {
      case null ⇒
        val uid = ThreadLocalRandom.current.nextInt()
        swapCell(new UnstartedCell(system, this, props, supervisor, uid))
        supervisor.sendSystemMessage(Supervise(this, uid))
        this
      case other ⇒ this
    }

  /**
   * This method is supposed to be called by the supervisor in handleSupervise()
   * to replace the UnstartedCell with the real one. It assumes no concurrent
   * modification of the `underlying` field, though it is safe to send messages
   * at any time.
   */
  def activate(): this.type =
    underlying match {
      case u: UnstartedCell ⇒ u.replaceWith(newCell(u)); this
      case null             ⇒ throw new IllegalStateException("underlying cell is null")
      case _                ⇒ this // this happens routinely for things which were created async=false
    }

  /**
   * This is called by activate() to obtain the cell which is to replace the
   * unstarted cell. The cell must be fully functional.
   */
  def newCell(old: Cell): Cell =
    new ActorCell(system, this, props, supervisor).
      init(old.asInstanceOf[UnstartedCell].uid, sendSupervise = false).start()

  def start(): Unit = ()

  def suspend(): Unit = underlying.suspend()

  def resume(causedByFailure: Throwable): Unit = underlying.resume(causedByFailure)

  def stop(): Unit = underlying.stop()

  def restart(cause: Throwable): Unit = underlying.restart(cause)

  def isStarted: Boolean = underlying match {
    case _: UnstartedCell ⇒ false
    case null             ⇒ throw new IllegalStateException("isStarted called before initialized")
    case _                ⇒ true
  }

  def isTerminated: Boolean = underlying.isTerminated

  def provider: ActorRefProvider = system.provider

  def isLocal: Boolean = underlying.isLocal

  def getParent: InternalActorRef = underlying.parent

  def getChild(name: Iterator[String]): InternalActorRef =
    if (name.hasNext) {
      name.next match {
        case ".." ⇒ getParent.getChild(name)
        case ""   ⇒ getChild(name)
        case other ⇒
          underlying.getChildByName(other) match {
            case Some(crs: ChildRestartStats) ⇒ crs.child.asInstanceOf[InternalActorRef].getChild(name)
            case _                            ⇒ Nobody
          }
      }
    } else this

  def !(message: Any)(implicit sender: ActorRef = Actor.noSender) = underlying.tell(message, sender)

  def sendSystemMessage(message: SystemMessage) = underlying.sendSystemMessage(message)

  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef = SerializedActorRef(path)
}

private[akka] class UnstartedCell(val systemImpl: ActorSystemImpl,
                                  val self: RepointableActorRef,
                                  val props: Props,
                                  val supervisor: InternalActorRef,
                                  val uid: Int) extends Cell {

  /*
   * This lock protects all accesses to this cell’s queues. It also ensures 
   * safe switching to the started ActorCell.
   */
  private[this] final val lock = new ReentrantLock

  // use Envelope to keep on-send checks in the same place ACCESS MUST BE PROTECTED BY THE LOCK
  private[this] final val queue: JQueue[Any] = new JLinkedList()

  import systemImpl.settings.UnstartedPushTimeout.{ duration ⇒ timeout }

  def replaceWith(cell: Cell): Unit = locked {
    try {
      /*
       * The CallingThreadDispatcher nicely dives under the ReentrantLock and
       * breaks things by enqueueing into stale queues from within the message
       * processing which happens in-line for sendSystemMessage() and tell().
       * Since this is the only possible way to f*ck things up within this 
       * lock, double-tap (well, N-tap, really); concurrent modification is
       * still not possible because we’re the only thread accessing the queues.
       */
      while (!queue.isEmpty) {
        queue.poll() match {
          case s: SystemMessage ⇒ cell.sendSystemMessage(s)
          case e: Envelope      ⇒ cell.tell(e.message, e.sender)
        }
      }
    } finally self.swapCell(cell)
  }

  def system: ActorSystem = systemImpl
  def suspend(): Unit = sendSystemMessage(Suspend())
  def resume(causedByFailure: Throwable): Unit = sendSystemMessage(Resume(causedByFailure))
  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))
  def stop(): Unit = sendSystemMessage(Terminate())
  def isTerminated: Boolean = locked {
    val cell = self.underlying
    if (cellIsReady(cell)) cell.isTerminated else false
  }
  def parent: InternalActorRef = supervisor
  def childrenRefs: ChildrenContainer = ChildrenContainer.EmptyChildrenContainer
  def getChildByName(name: String): Option[ChildRestartStats] = None
  def tell(message: Any, sender: ActorRef): Unit =
    if (lock.tryLock(timeout.length, timeout.unit)) {
      try {
        val cell = self.underlying
        if (cellIsReady(cell)) {
          cell.tell(message, sender)
        } else if (!queue.offer(Envelope(message, sender, system))) {
          system.eventStream.publish(Warning(self.path.toString, getClass, "dropping message of type " + message.getClass + " due to enqueue failure"))
          system.deadLetters ! DeadLetter(message, sender, self)
        }
      } finally lock.unlock()
    } else {
      system.eventStream.publish(Warning(self.path.toString, getClass, "dropping message of type" + message.getClass + " due to lock timeout"))
      system.deadLetters ! DeadLetter(message, sender, self)
    }

  // FIXME: once we have guaranteed delivery of system messages, hook this in!
  def sendSystemMessage(msg: SystemMessage): Unit =
    if (lock.tryLock(timeout.length, timeout.unit)) {
      try {
        val cell = self.underlying
        if (cellIsReady(cell)) {
          cell.sendSystemMessage(msg)
        } else if (!queue.offer(msg)) {
          system.eventStream.publish(Warning(self.path.toString, getClass, "dropping system message " + msg + " due to enqueue failure"))
          system.deadLetters ! DeadLetter(msg, self, self)
        }
      } finally lock.unlock()
    } else {
      system.eventStream.publish(Warning(self.path.toString, getClass, "dropping system message " + msg + " due to lock timeout"))
      system.deadLetters ! DeadLetter(msg, self, self)
    }

  def isLocal = true

  private[this] final def cellIsReady(cell: Cell): Boolean = (cell ne this) && (cell ne null)

  def hasMessages: Boolean = locked {
    val cell = self.underlying
    if (cellIsReady(cell)) cell.hasMessages else !queue.isEmpty
  }

  def numberOfMessages: Int = locked {
    val cell = self.underlying
    if (cellIsReady(cell)) cell.numberOfMessages else queue.size
  }

  private[this] final def locked[T](body: ⇒ T): T = {
    lock.lock()
    try body finally lock.unlock()
  }

}
