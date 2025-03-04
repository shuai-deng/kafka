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
package kafka.server

import com.yammer.metrics.core.Meter
import kafka.api._
import kafka.cluster.{BrokerEndPoint, Partition, PartitionListener}
import kafka.controller.{KafkaController, StateChangeLogger}
import kafka.log.remote.RemoteLogManager
import kafka.log.{LogManager, UnifiedLog}
import kafka.server.HostedPartition.Online
import kafka.server.QuotaFactory.QuotaManagers
import kafka.server.checkpoints.{LazyOffsetCheckpoints, OffsetCheckpointFile, OffsetCheckpoints}
import kafka.server.metadata.ZkMetadataCache
import kafka.utils.Implicits._
import kafka.utils._
import kafka.zk.KafkaZkClient
import org.apache.kafka.common.errors._
import org.apache.kafka.common.internals.Topic
import org.apache.kafka.common.message.AddPartitionsToTxnRequestData.{AddPartitionsToTxnTopic, AddPartitionsToTxnTopicCollection, AddPartitionsToTxnTransaction}
import org.apache.kafka.common.message.DeleteRecordsResponseData.DeleteRecordsPartitionResult
import org.apache.kafka.common.message.LeaderAndIsrRequestData.LeaderAndIsrPartitionState
import org.apache.kafka.common.message.LeaderAndIsrResponseData.{LeaderAndIsrPartitionError, LeaderAndIsrTopicError}
import org.apache.kafka.common.message.OffsetForLeaderEpochRequestData.OffsetForLeaderTopic
import org.apache.kafka.common.message.OffsetForLeaderEpochResponseData.{EpochEndOffset, OffsetForLeaderTopicResult}
import org.apache.kafka.common.message.StopReplicaRequestData.StopReplicaPartitionState
import org.apache.kafka.common.message.{DescribeLogDirsResponseData, DescribeProducersResponseData, FetchResponseData, LeaderAndIsrResponseData}
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record.FileRecords.TimestampAndOffset
import org.apache.kafka.common.record._
import org.apache.kafka.common.replica.PartitionView.DefaultPartitionView
import org.apache.kafka.common.replica.ReplicaView.DefaultReplicaView
import org.apache.kafka.common.replica._
import org.apache.kafka.common.requests.FetchRequest.PartitionData
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse
import org.apache.kafka.common.requests._
import org.apache.kafka.common.utils.Time
import org.apache.kafka.common.{ElectionType, IsolationLevel, Node, TopicIdPartition, TopicPartition, Uuid}
import org.apache.kafka.image.{LocalReplicaChanges, MetadataImage, TopicsDelta}
import org.apache.kafka.metadata.LeaderConstants.NO_LEADER
import org.apache.kafka.server.common.MetadataVersion._
import org.apache.kafka.server.util.{Scheduler, ShutdownableThread}
import org.apache.kafka.storage.internals.log.{AppendOrigin, FetchDataInfo, FetchParams, FetchPartitionData, LeaderHwChange, LogAppendInfo, LogConfig, LogDirFailureChannel, LogOffsetMetadata, LogReadInfo, RecordValidationException}
import org.apache.kafka.server.metrics.KafkaMetricsGroup

import java.io.File
import java.nio.file.{Files, Paths}
import java.util
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.{Optional, OptionalInt, OptionalLong}
import scala.collection.{Map, Seq, Set, mutable}
import scala.compat.java8.OptionConverters._
import scala.jdk.CollectionConverters._

/*
 * Result metadata of a log append operation on the log
 * LogAppendResult：表示副本管理器执行副本日志写入操作后返回的结果信息。
 */
case class LogAppendResult(info: LogAppendInfo, exception: Option[Throwable] = None) {
  def error: Errors = exception match {
    case None => Errors.NONE
    case Some(e) => Errors.forException(e)
  }
}
// LogDeleteRecordsResult：表示副本管理器执行副本日志删除操作后返回的结果信息。
case class LogDeleteRecordsResult(requestedOffset: Long, lowWatermark: Long, exception: Option[Throwable] = None) {
  def error: Errors = exception match {
    case None => Errors.NONE
    case Some(e) => Errors.forException(e)
  }
}

/**
 * Result metadata of a log read operation on the log
 * @param info @FetchDataInfo returned by the @Log read
 * @param divergingEpoch Optional epoch and end offset which indicates the largest epoch such
 *                       that subsequent records are known to diverge on the follower/consumer
 * @param highWatermark high watermark of the local replica
 * @param leaderLogStartOffset The log start offset of the leader at the time of the read
 * @param leaderLogEndOffset The log end offset of the leader at the time of the read
 * @param followerLogStartOffset The log start offset of the follower taken from the Fetch request
 * @param fetchTimeMs The time the fetch was received
 * @param lastStableOffset Current LSO or None if the result has an exception
 * @param preferredReadReplica the preferred read replica to be used for future fetches
 * @param exception Exception if error encountered while reading from the log
 *  LogReadResult:表示副本管理器从副本本地日志中读取到的消息数据以及相关元数据信息，如高水位值、Log Start Offset 值等。
 */
case class LogReadResult(info: FetchDataInfo,
                         divergingEpoch: Option[FetchResponseData.EpochEndOffset],
                         highWatermark: Long,
                         leaderLogStartOffset: Long,
                         leaderLogEndOffset: Long,
                         followerLogStartOffset: Long,
                         fetchTimeMs: Long,
                         lastStableOffset: Option[Long],
                         preferredReadReplica: Option[Int] = None,
                         exception: Option[Throwable] = None) {

  def error: Errors = exception match {
    case None => Errors.NONE
    case Some(e) => Errors.forException(e)
  }

  def toFetchPartitionData(isReassignmentFetch: Boolean): FetchPartitionData = new FetchPartitionData(
    this.error,
    this.highWatermark,
    this.leaderLogStartOffset,
    this.info.records,
    this.divergingEpoch.asJava,
    if (this.lastStableOffset.isDefined) OptionalLong.of(this.lastStableOffset.get) else OptionalLong.empty(),
    this.info.abortedTransactions,
    if (this.preferredReadReplica.isDefined) OptionalInt.of(this.preferredReadReplica.get) else OptionalInt.empty(),
    isReassignmentFetch)

  def withEmptyFetchInfo: LogReadResult =
    copy(info = new FetchDataInfo(LogOffsetMetadata.UNKNOWN_OFFSET_METADATA, MemoryRecords.EMPTY))

  override def toString = {
    "LogReadResult(" +
      s"info=$info, " +
      s"divergingEpoch=$divergingEpoch, " +
      s"highWatermark=$highWatermark, " +
      s"leaderLogStartOffset=$leaderLogStartOffset, " +
      s"leaderLogEndOffset=$leaderLogEndOffset, " +
      s"followerLogStartOffset=$followerLogStartOffset, " +
      s"fetchTimeMs=$fetchTimeMs, " +
      s"preferredReadReplica=$preferredReadReplica, " +
      s"lastStableOffset=$lastStableOffset, " +
      s"error=$error" +
      ")"
  }

}

/**
 * Trait to represent the state of hosted partitions. We create a concrete (active) Partition
 * instance when the broker receives a LeaderAndIsr request from the controller or a metadata
 * log record from the Quorum controller indicating that the broker should be either a leader
 * or follower of a partition.
 */
sealed trait HostedPartition
// HostedPartition 及其实现对象：表示 Broker 本地保存的分区对象的状态。
// 可能的状态包括：不存在状态（None）、在线状态（Online）和离线状态（Offline）。
object HostedPartition {
  /**
   * This broker does not have any state for this partition locally.
   */
  final object None extends HostedPartition

  /**
   * This broker hosts the partition and it is online.
   */
  final case class Online(partition: Partition) extends HostedPartition

  /**
   * This broker hosts the partition, but it is in an offline log directory.
   */
  final object Offline extends HostedPartition
}

object ReplicaManager {
  val HighWatermarkFilename = "replication-offset-checkpoint"
}

// ReplicaManager 类：它是副本管理器的具体实现代码，里面定义了读写副本、删除副本消息的方法以及其他管理方法。
class ReplicaManager(val config: KafkaConfig,// 配置管理类
                     metrics: Metrics,// 监控指标类
                     time: Time,// 定时器类
                     scheduler: Scheduler,// Kafka调度器
                     val logManager: LogManager,//这是日志管理器。它负责创建和管理分区的日志对象，里面定义了很多操作日志对象的方法，如 getOrCreateLog 等。
                     val remoteLogManager: Option[RemoteLogManager] = None,
                     quotaManagers: QuotaManagers,// 配额管理器
                     val metadataCache: MetadataCache,// Broker元数据缓存,这是 Broker 端的元数据缓存，保存集群上分区的 Leader、ISR 等信息。注意，它和我们之前说的 Controller 端元数据缓存是有联系的。每台 Broker 上的元数据缓存，是从 Controller 端的元数据缓存异步同步过来的。
                     // 这是失效日志路径的处理器类。Kafka 1.1 版本新增了对于 JBOD 的支持。这也就是说，Broker 如果配置了多个日志路径，当某个日志路径不可用之后（比如该路径所在的磁盘已满），Broker 能够继续工作。
                     // 那么，这就需要一整套机制来保证，在出现磁盘 I/O 故障时，Broker 的正常磁盘下的副本能够正常提供服务。
                     // 其中，logDirFailureChannel 是暂存失效日志路径的管理器类。该功能算是 Kafka 提升服务器端高可用性的一个改进。有了它之后，即使 Broker 上的单块磁盘坏掉了，整个 Broker 的服务也不会中断。
                     logDirFailureChannel: LogDirFailureChannel,
                     val alterPartitionManager: AlterPartitionManager,
                     val brokerTopicStats: BrokerTopicStats = new BrokerTopicStats(),
                     val isShuttingDown: AtomicBoolean = new AtomicBoolean(false),
                     val zkClient: Option[KafkaZkClient] = None,
                     // 处理延时PRODUCE请求的Purgatory
                     delayedProducePurgatoryParam: Option[DelayedOperationPurgatory[DelayedProduce]] = None,
                     // 处理延时FETCH请求的Purgatory
                     delayedFetchPurgatoryParam: Option[DelayedOperationPurgatory[DelayedFetch]] = None,
                     // 处理延时DELETE_RECORDS请求的Purgatory
                     delayedDeleteRecordsPurgatoryParam: Option[DelayedOperationPurgatory[DelayedDeleteRecords]] = None,
                     // 处理延时ELECT_LEADERS请求的Purgatory
                     delayedElectLeaderPurgatoryParam: Option[DelayedOperationPurgatory[DelayedElectLeader]] = None,
                     threadNamePrefix: Option[String] = None,
                     val brokerEpochSupplier: () => Long = () => -1,
                     addPartitionsToTxnManager: Option[AddPartitionsToTxnManager] = None
                     ) extends Logging {
  private val metricsGroup = new KafkaMetricsGroup(this.getClass)

  val delayedProducePurgatory = delayedProducePurgatoryParam.getOrElse(
    DelayedOperationPurgatory[DelayedProduce](
      purgatoryName = "Produce", brokerId = config.brokerId,
      purgeInterval = config.producerPurgatoryPurgeIntervalRequests))
  val delayedFetchPurgatory = delayedFetchPurgatoryParam.getOrElse(
    DelayedOperationPurgatory[DelayedFetch](
      purgatoryName = "Fetch", brokerId = config.brokerId,
      purgeInterval = config.fetchPurgatoryPurgeIntervalRequests))
  val delayedDeleteRecordsPurgatory = delayedDeleteRecordsPurgatoryParam.getOrElse(
    DelayedOperationPurgatory[DelayedDeleteRecords](
      purgatoryName = "DeleteRecords", brokerId = config.brokerId,
      purgeInterval = config.deleteRecordsPurgatoryPurgeIntervalRequests))
  val delayedElectLeaderPurgatory = delayedElectLeaderPurgatoryParam.getOrElse(
    DelayedOperationPurgatory[DelayedElectLeader](
      purgatoryName = "ElectLeader", brokerId = config.brokerId))

  /* epoch of the controller that last changed the leader */
  // 这个字段的作用是隔离过期 Controller 发送的请求，该字段表示最新一次变更分区 Leader 的 Controller 的 Epoch 值，其默认值为 0。Controller 每发生一次变更，该字段值都会 +1。
  // 在 ReplicaManager 的代码中，很多地方都会用到它来判断 Controller 发送过来的控制类请求是否合法。如果请求中携带的 controllerEpoch 值小于该字段值，
  // 就说明这个请求是由一个老的 Controller 发出的，因此，ReplicaManager 直接拒绝该请求的处理。
  // 它是一个 var 类型，它的值是能够动态修改的：【becomeLeaderOrFollower方法、stopReplicas方法、maybeUpdateMetadataCache方法】
  // Broker 上接收的所有请求都是由 Kafka I/O 线程处理的，而 I/O 线程可能有多个，因此，这里的 controllerEpoch 字段被声明为 volatile 型，以保证其内存可见性。
  @volatile private[server] var controllerEpoch: Int = KafkaController.InitialControllerEpoch
  protected val localBrokerId = config.brokerId
  // Kafka 没有所谓的分区管理器，ReplicaManager 类承担了一部分分区管理的工作。
  // 这里的 allPartitions，就承载了 Broker 上保存的所有分区对象数据。
  protected val allPartitions = new Pool[TopicPartition, HostedPartition](
    valueFactory = Some(tp => HostedPartition.Online(Partition(tp, time, this)))
  )
  protected val replicaStateChangeLock = new Object
  // 它的主要任务是创建 ReplicaFetcherThread 类实例
  // ReplicaFetcherThread 类的源码，它的主要职责是帮助 Follower 副本向 Leader 副本拉取消息，并写入到本地日志中。
  val replicaFetcherManager = createReplicaFetcherManager(metrics, time, threadNamePrefix, quotaManagers.follower)
  private[server] val replicaAlterLogDirsManager = createReplicaAlterLogDirsManager(quotaManagers.alterLogDirs, brokerTopicStats)
  private val highWatermarkCheckPointThreadStarted = new AtomicBoolean(false)
  @volatile private[server] var highWatermarkCheckpoints: Map[String, OffsetCheckpointFile] = logManager.liveLogDirs.map(dir =>
    (dir.getAbsolutePath, new OffsetCheckpointFile(new File(dir, ReplicaManager.HighWatermarkFilename), logDirFailureChannel))).toMap

  @volatile private var isInControlledShutdown = false

  this.logIdent = s"[ReplicaManager broker=$localBrokerId] "
  protected val stateChangeLogger = new StateChangeLogger(localBrokerId, inControllerContext = false, None)

  private var logDirFailureHandler: LogDirFailureHandler = _

  private class LogDirFailureHandler(name: String, haltBrokerOnDirFailure: Boolean) extends ShutdownableThread(name) {
    override def doWork(): Unit = {
      val newOfflineLogDir = logDirFailureChannel.takeNextOfflineLogDir()
      if (haltBrokerOnDirFailure) {
        fatal(s"Halting broker because dir $newOfflineLogDir is offline")
        Exit.halt(1)
      }
      handleLogDirFailure(newOfflineLogDir)
    }
  }

  // Visible for testing
  private[server] val replicaSelectorOpt: Option[ReplicaSelector] = createReplicaSelector()

  metricsGroup.newGauge("LeaderCount", () => leaderPartitionsIterator.size)
  // Visible for testing
  private[kafka] val partitionCount = metricsGroup.newGauge("PartitionCount", () => allPartitions.size)
  metricsGroup.newGauge("OfflineReplicaCount", () => offlinePartitionCount)
  metricsGroup.newGauge("UnderReplicatedPartitions", () => underReplicatedPartitionCount)
  metricsGroup.newGauge("UnderMinIsrPartitionCount", () => leaderPartitionsIterator.count(_.isUnderMinIsr))
  metricsGroup.newGauge("AtMinIsrPartitionCount", () => leaderPartitionsIterator.count(_.isAtMinIsr))
  metricsGroup.newGauge("ReassigningPartitions", () => reassigningPartitionsCount)
  metricsGroup.newGauge("PartitionsWithLateTransactionsCount", () => lateTransactionsCount)
  metricsGroup.newGauge("ProducerIdCount", () => producerIdCount)

  def reassigningPartitionsCount: Int = leaderPartitionsIterator.count(_.isReassigning)

  private def lateTransactionsCount: Int = {
    val currentTimeMs = time.milliseconds()
    leaderPartitionsIterator.count(_.hasLateTransaction(currentTimeMs))
  }

  def producerIdCount: Int = onlinePartitionsIterator.map(_.producerIdCount).sum

  val isrExpandRate: Meter = metricsGroup.newMeter("IsrExpandsPerSec", "expands", TimeUnit.SECONDS)
  val isrShrinkRate: Meter = metricsGroup.newMeter("IsrShrinksPerSec", "shrinks", TimeUnit.SECONDS)
  val failedIsrUpdatesRate: Meter = metricsGroup.newMeter("FailedIsrUpdatesPerSec", "failedUpdates", TimeUnit.SECONDS)

  def underReplicatedPartitionCount: Int = leaderPartitionsIterator.count(_.isUnderReplicated)

  def startHighWatermarkCheckPointThread(): Unit = {
    if (highWatermarkCheckPointThreadStarted.compareAndSet(false, true))
      scheduler.schedule("highwatermark-checkpoint", () => checkpointHighWatermarks(), 0L, config.replicaHighWatermarkCheckpointIntervalMs)
  }

  // When ReplicaAlterDirThread finishes replacing a current replica with a future replica, it will
  // remove the partition from the partition state map. But it will not close itself even if the
  // partition state map is empty. Thus we need to call shutdownIdleReplicaAlterDirThread() periodically
  // to shutdown idle ReplicaAlterDirThread
  def shutdownIdleReplicaAlterLogDirsThread(): Unit = {
    replicaAlterLogDirsManager.shutdownIdleFetcherThreads()
  }

  def resizeFetcherThreadPool(newSize: Int): Unit = {
    replicaFetcherManager.resizeThreadPool(newSize)
  }

  def getLog(topicPartition: TopicPartition): Option[UnifiedLog] = logManager.getLog(topicPartition)

  def hasDelayedElectionOperations: Boolean = delayedElectLeaderPurgatory.numDelayed != 0

  def tryCompleteElection(key: DelayedOperationKey): Unit = {
    val completed = delayedElectLeaderPurgatory.checkAndComplete(key)
    debug("Request key %s unblocked %d ElectLeader.".format(key.keyLabel, completed))
  }

  def startup(): Unit = {
    // start ISR expiration thread
    // A follower can lag behind leader for up to config.replicaLagTimeMaxMs x 1.5 before it is removed from ISR
    scheduler.schedule("isr-expiration", () => maybeShrinkIsr(), 0L, config.replicaLagTimeMaxMs / 2)
    scheduler.schedule("shutdown-idle-replica-alter-log-dirs-thread", () => shutdownIdleReplicaAlterLogDirsThread(), 0L, 10000L)

    // If inter-broker protocol (IBP) < 1.0, the controller will send LeaderAndIsrRequest V0 which does not include isNew field.
    // In this case, the broker receiving the request cannot determine whether it is safe to create a partition if a log directory has failed.
    // Thus, we choose to halt the broker on any log directory failure if IBP < 1.0
    val haltBrokerOnFailure = metadataCache.metadataVersion().isLessThan(IBP_1_0_IV0)
    logDirFailureHandler = new LogDirFailureHandler("LogDirFailureHandler", haltBrokerOnFailure)
    logDirFailureHandler.start()
    addPartitionsToTxnManager.foreach(_.start())
  }

  private def maybeRemoveTopicMetrics(topic: String): Unit = {
    val topicHasNonOfflinePartition = allPartitions.values.exists {
      case online: HostedPartition.Online => topic == online.partition.topic
      case HostedPartition.None | HostedPartition.Offline => false
    }
    if (!topicHasNonOfflinePartition) // nothing online or deferred
      brokerTopicStats.removeMetrics(topic)
  }

  protected def completeDelayedFetchOrProduceRequests(topicPartition: TopicPartition): Unit = {
    val topicPartitionOperationKey = TopicPartitionOperationKey(topicPartition)
    delayedProducePurgatory.checkAndComplete(topicPartitionOperationKey)
    delayedFetchPurgatory.checkAndComplete(topicPartitionOperationKey)
  }

  /**
   * Complete any local follower fetches that have been unblocked since new data is available
   * from the leader for one or more partitions. Should only be called by ReplicaFetcherThread
   * after successfully replicating from the leader.
   */
  private[server] def completeDelayedFetchRequests(topicPartitions: Seq[TopicPartition]): Unit = {
    topicPartitions.foreach(tp => delayedFetchPurgatory.checkAndComplete(TopicPartitionOperationKey(tp)))
  }

  /**
   * Registers the provided listener to the partition iff the partition is online.
   */
  def maybeAddListener(partition: TopicPartition, listener: PartitionListener): Boolean = {
    getPartition(partition) match {
      case HostedPartition.Online(partition) =>
        partition.maybeAddListener(listener)
      case _ =>
        false
    }
  }

  /**
   * Removes the provided listener from the partition.
   */
  def removeListener(partition: TopicPartition, listener: PartitionListener): Unit = {
    getPartition(partition) match {
      case HostedPartition.Online(partition) =>
        partition.removeListener(listener)
      case _ => // Ignore
    }
  }

  def stopReplicas(correlationId: Int,
                   controllerId: Int,
                   controllerEpoch: Int,
                   partitionStates: Map[TopicPartition, StopReplicaPartitionState]
                  ): (mutable.Map[TopicPartition, Errors], Errors) = {
    replicaStateChangeLock synchronized {
      stateChangeLogger.info(s"Handling StopReplica request correlationId $correlationId from controller " +
        s"$controllerId for ${partitionStates.size} partitions")
      if (stateChangeLogger.isTraceEnabled)
        partitionStates.forKeyValue { (topicPartition, partitionState) =>
          stateChangeLogger.trace(s"Received StopReplica request $partitionState " +
            s"correlation id $correlationId from controller $controllerId " +
            s"epoch $controllerEpoch for partition $topicPartition")
        }

      val responseMap = new collection.mutable.HashMap[TopicPartition, Errors]
      if (controllerEpoch < this.controllerEpoch) {
        stateChangeLogger.warn(s"Ignoring StopReplica request from " +
          s"controller $controllerId with correlation id $correlationId " +
          s"since its controller epoch $controllerEpoch is old. " +
          s"Latest known controller epoch is ${this.controllerEpoch}")
        (responseMap, Errors.STALE_CONTROLLER_EPOCH)
      } else {
        this.controllerEpoch = controllerEpoch

        val stoppedPartitions = mutable.Map.empty[TopicPartition, Boolean]
        partitionStates.forKeyValue { (topicPartition, partitionState) =>
          val deletePartition = partitionState.deletePartition()

          getPartition(topicPartition) match {
            case HostedPartition.Offline =>
              stateChangeLogger.warn(s"Ignoring StopReplica request (delete=$deletePartition) from " +
                s"controller $controllerId with correlation id $correlationId " +
                s"epoch $controllerEpoch for partition $topicPartition as the local replica for the " +
                "partition is in an offline log directory")
              responseMap.put(topicPartition, Errors.KAFKA_STORAGE_ERROR)

            case HostedPartition.Online(partition) =>
              val currentLeaderEpoch = partition.getLeaderEpoch
              val requestLeaderEpoch = partitionState.leaderEpoch
              // When a topic is deleted, the leader epoch is not incremented. To circumvent this,
              // a sentinel value (EpochDuringDelete) overwriting any previous epoch is used.
              // When an older version of the StopReplica request which does not contain the leader
              // epoch, a sentinel value (NoEpoch) is used and bypass the epoch validation.
              if (requestLeaderEpoch == LeaderAndIsr.EpochDuringDelete ||
                  requestLeaderEpoch == LeaderAndIsr.NoEpoch ||
                  requestLeaderEpoch >= currentLeaderEpoch) {
                stoppedPartitions += topicPartition -> deletePartition
                // Assume that everything will go right. It is overwritten in case of an error.
                responseMap.put(topicPartition, Errors.NONE)
              } else if (requestLeaderEpoch < currentLeaderEpoch) {
                stateChangeLogger.warn(s"Ignoring StopReplica request (delete=$deletePartition) from " +
                  s"controller $controllerId with correlation id $correlationId " +
                  s"epoch $controllerEpoch for partition $topicPartition since its associated " +
                  s"leader epoch $requestLeaderEpoch is smaller than the current " +
                  s"leader epoch $currentLeaderEpoch")
                responseMap.put(topicPartition, Errors.FENCED_LEADER_EPOCH)
              } else {
                stateChangeLogger.info(s"Ignoring StopReplica request (delete=$deletePartition) from " +
                  s"controller $controllerId with correlation id $correlationId " +
                  s"epoch $controllerEpoch for partition $topicPartition since its associated " +
                  s"leader epoch $requestLeaderEpoch matches the current leader epoch")
                responseMap.put(topicPartition, Errors.FENCED_LEADER_EPOCH)
              }

            case HostedPartition.None =>
              // Delete log and corresponding folders in case replica manager doesn't hold them anymore.
              // This could happen when topic is being deleted while broker is down and recovers.
              stoppedPartitions += topicPartition -> deletePartition
              responseMap.put(topicPartition, Errors.NONE)
          }
        }

        stopPartitions(stoppedPartitions).foreach { case (topicPartition, e) =>
          if (e.isInstanceOf[KafkaStorageException]) {
              stateChangeLogger.error(s"Ignoring StopReplica request (delete=true) from " +
                s"controller $controllerId with correlation id $correlationId " +
                s"epoch $controllerEpoch for partition $topicPartition as the local replica for the " +
                "partition is in an offline log directory")
          } else {
            stateChangeLogger.error(s"Ignoring StopReplica request (delete=true) from " +
                s"controller $controllerId with correlation id $correlationId " +
                s"epoch $controllerEpoch for partition $topicPartition due to an unexpected " +
                s"${e.getClass.getName} exception: ${e.getMessage}")
          }
          responseMap.put(topicPartition, Errors.forException(e))
        }
        (responseMap, Errors.NONE)
      }
    }
  }

  /**
   * Stop the given partitions.
   *
   * @param partitionsToStop    A map from a topic partition to a boolean indicating
   *                            whether the partition should be deleted.
   *
   * @return                    A map from partitions to exceptions which occurred.
   *                            If no errors occurred, the map will be empty.
   */
  protected def stopPartitions(
    partitionsToStop: Map[TopicPartition, Boolean]
  ): Map[TopicPartition, Throwable] = {
    // First stop fetchers for all partitions.
    val partitions = partitionsToStop.keySet
    replicaFetcherManager.removeFetcherForPartitions(partitions)
    replicaAlterLogDirsManager.removeFetcherForPartitions(partitions)

    // Second remove deleted partitions from the partition map. Fetchers rely on the
    // ReplicaManager to get Partition's information so they must be stopped first.
    val partitionsToDelete = mutable.Set.empty[TopicPartition]
    partitionsToStop.forKeyValue { (topicPartition, shouldDelete) =>
      if (shouldDelete) {
        getPartition(topicPartition) match {
          case hostedPartition: HostedPartition.Online =>
            if (allPartitions.remove(topicPartition, hostedPartition)) {
              maybeRemoveTopicMetrics(topicPartition.topic)
              // Logs are not deleted here. They are deleted in a single batch later on.
              // This is done to avoid having to checkpoint for every deletions.
              hostedPartition.partition.delete()
            }

          case _ =>
        }
        partitionsToDelete += topicPartition
      }
      // If we were the leader, we may have some operations still waiting for completion.
      // We force completion to prevent them from timing out.
      completeDelayedFetchOrProduceRequests(topicPartition)
    }

    // Third delete the logs and checkpoint.
    val errorMap = new mutable.HashMap[TopicPartition, Throwable]()
    if (partitionsToDelete.nonEmpty) {
      // Delete the logs and checkpoint.
      logManager.asyncDelete(partitionsToDelete, (tp, e) => errorMap.put(tp, e))
    }
    errorMap
  }

  def getPartition(topicPartition: TopicPartition): HostedPartition = {
    Option(allPartitions.get(topicPartition)).getOrElse(HostedPartition.None)
  }

  def isAddingReplica(topicPartition: TopicPartition, replicaId: Int): Boolean = {
    getPartition(topicPartition) match {
      case Online(partition) => partition.isAddingReplica(replicaId)
      case _ => false
    }
  }

  // Visible for testing
  def createPartition(topicPartition: TopicPartition): Partition = {
    val partition = Partition(topicPartition, time, this)
    allPartitions.put(topicPartition, HostedPartition.Online(partition))
    partition
  }

  def onlinePartition(topicPartition: TopicPartition): Option[Partition] = {
    getPartition(topicPartition) match {
      case HostedPartition.Online(partition) => Some(partition)
      case _ => None
    }
  }

  // An iterator over all non offline partitions. This is a weakly consistent iterator; a partition made offline after
  // the iterator has been constructed could still be returned by this iterator.
  private def onlinePartitionsIterator: Iterator[Partition] = {
    allPartitions.values.iterator.flatMap {
      case HostedPartition.Online(partition) => Some(partition)
      case _ => None
    }
  }

  private def offlinePartitionCount: Int = {
    allPartitions.values.iterator.count(_ == HostedPartition.Offline)
  }

  def getPartitionOrException(topicPartition: TopicPartition): Partition = {
    getPartitionOrError(topicPartition) match {
      case Left(Errors.KAFKA_STORAGE_ERROR) =>
        throw new KafkaStorageException(s"Partition $topicPartition is in an offline log directory")

      case Left(error) =>
        throw error.exception(s"Error while fetching partition state for $topicPartition")

      case Right(partition) => partition
    }
  }

  def getPartitionOrError(topicPartition: TopicPartition): Either[Errors, Partition] = {
    getPartition(topicPartition) match {
      case HostedPartition.Online(partition) =>
        Right(partition)

      case HostedPartition.Offline =>
        Left(Errors.KAFKA_STORAGE_ERROR)

      case HostedPartition.None if metadataCache.contains(topicPartition) =>
        // The topic exists, but this broker is no longer a replica of it, so we return NOT_LEADER_OR_FOLLOWER which
        // forces clients to refresh metadata to find the new location. This can happen, for example,
        // during a partition reassignment if a produce request from the client is sent to a broker after
        // the local replica has been deleted.
        Left(Errors.NOT_LEADER_OR_FOLLOWER)

      case HostedPartition.None =>
        Left(Errors.UNKNOWN_TOPIC_OR_PARTITION)
    }
  }

  def localLogOrException(topicPartition: TopicPartition): UnifiedLog = {
    getPartitionOrException(topicPartition).localLogOrException
  }

  def futureLocalLogOrException(topicPartition: TopicPartition): UnifiedLog = {
    getPartitionOrException(topicPartition).futureLocalLogOrException
  }

  def futureLogExists(topicPartition: TopicPartition): Boolean = {
    getPartitionOrException(topicPartition).futureLog.isDefined
  }

  def localLog(topicPartition: TopicPartition): Option[UnifiedLog] = {
    onlinePartition(topicPartition).flatMap(_.log)
  }

  def getLogDir(topicPartition: TopicPartition): Option[String] = {
    localLog(topicPartition).map(_.parentDir)
  }

  /**
   * TODO: move this action queue to handle thread so we can simplify concurrency handling
   */
  private val actionQueue = new ActionQueue

  def tryCompleteActions(): Unit = actionQueue.tryCompleteActions()

  /**
   * Append messages to leader replicas of the partition, and wait for them to be replicated to other replicas;
   * the callback function will be triggered either when timeout or the required acks are satisfied;
   * if the callback function itself is already synchronized on some object then pass this object to avoid deadlock.
   *
   * Noted that all pending delayed check operations are stored in a queue. All callers to ReplicaManager.appendRecords()
   * are expected to call ActionQueue.tryCompleteActions for all affected partitions, without holding any conflicting
   * locks.
   * 
   * @param timeout                       maximum time we will wait to append before returning
   * @param requiredAcks                  number of replicas who must acknowledge the append before sending the response
   * @param internalTopicsAllowed         boolean indicating whether internal topics can be appended to
   * @param origin                        source of the append request (ie, client, replication, coordinator)
   * @param entriesPerPartition           the records per partition to be appended
   * @param responseCallback              callback for sending the response
   * @param delayedProduceLock            lock for the delayed actions
   * @param recordConversionStatsCallback callback for updating stats on record conversions
   * @param requestLocal                  container for the stateful instances scoped to this request
   * @param transactionalId               transactional ID if the request is from a producer and the producer is transactional
   * @param transactionStatePartition     partition that holds the transactional state if transactionalId is present
   * 所谓的副本写入，是指向副本底层日志写入消息。
   * 在 ReplicaManager 类中，实现副本写入的方法叫 appendRecords。
   * 整个 Kafka 源码中，需要副本写入的场景有 4 个。
   * 场景一：生产者向 Leader 副本写入消息；
   * 场景二：Follower 副本拉取消息后写入副本；
   * 场景三：消费者组写入组信息；
   * 场景四：事务管理器写入事务信息（包括事务标记、事务元数据等）。
   */
  def appendRecords(timeout: Long, // 请求处理超时时间。对于生产者来说，它就是 request.timeout.ms 参数值。
                    requiredAcks: Short, // 是否需要等待其他副本写入。对于生产者而言，它就是 acks 参数的值。而在其他场景中，Kafka 默认使用 -1，表示等待其他副本全部写入成功再返回。
                    internalTopicsAllowed: Boolean, // 是否允许向内部主题写入消息。对于普通的生产者而言，该字段是 False，即不允许写入内部主题。对于 Coordinator 组件，特别是消费者组 GroupCoordinator 组件来说，它的职责之一就是向内部位移主题写入消息，因此，此时，该字段值是 True。
                    origin: AppendOrigin, // AppendOrigin 是一个接口，表示写入方来源。当前，它定义了 3 类写入方，分别是 Replication、Coordinator 和 Client。Replication 表示写入请求是由 Follower 副本发出的，它要将从 Leader 副本获取到的消息写入到底层的消息日志中。Coordinator 表示这些写入由 Coordinator 发起，它既可以是管理消费者组的 GroupCooridnator，也可以是管理事务的 TransactionCoordinator。Client 表示本次写入由客户端发起。前面我们说过了，Follower 副本同步过程不调用 appendRecords 方法，因此，这里的 origin 值只可能是 Replication 或 Coordinator。
                    entriesPerPartition: Map[TopicPartition, MemoryRecords], // 按分区分组的、实际要写入的消息集合。
                    responseCallback: Map[TopicPartition, PartitionResponse] => Unit, // 写入成功之后，要调用的回调逻辑函数。
                    delayedProduceLock: Option[Lock] = None, // 专门用来保护消费者组操作线程安全的锁对象，在其他场景中用不到。
                    recordConversionStatsCallback: Map[TopicPartition, RecordConversionStats] => Unit = _ => (), // 消息格式转换操作的回调统计逻辑，主要用于统计消息格式转换操作过程中的一些数据指标，比如总共转换了多少条消息，花费了多长时间。
                    requestLocal: RequestLocal = RequestLocal.NoCaching,
                    transactionalId: String = null,
                    transactionStatePartition: Option[Int] = None): Unit = {
    // requiredAcks合法取值是-1，0，1，否则视为非法
    if (isValidRequiredAcks(requiredAcks)) {
      val sTime = time.milliseconds
      
      val transactionalProducerIds = mutable.HashSet[Long]()
      val (verifiedEntriesPerPartition, notYetVerifiedEntriesPerPartition) = 
        if (transactionStatePartition.isEmpty || !config.transactionPartitionVerificationEnable)
          (entriesPerPartition, Map.empty)
        else {
          entriesPerPartition.partition { case (topicPartition, records) =>
            // Produce requests (only requests that require verification) should only have one batch per partition in "batches" but check all just to be safe.
            val transactionalBatches = records.batches.asScala.filter(batch => batch.hasProducerId && batch.isTransactional)
            transactionalBatches.foreach(batch => transactionalProducerIds.add(batch.producerId))
            if (transactionalBatches.nonEmpty) {
              getPartitionOrException(topicPartition).hasOngoingTransaction(transactionalBatches.head.producerId)
            } else { 
              // If there is no producer ID in the batches, no need to verify.
              true
            }
          }
        }
      // We should have exactly one producer ID for transactional records
      if (transactionalProducerIds.size > 1) {
        throw new InvalidPidMappingException("Transactional records contained more than one producer ID")
      }

      def appendEntries(allEntries: Map[TopicPartition, MemoryRecords])(unverifiedEntries: Map[TopicPartition, Errors]): Unit = {
        val verifiedEntries = 
          if (unverifiedEntries.isEmpty)
            allEntries 
          else
            allEntries.filter { case (tp, _) =>
              !unverifiedEntries.contains(tp)
            }

        // 调用appendToLocalLog方法写入消息集合到本地日志
        val localProduceResults = appendToLocalLog(internalTopicsAllowed = internalTopicsAllowed,
          origin, verifiedEntries, requiredAcks, requestLocal)
        debug("Produce to local log in %d ms".format(time.milliseconds - sTime))
        
        val unverifiedResults = unverifiedEntries.map { case (topicPartition, error) =>
          // NOTE: Older clients return INVALID_RECORD, but newer clients will return INVALID_TXN_STATE
          val message = if (error.equals(Errors.INVALID_RECORD)) "Partition was not added to the transaction" else error.message()
          topicPartition -> LogAppendResult(
            LogAppendInfo.UNKNOWN_LOG_APPEND_INFO,
            Some(error.exception(message))
          )
        }
        
        val allResults = localProduceResults ++ unverifiedResults

        val produceStatus = allResults.map { case (topicPartition, result) =>
          topicPartition -> ProducePartitionStatus(
            result.info.lastOffset + 1, // required offset // 设置下一条待写入消息的位移值
            new PartitionResponse( // 构建PartitionResponse封装写入结果
              result.error,
              result.info.firstOffset.map[Long](_.messageOffset).orElse(-1L),
              result.info.logAppendTime,
              result.info.logStartOffset,
              result.info.recordErrors,
              result.info.errorMessage
            )
          ) // response status
        }

        actionQueue.add {
          () =>
            allResults.foreach {
              case (topicPartition, result) =>
                val requestKey = TopicPartitionOperationKey(topicPartition)
                result.info.leaderHwChange match {
                  case LeaderHwChange.INCREASED =>
                    // some delayed operations may be unblocked after HW changed
                    delayedProducePurgatory.checkAndComplete(requestKey)
                    delayedFetchPurgatory.checkAndComplete(requestKey)
                    delayedDeleteRecordsPurgatory.checkAndComplete(requestKey)
                  case LeaderHwChange.SAME =>
                    // probably unblock some follower fetch requests since log end offset has been updated
                    delayedFetchPurgatory.checkAndComplete(requestKey)
                  case LeaderHwChange.NONE =>
                  // nothing
                }
            }
        }
        // 尝试更新消息格式转换的指标数据
        recordConversionStatsCallback(localProduceResults.map { case (k, v) => k -> v.info.recordConversionStats })
        // 需要等待其他副本完成写入
        if (delayedProduceRequestRequired(requiredAcks, allEntries, allResults)) {
          // create delayed produce operation
          val produceMetadata = ProduceMetadata(requiredAcks, produceStatus)
          // 创建DelayedProduce延时请求对象
          val delayedProduce = new DelayedProduce(timeout, produceMetadata, this, responseCallback, delayedProduceLock)

          // create a list of (topic, partition) pairs to use as keys for this delayed produce operation
          val producerRequestKeys = allEntries.keys.map(TopicPartitionOperationKey(_)).toSeq

          // try to complete the request immediately, otherwise put it into the purgatory
          // this is because while the delayed produce operation is being created, new
          // requests may arrive and hence make this operation completable.
          // 再一次尝试完成该延时请求
          // 如果暂时无法完成，则将对象放入到相应的Purgatory中等待后续处理
          delayedProducePurgatory.tryCompleteElseWatch(delayedProduce, producerRequestKeys)

        } else { // 无需等待其他副本写入完成，可以立即发送Response
          // we can respond immediately
          val produceResponseStatus = produceStatus.map { case (k, status) => k -> status.responseStatus }
          // 构造INVALID_REQUIRED_ACKS异常并封装进回调函数调用中
          responseCallback(produceResponseStatus)
        }
      }

      if (notYetVerifiedEntriesPerPartition.isEmpty || addPartitionsToTxnManager.isEmpty) {
        appendEntries(verifiedEntriesPerPartition)(Map.empty)
      } else {
        // For unverified entries, send a request to verify. When verified, the append process will proceed via the callback.
        val (error, node) = getTransactionCoordinator(transactionStatePartition.get)

        if (error != Errors.NONE) {
          throw error.exception() // Can throw coordinator not available -- which is retriable
        }

        val topicGrouping = notYetVerifiedEntriesPerPartition.keySet.groupBy(tp => tp.topic())
        val topicCollection = new AddPartitionsToTxnTopicCollection()
        topicGrouping.foreach { case (topic, tps) =>
          topicCollection.add(new AddPartitionsToTxnTopic()
            .setName(topic)
            .setPartitions(tps.map(tp => Integer.valueOf(tp.partition())).toList.asJava))
        }

        // Map not yet verified partitions to a request object.
        // We verify above that all partitions use the same producer ID.
        val batchInfo = notYetVerifiedEntriesPerPartition.head._2.firstBatch()
        val notYetVerifiedTransaction = new AddPartitionsToTxnTransaction()
          .setTransactionalId(transactionalId)
          .setProducerId(batchInfo.producerId())
          .setProducerEpoch(batchInfo.producerEpoch())
          .setVerifyOnly(true)
          .setTopics(topicCollection)

        addPartitionsToTxnManager.foreach(_.addTxnData(node, notYetVerifiedTransaction, KafkaRequestHandler.wrap(appendEntries(entriesPerPartition)(_))))
      }
    } else {
      // If required.acks is outside accepted range, something is wrong with the client
      // Just return an error and don't handle the request at all
      val responseStatus = entriesPerPartition.map { case (topicPartition, _) =>
        topicPartition -> new PartitionResponse(
          Errors.INVALID_REQUIRED_ACKS,
          LogAppendInfo.UNKNOWN_LOG_APPEND_INFO.firstOffset.map[Long](_.messageOffset).orElse(-1L),
          RecordBatch.NO_TIMESTAMP,
          LogAppendInfo.UNKNOWN_LOG_APPEND_INFO.logStartOffset
        )
      }
      responseCallback(responseStatus)
    }
  }

  /**
   * Delete records on leader replicas of the partition, and wait for delete records operation be propagated to other replicas;
   * the callback function will be triggered either when timeout or logStartOffset of all live replicas have reached the specified offset
   */
  private def deleteRecordsOnLocalLog(offsetPerPartition: Map[TopicPartition, Long]): Map[TopicPartition, LogDeleteRecordsResult] = {
    trace("Delete records on local logs to offsets [%s]".format(offsetPerPartition))
    offsetPerPartition.map { case (topicPartition, requestedOffset) =>
      // reject delete records operation on internal topics
      if (Topic.isInternal(topicPartition.topic)) {
        (topicPartition, LogDeleteRecordsResult(-1L, -1L, Some(new InvalidTopicException(s"Cannot delete records of internal topic ${topicPartition.topic}"))))
      } else {
        try {
          val partition = getPartitionOrException(topicPartition)
          val logDeleteResult = partition.deleteRecordsOnLeader(requestedOffset)
          (topicPartition, logDeleteResult)
        } catch {
          case e@ (_: UnknownTopicOrPartitionException |
                   _: NotLeaderOrFollowerException |
                   _: OffsetOutOfRangeException |
                   _: PolicyViolationException |
                   _: KafkaStorageException) =>
            (topicPartition, LogDeleteRecordsResult(-1L, -1L, Some(e)))
          case t: Throwable =>
            error("Error processing delete records operation on partition %s".format(topicPartition), t)
            (topicPartition, LogDeleteRecordsResult(-1L, -1L, Some(t)))
        }
      }
    }
  }

  // If there exists a topic partition that meets the following requirement,
  // we need to put a delayed DeleteRecordsRequest and wait for the delete records operation to complete
  //
  // 1. the delete records operation on this partition is successful
  // 2. low watermark of this partition is smaller than the specified offset
  private def delayedDeleteRecordsRequired(localDeleteRecordsResults: Map[TopicPartition, LogDeleteRecordsResult]): Boolean = {
    localDeleteRecordsResults.exists{ case (_, deleteRecordsResult) =>
      deleteRecordsResult.exception.isEmpty && deleteRecordsResult.lowWatermark < deleteRecordsResult.requestedOffset
    }
  }

  /**
   * For each pair of partition and log directory specified in the map, if the partition has already been created on
   * this broker, move its log files to the specified log directory. Otherwise, record the pair in the memory so that
   * the partition will be created in the specified log directory when broker receives LeaderAndIsrRequest for the partition later.
   */
  def alterReplicaLogDirs(partitionDirs: Map[TopicPartition, String]): Map[TopicPartition, Errors] = {
    replicaStateChangeLock synchronized {
      partitionDirs.map { case (topicPartition, destinationDir) =>
        try {
          /* If the topic name is exceptionally long, we can't support altering the log directory.
           * See KAFKA-4893 for details.
           * TODO: fix this by implementing topic IDs. */
          if (UnifiedLog.logFutureDirName(topicPartition).length > 255)
            throw new InvalidTopicException("The topic name is too long.")
          if (!logManager.isLogDirOnline(destinationDir))
            throw new KafkaStorageException(s"Log directory $destinationDir is offline")

          getPartition(topicPartition) match {
            case HostedPartition.Online(partition) =>
              // Stop current replica movement if the destinationDir is different from the existing destination log directory
              if (partition.futureReplicaDirChanged(destinationDir)) {
                replicaAlterLogDirsManager.removeFetcherForPartitions(Set(topicPartition))
                partition.removeFutureLocalReplica()
              }
            case HostedPartition.Offline =>
              throw new KafkaStorageException(s"Partition $topicPartition is offline")

            case HostedPartition.None => // Do nothing
          }

          // If the log for this partition has not been created yet:
          // 1) Record the destination log directory in the memory so that the partition will be created in this log directory
          //    when broker receives LeaderAndIsrRequest for this partition later.
          // 2) Respond with NotLeaderOrFollowerException for this partition in the AlterReplicaLogDirsResponse
          logManager.maybeUpdatePreferredLogDir(topicPartition, destinationDir)

          // throw NotLeaderOrFollowerException if replica does not exist for the given partition
          val partition = getPartitionOrException(topicPartition)
          val log = partition.localLogOrException
          val topicId = log.topicId

          // If the destinationLDir is different from the current log directory of the replica:
          // - If there is no offline log directory, create the future log in the destinationDir (if it does not exist) and
          //   start ReplicaAlterDirThread to move data of this partition from the current log to the future log
          // - Otherwise, return KafkaStorageException. We do not create the future log while there is offline log directory
          //   so that we can avoid creating future log for the same partition in multiple log directories.
          val highWatermarkCheckpoints = new LazyOffsetCheckpoints(this.highWatermarkCheckpoints)
          if (partition.maybeCreateFutureReplica(destinationDir, highWatermarkCheckpoints)) {
            val futureLog = futureLocalLogOrException(topicPartition)
            logManager.abortAndPauseCleaning(topicPartition)

            val initialFetchState = InitialFetchState(topicId, BrokerEndPoint(config.brokerId, "localhost", -1),
              partition.getLeaderEpoch, futureLog.highWatermark)
            replicaAlterLogDirsManager.addFetcherForPartitions(Map(topicPartition -> initialFetchState))
          }

          (topicPartition, Errors.NONE)
        } catch {
          case e@(_: InvalidTopicException |
                  _: LogDirNotFoundException |
                  _: ReplicaNotAvailableException |
                  _: KafkaStorageException) =>
            warn(s"Unable to alter log dirs for $topicPartition", e)
            (topicPartition, Errors.forException(e))
          case e: NotLeaderOrFollowerException =>
            // Retaining REPLICA_NOT_AVAILABLE exception for ALTER_REPLICA_LOG_DIRS for compatibility
            warn(s"Unable to alter log dirs for $topicPartition", e)
            (topicPartition, Errors.REPLICA_NOT_AVAILABLE)
          case t: Throwable =>
            error("Error while changing replica dir for partition %s".format(topicPartition), t)
            (topicPartition, Errors.forException(t))
        }
      }
    }
  }

  /*
   * Get the LogDirInfo for the specified list of partitions.
   *
   * Each LogDirInfo specifies the following information for a given log directory:
   * 1) Error of the log directory, e.g. whether the log is online or offline
   * 2) size and lag of current and future logs for each partition in the given log directory. Only logs of the queried partitions
   *    are included. There may be future logs (which will replace the current logs of the partition in the future) on the broker after KIP-113 is implemented.
   */
  def describeLogDirs(partitions: Set[TopicPartition]): List[DescribeLogDirsResponseData.DescribeLogDirsResult] = {
    val logsByDir = logManager.allLogs.groupBy(log => log.parentDir)

    config.logDirs.toSet.map { logDir: String =>
      val file = Paths.get(logDir)
      val absolutePath = file.toAbsolutePath.toString
      try {
        if (!logManager.isLogDirOnline(absolutePath))
          throw new KafkaStorageException(s"Log directory $absolutePath is offline")

        val fileStore = Files.getFileStore(file)
        val totalBytes = adjustForLargeFileSystems(fileStore.getTotalSpace)
        val usableBytes = adjustForLargeFileSystems(fileStore.getUsableSpace)
        logsByDir.get(absolutePath) match {
          case Some(logs) =>
            val topicInfos = logs.groupBy(_.topicPartition.topic).map{case (topic, logs) =>
              new DescribeLogDirsResponseData.DescribeLogDirsTopic().setName(topic).setPartitions(
                logs.filter { log =>
                  partitions.contains(log.topicPartition)
                }.map { log =>
                  new DescribeLogDirsResponseData.DescribeLogDirsPartition()
                    .setPartitionSize(log.size)
                    .setPartitionIndex(log.topicPartition.partition)
                    .setOffsetLag(getLogEndOffsetLag(log.topicPartition, log.logEndOffset, log.isFuture))
                    .setIsFutureKey(log.isFuture)
                }.toList.asJava)
            }.toList.asJava

            new DescribeLogDirsResponseData.DescribeLogDirsResult().setLogDir(absolutePath)
              .setErrorCode(Errors.NONE.code).setTopics(topicInfos)
              .setTotalBytes(totalBytes).setUsableBytes(usableBytes)
          case None =>
            new DescribeLogDirsResponseData.DescribeLogDirsResult().setLogDir(absolutePath)
              .setErrorCode(Errors.NONE.code)
              .setTotalBytes(totalBytes).setUsableBytes(usableBytes)
        }

      } catch {
        case e: KafkaStorageException =>
          warn("Unable to describe replica dirs for %s".format(absolutePath), e)
          new DescribeLogDirsResponseData.DescribeLogDirsResult()
            .setLogDir(absolutePath)
            .setErrorCode(Errors.KAFKA_STORAGE_ERROR.code)
        case t: Throwable =>
          error(s"Error while describing replica in dir $absolutePath", t)
          new DescribeLogDirsResponseData.DescribeLogDirsResult()
            .setLogDir(absolutePath)
            .setErrorCode(Errors.forException(t).code)
      }
    }.toList
  }

  // See: https://bugs.openjdk.java.net/browse/JDK-8162520
  def adjustForLargeFileSystems(space: Long): Long = {
    if (space < 0)
      return Long.MaxValue
    space
  }

  def getLogEndOffsetLag(topicPartition: TopicPartition, logEndOffset: Long, isFuture: Boolean): Long = {
    localLog(topicPartition) match {
      case Some(log) =>
        if (isFuture)
          log.logEndOffset - logEndOffset
        else
          math.max(log.highWatermark - logEndOffset, 0)
      case None =>
        // return -1L to indicate that the LEO lag is not available if the replica is not created or is offline
        DescribeLogDirsResponse.INVALID_OFFSET_LAG
    }
  }

  def deleteRecords(timeout: Long,
                    offsetPerPartition: Map[TopicPartition, Long],
                    responseCallback: Map[TopicPartition, DeleteRecordsPartitionResult] => Unit): Unit = {
    val timeBeforeLocalDeleteRecords = time.milliseconds
    val localDeleteRecordsResults = deleteRecordsOnLocalLog(offsetPerPartition)
    debug("Delete records on local log in %d ms".format(time.milliseconds - timeBeforeLocalDeleteRecords))

    val deleteRecordsStatus = localDeleteRecordsResults.map { case (topicPartition, result) =>
      topicPartition ->
        DeleteRecordsPartitionStatus(
          result.requestedOffset, // requested offset
          new DeleteRecordsPartitionResult()
            .setLowWatermark(result.lowWatermark)
            .setErrorCode(result.error.code)
            .setPartitionIndex(topicPartition.partition)) // response status
    }

    if (delayedDeleteRecordsRequired(localDeleteRecordsResults)) {
      // create delayed delete records operation
      val delayedDeleteRecords = new DelayedDeleteRecords(timeout, deleteRecordsStatus, this, responseCallback)

      // create a list of (topic, partition) pairs to use as keys for this delayed delete records operation
      val deleteRecordsRequestKeys = offsetPerPartition.keys.map(TopicPartitionOperationKey(_)).toSeq

      // try to complete the request immediately, otherwise put it into the purgatory
      // this is because while the delayed delete records operation is being created, new
      // requests may arrive and hence make this operation completable.
      delayedDeleteRecordsPurgatory.tryCompleteElseWatch(delayedDeleteRecords, deleteRecordsRequestKeys)
    } else {
      // we can respond immediately
      val deleteRecordsResponseStatus = deleteRecordsStatus.map { case (k, status) => k -> status.responseStatus }
      responseCallback(deleteRecordsResponseStatus)
    }
  }

  // If all the following conditions are true, we need to put a delayed produce request and wait for replication to complete
  //
  // 1. required acks = -1
  // 2. there is data to append
  // 3. at least one partition append was successful (fewer errors than partitions)
  private def delayedProduceRequestRequired(requiredAcks: Short,
                                            entriesPerPartition: Map[TopicPartition, MemoryRecords],
                                            localProduceResults: Map[TopicPartition, LogAppendResult]): Boolean = {
    requiredAcks == -1 &&
    entriesPerPartition.nonEmpty &&
    localProduceResults.values.count(_.exception.isDefined) < entriesPerPartition.size
  }

  private def isValidRequiredAcks(requiredAcks: Short): Boolean = {
    requiredAcks == -1 || requiredAcks == 1 || requiredAcks == 0
  }

  /**
   * Append the messages to the local replica logs
   */
  private def appendToLocalLog(internalTopicsAllowed: Boolean,
                               origin: AppendOrigin,
                               entriesPerPartition: Map[TopicPartition, MemoryRecords],
                               requiredAcks: Short,
                               requestLocal: RequestLocal): Map[TopicPartition, LogAppendResult] = {
    val traceEnabled = isTraceEnabled
    def processFailedRecord(topicPartition: TopicPartition, t: Throwable) = {
      val logStartOffset = onlinePartition(topicPartition).map(_.logStartOffset).getOrElse(-1L)
      brokerTopicStats.topicStats(topicPartition.topic).failedProduceRequestRate.mark()
      brokerTopicStats.allTopicsStats.failedProduceRequestRate.mark()
      error(s"Error processing append operation on partition $topicPartition", t)

      logStartOffset
    }

    if (traceEnabled)
      trace(s"Append [$entriesPerPartition] to local log")

    entriesPerPartition.map { case (topicPartition, records) =>
      brokerTopicStats.topicStats(topicPartition.topic).totalProduceRequestRate.mark()
      brokerTopicStats.allTopicsStats.totalProduceRequestRate.mark()

      // reject appending to internal topics if it is not allowed
      if (Topic.isInternal(topicPartition.topic) && !internalTopicsAllowed) {
        (topicPartition, LogAppendResult(
          LogAppendInfo.UNKNOWN_LOG_APPEND_INFO,
          Some(new InvalidTopicException(s"Cannot append to internal topic ${topicPartition.topic}"))))
      } else {
        try {
          val partition = getPartitionOrException(topicPartition)
          val info = partition.appendRecordsToLeader(records, origin, requiredAcks, requestLocal)
          val numAppendedMessages = info.numMessages

          // update stats for successfully appended bytes and messages as bytesInRate and messageInRate
          brokerTopicStats.topicStats(topicPartition.topic).bytesInRate.mark(records.sizeInBytes)
          brokerTopicStats.allTopicsStats.bytesInRate.mark(records.sizeInBytes)
          brokerTopicStats.topicStats(topicPartition.topic).messagesInRate.mark(numAppendedMessages)
          brokerTopicStats.allTopicsStats.messagesInRate.mark(numAppendedMessages)

          if (traceEnabled)
            trace(s"${records.sizeInBytes} written to log $topicPartition beginning at offset " +
              s"${info.firstOffset.orElse(new LogOffsetMetadata(-1))} and ending at offset ${info.lastOffset}")

          (topicPartition, LogAppendResult(info))
        } catch {
          // NOTE: Failed produce requests metric is not incremented for known exceptions
          // it is supposed to indicate un-expected failures of a broker in handling a produce request
          case e@ (_: UnknownTopicOrPartitionException |
                   _: NotLeaderOrFollowerException |
                   _: RecordTooLargeException |
                   _: RecordBatchTooLargeException |
                   _: CorruptRecordException |
                   _: KafkaStorageException) =>
            (topicPartition, LogAppendResult(LogAppendInfo.UNKNOWN_LOG_APPEND_INFO, Some(e)))
          case rve: RecordValidationException =>
            val logStartOffset = processFailedRecord(topicPartition, rve.invalidException)
            val recordErrors = rve.recordErrors
            (topicPartition, LogAppendResult(LogAppendInfo.unknownLogAppendInfoWithAdditionalInfo(
              logStartOffset, recordErrors, rve.invalidException.getMessage), Some(rve.invalidException)))
          case t: Throwable =>
            val logStartOffset = processFailedRecord(topicPartition, t)
            (topicPartition, LogAppendResult(LogAppendInfo.unknownLogAppendInfoWithLogStartOffset(logStartOffset), Some(t)))
        }
      }
    }
  }

  def fetchOffsetForTimestamp(topicPartition: TopicPartition,
                              timestamp: Long,
                              isolationLevel: Option[IsolationLevel],
                              currentLeaderEpoch: Optional[Integer],
                              fetchOnlyFromLeader: Boolean): Option[TimestampAndOffset] = {
    val partition = getPartitionOrException(topicPartition)
    partition.fetchOffsetForTimestamp(timestamp, isolationLevel, currentLeaderEpoch, fetchOnlyFromLeader)
  }

  def legacyFetchOffsetsForTimestamp(topicPartition: TopicPartition,
                                     timestamp: Long,
                                     maxNumOffsets: Int,
                                     isFromConsumer: Boolean,
                                     fetchOnlyFromLeader: Boolean): Seq[Long] = {
    val partition = getPartitionOrException(topicPartition)
    partition.legacyFetchOffsetsForTimestamp(timestamp, maxNumOffsets, isFromConsumer, fetchOnlyFromLeader)
  }

  /**
   * Fetch messages from a replica, and wait until enough data can be fetched and return;
   * the callback function will be triggered either when timeout or required fetch info is satisfied.
   * Consumers may fetch from any replica, but followers can only fetch from the leader.
   * 在 ReplicaManager 类中，负责读取副本数据的方法是 fetchMessages。
   * 不论是 Java 消费者 API，还是 Follower 副本，它们拉取消息的主要途径都是向 Broker 发送 FETCH 请求，Broker 端接收到该请求后，调用 fetchMessages 方法从底层的 Leader 副本取出消息。
   * 和 appendRecords 方法类似，fetchMessages 方法也可能会延时处理 FETCH 请求，因为 Broker 端必须要累积足够多的数据之后，才会返回 Response 给请求发送方。
   */
  def fetchMessages(
    params: FetchParams,
    fetchInfos: Seq[(TopicIdPartition, PartitionData)], //规定了读取分区的信息，比如要读取哪些分区、从这些分区的哪个位移值开始读、最多可以读多少字节，等等。
    quota: ReplicaQuota, //这是一个配额控制类，主要是为了判断是否需要在读取的过程中做限速控制。
    responseCallback: Seq[(TopicIdPartition, FetchPartitionData)] => Unit //Response 回调逻辑函数。当请求被处理完成后，调用该方法执行收尾逻辑。
  ): Unit = {
    // check if this fetch request can be satisfied right away
    val logReadResults = readFromLocalLog(params, fetchInfos, quota, readFromPurgatory = false)
    var bytesReadable: Long = 0
    var errorReadingData = false
    var hasDivergingEpoch = false
    var hasPreferredReadReplica = false
    val logReadResultMap = new mutable.HashMap[TopicIdPartition, LogReadResult]
    // 统计总共可读取的字节数
    logReadResults.foreach { case (topicIdPartition, logReadResult) =>
      brokerTopicStats.topicStats(topicIdPartition.topicPartition.topic).totalFetchRequestRate.mark()
      brokerTopicStats.allTopicsStats.totalFetchRequestRate.mark()
      if (logReadResult.error != Errors.NONE)
        errorReadingData = true
      if (logReadResult.divergingEpoch.nonEmpty)
        hasDivergingEpoch = true
      if (logReadResult.preferredReadReplica.nonEmpty)
        hasPreferredReadReplica = true
      bytesReadable = bytesReadable + logReadResult.info.records.sizeInBytes
      logReadResultMap.put(topicIdPartition, logReadResult)
    }

    // respond immediately if 1) fetch request does not want to wait //请求没有设置超时时间，说明请求方想让请求被处理后立即返回
    //                        2) fetch request does not require any data //未获取到任何数据
    //                        3) has enough data to respond //已累积到足够多的数据
    //                        4) some error happens while reading data // 读取过程中出错
    //                        5) we found a diverging epoch
    //                        6) has a preferred read replica
    if (params.maxWaitMs <= 0 || fetchInfos.isEmpty || bytesReadable >= params.minBytes || errorReadingData ||
      hasDivergingEpoch || hasPreferredReadReplica) {
      // 构建返回结果
      val fetchPartitionData = logReadResults.map { case (tp, result) =>
        val isReassignmentFetch = params.isFromFollower && isAddingReplica(tp.topicPartition, params.replicaId)
        tp -> result.toFetchPartitionData(isReassignmentFetch)
      }
      // 调用回调函数
      responseCallback(fetchPartitionData)
    } else {
      // construct the fetch results from the read results
      val fetchPartitionStatus = new mutable.ArrayBuffer[(TopicIdPartition, FetchPartitionStatus)]
      fetchInfos.foreach { case (topicIdPartition, partitionData) =>
        logReadResultMap.get(topicIdPartition).foreach(logReadResult => {
          val logOffsetMetadata = logReadResult.info.fetchOffsetMetadata
          fetchPartitionStatus += (topicIdPartition -> FetchPartitionStatus(logOffsetMetadata, partitionData))
        })
      }
      // 构建DelayedFetch延时请求对象
      val delayedFetch = new DelayedFetch(
        params = params,
        fetchPartitionStatus = fetchPartitionStatus,
        replicaManager = this,
        quota = quota,
        responseCallback = responseCallback
      )

      // create a list of (topic, partition) pairs to use as keys for this delayed fetch operation
      val delayedFetchKeys = fetchPartitionStatus.map { case (tp, _) => TopicPartitionOperationKey(tp) }

      // try to complete the request immediately, otherwise put it into the purgatory;
      // this is because while the delayed fetch operation is being created, new requests
      // may arrive and hence make this operation completable.
      // 再一次尝试完成请求，如果依然不能完成，则交由Purgatory等待后续处理
      delayedFetchPurgatory.tryCompleteElseWatch(delayedFetch, delayedFetchKeys)
    }
  }

  /**
   * Read from multiple topic partitions at the given offset up to maxSize bytes
   *  定义readFromLog方法读取底层日志中的消息
   */
  def readFromLocalLog(
    params: FetchParams,
    readPartitionInfo: Seq[(TopicIdPartition, PartitionData)],
    quota: ReplicaQuota,
    readFromPurgatory: Boolean
  ): Seq[(TopicIdPartition, LogReadResult)] = {
    val traceEnabled = isTraceEnabled

    def read(tp: TopicIdPartition, fetchInfo: PartitionData, limitBytes: Int, minOneMessage: Boolean): LogReadResult = {
      val offset = fetchInfo.fetchOffset
      val partitionFetchSize = fetchInfo.maxBytes
      val followerLogStartOffset = fetchInfo.logStartOffset

      val adjustedMaxBytes = math.min(fetchInfo.maxBytes, limitBytes)
      try {
        if (traceEnabled)
          trace(s"Fetching log segment for partition $tp, offset $offset, partition fetch size $partitionFetchSize, " +
            s"remaining response limit $limitBytes" +
            (if (minOneMessage) s", ignoring response/partition size limits" else ""))

        val partition = getPartitionOrException(tp.topicPartition)
        val fetchTimeMs = time.milliseconds

        // Check if topic ID from the fetch request/session matches the ID in the log
        val topicId = if (tp.topicId == Uuid.ZERO_UUID) None else Some(tp.topicId)
        if (!hasConsistentTopicId(topicId, partition.topicId))
          throw new InconsistentTopicIdException("Topic ID in the fetch session did not match the topic ID in the log.")

        // If we are the leader, determine the preferred read-replica
        val preferredReadReplica = params.clientMetadata.asScala.flatMap(
          metadata => findPreferredReadReplica(partition, metadata, params.replicaId, fetchInfo.fetchOffset, fetchTimeMs))

        if (preferredReadReplica.isDefined) {
          replicaSelectorOpt.foreach { selector =>
            debug(s"Replica selector ${selector.getClass.getSimpleName} returned preferred replica " +
              s"${preferredReadReplica.get} for ${params.clientMetadata}")
          }
          // If a preferred read-replica is set, skip the read
          val offsetSnapshot = partition.fetchOffsetSnapshot(fetchInfo.currentLeaderEpoch, fetchOnlyFromLeader = false)
          LogReadResult(info = new FetchDataInfo(LogOffsetMetadata.UNKNOWN_OFFSET_METADATA, MemoryRecords.EMPTY),
            divergingEpoch = None,
            highWatermark = offsetSnapshot.highWatermark.messageOffset,
            leaderLogStartOffset = offsetSnapshot.logStartOffset,
            leaderLogEndOffset = offsetSnapshot.logEndOffset.messageOffset,
            followerLogStartOffset = followerLogStartOffset,
            fetchTimeMs = -1L,
            lastStableOffset = Some(offsetSnapshot.lastStableOffset.messageOffset),
            preferredReadReplica = preferredReadReplica,
            exception = None)
        } else {
          // Try the read first, this tells us whether we need all of adjustedFetchSize for this partition
          val readInfo: LogReadInfo = partition.fetchRecords(
            fetchParams = params,
            fetchPartitionData = fetchInfo,
            fetchTimeMs = fetchTimeMs,
            maxBytes = adjustedMaxBytes,
            minOneMessage = minOneMessage,
            updateFetchState = !readFromPurgatory
          )

          val fetchDataInfo = if (params.isFromFollower && shouldLeaderThrottle(quota, partition, params.replicaId)) {
            // If the partition is being throttled, simply return an empty set.
            new FetchDataInfo(readInfo.fetchedData.fetchOffsetMetadata, MemoryRecords.EMPTY)
          } else if (!params.hardMaxBytesLimit && readInfo.fetchedData.firstEntryIncomplete) {
            // For FetchRequest version 3, we replace incomplete message sets with an empty one as consumers can make
            // progress in such cases and don't need to report a `RecordTooLargeException`
            new FetchDataInfo(readInfo.fetchedData.fetchOffsetMetadata, MemoryRecords.EMPTY)
          } else {
            readInfo.fetchedData
          }

          LogReadResult(info = fetchDataInfo,
            divergingEpoch = readInfo.divergingEpoch.asScala,
            highWatermark = readInfo.highWatermark,
            leaderLogStartOffset = readInfo.logStartOffset,
            leaderLogEndOffset = readInfo.logEndOffset,
            followerLogStartOffset = followerLogStartOffset,
            fetchTimeMs = fetchTimeMs,
            lastStableOffset = Some(readInfo.lastStableOffset),
            preferredReadReplica = preferredReadReplica,
            exception = None
          )
        }
      } catch {
        // NOTE: Failed fetch requests metric is not incremented for known exceptions since it
        // is supposed to indicate un-expected failure of a broker in handling a fetch request
        case e@ (_: UnknownTopicOrPartitionException |
                 _: NotLeaderOrFollowerException |
                 _: UnknownLeaderEpochException |
                 _: FencedLeaderEpochException |
                 _: ReplicaNotAvailableException |
                 _: KafkaStorageException |
                 _: OffsetOutOfRangeException |
                 _: InconsistentTopicIdException) =>
          LogReadResult(info = new FetchDataInfo(LogOffsetMetadata.UNKNOWN_OFFSET_METADATA, MemoryRecords.EMPTY),
            divergingEpoch = None,
            highWatermark = UnifiedLog.UnknownOffset,
            leaderLogStartOffset = UnifiedLog.UnknownOffset,
            leaderLogEndOffset = UnifiedLog.UnknownOffset,
            followerLogStartOffset = UnifiedLog.UnknownOffset,
            fetchTimeMs = -1L,
            lastStableOffset = None,
            exception = Some(e))
        case e: Throwable =>
          brokerTopicStats.topicStats(tp.topic).failedFetchRequestRate.mark()
          brokerTopicStats.allTopicsStats.failedFetchRequestRate.mark()

          val fetchSource = FetchRequest.describeReplicaId(params.replicaId)
          error(s"Error processing fetch with max size $adjustedMaxBytes from $fetchSource " +
            s"on partition $tp: $fetchInfo", e)

          LogReadResult(info = new FetchDataInfo(LogOffsetMetadata.UNKNOWN_OFFSET_METADATA, MemoryRecords.EMPTY),
            divergingEpoch = None,
            highWatermark = UnifiedLog.UnknownOffset,
            leaderLogStartOffset = UnifiedLog.UnknownOffset,
            leaderLogEndOffset = UnifiedLog.UnknownOffset,
            followerLogStartOffset = UnifiedLog.UnknownOffset,
            fetchTimeMs = -1L,
            lastStableOffset = None,
            exception = Some(e)
          )
      }
    }

    var limitBytes = params.maxBytes
    val result = new mutable.ArrayBuffer[(TopicIdPartition, LogReadResult)]
    var minOneMessage = !params.hardMaxBytesLimit
    readPartitionInfo.foreach { case (tp, fetchInfo) =>
      val readResult = read(tp, fetchInfo, limitBytes, minOneMessage)
      val recordBatchSize = readResult.info.records.sizeInBytes
      // Once we read from a non-empty partition, we stop ignoring request and partition level size limits
      if (recordBatchSize > 0)
        minOneMessage = false
      limitBytes = math.max(0, limitBytes - recordBatchSize)
      result += (tp -> readResult)
    }
    result
  }

  /**
    * Using the configured [[ReplicaSelector]], determine the preferred read replica for a partition given the
    * client metadata, the requested offset, and the current set of replicas. If the preferred read replica is the
    * leader, return None
    */
  def findPreferredReadReplica(partition: Partition,
                               clientMetadata: ClientMetadata,
                               replicaId: Int,
                               fetchOffset: Long,
                               currentTimeMs: Long): Option[Int] = {
    partition.leaderIdIfLocal.flatMap { leaderReplicaId =>
      // Don't look up preferred for follower fetches via normal replication
      if (FetchRequest.isValidBrokerId(replicaId))
        None
      else {
        replicaSelectorOpt.flatMap { replicaSelector =>
          val replicaEndpoints = metadataCache.getPartitionReplicaEndpoints(partition.topicPartition,
            new ListenerName(clientMetadata.listenerName))
          val replicaInfoSet = mutable.Set[ReplicaView]()

          partition.remoteReplicas.foreach { replica =>
            val replicaState = replica.stateSnapshot
            // Exclude replicas that are not in the ISR as the follower may lag behind. Worst case, the follower
            // will continue to lag and the consumer will fall behind the produce. The leader will
            // continuously pick the lagging follower when the consumer refreshes its preferred read replica.
            // This can go on indefinitely.
            if (partition.inSyncReplicaIds.contains(replica.brokerId) &&
                replicaState.logEndOffset >= fetchOffset &&
                replicaState.logStartOffset <= fetchOffset) {

              replicaInfoSet.add(new DefaultReplicaView(
                replicaEndpoints.getOrElse(replica.brokerId, Node.noNode()),
                replicaState.logEndOffset,
                currentTimeMs - replicaState.lastCaughtUpTimeMs
              ))
            }
          }

          val leaderReplica = new DefaultReplicaView(
            replicaEndpoints.getOrElse(leaderReplicaId, Node.noNode()),
            partition.localLogOrException.logEndOffset,
            0L
          )
          replicaInfoSet.add(leaderReplica)

          val partitionInfo = new DefaultPartitionView(replicaInfoSet.asJava, leaderReplica)
          replicaSelector.select(partition.topicPartition, clientMetadata, partitionInfo).asScala.collect {
            // Even though the replica selector can return the leader, we don't want to send it out with the
            // FetchResponse, so we exclude it here
            case selected if !selected.endpoint.isEmpty && selected != leaderReplica => selected.endpoint.id
          }
        }
      }
    }
  }

  /**
   *  To avoid ISR thrashing, we only throttle a replica on the leader if it's in the throttled replica list,
   *  the quota is exceeded and the replica is not in sync.
   */
  def shouldLeaderThrottle(quota: ReplicaQuota, partition: Partition, replicaId: Int): Boolean = {
    val isReplicaInSync = partition.inSyncReplicaIds.contains(replicaId)
    !isReplicaInSync && quota.isThrottled(partition.topicPartition) && quota.isQuotaExceeded
  }

  def getLogConfig(topicPartition: TopicPartition): Option[LogConfig] = localLog(topicPartition).map(_.config)

  def getMagic(topicPartition: TopicPartition): Option[Byte] = getLogConfig(topicPartition).map(_.recordVersion.value)

  def maybeUpdateMetadataCache(correlationId: Int, updateMetadataRequest: UpdateMetadataRequest) : Seq[TopicPartition] =  {
    replicaStateChangeLock synchronized {
      if (updateMetadataRequest.controllerEpoch < controllerEpoch) {
        val stateControllerEpochErrorMessage = s"Received update metadata request with correlation id $correlationId " +
          s"from an old controller ${updateMetadataRequest.controllerId} with epoch ${updateMetadataRequest.controllerEpoch}. " +
          s"Latest known controller epoch is $controllerEpoch"
        stateChangeLogger.warn(stateControllerEpochErrorMessage)
        throw new ControllerMovedException(stateChangeLogger.messageWithPrefix(stateControllerEpochErrorMessage))
      } else {
        val zkMetadataCache = metadataCache.asInstanceOf[ZkMetadataCache]
        val deletedPartitions = zkMetadataCache.updateMetadata(correlationId, updateMetadataRequest)
        controllerEpoch = updateMetadataRequest.controllerEpoch
        deletedPartitions
      }
    }
  }

  def becomeLeaderOrFollower(correlationId: Int,
                             leaderAndIsrRequest: LeaderAndIsrRequest,
                             onLeadershipChange: (Iterable[Partition], Iterable[Partition]) => Unit): LeaderAndIsrResponse = {
    val startMs = time.milliseconds()
    replicaStateChangeLock synchronized {
      val controllerId = leaderAndIsrRequest.controllerId
      val requestPartitionStates = leaderAndIsrRequest.partitionStates.asScala
      stateChangeLogger.info(s"Handling LeaderAndIsr request correlationId $correlationId from controller " +
        s"$controllerId for ${requestPartitionStates.size} partitions")
      if (stateChangeLogger.isTraceEnabled)
        requestPartitionStates.foreach { partitionState =>
          stateChangeLogger.trace(s"Received LeaderAndIsr request $partitionState " +
            s"correlation id $correlationId from controller $controllerId " +
            s"epoch ${leaderAndIsrRequest.controllerEpoch}")
        }
      val topicIds = leaderAndIsrRequest.topicIds()
      def topicIdFromRequest(topicName: String): Option[Uuid] = {
        val topicId = topicIds.get(topicName)
        // if invalid topic ID return None
        if (topicId == null || topicId == Uuid.ZERO_UUID)
          None
        else
          Some(topicId)
      }

      val response = {
        if (leaderAndIsrRequest.controllerEpoch < controllerEpoch) {
          stateChangeLogger.warn(s"Ignoring LeaderAndIsr request from controller $controllerId with " +
            s"correlation id $correlationId since its controller epoch ${leaderAndIsrRequest.controllerEpoch} is old. " +
            s"Latest known controller epoch is $controllerEpoch")
          leaderAndIsrRequest.getErrorResponse(0, Errors.STALE_CONTROLLER_EPOCH.exception)
        } else {
          val responseMap = new mutable.HashMap[TopicPartition, Errors]
          controllerEpoch = leaderAndIsrRequest.controllerEpoch

          val partitions = new mutable.HashSet[Partition]()
          val partitionsToBeLeader = new mutable.HashMap[Partition, LeaderAndIsrPartitionState]()
          val partitionsToBeFollower = new mutable.HashMap[Partition, LeaderAndIsrPartitionState]()
          val topicIdUpdateFollowerPartitions = new mutable.HashSet[Partition]()

          // First create the partition if it doesn't exist already
          requestPartitionStates.foreach { partitionState =>
            val topicPartition = new TopicPartition(partitionState.topicName, partitionState.partitionIndex)
            val partitionOpt = getPartition(topicPartition) match {
              case HostedPartition.Offline =>
                stateChangeLogger.warn(s"Ignoring LeaderAndIsr request from " +
                  s"controller $controllerId with correlation id $correlationId " +
                  s"epoch $controllerEpoch for partition $topicPartition as the local replica for the " +
                  "partition is in an offline log directory")
                responseMap.put(topicPartition, Errors.KAFKA_STORAGE_ERROR)
                None

              case HostedPartition.Online(partition) =>
                Some(partition)

              case HostedPartition.None =>
                val partition = Partition(topicPartition, time, this)
                allPartitions.putIfNotExists(topicPartition, HostedPartition.Online(partition))
                Some(partition)
            }

            // Next check the topic ID and the partition's leader epoch
            partitionOpt.foreach { partition =>
              val currentLeaderEpoch = partition.getLeaderEpoch
              val requestLeaderEpoch = partitionState.leaderEpoch
              val requestTopicId = topicIdFromRequest(topicPartition.topic)
              val logTopicId = partition.topicId

              if (!hasConsistentTopicId(requestTopicId, logTopicId)) {
                stateChangeLogger.error(s"Topic ID in memory: ${logTopicId.get} does not" +
                  s" match the topic ID for partition $topicPartition received: " +
                  s"${requestTopicId.get}.")
                responseMap.put(topicPartition, Errors.INCONSISTENT_TOPIC_ID)
              } else if (requestLeaderEpoch > currentLeaderEpoch) {
                // If the leader epoch is valid record the epoch of the controller that made the leadership decision.
                // This is useful while updating the isr to maintain the decision maker controller's epoch in the zookeeper path
                if (partitionState.replicas.contains(localBrokerId)) {
                  partitions += partition
                  if (partitionState.leader == localBrokerId) {
                    partitionsToBeLeader.put(partition, partitionState)
                  } else {
                    partitionsToBeFollower.put(partition, partitionState)
                  }
                } else {
                  stateChangeLogger.warn(s"Ignoring LeaderAndIsr request from controller $controllerId with " +
                    s"correlation id $correlationId epoch $controllerEpoch for partition $topicPartition as itself is not " +
                    s"in assigned replica list ${partitionState.replicas.asScala.mkString(",")}")
                  responseMap.put(topicPartition, Errors.UNKNOWN_TOPIC_OR_PARTITION)
                }
              } else if (requestLeaderEpoch < currentLeaderEpoch) {
                stateChangeLogger.warn(s"Ignoring LeaderAndIsr request from " +
                  s"controller $controllerId with correlation id $correlationId " +
                  s"epoch $controllerEpoch for partition $topicPartition since its associated " +
                  s"leader epoch $requestLeaderEpoch is smaller than the current " +
                  s"leader epoch $currentLeaderEpoch")
                responseMap.put(topicPartition, Errors.STALE_CONTROLLER_EPOCH)
              } else {
                val error = requestTopicId match {
                  case Some(topicId) if logTopicId.isEmpty =>
                    // The controller may send LeaderAndIsr to upgrade to using topic IDs without bumping the epoch.
                    // If we have a matching epoch, we expect the log to be defined.
                    val log = localLogOrException(partition.topicPartition)
                    log.assignTopicId(topicId)
                    stateChangeLogger.info(s"Updating log for $topicPartition to assign topic ID " +
                      s"$topicId from LeaderAndIsr request from controller $controllerId with correlation " +
                      s"id $correlationId epoch $controllerEpoch")
                    if (partitionState.leader != localBrokerId)
                      topicIdUpdateFollowerPartitions.add(partition)
                    Errors.NONE
                  case None if logTopicId.isDefined && partitionState.leader != localBrokerId =>
                    // If we have a topic ID in the log but not in the request, we must have previously had topic IDs but
                    // are now downgrading. If we are a follower, remove the topic ID from the PartitionFetchState.
                    stateChangeLogger.info(s"Updating PartitionFetchState for $topicPartition to remove log topic ID " +
                      s"${logTopicId.get} since LeaderAndIsr request from controller $controllerId with correlation " +
                      s"id $correlationId epoch $controllerEpoch did not contain a topic ID")
                    topicIdUpdateFollowerPartitions.add(partition)
                    Errors.NONE
                  case _ =>
                    stateChangeLogger.info(s"Ignoring LeaderAndIsr request from " +
                      s"controller $controllerId with correlation id $correlationId " +
                      s"epoch $controllerEpoch for partition $topicPartition since its associated " +
                      s"leader epoch $requestLeaderEpoch matches the current leader epoch")
                    Errors.STALE_CONTROLLER_EPOCH
                }
                responseMap.put(topicPartition, error)
              }
            }
          }

          val highWatermarkCheckpoints = new LazyOffsetCheckpoints(this.highWatermarkCheckpoints)
          val partitionsBecomeLeader = if (partitionsToBeLeader.nonEmpty)
            makeLeaders(controllerId, controllerEpoch, partitionsToBeLeader, correlationId, responseMap,
              highWatermarkCheckpoints, topicIdFromRequest)
          else
            Set.empty[Partition]
          val partitionsBecomeFollower = if (partitionsToBeFollower.nonEmpty)
            makeFollowers(controllerId, controllerEpoch, partitionsToBeFollower, correlationId, responseMap,
              highWatermarkCheckpoints, topicIdFromRequest)
          else
            Set.empty[Partition]

          val followerTopicSet = partitionsBecomeFollower.map(_.topic).toSet
          updateLeaderAndFollowerMetrics(followerTopicSet)

          if (topicIdUpdateFollowerPartitions.nonEmpty)
            updateTopicIdForFollowers(controllerId, controllerEpoch, topicIdUpdateFollowerPartitions, correlationId, topicIdFromRequest)

          // We initialize highwatermark thread after the first LeaderAndIsr request. This ensures that all the partitions
          // have been completely populated before starting the checkpointing there by avoiding weird race conditions
          startHighWatermarkCheckPointThread()

          maybeAddLogDirFetchers(partitions, highWatermarkCheckpoints, topicIdFromRequest)

          replicaFetcherManager.shutdownIdleFetcherThreads()
          replicaAlterLogDirsManager.shutdownIdleFetcherThreads()

          remoteLogManager.foreach(rlm => rlm.onLeadershipChange(partitionsBecomeLeader.asJava, partitionsBecomeFollower.asJava, topicIds))

          onLeadershipChange(partitionsBecomeLeader, partitionsBecomeFollower)

          val data = new LeaderAndIsrResponseData().setErrorCode(Errors.NONE.code)
          if (leaderAndIsrRequest.version < 5) {
            responseMap.forKeyValue { (tp, error) =>
              data.partitionErrors.add(new LeaderAndIsrPartitionError()
                .setTopicName(tp.topic)
                .setPartitionIndex(tp.partition)
                .setErrorCode(error.code))
            }
          } else {
            responseMap.forKeyValue { (tp, error) =>
              val topicId = topicIds.get(tp.topic)
              var topic = data.topics.find(topicId)
              if (topic == null) {
                topic = new LeaderAndIsrTopicError().setTopicId(topicId)
                data.topics.add(topic)
              }
              topic.partitionErrors.add(new LeaderAndIsrPartitionError()
                .setPartitionIndex(tp.partition)
                .setErrorCode(error.code))
            }
          }
          new LeaderAndIsrResponse(data, leaderAndIsrRequest.version)
        }
      }
      val endMs = time.milliseconds()
      val elapsedMs = endMs - startMs
      stateChangeLogger.info(s"Finished LeaderAndIsr request in ${elapsedMs}ms correlationId $correlationId from controller " +
        s"$controllerId for ${requestPartitionStates.size} partitions")
      response
    }
  }

  /**
   * Checks if the topic ID provided in the request is consistent with the topic ID in the log.
   * When using this method to handle a Fetch request, the topic ID may have been provided by an earlier request.
   *
   * If the request had an invalid topic ID (null or zero), then we assume that topic IDs are not supported.
   * The topic ID was not inconsistent, so return true.
   * If the log does not exist or the topic ID is not yet set, logTopicIdOpt will be None.
   * In both cases, the ID is not inconsistent so return true.
   *
   * @param requestTopicIdOpt the topic ID from the request if it exists
   * @param logTopicIdOpt the topic ID in the log if the log and the topic ID exist
   * @return true if the request topic id is consistent, false otherwise
   */
  private def hasConsistentTopicId(requestTopicIdOpt: Option[Uuid], logTopicIdOpt: Option[Uuid]): Boolean = {
    requestTopicIdOpt match {
      case None => true
      case Some(requestTopicId) => logTopicIdOpt.isEmpty || logTopicIdOpt.contains(requestTopicId)
    }
  }

  /**
   * KAFKA-8392
   * For topic partitions of which the broker is no longer a leader, delete metrics related to
   * those topics. Note that this means the broker stops being either a replica or a leader of
   * partitions of said topics
   */
  protected def updateLeaderAndFollowerMetrics(newFollowerTopics: Set[String]): Unit = {
    val leaderTopicSet = leaderPartitionsIterator.map(_.topic).toSet
    newFollowerTopics.diff(leaderTopicSet).foreach(brokerTopicStats.removeOldLeaderMetrics)

    // remove metrics for brokers which are not followers of a topic
    leaderTopicSet.diff(newFollowerTopics).foreach(brokerTopicStats.removeOldFollowerMetrics)
  }

  protected[server] def maybeAddLogDirFetchers(partitions: Set[Partition],
                                       offsetCheckpoints: OffsetCheckpoints,
                                       topicIds: String => Option[Uuid]): Unit = {
    val futureReplicasAndInitialOffset = new mutable.HashMap[TopicPartition, InitialFetchState]
    for (partition <- partitions) {
      val topicPartition = partition.topicPartition
      logManager.getLog(topicPartition, isFuture = true).foreach { futureLog =>
        partition.log.foreach { _ =>
          val leader = BrokerEndPoint(config.brokerId, "localhost", -1)

          // Add future replica log to partition's map
          partition.createLogIfNotExists(
            isNew = false,
            isFutureReplica = true,
            offsetCheckpoints,
            topicIds(partition.topic))

          // pause cleaning for partitions that are being moved and start ReplicaAlterDirThread to move
          // replica from source dir to destination dir
          logManager.abortAndPauseCleaning(topicPartition)

          futureReplicasAndInitialOffset.put(topicPartition, InitialFetchState(topicIds(topicPartition.topic), leader,
            partition.getLeaderEpoch, futureLog.highWatermark))
        }
      }
    }

    if (futureReplicasAndInitialOffset.nonEmpty)
      replicaAlterLogDirsManager.addFetcherForPartitions(futureReplicasAndInitialOffset)
  }

  /*
   * Make the current broker to become leader for a given set of partitions by:
   *
   * 1. Stop fetchers for these partitions
   * 2. Update the partition metadata in cache
   * 3. Add these partitions to the leader partitions set
   *
   * If an unexpected error is thrown in this function, it will be propagated to KafkaApis where
   * the error message will be set on each partition since we do not know which partition caused it. Otherwise,
   * return the set of partitions that are made leader due to this method
   *
   *  TODO: the above may need to be fixed later
   */
  private def makeLeaders(controllerId: Int,
                          controllerEpoch: Int,
                          partitionStates: Map[Partition, LeaderAndIsrPartitionState],
                          correlationId: Int,
                          responseMap: mutable.Map[TopicPartition, Errors],
                          highWatermarkCheckpoints: OffsetCheckpoints,
                          topicIds: String => Option[Uuid]): Set[Partition] = {
    val traceEnabled = stateChangeLogger.isTraceEnabled
    partitionStates.keys.foreach { partition =>
      if (traceEnabled)
        stateChangeLogger.trace(s"Handling LeaderAndIsr request correlationId $correlationId from " +
          s"controller $controllerId epoch $controllerEpoch starting the become-leader transition for " +
          s"partition ${partition.topicPartition}")
      responseMap.put(partition.topicPartition, Errors.NONE)
    }

    val partitionsToMakeLeaders = mutable.Set[Partition]()

    try {
      // First stop fetchers for all the partitions
      replicaFetcherManager.removeFetcherForPartitions(partitionStates.keySet.map(_.topicPartition))
      stateChangeLogger.info(s"Stopped fetchers as part of LeaderAndIsr request correlationId $correlationId from " +
        s"controller $controllerId epoch $controllerEpoch as part of the become-leader transition for " +
        s"${partitionStates.size} partitions")
      // Update the partition information to be the leader
      partitionStates.forKeyValue { (partition, partitionState) =>
        try {
          if (partition.makeLeader(partitionState, highWatermarkCheckpoints, topicIds(partitionState.topicName))) {
            partitionsToMakeLeaders += partition
          }
        } catch {
          case e: KafkaStorageException =>
            stateChangeLogger.error(s"Skipped the become-leader state change with " +
              s"correlation id $correlationId from controller $controllerId epoch $controllerEpoch for partition ${partition.topicPartition} " +
              s"(last update controller epoch ${partitionState.controllerEpoch}) since " +
              s"the replica for the partition is offline due to storage error $e")
            // If there is an offline log directory, a Partition object may have been created and have been added
            // to `ReplicaManager.allPartitions` before `createLogIfNotExists()` failed to create local replica due
            // to KafkaStorageException. In this case `ReplicaManager.allPartitions` will map this topic-partition
            // to an empty Partition object. We need to map this topic-partition to OfflinePartition instead.
            markPartitionOffline(partition.topicPartition)
            responseMap.put(partition.topicPartition, Errors.KAFKA_STORAGE_ERROR)
        }
      }

    } catch {
      case e: Throwable =>
        partitionStates.keys.foreach { partition =>
          stateChangeLogger.error(s"Error while processing LeaderAndIsr request correlationId $correlationId received " +
            s"from controller $controllerId epoch $controllerEpoch for partition ${partition.topicPartition}", e)
        }
        // Re-throw the exception for it to be caught in KafkaApis
        throw e
    }

    if (traceEnabled)
      partitionStates.keys.foreach { partition =>
        stateChangeLogger.trace(s"Completed LeaderAndIsr request correlationId $correlationId from controller $controllerId " +
          s"epoch $controllerEpoch for the become-leader transition for partition ${partition.topicPartition}")
      }

    partitionsToMakeLeaders
  }

  /*
   * Make the current broker to become follower for a given set of partitions by:
   *
   * 1. Remove these partitions from the leader partitions set.
   * 2. Mark the replicas as followers so that no more data can be added from the producer clients.
   * 3. Stop fetchers for these partitions so that no more data can be added by the replica fetcher threads.
   * 4. Truncate the log and checkpoint offsets for these partitions.
   * 5. Clear the produce and fetch requests in the purgatory
   * 6. If the broker is not shutting down, add the fetcher to the new leaders.
   *
   * The ordering of doing these steps make sure that the replicas in transition will not
   * take any more messages before checkpointing offsets so that all messages before the checkpoint
   * are guaranteed to be flushed to disks
   *
   * If an unexpected error is thrown in this function, it will be propagated to KafkaApis where
   * the error message will be set on each partition since we do not know which partition caused it. Otherwise,
   * return the set of partitions that are made follower due to this method
   */
  private def makeFollowers(controllerId: Int,
                            controllerEpoch: Int,
                            partitionStates: Map[Partition, LeaderAndIsrPartitionState],
                            correlationId: Int,
                            responseMap: mutable.Map[TopicPartition, Errors],
                            highWatermarkCheckpoints: OffsetCheckpoints,
                            topicIds: String => Option[Uuid]) : Set[Partition] = {
    val traceLoggingEnabled = stateChangeLogger.isTraceEnabled
    partitionStates.forKeyValue { (partition, partitionState) =>
      if (traceLoggingEnabled)
        stateChangeLogger.trace(s"Handling LeaderAndIsr request correlationId $correlationId from controller $controllerId " +
          s"epoch $controllerEpoch starting the become-follower transition for partition ${partition.topicPartition} with leader " +
          s"${partitionState.leader}")
      responseMap.put(partition.topicPartition, Errors.NONE)
    }

    val partitionsToMakeFollower: mutable.Set[Partition] = mutable.Set()
    try {
      partitionStates.forKeyValue { (partition, partitionState) =>
        val newLeaderBrokerId = partitionState.leader
        try {
          if (metadataCache.hasAliveBroker(newLeaderBrokerId)) {
            // Only change partition state when the leader is available
            if (partition.makeFollower(partitionState, highWatermarkCheckpoints, topicIds(partitionState.topicName))) {
              partitionsToMakeFollower += partition
            }
          } else {
            // The leader broker should always be present in the metadata cache.
            // If not, we should record the error message and abort the transition process for this partition
            stateChangeLogger.error(s"Received LeaderAndIsrRequest with correlation id $correlationId from " +
              s"controller $controllerId epoch $controllerEpoch for partition ${partition.topicPartition} " +
              s"(last update controller epoch ${partitionState.controllerEpoch}) " +
              s"but cannot become follower since the new leader $newLeaderBrokerId is unavailable.")
            // Create the local replica even if the leader is unavailable. This is required to ensure that we include
            // the partition's high watermark in the checkpoint file (see KAFKA-1647)
            partition.createLogIfNotExists(isNew = partitionState.isNew, isFutureReplica = false,
              highWatermarkCheckpoints, topicIds(partitionState.topicName))
          }
        } catch {
          case e: KafkaStorageException =>
            stateChangeLogger.error(s"Skipped the become-follower state change with correlation id $correlationId from " +
              s"controller $controllerId epoch $controllerEpoch for partition ${partition.topicPartition} " +
              s"(last update controller epoch ${partitionState.controllerEpoch}) with leader " +
              s"$newLeaderBrokerId since the replica for the partition is offline due to storage error $e")
            // If there is an offline log directory, a Partition object may have been created and have been added
            // to `ReplicaManager.allPartitions` before `createLogIfNotExists()` failed to create local replica due
            // to KafkaStorageException. In this case `ReplicaManager.allPartitions` will map this topic-partition
            // to an empty Partition object. We need to map this topic-partition to OfflinePartition instead.
            markPartitionOffline(partition.topicPartition)
            responseMap.put(partition.topicPartition, Errors.KAFKA_STORAGE_ERROR)
        }
      }

      // Stopping the fetchers must be done first in order to initialize the fetch
      // position correctly.
      replicaFetcherManager.removeFetcherForPartitions(partitionsToMakeFollower.map(_.topicPartition))
      stateChangeLogger.info(s"Stopped fetchers as part of become-follower request from controller $controllerId " +
        s"epoch $controllerEpoch with correlation id $correlationId for ${partitionsToMakeFollower.size} partitions")

      partitionsToMakeFollower.foreach { partition =>
        completeDelayedFetchOrProduceRequests(partition.topicPartition)
      }

      if (isShuttingDown.get()) {
        if (traceLoggingEnabled) {
          partitionsToMakeFollower.foreach { partition =>
            stateChangeLogger.trace(s"Skipped the adding-fetcher step of the become-follower state " +
              s"change with correlation id $correlationId from controller $controllerId epoch $controllerEpoch for " +
              s"partition ${partition.topicPartition} with leader ${partitionStates(partition).leader} " +
              "since it is shutting down")
          }
        }
      } else {
        // we do not need to check if the leader exists again since this has been done at the beginning of this process
        val partitionsToMakeFollowerWithLeaderAndOffset = partitionsToMakeFollower.map { partition =>
          val leaderNode = partition.leaderReplicaIdOpt.flatMap(leaderId => metadataCache.
            getAliveBrokerNode(leaderId, config.interBrokerListenerName)).getOrElse(Node.noNode())
          val leader = new BrokerEndPoint(leaderNode.id(), leaderNode.host(), leaderNode.port())
          val log = partition.localLogOrException
          val fetchOffset = initialFetchOffset(log)
          partition.topicPartition -> InitialFetchState(topicIds(partition.topic), leader, partition.getLeaderEpoch, fetchOffset)
        }.toMap

        replicaFetcherManager.addFetcherForPartitions(partitionsToMakeFollowerWithLeaderAndOffset)
      }
    } catch {
      case e: Throwable =>
        stateChangeLogger.error(s"Error while processing LeaderAndIsr request with correlationId $correlationId " +
          s"received from controller $controllerId epoch $controllerEpoch", e)
        // Re-throw the exception for it to be caught in KafkaApis
        throw e
    }

    if (traceLoggingEnabled)
      partitionStates.keys.foreach { partition =>
        stateChangeLogger.trace(s"Completed LeaderAndIsr request correlationId $correlationId from controller $controllerId " +
          s"epoch $controllerEpoch for the become-follower transition for partition ${partition.topicPartition} with leader " +
          s"${partitionStates(partition).leader}")
      }

    partitionsToMakeFollower
  }

  private def updateTopicIdForFollowers(controllerId: Int,
                                        controllerEpoch: Int,
                                        partitions: Set[Partition],
                                        correlationId: Int,
                                        topicIds: String => Option[Uuid]): Unit = {
    val traceLoggingEnabled = stateChangeLogger.isTraceEnabled

    try {
      if (isShuttingDown.get()) {
        if (traceLoggingEnabled) {
          partitions.foreach { partition =>
            stateChangeLogger.trace(s"Skipped the update topic ID step of the become-follower state " +
              s"change with correlation id $correlationId from controller $controllerId epoch $controllerEpoch for " +
              s"partition ${partition.topicPartition} since it is shutting down")
          }
        }
      } else {
        val partitionsToUpdateFollowerWithLeader = mutable.Map.empty[TopicPartition, Int]
        partitions.foreach { partition =>
          partition.leaderReplicaIdOpt.foreach { leader =>
            if (metadataCache.hasAliveBroker(leader)) {
              partitionsToUpdateFollowerWithLeader += partition.topicPartition -> leader
            }
          }
        }
        replicaFetcherManager.maybeUpdateTopicIds(partitionsToUpdateFollowerWithLeader, topicIds)
      }
    } catch {
      case e: Throwable =>
        stateChangeLogger.error(s"Error while processing LeaderAndIsr request with correlationId $correlationId " +
          s"received from controller $controllerId epoch $controllerEpoch when trying to update topic IDs in the fetchers", e)
        // Re-throw the exception for it to be caught in KafkaApis
        throw e
    }
  }

  /**
   * From IBP 2.7 onwards, we send latest fetch epoch in the request and truncate if a
   * diverging epoch is returned in the response, avoiding the need for a separate
   * OffsetForLeaderEpoch request.
   */
  protected def initialFetchOffset(log: UnifiedLog): Long = {
    if (metadataCache.metadataVersion().isTruncationOnFetchSupported && log.latestEpoch.nonEmpty)
      log.logEndOffset
    else
      log.highWatermark
  }

  private def maybeShrinkIsr(): Unit = {
    trace("Evaluating ISR list of partitions to see which replicas can be removed from the ISR")

    // Shrink ISRs for non offline partitions
    allPartitions.keys.foreach { topicPartition =>
      onlinePartition(topicPartition).foreach(_.maybeShrinkIsr())
    }
  }

  private def leaderPartitionsIterator: Iterator[Partition] =
    onlinePartitionsIterator.filter(_.leaderLogIfLocal.isDefined)

  def getLogEndOffset(topicPartition: TopicPartition): Option[Long] =
    onlinePartition(topicPartition).flatMap(_.leaderLogIfLocal.map(_.logEndOffset))

  // Flushes the highwatermark value for all partitions to the highwatermark file
  def checkpointHighWatermarks(): Unit = {
    def putHw(logDirToCheckpoints: mutable.AnyRefMap[String, mutable.AnyRefMap[TopicPartition, Long]],
              log: UnifiedLog): Unit = {
      val checkpoints = logDirToCheckpoints.getOrElseUpdate(log.parentDir,
        new mutable.AnyRefMap[TopicPartition, Long]())
      checkpoints.put(log.topicPartition, log.highWatermark)
    }

    val logDirToHws = new mutable.AnyRefMap[String, mutable.AnyRefMap[TopicPartition, Long]](
      allPartitions.size)
    onlinePartitionsIterator.foreach { partition =>
      partition.log.foreach(putHw(logDirToHws, _))
      partition.futureLog.foreach(putHw(logDirToHws, _))
    }

    for ((logDir, hws) <- logDirToHws) {
      try highWatermarkCheckpoints.get(logDir).foreach(_.write(hws))
      catch {
        case e: KafkaStorageException =>
          error(s"Error while writing to highwatermark file in directory $logDir", e)
      }
    }
  }

  def markPartitionOffline(tp: TopicPartition): Unit = replicaStateChangeLock synchronized {
    allPartitions.put(tp, HostedPartition.Offline) match {
      case HostedPartition.Online(partition) =>
        partition.markOffline()
      case _ => // Nothing
    }
  }

  /**
   * The log directory failure handler for the replica
   *
   * @param dir                     the absolute path of the log directory
   * @param sendZkNotification      check if we need to send notification to zookeeper node (needed for unit test)
   */
  def handleLogDirFailure(dir: String, sendZkNotification: Boolean = true): Unit = {
    if (!logManager.isLogDirOnline(dir))
      return
    warn(s"Stopping serving replicas in dir $dir")
    replicaStateChangeLock synchronized {
      val newOfflinePartitions = onlinePartitionsIterator.filter { partition =>
        partition.log.exists { _.parentDir == dir }
      }.map(_.topicPartition).toSet

      val partitionsWithOfflineFutureReplica = onlinePartitionsIterator.filter { partition =>
        partition.futureLog.exists { _.parentDir == dir }
      }.toSet

      replicaFetcherManager.removeFetcherForPartitions(newOfflinePartitions)
      replicaAlterLogDirsManager.removeFetcherForPartitions(newOfflinePartitions ++ partitionsWithOfflineFutureReplica.map(_.topicPartition))

      partitionsWithOfflineFutureReplica.foreach(partition => partition.removeFutureLocalReplica(deleteFromLogDir = false))
      newOfflinePartitions.foreach { topicPartition =>
        markPartitionOffline(topicPartition)
      }
      newOfflinePartitions.map(_.topic).foreach { topic: String =>
        maybeRemoveTopicMetrics(topic)
      }
      highWatermarkCheckpoints = highWatermarkCheckpoints.filter { case (checkpointDir, _) => checkpointDir != dir }

      warn(s"Broker $localBrokerId stopped fetcher for partitions ${newOfflinePartitions.mkString(",")} and stopped moving logs " +
           s"for partitions ${partitionsWithOfflineFutureReplica.mkString(",")} because they are in the failed log directory $dir.")
    }
    logManager.handleLogDirFailure(dir)

    if (sendZkNotification)
      if (zkClient.isEmpty) {
        warn("Unable to propagate log dir failure via Zookeeper in KRaft mode")
      } else {
        zkClient.get.propagateLogDirEvent(localBrokerId)
      }
    warn(s"Stopped serving replicas in dir $dir")
  }

  def removeMetrics(): Unit = {
    metricsGroup.removeMetric("LeaderCount")
    metricsGroup.removeMetric("PartitionCount")
    metricsGroup.removeMetric("OfflineReplicaCount")
    metricsGroup.removeMetric("UnderReplicatedPartitions")
    metricsGroup.removeMetric("UnderMinIsrPartitionCount")
    metricsGroup.removeMetric("AtMinIsrPartitionCount")
    metricsGroup.removeMetric("ReassigningPartitions")
    metricsGroup.removeMetric("PartitionsWithLateTransactionsCount")
    metricsGroup.removeMetric("ProducerIdCount")
    metricsGroup.removeMetric("IsrExpandsPerSec")
    metricsGroup.removeMetric("IsrShrinksPerSec")
    metricsGroup.removeMetric("FailedIsrUpdatesPerSec")
  }

  def beginControlledShutdown(): Unit = {
    isInControlledShutdown = true
  }

  // High watermark do not need to be checkpointed only when under unit tests
  def shutdown(checkpointHW: Boolean = true): Unit = {
    info("Shutting down")
    removeMetrics()
    if (logDirFailureHandler != null)
      logDirFailureHandler.shutdown()
    replicaFetcherManager.shutdown()
    replicaAlterLogDirsManager.shutdown()
    delayedFetchPurgatory.shutdown()
    delayedProducePurgatory.shutdown()
    delayedDeleteRecordsPurgatory.shutdown()
    delayedElectLeaderPurgatory.shutdown()
    if (checkpointHW)
      checkpointHighWatermarks()
    replicaSelectorOpt.foreach(_.close)
    removeAllTopicMetrics()
    addPartitionsToTxnManager.foreach(_.shutdown())
    info("Shut down completely")
  }

  private def removeAllTopicMetrics(): Unit = {
    val allTopics = new util.HashSet[String]
    allPartitions.keys.foreach(partition =>
      if (allTopics.add(partition.topic())) {
        brokerTopicStats.removeMetrics(partition.topic())
      })
  }

  protected def createReplicaFetcherManager(metrics: Metrics, time: Time, threadNamePrefix: Option[String], quotaManager: ReplicationQuotaManager) = {
    new ReplicaFetcherManager(config, this, metrics, time, threadNamePrefix, quotaManager, () => metadataCache.metadataVersion(), brokerEpochSupplier)
  }

  protected def createReplicaAlterLogDirsManager(quotaManager: ReplicationQuotaManager, brokerTopicStats: BrokerTopicStats) = {
    new ReplicaAlterLogDirsManager(config, this, quotaManager, brokerTopicStats)
  }

  protected def createReplicaSelector(): Option[ReplicaSelector] = {
    config.replicaSelectorClassName.map { className =>
      val tmpReplicaSelector: ReplicaSelector = CoreUtils.createObject[ReplicaSelector](className)
      tmpReplicaSelector.configure(config.originals())
      tmpReplicaSelector
    }
  }

  def lastOffsetForLeaderEpoch(
    requestedEpochInfo: Seq[OffsetForLeaderTopic]
  ): Seq[OffsetForLeaderTopicResult] = {
    requestedEpochInfo.map { offsetForLeaderTopic =>
      val partitions = offsetForLeaderTopic.partitions.asScala.map { offsetForLeaderPartition =>
        val tp = new TopicPartition(offsetForLeaderTopic.topic, offsetForLeaderPartition.partition)
        getPartition(tp) match {
          case HostedPartition.Online(partition) =>
            val currentLeaderEpochOpt =
              if (offsetForLeaderPartition.currentLeaderEpoch == RecordBatch.NO_PARTITION_LEADER_EPOCH)
                Optional.empty[Integer]
              else
                Optional.of[Integer](offsetForLeaderPartition.currentLeaderEpoch)

            partition.lastOffsetForLeaderEpoch(
              currentLeaderEpochOpt,
              offsetForLeaderPartition.leaderEpoch,
              fetchOnlyFromLeader = true)

          case HostedPartition.Offline =>
            new EpochEndOffset()
              .setPartition(offsetForLeaderPartition.partition)
              .setErrorCode(Errors.KAFKA_STORAGE_ERROR.code)

          case HostedPartition.None if metadataCache.contains(tp) =>
            new EpochEndOffset()
              .setPartition(offsetForLeaderPartition.partition)
              .setErrorCode(Errors.NOT_LEADER_OR_FOLLOWER.code)

          case HostedPartition.None =>
            new EpochEndOffset()
              .setPartition(offsetForLeaderPartition.partition)
              .setErrorCode(Errors.UNKNOWN_TOPIC_OR_PARTITION.code)
        }
      }

      new OffsetForLeaderTopicResult()
        .setTopic(offsetForLeaderTopic.topic)
        .setPartitions(partitions.toList.asJava)
    }
  }

  def electLeaders(
    controller: KafkaController,
    partitions: Set[TopicPartition],
    electionType: ElectionType,
    responseCallback: Map[TopicPartition, ApiError] => Unit,
    requestTimeout: Int
  ): Unit = {

    val deadline = time.milliseconds() + requestTimeout

    def electionCallback(results: Map[TopicPartition, Either[ApiError, Int]]): Unit = {
      val expectedLeaders = mutable.Map.empty[TopicPartition, Int]
      val failures = mutable.Map.empty[TopicPartition, ApiError]
      results.foreach {
        case (partition, Right(leader)) => expectedLeaders += partition -> leader
        case (partition, Left(error)) => failures += partition -> error
      }
      if (expectedLeaders.nonEmpty) {
        val watchKeys = expectedLeaders.iterator.map {
          case (tp, _) => TopicPartitionOperationKey(tp)
        }.toBuffer

        delayedElectLeaderPurgatory.tryCompleteElseWatch(
          new DelayedElectLeader(
            math.max(0, deadline - time.milliseconds()),
            expectedLeaders,
            failures,
            this,
            responseCallback
          ),
          watchKeys
        )
      } else {
          // There are no partitions actually being elected, so return immediately
          responseCallback(failures)
      }
    }

    controller.electLeaders(partitions, electionType, electionCallback)
  }

  def activeProducerState(requestPartition: TopicPartition): DescribeProducersResponseData.PartitionResponse = {
    getPartitionOrError(requestPartition) match {
      case Left(error) => new DescribeProducersResponseData.PartitionResponse()
        .setPartitionIndex(requestPartition.partition)
        .setErrorCode(error.code)
      case Right(partition) => partition.activeProducerState
    }
  }

  private[kafka] def getOrCreatePartition(tp: TopicPartition,
                                          delta: TopicsDelta,
                                          topicId: Uuid): Option[(Partition, Boolean)] = {
    getPartition(tp) match {
      case HostedPartition.Offline =>
        stateChangeLogger.warn(s"Unable to bring up new local leader $tp " +
          s"with topic id $topicId because it resides in an offline log " +
          "directory.")
        None

      case HostedPartition.Online(partition) =>
        if (partition.topicId.exists(_ != topicId)) {
          // Note: Partition#topicId will be None here if the Log object for this partition
          // has not been created.
          throw new IllegalStateException(s"Topic $tp exists, but its ID is " +
            s"${partition.topicId.get}, not $topicId as expected")
        }
        Some(partition, false)

      case HostedPartition.None =>
        if (delta.image().topicsById().containsKey(topicId)) {
          stateChangeLogger.error(s"Expected partition $tp with topic id " +
            s"$topicId to exist, but it was missing. Creating...")
        } else {
          stateChangeLogger.info(s"Creating new partition $tp with topic id " +
            s"$topicId.")
        }
        // it's a partition that we don't know about yet, so create it and mark it online
        val partition = Partition(tp, time, this)
        allPartitions.put(tp, HostedPartition.Online(partition))
        Some(partition, true)
    }
  }

  /**
   * Apply a KRaft topic change delta.
   *
   * @param delta           The delta to apply.
   * @param newImage        The new metadata image.
   */
  def applyDelta(delta: TopicsDelta, newImage: MetadataImage): Unit = {
    // Before taking the lock, compute the local changes
    val localChanges = delta.localChanges(config.nodeId)

    replicaStateChangeLock.synchronized {
      // Handle deleted partitions. We need to do this first because we might subsequently
      // create new partitions with the same names as the ones we are deleting here.
      if (!localChanges.deletes.isEmpty) {
        val deletes = localChanges.deletes.asScala.map(tp => (tp, true)).toMap
        stateChangeLogger.info(s"Deleting ${deletes.size} partition(s).")
        stopPartitions(deletes).forKeyValue { (topicPartition, e) =>
          if (e.isInstanceOf[KafkaStorageException]) {
            stateChangeLogger.error(s"Unable to delete replica $topicPartition because " +
              "the local replica for the partition is in an offline log directory")
          } else {
            stateChangeLogger.error(s"Unable to delete replica $topicPartition because " +
              s"we got an unexpected ${e.getClass.getName} exception: ${e.getMessage}")
          }
        }
      }

      // Handle partitions which we are now the leader or follower for.
      if (!localChanges.leaders.isEmpty || !localChanges.followers.isEmpty) {
        val lazyOffsetCheckpoints = new LazyOffsetCheckpoints(this.highWatermarkCheckpoints)
        val changedPartitions = new mutable.HashSet[Partition]
        if (!localChanges.leaders.isEmpty) {
          applyLocalLeadersDelta(changedPartitions, delta, lazyOffsetCheckpoints, localChanges.leaders.asScala)
        }
        if (!localChanges.followers.isEmpty) {
          applyLocalFollowersDelta(changedPartitions, newImage, delta, lazyOffsetCheckpoints, localChanges.followers.asScala)
        }
        maybeAddLogDirFetchers(changedPartitions, lazyOffsetCheckpoints,
          name => Option(newImage.topics().getTopic(name)).map(_.id()))

        replicaFetcherManager.shutdownIdleFetcherThreads()
        replicaAlterLogDirsManager.shutdownIdleFetcherThreads()
      }
    }
  }

  private def applyLocalLeadersDelta(
    changedPartitions: mutable.Set[Partition],
    delta: TopicsDelta,
    offsetCheckpoints: OffsetCheckpoints,
    localLeaders: mutable.Map[TopicPartition, LocalReplicaChanges.PartitionInfo]
  ): Unit = {
    stateChangeLogger.info(s"Transitioning ${localLeaders.size} partition(s) to " +
      "local leaders.")
    replicaFetcherManager.removeFetcherForPartitions(localLeaders.keySet)
    localLeaders.forKeyValue { (tp, info) =>
      getOrCreatePartition(tp, delta, info.topicId).foreach { case (partition, isNew) =>
        try {
          val state = info.partition.toLeaderAndIsrPartitionState(tp, isNew)
          partition.makeLeader(state, offsetCheckpoints, Some(info.topicId))
          changedPartitions.add(partition)
        } catch {
          case e: KafkaStorageException =>
            stateChangeLogger.info(s"Skipped the become-leader state change for $tp " +
              s"with topic id ${info.topicId} due to a storage error ${e.getMessage}")
            // If there is an offline log directory, a Partition object may have been created by
            // `getOrCreatePartition()` before `createLogIfNotExists()` failed to create local replica due
            // to KafkaStorageException. In this case `ReplicaManager.allPartitions` will map this topic-partition
            // to an empty Partition object. We need to map this topic-partition to OfflinePartition instead.
            markPartitionOffline(tp)
        }
      }
    }
  }

  private def applyLocalFollowersDelta(
    changedPartitions: mutable.Set[Partition],
    newImage: MetadataImage,
    delta: TopicsDelta,
    offsetCheckpoints: OffsetCheckpoints,
    localFollowers: mutable.Map[TopicPartition, LocalReplicaChanges.PartitionInfo]
  ): Unit = {
    stateChangeLogger.info(s"Transitioning ${localFollowers.size} partition(s) to " +
      "local followers.")
    val partitionsToStartFetching = new mutable.HashMap[TopicPartition, Partition]
    val partitionsToStopFetching = new mutable.HashMap[TopicPartition, Boolean]
    val followerTopicSet = new mutable.HashSet[String]
    localFollowers.forKeyValue { (tp, info) =>
      getOrCreatePartition(tp, delta, info.topicId).foreach { case (partition, isNew) =>
        try {
          followerTopicSet.add(tp.topic)

          // We always update the follower state.
          // - This ensure that a replica with no leader can step down;
          // - This also ensures that the local replica is created even if the leader
          //   is unavailable. This is required to ensure that we include the partition's
          //   high watermark in the checkpoint file (see KAFKA-1647).
          val state = info.partition.toLeaderAndIsrPartitionState(tp, isNew)
          val isNewLeaderEpoch = partition.makeFollower(state, offsetCheckpoints, Some(info.topicId))

          if (isInControlledShutdown && (info.partition.leader == NO_LEADER ||
              !info.partition.isr.contains(config.brokerId))) {
            // During controlled shutdown, replica with no leaders and replica
            // where this broker is not in the ISR are stopped.
            partitionsToStopFetching.put(tp, false)
          } else if (isNewLeaderEpoch) {
            // Otherwise, fetcher is restarted if the leader epoch has changed.
            partitionsToStartFetching.put(tp, partition)
          }

          changedPartitions.add(partition)
        } catch {
          case e: KafkaStorageException =>
            stateChangeLogger.error(s"Unable to start fetching $tp " +
              s"with topic ID ${info.topicId} due to a storage error ${e.getMessage}", e)
            replicaFetcherManager.addFailedPartition(tp)
            // If there is an offline log directory, a Partition object may have been created by
            // `getOrCreatePartition()` before `createLogIfNotExists()` failed to create local replica due
            // to KafkaStorageException. In this case `ReplicaManager.allPartitions` will map this topic-partition
            // to an empty Partition object. We need to map this topic-partition to OfflinePartition instead.
            markPartitionOffline(tp)

          case e: Throwable =>
            stateChangeLogger.error(s"Unable to start fetching $tp " +
              s"with topic ID ${info.topicId} due to ${e.getClass.getSimpleName}", e)
            replicaFetcherManager.addFailedPartition(tp)
        }
      }
    }

    if (partitionsToStartFetching.nonEmpty) {
      // Stopping the fetchers must be done first in order to initialize the fetch
      // position correctly.
      replicaFetcherManager.removeFetcherForPartitions(partitionsToStartFetching.keySet)
      stateChangeLogger.info(s"Stopped fetchers as part of become-follower for ${partitionsToStartFetching.size} partitions")

      val listenerName = config.interBrokerListenerName.value
      val partitionAndOffsets = new mutable.HashMap[TopicPartition, InitialFetchState]

      partitionsToStartFetching.forKeyValue { (topicPartition, partition) =>
        val nodeOpt = partition.leaderReplicaIdOpt
          .flatMap(leaderId => Option(newImage.cluster.broker(leaderId)))
          .flatMap(_.node(listenerName).asScala)

        nodeOpt match {
          case Some(node) =>
            val log = partition.localLogOrException
            partitionAndOffsets.put(topicPartition, InitialFetchState(
              log.topicId,
              new BrokerEndPoint(node.id, node.host, node.port),
              partition.getLeaderEpoch,
              initialFetchOffset(log)
            ))
          case None =>
            stateChangeLogger.trace(s"Unable to start fetching $topicPartition with topic ID ${partition.topicId} " +
              s"from leader ${partition.leaderReplicaIdOpt} because it is not alive.")
        }
      }

      replicaFetcherManager.addFetcherForPartitions(partitionAndOffsets)
      stateChangeLogger.info(s"Started fetchers as part of become-follower for ${partitionsToStartFetching.size} partitions")

      partitionsToStartFetching.keySet.foreach(completeDelayedFetchOrProduceRequests)

      updateLeaderAndFollowerMetrics(followerTopicSet)
    }

    if (partitionsToStopFetching.nonEmpty) {
      stopPartitions(partitionsToStopFetching)
      stateChangeLogger.info(s"Stopped fetchers as part of controlled shutdown for ${partitionsToStopFetching.size} partitions")
    }
  }

  def deleteStrayReplicas(topicPartitions: Iterable[TopicPartition]): Unit = {
    stopPartitions(topicPartitions.map(tp => tp -> true).toMap).forKeyValue { (topicPartition, exception) =>
      exception match {
        case e: KafkaStorageException =>
          stateChangeLogger.error(s"Unable to delete stray replica $topicPartition because " +
            s"the local replica for the partition is in an offline log directory: ${e.getMessage}.")
        case e: Throwable =>
          stateChangeLogger.error(s"Unable to delete stray replica $topicPartition because " +
            s"we got an unexpected ${e.getClass.getName} exception: ${e.getMessage}", e)
      }
    }
  }

  private[server] def getTransactionCoordinator(partition: Int): (Errors, Node) = {
    val listenerName = config.interBrokerListenerName

    val topicMetadata = metadataCache.getTopicMetadata(Set(Topic.TRANSACTION_STATE_TOPIC_NAME), listenerName)

    if (topicMetadata.headOption.isEmpty) {
      // If topic is not created, then the transaction is definitely not started.
      (Errors.COORDINATOR_NOT_AVAILABLE, Node.noNode)
    } else {
      if (topicMetadata.head.errorCode != Errors.NONE.code) {
        (Errors.COORDINATOR_NOT_AVAILABLE, Node.noNode)
      } else {
        val coordinatorEndpoint = topicMetadata.head.partitions.asScala
          .find(_.partitionIndex == partition)
          .filter(_.leaderId != MetadataResponse.NO_LEADER_ID)
          .flatMap(metadata => metadataCache.
            getAliveBrokerNode(metadata.leaderId, listenerName))

        coordinatorEndpoint match {
          case Some(endpoint) =>
            (Errors.NONE, endpoint)
          case _ =>
            (Errors.COORDINATOR_NOT_AVAILABLE, Node.noNode)
        }
      }
    }
  }
}
