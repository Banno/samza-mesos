/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package eu.inn.samza.mesos

import java.util.{Map => JMap}
import java.util.UUID

import org.apache.mesos.Protos._
import org.apache.samza.config.Config
import eu.inn.samza.mesos.MesosConfig.Config2Mesos
import org.apache.samza.config.TaskConfig.Config2Task
import org.apache.samza.container.TaskNamesToSystemStreamPartitions
import org.apache.samza.job.{CommandBuilder, ShellCommandBuilder}
import org.apache.samza.util.Logging

import scala.collection.JavaConversions._

class MesosTask(config: Config,
                state: SamzaSchedulerState,
                val samzaContainerId: Int) extends Logging {

  /** When the returned task's ID is accessed, it will be created with a new UUID. */
  def copyWithNewId: MesosTask = new MesosTask(config, state, samzaContainerId)

  lazy val getMesosTaskId: String = s"${config.getName.get}-samza-container-${samzaContainerId}-${UUID.randomUUID.toString}"
  lazy val getMesosTaskName: String = getMesosTaskId

  lazy val getSamzaContainerName: String = s"${config.getName.get}-container-${samzaContainerId}"

  lazy val getSamzaCommandBuilder: CommandBuilder = {
    val sspTaskNames: TaskNamesToSystemStreamPartitions = state.samzaContainerIdToSSPTaskNames.getOrElse(samzaContainerId, TaskNamesToSystemStreamPartitions())
    val cmdBuilderClassName = config.getCommandClass.getOrElse(classOf[ShellCommandBuilder].getName)
    Class.forName(cmdBuilderClassName).newInstance.asInstanceOf[CommandBuilder]
      .setConfig(config)
      .setName(getSamzaContainerName)
      .setTaskNameToSystemStreamPartitionsMapping(sspTaskNames.getJavaFriendlyType)
      .setTaskNameToChangeLogPartitionMapping(
        state.samzaTaskNameToChangeLogPartitionMapping.map(kv => kv._1 -> Integer.valueOf(kv._2))
      )
  }

  lazy val getBuiltMesosCommandInfoURI: CommandInfo.URI = {
    val packagePath = {
      config.getPackagePath.get
    }
    CommandInfo.URI.newBuilder()
      .setValue(packagePath)
      .setExtract(true)
      .build()
  }

  def getBuiltMesosEnvironment(envMap: JMap[String, String]): Environment = {
    val memory = config.getExecutorMaxMemoryMb
    val javaHeapOpts = ("JAVA_HEAP_OPTS" -> s"-Xms${memory}M -Xmx${memory}M")
    val mesosEnvironmentBuilder: Environment.Builder = Environment.newBuilder()
    (envMap + javaHeapOpts) foreach { case (name, value) => 
      mesosEnvironmentBuilder.addVariables(
        Environment.Variable.newBuilder()
          .setName(name)
          .setValue(value)
          .build()
      )
    }
    mesosEnvironmentBuilder.build()
  }

  lazy val getBuiltMesosTaskID: TaskID = {
    TaskID.newBuilder()
      .setValue(getMesosTaskId)
      .build()
  }

  lazy val getBuiltMesosCommandInfo: CommandInfo = {
    val samzaCommandBuilder = getSamzaCommandBuilder
    CommandInfo.newBuilder()
      // .addUris(getBuiltMesosCommandInfoURI)
      .setValue("/samza/" + samzaCommandBuilder.buildCommand()) //should be bin/run-container.sh for .tgz and /samza/bin/run-container.sh for Docker container
      .setEnvironment(getBuiltMesosEnvironment(samzaCommandBuilder.buildEnvironment()))
      .build()
  }

  lazy val getBuiltMesosContainerInfo: ContainerInfo = {
    val image = config.getDockerImage.get
    debug(s"Using Docker image $image")
    ContainerInfo.newBuilder()
      .setType(ContainerInfo.Type.DOCKER)
      .setDocker(ContainerInfo.DockerInfo.newBuilder().setImage(image).build())
      .build()
  }

  def getBuiltMesosTaskInfo(slaveId: SlaveID): TaskInfo = {
    TaskInfo.newBuilder()
      .setTaskId(getBuiltMesosTaskID)
      .setSlaveId(slaveId)
      .setName(getMesosTaskName)
      .setCommand(getBuiltMesosCommandInfo)
      //TODO support Samza container either in .tgz (using mesos.package.path) or Docker container (using mesos.docker.image)
      .setContainer(getBuiltMesosContainerInfo)
      .addResources(
        Resource.newBuilder
          .setName("cpus")
          .setType(Value.Type.SCALAR)
          .setScalar(
            Value.Scalar.newBuilder().setValue(
              config.getExecutorMaxCpuCores
            )
          ).build()
      )
      .addResources(
        Resource.newBuilder
          .setName("mem")
          .setType(Value.Type.SCALAR)
          .setScalar(
            Value.Scalar.newBuilder().setValue(
              config.getExecutorMaxMemoryMb
            )
          ).build()
      )
      .addResources(
        Resource.newBuilder
          .setName("disk")
          .setType(Value.Type.SCALAR)
          .setScalar(
            Value.Scalar.newBuilder().setValue(
              config.getExecutorMaxDiskMb
            )
          ).build()
      ).build()
  }
}

