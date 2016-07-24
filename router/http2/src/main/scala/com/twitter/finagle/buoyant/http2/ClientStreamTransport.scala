package com.twitter.finagle.buoyant.http2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw}
import io.netty.handler.codec.http2.{Http2StreamFrame, Http2HeadersFrame}

private[http2] class ClientStreamTransport(
  val streamId: Int,
  transport: Http2Transport.Writer,
  val recvq: AsyncQueue[Http2StreamFrame] = new AsyncQueue,
  minAccumFrames: Int = 10,
  statsReceiver: StatsReceiver = NullStatsReceiver
) {
  private[this] val responseMillis = statsReceiver.stat("response_duration_ms")
  private[this] val recvqSizes = statsReceiver.stat("recvq", "sz")

  @volatile private[this] var isRequestFinished, isResponseFinished = false
  def isClosed = isRequestFinished && isResponseFinished

  val closeP = new Promise[Unit]
  def onClose: Future[Unit] = closeP

  private[this] def setRequestFinished(): Unit = {
    isRequestFinished = true
    if (isClosed) {
      val _ = closeP.setDone()
    }
  }

  private[this] def setResponseFinished(): Unit = {
    isResponseFinished = true
    if (isClosed) {
      val _ = closeP.setDone()
    }
  }

  def writeHeaders(hdrs: Headers, eos: Boolean = false) = {
    val writeF = transport.writeHeaders(hdrs, streamId, eos)
    if (eos) writeF.ensure(setRequestFinished())
    writeF
  }

  /** Write a request stream */
  def streamRequest(data: DataStream): Future[Unit] = {
    require(!isRequestFinished)
    lazy val loop: Boolean => Future[Unit] = { eos =>
      if (eos) Future.Unit
      else data.read().flatMap(writeData).flatMap(loop)
    }
    data.read().flatMap(writeData).flatMap(loop)
  }

  private[this] val writeData: DataStream.Value => Future[Boolean] = { v =>
    val writeF = v match {
      case data: DataStream.Data =>
        transport.writeData(data, streamId).before(data.release()).map(_ => data.isEnd)
      case DataStream.Trailers(tlrs) =>
        transport.writeTrailers(tlrs, streamId).before(Future.True)
    }
    if (v.isEnd) writeF.ensure(setRequestFinished())
    writeF
  }

  def readResponse(): Future[Response] = {
    // Start out by reading response headers from the stream
    // queue. Once a response is initialized, if data is expected,
    // continue reading from the queue until an end stream message is
    // encounetered.
    recvqSizes.add(recvq.size)
    recvq.poll().map {
      case f: Http2HeadersFrame if f.isEndStream =>
        setResponseFinished()
        Response(ResponseHeaders(f.headers))

      case f: Http2HeadersFrame =>
        val responseStart = Stopwatch.start()
        val data = new Http2FrameDataStream(recvq, releaser, minAccumFrames, statsReceiver)
        data.onEnd.onSuccess(_ => responseMillis.add(responseStart().inMillis))
        data.onEnd.ensure(setResponseFinished())
        Response(ResponseHeaders(f.headers), data)

      case f =>
        setResponseFinished()
        throw new IllegalArgumentException(s"Expected response HEADERS; received ${f.name}")
    }
  }

  private[this] val releaser: Int => Future[Unit] =
    incr => transport.writeWindowUpdate(incr, streamId)
}
