/*
 * Copyright 2020 HM Revenue & Customs
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

package consolidator.dms

import java.io.File

import cats.effect.IO
import consolidator.dms.proxy.{ FileUploadFrontEndProxy, FileUploadProxy }
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }

@Singleton
class FileUploaderService @Inject()(
  fileUploadProxy: FileUploadProxy,
  fileUploadFrontEndProxy: FileUploadFrontEndProxy) {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def upload(file: File): IO[Unit] = {
    logger.info("Uploading file " + file)
    IO.pure(())
  }
}
