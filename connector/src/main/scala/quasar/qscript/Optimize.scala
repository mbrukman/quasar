/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.qscript

import quasar.Predef._
import quasar.fp._
import quasar.fs.MonadFsErr
import quasar.qscript.MapFunc._
import quasar.qscript.MapFuncs._

import matryoshka._,
  Recursive.ops._,
  FunctorT.ops._,
  TraverseT.nonInheritedOps._
import matryoshka.patterns._
import scalaz.{:+: => _, Divide => _, _}, Scalaz._, Inject._, Leibniz._

class Optimize[T[_[_]]: Recursive: Corecursive: EqualT: ShowT] {

  // TODO: These optimizations should give rise to various property tests:
  //       • elideNopMap ⇒ no `Map(???, HoleF)`
  //       • normalize ⇒ a whole bunch, based on MapFuncs
  //       • elideNopJoin ⇒ no `ThetaJoin(???, HoleF, HoleF, LeftSide === RightSide, ???, ???)`
  //       • coalesceMaps ⇒ no `Map(Map(???, ???), ???)`
  //       • coalesceMapJoin ⇒ no `Map(ThetaJoin(???, …), ???)`

  def elideNopQC[F[_]: Functor, G[_]: Functor]
    (FtoG: F ~> G)
    (implicit QC: QScriptCore[T, ?] :<: F)
      : QScriptCore[T, T[G]] => G[T[G]] = {
    case Filter(Embed(src), BoolLit(true)) => src
    case Map(Embed(src), mf) if mf ≟ HoleF => src
    case x                                 => FtoG(QC.inj(x))
  }

  // FIXME: This really needs to ensure that the condition is that of an
  //        autojoin, otherwise it’ll elide things that are truly meaningful.
  def elideNopJoin[F[_]]
    (implicit
      TJ: ThetaJoin[T, ?] :<: F,
      QC: QScriptCore[T, ?] :<: F,
      FI: Injectable.Aux[F, QScriptTotal[T, ?]]) =
    λ[ThetaJoin[T, ?] ~> F] {
      case ThetaJoin(src, l, r, on, Inner, combine)
          if l ≟ Free.point(SrcHole) && r ≟ Free.point(SrcHole) && on ≟ EquiJF =>
        QC.inj(Map(src, combine.map(_ => SrcHole: Hole)))
      case x @ ThetaJoin(src, l, r, on, _, combine) if on ≟ BoolLit(true) =>
        (l.resume.leftMap(_.map(_.resume)), r.resume.leftMap(_.map(_.resume))) match {
          case (-\/(m1), -\/(m2)) => (FI.project(m1) >>= QC.prj, FI.project(m2) >>= QC.prj) match {
            // both sides only map over the same data
            case (Some(Map(\/-(SrcHole), mf1)), Some(Map(\/-(SrcHole), mf2))) =>
              QC.inj(Map(src, combine >>= {
                case LeftSide  => mf1
                case RightSide => mf2
              }))
            // neither side references the src
            case (Some(Map(-\/(src1), mf1)), Some(Map(-\/(src2), mf2)))
                if src1 ≟ UnrefedSrc && src2 ≟ UnrefedSrc =>
              QC.inj(Map(src, combine >>= {
                case LeftSide  => mf1
                case RightSide => mf2
              }))
            case (_, _) => TJ.inj(x)
          }
          // one side maps over the src while the other passes the src untouched
          case (-\/(m1), \/-(SrcHole)) => (FI.project(m1) >>= QC.prj) match {
            case Some(Map(\/-(SrcHole), mf1)) =>
              QC.inj(Map(src, combine >>= {
                case LeftSide  => mf1
                case RightSide => HoleF
              }))
            case _ => TJ.inj(x)
          }
          case (\/-(SrcHole), -\/(m2)) => (FI.project(m2) >>= QC.prj) match {
            case Some(Map(\/-(SrcHole), mf2)) =>
              QC.inj(Map(src, combine >>= {
                case LeftSide  => HoleF
                case RightSide => mf2
              }))
            case _ => TJ.inj(x)
          }
          case (_, _) => TJ.inj(x)
        }
      case x => TJ.inj(x)
    }

  def rebaseT[F[_]: Traverse](
    target: FreeQS[T])(
    src: T[F])(
    implicit FI: Injectable.Aux[F, QScriptTotal[T, ?]]):
      Option[T[F]] =
    freeCata[QScriptTotal[T, ?], T[QScriptTotal[T, ?]], T[QScriptTotal[T, ?]]](
      target.as(src.transAna(FI.inject)))(recover(_.embed)).transAnaM(FI.project)

  def rebaseTCo[F[_]: Traverse](
    target: FreeQS[T])(
    srcCo: T[CoEnv[Hole, F, ?]])(
    implicit FI: Injectable.Aux[F, QScriptTotal[T, ?]]):
      Option[T[CoEnv[Hole, F, ?]]] =
    // TODO: with the right instances & types everywhere, this should look like
    //       target.transAnaM(_.htraverse(FI.project)) ∘ (srcCo >> _)
    freeTransCataM[T, Option, QScriptTotal[T, ?], F, Hole, Hole](
      target)(
      coEnvHtraverse(_)(λ[QScriptTotal[T, ?] ~> (Option ∘ F)#λ](FI.project(_))))
      .map(targ => (targ >> srcCo.fromCoEnv).toCoEnv[T])

  private val UnrefedSrc =
    Inject[QScriptCore[T, ?], QScriptTotal[T, ?]].inj(Unreferenced[T, FreeQS[T]]())

  /** Similar to [[elideNopJoin]], this has a more constrained type, because we
    * need to integrate one branch of the join into the source of the resulting
    * map.
    */
  def elideOneSidedJoin[F[_], G[_]](
    rebase: FreeQS[T] => T[G] => Option[T[G]])(
    implicit TJ: ThetaJoin[T, ?] :<: F,
             QC: QScriptCore[T, ?] :<: F,
             FI: Injectable.Aux[F, QScriptTotal[T, ?]]):
      ThetaJoin[T, T[G]] => F[T[G]] = {
    case tj @ ThetaJoin(src, left, right, on, Inner, combine) if on ≟ BoolLit(true) =>
      (left.resume.leftMap(_.map(_.resume)), right.resume.leftMap(_.map(_.resume))) match {
        case (-\/(m1), -\/(m2)) => (FI.project(m1) >>= QC.prj, FI.project(m2) >>= QC.prj) match {
          case (Some(Map(-\/(src1), mf1)), _) if src1 ≟ UnrefedSrc =>
            rebase(tj.rBranch)(tj.src).fold(
              TJ.inj(tj))(
              tf => QC.inj(Map(tf, tj.combine >>= {
                case LeftSide  => mf1
                case RightSide => HoleF
              })))
          case (_, Some(Map(-\/(src2), mf2))) if src2 ≟ UnrefedSrc =>
            rebase(tj.lBranch)(tj.src).fold(
              TJ.inj(tj))(
              tf => QC.inj(Map(tf, tj.combine >>= {
                case LeftSide  => HoleF
                case RightSide => mf2
              })))
          case (_, _)=> TJ.inj(tj)
        }
        case (_, _) => TJ.inj(tj)
      }
    case tj => TJ.inj(tj)
  }

  def coalesceMapJoin[F[_], G[_]: Functor](
    GtoF: G ~> λ[α => Option[F[α]]])(
    implicit QC: QScriptCore[T, ?] :<: F, TJ: ThetaJoin[T, ?] :<: F):
      QScriptCore[T, T[G]] => F[T[G]] = {
    case x @ Map(Embed(src), mf) =>
      (GtoF(src) >>= TJ.prj).fold(
        QC.inj(x))(
        tj => TJ.inj(ThetaJoin.combine.modify(mf >> (_: JoinFunc[T]))(tj)))
    case x => QC.inj(x)
  }

  def swapMapCount[F[_], G[_]: Functor]
    (GtoF: G ~> (Option ∘ F)#λ)
    (implicit QC: QScriptCore[T, ?] :<: F)
      : QScriptCore[T, T[G]] => Option[QScriptCore[T, T[G]]] = {
    case Map(Embed(src), mf) =>
      GtoF(src) >>= QC.prj >>= {
        case Drop(innerSrc, lb, rb) =>
          Drop(innerSrc,
            Free.roll(Inject[QScriptCore[T, ?], QScriptTotal[T, ?]].inj(Map(lb, mf))),
            rb).some
        case Take(innerSrc, lb, rb) =>
          Take(innerSrc,
            Free.roll(Inject[QScriptCore[T, ?], QScriptTotal[T, ?]].inj(Map(lb, mf))),
            rb).some
        case _ => None
      }
    case _ => None
  }

  def compactQC: QScriptCore[T, ?] ~> (Option ∘ QScriptCore[T, ?])#λ =
    new (QScriptCore[T, ?] ~> (Option ∘ QScriptCore[T, ?])#λ) {
      def apply[A](fa: QScriptCore[T, A]) = fa match  {
        case LeftShift(src, struct, repair) =>
          rewriteShift(struct, repair) ∘
            (LeftShift(src, _: FreeMap[T], _: JoinFunc[T])).tupled

        case Reduce(src, bucket, reducers0, repair0) => {
          // `reducers`: the reduce funcs that are used
          // `indices`: the indices into `reducers0` that are used
          val (reducers, indices): (List[ReduceFunc[FreeMap[T]]], List[Int]) = {
            val used: Set[Int] = repair0.foldLeft(Set[Int]()) {
              case (acc, redIdx) => acc + redIdx.idx
            }
            reducers0.zipWithIndex.filter {
              case (_, idx) => used.contains(idx)
            }.unzip
          }

          // reset the indices in `repair0`
          val repair: Free[MapFunc[T, ?], Int] = repair0.map {
            case ReduceIndex(idx) => indices.indexOf(idx)
          }

          if (repair.map(ReduceIndex(_)) ≟ repair0 || repair.element(-1))
            None
          else
            Reduce(src, bucket, reducers, repair.map(ReduceIndex(_))).some
        }
        case _ => None
      }
    }

  // /** Chains multiple transformations together, each of which can fail to change
  //   * anything.
  //   */
  // def applyTransforms
  //   (transform: F[T[F]] => Option[F[T[F]]],
  //     transforms: (F[T[F]] => Option[F[T[F]]])*)
  //     : F[T[F]] => Option[F[T[F]]] =
  //   transforms.foldLeft(
  //     transform)(
  //     (prev, next) => x => prev.fold(next(x))(y => next(y).orElse(y.some)))

  // TODO: add reordering
  // - Filter can be moved ahead of Sort
  // - Take/Drop can have a normalized order _if_ their counts are constant
  //   (maybe in some additional cases)
  // - Take/Drop can be moved ahead of Map

  // The order of optimizations is roughly this:
  // - elide NOPs
  // - read conversion given to us by the filesystem
  // - convert any remaning projects to maps
  // - coalesce nodes
  // - normalize mapfunc
  def applyAll[F[_]: Traverse: Normalizable](
    implicit C:  Coalesce.Aux[T, F, F],
             QC: QScriptCore[T, ?] :<: F,
             TJ: ThetaJoin[T, ?] :<: F,
             FI: Injectable.Aux[F, QScriptTotal[T, ?]]):
      F[T[F]] => F[T[F]] =
    (Normalizable[F].normalize(_: F[T[F]])) ⋙
      quasar.fp.free.injectedNT[F](elideNopJoin[F]) ⋙
      liftFG(elideOneSidedJoin[F, F](rebaseT[F])) ⋙
      repeatedly(C.coalesce(idPrism)) ⋙
      liftFG(coalesceMapJoin[F, F](idPrism.get)) ⋙
      liftFF(orOriginal(swapMapCount[F, F](idPrism.get))) ⋙
      liftFF(repeatedly(compactQC(_: QScriptCore[T, T[F]]))) ⋙
      liftFG(elideNopQC[F, F](idPrism.reverseGet))

  def applyToFreeQS[F[_]: Traverse: Normalizable](
    implicit C:  Coalesce.Aux[T, F, F],
             QC: QScriptCore[T, ?] :<: F,
             TJ: ThetaJoin[T, ?] :<: F,
             FI: Injectable.Aux[F, QScriptTotal[T, ?]]):
      F[T[CoEnv[Hole, F, ?]]] => CoEnv[Hole, F, T[CoEnv[Hole, F, ?]]] =
    (Normalizable[F].normalize(_: F[T[CoEnv[Hole, F, ?]]])) ⋙
      quasar.fp.free.injectedNT[F](elideNopJoin[F]) ⋙
      liftFG(elideOneSidedJoin[F, CoEnv[Hole, F, ?]](rebaseTCo[F])) ⋙
      repeatedly(C.coalesce[CoEnv[Hole, F, ?]](coenvPrism)) ⋙
      liftFG(coalesceMapJoin[F, CoEnv[Hole, F, ?]](coenvPrism.get)) ⋙
      liftFF(orOriginal(swapMapCount[F, CoEnv[Hole, F, ?]](coenvPrism.get))) ⋙
      liftFF(repeatedly(compactQC(_: QScriptCore[T, T[CoEnv[Hole, F, ?]]]))) ⋙
      (fa => QC.prj(fa).fold(CoEnv(fa.right[Hole]))(elideNopQC[F, CoEnv[Hole, F, ?]](coenvPrism.reverseGet)))


  /** A backend-or-mount-specific `f` is provided, that allows us to rewrite
    * [[Root]] (and projections, etc.) into [[Read]], so then we can handle
    * exposing only “true” joins and converting intra-data joins to map
    * operations.
    *
    * `f` takes QScript representing a _potential_ path to a file, converts
    * [[Root]] and its children to path, with the operations post-file remaining.
    */
  def pathify[M[_]: MonadFsErr, IN[_]: Traverse, OUT[_]: Traverse]
    (g: DiscoverPath.ListContents[M])
    (implicit
      FS: DiscoverPath.Aux[T, IN, OUT],
      R:     Const[Read, ?] :<: OUT,
      QC: QScriptCore[T, ?] :<: OUT,
      FI: Injectable.Aux[OUT, QScriptTotal[T, ?]])
      : T[IN] => M[T[OUT]] =
    _.cataM(FS.discoverPath[M](g)) >>= DiscoverPath.unionAll[T, M, OUT](g)
}