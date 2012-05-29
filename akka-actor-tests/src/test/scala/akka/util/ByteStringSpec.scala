package akka.util

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.prop.Checkers
import org.scalacheck._
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop._
import org.scalacheck.Gen._

import java.nio.{ ByteBuffer, ShortBuffer, IntBuffer, FloatBuffer, DoubleBuffer }
import java.nio.{ ByteOrder }, ByteOrder.{ BIG_ENDIAN, LITTLE_ENDIAN }

class ByteStringSpec extends WordSpec with MustMatchers with Checkers {

  def genSimpleByteString(min: Int, max: Int) = for {
    n ← choose(min, max)
    b ← Gen.containerOfN[Array, Byte](n, arbitrary[Byte])
    from ← choose(0, b.length)
    until ← choose(from, b.length)
  } yield ByteString(b).slice(from, until)

  implicit val arbitraryByteString: Arbitrary[ByteString] = Arbitrary {
    Gen.sized { s ⇒
      for {
        chunks ← choose(0, s)
        bytes ← listOfN(chunks, genSimpleByteString(1, s / (chunks max 1)))
      } yield (ByteString.empty /: bytes)(_ ++ _)
    }
  }

  type ByteStringSlice = (ByteString, Int, Int)

  implicit val arbitraryByteStringSlice: Arbitrary[ByteStringSlice] = Arbitrary {
    for {
      xs ← arbitraryByteString.arbitrary
      from ← choose(0, xs.length)
      until ← choose(from, xs.length)
    } yield (xs, from, until)
  }

  type ArraySlice[A] = (Array[A], Int, Int)

  def arbSlice[A](arbArray: Arbitrary[Array[A]]): Arbitrary[ArraySlice[A]] = Arbitrary {
    for {
      xs ← arbArray.arbitrary
      from ← choose(0, xs.length)
      until ← choose(from, xs.length)
    } yield (xs, from, until)
  }

  val arbitraryByteArray: Arbitrary[Array[Byte]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Byte](n, arbitrary[Byte]) } }
  implicit val arbitraryByteArraySlice: Arbitrary[ArraySlice[Byte]] = arbSlice(arbitraryByteArray)
  val arbitraryShortArray: Arbitrary[Array[Short]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Short](n, arbitrary[Short]) } }
  implicit val arbitraryShortArraySlice: Arbitrary[ArraySlice[Short]] = arbSlice(arbitraryShortArray)
  val arbitraryIntArray: Arbitrary[Array[Int]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Int](n, arbitrary[Int]) } }
  implicit val arbitraryIntArraySlice: Arbitrary[ArraySlice[Int]] = arbSlice(arbitraryIntArray)
  val arbitraryLongArray: Arbitrary[Array[Long]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Long](n, arbitrary[Long]) } }
  implicit val arbitraryLongArraySlice: Arbitrary[ArraySlice[Long]] = arbSlice(arbitraryLongArray)
  val arbitraryFloatArray: Arbitrary[Array[Float]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Float](n, arbitrary[Float]) } }
  implicit val arbitraryFloatArraySlice: Arbitrary[ArraySlice[Float]] = arbSlice(arbitraryFloatArray)
  val arbitraryDoubleArray: Arbitrary[Array[Double]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Double](n, arbitrary[Double]) } }
  implicit val arbitraryDoubleArraySlice: Arbitrary[ArraySlice[Double]] = arbSlice(arbitraryDoubleArray)

  def likeVecIt(bs: ByteString)(body: BufferedIterator[Byte] ⇒ Any, strict: Boolean = true): Boolean = {
    val bsIterator = bs.iterator
    val vecIterator = Vector(bs: _*).iterator.buffered
    (body(bsIterator) == body(vecIterator)) &&
      (!strict || (bsIterator.toSeq == vecIterator.toSeq))
  }

  def likeVecIts(a: ByteString, b: ByteString)(body: (BufferedIterator[Byte], BufferedIterator[Byte]) ⇒ Any, strict: Boolean = true): Boolean = {
    val (bsAIt, bsBIt) = (a.iterator, b.iterator)
    val (vecAIt, vecBIt) = (Vector(a: _*).iterator.buffered, Vector(b: _*).iterator.buffered)
    (body(bsAIt, bsBIt) == body(vecAIt, vecBIt)) &&
      (!strict || (bsAIt.toSeq, bsBIt.toSeq) == (vecAIt.toSeq, vecBIt.toSeq))
  }

  def testShortDecoding(slice: ByteStringSlice, byteOrder: ByteOrder): Boolean = {
    val elemSize = 2
    val (bytes, from, until) = slice
    val (n, a, b) = (bytes.length / elemSize, from / elemSize, until / elemSize)
    val reference = Array.ofDim[Short](n)
    bytes.asByteBuffer.order(byteOrder).asShortBuffer.get(reference, 0, n)
    val input = bytes.iterator
    val decoded = Array.ofDim[Short](n)
    for (i ← 0 to a - 1) decoded(i) = input.getShort(byteOrder)
    input.getShorts(decoded, a, b - a)(byteOrder)
    for (i ← b to n - 1) decoded(i) = input.getShort(byteOrder)
    (decoded.toSeq == reference.toSeq) && (input.toSeq == bytes.drop(n * elemSize))
  }

  def testIntDecoding(slice: ByteStringSlice, byteOrder: ByteOrder): Boolean = {
    val elemSize = 4
    val (bytes, from, until) = slice
    val (n, a, b) = (bytes.length / elemSize, from / elemSize, until / elemSize)
    val reference = Array.ofDim[Int](n)
    bytes.asByteBuffer.order(byteOrder).asIntBuffer.get(reference, 0, n)
    val input = bytes.iterator
    val decoded = Array.ofDim[Int](n)
    for (i ← 0 to a - 1) decoded(i) = input.getInt(byteOrder)
    input.getInts(decoded, a, b - a)(byteOrder)
    for (i ← b to n - 1) decoded(i) = input.getInt(byteOrder)
    (decoded.toSeq == reference.toSeq) && (input.toSeq == bytes.drop(n * elemSize))
  }

  def testLongDecoding(slice: ByteStringSlice, byteOrder: ByteOrder): Boolean = {
    val elemSize = 8
    val (bytes, from, until) = slice
    val (n, a, b) = (bytes.length / elemSize, from / elemSize, until / elemSize)
    val reference = Array.ofDim[Long](n)
    bytes.asByteBuffer.order(byteOrder).asLongBuffer.get(reference, 0, n)
    val input = bytes.iterator
    val decoded = Array.ofDim[Long](n)
    for (i ← 0 to a - 1) decoded(i) = input.getLong(byteOrder)
    input.getLongs(decoded, a, b - a)(byteOrder)
    for (i ← b to n - 1) decoded(i) = input.getLong(byteOrder)
    (decoded.toSeq == reference.toSeq) && (input.toSeq == bytes.drop(n * elemSize))
  }

  def testShortEncoding(slice: ArraySlice[Short], byteOrder: ByteOrder): Boolean = {
    val elemSize = 2
    val (data, from, until) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asShortBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 to from - 1) builder.putShort(data(i))(byteOrder)
    builder.putShorts(data, from, until - from)(byteOrder)
    for (i ← until to data.length - 1) builder.putShort(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testIntEncoding(slice: ArraySlice[Int], byteOrder: ByteOrder): Boolean = {
    val elemSize = 4
    val (data, from, until) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asIntBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 to from - 1) builder.putInt(data(i))(byteOrder)
    builder.putInts(data, from, until - from)(byteOrder)
    for (i ← until to data.length - 1) builder.putInt(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testLongEncoding(slice: ArraySlice[Long], byteOrder: ByteOrder): Boolean = {
    val elemSize = 8
    val (data, from, until) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asLongBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 to from - 1) builder.putLong(data(i))(byteOrder)
    builder.putLongs(data, from, until - from)(byteOrder)
    for (i ← until to data.length - 1) builder.putLong(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  "A ByteString" must {
    "have correct size" when {
      "concatenating" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).size == a.size + b.size) }
      "dropping" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).drop(b.size).size == a.size) }
    }
    "be sequential" when {
      "taking" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).take(a.size) == a) }
      "dropping" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).drop(a.size) == b) }

      "recombining" in {
        check { (xs: ByteString, from: Int, until: Int) ⇒
          val (tmp, c) = xs.splitAt(until)
          val (a, b) = tmp.splitAt(from)
          (a ++ b ++ c) == xs
        }
      }
    }
  }

  "A ByteStringIterator" must {
    "behave like a buffered Vector Iterator" when {
      "concatenating" in { check { (a: ByteString, b: ByteString) ⇒ likeVecIts(a, b) { (a, b) ⇒ (a ++ b).toSeq } } }

      "calling head" in { check { a: ByteString ⇒ a.isEmpty || likeVecIt(a) { _.head } } }
      "calling next" in { check { a: ByteString ⇒ a.isEmpty || likeVecIt(a) { _.next() } } }
      "calling hasNext" in { check { a: ByteString ⇒ likeVecIt(a) { _.hasNext } } }
      "calling length" in { check { a: ByteString ⇒ likeVecIt(a) { _.length } } }
      "calling duplicate" in { check { a: ByteString ⇒ likeVecIt(a)({ _.duplicate match { case (a, b) ⇒ (a.toSeq, b.toSeq) } }, strict = false) } }
      "calling span" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a)({ _.span(_ == b) match { case (a, b) ⇒ (a.toSeq, b.toSeq) } }, strict = false) } }
      "calling takeWhile" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a)({ _.takeWhile(_ == b).toSeq }, strict = false) } }
      "calling dropWhile" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a) { _.dropWhile(_ == b).toSeq } } }
      "calling indexWhere" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a) { _.indexWhere(_ == b) } } }
      "calling indexOf" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a) { _.indexOf(b) } } }
      "calling toSeq" in { check { a: ByteString ⇒ likeVecIt(a) { _.toSeq } } }
      "calling foreach" in { check { a: ByteString ⇒ likeVecIt(a) { it ⇒ var acc = 0; it foreach { acc += _ }; acc } } }
      "calling foldLeft" in { check { a: ByteString ⇒ likeVecIt(a) { _.foldLeft(0) { _ + _ } } } }
      "calling toArray" in { check { a: ByteString ⇒ likeVecIt(a) { _.toArray.toSeq } } }

      "calling slice" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒ likeVecIt(xs)({
              _.slice(from, until).toSeq
            }, strict = false)
          }
        }
      }

      "calling copyToArray" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒ likeVecIt(xs)({ it ⇒
              val array = Array.ofDim[Byte](xs.length)
              it.slice(from, until).copyToArray(array, from, until)
              array.toSeq
            }, strict = false)
          }
        }
      }
    }

    "function as expected" when {
      "getting Bytes, using getByte and getBytes" in {
        // mixing getByte and getBytes here for more rigorous testing
        check { slice: ByteStringSlice ⇒
          val (bytes, from, until) = slice
          val input = bytes.iterator
          val output = Array.ofDim[Byte](bytes.length)
          for (i ← 0 to from - 1) output(i) = input.getByte
          input.getBytes(output, from, until - from)
          for (i ← until to bytes.length - 1) output(i) = input.getByte
          (output.toSeq == bytes) && (input.isEmpty)
        }
      }

      "getting Bytes, using the InputStream wrapper" in {
        // combining skip and both read methods here for more rigorous testing
        check { slice: ByteStringSlice ⇒
          val (bytes, from, until) = slice
          val a = (0 max from) min bytes.length
          val b = (a max until) min bytes.length
          val input = bytes.iterator
          val output = Array.ofDim[Byte](bytes.length)

          input.asInputStream.skip(a)

          val toRead = b - a
          var (nRead, eof) = (0, false)
          while ((nRead < toRead) && !eof) {
            val n = input.asInputStream.read(output, a + nRead, toRead - nRead)
            if (n == -1) eof = true
            else nRead += n
          }
          if (eof) throw new RuntimeException("Unexpected EOF")

          for (i ← b to bytes.length - 1) output(i) = input.asInputStream.read().toByte

          (output.toSeq.drop(a) == bytes.drop(a)) &&
            (input.asInputStream.read() == -1) &&
            ((output.length < 1) || (input.asInputStream.read(output, 0, 1) == -1))
        }
      }
    }

    "decode data correctly" when {
      "decoding Short in big-endian" in { check { slice: ByteStringSlice ⇒ testShortDecoding(slice, BIG_ENDIAN) } }
      "decoding Short in little-endian" in { check { slice: ByteStringSlice ⇒ testShortDecoding(slice, LITTLE_ENDIAN) } }
      "decoding Int in big-endian" in { check { slice: ByteStringSlice ⇒ testIntDecoding(slice, BIG_ENDIAN) } }
      "decoding Int in little-endian" in { check { slice: ByteStringSlice ⇒ testIntDecoding(slice, LITTLE_ENDIAN) } }
      "decoding Long in big-endian" in { check { slice: ByteStringSlice ⇒ testLongDecoding(slice, BIG_ENDIAN) } }
      "decoding Long in little-endian" in { check { slice: ByteStringSlice ⇒ testLongDecoding(slice, LITTLE_ENDIAN) } }
    }
  }

  "A ByteStringBuilder" must {
    "function as expected" when {
      "putting Bytes, using putByte and putBytes" in {
        // mixing putByte and putBytes here for more rigorous testing
        check { slice: ArraySlice[Byte] ⇒
          val (data, from, until) = slice
          val builder = ByteString.newBuilder
          for (i ← 0 to from - 1) builder.putByte(data(i))
          builder.putBytes(data, from, until - from)
          for (i ← until to data.length - 1) builder.putByte(data(i))
          data.toSeq == builder.result
        }
      }

      "putting Bytes, using the OutputStream wrapper" in {
        // mixing the write methods here for more rigorous testing
        check { slice: ArraySlice[Byte] ⇒
          val (data, from, until) = slice
          val builder = ByteString.newBuilder
          for (i ← 0 to from - 1) builder.asOutputStream.write(data(i).toInt)
          builder.asOutputStream.write(data, from, until - from)
          for (i ← until to data.length - 1) builder.asOutputStream.write(data(i).toInt)
          data.toSeq == builder.result
        }
      }
    }

    "encode data correctly" when {
      "encoding Short in big-endian" in { check { slice: ArraySlice[Short] ⇒ testShortEncoding(slice, BIG_ENDIAN) } }
      "encoding Short in little-endian" in { check { slice: ArraySlice[Short] ⇒ testShortEncoding(slice, LITTLE_ENDIAN) } }
      "encoding Int in big-endian" in { check { slice: ArraySlice[Int] ⇒ testIntEncoding(slice, BIG_ENDIAN) } }
      "encoding Int in little-endian" in { check { slice: ArraySlice[Int] ⇒ testIntEncoding(slice, LITTLE_ENDIAN) } }
      "encoding Long in big-endian" in { check { slice: ArraySlice[Long] ⇒ testLongEncoding(slice, BIG_ENDIAN) } }
      "encoding Long in little-endian" in { check { slice: ArraySlice[Long] ⇒ testLongEncoding(slice, LITTLE_ENDIAN) } }
    }
  }
}
