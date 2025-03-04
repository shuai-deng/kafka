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
package kafka.controller

import com.yammer.metrics.core.Timer

import java.util.concurrent.TimeUnit
import kafka.admin.AdminOperationException
import kafka.api._
import kafka.common._
import kafka.cluster.Broker
import kafka.controller.KafkaController.{AlterReassignmentsCallback, ElectLeadersCallback, ListReassignmentsCallback, UpdateFeaturesCallback}
import kafka.coordinator.transaction.ZkProducerIdManager
import kafka.server._
import kafka.server.metadata.ZkFinalizedFeatureCache
import kafka.utils._
import kafka.utils.Implicits._
import kafka.zk.KafkaZkClient.UpdateLeaderAndIsrResult
import kafka.zk.TopicZNode.TopicIdReplicaAssignment
import kafka.zk.{FeatureZNodeStatus, _}
import kafka.zookeeper.{StateChangeHandler, ZNodeChangeHandler, ZNodeChildChangeHandler}
import org.apache.kafka.clients.admin.FeatureUpdate.UpgradeType
import org.apache.kafka.common.ElectionType
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.errors.{BrokerNotAvailableException, ControllerMovedException, StaleBrokerEpochException}
import org.apache.kafka.common.message.{AllocateProducerIdsRequestData, AllocateProducerIdsResponseData, AlterPartitionRequestData, AlterPartitionResponseData}
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.requests.{AbstractControlRequest, ApiError, LeaderAndIsrResponse, UpdateFeaturesRequest, UpdateMetadataResponse}
import org.apache.kafka.common.utils.{Time, Utils}
import org.apache.kafka.metadata.LeaderRecoveryState
import org.apache.kafka.server.common.ProducerIdsBlock
import org.apache.kafka.server.metrics.KafkaMetricsGroup
import org.apache.kafka.server.util.KafkaScheduler
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.Code

import scala.collection.{Map, Seq, Set, immutable, mutable}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
 * 选举触发器（ElectionTrigger）：这里的选举不是指 Controller 选举，而是指主题分区副本的选举，即为哪些分区选择 Leader 副本。
 */
sealed trait ElectionTrigger
final case object AutoTriggered extends ElectionTrigger
final case object ZkTriggered extends ElectionTrigger
final case object AdminClientTriggered extends ElectionTrigger

//KafkaController 伴生对象，仅仅定义了一些常量和回调函数类型。
object KafkaController extends Logging {
  val InitialControllerEpoch = 0
  val InitialControllerEpochZkVersion = 0

  type ElectLeadersCallback = Map[TopicPartition, Either[ApiError, Int]] => Unit
  type ListReassignmentsCallback = Either[Map[TopicPartition, ReplicaAssignment], ApiError] => Unit
  type AlterReassignmentsCallback = Either[Map[TopicPartition, ApiError], ApiError] => Unit
  type UpdateFeaturesCallback = Either[ApiError, Map[String, ApiError]] => Unit
}

/**
 * 作为核心组件，Controller 提供的功能非常多。除了集群成员管理，主题管理也是一个极其重要的功能。
 * 集群成员管理: 1、成员数量的管理，主要体现在新增成员和移除现有成员；2、单个成员的管理，如变更单个 Broker 的数据等。
 * * 成员数量管理每个 Broker 在启动的时候，会在 ZooKeeper 的 /brokers/ids 节点下创建一个名为 broker.id 参数值的临时节点。
 *
 * Controller 的很多代码仅仅是做数据的管理操作而已
 * Controller 承载了 ZooKeeper 上的所有元数据
 * 集群 Broker 是不会与 ZooKeeper 直接交互去获取元数据的，相反地，它们总是与 Controller 进行通信，获取和更新最新的集群数据。
 * @param config Kafka配置信息，通过它，你能拿到Broker端所有参数的值
 * @param zkClient ZooKeeper客户端，Controller与ZooKeeper的所有交互均通过该属性完成
 * @param time 提供时间服务(如获取当前时间)的工具类
 * @param metrics 实现指标监控服务(如创建监控指标)的工具类
 * @param initialBrokerInfo Broker节点信息，包括主机名、端口号，所用监听器等
 * @param initialBrokerEpoch Broker Epoch值，用于隔离老Controller发送的请求
 * @param tokenManager 实现Delegation token管理的工具类。Delegation token是一种轻量级的认证机制
 * @param brokerFeatures
 * @param featureCache
 * @param threadNamePrefix Controller端事件处理线程名字前缀
 */
class KafkaController(val config: KafkaConfig, //KafkaConfig 类实例，里面封装了 Broker 端所有参数的值。
                      zkClient: KafkaZkClient, //ZooKeeper 客户端类，定义了与 ZooKeeper 交互的所有方法。
                      time: Time,
                      metrics: Metrics,
                      initialBrokerInfo: BrokerInfo,
                      initialBrokerEpoch: Long, //Controller 所在 Broker 的 Epoch 值。Kafka 使用它来确保 Broker 不会处理老 Controller 发来的请求。
                      tokenManager: DelegationTokenManager,
                      brokerFeatures: BrokerFeatures,
                      featureCache: ZkFinalizedFeatureCache,
                      threadNamePrefix: Option[String] = None)
  extends ControllerEventProcessor with Logging {

  private val metricsGroup = new KafkaMetricsGroup(this.getClass)

  this.logIdent = s"[Controller id=${config.brokerId}] "

  @volatile private var brokerInfo = initialBrokerInfo
  @volatile private var _brokerEpoch = initialBrokerEpoch

  private val isAlterPartitionEnabled = config.interBrokerProtocolVersion.isAlterPartitionSupported
  private val stateChangeLogger = new StateChangeLogger(config.brokerId, inControllerContext = true, None)
  // 集群元数据类，保存集群所有元数据
  val controllerContext = new ControllerContext
  // Controller端通道管理器类，负责Controller向Broker发送请求
  var controllerChannelManager = new ControllerChannelManager(
    () => controllerContext.epoch,
    config,
    time,
    metrics,
    stateChangeLogger,
    threadNamePrefix
  )

  // have a separate scheduler for the controller to be able to start and stop independently of the kafka server
  // visible for testing
  // 线程调度器，当前唯一负责定期执行Leader重选举
  private[controller] val kafkaScheduler = new KafkaScheduler(1)

  // visible for testing
  // Controller事件管理器，负责管理事件处理线程
  private[controller] val eventManager = new ControllerEventManager(config.brokerId, this, time,
    controllerContext.stats.rateAndTimeMetrics)

  private val brokerRequestBatch = new ControllerBrokerRequestBatch(config, controllerChannelManager,
    eventManager, controllerContext, stateChangeLogger)
  // 副本状态机，负责副本状态转换
  val replicaStateMachine: ReplicaStateMachine = new ZkReplicaStateMachine(config, stateChangeLogger, controllerContext, zkClient,
    new ControllerBrokerRequestBatch(config, controllerChannelManager, eventManager, controllerContext, stateChangeLogger))
  // 分区状态机，负责分区状态转换
  val partitionStateMachine: PartitionStateMachine = new ZkPartitionStateMachine(config, stateChangeLogger, controllerContext, zkClient,
    new ControllerBrokerRequestBatch(config, controllerChannelManager, eventManager, controllerContext, stateChangeLogger))
  // 主题删除管理器，负责删除主题及日志
  private val topicDeletionManager = new TopicDeletionManager(config, controllerContext, replicaStateMachine,
    partitionStateMachine, new ControllerDeletionClient(this, zkClient))

  // Controller节点ZooKeeper监听器：它是监听 /controller 节点变更的。这种变更包括节点创建、删除以及数据变更。
  private val controllerChangeHandler = new ControllerChangeHandler(eventManager)
  // Broker数量ZooKeeper监听器：监听 Broker 的数量变化。
  private val brokerChangeHandler = new BrokerChangeHandler(eventManager)
  // Broker信息变更ZooKeeper监听器集合：监听 Broker 的数据变更，比如 Broker 的配置信息发生的变化。
  private val brokerModificationsHandlers: mutable.Map[Int, BrokerModificationsHandler] = mutable.Map.empty
  // 主题数量ZooKeeper监听器：监控主题数量变更
  private val topicChangeHandler = new TopicChangeHandler(eventManager)
  // 主题删除ZooKeeper监听器：监听主题删除节点 /admin/delete_topics 的子节点数量变更。
  private val topicDeletionHandler = new TopicDeletionHandler(eventManager)
  // 主题分区变更ZooKeeper监听器：监控主题分区数据变更的监听器，比如，新增加了副本、分区更换了 Leader 副本。
  private val partitionModificationsHandlers: mutable.Map[String, PartitionModificationsHandler] = mutable.Map.empty
  // 主题分区重分配ZooKeeper监听器：监听分区副本重分配任务。一旦发现新提交的任务，就为目标分区执行副本重分配。
  private val partitionReassignmentHandler = new PartitionReassignmentHandler(eventManager)
  // Preferred Leader选举ZooKeeper监听器：监听 Preferred Leader 选举任务。一旦发现新提交的任务，就为目标主题执行 Preferred Leader 选举。
  private val preferredReplicaElectionHandler = new PreferredReplicaElectionHandler(eventManager)
  // ISR副本集合变更ZooKeeper监听器：监听 ISR 副本集合变更。一旦被触发，就需要获取 ISR 发生变更的分区列表，然后更新 Controller 端对应的 Leader 和 ISR 缓存元数据。
  private val isrChangeNotificationHandler = new IsrChangeNotificationHandler(eventManager)
  // 日志路径变更ZooKeeper监听器：监听日志路径变更。一旦被触发，需要获取受影响的 Broker 列表，然后处理这些 Broker 上失效的日志路径。
  private val logDirEventNotificationHandler = new LogDirEventNotificationHandler(eventManager)

  // 当前Controller所在Broker Id
  @volatile private var activeControllerId = -1
  // 离线分区总数
  @volatile private var offlinePartitionCount = 0
  // 满足Preferred Leader选举条件的总分区数
  @volatile private var preferredReplicaImbalanceCount = 0
  // 总主题数
  @volatile private var globalTopicCount = 0
  // 总主题分区数
  @volatile private var globalPartitionCount = 0
  // 待删除主题数
  @volatile private var topicsToDeleteCount = 0
  //待删除副本数
  @volatile private var replicasToDeleteCount = 0
  // 暂时无法删除的主题数
  @volatile private var ineligibleTopicsToDeleteCount = 0
  // 暂时无法删除的副本数
  @volatile private var ineligibleReplicasToDeleteCount = 0
  @volatile private var activeBrokerCount = 0

  /* single-thread scheduler to clean expired tokens */
  private val tokenCleanScheduler = new KafkaScheduler(1, true, "delegation-token-cleaner")

  metricsGroup.newGauge("ActiveControllerCount", () => if (isActive) 1 else 0)
  metricsGroup.newGauge("OfflinePartitionsCount", () => offlinePartitionCount)
  metricsGroup.newGauge("PreferredReplicaImbalanceCount", () => preferredReplicaImbalanceCount)
  metricsGroup.newGauge("ControllerState", () => state.value)
  metricsGroup.newGauge("GlobalTopicCount", () => globalTopicCount)
  metricsGroup.newGauge("GlobalPartitionCount", () => globalPartitionCount)
  metricsGroup.newGauge("TopicsToDeleteCount", () => topicsToDeleteCount)
  metricsGroup.newGauge("ReplicasToDeleteCount", () => replicasToDeleteCount)
  metricsGroup.newGauge("TopicsIneligibleToDeleteCount", () => ineligibleTopicsToDeleteCount)
  metricsGroup.newGauge("ReplicasIneligibleToDeleteCount", () => ineligibleReplicasToDeleteCount)
  metricsGroup.newGauge("ActiveBrokerCount", () => activeBrokerCount)
  // FencedBrokerCount metric is always 0 in the ZK controller.
  metricsGroup.newGauge("FencedBrokerCount", () => 0)

  /**
   * Returns true if this broker is the current controller.
   */
  def isActive: Boolean = activeControllerId == config.brokerId

  def brokerEpoch: Long = _brokerEpoch

  def epoch: Int = controllerContext.epoch

  /**
   * Invoked when the controller module of a Kafka server is started up. This does not assume that the current broker
   * is the controller. It merely registers the session expiration listener and starts the controller leader
   * elector
   * 首先，startup 方法会注册 ZooKeeper 状态变更监听器，用于监听 Broker 与 ZooKeeper 之间的会话是否过期。
   * 接着，写入 Startup 事件到事件队列，
   * 然后启动 ControllerEventThread 线程，开始处理事件队列中的 Startup 事件。
   */
  def startup() = {
    // 第1步：注册ZooKeeper状态变更监听器，它是用于监听Zookeeper会话过期的
    zkClient.registerStateChangeHandler(new StateChangeHandler {
      override val name: String = StateChangeHandlers.ControllerHandler
      override def afterInitializingSession(): Unit = {
        eventManager.put(RegisterBrokerAndReelect)
      }
      override def beforeInitializingSession(): Unit = {
        val queuedEvent = eventManager.clearAndPut(Expire)

        // Block initialization of the new session until the expiration event is being handled,
        // which ensures that all pending events have been processed before creating the new session
        queuedEvent.awaitProcessing()
      }
    })
    // 第2步：写入Startup事件到事件队列
    eventManager.put(Startup)
    // 第3步：启动ControllerEventThread线程，开始处理事件队列中的ControllerEvent
    eventManager.start()
  }

  /**
   * Invoked when the controller module of a Kafka server is shutting down. If the broker was the current controller,
   * it shuts down the partition and replica state machines. If not, those are a no-op. In addition to that, it also
   * shuts down the controller channel manager, if one exists (i.e. if it was the current controller)
   */
  def shutdown(): Unit = {
    eventManager.close()
    onControllerResignation()
  }

  /**
   * On controlled shutdown, the controller first determines the partitions that the
   * shutting down broker leads, and moves leadership of those partitions to another broker
   * that is in that partition's ISR.
   *
   * @param id Id of the broker to shutdown.
   * @param brokerEpoch The broker epoch in the controlled shutdown request
   * @return The number of partitions that the broker still leads.
   */
  def controlledShutdown(id: Int, brokerEpoch: Long, controlledShutdownCallback: Try[Set[TopicPartition]] => Unit): Unit = {
    val controlledShutdownEvent = ControlledShutdown(id, brokerEpoch, controlledShutdownCallback)
    eventManager.put(controlledShutdownEvent)
  }

  private[kafka] def updateBrokerInfo(newBrokerInfo: BrokerInfo): Unit = {
    this.brokerInfo = newBrokerInfo
    zkClient.updateBrokerInfo(newBrokerInfo)
  }

  private[kafka] def enableDefaultUncleanLeaderElection(): Unit = {
    eventManager.put(UncleanLeaderElectionEnable)
  }

  private[kafka] def enableTopicUncleanLeaderElection(topic: String): Unit = {
    if (isActive) {
      eventManager.put(TopicUncleanLeaderElectionEnable(topic))
    }
  }

  def isTopicQueuedForDeletion(topic: String): Boolean = {
    topicDeletionManager.isTopicQueuedUpForDeletion(topic)
  }

  private def state: ControllerState = eventManager.state

  /**
   * This callback is invoked by the zookeeper leader elector on electing the current broker as the new controller.
   * It does the following things on the become-controller state change -
   * 1. Initializes the controller's context object that holds cache objects for current topics, live brokers and
   *    leaders for all existing partitions.
   * 2. Starts the controller's channel manager
   * 3. Starts the replica state machine
   * 4. Starts the partition state machine
   * If it encounters any unexpected exception/error while becoming controller, it resigns as the current controller.
   * This ensures another controller election will be triggered and there will always be an actively serving controller
   */
  private def onControllerFailover(): Unit = {
    maybeSetupFeatureVersioning()

    info("Registering handlers")

    // before reading source of truth from zookeeper, register the listeners to get broker/topic callbacks
    val childChangeHandlers = Seq(brokerChangeHandler, topicChangeHandler, topicDeletionHandler, logDirEventNotificationHandler,
      isrChangeNotificationHandler)
    childChangeHandlers.foreach(zkClient.registerZNodeChildChangeHandler)

    val nodeChangeHandlers = Seq(preferredReplicaElectionHandler, partitionReassignmentHandler)
    nodeChangeHandlers.foreach(zkClient.registerZNodeChangeHandlerAndCheckExistence)

    info("Deleting log dir event notifications")
    zkClient.deleteLogDirEventNotifications(controllerContext.epochZkVersion)
    info("Deleting isr change notifications")
    zkClient.deleteIsrChangeNotifications(controllerContext.epochZkVersion)
    info("Initializing controller context")
    initializeControllerContext()
    info("Fetching topic deletions in progress")
    val (topicsToBeDeleted, topicsIneligibleForDeletion) = fetchTopicDeletionsInProgress()
    info("Initializing topic deletion manager")
    topicDeletionManager.init(topicsToBeDeleted, topicsIneligibleForDeletion)

    // We need to send UpdateMetadataRequest after the controller context is initialized and before the state machines
    // are started. The is because brokers need to receive the list of live brokers from UpdateMetadataRequest before
    // they can process the LeaderAndIsrRequests that are generated by replicaStateMachine.startup() and
    // partitionStateMachine.startup().
    info("Sending update metadata request")
    sendUpdateMetadataRequest(controllerContext.liveOrShuttingDownBrokerIds.toSeq, Set.empty)

    replicaStateMachine.startup() // 启动副本状态机
    partitionStateMachine.startup() // 启动分区状态机

    info(s"Ready to serve as the new controller with epoch $epoch")

    initializePartitionReassignments()
    topicDeletionManager.tryTopicDeletion()
    val pendingPreferredReplicaElections = fetchPendingPreferredReplicaElections()
    onReplicaElection(pendingPreferredReplicaElections, ElectionType.PREFERRED, ZkTriggered)
    info("Starting the controller scheduler")
    kafkaScheduler.startup()
    if (config.autoLeaderRebalanceEnable) {
      scheduleAutoLeaderRebalanceTask(delay = 5, unit = TimeUnit.SECONDS)
    }

    if (config.tokenAuthEnabled) {
      info("starting the token expiry check scheduler")
      tokenCleanScheduler.startup()
      tokenCleanScheduler.schedule("delete-expired-tokens",
        () => tokenManager.expireTokens(),
        0L,
        config.delegationTokenExpiryCheckIntervalMs)
    }
  }

  private def createFeatureZNode(newNode: FeatureZNode): Int = {
    info(s"Creating FeatureZNode at path: ${FeatureZNode.path} with contents: $newNode")
    zkClient.createFeatureZNode(newNode)
    val (_, newVersion) = zkClient.getDataAndVersion(FeatureZNode.path)
    newVersion
  }

  private def updateFeatureZNode(updatedNode: FeatureZNode): Int = {
    info(s"Updating FeatureZNode at path: ${FeatureZNode.path} with contents: $updatedNode")
    zkClient.updateFeatureZNode(updatedNode)
  }

  /**
   * This method enables the feature versioning system (KIP-584).
   *
   * Development in Kafka (from a high level) is organized into features. Each feature is tracked by
   * a name and a range of version numbers or a version number. A feature can be of two types:
   *
   * 1. Supported feature:
   * A supported feature is represented by a name (string) and a range of versions (defined by a
   * SupportedVersionRange). It refers to a feature that a particular broker advertises support for.
   * Each broker advertises the version ranges of its own supported features in its own
   * BrokerIdZNode. The contents of the advertisement are specific to the particular broker and
   * do not represent any guarantee of a cluster-wide availability of the feature for any particular
   * range of versions.
   *
   * 2. Finalized feature:
   * A finalized feature is represented by a name (string) and a specified version level (defined
   * by a Short). Whenever the feature versioning system (KIP-584) is
   * enabled, the finalized features are stored in the cluster-wide common FeatureZNode.
   * In comparison to a supported feature, the key difference is that a finalized feature exists
   * in ZK only when it is guaranteed to be supported by any random broker in the cluster for a
   * specified range of version levels. Also, the controller is the only entity modifying the
   * information about finalized features.
   *
   * This method sets up the FeatureZNode with enabled status, which means that the finalized
   * features stored in the FeatureZNode are active. The enabled status should be written by the
   * controller to the FeatureZNode only when the broker IBP config is greater than or equal to
   * IBP_2_7_IV0.
   *
   * There are multiple cases handled here:
   *
   * 1. New cluster bootstrap:
   *    A new Kafka cluster (i.e. it is deployed first time) is almost always started with IBP config
   *    setting greater than or equal to IBP_2_7_IV0. We would like to start the cluster with all
   *    the possible supported features finalized immediately. Assuming this is the case, the
   *    controller will start up and notice that the FeatureZNode is absent in the new cluster,
   *    it will then create a FeatureZNode (with enabled status) containing the entire list of
   *    supported features as its finalized features.
   *
   * 2. Broker binary upgraded, but IBP config set to lower than IBP_2_7_IV0:
   *    Imagine there was an existing Kafka cluster with IBP config less than IBP_2_7_IV0, and the
   *    broker binary has now been upgraded to a newer version that supports the feature versioning
   *    system (KIP-584). But the IBP config is still set to lower than IBP_2_7_IV0, and may be
   *    set to a higher value later. In this case, we want to start with no finalized features and
   *    allow the user to finalize them whenever they are ready i.e. in the future whenever the
   *    user sets IBP config to be greater than or equal to IBP_2_7_IV0, then the user could start
   *    finalizing the features. This process ensures we do not enable all the possible features
   *    immediately after an upgrade, which could be harmful to Kafka.
   *    This is how we handle such a case:
   *      - Before the IBP config upgrade (i.e. IBP config set to less than IBP_2_7_IV0), the
   *        controller will start up and check if the FeatureZNode is absent.
   *        - If the node is absent, it will react by creating a FeatureZNode with disabled status
   *          and empty finalized features.
   *        - Otherwise, if a node already exists in enabled status then the controller will just
   *          flip the status to disabled and clear the finalized features.
   *      - After the IBP config upgrade (i.e. IBP config set to greater than or equal to
   *        IBP_2_7_IV0), when the controller starts up it will check if the FeatureZNode exists
   *        and whether it is disabled.
   *         - If the node is in disabled status, the controller won’t upgrade all features immediately.
   *           Instead it will just switch the FeatureZNode status to enabled status. This lets the
   *           user finalize the features later.
   *         - Otherwise, if a node already exists in enabled status then the controller will leave
   *           the node umodified.
   *
   * 3. Broker binary upgraded, with existing cluster IBP config >= IBP_2_7_IV0:
   *    Imagine there was an existing Kafka cluster with IBP config >= IBP_2_7_IV0, and the broker
   *    binary has just been upgraded to a newer version (that supports IBP config IBP_2_7_IV0 and
   *    higher). The controller will start up and find that a FeatureZNode is already present with
   *    enabled status and existing finalized features. In such a case, the controller leaves the node
   *    unmodified.
   *
   * 4. Broker downgrade:
   *    Imagine that a Kafka cluster exists already and the IBP config is greater than or equal to
   *    IBP_2_7_IV0. Then, the user decided to downgrade the cluster by setting IBP config to a
   *    value less than IBP_2_7_IV0. This means the user is also disabling the feature versioning
   *    system (KIP-584). In this case, when the controller starts up with the lower IBP config, it
   *    will switch the FeatureZNode status to disabled with empty features.
   */
  private def enableFeatureVersioning(): Unit = {
    val (mayBeFeatureZNodeBytes, version) = zkClient.getDataAndVersion(FeatureZNode.path)
    if (version == ZkVersion.UnknownVersion) {
      val newVersion = createFeatureZNode(
        FeatureZNode(config.interBrokerProtocolVersion,
          FeatureZNodeStatus.Enabled,
          brokerFeatures.defaultFinalizedFeatures
        ))
      featureCache.waitUntilFeatureEpochOrThrow(newVersion, config.zkConnectionTimeoutMs)
    } else {
      val existingFeatureZNode = FeatureZNode.decode(mayBeFeatureZNodeBytes.get)
      val newFeatures = existingFeatureZNode.status match {
        case FeatureZNodeStatus.Enabled => existingFeatureZNode.features
        case FeatureZNodeStatus.Disabled =>
          if (existingFeatureZNode.features.nonEmpty) {
            warn(s"FeatureZNode at path: ${FeatureZNode.path} with disabled status" +
                 s" contains non-empty features: ${existingFeatureZNode.features}")
          }
          Map.empty[String, Short]
      }
      val newFeatureZNode = FeatureZNode(config.interBrokerProtocolVersion, FeatureZNodeStatus.Enabled, newFeatures)
      if (!newFeatureZNode.equals(existingFeatureZNode)) {
        val newVersion = updateFeatureZNode(newFeatureZNode)
        featureCache.waitUntilFeatureEpochOrThrow(newVersion, config.zkConnectionTimeoutMs)
      }
    }
  }

  /**
   * Disables the feature versioning system (KIP-584).
   *
   * Sets up the FeatureZNode with disabled status. This status means the feature versioning system
   * (KIP-584) is disabled, and, the finalized features stored in the FeatureZNode are not relevant.
   * This status should be written by the controller to the FeatureZNode only when the broker
   * IBP config is less than IBP_2_7_IV0.
   *
   * NOTE:
   * 1. When this method returns, existing finalized features (if any) will be cleared from the
   *    FeatureZNode.
   * 2. This method, unlike enableFeatureVersioning() need not wait for the FinalizedFeatureCache
   *    to be updated, because, such updates to the cache (via FinalizedFeatureChangeListener)
   *    are disabled when IBP config is < than IBP_2_7_IV0.
   */
  private def disableFeatureVersioning(): Unit = {
    val newNode = FeatureZNode(config.interBrokerProtocolVersion, FeatureZNodeStatus.Disabled, Map.empty[String, Short])
    val (mayBeFeatureZNodeBytes, version) = zkClient.getDataAndVersion(FeatureZNode.path)
    if (version == ZkVersion.UnknownVersion) {
      createFeatureZNode(newNode)
    } else {
      val existingFeatureZNode = FeatureZNode.decode(mayBeFeatureZNodeBytes.get)
      if (existingFeatureZNode.status == FeatureZNodeStatus.Disabled &&
          existingFeatureZNode.features.nonEmpty) {
        warn(s"FeatureZNode at path: ${FeatureZNode.path} with disabled status" +
             s" contains non-empty features: ${existingFeatureZNode.features}")
      }
      if (!newNode.equals(existingFeatureZNode)) {
        updateFeatureZNode(newNode)
      }
    }
  }

  private def maybeSetupFeatureVersioning(): Unit = {
    if (config.isFeatureVersioningSupported) {
      enableFeatureVersioning()
    } else {
      disableFeatureVersioning()
    }
  }

  private def scheduleAutoLeaderRebalanceTask(delay: Long, unit: TimeUnit): Unit = {
    kafkaScheduler.scheduleOnce("auto-leader-rebalance-task",
      () => eventManager.put(AutoPreferredReplicaLeaderElection),
      unit.toMillis(delay))
  }

  /**
   * This callback is invoked by the zookeeper leader elector when the current broker resigns as the controller. This is
   * required to clean up internal controller data structures
   */
  private def onControllerResignation(): Unit = {
    debug("Resigning")
    // de-register listeners
    // 取消ZooKeeper监听器的注册
    zkClient.unregisterZNodeChildChangeHandler(isrChangeNotificationHandler.path)
    zkClient.unregisterZNodeChangeHandler(partitionReassignmentHandler.path)
    zkClient.unregisterZNodeChangeHandler(preferredReplicaElectionHandler.path)
    zkClient.unregisterZNodeChildChangeHandler(logDirEventNotificationHandler.path)
    unregisterBrokerModificationsHandler(brokerModificationsHandlers.keySet)

    // shutdown leader rebalance scheduler
    // 关闭Kafka线程调度器，其实就是取消定期的Leader重选举
    kafkaScheduler.shutdown()

    // stop token expiry check scheduler
    // 关闭Token过期检查调度器
    tokenCleanScheduler.shutdown()

    // de-register partition ISR listener for on-going partition reassignment task
    // 取消分区重分配监听器的注册
    unregisterPartitionReassignmentIsrChangeHandlers()
    // shutdown partition state machine
    // 关闭分区状态机
    partitionStateMachine.shutdown()
    // 取消主题变更监听器的注册
    zkClient.unregisterZNodeChildChangeHandler(topicChangeHandler.path)
    // 取消分区变更监听器的注册
    unregisterPartitionModificationsHandlers(partitionModificationsHandlers.keys.toSeq)
    // 取消主题删除监听器的注册
    zkClient.unregisterZNodeChildChangeHandler(topicDeletionHandler.path)
    // shutdown replica state machine
    // 关闭副本状态机
    replicaStateMachine.shutdown()
    // 取消Broker变更监听器的注册
    zkClient.unregisterZNodeChildChangeHandler(brokerChangeHandler.path)
    // 关闭Controller通道管理器
    controllerChannelManager.shutdown()
    // 清空集群元数据
    controllerContext.resetContext()

    info("Resigned")
  }

  /*
   * This callback is invoked by the controller's LogDirEventNotificationListener with the list of broker ids who
   * have experienced new log directory failures. In response the controller should send LeaderAndIsrRequest
   * to all these brokers to query the state of their replicas. Replicas with an offline log directory respond with
   * KAFKA_STORAGE_ERROR, which will be handled by the LeaderAndIsrResponseReceived event.
   */
  private def onBrokerLogDirFailure(brokerIds: Seq[Int]): Unit = {
    // send LeaderAndIsrRequest for all replicas on those brokers to see if they are still online.
    info(s"Handling log directory failure for brokers ${brokerIds.mkString(",")}")
    val replicasOnBrokers = controllerContext.replicasOnBrokers(brokerIds.toSet)
    replicaStateMachine.handleStateChanges(replicasOnBrokers.toSeq, OnlineReplica)
  }

  /**
   * This callback is invoked by the replica state machine's broker change listener, with the list of newly started
   * brokers as input. It does the following -
   * 1. Sends update metadata request to all live and shutting down brokers
   * 2. Triggers the OnlinePartition state change for all new/offline partitions
   * 3. It checks whether there are reassigned replicas assigned to any newly started brokers. If
   *    so, it performs the reassignment logic for each topic/partition.
   *
   * Note that we don't need to refresh the leader/isr cache for all topic/partitions at this point for two reasons:
   * 1. The partition state machine, when triggering online state change, will refresh leader and ISR for only those
   *    partitions currently new or offline (rather than every partition this controller is aware of)
   * 2. Even if we do refresh the cache, there is no guarantee that by the time the leader and ISR request reaches
   *    every broker that it is still valid.  Brokers check the leader epoch to determine validity of the request.
   *
   * 第 1 步是移除新增 Broker 在元数据缓存中的信息。你可能会问：“这些 Broker 不都是新增的吗？元数据缓存中有它们的数据吗？”
   * 实际上，这里的 newBrokers 仅仅表示新启动的 Broker，它们不一定是全新的 Broker。因此，这里的删除元数据缓存是非常安全的做法。
   * 第 2、3 步：分别给集群的已有 Broker 和新增 Broker 发送更新元数据请求。这样一来，整个集群上的 Broker 就可以互相感知到彼此，
   * 而且最终所有的 Broker 都能保存相同的分区数据。
   * 第 4 步：将新增 Broker 上的副本状态置为 Online 状态。Online 状态表示这些副本正常提供服务，即 Leader 副本对外提供读写服务，
   * Follower 副本自动向 Leader 副本同步消息。
   * 第 5、6 步：分别重启可能因为新增 Broker 启动、而能够重新被执行的副本迁移和主题删除操作。
   * 第 7 步：为所有新增 Broker 注册 BrokerModificationsHandler 监听器，允许 Controller 监控它们在 ZooKeeper 上的节点的数据变更。
   */
  private def onBrokerStartup(newBrokers: Seq[Int]): Unit = {
    info(s"New broker startup callback for ${newBrokers.mkString(",")}")
    // 第1步：移除元数据中新增Broker对应的副本集合
    newBrokers.foreach(controllerContext.replicasOnOfflineDirs.remove)
    val newBrokersSet = newBrokers.toSet
    val existingBrokers = controllerContext.liveOrShuttingDownBrokerIds.diff(newBrokersSet)
    // Send update metadata request to all the existing brokers in the cluster so that they know about the new brokers
    // via this update. No need to include any partition states in the request since there are no partition state changes.
    // 第2步：给集群现有Broker发送元数据更新请求，令它们感知到新增Broker的到来
    sendUpdateMetadataRequest(existingBrokers.toSeq, Set.empty)
    // Send update metadata request to all the new brokers in the cluster with a full set of partition states for initialization.
    // In cases of controlled shutdown leaders will not be elected when a new broker comes up. So at least in the
    // common controlled shutdown case, the metadata will reach the new brokers faster.
    // 第3步：给新增Broker发送元数据更新请求，令它们同步集群当前的所有分区数据
    sendUpdateMetadataRequest(newBrokers, controllerContext.partitionsWithLeaders)
    // the very first thing to do when a new broker comes up is send it the entire list of partitions that it is
    // supposed to host. Based on that the broker starts the high watermark threads for the input list of partitions
    // 第4步：将新增Broker上的所有副本设置为Online状态，即可用状态
    val allReplicasOnNewBrokers = controllerContext.replicasOnBrokers(newBrokersSet)
    replicaStateMachine.handleStateChanges(allReplicasOnNewBrokers.toSeq, OnlineReplica)
    // when a new broker comes up, the controller needs to trigger leader election for all new and offline partitions
    // to see if these brokers can become leaders for some/all of those
    partitionStateMachine.triggerOnlinePartitionStateChange()
    // check if reassignment of some partitions need to be restarted
    // 第5步：重启之前暂停的副本迁移操作
    maybeResumeReassignments { (_, assignment) =>
      assignment.targetReplicas.exists(newBrokersSet.contains)
    }
    // check if topic deletion needs to be resumed. If at least one replica that belongs to the topic being deleted exists
    // on the newly restarted brokers, there is a chance that topic deletion can resume
    val replicasForTopicsToBeDeleted = allReplicasOnNewBrokers.filter(p => topicDeletionManager.isTopicQueuedUpForDeletion(p.topic))
    // 第6步：重启之前暂停的主题删除操作
    if (replicasForTopicsToBeDeleted.nonEmpty) {
      info(s"Some replicas ${replicasForTopicsToBeDeleted.mkString(",")} for topics scheduled for deletion " +
        s"${controllerContext.topicsToBeDeleted.mkString(",")} are on the newly restarted brokers " +
        s"${newBrokers.mkString(",")}. Signaling restart of topic deletion for these topics")
      topicDeletionManager.resumeDeletionForTopics(replicasForTopicsToBeDeleted.map(_.topic))
    }
    // 第7步：为新增Broker注册BrokerModificationsHandler监听器
    registerBrokerModificationsHandler(newBrokers)
  }

  private def maybeResumeReassignments(shouldResume: (TopicPartition, ReplicaAssignment) => Boolean): Unit = {
    controllerContext.partitionsBeingReassigned.foreach { tp =>
      val currentAssignment = controllerContext.partitionFullReplicaAssignment(tp)
      if (shouldResume(tp, currentAssignment))
        onPartitionReassignment(tp, currentAssignment)
    }
  }

  private def registerBrokerModificationsHandler(brokerIds: Iterable[Int]): Unit = {
    debug(s"Register BrokerModifications handler for $brokerIds")
    brokerIds.foreach { brokerId =>
      val brokerModificationsHandler = new BrokerModificationsHandler(eventManager, brokerId)
      zkClient.registerZNodeChangeHandlerAndCheckExistence(brokerModificationsHandler)
      brokerModificationsHandlers.put(brokerId, brokerModificationsHandler)
    }
  }

  private def unregisterBrokerModificationsHandler(brokerIds: Iterable[Int]): Unit = {
    debug(s"Unregister BrokerModifications handler for $brokerIds")
    brokerIds.foreach { brokerId =>
      brokerModificationsHandlers.remove(brokerId).foreach(handler => zkClient.unregisterZNodeChangeHandler(handler.path))
    }
  }

  /**
   * This callback is invoked by the replica state machine's broker change listener with the list of failed brokers
   * as input. It will call onReplicaBecomeOffline(...) with the list of replicas on those failed brokers as input.
   *
   * 该方法接收一组已终止运行的 Broker ID 列表，首先是更新 Controller 元数据信息，
   * 将给定 Broker 从元数据的 replicasOnOfflineDirs 和 shuttingDownBrokerIds 中移除，
   * 然后为这组 Broker 执行必要的副本清扫工作，也就是 onReplicasBecomeOffline 方法做的事情。
   * 该方法主要依赖于分区状态机和副本状态机来完成对应的工作。这个方法的主要目的是把给定的副本标记成 Offline 状态，即不可用状态。
   * 具体分为以下这几个步骤：
   * 1、利用分区状态机将给定副本所在的分区标记为 Offline 状态；
   * 2、将集群上所有新分区和 Offline 分区状态变更为 Online 状态；
   * 3、将相应的副本对象状态变更为 Offline。
   *
   * Broker 终止，意味着我们必须要删除 Controller 元数据缓存中与之相关的所有项，还要处理这些 Broker 上保存的副本。
   * 最后，我们还要注销之前为该 Broker 注册的 BrokerModificationsHandler 监听器。
   * 其实，主体逻辑在于如何处理 Broker 上的副本对象，即 onReplicasBecomeOffline 方法。
   * 该方法大量调用了 Kafka 副本管理器和分区管理器的相关功能。
   * */
  private def onBrokerFailure(deadBrokers: Seq[Int]): Unit = {
    info(s"Broker failure callback for ${deadBrokers.mkString(",")}")
    // 第1步：为每个待移除Broker，删除元数据对象中的相关项
    // deadBrokers：给定的一组已终止运行的Broker Id列表
    // 更新Controller元数据信息，将给定Broker从元数据的replicasOnOfflineDirs中移除
    deadBrokers.foreach(controllerContext.replicasOnOfflineDirs.remove)
    // 第2步：将待移除Broker从元数据对象中处于已关闭状态的Broker列表中去除
    // 找出这些Broker上的所有副本对象
    val deadBrokersThatWereShuttingDown =
      deadBrokers.filter(id => controllerContext.shuttingDownBrokerIds.remove(id))
    if (deadBrokersThatWereShuttingDown.nonEmpty)
      info(s"Removed ${deadBrokersThatWereShuttingDown.mkString(",")} from list of shutting down brokers.")
    // 第3步：找出待移除Broker上的所有副本对象，执行相应操作，将其置为“不可用状态”（即Offline）
    // 执行副本清扫工作
    val allReplicasOnDeadBrokers = controllerContext.replicasOnBrokers(deadBrokers.toSet)
    onReplicasBecomeOffline(allReplicasOnDeadBrokers)

    // 第4步：注销注册的BrokerModificationsHandler监听器
    unregisterBrokerModificationsHandler(deadBrokers)
  }

  //onBrokerUpdate 就是向集群所有 Broker 发送更新元数据信息请求，把变更信息广播出去。
  private def onBrokerUpdate(updatedBrokerId: Int): Unit = {
    info(s"Broker info update callback for $updatedBrokerId")
    // 给集群所有Broker发送UpdateMetadataRequest，让她它们去更新元数据
    sendUpdateMetadataRequest(controllerContext.liveOrShuttingDownBrokerIds.toSeq, Set.empty)
  }

  /**
    * This method marks the given replicas as offline. It does the following -
    * 1. Marks the given partitions as offline
    * 2. Triggers the OnlinePartition state change for all new/offline partitions
    * 3. Invokes the OfflineReplica state change on the input list of newly offline replicas
    * 4. If no partitions are affected then send UpdateMetadataRequest to live or shutting down brokers
    *
    * Note that we don't need to refresh the leader/isr cache for all topic/partitions at this point. This is because
    * the partition state machine will refresh our cache for us when performing leader election for all new/offline
    * partitions coming online.
    */
  private def onReplicasBecomeOffline(newOfflineReplicas: Set[PartitionAndReplica]): Unit = {
    val (newOfflineReplicasForDeletion, newOfflineReplicasNotForDeletion) =
      newOfflineReplicas.partition(p => topicDeletionManager.isTopicQueuedUpForDeletion(p.topic))

    val partitionsWithOfflineLeader = controllerContext.partitionsWithOfflineLeader

    // trigger OfflinePartition state for all partitions whose current leader is one amongst the newOfflineReplicas
    partitionStateMachine.handleStateChanges(partitionsWithOfflineLeader.toSeq, OfflinePartition)
    // trigger OnlinePartition state changes for offline or new partitions
    val onlineStateChangeResults = partitionStateMachine.triggerOnlinePartitionStateChange()
    // trigger OfflineReplica state change for those newly offline replicas
    replicaStateMachine.handleStateChanges(newOfflineReplicasNotForDeletion.toSeq, OfflineReplica)

    // fail deletion of topics that are affected by the offline replicas
    if (newOfflineReplicasForDeletion.nonEmpty) {
      // it is required to mark the respective replicas in TopicDeletionFailed state since the replica cannot be
      // deleted when its log directory is offline. This will prevent the replica from being in TopicDeletionStarted state indefinitely
      // since topic deletion cannot be retried until at least one replica is in TopicDeletionStarted state
      topicDeletionManager.failReplicaDeletion(newOfflineReplicasForDeletion)
    }

    // If no partition has changed leader or ISR, no UpdateMetadataRequest is sent through PartitionStateMachine
    // and ReplicaStateMachine. In that case, we want to send an UpdateMetadataRequest explicitly to
    // propagate the information about the new offline brokers.
    if (newOfflineReplicasNotForDeletion.isEmpty && onlineStateChangeResults.values.forall(_.isLeft)) {
      sendUpdateMetadataRequest(controllerContext.liveOrShuttingDownBrokerIds.toSeq, Set.empty)
    }
  }

  /**
   * This callback is invoked by the topic change callback with the list of failed brokers as input.
   * It does the following -
   * 1. Move the newly created partitions to the NewPartition state
   * 2. Move the newly created partitions from NewPartition->OnlinePartition state
   */
  private def onNewPartitionCreation(newPartitions: Set[TopicPartition]): Unit = {
    info(s"New partition creation callback for ${newPartitions.mkString(",")}")
    partitionStateMachine.handleStateChanges(newPartitions.toSeq, NewPartition)
    replicaStateMachine.handleStateChanges(controllerContext.replicasForPartition(newPartitions).toSeq, NewReplica)
    partitionStateMachine.handleStateChanges(
      newPartitions.toSeq,
      OnlinePartition,
      Some(OfflinePartitionLeaderElectionStrategy(false))
    )
    replicaStateMachine.handleStateChanges(controllerContext.replicasForPartition(newPartitions).toSeq, OnlineReplica)
  }

  /**
   * This callback is invoked:
   * 1. By the AlterPartitionReassignments API
   * 2. By the reassigned partitions listener which is triggered when the /admin/reassign/partitions znode is created
   * 3. When an ongoing reassignment finishes - this is detected by a change in the partition's ISR znode
   * 4. Whenever a new broker comes up which is part of an ongoing reassignment
   * 5. On controller startup/failover
   *
   * Reassigning replicas for a partition goes through a few steps listed in the code.
   * RS = current assigned replica set
   * ORS = Original replica set for partition
   * TRS = Reassigned (target) replica set
   * AR = The replicas we are adding as part of this reassignment
   * RR = The replicas we are removing as part of this reassignment
   *
   * A reassignment may have up to three phases, each with its own steps:

   * Phase U (Assignment update): Regardless of the trigger, the first step is in the reassignment process
   * is to update the existing assignment state. We always update the state in Zookeeper before
   * we update memory so that it can be resumed upon controller fail-over.
   *
   *   U1. Update ZK with RS = ORS + TRS, AR = TRS - ORS, RR = ORS - TRS.
   *   U2. Update memory with RS = ORS + TRS, AR = TRS - ORS and RR = ORS - TRS
   *   U3. If we are cancelling or replacing an existing reassignment, send StopReplica to all members
   *       of AR in the original reassignment if they are not in TRS from the new assignment
   *
   * To complete the reassignment, we need to bring the new replicas into sync, so depending on the state
   * of the ISR, we will execute one of the following steps.
   *
   * Phase A (when TRS != ISR): The reassignment is not yet complete
   *
   *   A1. Bump the leader epoch for the partition and send LeaderAndIsr updates to RS.
   *   A2. Start new replicas AR by moving replicas in AR to NewReplica state.
   *
   * Phase B (when TRS = ISR): The reassignment is complete
   *
   *   B1. Move all replicas in AR to OnlineReplica state.
   *   B2. Set RS = TRS, AR = [], RR = [] in memory.
   *   B3. Send a LeaderAndIsr request with RS = TRS. This will prevent the leader from adding any replica in TRS - ORS back in the isr.
   *       If the current leader is not in TRS or isn't alive, we move the leader to a new replica in TRS.
   *       We may send the LeaderAndIsr to more than the TRS replicas due to the
   *       way the partition state machine works (it reads replicas from ZK)
   *   B4. Move all replicas in RR to OfflineReplica state. As part of OfflineReplica state change, we shrink the
   *       isr to remove RR in ZooKeeper and send a LeaderAndIsr ONLY to the Leader to notify it of the shrunk isr.
   *       After that, we send a StopReplica (delete = false) to the replicas in RR.
   *   B5. Move all replicas in RR to NonExistentReplica state. This will send a StopReplica (delete = true) to
   *       the replicas in RR to physically delete the replicas on disk.
   *   B6. Update ZK with RS=TRS, AR=[], RR=[].
   *   B7. Remove the ISR reassign listener and maybe update the /admin/reassign_partitions path in ZK to remove this partition from it if present.
   *   B8. After electing leader, the replicas and isr information changes. So resend the update metadata request to every broker.
   *
   * In general, there are two goals we want to aim for:
   * 1. Every replica present in the replica set of a LeaderAndIsrRequest gets the request sent to it
   * 2. Replicas that are removed from a partition's assignment get StopReplica sent to them
   *
   * For example, if ORS = {1,2,3} and TRS = {4,5,6}, the values in the topic and leader/isr paths in ZK
   * may go through the following transitions.
   * RS                AR          RR          leader     isr
   * {1,2,3}           {}          {}          1          {1,2,3}           (initial state)
   * {4,5,6,1,2,3}     {4,5,6}     {1,2,3}     1          {1,2,3}           (step A2)
   * {4,5,6,1,2,3}     {4,5,6}     {1,2,3}     1          {1,2,3,4,5,6}     (phase B)
   * {4,5,6,1,2,3}     {4,5,6}     {1,2,3}     4          {1,2,3,4,5,6}     (step B3)
   * {4,5,6,1,2,3}     {4,5,6}     {1,2,3}     4          {4,5,6}           (step B4)
   * {4,5,6}           {}          {}          4          {4,5,6}           (step B6)
   *
   * Note that we have to update RS in ZK with TRS last since it's the only place where we store ORS persistently.
   * This way, if the controller crashes before that step, we can still recover.
   */
  private def onPartitionReassignment(topicPartition: TopicPartition, reassignment: ReplicaAssignment): Unit = {
    // While a reassignment is in progress, deletion is not allowed
    topicDeletionManager.markTopicIneligibleForDeletion(Set(topicPartition.topic), reason = "topic reassignment in progress")

    updateCurrentReassignment(topicPartition, reassignment)

    val addingReplicas = reassignment.addingReplicas
    val removingReplicas = reassignment.removingReplicas

    if (!isReassignmentComplete(topicPartition, reassignment)) {
      // A1. Send LeaderAndIsr request to every replica in ORS + TRS (with the new RS, AR and RR).
      updateLeaderEpochAndSendRequest(topicPartition, reassignment)
      // A2. replicas in AR -> NewReplica
      startNewReplicasForReassignedPartition(topicPartition, addingReplicas)
    } else {
      // B1. replicas in AR -> OnlineReplica
      replicaStateMachine.handleStateChanges(addingReplicas.map(PartitionAndReplica(topicPartition, _)), OnlineReplica)
      // B2. Set RS = TRS, AR = [], RR = [] in memory.
      val completedReassignment = ReplicaAssignment(reassignment.targetReplicas)
      controllerContext.updatePartitionFullReplicaAssignment(topicPartition, completedReassignment)
      // B3. Send LeaderAndIsr request with a potential new leader (if current leader not in TRS) and
      //   a new RS (using TRS) and same isr to every broker in ORS + TRS or TRS
      moveReassignedPartitionLeaderIfRequired(topicPartition, completedReassignment)
      // B4. replicas in RR -> Offline (force those replicas out of isr)
      // B5. replicas in RR -> NonExistentReplica (force those replicas to be deleted)
      stopRemovedReplicasOfReassignedPartition(topicPartition, removingReplicas)
      // B6. Update ZK with RS = TRS, AR = [], RR = [].
      updateReplicaAssignmentForPartition(topicPartition, completedReassignment)
      // B7. Remove the ISR reassign listener and maybe update the /admin/reassign_partitions path in ZK to remove this partition from it.
      removePartitionFromReassigningPartitions(topicPartition, completedReassignment)
      // B8. After electing a leader in B3, the replicas and isr information changes, so resend the update metadata request to every broker
      sendUpdateMetadataRequest(controllerContext.liveOrShuttingDownBrokerIds.toSeq, Set(topicPartition))
      // signal delete topic thread if reassignment for some partitions belonging to topics being deleted just completed
      topicDeletionManager.resumeDeletionForTopics(Set(topicPartition.topic))
    }
  }

  /**
   * Update the current assignment state in Zookeeper and in memory. If a reassignment is already in
   * progress, then the new reassignment will supplant it and some replicas will be shutdown.
   *
   * Note that due to the way we compute the original replica set, we cannot guarantee that a
   * cancellation will restore the original replica order. Target replicas are always listed
   * first in the replica set in the desired order, which means we have no way to get to the
   * original order if the reassignment overlaps with the current assignment. For example,
   * with an initial assignment of [1, 2, 3] and a reassignment of [3, 4, 2], then the replicas
   * will be encoded as [3, 4, 2, 1] while the reassignment is in progress. If the reassignment
   * is cancelled, there is no way to restore the original order.
   *
   * @param topicPartition The reassigning partition
   * @param reassignment The new reassignment
   */
  private def updateCurrentReassignment(topicPartition: TopicPartition, reassignment: ReplicaAssignment): Unit = {
    val currentAssignment = controllerContext.partitionFullReplicaAssignment(topicPartition)

    if (currentAssignment != reassignment) {
      debug(s"Updating assignment of partition $topicPartition from $currentAssignment to $reassignment")

      // U1. Update assignment state in zookeeper
      updateReplicaAssignmentForPartition(topicPartition, reassignment)
      // U2. Update assignment state in memory
      controllerContext.updatePartitionFullReplicaAssignment(topicPartition, reassignment)

      // If there is a reassignment already in progress, then some of the currently adding replicas
      // may be eligible for immediate removal, in which case we need to stop the replicas.
      val unneededReplicas = currentAssignment.replicas.diff(reassignment.replicas)
      if (unneededReplicas.nonEmpty)
        stopRemovedReplicasOfReassignedPartition(topicPartition, unneededReplicas)
    }

    if (!isAlterPartitionEnabled) {
      val reassignIsrChangeHandler = new PartitionReassignmentIsrChangeHandler(eventManager, topicPartition)
      zkClient.registerZNodeChangeHandler(reassignIsrChangeHandler)
    }

    controllerContext.partitionsBeingReassigned.add(topicPartition)
  }

  /**
   * Trigger a partition reassignment provided that the topic exists and is not being deleted.
   *
   * This is called when a reassignment is initially received either through Zookeeper or through the
   * AlterPartitionReassignments API
   *
   * The `partitionsBeingReassigned` field in the controller context will be updated by this
   * call after the reassignment completes validation and is successfully stored in the topic
   * assignment zNode.
   *
   * @param reassignments The reassignments to begin processing
   * @return A map of any errors in the reassignment. If the error is NONE for a given partition,
   *         then the reassignment was submitted successfully.
   */
  private def maybeTriggerPartitionReassignment(reassignments: Map[TopicPartition, ReplicaAssignment]): Map[TopicPartition, ApiError] = {
    reassignments.map { case (tp, reassignment) =>
      val topic = tp.topic

      val apiError = if (topicDeletionManager.isTopicQueuedUpForDeletion(topic)) {
        info(s"Skipping reassignment of $tp since the topic is currently being deleted")
        new ApiError(Errors.UNKNOWN_TOPIC_OR_PARTITION, "The partition does not exist.")
      } else {
        val assignedReplicas = controllerContext.partitionReplicaAssignment(tp)
        if (assignedReplicas.nonEmpty) {
          try {
            onPartitionReassignment(tp, reassignment)
            ApiError.NONE
          } catch {
            case e: ControllerMovedException =>
              info(s"Failed completing reassignment of partition $tp because controller has moved to another broker")
              throw e
            case e: Throwable =>
              error(s"Error completing reassignment of partition $tp", e)
              new ApiError(Errors.UNKNOWN_SERVER_ERROR)
          }
        } else {
          new ApiError(Errors.UNKNOWN_TOPIC_OR_PARTITION, "The partition does not exist.")
        }
      }

      tp -> apiError
    }
  }

  /**
    * Attempt to elect a replica as leader for each of the given partitions.
    * @param partitions The partitions to have a new leader elected
    * @param electionType The type of election to perform
    * @param electionTrigger The reason for trigger this election
    * @return A map of failed and successful elections. The keys are the topic partitions and the corresponding values are
    *         either the exception that was thrown or new leader & ISR.
    */
  private[this] def onReplicaElection(
    partitions: Set[TopicPartition],
    electionType: ElectionType,
    electionTrigger: ElectionTrigger
  ): Map[TopicPartition, Either[Throwable, LeaderAndIsr]] = {
    info(s"Starting replica leader election ($electionType) for partitions ${partitions.mkString(",")} triggered by $electionTrigger")
    try {
      val strategy = electionType match {
        case ElectionType.PREFERRED => PreferredReplicaPartitionLeaderElectionStrategy
        case ElectionType.UNCLEAN =>
          /* Let's be conservative and only trigger unclean election if the election type is unclean and it was
           * triggered by the admin client
           */
          OfflinePartitionLeaderElectionStrategy(allowUnclean = electionTrigger == AdminClientTriggered)
      }

      val results = partitionStateMachine.handleStateChanges(
        partitions.toSeq,
        OnlinePartition,
        Some(strategy)
      )
      if (electionTrigger != AdminClientTriggered) {
        results.foreach {
          case (tp, Left(throwable)) =>
            if (throwable.isInstanceOf[ControllerMovedException]) {
              info(s"Error completing replica leader election ($electionType) for partition $tp because controller has moved to another broker.", throwable)
              throw throwable
            } else {
              error(s"Error completing replica leader election ($electionType) for partition $tp", throwable)
            }
          case (_, Right(_)) => // Ignored; No need to log or throw exception for the success cases
        }
      }

      results
    } finally {
      if (electionTrigger != AdminClientTriggered) {
        removePartitionsFromPreferredReplicaElection(partitions, electionTrigger == AutoTriggered)
      }
    }
  }

  private def initializeControllerContext(): Unit = {
    // update controller cache with delete topic information
    val curBrokerAndEpochs = zkClient.getAllBrokerAndEpochsInCluster
    val (compatibleBrokerAndEpochs, incompatibleBrokerAndEpochs) = partitionOnFeatureCompatibility(curBrokerAndEpochs)
    if (incompatibleBrokerAndEpochs.nonEmpty) {
      warn("Ignoring registration of new brokers due to incompatibilities with finalized features: " +
        incompatibleBrokerAndEpochs.map { case (broker, _) => broker.id }.toSeq.sorted.mkString(","))
    }
    controllerContext.setLiveBrokers(compatibleBrokerAndEpochs)
    info(s"Initialized broker epochs cache: ${controllerContext.liveBrokerIdAndEpochs}")
    controllerContext.setAllTopics(zkClient.getAllTopicsInCluster(true))
    registerPartitionModificationsHandlers(controllerContext.allTopics.toSeq)
    val replicaAssignmentAndTopicIds = zkClient.getReplicaAssignmentAndTopicIdForTopics(controllerContext.allTopics.toSet)
    processTopicIds(replicaAssignmentAndTopicIds)

    replicaAssignmentAndTopicIds.foreach { case TopicIdReplicaAssignment(_, _, assignments) =>
      assignments.foreach { case (topicPartition, replicaAssignment) =>
        controllerContext.updatePartitionFullReplicaAssignment(topicPartition, replicaAssignment)
        if (replicaAssignment.isBeingReassigned)
          controllerContext.partitionsBeingReassigned.add(topicPartition)
      }
    }
    controllerContext.clearPartitionLeadershipInfo()
    controllerContext.shuttingDownBrokerIds.clear()
    // register broker modifications handlers
    registerBrokerModificationsHandler(controllerContext.liveOrShuttingDownBrokerIds)
    // update the leader and isr cache for all existing partitions from Zookeeper
    updateLeaderAndIsrCache()
    // start the channel manager
    controllerChannelManager.startup(controllerContext.liveOrShuttingDownBrokers)
    info(s"Currently active brokers in the cluster: ${controllerContext.liveBrokerIds}")
    info(s"Currently shutting brokers in the cluster: ${controllerContext.shuttingDownBrokerIds}")
    info(s"Current list of topics in the cluster: ${controllerContext.allTopics}")
  }

  private def fetchPendingPreferredReplicaElections(): Set[TopicPartition] = {
    val partitionsUndergoingPreferredReplicaElection = zkClient.getPreferredReplicaElection
    // check if they are already completed or topic was deleted
    val partitionsThatCompletedPreferredReplicaElection = partitionsUndergoingPreferredReplicaElection.filter { partition =>
      val replicas = controllerContext.partitionReplicaAssignment(partition)
      val topicDeleted = replicas.isEmpty
      val successful =
        if (!topicDeleted) controllerContext.partitionLeadershipInfo(partition).get.leaderAndIsr.leader == replicas.head else false
      successful || topicDeleted
    }
    val pendingPreferredReplicaElectionsIgnoringTopicDeletion = partitionsUndergoingPreferredReplicaElection -- partitionsThatCompletedPreferredReplicaElection
    val pendingPreferredReplicaElectionsSkippedFromTopicDeletion = pendingPreferredReplicaElectionsIgnoringTopicDeletion.filter(partition => topicDeletionManager.isTopicQueuedUpForDeletion(partition.topic))
    val pendingPreferredReplicaElections = pendingPreferredReplicaElectionsIgnoringTopicDeletion -- pendingPreferredReplicaElectionsSkippedFromTopicDeletion
    info(s"Partitions undergoing preferred replica election: ${partitionsUndergoingPreferredReplicaElection.mkString(",")}")
    info(s"Partitions that completed preferred replica election: ${partitionsThatCompletedPreferredReplicaElection.mkString(",")}")
    info(s"Skipping preferred replica election for partitions due to topic deletion: ${pendingPreferredReplicaElectionsSkippedFromTopicDeletion.mkString(",")}")
    info(s"Resuming preferred replica election for partitions: ${pendingPreferredReplicaElections.mkString(",")}")
    pendingPreferredReplicaElections
  }

  /**
   * Initialize pending reassignments. This includes reassignments sent through /admin/reassign_partitions,
   * which will supplant any API reassignments already in progress.
   */
  private def initializePartitionReassignments(): Unit = {
    // New reassignments may have been submitted through Zookeeper while the controller was failing over
    val zkPartitionsResumed = processZkPartitionReassignment()
    // We may also have some API-based reassignments that need to be restarted
    maybeResumeReassignments { (tp, _) =>
      !zkPartitionsResumed.contains(tp)
    }
  }

  private def fetchTopicDeletionsInProgress(): (Set[String], Set[String]) = {
    val topicsToBeDeleted = zkClient.getTopicDeletions.toSet
    val topicsWithOfflineReplicas = controllerContext.allTopics.filter { topic => {
      val replicasForTopic = controllerContext.replicasForTopic(topic)
      replicasForTopic.exists(r => !controllerContext.isReplicaOnline(r.replica, r.topicPartition))
    }}
    val topicsForWhichPartitionReassignmentIsInProgress = controllerContext.partitionsBeingReassigned.map(_.topic)
    val topicsIneligibleForDeletion = topicsWithOfflineReplicas | topicsForWhichPartitionReassignmentIsInProgress
    info(s"List of topics to be deleted: ${topicsToBeDeleted.mkString(",")}")
    info(s"List of topics ineligible for deletion: ${topicsIneligibleForDeletion.mkString(",")}")
    (topicsToBeDeleted, topicsIneligibleForDeletion)
  }

  private def updateLeaderAndIsrCache(partitions: Seq[TopicPartition] = controllerContext.allPartitions.toSeq): Unit = {
    val leaderIsrAndControllerEpochs = zkClient.getTopicPartitionStates(partitions)
    leaderIsrAndControllerEpochs.forKeyValue { (partition, leaderIsrAndControllerEpoch) =>
      controllerContext.putPartitionLeadershipInfo(partition, leaderIsrAndControllerEpoch)
    }
  }

  private def isReassignmentComplete(partition: TopicPartition, assignment: ReplicaAssignment): Boolean = {
    if (!assignment.isBeingReassigned) {
      true
    } else {
      zkClient.getTopicPartitionStates(Seq(partition)).get(partition).exists { leaderIsrAndControllerEpoch =>
        val isr = leaderIsrAndControllerEpoch.leaderAndIsr.isr.toSet
        val targetReplicas = assignment.targetReplicas.toSet
        targetReplicas.subsetOf(isr)
      }
    }
  }

  private def moveReassignedPartitionLeaderIfRequired(topicPartition: TopicPartition,
                                                      newAssignment: ReplicaAssignment): Unit = {
    val reassignedReplicas = newAssignment.replicas
    val currentLeader = controllerContext.partitionLeadershipInfo(topicPartition).get.leaderAndIsr.leader

    if (!reassignedReplicas.contains(currentLeader)) {
      info(s"Leader $currentLeader for partition $topicPartition being reassigned, " +
        s"is not in the new list of replicas ${reassignedReplicas.mkString(",")}. Re-electing leader")
      // move the leader to one of the alive and caught up new replicas
      partitionStateMachine.handleStateChanges(Seq(topicPartition), OnlinePartition, Some(ReassignPartitionLeaderElectionStrategy))
    } else if (controllerContext.isReplicaOnline(currentLeader, topicPartition)) {
      info(s"Leader $currentLeader for partition $topicPartition being reassigned, " +
        s"is already in the new list of replicas ${reassignedReplicas.mkString(",")} and is alive")
      // shrink replication factor and update the leader epoch in zookeeper to use on the next LeaderAndIsrRequest
      updateLeaderEpochAndSendRequest(topicPartition, newAssignment)
    } else {
      info(s"Leader $currentLeader for partition $topicPartition being reassigned, " +
        s"is already in the new list of replicas ${reassignedReplicas.mkString(",")} but is dead")
      partitionStateMachine.handleStateChanges(Seq(topicPartition), OnlinePartition, Some(ReassignPartitionLeaderElectionStrategy))
    }
  }

  private def stopRemovedReplicasOfReassignedPartition(topicPartition: TopicPartition,
                                                       removedReplicas: Seq[Int]): Unit = {
    // first move the replica to offline state (the controller removes it from the ISR)
    val replicasToBeDeleted = removedReplicas.map(PartitionAndReplica(topicPartition, _))
    replicaStateMachine.handleStateChanges(replicasToBeDeleted, OfflineReplica)
    // send stop replica command to the old replicas
    replicaStateMachine.handleStateChanges(replicasToBeDeleted, ReplicaDeletionStarted)
    // TODO: Eventually partition reassignment could use a callback that does retries if deletion failed
    replicaStateMachine.handleStateChanges(replicasToBeDeleted, ReplicaDeletionSuccessful)
    replicaStateMachine.handleStateChanges(replicasToBeDeleted, NonExistentReplica)
  }

  private def updateReplicaAssignmentForPartition(topicPartition: TopicPartition, assignment: ReplicaAssignment): Unit = {
    val topicAssignment = mutable.Map() ++=
      controllerContext.partitionFullReplicaAssignmentForTopic(topicPartition.topic) +=
      (topicPartition -> assignment)

    val setDataResponse = zkClient.setTopicAssignmentRaw(topicPartition.topic,
      controllerContext.topicIds.get(topicPartition.topic),
      topicAssignment, controllerContext.epochZkVersion)
    setDataResponse.resultCode match {
      case Code.OK =>
        info(s"Successfully updated assignment of partition $topicPartition to $assignment")
      case Code.NONODE =>
        throw new IllegalStateException(s"Failed to update assignment for $topicPartition since the topic " +
          "has no current assignment")
      case _ => throw new KafkaException(setDataResponse.resultException.get)
    }
  }

  private def startNewReplicasForReassignedPartition(topicPartition: TopicPartition, newReplicas: Seq[Int]): Unit = {
    // send the start replica request to the brokers in the reassigned replicas list that are not in the assigned
    // replicas list
    newReplicas.foreach { replica =>
      replicaStateMachine.handleStateChanges(Seq(PartitionAndReplica(topicPartition, replica)), NewReplica)
    }
  }

  private def updateLeaderEpochAndSendRequest(topicPartition: TopicPartition,
                                              assignment: ReplicaAssignment): Unit = {
    val stateChangeLog = stateChangeLogger.withControllerEpoch(controllerContext.epoch)
    updateLeaderEpoch(topicPartition) match {
      case Some(updatedLeaderIsrAndControllerEpoch) =>
        try {
          brokerRequestBatch.newBatch()
          // the isNew flag, when set to true, makes sure that when a replica possibly resided
          // in a logDir that is offline, we refrain from just creating a new replica in a good
          // logDir. This is exactly the behavior we want for the original replicas, but not
          // for the replicas we add in this reassignment. For new replicas, want to be able
          // to assign to one of the good logDirs.
          brokerRequestBatch.addLeaderAndIsrRequestForBrokers(assignment.originReplicas, topicPartition,
            updatedLeaderIsrAndControllerEpoch, assignment, isNew = false)
          brokerRequestBatch.addLeaderAndIsrRequestForBrokers(assignment.addingReplicas, topicPartition,
            updatedLeaderIsrAndControllerEpoch, assignment, isNew = true)
          brokerRequestBatch.sendRequestsToBrokers(controllerContext.epoch)
        } catch {
          case e: IllegalStateException =>
            handleIllegalState(e)
        }
        stateChangeLog.info(s"Sent LeaderAndIsr request $updatedLeaderIsrAndControllerEpoch with " +
          s"new replica assignment $assignment to leader ${updatedLeaderIsrAndControllerEpoch.leaderAndIsr.leader} " +
          s"for partition being reassigned $topicPartition")

      case None => // fail the reassignment
        stateChangeLog.error(s"Failed to send LeaderAndIsr request with new replica assignment " +
          s"$assignment to leader for partition being reassigned $topicPartition")
    }
  }

  private def registerPartitionModificationsHandlers(topics: Seq[String]): Unit = {
    topics.foreach { topic =>
      val partitionModificationsHandler = new PartitionModificationsHandler(eventManager, topic)
      partitionModificationsHandlers.put(topic, partitionModificationsHandler)
    }
    partitionModificationsHandlers.values.foreach(zkClient.registerZNodeChangeHandler)
  }

  private[controller] def unregisterPartitionModificationsHandlers(topics: Seq[String]): Unit = {
    topics.foreach { topic =>
      partitionModificationsHandlers.remove(topic).foreach(handler => zkClient.unregisterZNodeChangeHandler(handler.path))
    }
  }

  private def unregisterPartitionReassignmentIsrChangeHandlers(): Unit = {
    if (!isAlterPartitionEnabled) {
      controllerContext.partitionsBeingReassigned.foreach { tp =>
        val path = TopicPartitionStateZNode.path(tp)
        zkClient.unregisterZNodeChangeHandler(path)
      }
    }
  }

  private def removePartitionFromReassigningPartitions(topicPartition: TopicPartition,
                                                       assignment: ReplicaAssignment): Unit = {
    if (controllerContext.partitionsBeingReassigned.contains(topicPartition)) {
      if (!isAlterPartitionEnabled) {
        val path = TopicPartitionStateZNode.path(topicPartition)
        zkClient.unregisterZNodeChangeHandler(path)
      }
      maybeRemoveFromZkReassignment((tp, replicas) => tp == topicPartition && replicas == assignment.replicas)
      controllerContext.partitionsBeingReassigned.remove(topicPartition)
    } else {
      throw new IllegalStateException("Cannot remove a reassigning partition because it is not present in memory")
    }
  }

  /**
   * Remove partitions from an active zk-based reassignment (if one exists).
   *
   * @param shouldRemoveReassignment Predicate indicating which partition reassignments should be removed
   */
  private def maybeRemoveFromZkReassignment(shouldRemoveReassignment: (TopicPartition, Seq[Int]) => Boolean): Unit = {
    if (!zkClient.reassignPartitionsInProgress)
      return

    val reassigningPartitions = zkClient.getPartitionReassignment
    val (removingPartitions, updatedPartitionsBeingReassigned) = reassigningPartitions.partition { case (tp, replicas) =>
      shouldRemoveReassignment(tp, replicas)
    }
    info(s"Removing partitions $removingPartitions from the list of reassigned partitions in zookeeper")

    // write the new list to zookeeper
    if (updatedPartitionsBeingReassigned.isEmpty) {
      info(s"No more partitions need to be reassigned. Deleting zk path ${ReassignPartitionsZNode.path}")
      zkClient.deletePartitionReassignment(controllerContext.epochZkVersion)
      // Ensure we detect future reassignments
      eventManager.put(ZkPartitionReassignment)
    } else {
      try {
        zkClient.setOrCreatePartitionReassignment(updatedPartitionsBeingReassigned, controllerContext.epochZkVersion)
      } catch {
        case e: KeeperException => throw new AdminOperationException(e)
      }
    }
  }

  private def removePartitionsFromPreferredReplicaElection(partitionsToBeRemoved: Set[TopicPartition],
                                                           isTriggeredByAutoRebalance : Boolean): Unit = {
    for (partition <- partitionsToBeRemoved) {
      // check the status
      val currentLeader = controllerContext.partitionLeadershipInfo(partition).get.leaderAndIsr.leader
      val preferredReplica = controllerContext.partitionReplicaAssignment(partition).head
      if (currentLeader == preferredReplica) {
        info(s"Partition $partition completed preferred replica leader election. New leader is $preferredReplica")
      } else {
        warn(s"Partition $partition failed to complete preferred replica leader election to $preferredReplica. " +
          s"Leader is still $currentLeader")
      }
    }
    if (!isTriggeredByAutoRebalance) {
      zkClient.deletePreferredReplicaElection(controllerContext.epochZkVersion)
      // Ensure we detect future preferred replica leader elections
      eventManager.put(ReplicaLeaderElection(None, ElectionType.PREFERRED, ZkTriggered))
    }
  }

  /**
   * Send the leader information for selected partitions to selected brokers so that they can correctly respond to
   * metadata requests
   *
   * @param brokers The brokers that the update metadata request should be sent to
   */
  private[controller] def sendUpdateMetadataRequest(brokers: Seq[Int], partitions: Set[TopicPartition]): Unit = {
    try {
      brokerRequestBatch.newBatch()
      brokerRequestBatch.addUpdateMetadataRequestForBrokers(brokers, partitions)
      brokerRequestBatch.sendRequestsToBrokers(epoch)
    } catch {
      case e: IllegalStateException =>
        handleIllegalState(e)
    }
  }

  /**
   * Does not change leader or isr, but just increments the leader epoch
   *
   * @param partition partition
   * @return the new leaderAndIsr with an incremented leader epoch, or None if leaderAndIsr is empty.
   */
  private def updateLeaderEpoch(partition: TopicPartition): Option[LeaderIsrAndControllerEpoch] = {
    debug(s"Updating leader epoch for partition $partition")
    var finalLeaderIsrAndControllerEpoch: Option[LeaderIsrAndControllerEpoch] = None
    var zkWriteCompleteOrUnnecessary = false
    while (!zkWriteCompleteOrUnnecessary) {
      // refresh leader and isr from zookeeper again
      zkWriteCompleteOrUnnecessary = zkClient.getTopicPartitionStates(Seq(partition)).get(partition) match {
        case Some(leaderIsrAndControllerEpoch) =>
          val leaderAndIsr = leaderIsrAndControllerEpoch.leaderAndIsr
          val controllerEpoch = leaderIsrAndControllerEpoch.controllerEpoch
          if (controllerEpoch > epoch)
            throw new StateChangeFailedException("Leader and isr path written by another controller. This probably " +
              s"means the current controller with epoch $epoch went through a soft failure and another " +
              s"controller was elected with epoch $controllerEpoch. Aborting state change by this controller")
          // increment the leader epoch even if there are no leader or isr changes to allow the leader to cache the expanded
          // assigned replica list
          val newLeaderAndIsr = leaderAndIsr.newEpoch
          // update the new leadership decision in zookeeper or retry
          val UpdateLeaderAndIsrResult(finishedUpdates, _) =
            zkClient.updateLeaderAndIsr(immutable.Map(partition -> newLeaderAndIsr), epoch, controllerContext.epochZkVersion)

          finishedUpdates.get(partition) match {
            case Some(Right(leaderAndIsr)) =>
              val leaderIsrAndControllerEpoch = LeaderIsrAndControllerEpoch(leaderAndIsr, epoch)
              controllerContext.putPartitionLeadershipInfo(partition, leaderIsrAndControllerEpoch)
              finalLeaderIsrAndControllerEpoch = Some(leaderIsrAndControllerEpoch)
              info(s"Updated leader epoch for partition $partition to ${leaderAndIsr.leaderEpoch}, zkVersion=${leaderAndIsr.partitionEpoch}")
              true
            case Some(Left(e)) => throw e
            case None => false
          }
        case None =>
          throw new IllegalStateException(s"Cannot update leader epoch for partition $partition as " +
            "leaderAndIsr path is empty. This could mean we somehow tried to reassign a partition that doesn't exist")
      }
    }
    finalLeaderIsrAndControllerEpoch
  }

  private def checkAndTriggerAutoLeaderRebalance(): Unit = {
    trace("Checking need to trigger auto leader balancing")
    val preferredReplicasForTopicsByBrokers: Map[Int, Map[TopicPartition, Seq[Int]]] =
      controllerContext.allPartitions.filterNot {
        tp => topicDeletionManager.isTopicQueuedUpForDeletion(tp.topic)
      }.map { tp =>
        (tp, controllerContext.partitionReplicaAssignment(tp) )
      }.toMap.groupBy { case (_, assignedReplicas) => assignedReplicas.head }

    // for each broker, check if a preferred replica election needs to be triggered
    preferredReplicasForTopicsByBrokers.forKeyValue { (leaderBroker, topicPartitionsForBroker) =>
      val topicsNotInPreferredReplica = topicPartitionsForBroker.filter { case (topicPartition, _) =>
        val leadershipInfo = controllerContext.partitionLeadershipInfo(topicPartition)
        leadershipInfo.exists(_.leaderAndIsr.leader != leaderBroker)
      }
      debug(s"Topics not in preferred replica for broker $leaderBroker $topicsNotInPreferredReplica")

      val imbalanceRatio = topicsNotInPreferredReplica.size.toDouble / topicPartitionsForBroker.size
      trace(s"Leader imbalance ratio for broker $leaderBroker is $imbalanceRatio")

      // check ratio and if greater than desired ratio, trigger a rebalance for the topic partitions
      // that need to be on this broker
      if (imbalanceRatio > (config.leaderImbalancePerBrokerPercentage.toDouble / 100)) {
        val candidatePartitions = topicsNotInPreferredReplica.keys.filter(tp =>
          !topicDeletionManager.isTopicQueuedUpForDeletion(tp.topic) &&
          controllerContext.allTopics.contains(tp.topic) &&
          canPreferredReplicaBeLeader(tp)
       )
        onReplicaElection(candidatePartitions.toSet, ElectionType.PREFERRED, AutoTriggered)
      }
    }
  }

  private def canPreferredReplicaBeLeader(tp: TopicPartition): Boolean = {
    val assignment = controllerContext.partitionReplicaAssignment(tp)
    val liveReplicas = assignment.filter(replica => controllerContext.isReplicaOnline(replica, tp))
    val isr = controllerContext.partitionLeadershipInfo(tp).get.leaderAndIsr.isr
    PartitionLeaderElectionAlgorithms
      .preferredReplicaPartitionLeaderElection(assignment, isr, liveReplicas.toSet)
      .nonEmpty
  }

  private def processAutoPreferredReplicaLeaderElection(): Unit = {
    if (!isActive) return
    try {
      info("Processing automatic preferred replica leader election")
      checkAndTriggerAutoLeaderRebalance()
    } finally {
      scheduleAutoLeaderRebalanceTask(delay = config.leaderImbalanceCheckIntervalSeconds, unit = TimeUnit.SECONDS)
    }
  }

  private def processUncleanLeaderElectionEnable(): Unit = {
    if (!isActive) return
    info("Unclean leader election has been enabled by default")
    partitionStateMachine.triggerOnlinePartitionStateChange()
  }

  private def processTopicUncleanLeaderElectionEnable(topic: String): Unit = {
    if (!isActive) return
    info(s"Unclean leader election has been enabled for topic $topic")
    partitionStateMachine.triggerOnlinePartitionStateChange(topic)
  }

  private def processControlledShutdown(id: Int, brokerEpoch: Long, controlledShutdownCallback: Try[Set[TopicPartition]] => Unit): Unit = {
    val controlledShutdownResult = Try { doControlledShutdown(id, brokerEpoch) }
    controlledShutdownCallback(controlledShutdownResult)
  }

  private def doControlledShutdown(id: Int, brokerEpoch: Long): Set[TopicPartition] = {
    if (!isActive) {
      throw new ControllerMovedException("Controller moved to another broker. Aborting controlled shutdown")
    }

    // broker epoch in the request is unknown if the controller hasn't been upgraded to use KIP-380
    // so we will keep the previous behavior and don't reject the request
    if (brokerEpoch != AbstractControlRequest.UNKNOWN_BROKER_EPOCH) {
      val cachedBrokerEpoch = controllerContext.liveBrokerIdAndEpochs(id)
      if (brokerEpoch < cachedBrokerEpoch) {
        val stateBrokerEpochErrorMessage = "Received controlled shutdown request from an old broker epoch " +
          s"$brokerEpoch for broker $id. Current broker epoch is $cachedBrokerEpoch."
        info(stateBrokerEpochErrorMessage)
        throw new StaleBrokerEpochException(stateBrokerEpochErrorMessage)
      }
    }

    info(s"Shutting down broker $id")

    if (!controllerContext.liveOrShuttingDownBrokerIds.contains(id))
      throw new BrokerNotAvailableException(s"Broker id $id does not exist.")

    controllerContext.shuttingDownBrokerIds.add(id)
    debug(s"All shutting down brokers: ${controllerContext.shuttingDownBrokerIds.mkString(",")}")
    debug(s"Live brokers: ${controllerContext.liveBrokerIds.mkString(",")}")

    val partitionsToActOn = controllerContext.partitionsOnBroker(id).filter { partition =>
      controllerContext.partitionReplicaAssignment(partition).size > 1 &&
        controllerContext.partitionLeadershipInfo(partition).isDefined &&
        !topicDeletionManager.isTopicQueuedUpForDeletion(partition.topic)
    }
    val (partitionsLedByBroker, partitionsFollowedByBroker) = partitionsToActOn.partition { partition =>
      controllerContext.partitionLeadershipInfo(partition).get.leaderAndIsr.leader == id
    }
    partitionStateMachine.handleStateChanges(partitionsLedByBroker.toSeq, OnlinePartition, Some(ControlledShutdownPartitionLeaderElectionStrategy))
    try {
      brokerRequestBatch.newBatch()
      partitionsFollowedByBroker.foreach { partition =>
        brokerRequestBatch.addStopReplicaRequestForBrokers(Seq(id), partition, deletePartition = false)
      }
      brokerRequestBatch.sendRequestsToBrokers(epoch)
    } catch {
      case e: IllegalStateException =>
        handleIllegalState(e)
    }
    // If the broker is a follower, updates the isr in ZK and notifies the current leader
    replicaStateMachine.handleStateChanges(partitionsFollowedByBroker.map(partition =>
      PartitionAndReplica(partition, id)).toSeq, OfflineReplica)
    trace(s"All leaders = ${controllerContext.partitionsLeadershipInfo.mkString(",")}")
    controllerContext.partitionLeadersOnBroker(id)
  }

  private def processUpdateMetadataResponseReceived(updateMetadataResponse: UpdateMetadataResponse, brokerId: Int): Unit = {
    if (!isActive) return

    if (updateMetadataResponse.error != Errors.NONE) {
      stateChangeLogger.error(s"Received error ${updateMetadataResponse.error} in UpdateMetadata " +
        s"response $updateMetadataResponse from broker $brokerId")
    }
  }

  private def processLeaderAndIsrResponseReceived(leaderAndIsrResponse: LeaderAndIsrResponse, brokerId: Int): Unit = {
    if (!isActive) return

    if (leaderAndIsrResponse.error != Errors.NONE) {
      stateChangeLogger.error(s"Received error ${leaderAndIsrResponse.error} in LeaderAndIsr " +
        s"response $leaderAndIsrResponse from broker $brokerId")
      return
    }

    val offlineReplicas = new ArrayBuffer[TopicPartition]()
    val onlineReplicas = new ArrayBuffer[TopicPartition]()

    leaderAndIsrResponse.partitionErrors(controllerContext.topicNames.asJava).forEach{ case (tp, error) =>
      if (error.code() == Errors.KAFKA_STORAGE_ERROR.code)
        offlineReplicas += tp
      else if (error.code() == Errors.NONE.code)
        onlineReplicas += tp
    }

    val previousOfflineReplicas = controllerContext.replicasOnOfflineDirs.getOrElse(brokerId, Set.empty[TopicPartition])
    val currentOfflineReplicas = mutable.Set() ++= previousOfflineReplicas --= onlineReplicas ++= offlineReplicas
    controllerContext.replicasOnOfflineDirs.put(brokerId, currentOfflineReplicas)
    val newOfflineReplicas = currentOfflineReplicas.diff(previousOfflineReplicas)

    if (newOfflineReplicas.nonEmpty) {
      stateChangeLogger.info(s"Mark replicas ${newOfflineReplicas.mkString(",")} on broker $brokerId as offline")
      onReplicasBecomeOffline(newOfflineReplicas.map(PartitionAndReplica(_, brokerId)))
    }
  }

  private def processTopicDeletionStopReplicaResponseReceived(replicaId: Int,
                                                              requestError: Errors,
                                                              partitionErrors: Map[TopicPartition, Errors]): Unit = {
    if (!isActive) return
    debug(s"Delete topic callback invoked on StopReplica response received from broker $replicaId: " +
      s"request error = $requestError, partition errors = $partitionErrors")

    val partitionsInError = if (requestError != Errors.NONE)
      partitionErrors.keySet
    else
      partitionErrors.filter { case (_, error) => error != Errors.NONE }.keySet

    val replicasInError = partitionsInError.map(PartitionAndReplica(_, replicaId))
    // move all the failed replicas to ReplicaDeletionIneligible
    topicDeletionManager.failReplicaDeletion(replicasInError)
    if (replicasInError.size != partitionErrors.size) {
      // some replicas could have been successfully deleted
      val deletedReplicas = partitionErrors.keySet.diff(partitionsInError)
      topicDeletionManager.completeReplicaDeletion(deletedReplicas.map(PartitionAndReplica(_, replicaId)))
    }
  }
  // 注册ControllerChangeHandler ZooKeeper监听器
  private def processStartup(): Unit = {
    zkClient.registerZNodeChangeHandlerAndCheckExistence(controllerChangeHandler)
    // 执行Controller选举
    elect()
  }

  private def updateMetrics(): Unit = {
    if (isActive) {
      offlinePartitionCount = controllerContext.offlinePartitionCount
      preferredReplicaImbalanceCount = controllerContext.preferredReplicaImbalanceCount
      globalTopicCount = controllerContext.allTopics.size
      globalPartitionCount = controllerContext.partitionWithLeadersCount
      topicsToDeleteCount = controllerContext.topicsToBeDeleted.size
      replicasToDeleteCount = controllerContext.topicsToBeDeleted.map { topic =>
        // For each enqueued topic, count the number of replicas that are not yet deleted
        controllerContext.replicasForTopic(topic).count { replica =>
          controllerContext.replicaState(replica) != ReplicaDeletionSuccessful
        }
      }.sum
      ineligibleTopicsToDeleteCount = controllerContext.topicsIneligibleForDeletion.size
      ineligibleReplicasToDeleteCount = controllerContext.topicsToBeDeleted.map { topic =>
        // For each enqueued topic, count the number of replicas that are ineligible
        controllerContext.replicasForTopic(topic).count { replica =>
          controllerContext.replicaState(replica) == ReplicaDeletionIneligible
        }
      }.sum
      activeBrokerCount = controllerContext.liveOrShuttingDownBrokerIds.size
    } else {
      offlinePartitionCount = 0
      preferredReplicaImbalanceCount = 0
      globalTopicCount = 0
      globalPartitionCount = 0
      topicsToDeleteCount = 0
      replicasToDeleteCount = 0
      ineligibleTopicsToDeleteCount = 0
      ineligibleReplicasToDeleteCount = 0
      activeBrokerCount = 0
    }
  }

  // visible for testing
  private[controller] def handleIllegalState(e: IllegalStateException): Nothing = {
    // Resign if the controller is in an illegal state
    error("Forcing the controller to resign")
    brokerRequestBatch.clear()
    triggerControllerMove()
    throw e
  }

  private def triggerControllerMove(): Unit = {
    activeControllerId = zkClient.getControllerId.getOrElse(-1)
    if (!isActive) {
      warn("Controller has already moved when trying to trigger controller movement")
      return
    }
    try {
      val expectedControllerEpochZkVersion = controllerContext.epochZkVersion
      activeControllerId = -1
      onControllerResignation()
      zkClient.deleteController(expectedControllerEpochZkVersion)
    } catch {
      case _: ControllerMovedException =>
        warn("Controller has already moved when trying to trigger controller movement")
    }
  }

  /**
   * 执行选举 Controller的场景三：/controller 节点数据变更
   * Broker 检测到 /controller 节点数据发生变化，通常表明，Controller“易主”了，这就分为两种情况：
   * 1、如果 Broker 之前是 Controller，那么该 Broker 需要首先执行卸任操作，然后再尝试竞选；
   * 2、如果 Broker 之前不是 Controller，那么，该 Broker 直接去竞选新 Controller。
   */
  private def maybeResign(): Unit = {
    // 非常关键的一步！这是判断是否需要执行卸任逻辑的重要依据
    // 判断该Broker之前是否是Controller
    val wasActiveBeforeChange = isActive
    // 注册ControllerChangeHandler监听器
    zkClient.registerZNodeChangeHandlerAndCheckExistence(controllerChangeHandler)
    // 获取当前集群Controller所在的Broker Id，如果没有Controller则返回-1
    activeControllerId = zkClient.getControllerId.getOrElse(-1)
    // 如果该Broker之前是Controller但现在不是了
    if (wasActiveBeforeChange && !isActive) {
      onControllerResignation()// 执行卸任逻辑
    }
  }

  /**
   * Controller 选举
   * 该方法首先检查 Controller 是否已经选出来了。要知道，集群中的所有 Broker 都要执行这些逻辑，
   * 因此，非常有可能出现某些 Broker 在执行 elect 方法时，Controller 已经被选出来的情况。
   * 如果 Controller 已经选出来了，那么，自然也就不用再做什么了。
   * 相反地，如果 Controller 尚未被选举出来，那么，代码会尝试创建 /controller 节点去抢注 Controller。
   *
   * 一旦抢注成功，就调用 onControllerFailover 方法，执行选举成功后的动作。
   * 这些动作包括注册各类 ZooKeeper 监听器、删除日志路径变更和 ISR 副本变更通知事件、启动 Controller 通道管理器，
   * 以及启动副本状态机和分区状态机。
   *
   * 如果抢注失败了，代码会抛出 ControllerMovedException 异常。这通常表明 Controller 已经被其他 Broker 抢先占据了，
   * 那么，此时代码调用 maybeResign 方法去执行卸任逻辑。
   */
  private def elect(): Unit = {
    // 第1步：获取当前Controller所在Broker的序号，如果Controller不存在，显式标记为-1
    activeControllerId = zkClient.getControllerId.getOrElse(-1)
    /*
     * We can get here during the initial startup and the handleDeleted ZK callback. Because of the potential race condition,
     * it's possible that the controller has already been elected when we get here. This check will prevent the following
     * createEphemeralPath method from getting into an infinite loop if this broker is already the controller.
     */
    // 第2步：如果当前Controller已经选出来了，直接返回即可
    if (activeControllerId != -1) {
      debug(s"Broker $activeControllerId has been elected as the controller, so stopping the election process.")
      return
    }

    try {
      // 第3步：注册Controller相关信息
      // 主要是创建/controller节点
      val (epoch, epochZkVersion) = zkClient.registerControllerAndIncrementControllerEpoch(config.brokerId)
      controllerContext.epoch = epoch
      controllerContext.epochZkVersion = epochZkVersion
      activeControllerId = config.brokerId

      info(s"${config.brokerId} successfully elected as the controller. Epoch incremented to ${controllerContext.epoch} " +
        s"and epoch zk version is now ${controllerContext.epochZkVersion}")
      // 第4步：执行当选Controller的后续逻辑
      onControllerFailover()
    } catch {
      case e: ControllerMovedException =>
        maybeResign()

        if (activeControllerId != -1)
          debug(s"Broker $activeControllerId was elected as controller instead of broker ${config.brokerId}", e)
        else
          warn("A controller has been elected but just resigned, this will result in another round of election", e)
      case t: Throwable =>
        error(s"Error while electing or becoming controller on broker ${config.brokerId}. " +
          s"Trigger controller movement immediately", t)
        triggerControllerMove()
    }
  }

  /**
   * Partitions the provided map of brokers and epochs into 2 new maps:
   *  - The first map contains only those brokers whose features were found to be compatible with
   *    the existing finalized features.
   *  - The second map contains only those brokers whose features were found to be incompatible with
   *    the existing finalized features.
   *
   * @param brokersAndEpochs   the map to be partitioned
   * @return                   two maps: first contains compatible brokers and second contains
   *                           incompatible brokers as explained above
   */
  private def partitionOnFeatureCompatibility(brokersAndEpochs: Map[Broker, Long]): (Map[Broker, Long], Map[Broker, Long]) = {
    // There can not be any feature incompatibilities when the feature versioning system is disabled
    // or when the finalized feature cache is empty. Otherwise, we check if the non-empty contents
    //  of the cache are compatible with the supported features of each broker.
    brokersAndEpochs.partition {
      case (broker, _) =>
        !config.isFeatureVersioningSupported ||
        !featureCache.getFeatureOption.exists(
          latestFinalizedFeatures =>
            BrokerFeatures.hasIncompatibleFeatures(broker.features, latestFinalizedFeatures.features))
    }
  }

  /**
   * 处理 BrokerChange 事件的方法实际上是 processBrokerChange方法，整个方法共有 9 步。
   * 第 1~3 步：前两步分别是从 ZooKeeper 和 ControllerContext 中获取 Broker 列表；
   * 第 3 步是获取 4 个 Broker 列表：新增 Broker 列表、待移除 Broker 列表、已重启的 Broker 列表和当前运行中的 Broker 列表。
   * 假设前两步中的 Broker 列表分别用 A 和 B 表示，由于 Kafka 以 ZooKeeper 上的数据为权威数据，
   * 因此，A 就是最新的运行中 Broker 列表，“A-B”就表示新增的 Broker，“B-A”就表示待移除的 Broker。
   * 已重启的 Broker 的判断逻辑要复杂一些，它判断的是 A∧B 集合中的那些 Epoch 值变更了的 Broker。
   * 大体上可以把 Epoch 值理解为 Broker 的版本或重启的次数。显然，Epoch 值变更了，就说明 Broker 发生了重启行为。
   *
   * 第 4~9 步：拿到这些集合之后，Controller 会分别为这 4 个 Broker 列表执行相应的操作，也就是这个方法中第 4~9 步要做的事情。
   * 总体上，这些相应的操作分为 3 类。
   * 执行元数据更新操作：调用 ControllerContext 类的各个方法，更新不同的集群元数据信息。比如需要将新增 Broker 加入到集群元数据，将待移除 Broker 从元数据中移除等。
   * 执行 Broker 终止操作：为待移除 Broker 和已重启 Broker 调用 onBrokerFailure 方法。
   * 执行 Broker 启动操作：为已重启 Broker 和新增 Broker 调用 onBrokerStartup 方法。
   *
   */
  private def processBrokerChange(): Unit = {
    if (!isActive) return
    // 第1步：从ZooKeeper中获取集群Broker列表
    val curBrokerAndEpochs = zkClient.getAllBrokerAndEpochsInCluster
    val curBrokerIdAndEpochs = curBrokerAndEpochs map { case (broker, epoch) => (broker.id, epoch) }
    val curBrokerIds = curBrokerIdAndEpochs.keySet
    // 第2步：获取Controller当前保存的Broker列表
    val liveOrShuttingDownBrokerIds = controllerContext.liveOrShuttingDownBrokerIds
    // 第3步：比较两个列表，获取新增Broker列表、待移除Broker列表、已重启Broker列表和当前运行中的Broker列表
    val newBrokerIds = curBrokerIds.diff(liveOrShuttingDownBrokerIds)
    val deadBrokerIds = liveOrShuttingDownBrokerIds.diff(curBrokerIds)
    val bouncedBrokerIds = (curBrokerIds & liveOrShuttingDownBrokerIds)
      .filter(brokerId => curBrokerIdAndEpochs(brokerId) > controllerContext.liveBrokerIdAndEpochs(brokerId))
    val newBrokerAndEpochs = curBrokerAndEpochs.filter { case (broker, _) => newBrokerIds.contains(broker.id) }
    val bouncedBrokerAndEpochs = curBrokerAndEpochs.filter { case (broker, _) => bouncedBrokerIds.contains(broker.id) }
    val newBrokerIdsSorted = newBrokerIds.toSeq.sorted
    val deadBrokerIdsSorted = deadBrokerIds.toSeq.sorted
    val liveBrokerIdsSorted = curBrokerIds.toSeq.sorted
    val bouncedBrokerIdsSorted = bouncedBrokerIds.toSeq.sorted
    info(s"Newly added brokers: ${newBrokerIdsSorted.mkString(",")}, " +
      s"deleted brokers: ${deadBrokerIdsSorted.mkString(",")}, " +
      s"bounced brokers: ${bouncedBrokerIdsSorted.mkString(",")}, " +
      s"all live brokers: ${liveBrokerIdsSorted.mkString(",")}")

    // 第4步：为每个新增Broker创建与之连接的通道管理器和底层的请求发送线程（RequestSendThread）
    newBrokerAndEpochs.keySet.foreach(controllerChannelManager.addBroker)
    // 第5步：为每个已重启的Broker移除它们现有的配套资源（通道管理器、RequestSendThread等），并重新添加它们
    bouncedBrokerIds.foreach(controllerChannelManager.removeBroker)
    bouncedBrokerAndEpochs.keySet.foreach(controllerChannelManager.addBroker)
    // 第6步：为每个待移除Broker移除对应的配套资源
    deadBrokerIds.foreach(controllerChannelManager.removeBroker)

    // 第7步：为新增Broker执行更新Controller元数据和Broker启动逻辑
    if (newBrokerIds.nonEmpty) {
      val (newCompatibleBrokerAndEpochs, newIncompatibleBrokerAndEpochs) =
        partitionOnFeatureCompatibility(newBrokerAndEpochs)
      if (newIncompatibleBrokerAndEpochs.nonEmpty) {
        warn("Ignoring registration of new brokers due to incompatibilities with finalized features: " +
          newIncompatibleBrokerAndEpochs.map { case (broker, _) => broker.id }.toSeq.sorted.mkString(","))
      }
      controllerContext.addLiveBrokers(newCompatibleBrokerAndEpochs)
      onBrokerStartup(newBrokerIdsSorted)
    }
    // 第8步：为已重启Broker执行重添加逻辑，包含更新ControllerContext、执行Broker重启动逻辑
    if (bouncedBrokerIds.nonEmpty) {
      controllerContext.removeLiveBrokers(bouncedBrokerIds)
      onBrokerFailure(bouncedBrokerIdsSorted)
      val (bouncedCompatibleBrokerAndEpochs, bouncedIncompatibleBrokerAndEpochs) =
        partitionOnFeatureCompatibility(bouncedBrokerAndEpochs)
      if (bouncedIncompatibleBrokerAndEpochs.nonEmpty) {
        warn("Ignoring registration of bounced brokers due to incompatibilities with finalized features: " +
          bouncedIncompatibleBrokerAndEpochs.map { case (broker, _) => broker.id }.toSeq.sorted.mkString(","))
      }
      controllerContext.addLiveBrokers(bouncedCompatibleBrokerAndEpochs)
      onBrokerStartup(bouncedBrokerIdsSorted)
    }
    // 第9步：为待移除Broker执行移除ControllerContext和Broker终止逻辑
    if (deadBrokerIds.nonEmpty) {
      controllerContext.removeLiveBrokers(deadBrokerIds)
      onBrokerFailure(deadBrokerIdsSorted)
    }

    if (newBrokerIds.nonEmpty || deadBrokerIds.nonEmpty || bouncedBrokerIds.nonEmpty) {
      info(s"Updated broker epochs cache: ${controllerContext.liveBrokerIdAndEpochs}")
    }
  }

  /**
   * 和管理集群成员类似，Controller 也是通过 ZooKeeper 监听器的方式来应对 Broker 的变化。这个监听器就是 BrokerModificationsHandler。
   * 一旦 Broker 的信息发生变更，该监听器的 handleDataChange 方法就会被调用，向事件队列写入 BrokerModifications 事件。
   * 该方法首先获取 ZooKeeper 上最权威的 Broker 数据，将其与元数据缓存上的数据进行比对。
   * 如果发现两者不一致，就会更新元数据缓存，同时调用 onBrokerUpdate 方法执行更新逻辑。
   * @param brokerId
   */
  private def processBrokerModification(brokerId: Int): Unit = {
    // 第1步：获取目标Broker的详细数据，
    // 包括每套监听器配置的主机名、端口号以及所使用的安全协议等
    if (!isActive) return
    val newMetadataOpt = zkClient.getBroker(brokerId)
    // 第2步：从元数据缓存中获得目标Broker的详细数据
    val oldMetadataOpt = controllerContext.liveOrShuttingDownBroker(brokerId)
    if (newMetadataOpt.nonEmpty && oldMetadataOpt.nonEmpty) {
      val oldMetadata = oldMetadataOpt.get
      val newMetadata = newMetadataOpt.get
      // 第3步：如果两者不相等，说明Broker数据发生了变更，那么，更新元数据缓存，以及执行onBrokerUpdate方法处理Broker更新的逻辑
      if (newMetadata.endPoints != oldMetadata.endPoints || !oldMetadata.features.equals(newMetadata.features)) {
        info(s"Updated broker metadata: $oldMetadata -> $newMetadata")
        controllerContext.updateBrokerMetadata(oldMetadata, newMetadata)
        onBrokerUpdate(brokerId)
      }
    }
  }

  private def processTopicChange(): Unit = {
    if (!isActive) return // 如果Controller已经关闭，直接返回
    // 第1步：从ZooKeeper中获取所有主题
    val topics = zkClient.getAllTopicsInCluster(true) // 从ZooKeeper中获取当前所有主题列表
    // 第2步：与元数据缓存比对，找出新增主题列表与已删除主题列表
    val newTopics = topics -- controllerContext.allTopics // 找出当前元数据中不存在、ZooKeeper中存在的主题，视为新增主题
    val deletedTopics = controllerContext.allTopics.diff(topics) // 找出当前元数据中存在、ZooKeeper中不存在的主题，视为已删除主题
    // 第3步：使用ZooKeeper中的主题列表更新元数据缓存
    controllerContext.setAllTopics(topics) // 更新Controller元数据

    // 第4步：为新增主题注册分区变更监听器
    // 分区变更监听器是监听主题分区变更的
    // 为新增主题和已删除主题执行后续处理操作
    registerPartitionModificationsHandlers(newTopics.toSeq)
    // 第5步：从ZooKeeper中获取新增主题的副本分配情况
    val addedPartitionReplicaAssignment = zkClient.getReplicaAssignmentAndTopicIdForTopics(newTopics)
    // 第6步：清除元数据缓存中属于已删除主题的缓存项
    deletedTopics.foreach(controllerContext.removeTopic)
    processTopicIds(addedPartitionReplicaAssignment)
    // 第7步：为新增主题更新元数据缓存中的副本分配条目
    addedPartitionReplicaAssignment.foreach { case TopicIdReplicaAssignment(_, _, newAssignments) =>
      newAssignments.foreach { case (topicAndPartition, newReplicaAssignment) =>
        controllerContext.updatePartitionFullReplicaAssignment(topicAndPartition, newReplicaAssignment)
      }
    }
    info(s"New topics: [$newTopics], deleted topics: [$deletedTopics], new partition replica assignment " +
      s"[$addedPartitionReplicaAssignment]")
    // 第8步：调整新增主题所有分区以及所属所有副本的运行状态为“上线”状态
    // 涉及到了使用分区管理器和副本管理器来调整分区和副本状态。
    if (addedPartitionReplicaAssignment.nonEmpty) {
      val partitionAssignments = addedPartitionReplicaAssignment
        .map { case TopicIdReplicaAssignment(_, _, partitionsReplicas) => partitionsReplicas.keySet }
        .reduce((s1, s2) => s1.union(s2))
      onNewPartitionCreation(partitionAssignments)
    }
  }

  private def processTopicIds(topicIdAssignments: Set[TopicIdReplicaAssignment]): Unit = {
    // Create topic IDs for topics missing them if we are using topic IDs
    // Otherwise, maintain what we have in the topicZNode
    val updatedTopicIdAssignments = if (config.usesTopicId) {
      val (withTopicIds, withoutTopicIds) = topicIdAssignments.partition(_.topicId.isDefined)
      withTopicIds ++ zkClient.setTopicIds(withoutTopicIds, controllerContext.epochZkVersion)
    } else {
      topicIdAssignments
    }

    // Add topic IDs to controller context
    // If we don't have IBP 2.8, but are running 2.8 code, put any topic IDs from the ZNode in controller context
    // This is to avoid losing topic IDs during operations like partition reassignments while the cluster is in a mixed state
    updatedTopicIdAssignments.foreach { topicIdAssignment =>
      topicIdAssignment.topicId.foreach { topicId =>
        controllerContext.addTopicId(topicIdAssignment.topic, topicId)
    }
    }
  }

  private def processLogDirEventNotification(): Unit = {
    if (!isActive) return
    val sequenceNumbers = zkClient.getAllLogDirEventNotifications
    try {
      val brokerIds = zkClient.getBrokerIdsFromLogDirEvents(sequenceNumbers)
      onBrokerLogDirFailure(brokerIds)
    } finally {
      // delete processed children
      zkClient.deleteLogDirEventNotifications(sequenceNumbers, controllerContext.epochZkVersion)
    }
  }

  private def processPartitionModifications(topic: String): Unit = {
    def restorePartitionReplicaAssignment(
      topic: String,
      newPartitionReplicaAssignment: Map[TopicPartition, ReplicaAssignment]
    ): Unit = {
      info("Restoring the partition replica assignment for topic %s".format(topic))

      val existingPartitions = zkClient.getChildren(TopicPartitionsZNode.path(topic))
      val existingPartitionReplicaAssignment = newPartitionReplicaAssignment
        .filter(p => existingPartitions.contains(p._1.partition.toString))
        .map { case (tp, _) =>
          tp -> controllerContext.partitionFullReplicaAssignment(tp)
      }.toMap

      zkClient.setTopicAssignment(topic,
        controllerContext.topicIds.get(topic),
        existingPartitionReplicaAssignment,
        controllerContext.epochZkVersion)
    }

    if (!isActive) return
    val partitionReplicaAssignment = zkClient.getFullReplicaAssignmentForTopics(immutable.Set(topic))
    val partitionsToBeAdded = partitionReplicaAssignment.filter { case (topicPartition, _) =>
      controllerContext.partitionReplicaAssignment(topicPartition).isEmpty
    }

    if (topicDeletionManager.isTopicQueuedUpForDeletion(topic)) {
      if (partitionsToBeAdded.nonEmpty) {
        warn("Skipping adding partitions %s for topic %s since it is currently being deleted"
          .format(partitionsToBeAdded.map(_._1.partition).mkString(","), topic))

        restorePartitionReplicaAssignment(topic, partitionReplicaAssignment)
      } else {
        // This can happen if existing partition replica assignment are restored to prevent increasing partition count during topic deletion
        info("Ignoring partition change during topic deletion as no new partitions are added")
      }
    } else if (partitionsToBeAdded.nonEmpty) {
      info(s"New partitions to be added $partitionsToBeAdded")
      partitionsToBeAdded.forKeyValue { (topicPartition, assignedReplicas) =>
        controllerContext.updatePartitionFullReplicaAssignment(topicPartition, assignedReplicas)
      }
      onNewPartitionCreation(partitionsToBeAdded.keySet)
    }
  }

  /**
   * 首先，代码从 ZooKeeper 的 /admin/delete_topics 下获取子节点列表，即待删除主题列表。
   * 之后，比对元数据缓存中的主题列表，获知压根不存在的主题列表。如果确实有不存在的主题，删除 /admin/delete_topics 下对应的子节点就行了。
   * 同时，代码会更新待删除主题列表，将这些不存在的主题剔除掉。
   * 接着，代码会检查 Broker 端参数 delete.topic.enable 的值。如果该参数为 false，即不允许删除主题，
   * 代码就会清除 ZooKeeper 下的对应子节点，不会做其他操作。
   * 反之，代码会遍历待删除主题列表，将那些正在执行分区迁移的主题暂时设置成“不可删除”状态。
   * 最后，把剩下可以删除的主题交由 TopicDeletionManager，由它执行真正的删除逻辑。
   */
  private def processTopicDeletion(): Unit = {
    if (!isActive) return
    // 从ZooKeeper中获取待删除主题列表
    var topicsToBeDeleted = zkClient.getTopicDeletions.toSet
    debug(s"Delete topics listener fired for topics ${topicsToBeDeleted.mkString(",")} to be deleted")
    // 找出不存在的主题列表
    val nonExistentTopics = topicsToBeDeleted -- controllerContext.allTopics
    if (nonExistentTopics.nonEmpty) {
      warn(s"Ignoring request to delete non-existing topics ${nonExistentTopics.mkString(",")}")
      zkClient.deleteTopicDeletions(nonExistentTopics.toSeq, controllerContext.epochZkVersion)
    }
    topicsToBeDeleted --= nonExistentTopics
    // 如果delete.topic.enable参数设置成true
    if (config.deleteTopicEnable) {
      if (topicsToBeDeleted.nonEmpty) {
        info(s"Starting topic deletion for topics ${topicsToBeDeleted.mkString(",")}")
        // mark topic ineligible for deletion if other state changes are in progress
        topicsToBeDeleted.foreach { topic =>
          val partitionReassignmentInProgress =
            controllerContext.partitionsBeingReassigned.map(_.topic).contains(topic)
          if (partitionReassignmentInProgress)
            topicDeletionManager.markTopicIneligibleForDeletion(Set(topic),
              reason = "topic reassignment in progress")
        }
        // add topic to deletion list
        // 将待删除主题插入到删除等待集合交由TopicDeletionManager处理
        topicDeletionManager.enqueueTopicsForDeletion(topicsToBeDeleted)
      }
    } else {// 不允许删除主题
      // If delete topic is disabled remove entries under zookeeper path : /admin/delete_topics
      info(s"Removing $topicsToBeDeleted since delete topic is disabled")
      // 清除ZooKeeper下/admin/delete_topics下的子节点
      zkClient.deleteTopicDeletions(topicsToBeDeleted.toSeq, controllerContext.epochZkVersion)
    }
  }

  private def processZkPartitionReassignment(): Set[TopicPartition] = {
    // We need to register the watcher if the path doesn't exist in order to detect future
    // reassignments and we get the `path exists` check for free
    if (isActive && zkClient.registerZNodeChangeHandlerAndCheckExistence(partitionReassignmentHandler)) {
      val reassignmentResults = mutable.Map.empty[TopicPartition, ApiError]
      val partitionsToReassign = mutable.Map.empty[TopicPartition, ReplicaAssignment]

      zkClient.getPartitionReassignment.forKeyValue { (tp, targetReplicas) =>
        maybeBuildReassignment(tp, Some(targetReplicas)) match {
          case Some(context) => partitionsToReassign.put(tp, context)
          case None => reassignmentResults.put(tp, new ApiError(Errors.NO_REASSIGNMENT_IN_PROGRESS))
        }
      }

      reassignmentResults ++= maybeTriggerPartitionReassignment(partitionsToReassign)
      val (partitionsReassigned, partitionsFailed) = reassignmentResults.partition(_._2.error == Errors.NONE)
      if (partitionsFailed.nonEmpty) {
        warn(s"Failed reassignment through zk with the following errors: $partitionsFailed")
        maybeRemoveFromZkReassignment((tp, _) => partitionsFailed.contains(tp))
      }
      partitionsReassigned.keySet
    } else {
      Set.empty
    }
  }

  /**
   * Process a partition reassignment from the AlterPartitionReassignment API. If there is an
   * existing reassignment through zookeeper for any of the requested partitions, they will be
   * cancelled prior to beginning the new reassignment. Any zk-based reassignment for partitions
   * which are NOT included in this call will not be affected.
   *
   * @param reassignments Map of reassignments passed through the AlterReassignments API. A null value
   *                      means that we should cancel an in-progress reassignment.
   * @param callback Callback to send AlterReassignments response
   */
  private def processApiPartitionReassignment(reassignments: Map[TopicPartition, Option[Seq[Int]]],
                                              callback: AlterReassignmentsCallback): Unit = {
    if (!isActive) {
      callback(Right(new ApiError(Errors.NOT_CONTROLLER)))
    } else {
      val reassignmentResults = mutable.Map.empty[TopicPartition, ApiError]
      val partitionsToReassign = mutable.Map.empty[TopicPartition, ReplicaAssignment]

      reassignments.forKeyValue { (tp, targetReplicas) =>
        val maybeApiError = targetReplicas.flatMap(validateReplicas(tp, _))
        maybeApiError match {
          case None =>
            maybeBuildReassignment(tp, targetReplicas) match {
              case Some(context) => partitionsToReassign.put(tp, context)
              case None => reassignmentResults.put(tp, new ApiError(Errors.NO_REASSIGNMENT_IN_PROGRESS))
            }
          case Some(err) =>
            reassignmentResults.put(tp, err)
        }
      }

      // The latest reassignment (whether by API or through zk) always takes precedence,
      // so remove from active zk reassignment (if one exists)
      maybeRemoveFromZkReassignment((tp, _) => partitionsToReassign.contains(tp))

      reassignmentResults ++= maybeTriggerPartitionReassignment(partitionsToReassign)
      callback(Left(reassignmentResults))
    }
  }

  private def validateReplicas(topicPartition: TopicPartition, replicas: Seq[Int]): Option[ApiError] = {
    val replicaSet = replicas.toSet
    if (replicas.isEmpty)
      Some(new ApiError(Errors.INVALID_REPLICA_ASSIGNMENT,
          s"Empty replica list specified in partition reassignment."))
    else if (replicas.size != replicaSet.size) {
      Some(new ApiError(Errors.INVALID_REPLICA_ASSIGNMENT,
          s"Duplicate replica ids in partition reassignment replica list: $replicas"))
    } else if (replicas.exists(_ < 0))
      Some(new ApiError(Errors.INVALID_REPLICA_ASSIGNMENT,
          s"Invalid broker id in replica list: $replicas"))
    else {
      // Ensure that any new replicas are among the live brokers
      val currentAssignment = controllerContext.partitionFullReplicaAssignment(topicPartition)
      val newAssignment = currentAssignment.reassignTo(replicas)
      val areNewReplicasAlive = newAssignment.addingReplicas.toSet.subsetOf(controllerContext.liveBrokerIds)
      if (!areNewReplicasAlive)
        Some(new ApiError(Errors.INVALID_REPLICA_ASSIGNMENT,
          s"Replica assignment has brokers that are not alive. Replica list: " +
            s"${newAssignment.addingReplicas}, live broker list: ${controllerContext.liveBrokerIds}"))
      else None
    }
  }

  private def maybeBuildReassignment(topicPartition: TopicPartition,
                                     targetReplicasOpt: Option[Seq[Int]]): Option[ReplicaAssignment] = {
    val replicaAssignment = controllerContext.partitionFullReplicaAssignment(topicPartition)
    if (replicaAssignment.isBeingReassigned) {
      val targetReplicas = targetReplicasOpt.getOrElse(replicaAssignment.originReplicas)
      Some(replicaAssignment.reassignTo(targetReplicas))
    } else {
      targetReplicasOpt.map { targetReplicas =>
        replicaAssignment.reassignTo(targetReplicas)
      }
    }
  }

  private def processPartitionReassignmentIsrChange(topicPartition: TopicPartition): Unit = {
    if (!isActive) return

    if (controllerContext.partitionsBeingReassigned.contains(topicPartition)) {
      maybeCompleteReassignment(topicPartition)
    }
  }

  private def maybeCompleteReassignment(topicPartition: TopicPartition): Unit = {
    val reassignment = controllerContext.partitionFullReplicaAssignment(topicPartition)
    if (isReassignmentComplete(topicPartition, reassignment)) {
      // resume the partition reassignment process
      info(s"Target replicas ${reassignment.targetReplicas} have all caught up with the leader for " +
        s"reassigning partition $topicPartition")
      onPartitionReassignment(topicPartition, reassignment)
    }
  }

  private def processListPartitionReassignments(partitionsOpt: Option[Set[TopicPartition]], callback: ListReassignmentsCallback): Unit = {
    if (!isActive) {
      callback(Right(new ApiError(Errors.NOT_CONTROLLER)))
    } else {
      val results: mutable.Map[TopicPartition, ReplicaAssignment] = mutable.Map.empty
      val partitionsToList = partitionsOpt match {
        case Some(partitions) => partitions
        case None => controllerContext.partitionsBeingReassigned
      }

      partitionsToList.foreach { tp =>
        val assignment = controllerContext.partitionFullReplicaAssignment(tp)
        if (assignment.isBeingReassigned) {
          results += tp -> assignment
        }
      }

      callback(Left(results))
    }
  }

  /**
   * Returns the new finalized version for the feature, if there are no feature
   * incompatibilities seen with all known brokers for the provided feature update.
   * Otherwise returns an ApiError object containing Errors.INVALID_REQUEST.
   *
   * @param update   the feature update to be processed (this can not be meant to delete the feature)
   *
   * @return         the new finalized version or error, as described above.
   */
  private def newFinalizedVersionOrIncompatibilityError(update: UpdateFeaturesRequest.FeatureUpdateItem):
      Either[Short, ApiError] = {
    if (update.isDeleteRequest) {
      throw new IllegalArgumentException(s"Provided feature update can not be meant to delete the feature: $update")
    }

    val supportedVersionRange = brokerFeatures.supportedFeatures.get(update.feature)
    if (supportedVersionRange == null) {
      Right(new ApiError(Errors.INVALID_REQUEST,
                         "Could not apply finalized feature update because the provided feature" +
                         " is not supported."))
    } else {
      val newVersion = update.versionLevel()
      if (supportedVersionRange.isIncompatibleWith(newVersion)) {
        Right(new ApiError(Errors.INVALID_REQUEST,
          "Could not apply finalized feature update because the provided" +
          s" versionLevel:${update.versionLevel} is lower than the" +
          s" supported minVersion:${supportedVersionRange.min}."))
      } else {
        val newFinalizedFeature = Utils.mkMap(Utils.mkEntry(update.feature, newVersion)).asScala.toMap
        val numIncompatibleBrokers = controllerContext.liveOrShuttingDownBrokers.count(broker => {
          BrokerFeatures.hasIncompatibleFeatures(broker.features, newFinalizedFeature)
        })
        if (numIncompatibleBrokers == 0) {
          Left(newVersion)
        } else {
          Right(new ApiError(Errors.INVALID_REQUEST,
                             "Could not apply finalized feature update because" +
                             " brokers were found to have incompatible versions for the feature."))
        }
      }
    }
  }

  /**
   * Validates a feature update on an existing finalized version.
   * If the validation succeeds, then, the return value contains:
   * 1. the new finalized version for the feature, if the feature update was not meant to delete the feature.
   * 2. Option.empty, if the feature update was meant to delete the feature.
   *
   * If the validation fails, then returned value contains a suitable ApiError.
   *
   * @param update             the feature update to be processed.
   * @param existingVersion    the existing finalized version which can be empty when no
   *                           finalized version exists for the associated feature
   *
   * @return                   the new finalized version to be updated into ZK or error
   *                           as described above.
   */
  private def validateFeatureUpdate(update: UpdateFeaturesRequest.FeatureUpdateItem,
                                    existingVersion: Option[Short]): Either[Option[Short], ApiError] = {
    def newVersionRangeOrError(update: UpdateFeaturesRequest.FeatureUpdateItem): Either[Option[Short], ApiError] = {
      newFinalizedVersionOrIncompatibilityError(update)
        .fold(versionRange => Left(Some(versionRange)), error => Right(error))
    }

    if (update.feature.isEmpty) {
      // Check that the feature name is not empty.
      Right(new ApiError(Errors.INVALID_REQUEST, "Feature name can not be empty."))
    } else if (update.upgradeType.equals(UpgradeType.UNKNOWN)) {
      Right(new ApiError(Errors.INVALID_REQUEST, "Received unknown upgrade type."))
    } else {

      // We handle deletion requests separately from non-deletion requests.
      if (update.isDeleteRequest) {
        if (existingVersion.isEmpty) {
          // Disallow deletion of a non-existing finalized feature.
          Right(new ApiError(Errors.INVALID_REQUEST,
                             "Can not delete non-existing finalized feature."))
        } else {
          Left(Option.empty)
        }
      } else if (update.versionLevel() < 1) {
        // Disallow deletion of a finalized feature without SAFE downgrade type.
        Right(new ApiError(Errors.INVALID_REQUEST,
                           s"Can not provide versionLevel: ${update.versionLevel} less" +
                           s" than 1 without setting the SAFE downgradeType in the request."))
      } else {
        existingVersion.map(existing =>
          if (update.versionLevel == existing) {
            // Disallow a case where target versionLevel matches existing versionLevel.
            Right(new ApiError(Errors.INVALID_REQUEST,
                               s"Can not ${if (update.upgradeType.equals(UpgradeType.SAFE_DOWNGRADE)) "downgrade" else "upgrade"}" +
                               s" a finalized feature from existing versionLevel:$existing" +
                               " to the same value."))
          } else if (update.versionLevel < existing && !update.upgradeType.equals(UpgradeType.SAFE_DOWNGRADE)) {
            // Disallow downgrade of a finalized feature without the downgradeType set.
            Right(new ApiError(Errors.INVALID_REQUEST,
                               s"Can not downgrade finalized feature from existing" +
                               s" versionLevel:$existing to provided" +
                               s" versionLevel:${update.versionLevel} without setting the" +
                               " downgradeType to SAFE in the request."))
          } else if (!update.upgradeType.equals(UpgradeType.UPGRADE) && update.versionLevel > existing) {
            // Disallow a request that sets downgradeType without specifying a
            // versionLevel that's lower than the existing versionLevel.
            Right(new ApiError(Errors.INVALID_REQUEST,
                               s"When the downgradeType is set to SAFE in the request, the provided" +
                               s" versionLevel:${update.versionLevel} can not be greater than" +
                               s" existing versionLevel:$existing."))
          } else {
            newVersionRangeOrError(update)
          }
        ).getOrElse(newVersionRangeOrError(update))
      }
    }
  }

  private def processFeatureUpdates(request: UpdateFeaturesRequest,
                                    callback: UpdateFeaturesCallback): Unit = {
    if (isActive) {
      processFeatureUpdatesWithActiveController(request, callback)
    } else {
      callback(Left(new ApiError(Errors.NOT_CONTROLLER)))
    }
  }

  private def processFeatureUpdatesWithActiveController(request: UpdateFeaturesRequest,
                                                        callback: UpdateFeaturesCallback): Unit = {
    val updates = request.featureUpdates
    val existingFeatures = featureCache.getFeatureOption
      .map(featuresAndEpoch => featuresAndEpoch.features)
      .getOrElse(Map[String, Short]())
    // A map with key being feature name and value being finalized version.
    // This contains the target features to be eventually written to FeatureZNode.
    val targetFeatures = scala.collection.mutable.Map[String, Short]() ++ existingFeatures
    // A map with key being feature name and value being error encountered when the FeatureUpdate
    // was applied.
    val errors = scala.collection.mutable.Map[String, ApiError]()

    // Below we process each FeatureUpdate using the following logic:
    //  - If a FeatureUpdate is found to be valid, then:
    //    - The corresponding entry in errors map would be updated to contain Errors.NONE.
    //    - If the FeatureUpdate is an add or update request, then the targetFeatures map is updated
    //      to contain the new finalized version for the feature.
    //    - Otherwise if the FeatureUpdate is a delete request, then the feature is removed from the
    //      targetFeatures map.
    //  - Otherwise if a FeatureUpdate is found to be invalid, then:
    //    - The corresponding entry in errors map would be updated with the appropriate ApiError.
    //    - The entry in targetFeatures map is left untouched.
    updates.asScala.iterator.foreach { update =>
      validateFeatureUpdate(update, existingFeatures.get(update.feature())) match {
        case Left(newVersionRangeOrNone) =>
          newVersionRangeOrNone match {
            case Some(newVersionRange) => targetFeatures += (update.feature() -> newVersionRange)
            case None => targetFeatures -= update.feature()
          }
          errors += (update.feature() -> new ApiError(Errors.NONE))
        case Right(featureUpdateFailureReason) =>
          errors += (update.feature() -> featureUpdateFailureReason)
      }
    }

    // If the existing and target features are the same, then, we skip the update to the
    // FeatureZNode as no changes to the node are required. Otherwise, we replace the contents
    // of the FeatureZNode with the new features. This may result in partial or full modification
    // of the existing finalized features in ZK.
    try {
      if (!existingFeatures.equals(targetFeatures)) {
        val newNode = FeatureZNode(config.interBrokerProtocolVersion, FeatureZNodeStatus.Enabled, targetFeatures)
        val newVersion = updateFeatureZNode(newNode)
        featureCache.waitUntilFeatureEpochOrThrow(newVersion, request.data().timeoutMs())
      }
    } catch {
      // For all features that correspond to valid FeatureUpdate (i.e. error is Errors.NONE),
      // we set the error as Errors.FEATURE_UPDATE_FAILED since the FeatureZNode update has failed
      // for these. For the rest, the existing error is left untouched.
      case e: Exception =>
        warn(s"Processing of feature updates: $request failed due to error: $e")
        errors.foreach { case (feature, apiError) =>
          if (apiError.error() == Errors.NONE) {
            errors(feature) = new ApiError(Errors.FEATURE_UPDATE_FAILED)
          }
        }
    } finally {
      callback(Right(errors))
    }
  }

  private def processIsrChangeNotification(): Unit = {
    def processUpdateNotifications(partitions: Seq[TopicPartition]): Unit = {
      val liveBrokers: Seq[Int] = controllerContext.liveOrShuttingDownBrokerIds.toSeq
      debug(s"Sending MetadataRequest to Brokers: $liveBrokers for TopicPartitions: $partitions")
      sendUpdateMetadataRequest(liveBrokers, partitions.toSet)
    }

    if (!isActive) return
    val sequenceNumbers = zkClient.getAllIsrChangeNotifications
    try {
      val partitions = zkClient.getPartitionsFromIsrChangeNotifications(sequenceNumbers)
      if (partitions.nonEmpty) {
        updateLeaderAndIsrCache(partitions)
        processUpdateNotifications(partitions)

        // During a partial upgrade, the controller may be on an IBP which assumes
        // ISR changes through the `AlterPartition` API while some brokers are on an older
        // IBP which assumes notification through Zookeeper. In this case, since the
        // controller will not have registered watches for reassigning partitions, we
        // can still rely on the batch ISR change notification path in order to
        // complete the reassignment.
        partitions.filter(controllerContext.partitionsBeingReassigned.contains).foreach { topicPartition =>
          maybeCompleteReassignment(topicPartition)
        }
      }
    } finally {
      // delete the notifications
      zkClient.deleteIsrChangeNotifications(sequenceNumbers, controllerContext.epochZkVersion)
    }
  }

  def electLeaders(
    partitions: Set[TopicPartition],
    electionType: ElectionType,
    callback: ElectLeadersCallback
  ): Unit = {
    eventManager.put(ReplicaLeaderElection(Some(partitions), electionType, AdminClientTriggered, callback))
  }

  def listPartitionReassignments(partitions: Option[Set[TopicPartition]],
                                 callback: ListReassignmentsCallback): Unit = {
    eventManager.put(ListPartitionReassignments(partitions, callback))
  }

  def updateFeatures(request: UpdateFeaturesRequest,
                     callback: UpdateFeaturesCallback): Unit = {
    eventManager.put(UpdateFeatures(request, callback))
  }

  def alterPartitionReassignments(partitions: Map[TopicPartition, Option[Seq[Int]]],
                                  callback: AlterReassignmentsCallback): Unit = {
    eventManager.put(ApiPartitionReassignment(partitions, callback))
  }

  private def processReplicaLeaderElection(
    partitionsFromAdminClientOpt: Option[Set[TopicPartition]],
    electionType: ElectionType,
    electionTrigger: ElectionTrigger,
    callback: ElectLeadersCallback
  ): Unit = {
    if (!isActive) {
      callback(partitionsFromAdminClientOpt.fold(Map.empty[TopicPartition, Either[ApiError, Int]]) { partitions =>
        partitions.iterator.map(partition => partition -> Left(new ApiError(Errors.NOT_CONTROLLER, null))).toMap
      })
    } else {
      // We need to register the watcher if the path doesn't exist in order to detect future preferred replica
      // leader elections and we get the `path exists` check for free
      if (electionTrigger == AdminClientTriggered || zkClient.registerZNodeChangeHandlerAndCheckExistence(preferredReplicaElectionHandler)) {
        val partitions = partitionsFromAdminClientOpt match {
          case Some(partitions) => partitions
          case None => zkClient.getPreferredReplicaElection
        }

        val allPartitions = controllerContext.allPartitions
        val (knownPartitions, unknownPartitions) = partitions.partition(tp => allPartitions.contains(tp))
        unknownPartitions.foreach { p =>
          info(s"Skipping replica leader election ($electionType) for partition $p by $electionTrigger since it doesn't exist.")
        }

        val (partitionsBeingDeleted, livePartitions) = knownPartitions.partition(partition =>
            topicDeletionManager.isTopicQueuedUpForDeletion(partition.topic))
        if (partitionsBeingDeleted.nonEmpty) {
          warn(s"Skipping replica leader election ($electionType) for partitions $partitionsBeingDeleted " +
            s"by $electionTrigger since the respective topics are being deleted")
        }

        // partition those that have a valid leader
        val (electablePartitions, alreadyValidLeader) = livePartitions.partition { partition =>
          electionType match {
            case ElectionType.PREFERRED =>
              val assignedReplicas = controllerContext.partitionReplicaAssignment(partition)
              val preferredReplica = assignedReplicas.head
              val currentLeader = controllerContext.partitionLeadershipInfo(partition).get.leaderAndIsr.leader
              currentLeader != preferredReplica

            case ElectionType.UNCLEAN =>
              val currentLeader = controllerContext.partitionLeadershipInfo(partition).get.leaderAndIsr.leader
              currentLeader == LeaderAndIsr.NoLeader || !controllerContext.liveBrokerIds.contains(currentLeader)
          }
        }

        val results = onReplicaElection(electablePartitions, electionType, electionTrigger).map {
          case (k, Left(ex)) =>
            if (ex.isInstanceOf[StateChangeFailedException]) {
              val error = if (electionType == ElectionType.PREFERRED) {
                Errors.PREFERRED_LEADER_NOT_AVAILABLE
              } else {
                Errors.ELIGIBLE_LEADERS_NOT_AVAILABLE
              }
              k -> Left(new ApiError(error, ex.getMessage))
            } else {
              k -> Left(ApiError.fromThrowable(ex))
            }
          case (k, Right(leaderAndIsr)) => k -> Right(leaderAndIsr.leader)
        } ++
        alreadyValidLeader.map(_ -> Left(new ApiError(Errors.ELECTION_NOT_NEEDED))) ++
        partitionsBeingDeleted.map(
          _ -> Left(new ApiError(Errors.INVALID_TOPIC_EXCEPTION, "The topic is being deleted"))
        ) ++
        unknownPartitions.map(
          _ -> Left(new ApiError(Errors.UNKNOWN_TOPIC_OR_PARTITION, "The partition does not exist."))
        )

        debug(s"Waiting for any successful result for election type ($electionType) by $electionTrigger for partitions: $results")
        callback(results)
      }
    }
  }

  def alterPartitions(
    alterPartitionRequest: AlterPartitionRequestData,
    alterPartitionRequestVersion: Short,
    callback: AlterPartitionResponseData => Unit
  ): Unit = {
    eventManager.put(AlterPartitionReceived(
      alterPartitionRequest,
      alterPartitionRequestVersion,
      callback
    ))
  }

  private def processAlterPartition(
    alterPartitionRequest: AlterPartitionRequestData,
    alterPartitionRequestVersion: Short,
    callback: AlterPartitionResponseData => Unit
  ): Unit = {
    val partitionResponses = try {
      tryProcessAlterPartition(
        alterPartitionRequest,
        alterPartitionRequestVersion,
        callback
      )
    } catch {
      case e: Throwable =>
        error(s"Error when processing AlterPartition: $alterPartitionRequest", e)
        callback(new AlterPartitionResponseData().setErrorCode(Errors.UNKNOWN_SERVER_ERROR.code))
        mutable.Map.empty
    }

    // After we have returned the result of the `AlterPartition` request, we should check whether
    // there are any reassignments which can be completed by a successful ISR expansion.
    partitionResponses.forKeyValue { (topicPartition, partitionResponse) =>
      if (controllerContext.partitionsBeingReassigned.contains(topicPartition)) {
        val isSuccessfulUpdate = partitionResponse.isRight
        if (isSuccessfulUpdate) {
          maybeCompleteReassignment(topicPartition)
        }
      }
    }
  }

  private def tryProcessAlterPartition(
    alterPartitionRequest: AlterPartitionRequestData,
    alterPartitionRequestVersion: Short,
    callback: AlterPartitionResponseData => Unit
  ): mutable.Map[TopicPartition, Either[Errors, LeaderAndIsr]] = {
    val useTopicsIds = alterPartitionRequestVersion > 1

    // Handle a few short-circuits
    if (!isActive) {
      callback(new AlterPartitionResponseData().setErrorCode(Errors.NOT_CONTROLLER.code))
      return mutable.Map.empty
    }

    val brokerId = alterPartitionRequest.brokerId
    val brokerEpoch = alterPartitionRequest.brokerEpoch
    val brokerEpochOpt = controllerContext.liveBrokerIdAndEpochs.get(brokerId)
    if (brokerEpochOpt.isEmpty) {
      info(s"Ignoring AlterPartition due to unknown broker $brokerId")
      callback(new AlterPartitionResponseData().setErrorCode(Errors.STALE_BROKER_EPOCH.code))
      return mutable.Map.empty
    }

    if (!brokerEpochOpt.contains(brokerEpoch)) {
      info(s"Ignoring AlterPartition due to stale broker epoch $brokerEpoch and local broker epoch $brokerEpochOpt for broker $brokerId")
      callback(new AlterPartitionResponseData().setErrorCode(Errors.STALE_BROKER_EPOCH.code))
      return mutable.Map.empty
    }

    val partitionsToAlter = new mutable.HashMap[TopicPartition, LeaderAndIsr]()
    val alterPartitionResponse = new AlterPartitionResponseData()

    alterPartitionRequest.topics.forEach { topicReq =>
      val topicNameOpt = if (useTopicsIds) {
        controllerContext.topicName(topicReq.topicId)
      } else {
        Some(topicReq.topicName)
      }

      topicNameOpt match {
        case None =>
          val topicResponse = new AlterPartitionResponseData.TopicData()
            .setTopicId(topicReq.topicId)
          alterPartitionResponse.topics.add(topicResponse)
          topicReq.partitions.forEach { partitionReq =>
            topicResponse.partitions.add(new AlterPartitionResponseData.PartitionData()
              .setPartitionIndex(partitionReq.partitionIndex)
              .setErrorCode(Errors.UNKNOWN_TOPIC_ID.code))
          }

        case Some(topicName) =>
          topicReq.partitions.forEach { partitionReq =>
            val isr = if (alterPartitionRequestVersion >= 3) {
              partitionReq.newIsrWithEpochs.asScala.toList.map(brokerState => brokerState.brokerId())
            } else {
              partitionReq.newIsr.asScala.toList.map(_.toInt)
            }
            partitionsToAlter.put(
              new TopicPartition(topicName, partitionReq.partitionIndex),
              LeaderAndIsr(
                alterPartitionRequest.brokerId,
                partitionReq.leaderEpoch,
                isr,
                LeaderRecoveryState.of(partitionReq.leaderRecoveryState),
                partitionReq.partitionEpoch
              )
            )
          }
      }
    }

    val partitionResponses = mutable.HashMap[TopicPartition, Either[Errors, LeaderAndIsr]]()
    // Determine which partitions we will accept the new ISR for
    val adjustedIsrs = partitionsToAlter.flatMap { case (tp, newLeaderAndIsr) =>
      controllerContext.partitionLeadershipInfo(tp) match {
        case Some(leaderIsrAndControllerEpoch) =>
          val currentLeaderAndIsr = leaderIsrAndControllerEpoch.leaderAndIsr
          if (newLeaderAndIsr.partitionEpoch > currentLeaderAndIsr.partitionEpoch
              || newLeaderAndIsr.leaderEpoch > currentLeaderAndIsr.leaderEpoch) {
            // If the partition leader has a higher partition/leader epoch, then it is likely
            // that this node is no longer the active controller. We return NOT_CONTROLLER in
            // this case to give the leader an opportunity to find the new controller.
            partitionResponses(tp) = Left(Errors.NOT_CONTROLLER)
            None
          } else if (newLeaderAndIsr.leaderEpoch < currentLeaderAndIsr.leaderEpoch) {
            partitionResponses(tp) = Left(Errors.FENCED_LEADER_EPOCH)
            None
          } else if (newLeaderAndIsr.equalsAllowStalePartitionEpoch(currentLeaderAndIsr)) {
            // If a partition is already in the desired state, just return it
            // this check must be done before fencing based on partition epoch to maintain idempotency
            partitionResponses(tp) = Right(currentLeaderAndIsr)
            None
          } else if (newLeaderAndIsr.partitionEpoch < currentLeaderAndIsr.partitionEpoch) {
            partitionResponses(tp) = Left(Errors.INVALID_UPDATE_VERSION)
            None
          }  else if (newLeaderAndIsr.leaderRecoveryState == LeaderRecoveryState.RECOVERING && newLeaderAndIsr.isr.length > 1) {
            partitionResponses(tp) = Left(Errors.INVALID_REQUEST)
            info(
              s"Rejecting AlterPartition from node $brokerId for $tp because leader is recovering and ISR is greater than 1: " +
              s"$newLeaderAndIsr"
            )
            None
          } else if (currentLeaderAndIsr.leaderRecoveryState == LeaderRecoveryState.RECOVERED &&
            newLeaderAndIsr.leaderRecoveryState == LeaderRecoveryState.RECOVERING) {

            partitionResponses(tp) = Left(Errors.INVALID_REQUEST)
            info(
              s"Rejecting AlterPartition from node $brokerId for $tp because the leader recovery state cannot change from " +
              s"RECOVERED to RECOVERING: $newLeaderAndIsr"
            )
            None
          } else {
            // Pull out replicas being added to ISR and verify they are all online.
            // If a replica is not online, reject the update as specified in KIP-841.
            val ineligibleReplicas = newLeaderAndIsr.isr.toSet -- controllerContext.liveBrokerIds
            if (ineligibleReplicas.nonEmpty) {
              info(s"Rejecting AlterPartition request from node $brokerId for $tp because " +
                s"it specified ineligible replicas $ineligibleReplicas in the new ISR ${newLeaderAndIsr.isr}."
              )

              if (alterPartitionRequestVersion > 1) {
                partitionResponses(tp) = Left(Errors.INELIGIBLE_REPLICA)
              } else {
                partitionResponses(tp) = Left(Errors.OPERATION_NOT_ATTEMPTED)
              }
              None
            } else {
              Some(tp -> newLeaderAndIsr)
            }
          }

        case None =>
          partitionResponses(tp) = Left(Errors.UNKNOWN_TOPIC_OR_PARTITION)
          None
      }
    }

    // Do the updates in ZK
    debug(s"Updating ISRs for partitions: ${adjustedIsrs.keySet}.")
    val UpdateLeaderAndIsrResult(finishedUpdates, badVersionUpdates) = zkClient.updateLeaderAndIsr(
      adjustedIsrs, controllerContext.epoch, controllerContext.epochZkVersion)

    val successfulUpdates = finishedUpdates.flatMap { case (partition, isrOrError) =>
      isrOrError match {
        case Right(updatedIsr) =>
          debug(s"ISR for partition $partition updated to $updatedIsr.")
          partitionResponses(partition) = Right(updatedIsr)
          Some(partition -> updatedIsr)
        case Left(e) =>
          error(s"Failed to update ISR for partition $partition", e)
          partitionResponses(partition) = Left(Errors.forException(e))
          None
      }
    }

    badVersionUpdates.foreach { partition =>
      info(s"Failed to update ISR to ${adjustedIsrs(partition)} for partition $partition, bad ZK version.")
      partitionResponses(partition) = Left(Errors.INVALID_UPDATE_VERSION)
    }

    // Update our cache and send out metadata updates
    updateLeaderAndIsrCache(successfulUpdates.keys.toSeq)
    sendUpdateMetadataRequest(
      controllerContext.liveOrShuttingDownBrokerIds.toSeq,
      partitionsToAlter.keySet
    )

    partitionResponses.groupBy(_._1.topic).forKeyValue { (topicName, partitionResponses) =>
      // Add each topic part to the response
      val topicResponse = if (useTopicsIds) {
        new AlterPartitionResponseData.TopicData()
          .setTopicId(controllerContext.topicIds.getOrElse(topicName, Uuid.ZERO_UUID))
      } else {
        new AlterPartitionResponseData.TopicData()
          .setTopicName(topicName)
      }
      alterPartitionResponse.topics.add(topicResponse)

      partitionResponses.forKeyValue { (tp, errorOrIsr) =>
        // Add each partition part to the response (new ISR or error)
        errorOrIsr match {
          case Left(error) =>
            topicResponse.partitions.add(
              new AlterPartitionResponseData.PartitionData()
                .setPartitionIndex(tp.partition)
                .setErrorCode(error.code))
          case Right(leaderAndIsr) =>
            /* Setting the LeaderRecoveryState field is always safe because it will always be the same
             * as the value set in the request. For version 0, that is always the default RECOVERED
             * which is ignored when serializing to version 0. For any other version, the
             * LeaderRecoveryState field is supported.
             */
            topicResponse.partitions.add(
              new AlterPartitionResponseData.PartitionData()
                .setPartitionIndex(tp.partition)
                .setLeaderId(leaderAndIsr.leader)
                .setLeaderEpoch(leaderAndIsr.leaderEpoch)
                .setIsr(leaderAndIsr.isr.map(Integer.valueOf).asJava)
                .setLeaderRecoveryState(leaderAndIsr.leaderRecoveryState.value)
                .setPartitionEpoch(leaderAndIsr.partitionEpoch)
            )
        }
      }
    }

    callback(alterPartitionResponse)

    partitionResponses
  }

  def allocateProducerIds(allocateProducerIdsRequest: AllocateProducerIdsRequestData,
                          callback: AllocateProducerIdsResponseData => Unit): Unit = {

    def eventManagerCallback(results: Either[Errors, ProducerIdsBlock]): Unit = {
      results match {
        case Left(error) => callback.apply(new AllocateProducerIdsResponseData().setErrorCode(error.code))
        case Right(pidBlock) => callback.apply(
          new AllocateProducerIdsResponseData()
            .setProducerIdStart(pidBlock.firstProducerId())
            .setProducerIdLen(pidBlock.size()))
      }
    }
    eventManager.put(AllocateProducerIds(allocateProducerIdsRequest.brokerId,
      allocateProducerIdsRequest.brokerEpoch, eventManagerCallback))
  }

  def processAllocateProducerIds(brokerId: Int, brokerEpoch: Long, callback: Either[Errors, ProducerIdsBlock] => Unit): Unit = {
    // Handle a few short-circuits
    if (!isActive) {
      callback.apply(Left(Errors.NOT_CONTROLLER))
      return
    }

    val brokerEpochOpt = controllerContext.liveBrokerIdAndEpochs.get(brokerId)
    if (brokerEpochOpt.isEmpty) {
      warn(s"Ignoring AllocateProducerIds due to unknown broker $brokerId")
      callback.apply(Left(Errors.BROKER_ID_NOT_REGISTERED))
      return
    }

    if (!brokerEpochOpt.contains(brokerEpoch)) {
      warn(s"Ignoring AllocateProducerIds due to stale broker epoch $brokerEpoch for broker $brokerId")
      callback.apply(Left(Errors.STALE_BROKER_EPOCH))
      return
    }

    val maybeNewProducerIdsBlock = try {
      Try(ZkProducerIdManager.getNewProducerIdBlock(brokerId, zkClient, this))
    } catch {
      case ke: KafkaException => Failure(ke)
    }

    maybeNewProducerIdsBlock match {
      case Failure(exception) => callback.apply(Left(Errors.forException(exception)))
      case Success(newProducerIdBlock) => callback.apply(Right(newProducerIdBlock))
    }
  }

  // 如果是ControllerChange事件，仅执行卸任逻辑即可
  private def processControllerChange(): Unit = {
    maybeResign()
  }

  // 如果是Reelect事件，还需要执行elect方法参与新一轮的选举
  private def processReelect(): Unit = {
    maybeResign()
    elect()
  }

  private def processRegisterBrokerAndReelect(): Unit = {
    _brokerEpoch = zkClient.registerBroker(brokerInfo)
    processReelect()
  }

  private def processExpire(): Unit = {
    activeControllerId = -1
    onControllerResignation()
  }

  /**
   * 这个 process 方法接收一个 ControllerEvent 实例，接着会判断它是哪类 Controller 事件，并调用相应的处理方法。
   * 比如，如果是 AutoPreferredReplicaLeaderElection 事件，则调用 processAutoPreferredReplicaLeaderElection 方法；
   * 如果是其他类型的事件，则调用 process*** 方法。
   * @param event
   */
  override def process(event: ControllerEvent): Unit = {
    try {
      // 依次匹配ControllerEvent事件
      event match {
        case event: MockEvent =>
          // Used only in test cases
          event.process()
        case ShutdownEventThread =>
          error("Received a ShutdownEventThread event. This type of event is supposed to be handle by ControllerEventThread")
        case AutoPreferredReplicaLeaderElection =>
          processAutoPreferredReplicaLeaderElection()
        case ReplicaLeaderElection(partitions, electionType, electionTrigger, callback) =>
          processReplicaLeaderElection(partitions, electionType, electionTrigger, callback)
        case UncleanLeaderElectionEnable =>
          processUncleanLeaderElectionEnable()
        case TopicUncleanLeaderElectionEnable(topic) =>
          processTopicUncleanLeaderElectionEnable(topic)
        case ControlledShutdown(id, brokerEpoch, callback) =>
          processControlledShutdown(id, brokerEpoch, callback)
        case LeaderAndIsrResponseReceived(response, brokerId) =>
          processLeaderAndIsrResponseReceived(response, brokerId)
        case UpdateMetadataResponseReceived(response, brokerId) =>
          processUpdateMetadataResponseReceived(response, brokerId)
        case TopicDeletionStopReplicaResponseReceived(replicaId, requestError, partitionErrors) =>
          processTopicDeletionStopReplicaResponseReceived(replicaId, requestError, partitionErrors)
        case BrokerChange =>
          processBrokerChange()
        case BrokerModifications(brokerId) =>
          processBrokerModification(brokerId)
        case ControllerChange =>
          processControllerChange()
        case Reelect =>
          processReelect()
        case RegisterBrokerAndReelect =>
          processRegisterBrokerAndReelect()
        case Expire =>
          processExpire()
        case TopicChange =>
          processTopicChange()
        case LogDirEventNotification =>
          processLogDirEventNotification()
        case PartitionModifications(topic) =>
          processPartitionModifications(topic)
        case TopicDeletion =>
          processTopicDeletion()
        case ApiPartitionReassignment(reassignments, callback) =>
          processApiPartitionReassignment(reassignments, callback)
        case ZkPartitionReassignment =>
          processZkPartitionReassignment()
        case ListPartitionReassignments(partitions, callback) =>
          processListPartitionReassignments(partitions, callback)
        case UpdateFeatures(request, callback) =>
          processFeatureUpdates(request, callback)
        case PartitionReassignmentIsrChange(partition) =>
          processPartitionReassignmentIsrChange(partition)
        case IsrChangeNotification =>
          processIsrChangeNotification()
        case AlterPartitionReceived(alterPartitionRequest, alterPartitionRequestVersion, callback) =>
          processAlterPartition(alterPartitionRequest, alterPartitionRequestVersion, callback)
        case AllocateProducerIds(brokerId, brokerEpoch, callback) =>
          processAllocateProducerIds(brokerId, brokerEpoch, callback)
        case Startup =>
          // 执行选举 Controller的场景一：集群从零启动【执行选举 Controller的场景二：/controller 节点消失】
          // 集群首次启动时，Controller 尚未被选举出来。于是，Broker 启动后，首先将 Startup 这个 ControllerEvent 写入到事件队列中，
          // 然后启动对应的事件处理线程和 ControllerChangeHandler ZooKeeper 监听器，最后依赖事件处理线程进行 Controller 的选举。
          //
          // process 方法调用 processStartup 方法去处理 Startup 事件。
          // 而 processStartup 方法又会调用 zkClient 的 registerZNodeChangeHandlerAndCheckExistence 方法注册 ControllerChangeHandler 监听器。
          processStartup() // 处理Startup事件
      }
    } catch {
      // 如果Controller换成了别的Broker
      case e: ControllerMovedException =>
        info(s"Controller moved to another broker when processing $event.", e)
        // 执行Controller卸任逻辑
        maybeResign()
      case e: Throwable =>
        error(s"Error processing event $event", e)
    } finally {
      updateMetrics()
    }
  }

  override def preempt(event: ControllerEvent): Unit = {
    event.preempt()
  }
}

/**
 * 该方法的作用就是向 Controller 事件队列写入一个 BrokerChange 事件。
 * 事实上，Controller 端定义的所有 Handler 的处理逻辑，都是向事件队列写入相应的 ControllerEvent，
 * 真正的事件处理逻辑位于 KafkaController 类的 process 方法中。
 * @param eventManager
 */
class BrokerChangeHandler(eventManager: ControllerEventManager) extends ZNodeChildChangeHandler {
  // Broker ZooKeeper ZNode: /brokers/ids
  override val path: String = BrokerIdsZNode.path

  override def handleChildChange(): Unit = {
    eventManager.put(BrokerChange) // 仅向事件队列写入BrokerChange事件
  }
}

class BrokerModificationsHandler(eventManager: ControllerEventManager, brokerId: Int) extends ZNodeChangeHandler {
  override val path: String = BrokerIdZNode.path(brokerId)

  override def handleDataChange(): Unit = {
    eventManager.put(BrokerModifications(brokerId))
  }
}

/**
 * 代码中的 TopicsZNode.path 就是 ZooKeeper 下 /brokers/topics 节点。
 * 一旦该节点下新增了主题信息，该监听器的 handleChildChange 就会被触发，Controller 通过 ControllerEventManager 对象，向事件队列写入 TopicChange 事件。
 * @param eventManager
 */
class TopicChangeHandler(eventManager: ControllerEventManager) extends ZNodeChildChangeHandler {
  // ZooKeeper节点：/brokers/topics
  override val path: String = TopicsZNode.path
  // 向事件队列写入TopicChange事件
  override def handleChildChange(): Unit = eventManager.put(TopicChange)
}

class LogDirEventNotificationHandler(eventManager: ControllerEventManager) extends ZNodeChildChangeHandler {
  override val path: String = LogDirEventNotificationZNode.path

  override def handleChildChange(): Unit = eventManager.put(LogDirEventNotification)
}

object LogDirEventNotificationHandler {
  val Version: Long = 1L
}

class PartitionModificationsHandler(eventManager: ControllerEventManager, topic: String) extends ZNodeChangeHandler {
  override val path: String = TopicZNode.path(topic)

  override def handleDataChange(): Unit = eventManager.put(PartitionModifications(topic))
}


/**
 * 删除主题也依赖 ZooKeeper 监听器完成
 * DeleteTopicsZNode.path 指的是 /admin/delete_topics 节点。目前，无论是 kafka-topics 脚本，还是 AdminClient，
 * 删除主题都是在 /admin/delete_topics 节点下创建名为待删除主题名的子节点。
 * 比如，如果要删除 test-topic 主题，那么，Kafka 的删除命令仅仅是在 ZooKeeper 上创建 /admin/delete_topics/test-topic 节点。
 * 一旦监听到该节点被创建，TopicDeletionHandler 的 handleChildChange 方法就会被触发，Controller 会向事件队列写入 TopicDeletion 事件。
 *
 * @param eventManager
 */
class TopicDeletionHandler(eventManager: ControllerEventManager) extends ZNodeChildChangeHandler {
  // ZooKeeper节点：/admin/delete_topics
  override val path: String = DeleteTopicsZNode.path
  // 向事件队列写入TopicDeletion事件
  override def handleChildChange(): Unit = eventManager.put(TopicDeletion)
}

class PartitionReassignmentHandler(eventManager: ControllerEventManager) extends ZNodeChangeHandler {
  override val path: String = ReassignPartitionsZNode.path

  // Note that the event is also enqueued when the znode is deleted, but we do it explicitly instead of relying on
  // handleDeletion(). This approach is more robust as it doesn't depend on the watcher being re-registered after
  // it's consumed during data changes (we ensure re-registration when the znode is deleted).
  override def handleCreation(): Unit = eventManager.put(ZkPartitionReassignment)
}

class PartitionReassignmentIsrChangeHandler(eventManager: ControllerEventManager, partition: TopicPartition) extends ZNodeChangeHandler {
  override val path: String = TopicPartitionStateZNode.path(partition)

  override def handleDataChange(): Unit = eventManager.put(PartitionReassignmentIsrChange(partition))
}

class IsrChangeNotificationHandler(eventManager: ControllerEventManager) extends ZNodeChildChangeHandler {
  override val path: String = IsrChangeNotificationZNode.path

  override def handleChildChange(): Unit = eventManager.put(IsrChangeNotification)
}

object IsrChangeNotificationHandler {
  val Version: Long = 1L
}

class PreferredReplicaElectionHandler(eventManager: ControllerEventManager) extends ZNodeChangeHandler {
  override val path: String = PreferredReplicaElectionZNode.path

  override def handleCreation(): Unit = eventManager.put(ReplicaLeaderElection(None, ElectionType.PREFERRED, ZkTriggered))
}

//它是监听 /controller 节点变更的。这种变更包括节点创建、删除以及数据变更。
class ControllerChangeHandler(eventManager: ControllerEventManager) extends ZNodeChangeHandler {
  // ZooKeeper中Controller节点路径，即/controller
  override val path: String = ControllerZNode.path
  // 监听/controller节点创建事件
  override def handleCreation(): Unit = eventManager.put(ControllerChange)
  // 监听/controller节点被删除事件
  override def handleDeletion(): Unit = eventManager.put(Reelect)
  // 监听/controller节点数据变更事件
  override def handleDataChange(): Unit = eventManager.put(ControllerChange)
}

case class PartitionAndReplica(topicPartition: TopicPartition, replica: Int) {
  def topic: String = topicPartition.topic
  def partition: Int = topicPartition.partition

  override def toString: String = {
    s"[Topic=$topic,Partition=$partition,Replica=$replica]"
  }
}

/**
 *
 * @param leaderAndIsr
 * @param controllerEpoch 可以理解是controller换了多少次。比如epoch = 5，说明controller前前后后更换过6次
 */
case class LeaderIsrAndControllerEpoch(leaderAndIsr: LeaderAndIsr, controllerEpoch: Int) {
  override def toString: String = {
    val leaderAndIsrInfo = new StringBuilder
    leaderAndIsrInfo.append("(Leader:" + leaderAndIsr.leader)
    leaderAndIsrInfo.append(",ISR:" + leaderAndIsr.isr.mkString(","))
    leaderAndIsrInfo.append(",LeaderRecoveryState:" + leaderAndIsr.leaderRecoveryState)
    leaderAndIsrInfo.append(",LeaderEpoch:" + leaderAndIsr.leaderEpoch)
    leaderAndIsrInfo.append(",ZkVersion:" + leaderAndIsr.partitionEpoch)
    leaderAndIsrInfo.append(",ControllerEpoch:" + controllerEpoch + ")")
    leaderAndIsrInfo.toString()
  }
}

/**
 * 目前定义了两大类统计指标：UncleanLeaderElectionsPerSec 和所有 Controller 事件状态的执行速率与时间。
 */
private[controller] class ControllerStats {
  private val metricsGroup = new KafkaMetricsGroup(this.getClass)
  // 统计每秒发生的Unclean Leader选举次数
  // 计算 Controller 每秒执行的 Unclean Leader 选举数量，
  // 通常情况下，执行 Unclean Leader 选举可能造成数据丢失，一般不建议开启它。
  // 一旦开启，你就需要时刻关注这个监控指标的值，确保 Unclean Leader 选举的速率维持在一个很低的水平，否则会出现很多数据丢失的情况。
  val uncleanLeaderElectionRate = metricsGroup.newMeter("UncleanLeaderElectionsPerSec", "elections", TimeUnit.SECONDS)

  // Controller事件通用的统计速率指标的方法
  // 统计所有 Controller 状态的速率和时间信息，单位是毫秒。
  // 当前，Controller 定义了很多事件，比如，TopicDeletion 是执行主题删除的 Controller 事件、ControllerChange 是执行 Controller 重选举的事件。
  // ControllerStats 的这个指标通过在每个事件名后拼接字符串 RateAndTimeMs 的方式，为每类 Controller 事件都创建了对应的速率监控指标。
  val rateAndTimeMetrics: Map[ControllerState, Timer] = ControllerState.values.flatMap { state =>
    state.rateAndTimeMetricName.map { metricName =>
      state -> metricsGroup.newTimer(metricName, TimeUnit.MILLISECONDS, TimeUnit.SECONDS)
    }
  }.toMap

  // For test.
  def removeMetric(name: String): Unit = {
    metricsGroup.removeMetric(name)
  }
}
//Controller 事件，也就是事件队列中被处理的对象。
//每个 ControllerEvent 都定义了一个状态。Controller 在处理具体的事件时，会对状态进行相应的变更。
//多个 ControllerEvent 可能归属于相同的 ControllerState。
//比如，TopicChange 和 PartitionModifications 事件都属于 TopicChange 状态，毕竟，它们都与 Topic 的变更有关。
sealed trait ControllerEvent {
  def state: ControllerState
  // preempt() is not executed by `ControllerEventThread` but by the main thread.
  def preempt(): Unit
}

case object ControllerChange extends ControllerEvent {
  override def state: ControllerState = ControllerState.ControllerChange
  override def preempt(): Unit = {}
}

case object Reelect extends ControllerEvent {
  override def state: ControllerState = ControllerState.ControllerChange
  override def preempt(): Unit = {}
}

case object RegisterBrokerAndReelect extends ControllerEvent {
  override def state: ControllerState = ControllerState.ControllerChange
  override def preempt(): Unit = {}
}

case object Expire extends ControllerEvent {
  override def state: ControllerState = ControllerState.ControllerChange
  override def preempt(): Unit = {}
}

case object ShutdownEventThread extends ControllerEvent {
  override def state: ControllerState = ControllerState.ControllerShutdown
  override def preempt(): Unit = {}
}

case object AutoPreferredReplicaLeaderElection extends ControllerEvent {
  override def state: ControllerState = ControllerState.AutoLeaderBalance
  override def preempt(): Unit = {}
}

case object UncleanLeaderElectionEnable extends ControllerEvent {
  override def state: ControllerState = ControllerState.UncleanLeaderElectionEnable
  override def preempt(): Unit = {}
}

case class TopicUncleanLeaderElectionEnable(topic: String) extends ControllerEvent {
  override def state: ControllerState = ControllerState.TopicUncleanLeaderElectionEnable
  override def preempt(): Unit = {}
}

case class ControlledShutdown(id: Int, brokerEpoch: Long, controlledShutdownCallback: Try[Set[TopicPartition]] => Unit) extends ControllerEvent {
  override def state: ControllerState = ControllerState.ControlledShutdown
  override def preempt(): Unit = controlledShutdownCallback(Failure(new ControllerMovedException("Controller moved to another broker")))
}

case class LeaderAndIsrResponseReceived(leaderAndIsrResponse: LeaderAndIsrResponse, brokerId: Int) extends ControllerEvent {
  override def state: ControllerState = ControllerState.LeaderAndIsrResponseReceived
  override def preempt(): Unit = {}
}

case class UpdateMetadataResponseReceived(updateMetadataResponse: UpdateMetadataResponse, brokerId: Int) extends ControllerEvent {
  override def state: ControllerState = ControllerState.UpdateMetadataResponseReceived
  override def preempt(): Unit = {}
}

case class TopicDeletionStopReplicaResponseReceived(replicaId: Int,
                                                    requestError: Errors,
                                                    partitionErrors: Map[TopicPartition, Errors]) extends ControllerEvent {
  override def state: ControllerState = ControllerState.TopicDeletion
  override def preempt(): Unit = {}
}

case object Startup extends ControllerEvent {
  override def state: ControllerState = ControllerState.ControllerChange
  override def preempt(): Unit = {}
}

case object BrokerChange extends ControllerEvent {
  override def state: ControllerState = ControllerState.BrokerChange
  override def preempt(): Unit = {}
}

case class BrokerModifications(brokerId: Int) extends ControllerEvent {
  override def state: ControllerState = ControllerState.BrokerChange
  override def preempt(): Unit = {}
}

case object TopicChange extends ControllerEvent {
  override def state: ControllerState = ControllerState.TopicChange
  override def preempt(): Unit = {}
}

case object LogDirEventNotification extends ControllerEvent {
  override def state: ControllerState = ControllerState.LogDirChange
  override def preempt(): Unit = {}
}

case class PartitionModifications(topic: String) extends ControllerEvent {
  override def state: ControllerState = ControllerState.TopicChange
  override def preempt(): Unit = {}
}

case object TopicDeletion extends ControllerEvent {
  override def state: ControllerState = ControllerState.TopicDeletion
  override def preempt(): Unit = {}
}

case object ZkPartitionReassignment extends ControllerEvent {
  override def state: ControllerState = ControllerState.AlterPartitionReassignment
  override def preempt(): Unit = {}
}

case class ApiPartitionReassignment(reassignments: Map[TopicPartition, Option[Seq[Int]]],
                                    callback: AlterReassignmentsCallback) extends ControllerEvent {
  override def state: ControllerState = ControllerState.AlterPartitionReassignment
  override def preempt(): Unit = callback(Right(new ApiError(Errors.NOT_CONTROLLER)))
}

case class PartitionReassignmentIsrChange(partition: TopicPartition) extends ControllerEvent {
  override def state: ControllerState = ControllerState.AlterPartitionReassignment
  override def preempt(): Unit = {}
}

case object IsrChangeNotification extends ControllerEvent {
  override def state: ControllerState = ControllerState.IsrChange
  override def preempt(): Unit = {}
}

case class AlterPartitionReceived(
  alterPartitionRequest: AlterPartitionRequestData,
  alterPartitionRequestVersion: Short,
  callback: AlterPartitionResponseData => Unit
) extends ControllerEvent {
  override def state: ControllerState = ControllerState.IsrChange
  override def preempt(): Unit = {}
}

case class ReplicaLeaderElection(
  partitionsFromAdminClientOpt: Option[Set[TopicPartition]],
  electionType: ElectionType,
  electionTrigger: ElectionTrigger,
  callback: ElectLeadersCallback = _ => {}
) extends ControllerEvent {
  override def state: ControllerState = ControllerState.ManualLeaderBalance

  override def preempt(): Unit = callback(
    partitionsFromAdminClientOpt.fold(Map.empty[TopicPartition, Either[ApiError, Int]]) { partitions =>
      partitions.iterator.map(partition => partition -> Left(new ApiError(Errors.NOT_CONTROLLER, null))).toMap
    }
  )
}

/**
  * @param partitionsOpt - an Optional set of partitions. If not present, all reassigning partitions are to be listed
  */
case class ListPartitionReassignments(partitionsOpt: Option[Set[TopicPartition]],
                                      callback: ListReassignmentsCallback) extends ControllerEvent {
  override def state: ControllerState = ControllerState.ListPartitionReassignment
  override def preempt(): Unit = callback(Right(new ApiError(Errors.NOT_CONTROLLER, null)))
}

case class UpdateFeatures(request: UpdateFeaturesRequest,
                          callback: UpdateFeaturesCallback) extends ControllerEvent {
  override def state: ControllerState = ControllerState.UpdateFeatures
  override def preempt(): Unit = {}
}

case class AllocateProducerIds(brokerId: Int, brokerEpoch: Long, callback: Either[Errors, ProducerIdsBlock] => Unit)
    extends ControllerEvent {
  override def state: ControllerState = ControllerState.Idle
  override def preempt(): Unit = {}
}


// Used only in test cases
abstract class MockEvent(val state: ControllerState) extends ControllerEvent {
  def process(): Unit
  def preempt(): Unit
}
