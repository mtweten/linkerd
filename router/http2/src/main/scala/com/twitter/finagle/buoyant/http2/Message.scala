package com.twitter.finagle.buoyant.http2

import com.twitter.io.Buf
import com.twitter.util.{Future, Promise, Return, Throw}
import io.netty.handler.codec.http2.{DefaultHttp2Headers, Http2Headers}
import scala.collection.immutable.Queue
import scala.collection.mutable.ListBuffer

trait Header {
  def key: String
  def value: String
}
sealed trait Headers {
  def toSeq: Seq[(String, String)]
}

private[http2] trait Netty4Headers { self: Headers =>
  def underlying: Http2Headers

  def toSeq = {
    val buf = ListBuffer.newBuilder[(String, String)]
    buf.sizeHint(underlying.size)
    val iter = underlying.iterator
    while (iter.hasNext) {
      val entry = iter.next()
      buf += entry.getKey.toString -> entry.getValue.toString
    }
    buf.result
  }
}

object Headers {
  private[http2] def apply(headers: Http2Headers): Headers =
    new Netty4Headers with Headers {
      val underlying = headers
    }
}

trait RequestHeaders extends Headers {
  def scheme: String
  def method: String
  def path: String
  def authority: String
}

object RequestHeaders {

  private[http2] def apply(headers: Http2Headers): RequestHeaders = {
    require(headers.scheme != null)
    require(headers.method != null)
    require(headers.path != null)
    require(headers.authority != null)
    new Netty4Headers with RequestHeaders {
      val underlying = headers
      def scheme = headers.scheme.toString
      def method = headers.method.toString
      def path = headers.path.toString
      def authority = headers.authority.toString
    }
  }

  def apply(
    scheme: String,
    method: String,
    path: String,
    authority: String,
    headers: Seq[(String, String)] = Nil
  ): RequestHeaders = {
    val h = new DefaultHttp2Headers
    h.scheme(scheme)
    h.method(method)
    h.path(path)
    h.authority(authority)
    for ((k, v) <- headers) h.add(k, v)
    apply(h)
  }
}

trait ResponseHeaders extends Headers {
  def status: Int
}

object ResponseHeaders {

  private[http2] def apply(headers: Http2Headers): ResponseHeaders = {
    require(headers.status != null)
    new Netty4Headers with ResponseHeaders {
      val underlying = headers
      def status = headers.status.toString.toInt
    }
  }

  def apply(
    status: Int,
    headers: Seq[(String, String)] = Nil
  ): ResponseHeaders = {
    val h = new DefaultHttp2Headers
    h.status(status.toString)
    for ((k, v) <- headers) h.add(k, v)
    apply(h)
  }
}

object DataStream {
  sealed trait Value {
    def isEnd: Boolean
  }

  trait Data extends Value {
    def buf: Buf
    def release(): Future[Unit]
  }

  case class Trailers(headers: Headers) extends Value {
    val isEnd = true
  }

  object Empty extends DataStream {
    def isEmpty = true
    def onEnd = Future.Unit
    def read() = Future.never
    def fail(exn: Throwable) = {}
  }
}

trait DataStream {
  def isEmpty: Boolean
  final def nonEmpty: Boolean = !isEmpty

  def onEnd: Future[Unit]

  def read(): Future[DataStream.Value]
  def fail(exn: Throwable): Unit
}

/**
 * An HTTP2 message.
 *
 * HTTP2 Messages consist of three components:
 * - Headers
 * - Data
 * - Trailers
 *
 * Headers are required; and Data and Trailers may be empty.
 */
sealed trait Message {
  def headers: Headers
  def data: DataStream
}

case class Request(
  headers: RequestHeaders,
  data: DataStream = DataStream.Empty
) extends Message {
  override def toString =
    s"Request(${headers.method} ${headers.authority} ${headers.path}, eos=${data.isEmpty})"
}

case class Response(
  headers: ResponseHeaders,
  data: DataStream = DataStream.Empty
) extends Message {
  override def toString =
    s"Response(${headers.status}, eos=${data.isEmpty})"
}
