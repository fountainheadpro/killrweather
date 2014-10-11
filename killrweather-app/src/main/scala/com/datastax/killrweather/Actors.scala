/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.killrweather

import org.joda.time.DateTime

import scala.concurrent.Future
import akka.pattern.{ pipe, ask }
import akka.actor.{Actor, ActorRef}
import org.apache.spark.SparkContext._
import org.apache.spark.streaming.StreamingContext
import kafka.server.KafkaConfig
import kafka.serializer.StringDecoder
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.kafka.KafkaUtils
import com.datastax.spark.connector.embedded.EmbeddedKafka
import com.datastax.killrweather.actor.{KafkaProducer, WeatherActor}

/** 1. Transforms annual weather .gz files to line data and publishes to a Kafka topic.
  *
  * This just batches one data file for the demo. But you could do something like this
  * to set up a monitor on an S3 bucket, so that when new files arrive, Spark streams
  * them in. New files are read as text files with 'textFileStream' (using key as LongWritable,
  * value as Text and input format as TextInputFormat)
  * {{{
  *   streamingContext.textFileStream(dir)
       .reduceByWindow(_ + _, Seconds(30), Seconds(1))
  * }}}
  */
class RawFeedActor(val kafkaConfig: KafkaConfig, ssc: StreamingContext, settings: WeatherSettings)
  extends KafkaProducer {

  import WeatherEvent._
  import settings._

  def receive : Actor.Receive = {
    case PublishFeed(years) => publish(years, sender)
  }

 /** 1. Transforms then publishes the raw data to a Kafka topic. */
  def publish(years: Range, requester: ActorRef): Unit =
    years foreach { n =>
      val location = s"$DataLocation$n.$DataFormat"

      /* The iterator will consume as much memory as the largest partition in this RDD */
      val lines = ssc.sparkContext.textFile(location).flatMap(_.split("\\n")).toLocalIterator
      batchSend(KafkaTopicRaw, KafkaGroupId, KafkaBatchSendSize, lines.toSeq)
    }
}

/** 2. Creates the Kafka stream which streams the raw data, transforms it, and saves it to Cassandra. */
class RawDataPublisher(kafka: EmbeddedKafka, ssc: StreamingContext, settings: WeatherSettings) extends WeatherActor {
  import com.datastax.spark.connector.streaming._

  import Weather.RawWeatherData
  import settings._

  /* Creates the stream which streams in the raw data. */
  val stream = KafkaUtils.createStream[String, String, StringDecoder, StringDecoder](
    ssc, kafka.kafkaParams, Map(KafkaTopicRaw -> 1), StorageLevel.MEMORY_ONLY)

  /* Defines the work to be done on the raw data topic stream.
     Converts to a [[com.datastax.killrweather.Weather.RawWeatherData]], saves to cassandra table. */
  stream.map { case (_,d) => d.split(",")}
    .map (RawWeatherData(_))
    .saveToCassandra(CassandraKeyspace, CassandraTableRaw)

  def receive : Actor.Receive = {
    case _ =>
  }
}

/** 3. Reads the raw data from Cassandra,
  * For a given weather station, in a given `year`, calculates temperature statistics.
  * For the moment we do just the month interval.
  */
class DailyTemperatureActor(year: Int, ssc: StreamingContext, settings: WeatherSettings) extends WeatherActor {
  import com.datastax.spark.connector.streaming._
  import scala.concurrent.duration._
  import WeatherEvent._
  import settings._

  /* Hourly background task that computes and persists daily temperature by weather station. */
  final val d = dayOfYearForYear(1, year)

  allDays(d).filter(_.year == d.year).filter(_ isBeforeNow).map {
    dt =>
      context.system.scheduler.scheduleOnce(DailyTemperatureTaskInterval) {
        self ! GetDailyTemperature("", dt.getDayOfYear, dt.getYear)
      }
      0.
1  }

  def receive : Actor.Receive = {
    case GetDailyTemperature(sid, doy) => compute(sid, doy, year, sender)
  }

  /* Reads from the table can contain new data from the kafka stream in step 2. */
  def compute(sid: String, doy: Int, year: Int, requester: ActorRef): Unit = Future {
    val dt = dayOfYearForYear(doy, year)
    val rdd = ssc.cassandraTable[Double](CassandraKeyspace, CassandraTableRaw)
      .select("temperature")
      .where("weather_station = ? AND year = ? AND month = ? AND day = ?",
        sid, dt.year, dt.monthOfYear, dt.dayOfYear)

    Temperature(sid, rdd)
  } pipeTo requester

}

/** 3. Reads the raw data from Cassandra,
 * For a given weather station, calculates high, low and average temperature.
 * For the moment we do just the month interval.
 */
class TemperatureActor(ssc: StreamingContext, settings: WeatherSettings) extends WeatherActor {
  import com.datastax.spark.connector.streaming._
  import scala.concurrent.duration._
  import WeatherEvent._
  import settings._

  /* Background task that computes and persists daily temperature by weather station. */
  val dailyTask = context.system.scheduler.schedule(initialDelay = 60.minutes, interval = 60.minutes) {
    /* def publish(years: Range, requester: ActorRef): Unit =
    years foreach { n =>
      val location = s"$DataLocation$n.$DataFormat"
  */
     self ! GetDailyTemperature("", 1, 2)
  }

  def receive : Actor.Receive = {
    case GetDailyTemperature(sid, doy, year) => compute(sid, doy, year, sender)
    case GetMonthlyTemperature(sid, doy, year) => compute(sid, doy, year, sender)
  }

  def compute(sid: String, doy: Int, year: Int, requester: ActorRef): Unit = Future {
     val dt = dayOfYearForYear(doy, year)
     val rdd = ssc.cassandraTable[Double](CassandraKeyspace, CassandraTableRaw)
                .select("temperature")
                .where("weather_station = ? AND year = ? AND month = ? AND day = ?",
                  sid, dt.year, dt.monthOfYear, dt.dayOfYear)


      Temperature(sid, rdd)
  } pipeTo requester

}

/** For a given weather station, calculates annual cumulative precip - or year to date. */
class PrecipitationActor(ssc: StreamingContext, settings: WeatherSettings) extends WeatherActor {
  import com.datastax.spark.connector.streaming._
  import WeatherEvent._
  import settings._

  def receive : Actor.Receive = {
    case WeatherEvent.GetPrecipitation(sid, year) => compute(sid, year, sender)
  }

  def compute(sid: String, year: Int, requester: ActorRef): Unit = Future {
    // in the background: do daily to cassandra, then
    val rdd = ssc.cassandraTable[Double](CassandraKeyspace, CassandraTableRaw)
              .select("oneHourPrecip")// everything on that day up to the hour you have (up to 23)
              .where("id = ? AND year = ?", sid, year)//.collectAsync()
    // map over the previous value and add the next
    // rained 25 mm, next 35mm, cumulative 55
    // 1 hour is delta from the previous
    // persist to table temperature_by_station
    Precipitation(sid, rdd.sum) // rdd



  } pipeTo requester

}

/** For a given weather station id, retrieves the full station data. */
class WeatherStationActor(ssc: StreamingContext, settings: WeatherSettings) extends WeatherActor {
  import com.datastax.spark.connector.streaming._
  import settings._

  def receive : Actor.Receive = {
    case WeatherEvent.GetWeatherStation(sid) => weatherStation(sid, sender)
  }

  /** Fill out the where clause and what needs to be passed in to request one.
    *
    * The reason we can not allow a `LIMIT 1` in the `where` function is that
    * the query is executed on each node, so the limit would applied in each
    * query invocation. You would probably receive about partitions_number * limit results.
    */
  def weatherStation(sid: String, requester: ActorRef): Unit =
    for {
      stations <- ssc.cassandraTable[Weather.WeatherStation](CassandraKeyspace, CassandraTableStations)
                  .where("id = ?", sid)
                  .collectAsync
      station <- stations.headOption
    } requester ! station

}
