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

import kafka.api.LeaderAndIsr
import kafka.cluster.Broker
import kafka.utils.Implicits._
import org.apache.kafka.common.{TopicPartition, Uuid}

import scala.collection.{Map, Seq, Set, mutable}

object ReplicaAssignment {
  def apply(replicas: Seq[Int]): ReplicaAssignment = {
    apply(replicas, Seq.empty, Seq.empty)
  }

  val empty: ReplicaAssignment = apply(Seq.empty)
}


/**
 * @param replicas the sequence of brokers assigned to the partition. It includes the set of brokers
 *                 that were added (`addingReplicas`) and removed (`removingReplicas`).
 * @param addingReplicas the replicas that are being added if there is a pending reassignment
 * @param removingReplicas the replicas that are being removed if there is a pending reassignment
 */
case class ReplicaAssignment private (replicas: Seq[Int],
                                      addingReplicas: Seq[Int],
                                      removingReplicas: Seq[Int]) {

  lazy val originReplicas: Seq[Int] = replicas.diff(addingReplicas)
  lazy val targetReplicas: Seq[Int] = replicas.diff(removingReplicas)

  def isBeingReassigned: Boolean = {
    addingReplicas.nonEmpty || removingReplicas.nonEmpty
  }

  def reassignTo(target: Seq[Int]): ReplicaAssignment = {
    val fullReplicaSet = (target ++ originReplicas).distinct
    ReplicaAssignment(
      fullReplicaSet,
      fullReplicaSet.diff(originReplicas),
      fullReplicaSet.diff(target)
    )
  }

  def removeReplica(replica: Int): ReplicaAssignment = {
    ReplicaAssignment(
      replicas.filterNot(_ == replica),
      addingReplicas.filterNot(_ == replica),
      removingReplicas.filterNot(_ == replica)
    )
  }

  override def toString: String = s"ReplicaAssignment(" +
    s"replicas=${replicas.mkString(",")}, " +
    s"addingReplicas=${addingReplicas.mkString(",")}, " +
    s"removingReplicas=${removingReplicas.mkString(",")})"
}

/**
 * 这个类是 Controller 组件的数据容器类
 * 它定义了所有元数据信息，以及许多实用的工具方法。
 */
class ControllerContext extends ControllerChannelContext {
  val stats = new ControllerStats // Controller统计信息类
  var offlinePartitionCount = 0 // 离线分区计数器，该字段统计集群中所有离线或处于不可用状态的主题分区数量。所谓的不可用状态，就是“Leader=-1”的情况。
  var preferredReplicaImbalanceCount = 0
  // 当 Controller 在管理集群 Broker 时，它要依靠shuttingDownBrokerIds这个字段来甄别 Broker 当前是否已关闭，
  // 因为处于关闭状态的 Broker 是不适合执行某些操作的，如分区重分配（Reassignment）以及主题删除等。
  val shuttingDownBrokerIds = mutable.Set.empty[Int] // 该字段保存所有正在关闭中的 Broker ID 列表
  private val liveBrokers = mutable.Set.empty[Broker] // 当前运行中Broker对象列表
  private val liveBrokerEpochs = mutable.Map.empty[Int, Long] // 运行中Broker ,Kafka 使用 Epoch 数据防止 Zombie Broker，即一个非常老的 Broker 被选举成为 Controller。
  /**
   * epoch 实际上就是 ZooKeeper 中 /controller_epoch 节点的值，你可以认为它就是 Controller 在整个 Kafka 集群的版本号，
   * 而 epochZkVersion 实际上是 /controller_epoch 节点的 dataVersion 值。
   * Kafka 使用 epochZkVersion 来判断和防止 Zombie Controller。
   * 这也就是说，原先在老 Controller 任期内的 Controller 操作在新 Controller 不能成功执行，
   * 因为新 Controller 的 epochZkVersion 要比老 Controller 的大。
   */
  var epoch: Int = KafkaController.InitialControllerEpoch // Controller当前Epoch值
  var epochZkVersion: Int = KafkaController.InitialControllerEpochZkVersion // Controller对应ZooKeeper节点的Epoch值

  val allTopics = mutable.Set.empty[String] // 集群主题列表
  var topicIds = mutable.Map.empty[String, Uuid]
  var topicNames = mutable.Map.empty[Uuid, String]
  val partitionAssignments = mutable.Map.empty[String, mutable.Map[Int, ReplicaAssignment]] // 主题分区的副本列表,该字段保存所有主题分区的副本分配情况。
  private val partitionLeadershipInfo = mutable.Map.empty[TopicPartition, LeaderIsrAndControllerEpoch] // 每个分区对应的分区主副本，isr集合，还有controller的epoch数
  val partitionsBeingReassigned = mutable.Set.empty[TopicPartition] // 正处于副本重分配过程的主题分区列表
  val partitionStates = mutable.Map.empty[TopicPartition, PartitionState] // 主题分区状态列表
  val replicaStates = mutable.Map.empty[PartitionAndReplica, ReplicaState] // 主题分区的副本状态列表
  val replicasOnOfflineDirs = mutable.Map.empty[Int, Set[TopicPartition]] // 不可用磁盘路径上的副本列表

  val topicsToBeDeleted = mutable.Set.empty[String] // 待删除主题列表

  /** The following topicsWithDeletionStarted variable is used to properly update the offlinePartitionCount metric.
   * When a topic is going through deletion, we don't want to keep track of its partition state
   * changes in the offlinePartitionCount metric. This goal means if some partitions of a topic are already
   * in OfflinePartition state when deletion starts, we need to change the corresponding partition
   * states to NonExistentPartition first before starting the deletion.
   *
   * However we can NOT change partition states to NonExistentPartition at the time of enqueuing topics
   * for deletion. The reason is that when a topic is enqueued for deletion, it may be ineligible for
   * deletion due to ongoing partition reassignments. Hence there might be a delay between enqueuing
   * a topic for deletion and the actual start of deletion. In this delayed interval, partitions may still
   * transition to or out of the OfflinePartition state.
   *
   * Hence we decide to change partition states to NonExistentPartition only when the actual deletion have started.
   * For topics whose deletion have actually started, we keep track of them in the following topicsWithDeletionStarted
   * variable. And once a topic is in the topicsWithDeletionStarted set, we are sure there will no longer
   * be partition reassignments to any of its partitions, and only then it's safe to move its partitions to
   * NonExistentPartition state. Once a topic is in the topicsWithDeletionStarted set, we will stop monitoring
   * its partition state changes in the offlinePartitionCount metric
   */
  val topicsWithDeletionStarted = mutable.Set.empty[String] // 已开启删除的主题列表
  val topicsIneligibleForDeletion = mutable.Set.empty[String] // 暂时无法执行删除的主题列表

  private def clearTopicsState(): Unit = {
    allTopics.clear()
    topicIds.clear()
    topicNames.clear()
    partitionAssignments.clear()
    partitionLeadershipInfo.clear()
    partitionsBeingReassigned.clear()
    replicasOnOfflineDirs.clear()
    partitionStates.clear()
    offlinePartitionCount = 0
    preferredReplicaImbalanceCount = 0
    replicaStates.clear()
  }

  def addTopicId(topic: String, id: Uuid): Unit = {
    if (!allTopics.contains(topic))
      throw new IllegalStateException(s"topic $topic is not contained in all topics.")

    topicIds.get(topic).foreach { existingId =>
      if (!existingId.equals(id))
        throw new IllegalStateException(s"topic ID map already contained ID for topic " +
          s"$topic and new ID $id did not match existing ID $existingId")
    }
    topicNames.get(id).foreach { existingName =>
      if (!existingName.equals(topic))
        throw new IllegalStateException(s"topic name map already contained ID " +
          s"$id and new name $topic did not match existing name $existingName")
    }
    topicIds.put(topic, id)
    topicNames.put(id, topic)
  }

  def partitionReplicaAssignment(topicPartition: TopicPartition): Seq[Int] = {
    partitionAssignments.getOrElse(topicPartition.topic, mutable.Map.empty).get(topicPartition.partition) match {
      case Some(partitionAssignment) => partitionAssignment.replicas
      case None => Seq.empty
    }
  }

  def partitionFullReplicaAssignment(topicPartition: TopicPartition): ReplicaAssignment = {
    partitionAssignments.getOrElse(topicPartition.topic, mutable.Map.empty)
      .getOrElse(topicPartition.partition, ReplicaAssignment.empty)
  }

  def updatePartitionFullReplicaAssignment(topicPartition: TopicPartition, newAssignment: ReplicaAssignment): Unit = {
    val assignments = partitionAssignments.getOrElseUpdate(topicPartition.topic, mutable.Map.empty)
    val previous = assignments.put(topicPartition.partition, newAssignment)
    val leadershipInfo = partitionLeadershipInfo.get(topicPartition)
    updatePreferredReplicaImbalanceMetric(topicPartition, previous, leadershipInfo,
      Some(newAssignment), leadershipInfo)
  }

  def partitionReplicaAssignmentForTopic(topic : String): Map[TopicPartition, Seq[Int]] = {
    partitionAssignments.getOrElse(topic, Map.empty).map {
      case (partition, assignment) => (new TopicPartition(topic, partition), assignment.replicas)
    }.toMap
  }

  def partitionFullReplicaAssignmentForTopic(topic : String): Map[TopicPartition, ReplicaAssignment] = {
    partitionAssignments.getOrElse(topic, Map.empty).map {
      case (partition, assignment) => (new TopicPartition(topic, partition), assignment)
    }.toMap
  }

  def allPartitions: Set[TopicPartition] = {
    partitionAssignments.flatMap {
      case (topic, topicReplicaAssignment) => topicReplicaAssignment.map {
        case (partition, _) => new TopicPartition(topic, partition)
      }
    }.toSet
  }

  def setLiveBrokers(brokerAndEpochs: Map[Broker, Long]): Unit = {
    clearLiveBrokers()
    addLiveBrokers(brokerAndEpochs)
  }

  private def clearLiveBrokers(): Unit = {
    liveBrokers.clear()
    liveBrokerEpochs.clear()
  }

  def addLiveBrokers(brokerAndEpochs: Map[Broker, Long]): Unit = {
    liveBrokers ++= brokerAndEpochs.keySet
    liveBrokerEpochs ++= brokerAndEpochs.map { case (broker, brokerEpoch) => (broker.id, brokerEpoch) }
  }

  def removeLiveBrokers(brokerIds: Set[Int]): Unit = {
    liveBrokers --= liveBrokers.filter(broker => brokerIds.contains(broker.id))
    liveBrokerEpochs --= brokerIds
  }

  /**
   * 每当新增或移除已有 Broker 时，ZooKeeper 就会更新其保存的 Broker 数据，
   * 从而引发 Controller 修改元数据，也就是会调用 updateBrokerMetadata 方法来增减 Broker 列表中的对象。
   * @param oldMetadata
   * @param newMetadata
   */
  def updateBrokerMetadata(oldMetadata: Broker, newMetadata: Broker): Unit = {
    liveBrokers -= oldMetadata
    liveBrokers += newMetadata
  }

  // getter
  /**
   * liveBrokerEpochs 的 keySet 方法返回 Broker 序号列表，然后从中移除关闭中的 Broker 序号，
   * 剩下的自然就是处于运行中的 Broker 序号列表了。
   * 大多使用这个字段来获取所有运行中 Broker 的 ID 序号
   * @return
   */
  def liveBrokerIds: Set[Int] = liveBrokerEpochs.keySet.diff(shuttingDownBrokerIds)
  def liveOrShuttingDownBrokerIds: Set[Int] = liveBrokerEpochs.keySet
  def liveOrShuttingDownBrokers: Set[Broker] = liveBrokers
  def liveBrokerIdAndEpochs: Map[Int, Long] = liveBrokerEpochs
  def liveOrShuttingDownBroker(brokerId: Int): Option[Broker] = liveOrShuttingDownBrokers.find(_.id == brokerId)

  /**
   * Kafka 要获取某个 Broker 上的所有分区
   * @param brokerId
   * @return
   */
  def partitionsOnBroker(brokerId: Int): Set[TopicPartition] = {
    partitionAssignments.flatMap {
      case (topic, topicReplicaAssignment) => topicReplicaAssignment.filter {
        case (_, partitionAssignment) => partitionAssignment.replicas.contains(brokerId)
      }.map {
        case (partition, _) => new TopicPartition(topic, partition)
      }
    }.toSet
  }

  def isReplicaOnline(brokerId: Int, topicPartition: TopicPartition): Boolean = {
    isReplicaOnline(brokerId, topicPartition, includeShuttingDownBrokers = false)
  }

  def isReplicaOnline(brokerId: Int, topicPartition: TopicPartition, includeShuttingDownBrokers: Boolean): Boolean = {
    val brokerOnline = {
      if (includeShuttingDownBrokers) liveOrShuttingDownBrokerIds.contains(brokerId)
      else liveBrokerIds.contains(brokerId)
    }
    brokerOnline && !replicasOnOfflineDirs.getOrElse(brokerId, Set.empty).contains(topicPartition)
  }

  def replicasOnBrokers(brokerIds: Set[Int]): Set[PartitionAndReplica] = {
    brokerIds.flatMap { brokerId =>
      partitionAssignments.flatMap {
        case (topic, topicReplicaAssignment) => topicReplicaAssignment.collect {
          case (partition, partitionAssignment) if partitionAssignment.replicas.contains(brokerId) =>
            PartitionAndReplica(new TopicPartition(topic, partition), brokerId)
        }
      }
    }
  }

  def replicasForTopic(topic: String): Set[PartitionAndReplica] = {
    partitionAssignments.getOrElse(topic, mutable.Map.empty).flatMap {
      case (partition, assignment) => assignment.replicas.map { r =>
        PartitionAndReplica(new TopicPartition(topic, partition), r)
      }
    }.toSet
  }

  /**
   * 如果 Kafka 要获取某个主题的所有分区对象
   * @param topic
   * @return
   */
  def partitionsForTopic(topic: String): collection.Set[TopicPartition] = {
    partitionAssignments.getOrElse(topic, mutable.Map.empty).map {
      case (partition, _) => new TopicPartition(topic, partition)
    }.toSet
  }

  /**
    * Get all online and offline replicas.
    *
    * @return a tuple consisting of first the online replicas and followed by the offline replicas
    */
  def onlineAndOfflineReplicas: (Set[PartitionAndReplica], Set[PartitionAndReplica]) = {
    val onlineReplicas = mutable.Set.empty[PartitionAndReplica]
    val offlineReplicas = mutable.Set.empty[PartitionAndReplica]
    for ((topic, partitionAssignments) <- partitionAssignments;
         (partitionId, assignment) <- partitionAssignments) {
      val partition = new TopicPartition(topic, partitionId)
      for (replica <- assignment.replicas) {
        val partitionAndReplica = PartitionAndReplica(partition, replica)
        if (isReplicaOnline(replica, partition))
          onlineReplicas.add(partitionAndReplica)
        else
          offlineReplicas.add(partitionAndReplica)
      }
    }
    (onlineReplicas, offlineReplicas)
  }

  def replicasForPartition(partitions: collection.Set[TopicPartition]): collection.Set[PartitionAndReplica] = {
    partitions.flatMap { p =>
      val replicas = partitionReplicaAssignment(p)
      replicas.map(PartitionAndReplica(p, _))
    }
  }

  def resetContext(): Unit = {
    topicsToBeDeleted.clear()
    topicsWithDeletionStarted.clear()
    topicsIneligibleForDeletion.clear()
    shuttingDownBrokerIds.clear()
    epoch = 0
    epochZkVersion = 0
    clearTopicsState()
    clearLiveBrokers()
  }

  def setAllTopics(topics: Set[String]): Unit = {
    allTopics.clear()
    allTopics ++= topics
  }

  def removeTopic(topic: String): Unit = {
    // Metric is cleaned when the topic is queued up for deletion so
    // we don't clean it twice. We clean it only if it is deleted
    // directly.
    if (!topicsToBeDeleted.contains(topic))
      cleanPreferredReplicaImbalanceMetric(topic)
    topicsToBeDeleted -= topic
    topicsWithDeletionStarted -= topic
    allTopics -= topic
    topicIds.remove(topic).foreach { topicId =>
      topicNames.remove(topicId)
    }
    partitionAssignments.remove(topic).foreach { assignments =>
      assignments.keys.foreach { partition =>
        partitionLeadershipInfo.remove(new TopicPartition(topic, partition))
      }
    }
  }

  def queueTopicDeletion(topicToBeAddedIntoDeletionList: Set[String]): Unit = {
    // queueTopicDeletion could be called multiple times for same topic.
    // e.g. 1) delete topic-A => 2) delete topic-B before A's deletion completes.
    // In this case, at 2), queueTopicDeletion will be called with Set(topic-A, topic-B).
    // However we should call cleanPreferredReplicaImbalanceMetric only once for same topic
    // because otherwise, preferredReplicaImbalanceCount could be decremented wrongly at 2nd call.
    // So we need to take a diff with already queued topics here.
    val newlyDeletedTopics = topicToBeAddedIntoDeletionList.diff(topicsToBeDeleted)
    topicsToBeDeleted ++= newlyDeletedTopics
    newlyDeletedTopics.foreach(cleanPreferredReplicaImbalanceMetric)
  }

  def beginTopicDeletion(topics: Set[String]): Unit = {
    topicsWithDeletionStarted ++= topics
  }

  def isTopicDeletionInProgress(topic: String): Boolean = {
    topicsWithDeletionStarted.contains(topic)
  }

  def isTopicQueuedUpForDeletion(topic: String): Boolean = {
    topicsToBeDeleted.contains(topic)
  }

  def isTopicEligibleForDeletion(topic: String): Boolean = {
    topicsToBeDeleted.contains(topic) && !topicsIneligibleForDeletion.contains(topic)
  }

  def topicsQueuedForDeletion: Set[String] = {
    topicsToBeDeleted
  }

  def replicasInState(topic: String, state: ReplicaState): Set[PartitionAndReplica] = {
    replicasForTopic(topic).filter(replica => replicaStates(replica) == state).toSet
  }

  def areAllReplicasInState(topic: String, state: ReplicaState): Boolean = {
    replicasForTopic(topic).forall(replica => replicaStates(replica) == state)
  }

  def isAnyReplicaInState(topic: String, state: ReplicaState): Boolean = {
    replicasForTopic(topic).exists(replica => replicaStates(replica) == state)
  }

  def checkValidReplicaStateChange(replicas: Seq[PartitionAndReplica], targetState: ReplicaState): (Seq[PartitionAndReplica], Seq[PartitionAndReplica]) = {
    replicas.partition(replica => isValidReplicaStateTransition(replica, targetState))
  }

  def checkValidPartitionStateChange(partitions: Seq[TopicPartition], targetState: PartitionState): (Seq[TopicPartition], Seq[TopicPartition]) = {
    partitions.partition(p => isValidPartitionStateTransition(p, targetState))
  }

  def putReplicaState(replica: PartitionAndReplica, state: ReplicaState): Unit = {
    replicaStates.put(replica, state)
  }

  def removeReplicaState(replica: PartitionAndReplica): Unit = {
    replicaStates.remove(replica)
  }

  def putReplicaStateIfNotExists(replica: PartitionAndReplica, state: ReplicaState): Unit = {
    replicaStates.getOrElseUpdate(replica, state)
  }

  def putPartitionState(partition: TopicPartition, targetState: PartitionState): Unit = {
    val currentState = partitionStates.put(partition, targetState).getOrElse(NonExistentPartition)
    updatePartitionStateMetrics(partition, currentState, targetState)
  }

  /**
   * 更新offlinePartitionCount元数据
   * 根据给定主题分区的当前状态和目标状态，来判断该分区是否是离线状态的分区。
   * 如果是，则累加 offlinePartitionCount 字段的值，否则递减该值。
   *
   * 该方法首先要判断，此分区所属的主题当前是否处于删除操作的过程中。
   * 如果是的话，Kafka 就不能修改这个分区的状态，那么代码什么都不做，直接返回。否则，代码会判断该分区是否要转换到离线状态。
   * 如果 targetState 是 OfflinePartition，那么就将 offlinePartitionCount 值加 1，毕竟多了一个离线状态的分区。
   * 相反地，如果 currentState 是 offlinePartition，而 targetState 反而不是，那么就将 offlinePartitionCount 值减 1。
   * @param partition
   * @param currentState
   * @param targetState
   */
  private def updatePartitionStateMetrics(partition: TopicPartition,
                                          currentState: PartitionState,
                                          targetState: PartitionState): Unit = {
    // 如果该主题当前并未处于删除中状态
    if (!isTopicDeletionInProgress(partition.topic)) {
      // targetState表示该分区要变更到的状态
      // 如果当前状态不是OfflinePartition，即离线状态并且目标状态是离线状态
      // 这个if语句判断是否要将该主题分区状态转换到离线状态
      if (currentState != OfflinePartition && targetState == OfflinePartition) {
        offlinePartitionCount = offlinePartitionCount + 1
      } else if (currentState == OfflinePartition && targetState != OfflinePartition) {
        // 如果当前状态已经是离线状态，但targetState不是
        // 这个else if语句判断是否要将该主题分区状态转换到非离线状态
        offlinePartitionCount = offlinePartitionCount - 1
      }
    }
  }

  def putPartitionStateIfNotExists(partition: TopicPartition, state: PartitionState): Unit = {
    if (partitionStates.getOrElseUpdate(partition, state) == state)
      updatePartitionStateMetrics(partition, NonExistentPartition, state)
  }

  def replicaState(replica: PartitionAndReplica): ReplicaState = {
    replicaStates(replica)
  }

  def partitionState(partition: TopicPartition): PartitionState = {
    partitionStates(partition)
  }

  def partitionsInState(state: PartitionState): Set[TopicPartition] = {
    partitionStates.filter { case (_, s) => s == state }.keySet.toSet
  }

  def partitionsInStates(states: Set[PartitionState]): Set[TopicPartition] = {
    partitionStates.filter { case (_, s) => states.contains(s) }.keySet.toSet
  }

  def partitionsInState(topic: String, state: PartitionState): Set[TopicPartition] = {
    partitionsForTopic(topic).filter { partition => state == partitionState(partition) }.toSet
  }

  def partitionsInStates(topic: String, states: Set[PartitionState]): Set[TopicPartition] = {
    partitionsForTopic(topic).filter { partition => states.contains(partitionState(partition)) }.toSet
  }

  def putPartitionLeadershipInfo(partition: TopicPartition,
                                 leaderIsrAndControllerEpoch: LeaderIsrAndControllerEpoch): Unit = {
    val previous = partitionLeadershipInfo.put(partition, leaderIsrAndControllerEpoch)
    val replicaAssignment = partitionFullReplicaAssignment(partition)
    updatePreferredReplicaImbalanceMetric(partition, Some(replicaAssignment), previous,
      Some(replicaAssignment), Some(leaderIsrAndControllerEpoch))
  }

  def partitionLeaderAndIsr(partition: TopicPartition): Option[LeaderAndIsr] = {
    partitionLeadershipInfo.get(partition).map(_.leaderAndIsr)
  }

  def leaderEpoch(partition: TopicPartition): Int = {
    // A sentinel (-2) is used as an epoch if the topic is queued for deletion. It overrides
    // any existing epoch.
    if (isTopicQueuedUpForDeletion(partition.topic)) {
      LeaderAndIsr.EpochDuringDelete
    } else {
      partitionLeadershipInfo.get(partition)
        .map(_.leaderAndIsr.leaderEpoch)
        .getOrElse(LeaderAndIsr.NoEpoch)
    }
  }

  def partitionLeadershipInfo(partition: TopicPartition): Option[LeaderIsrAndControllerEpoch] = {
    partitionLeadershipInfo.get(partition)
  }

  def partitionsLeadershipInfo: Map[TopicPartition, LeaderIsrAndControllerEpoch] =
    partitionLeadershipInfo

  def partitionsWithLeaders: Set[TopicPartition] =
    partitionLeadershipInfo.keySet.filter(tp => !isTopicQueuedUpForDeletion(tp.topic))

  def partitionsWithOfflineLeader: Set[TopicPartition] = {
    partitionLeadershipInfo.filter { case (topicPartition, leaderIsrAndControllerEpoch) =>
      !isReplicaOnline(leaderIsrAndControllerEpoch.leaderAndIsr.leader, topicPartition) &&
        !isTopicQueuedUpForDeletion(topicPartition.topic)
    }.keySet
  }

  def partitionLeadersOnBroker(brokerId: Int): Set[TopicPartition] = {
    partitionLeadershipInfo.filter { case (topicPartition, leaderIsrAndControllerEpoch) =>
      !isTopicQueuedUpForDeletion(topicPartition.topic) &&
        leaderIsrAndControllerEpoch.leaderAndIsr.leader == brokerId &&
        partitionReplicaAssignment(topicPartition).size > 1
    }.keySet
  }

  def topicName(topicId: Uuid): Option[String] = {
    topicNames.get(topicId)
  }

  def clearPartitionLeadershipInfo(): Unit = partitionLeadershipInfo.clear()

  def partitionWithLeadersCount: Int = partitionLeadershipInfo.size

  private def updatePreferredReplicaImbalanceMetric(partition: TopicPartition,
                                                    oldReplicaAssignment: Option[ReplicaAssignment],
                                                    oldLeadershipInfo: Option[LeaderIsrAndControllerEpoch],
                                                    newReplicaAssignment: Option[ReplicaAssignment],
                                                    newLeadershipInfo: Option[LeaderIsrAndControllerEpoch]): Unit = {
    if (!isTopicQueuedUpForDeletion(partition.topic)) {
      oldReplicaAssignment.foreach { replicaAssignment =>
        oldLeadershipInfo.foreach { leadershipInfo =>
          if (!hasPreferredLeader(replicaAssignment, leadershipInfo))
            preferredReplicaImbalanceCount -= 1
        }
      }

      newReplicaAssignment.foreach { replicaAssignment =>
        newLeadershipInfo.foreach { leadershipInfo =>
          if (!hasPreferredLeader(replicaAssignment, leadershipInfo))
            preferredReplicaImbalanceCount += 1
        }
      }
    }
  }

  private def cleanPreferredReplicaImbalanceMetric(topic: String): Unit = {
    partitionAssignments.getOrElse(topic, mutable.Map.empty).forKeyValue { (partition, replicaAssignment) =>
      partitionLeadershipInfo.get(new TopicPartition(topic, partition)).foreach { leadershipInfo =>
        if (!hasPreferredLeader(replicaAssignment, leadershipInfo))
          preferredReplicaImbalanceCount -= 1
      }
    }
  }

  private def hasPreferredLeader(replicaAssignment: ReplicaAssignment,
                                 leadershipInfo: LeaderIsrAndControllerEpoch): Boolean = {
    val preferredReplica = replicaAssignment.replicas.head
    if (replicaAssignment.isBeingReassigned && replicaAssignment.addingReplicas.contains(preferredReplica))
      // reassigning partitions are not counted as imbalanced until the new replica joins the ISR (completes reassignment)
      !leadershipInfo.leaderAndIsr.isr.contains(preferredReplica)
    else
      leadershipInfo.leaderAndIsr.leader == preferredReplica
  }

  private def isValidReplicaStateTransition(replica: PartitionAndReplica, targetState: ReplicaState): Boolean =
    targetState.validPreviousStates.contains(replicaStates(replica))

  private def isValidPartitionStateTransition(partition: TopicPartition, targetState: PartitionState): Boolean =
    targetState.validPreviousStates.contains(partitionStates(partition))
}
