package io.chrisdavenport.mules.http4s.redis

import io.chrisdavenport.mules.redis.RedisCache
import cats.implicits._
import cats.effect._

import dev.profunktor.redis4cats.connection.{ RedisClient, RedisURI }
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log
import io.chrisdavenport.mules.Cache

import org.specs2.mutable.Specification
import cats.effect.specs2.CatsIO

import com.dimafeng.testcontainers.GenericContainer
import io.chrisdavenport.testcontainersspecs2.ForAllTestContainer

import java.time.Duration
import java.time.temporal.ChronoUnit.SECONDS

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy
import io.chrisdavenport.mules.TimeSpec
import scala.concurrent.duration._
import io.chrisdavenport.mules.http4s._
import org.http4s._

import cats.implicits._
import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.specs2.CatsIO
import org.http4s._
import org.http4s.implicits._
import org.http4s.headers._
import scala.concurrent.duration._
import io.chrisdavenport.cats.effect.time.JavaTime
import java.nio.charset.StandardCharsets
import scodec.bits.ByteVector

class RedisCacheSpec extends Specification with CatsIO 
  with  ForAllTestContainer {

    sequential

    lazy val container: GenericContainer = GenericContainer(
      "redis:5.0.0",
      exposedPorts = Seq(6379),
      env = Map(),
      waitStrategy = new LogMessageWaitStrategy()
        .withRegEx(".*Ready to accept connections*\\s")
        .withTimes(1)
        .withStartupTimeout(Duration.of(60, SECONDS))
    )

    lazy val ipAddress = container.containerIpAddress
    lazy val mappedPort = container.mappedPort(6379)

  implicit val T = IO.timer(scala.concurrent.ExecutionContext.global)
  implicit val CS = IO.contextShift(scala.concurrent.ExecutionContext.global)

  implicit val log = new Log[IO]{
    def error(msg: => String): cats.effect.IO[Unit] = IO.unit
    def info(msg: => String): cats.effect.IO[Unit] = IO.unit
  }

  def makeCache(defaultTimeout: Option[TimeSpec]) : Resource[IO, Cache[IO, (Method, Uri), CacheItem]] = for {
    uri <- Resource.liftF(RedisURI.make[IO](s"redis://localhost:$mappedPort"))
    client <- RedisClient[IO](uri)
    redis <- Redis[IO].fromClient(client,  RedisHttpCodec.CacheKeyWithItem)
    cache = RedisCache.fromCommands(redis, defaultTimeout)
  } yield cache

  "RedisCache" should {
    "never cache a response that should never be cached" in {
      makeCache(None).use{ cache => 
        for {
        ref <- Ref[IO].of(0)
        now <- JavaTime[IO].getInstant.map(HttpDate.fromInstant).rethrow
        httpApp = HttpRoutes.of[IO]{
          case _ => ref.modify(i => (i+1, i)).map{i => 
            Response[IO](Status.Ok)
              .withEntity(i.toString())
              .withHeaders(
                `Cache-Control`(
                  NonEmptyList.of(CacheDirective.`no-store`)
                ),
                Date(now),
                Expires(now),
                Header("Pragma", "no-cache")
              )
          }
        }.orNotFound
        cached = CacheMiddleware.httpApp(cache, CacheType.Private)
        newApp = cached(httpApp)
        request = Request[IO]()

        firstResp <- newApp.run(request)
        first <- firstResp.as[String]

        secondResp <- newApp.run(request)
        second <- secondResp.as[String]
        _ <- cache.delete((request.method, request.uri))
      } yield {
        (first must_=== "0") and 
          (second must_=== "1")
      }
    }
  }

    "cache a public cache response" in { makeCache(None).use{ cache => 
      for {
        ref <- Ref[IO].of(0)
        now <- JavaTime[IO].getInstant.map(HttpDate.fromInstant).rethrow
        lifetime = 24.hours
        httpApp = HttpRoutes.of[IO]{
          case _ => ref.modify(i => (i+1, i)).map{i => 
            Response[IO](Status.Ok)
              .withEntity(i.toString())
              .withHeaders(
                `Cache-Control`(
                  NonEmptyList.of(
                    CacheDirective.public,
                    CacheDirective.`max-age`(lifetime)
                  )
                ),
                Date(now),
                Expires(HttpDate.unsafeFromEpochSecond(now.epochSecond + lifetime.toSeconds)),
              )
          }
        }.orNotFound
        cached = CacheMiddleware.httpApp(cache, CacheType.Public)
        newApp = cached(httpApp)
        request = Request[IO]()

        firstResp <- newApp.run(request)
        first <- firstResp.as[String]

        secondResp <- newApp.run(request)
        second <- secondResp.as[String]//.as[Array[Byte]].map(ByteVector(_))
        _ <- cache.delete((request.method, request.uri))
      } yield {
        (first must_=== "0") and 
          (second must_=== "0")
      }
    }}

    "public cache does not cache private response" in { makeCache(None).use{ cache => 
      for {
        ref <- Ref[IO].of(0)
        now <- JavaTime[IO].getInstant.map(HttpDate.fromInstant).rethrow
        lifetime = 24.hours
        httpApp = HttpRoutes.of[IO]{
          case _ => ref.modify(i => (i+1, i)).map{i => 
            Response[IO](Status.Ok)
              .withEntity(i.toString())
              .withHeaders(
                `Cache-Control`(
                  NonEmptyList.of(
                    CacheDirective.`private`(List.empty),
                    CacheDirective.`max-age`(lifetime)
                  )
                ),
                Date(now),
                Expires(HttpDate.unsafeFromEpochSecond(now.epochSecond + lifetime.toSeconds)),
              )
          }
        }.orNotFound
        cached = CacheMiddleware.httpApp(cache, CacheType.Public)
        newApp = cached(httpApp)
        request = Request[IO]()

        firstResp <- newApp.run(request)
        first <- firstResp.as[String]

        secondResp <- newApp.run(request)
        second <- secondResp.as[String]
        _ <- cache.delete((request.method, request.uri))
      } yield {
        (first must_=== "0") and 
          (second must_=== "1")
      }
    }}


    "private cache does cache private response" in { makeCache(None).use{ cache => 
      for {
        ref <- Ref[IO].of(0)
        now <- JavaTime[IO].getInstant.map(HttpDate.fromInstant).rethrow
        lifetime = 24.hours
        httpApp = HttpRoutes.of[IO]{
          case _ => ref.modify(i => (i+1, i)).map{i => 
            Response[IO](Status.Ok)
              .withEntity(i.toString())
              .withHeaders(
                `Cache-Control`(
                  NonEmptyList.of(
                    CacheDirective.`private`(List.empty),
                    CacheDirective.`max-age`(lifetime)
                  )
                ),
                Date(now),
                Expires(HttpDate.unsafeFromEpochSecond(now.epochSecond + lifetime.toSeconds)),
              )
          }
        }.orNotFound
        cached = CacheMiddleware.httpApp(cache, CacheType.Private)
        newApp = cached(httpApp)
        request = Request[IO]()

        firstResp <- newApp.run(request)
        first <- firstResp.as[String]

        secondResp <- newApp.run(request)
        second <- secondResp.as[String]
        _ <- cache.delete((request.method, request.uri))
      } yield {
        (first must_=== "0") and
          (second must_=== "0")
      }
    }}

    "cached value expires after time" in { makeCache(None).use{ cache => 
      for {
        ref <- Ref[IO].of(0)
        now <- JavaTime[IO].getInstant.map(HttpDate.fromInstant).rethrow
        lifetime = 1.second
        httpApp = HttpRoutes.of[IO]{
          case _ => ref.modify(i => (i+1, i)).map{i => 
            Response[IO](Status.Ok)
              .withEntity(i.toString())
              .withHeaders(
                `Cache-Control`(
                  NonEmptyList.of(
                    CacheDirective.`private`(List.empty),
                    CacheDirective.`max-age`(lifetime)
                  )
                ),
                Date(now),
                Expires(HttpDate.unsafeFromEpochSecond(now.epochSecond + lifetime.toSeconds)),
              )
          }
        }.orNotFound
        cached = CacheMiddleware.httpApp(cache, CacheType.Private)
        newApp = cached(httpApp)
        request = Request[IO]()

        firstResp <- newApp.run(request)
        first <- firstResp.as[String]

        _ <- Timer[IO].sleep(2.seconds)

        secondResp <- newApp.run(request)
        second <- secondResp.as[String]
        _ <- cache.delete((request.method, request.uri))
      } yield {
        (first must_=== "0") and
          (second must_=== "1")
      }
    }}
  }

}