package c4s.process

import cats.syntax.all._
import cats.effect._
import cats.effect.syntax.all._
import scala.concurrent.ExecutionContext

import java.io.{InputStream, OutputStream}
import java.nio.file.Path

trait Process[F[_]] {
  def run(command: String, input: Option[fs2.Stream[F, Byte]], path: Option[Path]): F[ProcessResult[F]]
}

object Process {
  import scala.sys.process.{Process => ScalaProcess, _}
  final def apply[F[_]](implicit process: Process[F]): Process[F] = process

  final def run[F[_]: Process](command: String): F[ProcessResult[F]] = Process[F].run(command, None, None)

  final def run[F[_]: Process](command: String, input: fs2.Stream[F, Byte]): F[ProcessResult[F]] =
    Process[F].run(command, Some(input), None)

  final def runInPath[F[_]: Process](command: String, path: Path): F[ProcessResult[F]] =
    Process[F].run(command, None, path.some)

  final def impl[F[_]: Async: Extract](
                                             ec: ExecutionContext
  ): Process[F] = new ProcessImpl[F](ec)

  private[this] final class ProcessImpl[F[_]: Async: Extract](
      ec: ExecutionContext
  ) extends Process[F] {
    import java.util.concurrent.atomic.AtomicReference
    val atomicReference = Sync[F].delay(new AtomicReference[fs2.Stream[F, Byte]])

    override def run(command: String, input: Option[fs2.Stream[F, Byte]], path: Option[Path]): F[ProcessResult[F]] =
      for {
        outputRef <- atomicReference
        errorRef <- atomicReference
        fout = toOutputStream(input)
        exitValue <- Async[F].delay {
          val p = new ProcessIO(
            fout andThen (Extract[F].extract),
            redirectInputStream(outputRef, _),
            redirectInputStream(errorRef, _)
          )
          path.fold(
            ScalaProcess(command).run(p)
          )(path => ScalaProcess(command, path.toFile).run(p))
        }.bracket(p => Async[F].delay(p.exitValue()).evalOn(ec))(p => Async[F].delay(p.destroy()))
        output <- Async[F].delay(outputRef.get())
        error <- Async[F].delay(errorRef.get())
      } yield ProcessResult(ExitCode(exitValue), output, error)

    private[this] def toOutputStream(opt: Option[fs2.Stream[F, Byte]]): OutputStream => F[Unit] =
      out =>
        opt.fold(Async[F].unit) { stream =>
          Resource
            .fromAutoCloseable(Async[F].delay(out))
            .evalOn(ec)
            .use { outStream =>
              stream
                .through(fs2.io.writeOutputStream[F](Async[F].delay(outStream), false))
                .compile
                .drain
            }
        }

    private[this] def redirectInputStream(
        ref: AtomicReference[fs2.Stream[F, Byte]],
        is: InputStream
    ): Unit =
      try {
        val queue = scala.collection.mutable.Queue.empty[Byte]
        var n = is.read()
        while (n != -1) {
          queue.enqueue(n.toByte)
          n = is.read()
        }
        ref.set(fs2.Stream.fromIterator[F](queue.iterator, 1024))
      } finally is.close()
  }
}
