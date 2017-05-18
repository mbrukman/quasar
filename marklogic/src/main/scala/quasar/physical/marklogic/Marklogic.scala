/*
 * Copyright 2014–2017 SlamData Inc.
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

package quasar
package physical.marklogic

import slamdata.Predef._
import quasar.common._
import quasar.connector._
import quasar.contrib.matryoshka._
import quasar.contrib.pathy._
import quasar.contrib.scalaz._, eitherT._
import quasar.fp.numeric._
import quasar.fs._
import quasar.fs.mount._
import quasar.qscript._

import matryoshka._
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import scala.Predef.implicitly

final class Marklogic(val config: (Positive, Positive)) extends BackendModule {
  type QS[T[_[_]]] = fs.queryfile.MLQScriptCP[T]

  implicit def qScriptToQScriptTotal[T[_[_]]]: Injectable.Aux[QSM[T, ?], QScriptTotal[T, ?]] =
    ::\::[QScriptCore[T, ?]](::\::[ThetaJoin[T, ?]](::/::[T, Const[ShiftedRead[ADir], ?], Const[Read[AFile], ?]]))

  // TODO
  type Repr = xquery.MainModule
  type M[A] = FileSystemErrT[PhaseResultT[fs.MLFSQ, ?], A]

  def FunctorQSM[T[_[_]]] = Functor[QSM[T, ?]]
  def DelayRenderTreeQSM[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = implicitly[Delay[RenderTree, QSM[T, ?]]]
  def ExtractPathQSM[T[_[_]]: RecursiveT] = ExtractPath[QSM[T, ?], APath]
  def QSCoreInject[T[_[_]]] = implicitly[QScriptCore[T, ?] :<: QSM[T, ?]]
  def MonadM = Monad[M]
  def UnirewriteT[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = implicitly[Unirewrite[T, QS[T]]]
  def UnicoalesceCap[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = Unicoalesce.Capture[T, QS[T]]

  type Config = Unit

  def parseConfig[S[_]](uri: ConnectionUri)(
    implicit
      S0: Task :<: S,
      S1: PhysErr :<: S): EitherT[Free[S, ?], ErrorMessages, Config] = ???

  def compile[S[_]](cfg: Config)(
    implicit
      S0: Task :<: S,
      S1: PhysErr :<: S): FileSystemDef.DefErrT[Free[S, ?], (M ~> Free[S, ?], Free[S, Unit])] = ???

  val Type = FileSystemType("marklogic")

  def plan[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT](
      cp: T[QSM[T, ?]]): Backend[Repr] = ???

  object QueryFileModule extends QueryFileModule {
    import QueryFile._

    def executePlan(repr: Repr, out: AFile): Backend[AFile] = ???
    def evaluatePlan(repr: Repr): Backend[ResultHandle] = ???
    def more(h: ResultHandle): Backend[Vector[Data]] = ???
    def close(h: ResultHandle): Configured[Unit] = ???
    def explain(repr: Repr): Backend[String] = ???
    def listContents(dir: ADir): Backend[Set[PathSegment]] = ???
    def fileExists(file: AFile): Configured[Boolean] = ???
  }

  object ReadFileModule extends ReadFileModule {
    import ReadFile._

    def open(file: AFile, offset: Natural, limit: Option[Positive]): Backend[ReadHandle] = ???
    def read(h: ReadHandle): Backend[Vector[Data]] = ???
    def close(h: ReadHandle): Configured[Unit] = ???
  }

  object WriteFileModule extends WriteFileModule {
    import WriteFile._

    def open(file: AFile): Backend[WriteHandle] = ???
    def write(h: WriteHandle, chunk: Vector[Data]): Configured[Vector[FileSystemError]] = ???
    def close(h: WriteHandle): Configured[Unit] = ???
  }

  object ManageFileModule extends ManageFileModule {
    import ManageFile._

    def move(scenario: MoveScenario, semantics: MoveSemantics): Backend[Unit] = ???
    def delete(path: APath): Backend[Unit] = ???
    def tempFile(near: APath): Backend[AFile] = ???
  }
}
