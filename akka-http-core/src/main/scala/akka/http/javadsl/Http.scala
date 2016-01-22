/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import java.util.Optional
import akka.http.impl.util.JavaMapping
import akka.http.impl.util.JavaMapping.HttpsConnectionContext
import akka.http.javadsl.model.ws._
import akka.{ stream, NotUsed }
import akka.stream.io.{ SslTlsInbound, SslTlsOutbound }

import scala.language.implicitConversions
import scala.concurrent.Future
import scala.util.Try
import akka.stream.scaladsl.Keep
import akka.japi.{ Pair, Function }
import akka.actor.{ ExtendedActorSystem, ActorSystem, ExtensionIdProvider, ExtensionId }
import akka.event.LoggingAdapter
import akka.stream.Materializer
import akka.stream.javadsl.{ BidiFlow, Flow, Source }

import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.scaladsl.{ model ⇒ sm }
import akka.http.javadsl.model._
import akka.http._

import scala.compat.java8.OptionConverters._

object Http extends ExtensionId[Http] with ExtensionIdProvider {
  override def get(system: ActorSystem): Http = super.get(system)
  def lookup() = Http
  def createExtension(system: ExtendedActorSystem): Http = new Http(system)
}

class Http(system: ExtendedActorSystem) extends akka.actor.Extension {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ ec }

  private lazy val delegate = akka.http.scaladsl.Http(system)

  /**
   * Constructs a server layer stage using the configured default [[ServerSettings]]. The returned [[BidiFlow]] isn't
   * reusable and can only be materialized once.
   */
  def serverLayer(materializer: Materializer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    adaptServerLayer(delegate.serverLayer()(materializer))

  /**
   * Constructs a server layer stage using the given [[ServerSettings]]. The returned [[BidiFlow]] isn't reusable and
   * can only be materialized once.
   */
  def serverLayer(settings: ServerSettings,
                  materializer: Materializer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    adaptServerLayer(delegate.serverLayer(settings)(materializer))

  /**
   * Constructs a server layer stage using the given [[ServerSettings]]. The returned [[BidiFlow]] isn't reusable and
   * can only be materialized once. The `remoteAddress`, if provided, will be added as a header to each [[HttpRequest]]
   * this layer produces if the `akka.http.server.remote-address-header` configuration option is enabled.
   */
  def serverLayer(settings: ServerSettings,
                  remoteAddress: Optional[InetSocketAddress],
                  materializer: Materializer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    adaptServerLayer(delegate.serverLayer(settings, remoteAddress.asScala)(materializer))

  /**
   * Constructs a server layer stage using the given [[ServerSettings]]. The returned [[BidiFlow]] isn't reusable and
   * can only be materialized once. The remoteAddress, if provided, will be added as a header to each [[HttpRequest]]
   * this layer produces if the `akka.http.server.remote-address-header` configuration option is enabled.
   */
  def serverLayer(settings: ServerSettings,
                  remoteAddress: Optional[InetSocketAddress],
                  log: LoggingAdapter,
                  materializer: Materializer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    adaptServerLayer(delegate.serverLayer(settings, remoteAddress.asScala, log)(materializer))

  /**
   * Creates a [[Source]] of [[IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[ServerBinding]].
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[ServerBinding]].
   */
  def bind(interface: String, port: Int, materializer: Materializer): Source[IncomingConnection, Future[ServerBinding]] =
    new Source(delegate.bind(interface, port)(materializer)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec)))

  /**
   * Creates a [[Source]] of [[IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   *
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[ServerBinding]].
   *
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[ServerBinding]].
   */
  def bind(interface: String, port: Int,
           connectionContext: ConnectionContext,
           settings: ServerSettings,
           materializer: Materializer): Source[IncomingConnection, Future[ServerBinding]] =
    new Source(delegate.bind(interface, port, settings = settings, connectionContext = ConnectionContext.noEncryption().asScala)(materializer)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec)))

  /**
   * Creates a [[Source]] of [[IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   *
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[ServerBinding]].
   *
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[ServerBinding]].
   */
  def bind(interface: String, port: Int,
           connectionContext: ConnectionContext,
           materializer: Materializer): Source[IncomingConnection, Future[ServerBinding]] =
    new Source(delegate.bind(interface, port, connectionContext = connectionContext.asScala)(materializer)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec)))

  /**
   * Creates a [[Source]] of [[IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   *
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[ServerBinding]].
   *
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[ServerBinding]].
   */
  def bind(interface: String, port: Int,
           connectionContext: ConnectionContext,
           settings: ServerSettings,
           log: LoggingAdapter,
           materializer: Materializer): Source[IncomingConnection, Future[ServerBinding]] =
    new Source(delegate.bind(interface, port, ConnectionContext.noEncryption().asScala, settings, log)(materializer)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec)))

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandle(handler: Flow[HttpRequest, HttpResponse, _],
                    interface: String, port: Int,
                    materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandle(handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, _]].asScala,
      interface, port)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandle(handler: Flow[HttpRequest, HttpResponse, _],
                    interface: String, port: Int,
                    connectionContext: ConnectionContext,
                    materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandle(handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, _]].asScala,
      interface, port, connectionContext.asScala)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandle(handler: Flow[HttpRequest, HttpResponse, _],
                    interface: String, port: Int,
                    settings: ServerSettings,
                    connectionContext: ConnectionContext,
                    log: LoggingAdapter,
                    materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandle(handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, _]].asScala,
      interface, port, connectionContext.asScala, settings, log)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleSync(handler: Function[HttpRequest, HttpResponse],
                        interface: String, port: Int,
                        materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleSync(handler.apply(_).asScala, interface, port)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleSync(handler: Function[HttpRequest, HttpResponse],
                        interface: String, port: Int,
                        connectionContext: ConnectionContext,
                        materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleSync(handler.apply(_).asScala, interface, port, connectionContext.asScala)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleSync(handler: Function[HttpRequest, HttpResponse],
                        interface: String, port: Int,
                        settings: ServerSettings,
                        connectionContext: ConnectionContext,
                        log: LoggingAdapter,
                        materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleSync(handler.apply(_).asScala,
      interface, port, connectionContext.asScala, settings, log)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleAsync(handler: Function[HttpRequest, Future[HttpResponse]],
                         interface: String, port: Int,
                         materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleAsync(handler.apply(_).asInstanceOf[Future[sm.HttpResponse]], interface, port)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleAsync(handler: Function[HttpRequest, Future[HttpResponse]],
                         interface: String, port: Int,
                         connectionContext: ConnectionContext,
                         materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleAsync(handler.apply(_).asInstanceOf[Future[sm.HttpResponse]], interface, port, connectionContext.asScala)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleAsync(handler: Function[HttpRequest, Future[HttpResponse]],
                         interface: String, port: Int,
                         settings: ServerSettings, connectionContext: ConnectionContext,
                         parallelism: Int, log: LoggingAdapter,
                         materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleAsync(handler.apply(_).asInstanceOf[Future[sm.HttpResponse]],
      interface, port, connectionContext.asScala, settings, parallelism, log)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Constructs a client layer stage using the configured default [[ClientConnectionSettings]].
   */
  def clientLayer(hostHeader: headers.Host): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed] =
    adaptClientLayer(delegate.clientLayer(JavaMapping.toScala(hostHeader)))

  /**
   * Constructs a client layer stage using the given [[ClientConnectionSettings]].
   */
  def clientLayer(hostHeader: headers.Host,
                  settings: ClientConnectionSettings): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed] =
    adaptClientLayer(delegate.clientLayer(JavaMapping.toScala(hostHeader), settings))

  /**
   * Constructs a client layer stage using the given [[ClientConnectionSettings]].
   */
  def clientLayer(hostHeader: headers.Host,
                  settings: ClientConnectionSettings,
                  log: LoggingAdapter): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed] =
    adaptClientLayer(delegate.clientLayer(JavaMapping.toScala(hostHeader), settings, log))

  /**
   * Creates a [[Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   *
   * If the hostname is given with an `https://` prefix, the default [[HttpsConnectionContext]] will be used.
   */
  def outgoingConnection(host: String): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    outgoingConnection(ConnectHttp.toHost(host))

  /**
   * Creates a [[Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   *
   * Use the [[ConnectHttp]] DSL to configure target host and whether HTTPS should be used.
   */
  def outgoingConnection(to: ConnectHttp): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    adaptOutgoingFlow {
      if (to.isHttps) delegate.outgoingConnectionHttps(to.host, to.port, to.effectiveConnectionContext(defaultClientHttpsContext).asScala)
      else delegate.outgoingConnection(to.host, to.port)
    }

  /**
   * Creates a [[Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   */
  def outgoingConnection(host: String, port: Int,
                         connectionContext: ConnectionContext,
                         localAddress: Optional[InetSocketAddress],
                         settings: ClientConnectionSettings,
                         log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    adaptOutgoingFlow {
      connectionContext match {
        case https: HttpsConnectionContext ⇒ delegate.outgoingConnectionHttps(host, port, https.asScala, localAddress.asScala, settings, log)
        case _                             ⇒ delegate.outgoingConnection(host, port, localAddress.asScala, settings, log)
      }
    }

  /**
   * Starts a new connection pool to the given host and configuration and returns a [[Flow]] which dispatches
   * the requests from all its materializations across this pool.
   * While the started host connection pool internally shuts itself down automatically after the configured idle
   * timeout it will spin itself up again if more requests arrive from an existing or a new client flow
   * materialization. The returned flow therefore remains usable for the full lifetime of the application.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def newHostConnectionPool[T](host: String, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    newHostConnectionPool[T](ConnectHttp.toHost(host), materializer)

  /**
   * Starts a new connection pool to the given host and configuration and returns a [[Flow]] which dispatches
   * the requests from all its materializations across this pool.
   * While the started host connection pool internally shuts itself down automatically after the configured idle
   * timeout it will spin itself up again if more requests arrive from an existing or a new client flow
   * materialization. The returned flow therefore remains usable for the full lifetime of the application.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def newHostConnectionPool[T](to: ConnectHttp, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.newHostConnectionPool[T](to.host, to.port)(materializer))

  /**
   * Same as [[newHostConnectionPool]] but with HTTPS encryption.
   *
   * The given [[ConnectionContext]] will be used for encryption on the connection.
   */
  def newHostConnectionPool[T](to: ConnectHttp,
                               settings: ConnectionPoolSettings,
                               log: LoggingAdapter, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow {
      to.effectiveConnectionContext(defaultClientHttpsContext) match {
        case https: HttpsConnectionContext ⇒ delegate.newHostConnectionPoolHttps[T](to.host, to.port, https.asScala, settings, log)(materializer)
        case _                             ⇒ delegate.newHostConnectionPool[T](to.host, to.port, settings, log)(materializer)
      }
    }

  /**
   * Returns a [[Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * The internal caching logic guarantees that there will never be more than a single pool running for the
   * given target host endpoint and configuration (in this ActorSystem).
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def cachedHostConnectionPool[T](host: String, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    cachedHostConnectionPool(ConnectHttp.toHost(host), materializer)

  /**
   * Returns a [[Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * The internal caching logic guarantees that there will never be more than a single pool running for the
   * given target host endpoint and configuration (in this ActorSystem).
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def cachedHostConnectionPool[T](to: ConnectHttp, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPool[T](to.host, to.port)(materializer))

  /**
   * Same as [[cachedHostConnectionPool]] but with HTTPS encryption.
   *
   * The given [[ConnectionContext]] will be used for encryption on the connection.
   */
  def cachedHostConnectionPool[T](to: ConnectHttp,
                                  settings: ConnectionPoolSettings,
                                  log: LoggingAdapter, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPoolHttps[T](to.host, to.port, to.effectiveConnectionContext(defaultClientHttpsContext).asScala, settings, log)(materializer))

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have either an absolute URI or
   * a valid `Host` header.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests `A` and `B` enter the flow in that order the response for `B` might be produced before the
   * response for `A`.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def superPool[T](materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], NotUsed] =
    adaptTupleFlow(delegate.superPool[T]()(materializer))

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have either an absolute URI or
   * a valid `Host` header.
   *
   * The given [[HttpsConnectionContext]] is used to configure TLS for the connection.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests `A` and `B` enter the `flow` in that order the response for `B` might be produced before the
   * response for `A`.
   *
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def superPool[T](settings: ConnectionPoolSettings,
                   connectionContext: HttpsConnectionContext,
                   log: LoggingAdapter, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], NotUsed] =
    adaptTupleFlow(delegate.superPool[T](connectionContext.asScala, settings, log)(materializer))

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have either an absolute URI or
   * a valid `Host` header.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests `A` and `B` enter the `flow` in that order the response for `B` might be produced before the
   * response for `A`.
   *
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def superPool[T](settings: ConnectionPoolSettings,
                   log: LoggingAdapter, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], NotUsed] =
    adaptTupleFlow(delegate.superPool[T](defaultClientHttpsContext.asScala, settings, log)(materializer))

  /**
   * Fires a single [[HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   *
   * Note that the request must have either an absolute URI or a valid `Host` header, otherwise
   * the future will be completed with an error.
   */
  def singleRequest(request: HttpRequest, materializer: Materializer): Future[HttpResponse] =
    delegate.singleRequest(request.asScala)(materializer)

  /**
   * Fires a single [[HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   *
   * Note that the request must have either an absolute URI or a valid `Host` header, otherwise
   * the future will be completed with an error.
   */
  def singleRequest(request: HttpRequest, connectionContext: HttpsConnectionContext, materializer: Materializer): Future[HttpResponse] =
    delegate.singleRequest(request.asScala, connectionContext.asScala)(materializer)

  /**
   * Fires a single [[HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * The given [[HttpsConnectionContext]] will be used for encruption if the request is sent to an https endpoint.
   *
   * Note that the request must have either an absolute URI or a valid `Host` header, otherwise
   * the future will be completed with an error.
   */
  def singleRequest(request: HttpRequest,
                    connectionContext: HttpsConnectionContext,
                    settings: ConnectionPoolSettings,
                    log: LoggingAdapter, materializer: Materializer): Future[HttpResponse] =
    delegate.singleRequest(request.asScala, connectionContext.asScala, settings, log)(materializer)

  /**
   * Constructs a WebSocket [[BidiFlow]].
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientLayer(request: WebSocketRequest): BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebSocketUpgradeResponse]] =
    adaptWsBidiFlow(delegate.webSocketClientLayer(request.asScala))

  /**
   * Constructs a WebSocket [[BidiFlow]] using the configured default [[ClientConnectionSettings]],
   * configured using the `akka.http.client` config section.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientLayer(request: WebSocketRequest,
                           settings: ClientConnectionSettings): BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebSocketUpgradeResponse]] =
    adaptWsBidiFlow(delegate.webSocketClientLayer(request.asScala, settings))

  /**
   * Constructs a WebSocket [[BidiFlow]] using the configured default [[ClientConnectionSettings]],
   * configured using the `akka.http.client` config section.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientLayer(request: WebSocketRequest,
                           settings: ClientConnectionSettings,
                           log: LoggingAdapter): BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebSocketUpgradeResponse]] =
    adaptWsBidiFlow(delegate.webSocketClientLayer(request.asScala, settings, log))

  /**
   * Constructs a flow that once materialized establishes a WebSocket connection to the given Uri.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientFlow(request: WebSocketRequest): Flow[Message, Message, Future[WebSocketUpgradeResponse]] =
    adaptWsFlow {
      delegate.webSocketClientFlow(request.asScala)
    }

  /**
   * Constructs a flow that once materialized establishes a WebSocket connection to the given Uri.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientFlow(request: WebSocketRequest,
                          connectionContext: ConnectionContext,
                          localAddress: Optional[InetSocketAddress],
                          settings: ClientConnectionSettings,
                          log: LoggingAdapter): Flow[Message, Message, Future[WebSocketUpgradeResponse]] =
    adaptWsFlow {
      delegate.webSocketClientFlow(request.asScala, connectionContext.asScala, localAddress.asScala, settings, log)
    }

  /**
   * Runs a single WebSocket conversation given a Uri and a flow that represents the client side of the
   * WebSocket conversation.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   */
  def singleWebSocketRequest[T](request: WebSocketRequest,
                                clientFlow: Flow[Message, Message, T],
                                materializer: Materializer): Pair[Future[WebSocketUpgradeResponse], T] =
    adaptWsResultTuple {
      delegate.singleWebSocketRequest(
        request.asScala,
        adaptWsFlow[T](clientFlow))(materializer)
    }

  /**
   * Runs a single WebSocket conversation given a Uri and a flow that represents the client side of the
   * WebSocket conversation.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   */
  def singleWebSocketRequest[T](request: WebSocketRequest,
                                clientFlow: Flow[Message, Message, T],
                                connectionContext: ConnectionContext,
                                materializer: Materializer): Pair[Future[WebSocketUpgradeResponse], T] =
    adaptWsResultTuple {
      delegate.singleWebSocketRequest(
        request.asScala,
        adaptWsFlow[T](clientFlow),
        connectionContext.asScala)(materializer)
    }

  /**
   * Runs a single WebSocket conversation given a Uri and a flow that represents the client side of the
   * WebSocket conversation.
   */
  def singleWebSocketRequest[T](request: WebSocketRequest,
                                clientFlow: Flow[Message, Message, T],
                                connectionContext: ConnectionContext,
                                localAddress: Optional[InetSocketAddress],
                                settings: ClientConnectionSettings,
                                log: LoggingAdapter,
                                materializer: Materializer): Pair[Future[WebSocketUpgradeResponse], T] =
    adaptWsResultTuple {
      delegate.singleWebSocketRequest(
        request.asScala,
        adaptWsFlow[T](clientFlow),
        connectionContext.asScala,
        localAddress.asScala,
        settings,
        log)(materializer)
    }

  /**
   * Triggers an orderly shutdown of all host connections pools currently maintained by the [[ActorSystem]].
   * The returned future is completed when all pools that were live at the time of this method call
   * have completed their shutdown process.
   *
   * If existing pool client flows are re-used or new ones materialized concurrently with or after this
   * method call the respective connection pools will be restarted and not contribute to the returned future.
   */
  def shutdownAllConnectionPools(): Future[Unit] = delegate.shutdownAllConnectionPools()

  /**
   * Gets the default
   * @return
   */
  def defaultServerHttpContext: ConnectionContext =
    delegate.defaultServerHttpContext

  /**
   * Gets the current default client-side [[ConnectionContext]].
   */
  def defaultClientHttpsContext: akka.http.javadsl.HttpsConnectionContext = delegate.defaultClientHttpsContext

  /**
   * Sets the default client-side [[ConnectionContext]].
   */
  def setDefaultClientHttpsContext(context: HttpsConnectionContext): Unit =
    delegate.setDefaultClientHttpsContext(context.asInstanceOf[akka.http.scaladsl.HttpsConnectionContext])

  private def adaptTupleFlow[T, Mat](scalaFlow: stream.scaladsl.Flow[(scaladsl.model.HttpRequest, T), (Try[scaladsl.model.HttpResponse], T), Mat]): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], Mat] = {
    implicit val _ = JavaMapping.identity[T]
    JavaMapping.toJava(scalaFlow)(JavaMapping.flowMapping[Pair[HttpRequest, T], (scaladsl.model.HttpRequest, T), Pair[Try[HttpResponse], T], (Try[scaladsl.model.HttpResponse], T), Mat])
  }

  private def adaptOutgoingFlow[T, Mat](scalaFlow: stream.scaladsl.Flow[scaladsl.model.HttpRequest, scaladsl.model.HttpResponse, Future[scaladsl.Http.OutgoingConnection]]): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    Flow.fromGraph {
      akka.stream.scaladsl.Flow[HttpRequest].map(_.asScala)
        .viaMat(scalaFlow)(Keep.right)
        .mapMaterializedValue(_.map(new OutgoingConnection(_))(ec))
    }

  private def adaptServerLayer(serverLayer: scaladsl.Http.ServerLayer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    new BidiFlow(
      JavaMapping.adapterBidiFlow[HttpResponse, sm.HttpResponse, sm.HttpRequest, HttpRequest]
        .atop(serverLayer))

  private def adaptClientLayer(clientLayer: scaladsl.Http.ClientLayer): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed] =
    new BidiFlow(
      JavaMapping.adapterBidiFlow[HttpRequest, sm.HttpRequest, sm.HttpResponse, HttpResponse]
        .atop(clientLayer))

  private def adaptWsBidiFlow(wsLayer: scaladsl.Http.WebSocketClientLayer): BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebSocketUpgradeResponse]] =
    new BidiFlow(
      JavaMapping.adapterBidiFlow[Message, sm.ws.Message, sm.ws.Message, Message]
        .atopMat(wsLayer)((_, s) ⇒ adaptWsUpgradeResponse(s)))

  private def adaptWsFlow(wsLayer: stream.scaladsl.Flow[sm.ws.Message, sm.ws.Message, Future[scaladsl.model.ws.WebSocketUpgradeResponse]]): Flow[Message, Message, Future[WebSocketUpgradeResponse]] =
    Flow.fromGraph(JavaMapping.adapterBidiFlow[Message, sm.ws.Message, sm.ws.Message, Message].joinMat(wsLayer)(Keep.right).mapMaterializedValue(adaptWsUpgradeResponse _))

  private def adaptWsFlow[Mat](javaFlow: Flow[Message, Message, Mat]): stream.scaladsl.Flow[scaladsl.model.ws.Message, scaladsl.model.ws.Message, Mat] =
    stream.scaladsl.Flow[scaladsl.model.ws.Message]
      .map(Message.adapt)
      .viaMat(javaFlow.asScala)(Keep.right)
      .map(_.asScala)

  private def adaptWsResultTuple[T](result: (Future[scaladsl.model.ws.WebSocketUpgradeResponse], T)): Pair[Future[WebSocketUpgradeResponse], T] =
    result match {
      case (fut, tMat) ⇒ Pair(adaptWsUpgradeResponse(fut), tMat)
    }
  private def adaptWsUpgradeResponse(responseFuture: Future[scaladsl.model.ws.WebSocketUpgradeResponse]): Future[WebSocketUpgradeResponse] =
    responseFuture.map(WebSocketUpgradeResponse.adapt)(system.dispatcher)
}
