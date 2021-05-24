/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package consolidator.services

import java.nio.file.Path

import javax.inject.{ Inject, Singleton }
import org.apache.commons.io.FileUtils.deleteDirectory
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@Singleton
class DeleteDirService @Inject()(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(classOf[DeleteDirService])

  def deleteDir(path: Path): Future[Either[Throwable, Unit]] = Future {
    logger.info(s"Deleting directory $path")
    Try(deleteDirectory(path.toFile)) match {
      case Success(_) => Right(())
      case Failure(exception) =>
        logger.error(s"Failed to delete dir $path", exception)
        Left(exception)
    }
  }
}
