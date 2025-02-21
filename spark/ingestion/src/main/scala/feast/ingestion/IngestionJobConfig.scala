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

import feast.ingestion.Modes.Modes
import org.joda.time.DateTime

object Modes extends Enumeration {
  type Modes = Value
  val Offline, Online = Value
}

abstract class StoreConfig

case class RedisConfig(
    host: String,
    port: Int,
    password: String = "",
    ssl: Boolean = false,
    properties: RedisWriteProperties = RedisWriteProperties()
) extends StoreConfig
case class RedisWriteProperties(maxJitterSeconds: Int = 3600, pipelineSize: Int = 250)
case class BigTableConfig(projectId: String, instanceId: String) extends StoreConfig
case class CassandraConfig(
    connection: CassandraConnection,
    keyspace: String,
    properties: CassandraWriteProperties = CassandraWriteProperties(1024, 5)
) extends StoreConfig
case class CassandraConnection(host: String, port: Int)
case class CassandraWriteProperties(batchSize: Int, concurrentWrite: Int)

sealed trait MetricConfig

case class StatsDConfig(host: String, port: Int) extends MetricConfig

abstract class DataFormat
case object ParquetFormat                 extends DataFormat
case class ProtoFormat(classPath: String) extends DataFormat
case class AvroFormat(schemaJson: String) extends DataFormat

abstract class Source {
  def fieldMapping: Map[String, String]

  def eventTimestampColumn: String
  def createdTimestampColumn: Option[String]
  def datePartitionColumn: Option[String]
}

abstract class BatchSource extends Source

abstract class StreamingSource extends Source {
  def format: DataFormat
}

case class FileSource(
    path: String,
    override val fieldMapping: Map[String, String],
    override val eventTimestampColumn: String,
    override val createdTimestampColumn: Option[String] = None,
    override val datePartitionColumn: Option[String] = None
) extends BatchSource

case class BQMaterializationConfig(project: String, dataset: String)

case class BQSource(
    project: String,
    dataset: String,
    table: String,
    override val fieldMapping: Map[String, String],
    override val eventTimestampColumn: String,
    override val createdTimestampColumn: Option[String] = None,
    override val datePartitionColumn: Option[String] = None,
    materialization: Option[BQMaterializationConfig] = None
) extends BatchSource

case class KafkaSource(
    bootstrapServers: String,
    topic: String,
    override val format: DataFormat,
    override val fieldMapping: Map[String, String],
    override val eventTimestampColumn: String,
    override val createdTimestampColumn: Option[String] = None,
    override val datePartitionColumn: Option[String] = None
) extends StreamingSource

case class Sources(
    file: Option[FileSource] = None,
    bq: Option[BQSource] = None,
    kafka: Option[KafkaSource] = None
)

case class Field(name: String, `type`: feast.proto.types.ValueProto.ValueType.Enum)

case class FeatureTable(
    name: String,
    project: String,
    entities: Seq[Field],
    features: Seq[Field],
    maxAge: Option[Long] = None,
    labels: Map[String, String] = Map.empty
)

case class ValidationConfig(
    name: String,
    pickledCodePath: String,
    includeArchivePath: String
)

case class IngestionJobConfig(
    mode: Modes = Modes.Offline,
    featureTable: FeatureTable = null,
    source: Source = null,
    startTime: DateTime = DateTime.now(),
    endTime: DateTime = DateTime.now(),
    store: StoreConfig = RedisConfig("localhost", 6379, "", false),
    metrics: Option[MetricConfig] = None,
    deadLetterPath: Option[String] = None,
    stencilURL: Option[String] = None,
    streamingTriggeringSecs: Int = 0,
    validationConfig: Option[ValidationConfig] = None,
    doNotIngestInvalidRows: Boolean = false,
    checkpointPath: Option[String] = None
)
