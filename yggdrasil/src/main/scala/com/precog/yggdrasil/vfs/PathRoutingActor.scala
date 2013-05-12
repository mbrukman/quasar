/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog
package yggdrasil
package vfs

import akka.actor.{Actor, ActorRef, Props}
import akka.dispatch.{Await, Future}
import akka.pattern.pipe
import akka.util.Duration


import blueeyes.bkka.FutureMonad
import blueeyes.json.serialization.Extractor.Thrown
import blueeyes.util.Clock

import com.precog.common.Path
import com.precog.common.ingest._
import com.precog.common.security._
import com.precog.common.jobs._
import com.precog.yggdrasil.actor._
import com.precog.util._

import com.weiglewilczek.slf4s.Logging

import java.io.{IOException, File}

import scalaz._
import scalaz.std.stream._
import scalaz.effect.IO
import scalaz.syntax.id._
import scalaz.syntax.traverse._

class PathRoutingActor (baseDir: File, resources: DefaultResourceBuilder, permissionsFinder: PermissionsFinder[Future], shutdownTimeout: Duration, jobManager: JobManager[Future], clock: Clock) extends Actor with Logging {
  private implicit val M: Monad[Future] = new FutureMonad(context.dispatcher)

  private[this] var pathActors = Map.empty[Path, ActorRef]

  override def postStop = {
    logger.info("Shutdown of path actors complete")
  }

  private[this] def targetActor(path: Path): IO[ActorRef] = {
    pathActors.get(path).map(IO(_)) getOrElse {
      val pathDir = VFSPathUtils.pathDir(baseDir, path)

      IOUtils.makeDirectory(pathDir) flatMap { _ =>
        logger.debug("Created new path dir for %s : %s".format(path, pathDir))
        VersionLog.open(pathDir) map {
          _ map { versionLog =>
            logger.debug("Creating new PathManagerActor for " + path)
            context.actorOf(Props(new PathManagerActor(path, VFSPathUtils.versionsSubdir(pathDir), versionLog, resources, permissionsFinder, shutdownTimeout, jobManager, clock))) unsafeTap { newActor =>
              pathActors += (path -> newActor)
            }
          } valueOr {
            case Thrown(t) =>
              throw t

            case error =>
              throw new Exception(error.message)
          }
        }
      }
    }
  }

  def receive = {
    case FindChildren(path, apiKey) =>
      VFSPathUtils.findChildren(baseDir, path, apiKey, permissionsFinder) pipeTo sender

    case op: PathOp =>
      val requestor = sender
      val io = targetActor(op.path) map { _.tell(op, requestor) } except {
        case t: Throwable =>
          logger.error("Error obtaining path actor for " + op.path, t)
          IO { requestor ! PathFailure(op.path, NonEmptyList(ResourceError.GeneralError("Error opening resources at " + op.path))) }
      } 
      
      io.unsafePerformIO

    case IngestData(messages) =>
      val requestor = sender
      val groupedAndPermissioned = messages.groupBy({ case (_, event) => event.path }).toStream traverse {
        case (path, pathMessages) =>
          targetActor(path) map { pathActor =>
            pathMessages.map(_._2.apiKey).distinct.toStream traverse { apiKey =>
              permissionsFinder.writePermissions(apiKey, path, clock.instant()) map { perms =>
                apiKey -> perms.toSet[Permission]
              }
            } map { allPerms =>
              val (totalArchives, totalEvents, totalStoreFiles) = pathMessages.foldLeft((0, 0, 0)) {
                case ((archived, events, storeFiles), (_, IngestMessage(_, _, _, data, _, _, _))) => (archived, events + data.size, storeFiles)
                case ((archived, events, storeFiles), (_, am: ArchiveMessage)) => (archived + 1, events, storeFiles)
                case ((archived, events, storeFiles), (_, sf: StoreFileMessage)) => (archived, events, storeFiles + 1)
              }
              logger.debug("Sending %d archives, %d storeFiles, and %d events to %s".format(totalArchives, totalStoreFiles, totalEvents, path))
              pathActor.tell(IngestBundle(pathMessages, allPerms.toMap), requestor)
            }
          } except {
            case t: Throwable => IO(logger.error("Failure during version log open on " + path, t))
          }
      }

      groupedAndPermissioned.unsafePerformIO
  }
}
