# mules-http4s-redis - Mules Http4s Integration for Redis [![Build Status](https://travis-ci.com/ChristopherDavenport/mules-http4s-redis.svg?branch=master)](https://travis-ci.com/ChristopherDavenport/mules-http4s-redis) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/mules-http4s-redis_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/mules-http4s-redis_2.12) ![Code of Consuct](https://img.shields.io/badge/Code%20of%20Conduct-Scala-blue.svg)

## [Head on over to the microsite](https://ChristopherDavenport.github.io/mules-http4s-redis)

## Quick Start

To use mules-http4s-redis in an existing SBT project with Scala 2.11 or a later version, add the following dependencies to your
`build.sbt` depending on your needs:

```scala
libraryDependencies ++= Seq(
  "io.chrisdavenport" %% "mules-http4s-redis" % "<version>"
)
```


To create a client, simply use the redis tools available from other components. This serves as the Codec which allows the other mules-redis, and mules-http4s to interact together.

```scala

import io.chrisdavenport.mules.redis.RedisCache
import org.http4s._
import io.chrisdavenport.mules.http4s._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.{ RedisClient, RedisURI }

def makeCache(defaultTimeout: Option[TimeSpec], connectString: String) : Resource[IO, Cache[IO, (Method, Uri), CacheItem]] = for {
    uri <- Resource.liftF(RedisURI.make[IO](connectString)) // Something like s"redis://$server:$port"
    client <- RedisClient[IO](uri)
    redis <- Redis[IO].fromClient(client,  RedisHttpCodec.CacheKeyWithItem)
    cache = RedisCache.fromCommands(redis, defaultTimeout)
  } yield cache
```
