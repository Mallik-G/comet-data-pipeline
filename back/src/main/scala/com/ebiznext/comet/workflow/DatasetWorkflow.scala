package com.ebiznext.comet.workflow

import better.files._
import com.ebiznext.comet.config.DatasetArea
import com.ebiznext.comet.job.{AutoBusinessJob, DsvJob, JsonJob}
import com.ebiznext.comet.schema.handlers.{LaunchHandler, SchemaHandler, StorageHandler}
import com.ebiznext.comet.schema.model.SchemaModel
import com.ebiznext.comet.schema.model.SchemaModel.{Domain, Metadata}
import com.ebiznext.comet.schema.model.SchemaModel.Format.{DSV, JSON}
import com.typesafe.scalalogging.StrictLogging
import org.apache.hadoop.fs.Path

class DatasetWorkflow(storageHandler: StorageHandler,
                      schemaHandler: SchemaHandler,
                      launchHandler: LaunchHandler) extends StrictLogging {

  /**
    *
    * @param domainName
    * @return resolved && unresolved schemas / path
    */
  private def pending(
                       domainName: String): (Iterable[(Option[SchemaModel.Schema], Path)],
    Iterable[(Option[SchemaModel.Schema], Path)]) = {
    val paths = storageHandler.list(DatasetArea.pending(domainName))
    val domain = schemaHandler.getDomain(domainName)
    val schemas: Iterable[(Option[SchemaModel.Schema], Path)] = for {
      schema <- paths.map { path =>
        (domain.get.findSchema(path.getName), path)
      }
    } yield schema
    schemas.partition(_._1.isDefined)
  }


  private def staging(domain: Domain, schema: SchemaModel.Schema, path: Path): Unit = {
    val metadata = domain.metadata.merge(schema.metadata.getOrElse(Metadata()))

    metadata.getFormat() match {
      case DSV =>
        new DsvJob(domain, schema, schemaHandler.types.types, metadata, path, storageHandler).run(null)
      case JSON =>
        new JsonJob(domain, schema, schemaHandler.types.types, metadata, path, storageHandler).run(null)
    }
    val targetPath = new Path(DatasetArea.staging(domain.name), path.getName)
    storageHandler.move(path, targetPath)
  }

  private def ingesting(domain: Domain, schema: SchemaModel.Schema, path: Path): Unit = {
    val targetPath = new Path(DatasetArea.ingesting(domain.name), path.getName)
    if (storageHandler.move(path, targetPath)) {
      staging(domain, schema, targetPath)
    }
  }

  def loadPending(): Unit = {
    val domains = schemaHandler.domains
    domains.foreach { domain =>
      val (resolved, unresolved) = pending(domain.name)
      unresolved.foreach {
        case (_, path) =>
          val targetPath = new Path(DatasetArea.unresolved(domain.name), path.getName)
          storageHandler.move(path, targetPath)
      }
      resolved.foreach {
        case (Some(schema), path) =>
          ingesting(domain, schema, path)
          //launchHandler.ingest(domain.name, schema.name, path)
        case (None, _) => throw new Exception("Should never happen")
      }
    }
  }

  def loadLanding(): Unit = {
    val domains = schemaHandler.domains
    domains.foreach { domain =>
      val inputDir = File(domain.directory)

      inputDir.list(_.extension == Some(".ack")).foreach { path =>
        val ackFile: File = path
        val fileStr = ackFile.pathAsString
        val prefixStr = fileStr.stripSuffix(".ack")
        val tgz = File(prefixStr + ".tgz")
        val gz = File(prefixStr + ".gz")
        val tmpDir = File(prefixStr)
        val zip = File(prefixStr + ".zip")
        ackFile.delete()
        if (gz.exists) {
          gz.unGzipTo(tmpDir)
          gz.delete()
        }
        else if (tgz.exists) {
          tgz.unGzipTo(tmpDir)
          tgz.delete()
        }
        else if (zip.exists) {
          zip.unzipTo(tmpDir)
          zip.delete()
        }
        else {
          logger.error(s"No archive found for file ${ackFile.pathAsString}")
        }
        if (tmpDir.exists) {
          val dest = DatasetArea.pending(domain.name)
          tmpDir.list.foreach { file =>
            val source = new Path(file.pathAsString)
            logger.info(s"Importing ${file.pathAsString}")
            storageHandler.moveFromLocal(source, dest)
          }
          tmpDir.delete()
        }
      }
    }
  }

  def ingest(domainName: String, schemaName: String, path: String): Unit = {
    val domains = schemaHandler.domains
    for {
      domain <- domains.find(_.name == domainName)
      schema <- domain.schemas.find(_.name == schemaName)
    } yield ingesting(domain, schema, new Path(path))
  }

  def businessJob(jobname: String): Unit = {
    val job = schemaHandler.business(jobname)
    job.tasks.foreach { task =>
      val action = new AutoBusinessJob(job.name, task.sql, task.domain, task.dataset, task.write)
      action.run()
    }
  }
}