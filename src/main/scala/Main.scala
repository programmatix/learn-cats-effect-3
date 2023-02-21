import cats.effect._
import cats.implicits._
import scala.concurrent.duration._
import cats.Monad
import cats.effect.std.Console
import cats.syntax.all._
import cats.effect.IO
import java.io.File
import java.io._

object Main {
  // Supports ScalaJS.
  // Describes itself as a library rather than a framework.  Amenable to being plugged into an existing app.
  // Claims to provide nice async stacktraces.

  // Fibers.
  // Like ZIO and (to a degree) Akka, supports fiber-based work stealing runtime.
  // Like ZIO, extremely lightweight.  Can create 10s of millions of fibers.
  // Extremely cheap to create and destroy also.
  // Allows creating a fiber per server request, which is a nice programming model.

  // Each step of a fiber is called an effect.

  // Can combine effects in various ways:
  IO.println("Hello") flatMap { _ =>
    IO.println("World")
  }

  for {
    _ <- IO.println("Hello")
    _ <- IO.println("World")
  } yield ()

  IO.println("Hello") >> IO.println("World")

  // cats-effect-cps allows an async/await type syntax:
  //  import cats.effect.cps._
  //  async[IO] {
  //    IO.println("Hello").await
  //    IO.println("World").await
  //  }

  // Fibers can be handled manually with start and join, but usually use higher-level abtractions.

  // Each app has a main fiber, usually defined:
  object Hi extends IOApp.Simple {
    val run = IO.println("Hello") >> IO.println("World")
  }

  // When one fiber starts another, it becomes the parent to that fiber's child.

  // Cancellation.
  // Like ZIO (and unlike Akka, Netty and Vert.x), supports async cancellation/interruption.
  // Generally cancellation will happen on either timeout or concurrent errors.

  lazy val loop: IO[Unit] = IO.println("Hello, World!") >> loop

  loop.timeout(5.seconds)

  // timoeut will call cancel on the fiber.

  // Cancellation is a request.  One fiber is requesting the other fiber to end.
  // That cancel call blocks until the other fiber actually cancels.
  // All finalizers get run.
  // IO.uncancelable can be used to mark an atomic block of effects that won't be interrupted.


  // Concurrency.
  def createFakeTask: IO[Unit] = ???

  val task1: IO[Unit] = createFakeTask
  val task2: IO[Unit] = createFakeTask

  (task1, task2).parTupled

  // Cats Effect never assumes effects can be executed in parallel.  Need to explicitly say with e.g. parTupled.
  // Various concurrency tools: parTupled, parMapN, parTraverse, background, Supervisor, Dispatcher..

  // FS2 builds on Cats Effect to add... more stuff?


  // Shared concurrent state.
  // Ref and Deferred.
  // Queue and Semaphore built on those.

  // The runtime will usually use N Threads, where N="number of physical threads provided by hardware". (Cores?)
  // Since "concurrent" fibers can get scheduled on the same Thread, they may not actually execute in
  // parallel.

  // Fibers are made to yield.  Any call to an async effect will automatically yield.  And the runtime
  // will automatically yield a fiber that does a long bunch of sequential effects.


  // Effects.
  // Just some action that happens.

  val printer: IO[Unit] = IO.println("Hello, World")
  val printAndRead: IO[String] = IO.print("Enter your name: ") >> IO.readLine

  // These are both effects.  They describe the action(s) that will happen when the are evaluated.


  // An effectful function is one that returns an effect.

  def foo(str: String): IO[String] = ???

  // So like ZIO, a core part is presenting side-effectful stuff functionally.  It's going to get
  // executed later - it's the description of an action, not the action itself.
  // (And a key difference from a Future, which will start executing immediately).

  // Effects != side-effect.  An effect is a description of some action(s) - which may happen to
  // contain side-effects.


  // Effects don't have to be IO.  Here's an advanced example:

  def example[F[_] : Monad : Console](str: String): F[String] = {
    val printer: F[Unit] = Console[F].println(str)
    (printer >> printer).as(str)
  }

  // The effect type is F.  So could have everything be IO[String] - or could be something else.


  // Side-effects.
  // Always need to be wrapped inside special ctors.

  // Synchronous (returns or throws)
  // * IO(...) or IO.delay(...)
  // * IO.blocking(...)
  // * IO.interruptible(...)
  // * IO.interruptibleMany(...)
  // Asynchronous (invokes a callback)
  // * IO.async or IO.async_

  val wrapped: IO[Unit] = IO(System.out.println("Hello, World"))


  // End of concepts!  Now looking at some examples in the tutorials.

  // Aiming for:

  def copy(origin: File, destination: File): IO[Long] = ???


  // Resources allow RAII.

  def inputStream(f: File): Resource[IO, FileInputStream] =
    Resource.make {
      IO.blocking(new FileInputStream(f)) // build
    } { inStream =>
      IO.blocking(inStream.close()).handleErrorWith(_ => IO.unit) // release
    }

  // Note that during release have to handle any errors - here they're just ignored.
  // attempt.void does the swallow-errors behaviour too.
  // And if just swallowing errors then can use a handy shortcut:

  Resource.fromAutoCloseable(IO(new FileInputStream(f)))


  // Resources can be composed:

  def outputStream(f: File): Resource[IO, FileOutputStream] =
    Resource.make {
      IO.blocking(new FileOutputStream(f)) // build
    } { outStream =>
      IO.blocking(outStream.close()).handleErrorWith(_ => IO.unit) // release
    }

  def inputOutputStreams(in: File, out: File): Resource[IO, (InputStream, OutputStream)] =
    for {
      inStream <- inputStream(in)
      outStream <- outputStream(out)
    } yield (inStream, outStream)

  // E.g. what inputOutputStreams returns will, when executed, open both file streams,
  // and close them again when done.

  // Can use Resources like this:

  def copy(origin: File, destination: File): IO[Long] =
    inputOutputStreams(origin, destination).use { case (in, out) =>
      transfer(in, out)
    }

  // Now to do the actual work:

  def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] =
    for {
      // Can just use IO() too, but IO.blocking() preferred - helps fiber runtime out.
      amount <- IO.blocking(origin.read(buffer, 0, buffer.size))

      // If we read some bytes, write them to the output, then recurse.
      // `IO` is stack safe so we don't have to worry about stack overflow.
      count <- if (amount > -1) IO.blocking(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)

      // End of read stream reached (by java.io.InputStream contract), nothing to write.
      // Return the accumulated count written.
      else IO.pure(acc)

      // Returns the actual amount of bytes transmitted
    } yield count

  def transfer(origin: InputStream, destination: OutputStream): IO[Long] =
    transmit(origin, destination, new Array[Byte](1024 * 10), 0L)

  // Working through tutorial...

}
