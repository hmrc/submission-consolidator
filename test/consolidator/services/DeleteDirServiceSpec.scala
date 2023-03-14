/*
 * Copyright 2023 HM Revenue & Customs
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

import java.nio.file.{ Files, Path, Paths }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.ExecutionContext.Implicits.global

class DeleteDirServiceSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with ScalaFutures {

  trait TestFixture {

    val filesDir: Path = {
      val path = Files.createDirectories(
        Paths.get(System.getProperty("java.io.tmpdir") + s"/DeleteDirActorSpec-${System.currentTimeMillis()}")
      )
      Files.createFile(Paths.get(s"$path/someFile.txt")).toFile
      path
    }

    val service = new DeleteDirService()
  }

  "DeleteDirRequest" should {

    "delete the directory with all its contents" in new TestFixture {
      val future = service.deleteDir(filesDir)

      whenReady(future) { result =>
        result shouldBe Right(())
        filesDir.toFile.exists() shouldBe false
      }
    }

    "return error when path is not a directory" in new TestFixture {
      val path: Path = filesDir.toFile.listFiles().head.toPath
      val future = service.deleteDir(path)

      whenReady(future) { result =>
        result.isLeft shouldBe true
        result.swap.getOrElse(new NoSuchElementException()).getMessage shouldBe s"$path is not a directory"
      }
    }
  }
}
