/*
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

package org.apache.spark.sql.kinesis

import java.util.concurrent.locks.ReentrantLock

import scala.collection.JavaConversions._
import scala.collection.mutable

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.kinesis.AmazonKinesisClient

import org.apache.spark.internal.Logging
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.JoinedRow
import org.apache.spark.sql.execution.LogicalRDD
import org.apache.spark.sql.execution.streaming._
import org.apache.spark.sql.types._
import org.apache.spark.storage.{BlockId, StorageLevel, StreamBlockId}
import org.apache.spark.streaming.kinesis._
import org.apache.spark.streaming._

/**
 * A [[Source]] that uses the Kinesis Client Library to reads data from Amazon Kinesis.
 */
private[kinesis] class KinesisSource(
    sqlContext: SQLContext,
    metadataPath: String,
    userSpecifiedSchema: Option[StructType],
    credentialProvider: AWSCredentialsProvider,
    sourceOptions: Map[String, String])
  extends Source with Logging {

  import KinesisSourceOffset._

  private val _sparkSession = sqlContext.sparkSession
  private val _sc = sqlContext.sparkContext
  private val kinesisOptions = new KinesisOptions(sourceOptions)
  private val kinesisClient = {
    val cli = new AmazonKinesisClient(credentialProvider)
    cli.setEndpoint(kinesisOptions.endpointUrl)
    cli
  }
  private val serializableAWSCredentials = {
    val awsCredentials = credentialProvider.getCredentials
    SerializableAWSCredentials(
      awsCredentials.getAWSAccessKeyId,
      awsCredentials.getAWSSecretKey
    )
  }

  // Starts receiving Amazon Kinesis streams
  private val _ssc = new StreamingContext(_sc, Seconds(1))

  private val kinesisStreams = kinesisOptions.streamNames.flatMap { stream =>
    // Creates 1 Kinesis Receiver/input DStream for each shard
    val numStreams = kinesisClient.describeStream(stream).getStreamDescription.getShards.size
    logInfo(s"Create $numStreams streams for $stream")
    (0 until numStreams).map { i =>
      new KinesisSourceDStream(
        _ssc,
        stream,
        kinesisOptions.endpointUrl,
        kinesisOptions.regionId,
        kinesisOptions.initialPositionInStream,
        kinesisOptions.checkpointName,
        Milliseconds(2000),
        StorageLevel.MEMORY_AND_DISK,
        collectKinesisStreamBlockData,
        Some(serializableAWSCredentials)
      )
    }
  }

  // Union all the input streams
  _ssc.union(kinesisStreams).foreachRDD(_ => {})
  _ssc.start()

  private val initialShardSeqNumbers = {
    val metadataLog =
      new HDFSMetadataLog[KinesisSourceOffset](_sparkSession, metadataPath)
    val seqNumbers = metadataLog.get(0).getOrElse {
        val offsets = KinesisSourceOffset(fetchAllShardEarliestSeqNumber())
        metadataLog.add(0, offsets)
        logInfo(s"Initial offsets: $offsets")
        offsets
      }.shardToSeqNum
    seqNumbers
  }

  /** A holder for stream blocks stored in workers */
  type StreamBlock = (BlockId, SequenceNumberRanges, Boolean)

  /**
   * Sequence number ranges added to the current block being generated.
   * Accessing and updating of this map is synchronized with `shardIdToLatestStoredSeqNum`
   * by lock `blockLock`.
   */
  private var streamBlocksInStores = mutable.ArrayBuffer[StreamBlock]()

  private var isKinesisStreamInitialized = false

  /**
   * Latest sequence number ranges that have been stored successfully.
   */
  private lazy val shardIdToLatestStoredSeqNum: mutable.Map[KinesisShard, String] = {
    val seqNumbers = mutable.Map[KinesisShard, String]()
    initialShardSeqNumbers.map { case (key, value) =>
      seqNumbers += key -> value
    }
    isKinesisStreamInitialized = true
    seqNumbers
  }

  private val blockLock = new ReentrantLock(false)

  private def synchronizeUpdateStreamBlock[T](func: => T): Option[T] = {
    var retValue: Option[T] = None
    blockLock.lock()
    try {
      retValue = Option(func)
    } catch {
      case _: Throwable =>
        reportDataLoss("May lose data because of an exception occurred"
          + "when updating stream blocks information")
    } finally {
      blockLock.unlock()
    }
    retValue
  }

  /**
   * Callback method called after a block has been generated.
   */
  private def collectKinesisStreamBlockData(
      blockIds: Array[BlockId], arrayOfseqNumberRanges: Array[SequenceNumberRanges],
      isBlockIdValid: Array[Boolean]): Unit = {
    if (blockIds.length == 0) return
    logInfo(s"Received kinesis stream blocked data: ${arrayOfseqNumberRanges.mkString(", ")}")
    synchronizeUpdateStreamBlock {
      val streamBlocks = Seq.tabulate(blockIds.length) { i =>
        val isValid = if (isBlockIdValid.length == 0) true else isBlockIdValid(i)
        (blockIds(i), arrayOfseqNumberRanges(i), isValid)
      }
      streamBlocks.map { case streamBlock =>
        streamBlocksInStores += streamBlock

        // Update the latest sequence numbers of registered shards
        streamBlock._2.ranges.map { case range =>
          val shard = KinesisShard(range.streamName, range.shardId)
          (shard, range.toSeqNumber)
        }.groupBy(_._1).mapValues { value =>
          value.map(_._2).max
        }.foreach { case (shard, latestSeqNumber) =>
          val curSeqNumber = shardIdToLatestStoredSeqNum.getOrDefault(shard, minimumSeqNumber)
          shardIdToLatestStoredSeqNum.put(shard, Seq(latestSeqNumber, curSeqNumber).max)
        }
      }
      logDebug(s"Pending stream blocks are: ${streamBlocksInStores.map(_._2).mkString(", ")}")
      logDebug("shardIdToLatestStoredSeqNum:"
        + shardIdToLatestStoredSeqNum.map { case (shard, latestSeqNumber) =>
          s"(${shard.streamName}, ${shard.shardId}, $latestSeqNumber)"
        }.mkString(", "))
    }
  }

  private lazy val _schema = userSpecifiedSchema.getOrElse {
    KinesisSource.inferSchema(sqlContext, sourceOptions)
  }

  override val schema: StructType = _schema

  private val dataFormat = KinesisDataFormatFactory.create(kinesisOptions.format)
  private val schemaWithoutTimestamp = StructType(schema.drop(1))

  private val invalidStreamBlockId = StreamBlockId(0, 0L)
  private val minimumSeqNumber = ""

  override def getOffset: Option[Offset] = {
    var offset: KinesisSourceOffset = null
    synchronizeUpdateStreamBlock {
      if (isKinesisStreamInitialized) {
        offset = KinesisSourceOffset(shardIdToLatestStoredSeqNum.clone.toMap)
      }
    }
    if (offset != null) {
      logDebug(s"getOffset: ${offset.shardToSeqNum.toSeq.sorted(kinesisOffsetOrdering)}")
      Some(offset)
    } else {
      None
    }
  }

  /**
   * Returns the data that is between the offsets
   * (`start.get.shardToSeqNum`, `end.shardToSeqNum`], i.e. `start.get.shardToSeqNum` is exclusive.
   */
  override def getBatch(start: Option[Offset], end: Offset): DataFrame = {
    logInfo(s"getBatch called with start = $start, end = $end")
    val untilShardSeqNumbers = KinesisSourceOffset.getShardSeqNumbers(end)
    val fromShardSeqNumbers = start match {
      case Some(prevBatchEndOffset) =>
        KinesisSourceOffset.getShardSeqNumbers(prevBatchEndOffset)
      case None =>
        initialShardSeqNumbers
    }

    // Find the new shards in streams, and get their earliest sequence numbers
    val newShards = untilShardSeqNumbers.keySet.diff(fromShardSeqNumbers.keySet)
    val newShardSeqNumbers = if (newShards.nonEmpty) {
      val newShardsMap = newShards.map(s => (s, fetchShardEarliestSeqNumber(s))).toMap
      if (newShardsMap.keySet != newShards) {
        // We cannot get from sequence numbers for some shards. It means they got deleted.
        val deletedShards = newShards.diff(newShardsMap.keySet)
        reportDataLoss(s"Cannot find earliest sequence numbers of ${deletedShards}. " +
          "Some data may have been missed")
      }
      logInfo(s"Shards added: $newShardsMap")
      newShardsMap
    } else {
      Map.empty[KinesisShard, String]
    }

    val deletedShards = fromShardSeqNumbers.keySet.diff(untilShardSeqNumbers.keySet)
    if (deletedShards.nonEmpty) {
      reportDataLoss(s"$deletedShards are gone. Some data may have been missed")
    }

    // Calculate sequence ranges
    val seqRanges = (newShardSeqNumbers ++ fromShardSeqNumbers).map { case (shard, from) =>
      SequenceNumberRange(
        shard.streamName,
        shard.shardId,
        from,
        // This unexpected behaviour is handled in a next range check
        untilShardSeqNumbers.getOrDefault(shard, ""))
    }.filter { range =>
      if (range.toSeqNumber < range.fromSeqNumber) {
        reportDataLoss(s"Shard ${range.shardId}'s offset in ${range.streamName} was changed " +
          s"from ${range.fromSeqNumber} to ${range.toSeqNumber}, some data may have been missed")
        false
      } else {
        true
      }
    }.toSeq

    def isSameShard(x: SequenceNumberRange, y: SequenceNumberRange): Boolean = {
      x.streamName == y.streamName & x.shardId == y.shardId
    }
    def includeRange(subject: SequenceNumberRange, target: SequenceNumberRange): Boolean = {
      require(isSameShard(subject, target), "`includeRange` only used in the same shard")
      target.fromSeqNumber < subject.fromSeqNumber && subject.toSeqNumber <= target.toSeqNumber
    }
    val targetBlocks = synchronizeUpdateStreamBlock {
      var candidiates = streamBlocksInStores
      val notUsed = mutable.ArrayBuffer[StreamBlock]()

      seqRanges.foreach { targetRange =>
        val (nextCandidiate, n) = candidiates.partition { case (_, r, _) =>
          r.ranges.forall { case range =>
            if (isSameShard(range, targetRange)) {
              includeRange(range, targetRange)
            } else {
              true
            }
          }
        }
        notUsed +: n
        candidiates = nextCandidiate
      }

      // TODO: This code below is moved from this block
      streamBlocksInStores = notUsed.filter { case (_, r, _) =>
        seqRanges.forall { case targetRange =>
          r.ranges.forall { case range =>
            val isSurvive = if (isSameShard(range, targetRange)) {
              targetRange.fromSeqNumber <= range.fromSeqNumber
            } else {
              true
            }
            if (!isSurvive) {
              reportDataLoss(s"Some data loss might occur: ${range}")
            }
            isSurvive
          }
        }
      }
      if (candidiates.size > 0) {
        candidiates.unzip3
      } else {
        null
      }
    }.getOrElse {
      Seq.tabulate(seqRanges.length) { i =>
        (invalidStreamBlockId, SequenceNumberRanges(seqRanges(i)), false)
      }.unzip3
    }

    // Create a RDD that reads from Amazon Kinesis and get inputs as binary data
    val baseRdd = new KinesisSourceBlockRDD(
      _sc,
      kinesisOptions.regionId,
      kinesisOptions.endpointUrl,
      _blockIds = targetBlocks._1.toArray,
      arrayOfseqNumberRanges = targetBlocks._2.toArray,
      isBlockIdValid = targetBlocks._3.toArray,
      retryTimeoutMs = kinesisOptions.retryTimeoutMs,
      awsCredentialsOption = Option(serializableAWSCredentials)
    )
    val serializableReadFunc = dataFormat.buildReader(
      _sparkSession, schemaWithoutTimestamp, sourceOptions)
    val rdd = baseRdd.mapPartitionsInternal { iter =>
      val dataOnMem = iter.toSeq
      val timestampRows = dataOnMem.map(_._1).map(InternalRow(_))
      val valueRows = serializableReadFunc(dataOnMem.map(_._2).toIterator)
      timestampRows.toIterator.zip(valueRows).map{ row =>
        new JoinedRow(row._1, row._2)
      }.toIterator.asInstanceOf[Iterator[InternalRow]]
    }
    val logicalRdd = LogicalRDD(schema.toAttributes, rdd)(_sparkSession)
    Dataset.ofRows(_sparkSession, logicalRdd)
  }

  // Fetches the earliest offsets for all the shards in given streams
  private def fetchAllShardEarliestSeqNumber(): Map[KinesisShard, String] = {
    val allShards = kinesisOptions.streamNames.map { streamName =>
      val shards = kinesisClient.describeStream(streamName).getStreamDescription().getShards()
      logDebug(s"Number of assigned to $streamName: ${shards.size}")
      (streamName, shards)
    }
    allShards.flatMap { case (streamName, shards) =>
      val shardSeqNumbers = shards.map { case shard =>
        (KinesisShard(streamName, shard.getShardId),
          shard.getSequenceNumberRange.getStartingSequenceNumber)
      }
      logDebug(s"Got sequence numbers for $streamName: $shardSeqNumbers")
      shardSeqNumbers
    }.toMap
  }

  // Fetches the earliest offsets for shards
  private def fetchShardEarliestSeqNumber(shard: KinesisShard): String = {
    val shards = kinesisClient.describeStream(shard.streamName).getStreamDescription().getShards()
    shards.find(_.getParentShardId == shard.shardId)
      .map(_.getSequenceNumberRange.getStartingSequenceNumber)
      .getOrElse {
          logWarning(s"Unknown shard detected: ${shard.shardId}")
          ""
        }
  }

  override def stop(): Unit = {
    _ssc.stop(stopSparkContext = false, stopGracefully = true)
  }

  /**
   * If `failOnDataLoss` is true, this method will throw an `IllegalStateException`.
   * Otherwise, just log a warning.
   */
  private def reportDataLoss(message: String): Unit = {
    if (kinesisOptions.failOnDataLoss) {
      throw new IllegalStateException(message +
        ". Set the source option 'failOnDataLoss' to 'false' if you want to ignore these checks.")
    } else {
      logWarning(message)
    }
  }
}

private[kinesis] object KinesisSource {

  def withTimestamp(schema: StructType): StructType = {
    StructType(StructField("timestamp", TimestampType, false) +: schema)
  }

  def inferSchema(
      sqlContext: SQLContext,
      sourceOptions: Map[String, String]): StructType = {
    val kinesisOptions = new KinesisOptions(sourceOptions)
    val dataFormat = KinesisDataFormatFactory.create(kinesisOptions.format)
    val baseRdd = new KinesisSnapshotRDD(
      sqlContext.sparkContext,
      kinesisOptions.regionId,
      kinesisOptions.endpointUrl,
      kinesisOptions.streamNames
    )
    if (baseRdd.isEmpty()) {
      throw new IllegalStateException("No stream data exists for inferring a schema")
    }
    val valueSchema = dataFormat.inferSchema(sqlContext.sparkSession, baseRdd, sourceOptions)
    withTimestamp(valueSchema)
  }
}