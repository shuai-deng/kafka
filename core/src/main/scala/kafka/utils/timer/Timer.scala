/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.utils.timer

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.{DelayQueue, Executors, TimeUnit}

import kafka.utils.threadsafe
import org.apache.kafka.common.utils.{KafkaThread, Time}
// Timer 接口定义了管理延迟操作的方法，而 SystemTimer 是实现延迟操作的关键代码。
trait Timer {
  /**
    * Add a new task to this executor. It will be executed after the task's delay
    * (beginning from the time of submission)
    * @param timerTask the task to add
    */
  // 将给定的定时任务插入到时间轮上，等待后续延迟执行
  def add(timerTask: TimerTask): Unit

  /**
    * Advance the internal clock, executing any tasks whose expiration has been
    * reached within the duration of the passed timeout.
    * @param timeoutMs
    * @return whether or not any tasks were executed
    */
  // 向前推进时钟，执行已达过期时间的延迟任务
  def advanceClock(timeoutMs: Long): Boolean

  /**
    * Get the number of tasks pending execution
    * @return the number of tasks
    */
  // 获取时间轮上总的定时任务数
  def size: Int

  /**
    * Shutdown the timer service, leaving pending tasks unexecuted
    */
  // 关闭定时器
  def shutdown(): Unit
}
//SystemTimer 类是 Timer 接口的实现类。
// 它是一个定时器类，封装了分层时间轮对象，为 Purgatory 提供延迟请求管理功能。
// 所谓的 Purgatory，就是保存延迟请求的缓冲区。也就是说，它保存的是因为不满足条件而无法完成，但是又没有超时的请求。
// executorName：Purgatory 的名字。Kafka 中存在不同的 Purgatory，比如专门处理生产者延迟请求的 Produce 缓冲区、
//              处理消费者延迟请求的 Fetch 缓冲区等。这里的 Produce 和 Fetch 就是 executorName。
// startMs：该 SystemTimer 定时器启动时间，单位是毫秒。
@threadsafe
class SystemTimer(executorName: String,
                  tickMs: Long = 1,
                  wheelSize: Int = 20,
                  startMs: Long = Time.SYSTEM.hiResClockMs) extends Timer {

  // timeout timer
  // 它是单线程的线程池，用于异步执行提交的定时任务逻辑。
  private[this] val taskExecutor = Executors.newFixedThreadPool(1,
    (runnable: Runnable) => KafkaThread.nonDaemon("executor-" + executorName, runnable))
  // 延迟队列保存所有Bucket，即所有TimerTaskList对象
  // 它保存了该定时器下管理的所有 Bucket 对象。因为是 DelayQueue，所以只有在 Bucket 过期后，才能从该队列中获取到。
  // SystemTimer 类的 advanceClock 方法正是依靠了这个特性向前驱动时钟。关于这一点，一会儿我们详细说。
  private[this] val delayQueue = new DelayQueue[TimerTaskList]()
  // 总定时任务数
  private[this] val taskCounter = new AtomicInteger(0)
  // 时间轮对象
  // TimingWheel 是实现分层时间轮的类。SystemTimer 类依靠它来操作分层时间轮。
  private[this] val timingWheel = new TimingWheel(
    tickMs = tickMs,
    wheelSize = wheelSize,
    startMs = startMs,
    taskCounter = taskCounter,
    delayQueue
  )

  // Locks used to protect data structures while ticking
  // 维护线程安全的读写锁
  private[this] val readWriteLock = new ReentrantReadWriteLock()
  private[this] val readLock = readWriteLock.readLock()
  private[this] val writeLock = readWriteLock.writeLock()

  def add(timerTask: TimerTask): Unit = {
    // 获取读锁。在没有线程持有写锁的前提下， 多个线程能够同时向时间轮添加定时任务
    readLock.lock()
    try {
      // 调用addTimerTaskEntry执行插入逻辑
      addTimerTaskEntry(new TimerTaskEntry(timerTask, timerTask.delayMs + Time.SYSTEM.hiResClockMs))
    } finally {
      // 释放读锁
      readLock.unlock()
    }
  }

  // addTimerTaskEntry 方法到底执行什么逻辑，取决于给定定时任务的状态
  private def addTimerTaskEntry(timerTaskEntry: TimerTaskEntry): Unit = {
    // 视timerTaskEntry状态决定执行什么逻辑：
    // 1. 未过期未取消：添加到时间轮
    // 2. 已取消：什么都不做
    // 3. 已过期：提交到线程池，等待执行
    if (!timingWheel.add(timerTaskEntry)) {
      // Already expired or cancelled
      // 定时任务未取消，说明定时任务已过期；否则timingWheel.add方法应该返回True
      if (!timerTaskEntry.cancelled)
        taskExecutor.submit(timerTaskEntry.timerTask)
    }
  }

  /*
   * Advances the clock if there is an expired bucket. If there isn't any expired bucket when called,
   * waits up to timeoutMs before giving up.
   */
  def advanceClock(timeoutMs: Long): Boolean = {
    // 获取delayQueue中下一个已过期的Bucket
    var bucket = delayQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    if (bucket != null) {
      // 获取写锁
      // 一旦有线程持有写锁，其他任何线程执行add或advanceClock方法时会阻塞
      writeLock.lock()
      try {
        while (bucket != null) {
          // 推动时间轮向前"滚动"到Bucket的过期时间点
          timingWheel.advanceClock(bucket.getExpiration)
          // 将该Bucket下的所有定时任务重写回到时间轮
          bucket.flush(addTimerTaskEntry)
          // 读取下一个Bucket对象
          bucket = delayQueue.poll()
        }
      } finally {
        // 释放写锁
        writeLock.unlock()
      }
      true
    } else {
      false
    }
  }

  def size: Int = taskCounter.get

  override def shutdown(): Unit = {
    taskExecutor.shutdown()
  }

}
