package io.casperlabs.node.configuration
import magnolia._

import scala.language.experimental.macros
import org.scalacheck.{Arbitrary, Gen, GenHack}

//Borrowed from https://github.com/etaty/scalacheck-magnolia
//Needed because scalacheck-shapeless very slow
object MagnoliaArbitrary {
  type Typeclass[T] = Arbitrary[T]

  def combine[T](caseClass: CaseClass[Arbitrary, T]): Arbitrary[T] = Arbitrary[T] {
    GenHack.gen[T] { (params, seed) =>
      try {
        var acc = seed
        val a = caseClass.construct { p =>
          val r = p.typeclass.arbitrary.doPureApply(params, acc)
          acc = r.seed
          r.retrieve.get
        }
        GenHack.r(Some(a), acc)

      } catch {
        case _: StackOverflowError =>
          GenHack.r(None, seed.next)
      }
    }
  }

  def dispatch[T](sealedTrait: SealedTrait[Arbitrary, T])(): Arbitrary[T] = Arbitrary[T] {
    val gs: Seq[Gen[T]] = sealedTrait.subtypes.map(_.typeclass.arbitrary.asInstanceOf[Gen[T]])
    Gen.choose(0, gs.size - 1).flatMap(gs(_)).suchThat(x => gs.exists(GenHack.sieveCopy(_, x)))
  }

  implicit def gen[T]: Arbitrary[T] = macro Magnolia.gen[T]
}
