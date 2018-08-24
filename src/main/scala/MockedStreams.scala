/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package com.madewithtea.mockedstreams

import java.lang
import java.util.{Properties, UUID}

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.state.ReadOnlyWindowStore
import org.apache.kafka.streams.test.ConsumerRecordFactory
import org.apache.kafka.streams.{StreamsBuilder, StreamsConfig, Topology, TopologyTestDriver => Driver}

import scala.collection.JavaConverters._

object MockedStreams {

  def apply() = Builder()

  case class Builder(topology: Option[() => Topology] = None,
                     configuration: Properties = new Properties(),
                     stateStores: Seq[String] = Seq(),
                     inputs: List[ConsumerRecord[Array[Byte], Array[Byte]]] = List.empty) {

    def config(configuration: Properties) = this.copy(configuration = configuration)

    def topology(func: StreamsBuilder => Unit) = {
      val buildTopology = () => {
        val builder = new StreamsBuilder()
        func(builder)
        builder.build()
      }
      this.copy(topology = Some(buildTopology))
    }

    def withTopology(t: () => Topology) = this.copy(topology = Some(t))

    def stores(stores: Seq[String]) = this.copy(stateStores = stores)

    private def inputPriv[K, V](topic: String, key: Serde[K], value: Serde[V],
                                records: Either[Seq[(K, V)], Seq[(K, V, Long)]]) = {
      val keySer = key.serializer
      val valSer = value.serializer
      val factory = new ConsumerRecordFactory[K, V](keySer, valSer)

      def foldWithoutTime(events: List[ConsumerRecord[Array[Byte], Array[Byte]]], kv: (K, V)) = {
        val newRecord = factory.create(topic, kv._1, kv._2)
        events :+ newRecord
      }

      def foldTime(events: List[ConsumerRecord[Array[Byte], Array[Byte]]], kvt: (K, V, Long)) = kvt match {
        case (k, v, t) =>
          val newRecord = factory.create(topic, k, v, t)
          events :+ newRecord
      }

      val updatedRecords = records match {
        case Left(withoutTime) => withoutTime.foldLeft(inputs)(foldWithoutTime)
        case Right(withTime) => withTime.foldLeft(inputs)(foldTime)
      }
      this.copy(inputs = updatedRecords)
    }

    def input[K, V](topic: String, key: Serde[K], value: Serde[V], newRecords: Seq[(K, V)]) = {
      inputPriv(topic, key, value, Left(newRecords))
    }

    def inputWithTime[K, V](topic: String,
                            key: Serde[K],
                            value: Serde[V],
                            newRecords: Seq[(K, V, Long)]): Builder = {
      inputPriv(topic, key, value, Right(newRecords))
    }

    def output[K, V](topic: String, key: Serde[K], value: Serde[V], size: Int) = {
      if (size <= 0) throw new ExpectedOutputIsEmpty
      withProcessedDriver { driver =>
        (0 until size).flatMap { _ =>
          Option(driver.readOutput(topic, key.deserializer, value.deserializer)) match {
            case Some(record) => Some((record.key, record.value))
            case None => None
          }
        }
      }
    }

    def outputTable[K, V](topic: String, key: Serde[K], value: Serde[V], size: Int) =
      output[K, V](topic, key, value, size).toMap

    def stateTable(name: String): Map[Nothing, Nothing] = withProcessedDriver { driver =>
      val records = driver.getKeyValueStore(name).all()
      val list = records.asScala.toList.map { record => (record.key, record.value) }
      records.close()
      list.toMap
    }

    def windowStateTable[K, V](name: String,
                               key: K,
                               timeFrom: Long = 0,
                               timeTo: Long = Long.MaxValue): Map[lang.Long, V] = withProcessedDriver { driver =>

      val store = driver.getStateStore(name).asInstanceOf[ReadOnlyWindowStore[K, V]]
      val records = store.fetch(key, timeFrom, timeTo)
      val list = records.asScala.toList.map { record => (record.key, record.value) }
      records.close()
      list.toMap
    }

    // state store is temporarily created in ProcessorTopologyTestDriver
    private def stream = {
      val props = new Properties
      props.put(StreamsConfig.APPLICATION_ID_CONFIG, s"mocked-${UUID.randomUUID().toString}")
      props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
      props.putAll(configuration)
      new Driver(topology.getOrElse(throw new NoTopologySpecified)(), props)
    }

    private def produce(driver: Driver): Unit =
      inputs.foreach(driver.pipeInput)

    private def withProcessedDriver[T](f: Driver => T): T = {
      if (inputs.isEmpty)
        throw new NoInputSpecified

      val driver = stream
      produce(driver)
      val result: T = f(driver)
      driver.close
      result
    }
  }

  class NoTopologySpecified extends Exception("No topology specified. Call topology() on builder.")

  class NoInputSpecified extends Exception("No input fixtures specified. Call input() method on builder.")

  class ExpectedOutputIsEmpty extends Exception("Output size needs to be greater than 0.")

}
