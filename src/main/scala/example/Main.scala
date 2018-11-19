package example

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import org.apache.poi.ss.usermodel.{Row, Sheet, WorkbookFactory}

import scala.collection.JavaConverters._
import scala.language.{higherKinds, implicitConversions}
import scala.util.{Either, Try}

trait Monoidal[F[_]] {
  // Named this way for Historical Reasons™
  def pure[A](x: A): F[A]

  def map[A, B](fa: F[A])(f: A => B): F[B]

  def product[A, B](fa: F[A], fb: F[B]): F[(A, B)]

  def map2[M[_] : Monoidal, A, B, C](fa: M[A], fb: M[B])(f: (A, B) => C): M[C] =
    Monoidal[M].map(Monoidal[M].product(fa, fb))(f.tupled)

  def map3[M[_] : Monoidal, A, B, C, D](fa: M[A], fb: M[B], fc: M[C])(
    f: (A, B, C) => D): M[D] =
    Monoidal[M].map(Monoidal[M].product(Monoidal[M].product(fa, fb), fc))({
      case ((a, b), c) => f(a, b, c)
    })
}

trait Monoidal4[F[_]] extends Monoidal[F] {
  def map4[M[_] : Monoidal, A, B, C, D, E](fa: M[A], fb: M[B], fc: M[C], fd: M[D])(
    f: (A, B, C, D) => E): M[E] =
    Monoidal[M].map(Monoidal[M].product(Monoidal[M].product(Monoidal[M].product(fa, fb), fc), fd))({
      case (((a, b), c), d) => f(a, b, c, d)
    })
}

object Monoidal {
  // Some convenient boilerplate to "summon" our semigroupal
  // again, ignore this if it doesn't make sense, allows Semigroupal[RowDecoder].map to work
  def apply[F[_]](implicit semi: Monoidal[F]): Monoidal[F] = semi
}

trait RowDecoder[A] {
  def decode(row: Row): Either[Throwable, A]
}

// Specialized to RowDecoder!
object RowDecoder {
  implicit val monoidalForRowDecoder: Monoidal[RowDecoder] = new Monoidal[RowDecoder] {
    // This always succeeds returning the given value!
    def pure[A](x: A): RowDecoder[A] =
      RowDecoder.from(Function.const(Right(x)))

    // This processes the output of the given decode with the given function
    def map[A, B](fa: RowDecoder[A])(f: A => B): RowDecoder[B] =
      RowDecoder.from(x => fa.decode(x).map(f))

    // Combine the output of two into one!
    def product[A, B](fa: RowDecoder[A],
      fb: RowDecoder[B]): RowDecoder[(A, B)] =
      RowDecoder.from(x =>
        fa.decode(x).flatMap(a => fb.decode(x).map(b => (a, b))))
  }

  // A way to easily make decoders without too much boilerplate
  def from[A](f: Row => Either[Throwable, A]): RowDecoder[A] =
    new RowDecoder[A] {
      def decode(row: Row) = f(row)
    }

  // Just some useful boilerplate for summoning a RowDecoder
  // Don't need to know this mechanic, it just allows RowDecoder[Int] to compile
  def apply[A](implicit rowDecoder: RowDecoder[A]): RowDecoder[A] = rowDecoder
}


trait Alternative[F[_]] {
  // Kind of like true, or 1
  def pure[A](x: A): F[A]

  // Kind of like boolean AND, or multiplication
  def product[A, B](fa: F[A], fb: F[B]): F[(A, B)]

  // Kind of like false, or 0
  def empty[A]: F[A]

  // Kind of like boolean XOR, or addition
  // Named this way for Reasons™
  def combineK[A](x: F[A], y: F[A]): F[A]
}


object Alternative {
  implicit class altOps[F[_] : Alternative, A](x: F[A]) {
    def <+>(y: F[A]): F[A] = implicitly[Alternative[F]].combineK(x, y)
  }
}

object RowDecodeWithDate {
  implicit val alternativeForRowDecoder = new Alternative[RowDecoder] {
    // Kind of like true, same as in our Monoidal above
    def pure[A](x: A): RowDecoder[A] =
      Monoidal[RowDecoder].pure(x)

    // Kind of like AND, same as in our Monoidal abaove
    def product[A, B](fa: RowDecoder[A], fb: RowDecoder[B]): RowDecoder[(A, B)] =
      Monoidal[RowDecoder].product(fa, fb)

    // Kind of like false but for decoders
    def empty[A]: RowDecoder[A] =
      new RowDecoder[A] {
        def decode(row: Row) = Left(new RuntimeException("no dice"))
      }

    // Kind of like XOR but for decoders
    def combineK[A](x: RowDecoder[A], y: RowDecoder[A]): RowDecoder[A] =
      RowDecoder.from(z =>
        x.decode(z) match {
          case Left(_) => y.decode(z)
          case Right(v) => Right(v)
        })
  }

  implicit val monoidalForRowDecoder: Monoidal4[RowDecoder] = new Monoidal4[RowDecoder] {

    import RowDecoder._

    override def pure[A](x: A): RowDecoder[A] =
      implicitly[Monoidal[RowDecoder]].pure(x)

    override def map[A, B](fa: RowDecoder[A])(f: A => B): RowDecoder[B] =
      implicitly[Monoidal[RowDecoder]].map(fa)(f)

    override def product[A, B](fa: RowDecoder[A], fb: RowDecoder[B]): RowDecoder[(A, B)] =
      implicitly[Monoidal[RowDecoder]].product(fa, fb)
  }
}

case class Loan(
  id: Int,
  balance: Double,
  loanState: String
)

case class LoanWithPaymentDate(
  id: Int,
  balance: Double,
  loanState: String,
  nextPaymentDate: LocalDate
)

object Loan {
  val decodeId: RowDecoder[Int] =
    RowDecoder.from(row => Try(row.getCell(0).getNumericCellValue.toInt).toEither)

  val decodeBalance: RowDecoder[Double] =
    RowDecoder.from(row => Try(row.getCell(1).getNumericCellValue).toEither)

  val decodeLoanState: RowDecoder[String] =
    RowDecoder.from(row => Try(row.getCell(2).getStringCellValue).toEither)

  import RowDecoder._

  // Boom! Decode each row into a Loan object so long as we are able to decode each cell.
  implicit val decodeLoan: RowDecoder[Loan] = implicitly[Monoidal[RowDecoder]].map3(
    decodeId,
    decodeBalance,
    decodeLoanState
  )(Loan.apply _)
}

object DateParserAlternative {

  import Alternative._
  import RowDecodeWithDate._

  def altSum[A](patterns: List[RowDecoder[A]]): RowDecoder[A] =
    patterns.foldLeft(implicitly[Alternative[RowDecoder]].empty[A])(_ <+> _)

  // One way to parse a date:
  val slashParser = DateTimeFormatter.ofPattern("yyyy/MM/dd")

  // Another way to parse a date:
  val dashParser = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  val withSlashes: RowDecoder[LocalDate] =
    RowDecoder.from(x => Try(LocalDate.parse(x.getCell(3).getStringCellValue, slashParser)).toEither)

  val withDashes: RowDecoder[LocalDate] =
    RowDecoder.from(x => Try(LocalDate.parse(x.getCell(3).getStringCellValue, dashParser)).toEither)

  // Four different patterns!
  val variableDateDecoder: RowDecoder[LocalDate] =
    altSum(
      List(
        withSlashes,
        withDashes,
        RowDecoder.from(x => Try(
          LocalDate.parse(x.getCell(4).getStringCellValue, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'hh:mm:ss"))).toEither),
        RowDecoder.from(x => Try(
          LocalDate.parse(x.getCell(4).getStringCellValue, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'hh"))).toEither)
      )
    )
}

object LoanWithDate {

  import DateParserAlternative._
  import Loan._
  import RowDecodeWithDate._

  // Putting it all together…
  implicit val loanDecoder: RowDecoder[LoanWithPaymentDate] =
    implicitly[Monoidal4[RowDecoder]].map4(
      decodeId,
      decodeBalance,
      decodeLoanState,
      variableDateDecoder
    )(LoanWithPaymentDate.apply)
}

object Main extends App {

  def getSheet(f: File): Either[Throwable, Sheet] =
    Try(WorkbookFactory.create(f).getSheetAt(0)).toEither

  def processSheet[A: RowDecoder](sheet: Sheet): Either[Throwable, Iterator[Either[Throwable, A]]] =
    Right(sheet
      .iterator()
      .asScala
      .map(RowDecoder.apply[A].decode)) // alternatively replace with implicitly[RowDecoder[A]]
  // and remove RowParser.apply at line 72

  object FindLoans {

    import Loan._

    def records(f: File): Either[Throwable, Iterator[Either[Throwable, Loan]]] =
      for {
        sheet <- getSheet(f)
        result <- processSheet(sheet)
      } yield result

    def apply: Either[Throwable, Iterator[Either[Throwable, Loan]]] = {
      records(new File(getClass.getResource("/Loans.xlsx").getFile))

    }
  }

  FindLoans.apply.foreach(iter => iter.foreach(println))

  object FindLoansWithDates {

    import LoanWithDate._

    def recordsWithDate(f: File): Either[Throwable, Iterator[Either[Throwable, LoanWithPaymentDate]]] =
      for {
        sheet <- getSheet(f)
        result <- processSheet(sheet)
      } yield result

    def apply: Either[Throwable, Iterator[Either[Throwable, LoanWithPaymentDate]]] = recordsWithDate(new File(getClass.getResource("/Loans.xlsx").getFile))
  }

  FindLoansWithDates.apply.foreach(iter => iter.foreach(println))
}
