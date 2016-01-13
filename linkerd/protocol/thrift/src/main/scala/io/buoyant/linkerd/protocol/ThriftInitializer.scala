package io.buoyant.linkerd
package protocol

import com.twitter.finagle.Path
import io.buoyant.router.{Thrift, RoutingFactory}

class ThriftInitializer extends ProtocolInitializer {
  val name = "thrift"

  protected type RouterReq = com.twitter.finagle.thrift.ThriftClientRequest
  protected type RouterRsp = Array[Byte]
  protected type ServerReq = Array[Byte]
  protected type ServerRsp = Array[Byte]

  protected val defaultRouter = Thrift.router
    .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))

  protected val adapter = Thrift.Router.IngestingFilter
  protected val defaultServer = Thrift.server
    .configured(Server.Port(4114))

  val Framed = Parsing.Param.Boolean("thriftFramed") { framed =>
    Thrift.param.Framed(framed)
  }

  override val routerParamsParser = Framed
  override val serverParamsParser = Framed
}
