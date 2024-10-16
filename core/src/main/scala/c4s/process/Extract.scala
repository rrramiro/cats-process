package c4s.process

import cats.effect.{IO, unsafe}

trait Extract[F[_]] {
  def extract[A](fa: F[A]): A
}

object Extract {

  final def apply[F[_]](implicit extract: Extract[F]): Extract[F] = extract

  implicit def extractIO(implicit runtime: unsafe.IORuntime): Extract[IO] = new Extract[IO] {
    override def extract[A](fa: IO[A]): A = fa.unsafeRunSync()
  }
}
