package c4s.process

import java.nio.file._
import java.util.Comparator
import cats.effect._
import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all._
import munit.CatsEffectSuite

import java.io.File
import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.ExecutionContext

class ProcessSpec extends CatsEffectSuite {
  import c4s.process.syntax._

  test("It should run commands") {
    withProcess.use(implicit shell =>
      Process
        .run("ls -la")
        .map(x => assertEquals(x.exitCode, ExitCode.Success))
    )
  }

  test("It should strict run commands") {
    withProcess.use(implicit shell =>
      Process
        .run("ls -la")
        .strict
        .map(x => assertEquals(x._1, ExitCode.Success))
    )
  }

  test("It should strict run commands when it is failing") {
    withProcess.use(implicit shell =>
      Process
        .run("ls foo")
        .strict
        .attempt
        .map(x =>
          assert(
            x.swap.exists(_.getMessage().startsWith("Failed to execute command")),
            "It should failed to execute command"
          )
        )
    )
  }

  test("It should get the output as string and execute commands in different folder") {
    withProcess.use { implicit shell =>
      createTmpDirectory[IO].use { path =>
        Process.runInPath("mkdir foo", path) >>
          Process.runInPath("ls", path).string.map { output =>
            assert(output.contains("foo"), "foo")
          }
      }
    }
  }

  test("It should get lines and execute commands in different folder") {
    withProcess.use { implicit shell =>
      createTmpDirectory[IO].use { path =>
        Process.runInPath("mkdir foo", path) >>
          Process.runInPath("mkdir bar", path) >>
          Process.runInPath("ls", path).lines.map { output =>
            assertEquals(output, List("bar", "foo"))
          }
      }
    }
  }

  test("It should run command reading a stream from another command") {
    withProcess.use { implicit shell =>
      createTmpDirectory[IO].use { path =>
        Process.runInPath("touch test-file", path) >>
          Process.runInPath("ls", path) >>= (result =>
          Process
            .run("wc", result.output)
            .string
            .map(resultStream =>
              assertEquals(
                resultStream
                  .replaceAll(" ", "")
                  .trim
                  .toInt,
                1110
              )
            )
        )
      }
    }
  }

  lazy val withProcess: Resource[IO, Process[IO]] =
    fixedThreadPool[IO](3)
      .map(x => Process.impl[IO](x))

  def createTmpDirectory[F[_]: Sync]: Resource[F, Path] =
    Resource.make(Sync[F].delay(Files.createTempDirectory("tmp")))(path =>
      Sync[F].delay {
        Files.walk(path).sorted(Comparator.reverseOrder()).map[File](_.toFile).map[Boolean](_.delete())
      }.void
    )

  private def fixedThreadPool[F[_]](size: Int)(
    implicit sf: Sync[F]
  ): Resource[F, ExecutionContext] = {
    val alloc = sf.delay(Executors.newFixedThreadPool(size))
    val free  = (es: ExecutorService) => sf.delay(es.shutdown())
    Resource.make(alloc)(free).map(ExecutionContext.fromExecutor)
  }

}
