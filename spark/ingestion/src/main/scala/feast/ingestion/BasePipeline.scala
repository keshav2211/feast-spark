/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2020 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.ingestion

import feast.ingestion.utils.TypeConversion
import feast.ingestion.validation.TypeCheck
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql.functions.{col, expr}
import org.apache.spark.sql.streaming.StreamingQuery
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Column, SparkSession}

object BasePipeline {

  def createSparkSession(jobConfig: IngestionJobConfig): SparkSession = {
    // workaround for issue with arrow & netty
    // see https://github.com/apache/arrow/tree/master/java#java-properties
    System.setProperty("io.netty.tryReflectionSetAccessible", "true")
    // suppress SubscriptionState logs
    Logger.getLogger("org.apache.kafka").setLevel(Level.WARN)

    val conf = new SparkConf()

    jobConfig.store match {
      case RedisConfig(host, port, password, ssl, properties) =>
        conf
          .set("spark.redis.host", host)
          .set("spark.redis.port", port.toString)
          .set("spark.redis.password", password)
          .set("spark.redis.ssl", ssl.toString)
          .set("spark.redis.properties.maxJitter", properties.maxJitterSeconds.toString)
          .set("spark.redis.properties.pipelineSize", properties.pipelineSize.toString)
      case BigTableConfig(projectId, instanceId) =>
        conf
          .set("spark.bigtable.projectId", projectId)
          .set("spark.bigtable.instanceId", instanceId)
      case CassandraConfig(connection, keyspace, properties) =>
        conf
          .set("spark.sql.extensions", "com.datastax.spark.connector.CassandraSparkExtensions")
          .set("spark.cassandra.connection.host", connection.host)
          .set("spark.cassandra.connection.port", connection.port.toString)
          .set("spark.cassandra.output.batch.size.bytes", properties.batchSize.toString)
          .set("spark.cassandra.output.concurrent.writes", properties.concurrentWrite.toString)
          .set(
            s"spark.sql.catalog.feast",
            "com.datastax.spark.connector.datasource.CassandraCatalog"
          )
          .set("feast.store.cassandra.keyspace", keyspace)
    }

    jobConfig.metrics match {
      case Some(c: StatsDConfig) =>
        conf
          .set(
            "spark.metrics.labels",
            s"feature_table=${jobConfig.featureTable.name},project=${jobConfig.featureTable.project}"
          )
          .set(
            "spark.metrics.conf.*.sink.statsd.class",
            "org.apache.spark.metrics.sink.StatsdSinkWithTags"
          )
          .set("spark.metrics.conf.*.sink.statsd.host", c.host)
          .set("spark.metrics.conf.*.sink.statsd.port", c.port.toString)
          .set("spark.metrics.conf.*.sink.statsd.period", "30")
          .set("spark.metrics.conf.*.sink.statsd.unit", "seconds")
          .set("spark.metrics.namespace", s"feast_${jobConfig.mode.toString.toLowerCase}")
          // until proto parser udf will be fixed, we have to use this
          .set("spark.sql.legacy.allowUntypedScalaUDF", "true")
      case None => ()
    }

    (jobConfig.metrics, jobConfig.mode) match {
      case (Some(_), Modes.Online) =>
        conf
          .set(
            "spark.metrics.conf.*.source.jvm.class",
            "org.apache.spark.metrics.source.StreamingMetricSource"
          )
      case (_, _) => ()
    }

    jobConfig.stencilURL match {
      case Some(url: String) =>
        conf
          .set("feast.ingestion.registry.proto.kind", "stencil")
          .set("feast.ingestion.registry.proto.url", url)
      case None => ()
    }

    SparkSession
      .builder()
      .config(conf)
      .getOrCreate()
  }

  /**
    * Build column projection using custom mapping with fallback to feature|entity names.
    */
  def inputProjection(
      source: Source,
      features: Seq[Field],
      entities: Seq[Field],
      inputSchema: StructType
  ): Array[Column] = {
    val typeByField =
      (entities ++ features).map(f => f.name -> f.`type`).toMap
    val columnDataTypes = inputSchema.fields
      .map(f => f.name -> f.dataType)
      .toMap

    val entitiesFeaturesColumns: Seq[(String, String)] = (entities ++ features)
      .map {
        case f if source.fieldMapping.contains(f.name) => (f.name, source.fieldMapping(f.name))
        case f                                         => (f.name, f.name)
      }

    val entitiesFeaturesProjection: Seq[Column] = entitiesFeaturesColumns
      .map {
        case (alias, source) if !columnDataTypes.contains(source) =>
          expr(source).alias(alias)
        case (alias, source)
            if TypeCheck.typesMatch(
              typeByField(alias),
              columnDataTypes(source)
            ) =>
          col(source).alias(alias)
        case (alias, source) =>
          col(source).cast(TypeConversion.feastTypeToSqlType(typeByField(alias))).alias(alias)
      }

    val timestampProjection = Seq(col(source.eventTimestampColumn))
    (entitiesFeaturesProjection ++ timestampProjection).toArray
  }

}

trait BasePipeline {

  def createPipeline(sparkSession: SparkSession, config: IngestionJobConfig): Option[StreamingQuery]
}
