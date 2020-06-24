package io.chrisdavenport.mules.http4s.redis

import cats.implicits._

import io.chrisdavenport.mules._
import io.chrisdavenport.mules.http4s._
import io.chrisdavenport.mules.redis.RedisCache
import io.chrisdavenport.mules.http4s.codecs.cacheItemCodec


import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.codecs.Codecs
import dev.profunktor.redis4cats.codecs.splits.SplitEpi

import dev.profunktor.redis4cats.connection.RedisClient

import scodec.bits.ByteVector
import org.http4s.{Method, Uri}
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object RedisHttpCodec {

  private[redis] object Scodec {
    import scodec._
    import scodec.codecs._

    val method: Codec[Method] = cstring.exmapc(s => 
      Attempt.fromEither(Method.fromString(s).leftMap(p => Err.apply(p.details)))
    )(m => Attempt.successful(m.name))

    val uri : Codec[Uri] = variableSizeBytesLong(int64, string(StandardCharsets.UTF_8))
      .withToString(s"string64(${StandardCharsets.UTF_8.displayName()})")
      .exmapc(
      s => Attempt.fromEither(Uri.fromString(s).leftMap(p => Err.apply(p.details)))
    )(uri =>  Attempt.successful(uri.renderString))

    val tuple : Codec[(Method, Uri)] = method ~ uri
  }

  private val arrayBVSplit = SplitEpi[Array[Byte], ByteVector](ByteVector(_), _.toArray)

  private val byteVectorCodec = Codecs.derive(
    RedisCodec.Bytes,
    arrayBVSplit,
    arrayBVSplit
  )

  val apply: RedisCodec[(Method, Uri), CacheItem] = Codecs.derive(
    byteVectorCodec,
    SplitEpi[ByteVector, (Method, Uri)](
      bv => Scodec.tuple.decode(bv.toBitVector).fold(err => throw new Throwable(s"Failed To Decode - $err"), _.value),
      ci => Scodec.tuple.encode(ci).fold(err => throw new Throwable(s"Failed to Encode $err"), _.toByteVector)
    ),
    SplitEpi[ByteVector, CacheItem](
      bv => cacheItemCodec.decode(bv.toBitVector).fold(err => throw new Throwable(s"Failed To Decode - $err"), _.value),
      ci => cacheItemCodec.encode(ci).fold(err => throw new Throwable(s"Failed to Encode $err"), _.toByteVector)
    )
  )
  
}