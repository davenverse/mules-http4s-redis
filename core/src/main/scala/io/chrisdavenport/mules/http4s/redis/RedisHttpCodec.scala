package io.chrisdavenport.mules.http4s.redis

import cats.implicits._

import io.chrisdavenport.mules._
import io.chrisdavenport.mules.http4s._
import io.chrisdavenport.mules.redis.RedisCache
import io.chrisdavenport.mules.http4s.codecs.{cacheItemCodec, keyTupleCodec}


import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.codecs.Codecs
import dev.profunktor.redis4cats.codecs.splits.SplitEpi

import dev.profunktor.redis4cats.connection.RedisClient

import scodec.bits.ByteVector
import org.http4s.{Method, Uri}
import scodec.bits.BitVector

object RedisHttpCodec {

  private val arrayBVSplit = SplitEpi[Array[Byte], ByteVector](ByteVector(_), _.toArray)

  case class RedisScodecDecodingFailure(err: scodec.Err) extends Throwable(err.message)
  case class RedisScodecEncodingFailure(err: scodec.Err) extends Throwable(err.message)

  private def splitEpiCodec[A](codec: scodec.Codec[A]): SplitEpi[ByteVector, A] = SplitEpi[ByteVector, A](
    {bv =>
      // TODO: Remove NUL termination
      val bitVector = bv.indexOfSlice(BitVector.lowByte.bytes) match {
        case -1 => bv.toBitVector
        case i => bv.dropRight(1).toBitVector
      }
      codec.decode(bitVector).fold(err => throw RedisScodecDecodingFailure(err), _.value)
    },
    a => codec.encode(a).fold(err => throw RedisScodecEncodingFailure(err), _.toByteVector)
  )

  private val byteVectorCodec = Codecs.derive(
    RedisCodec.Bytes,
    arrayBVSplit,
    arrayBVSplit
  )

  val CacheKeyWithItem: RedisCodec[(Method, Uri), CacheItem] = Codecs.derive(
    byteVectorCodec,
    splitEpiCodec(keyTupleCodec),
    splitEpiCodec(cacheItemCodec)
  )
  
}