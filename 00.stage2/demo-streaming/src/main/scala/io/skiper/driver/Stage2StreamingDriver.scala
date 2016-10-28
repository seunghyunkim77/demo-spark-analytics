package io.skiper.driver




import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar

import scala.collection.mutable
import org.apache.spark.SparkConf
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.scheduler.{StreamingListener, StreamingListenerBatchCompleted, StreamingListenerBatchStarted, StreamingListenerBatchSubmitted}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.elasticsearch.spark.rdd.EsSpark

import scala.util.{Failure, Success, Try}

object Stage2StreamingDriver {
  def main(args: Array[String]) {

    //[STEP 1] create spark streaming session
    // Create the context with a 1 second batch size
    val sparkConf = new SparkConf().setMaster("local[2]").setAppName("Stage2_Streaming")
    sparkConf.set("es.index.auto.create", "true");
    sparkConf.set("es.nodes", "localhost")
    val ssc = new StreamingContext(sparkConf, Seconds(2))
    addStreamListener(ssc)

    // [STEP 1]. Create Kafka Receiver and receive message from kafka broker
    val Array(zkQuorum, group, topics, numThreads) = Array("localhost:2181" ,"realtime-group1", "realtime", "2")
    ssc.checkpoint("checkpoint")
    val topicMap    = topics.split(",").map((_, numThreads.toInt)).toMap
    val numReceiver = 1

    // parallel receiver per partition
    val kafkaStreams = (1 to numReceiver).map{i =>
      KafkaUtils.createStream(ssc, zkQuorum, group, topicMap).map(_._2)
    }
    val lines = ssc.union(kafkaStreams)

    // [STEP 2]. parser message and save to Elasticsearch
    // original msg = ["event_id","customer_id","track_id","datetime","ismobile","listening_zip_code"]
    val columnList  = List("@timestamp", "customer_id","track_id","ismobile","listening_zip_code")
    val wordList    = lines.mapPartitions(iter => {
      iter.toList.map(s => {
        val listMap = new mutable.LinkedHashMap[String, Any]()
        val split   = s.split(",")
        listMap.put(columnList(0), getTimestamp()) //timestamp
        listMap.put(columnList(1), split(1)) //customer_id
        listMap.put(columnList(2), split(2)) //track_id
        listMap.put(columnList(3), split(4).toInt) //ismobile
        listMap.put(columnList(4), split(5).replace("\"", "")) //listening_zip_code

        println(s" map = ${listMap.toString()}")
        listMap
      }).iterator
    })

    // Write to ElasticSearch
    wordList.foreachRDD(rdd => {
      rdd.foreach(s => s.foreach(x => println(x.toString)))
      EsSpark.saveToEs(rdd, "ba_realtime2/stage2")
    })

    ssc.start()
    ssc.awaitTermination()
  }

  // get current time
  def getTimestamp(): Timestamp = {
    val format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    new Timestamp(Calendar.getInstance().getTime().getTime)
  }

  def addStreamListener(ssc: StreamingContext): Unit = {
    ssc.addStreamingListener(new StreamingListener() {
      override def onBatchSubmitted(batchSubmitted: StreamingListenerBatchSubmitted): Unit = {
        super.onBatchSubmitted(batchSubmitted)
        val batchTime = batchSubmitted.batchInfo.batchTime
        println("[batchSubmitted] " + batchTime.milliseconds)
      }

      override def onBatchStarted(batchStarted: StreamingListenerBatchStarted): Unit = {
        super.onBatchStarted(batchStarted)
      }

      override def onBatchCompleted(batchCompleted: StreamingListenerBatchCompleted): Unit = {
        super.onBatchCompleted(batchCompleted)
      }
    })
  }
}