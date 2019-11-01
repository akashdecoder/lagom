/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.scaladsl.client

import java.io.File
import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import com.lightbend.lagom.internal.client.CircuitBreakerMetricsProviderImpl
import com.lightbend.lagom.internal.client.WebSocketClientConfig
import com.lightbend.lagom.internal.scaladsl.api.broker.TopicFactoryProvider
import com.lightbend.lagom.internal.scaladsl.client.ScaladslClientMacroImpl
import com.lightbend.lagom.internal.scaladsl.client.ScaladslServiceClient
import com.lightbend.lagom.internal.scaladsl.client.ScaladslServiceResolver
import com.lightbend.lagom.internal.scaladsl.client.ScaladslWebSocketClient
import com.lightbend.lagom.internal.spi.CircuitBreakerMetricsProvider
import com.lightbend.lagom.scaladsl.api._
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.deser.DefaultExceptionSerializer
import com.lightbend.lagom.scaladsl.api.deser.ExceptionSerializer
import org.slf4j.LoggerFactory
import play.api.inject.ApplicationLifecycle
import play.api.inject.DefaultApplicationLifecycle
import play.api.libs.concurrent.ActorSystemProvider
import play.api.libs.concurrent.CoordinatedShutdownProvider
import play.api.internal.libs.concurrent.CoordinatedShutdownSupport
import play.api.internal.libs.concurrent.CoordinatedShutdownSupport.asyncShutdown
import play.api.libs.ws.WSClient
import play.api.Configuration
import play.api.Environment
import play.api.Mode

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.language.experimental.macros
import scala.util.control.NonFatal

/**
 * The Lagom service client implementor.
 *
 * Instances of this must also implement [[ServiceClientConstructor]], so that the `implementClient` macro can
 * generate code that constructs the service client.
 */
trait ServiceClient { self: ServiceClientConstructor =>

  /**
   * Implement a client for the given service descriptor.
   */
  def implement[S <: Service]: S = macro ScaladslClientMacroImpl.implementClient[S]
}

/**
 * Lagom service client constructor.
 *
 * This API should not be used directly, it will be invoked by the client generated by [[ServiceClient.implement]] in
 * order to construct the client and obtain the dependencies necessary for the client to operate.
 *
 * The reason for a separation between this interface and [[ServiceClient]] is so that the [[#construct]] method
 * doesn't appear on the user facing [[ServiceClient]] API. The macro it generates will cast the [[ServiceClient]] to
 * a [[ServiceClientConstructor]] in order to invoke it.
 *
 * Although this API should not directly be used by end users, the code generated by the [[ServiceClient]] macro does
 * cause end users to have a binary dependency on this class, which is why it's in the `scaladsl` package.
 */
trait ServiceClientConstructor extends ServiceClient {
  /**
   * Construct a service client, by invoking the passed in function that takes the implementation context.
   */
  def construct[S <: Service](constructor: ServiceClientImplementationContext => S): S
}

/**
 * The service client implementation context.
 *
 * This API should not be used directly, it will be invoked by the client generated by [[ServiceClient.implement]] in
 * order to resolve the service descriptor.
 *
 * The purpose of this API is to capture the dependencies required in order to implement a service client, such as the
 * HTTP and WebSocket clients.
 *
 * Although this API should not directly be used by end users, the code generated by the [[ServiceClient]] macro does
 * cause end users to have a binary dependency on this class, which is why it's in the `scaladsl` package.
 */
trait ServiceClientImplementationContext {
  /**
   * Resolve the given descriptor to a service client context.
   */
  def resolve(descriptor: Descriptor): ServiceClientContext
}

/**
 * The service client context.
 *
 * This API should not be used directly, it will be invoked by the client generated by [[ServiceClient.implement]] in
 * order to implement each service call and topic.
 *
 * The service client context is essentially a map of service calls and topics, constructed from a service descriptor,
 * that allows a [[ServiceCall]] to be easily constructed by the services methods.
 *
 * Although this API should not directly be used by end users, the code generated by the [[ServiceClient]] macro does
 * cause end users to have a binary dependency on this class, which is why it's in the `scaladsl` package.
 */
trait ServiceClientContext {
  /**
   * Create a service call for the given method name and passed in parameters.
   */
  def createServiceCall[Request, Response](
      methodName: String,
      params: immutable.Seq[Any]
  ): ServiceCall[Request, Response]

  /**
   * Create a topic for the given method name.
   */
  def createTopic[Message](methodName: String): Topic[Message]
}

trait ServiceResolver {
  def resolve(descriptor: Descriptor): Descriptor
}

/**
 * The Lagom service client components.
 */
trait LagomServiceClientComponents extends TopicFactoryProvider { self: LagomConfigComponent =>

  def wsClient: WSClient
  def serviceInfo: ServiceInfo
  def serviceLocator: ServiceLocator
  def materializer: Materializer
  def actorSystem: ActorSystem
  def executionContext: ExecutionContext
  def environment: Environment
  def applicationLifecycle: ApplicationLifecycle

  lazy val circuitBreakerMetricsProvider: CircuitBreakerMetricsProvider = new CircuitBreakerMetricsProviderImpl(
    actorSystem
  )

  lazy val serviceResolver: ServiceResolver                = new ScaladslServiceResolver(defaultExceptionSerializer)
  lazy val defaultExceptionSerializer: ExceptionSerializer = new DefaultExceptionSerializer(environment)

  lazy val scaladslWebSocketClient: ScaladslWebSocketClient =
    new ScaladslWebSocketClient(
      environment,
      WebSocketClientConfig(config),
      applicationLifecycle
    )(executionContext)

  lazy val serviceClient: ServiceClient = new ScaladslServiceClient(
    wsClient,
    scaladslWebSocketClient,
    serviceInfo,
    serviceLocator,
    serviceResolver,
    optionalTopicFactory
  )(executionContext, materializer)
}

/**
 * Convenience for constructing service clients in a non Lagom server application.
 *
 * It is important to invoke [[#stop]] when the application is no longer needed, as this will trigger the shutdown
 * of all thread and connection pools.
 */
@deprecated(message = "Use StandaloneLagomClientFactory instead", since = "1.4.9")
abstract class LagomClientApplication(
    clientName: String,
    classLoader: ClassLoader = classOf[LagomClientApplication].getClassLoader
) extends StandaloneLagomClientFactory(clientName, classLoader)

/**
 * Convenience for constructing service clients in a non Lagom server application.
 *
 * A [[StandaloneLagomClientFactory]] should be used only if your application does NOT have its own [[akka.actor.ActorSystem]], in which
 * this standalone factory will create and manage an [[akka.actor.ActorSystem]] and Akka Streams [[akka.stream.Materializer]].
 *
 * It is important to invoke [[StandaloneLagomClientFactory#stop()]] when the application is no longer needed,
 * as this will trigger the shutdown of the underlying [[akka.actor.ActorSystem]] and Akka Streams [[akka.stream.Materializer]]
 * releasing all thread and connection pools in use by the clients.
 *
 * There is one more component that you’ll need to provide when creating a client application, that is a service locator.
 * It is up to you what service locator you use, it could be a third party service locator, or a service locator created
 * from static configuration.
 *
 * Lagom provides a number of built-in service locators, including a [[StaticServiceLocator]], a [[RoundRobinServiceLocator]]
 * and a [[ConfigurationServiceLocator]]. The easiest way to use these is to mix in their respective Components traits.
 *
 * For example, here’s a client application built using the static service locator, which uses a static URI:
 * {{{
 * import java.net.URI
 * import com.lightbend.lagom.scaladsl.client._
 * import play.api.libs.ws.ahc.AhcWSComponents
 *
 * val clientApplication = new StandaloneLagomClientFactory("my-client")
 *   with StaticServiceLocatorComponents
 *   with AhcWSComponents {
 *
 *   override def staticServiceUri = URI.create("http://localhost:8080")
 * }
 * }}}
 *
 *
 * @param clientName The name of the service that is consuming the Lagom service. This will impact how calls made through clients
 *                   generated by this factory will identify themselves.
 * @param classLoader A classloader, it will be used to create the service proxy and needs to have the API for the client in it.
 */
abstract class StandaloneLagomClientFactory(
    clientName: String,
    classLoader: ClassLoader = classOf[StandaloneLagomClientFactory].getClassLoader
) extends LagomClientFactory(clientName, classLoader) {
  override lazy val actorSystem: ActorSystem = new ActorSystemProvider(environment, configuration).get
  lazy val coordinatedShutdown: CoordinatedShutdown =
    new CoordinatedShutdownProvider(actorSystem, applicationLifecycle).get
  override lazy val materializer: Materializer = ActorMaterializer.create(actorSystem)

  private val log = LoggerFactory.getLogger(this.getClass)

  /**
   * Stop this [[LagomClientFactory]] by shutting down the internal [[akka.actor.ActorSystem]], Akka Streams [[akka.stream.Materializer]]
   * and internal resources.
   */
  override def stop(): Unit = {
    implicit val ex = executionContext

    // we need to use the stop method from ApplicationLifecycle because the
    // WebSocket client register a stop hook on it
    // ideally, we should have used a CoordinatedShutdown phase for it,
    // but we can't know if the ActorSystem is going to be shutdown together with this Factory
    val stopped =
      releaseInternalResources()
      // we don't want to fail the Future if we can't close the internal resources
        .map(_ => Right[Throwable, Done](Done))
        .recover[Either[Throwable, Done]] {
          case NonFatal(ex) =>
            log.warn("failed to close internal resources", ex)
            Right(Done)
          case fatal =>
            log.warn("failed to close internal resources", fatal)
            Left(fatal)
        }
        .flatMap {
          case Right(_) =>
            CoordinatedShutdownSupport.asyncShutdown(actorSystem, ClientStoppedReason)
          case Left(fatal) =>
            // if releaseInternalResources throws a fatal exception
            // we still try to stop the actor system, but fail the final Future
            // using the fatal exception we got from above
            CoordinatedShutdownSupport
              .asyncShutdown(actorSystem, ClientStoppedReason)
              .recover { case NonFatal(_) => throw fatal }
              .flatMap(_ => throw fatal)
        }
        .map(_ => ())

    val shutdownTimeout = CoordinatedShutdown(actorSystem).totalTimeout() + Duration(5, TimeUnit.SECONDS)
    Await.result(
      stopped,
      shutdownTimeout
    )
  }
}

case object ClientStoppedReason extends CoordinatedShutdown.Reason

/**
 * Convenience for constructing service clients in a non Lagom server application.
 *
 * [[LagomClientFactory]] should be used only if your application DO have its own [[akka.actor.ActorSystem]] and Akka Streams [[akka.stream.Materializer]],
 * in which case you should reuse then when building a [[LagomClientFactory]].
 *
 * The easiest way to reuse your existing [[akka.actor.ActorSystem]] and Akka Stream [[akka.stream.Materializer]] is to extend the [[LagomClientFactory]]
 * and add a constructor where you can pass them as arguments (see example below).
 *
 * There is one more component that you’ll need to provide when creating a [[LagomClientFactory]], that is a service locator.
 * It is up to you what service locator you use, it could be a third party service locator, or a service locator created
 * from static configuration.
 *
 * Lagom provides a number of built-in service locators, including a [[StaticServiceLocator]], a [[RoundRobinServiceLocator]]
 * and a [[ConfigurationServiceLocator]]. The easiest way to use these is to mix in their respective Components traits.
 *
 * For example, here’s a client factory built using the static service locator, which uses a static URI,
 * and reusing an [[akka.actor.ActorSystem]] and Akka Streams [[akka.stream.Materializer]] created outside it:
 *
 * {{{
 * import java.net.URI
 * import com.lightbend.lagom.scaladsl.client._
 * import play.api.libs.ws.ahc.AhcWSComponents
 *
 * class MyLagomClientFactory(val actorSystem: ActorSystem, val materialzer: Materializer)
 *   extends LagomClientFactory("my-client")
 *   with StaticServiceLocatorComponents
 *   with AhcWSComponents {
 *
 *   override def staticServiceUri = URI.create("http://localhost:8080")
 * }
 *
 *
 * val actorSystem = ActorSystem("my-app")
 * val materializer = ActorMaterializer()(actorSystem)
 * val clientFactory = new MyLagomClientFactory(actorSystem, materializer)
 * }}}
 *
 * @param clientName The name of the service that is consuming the Lagom service. This will impact how calls made through clients
 *                   generated by this factory will identify themselves.
 * @param classLoader A classloader, it will be used to create the service proxy and needs to have the API for the client in it.
 */
abstract class LagomClientFactory(
    clientName: String,
    classLoader: ClassLoader = classOf[LagomClientFactory].getClassLoader
) extends LagomServiceClientComponents
    with LagomConfigComponent {
  private val defaultApplicationLifecycle = new DefaultApplicationLifecycle

  override lazy val serviceInfo: ServiceInfo = ServiceInfo(clientName, immutable.Seq.empty)
  override lazy val environment: Environment = Environment(new File("."), classLoader, Mode.Prod)

  lazy val configuration: Configuration = Configuration.load(
    environment.classLoader,
    System.getProperties,
    Map.empty,
    allowMissingApplicationConf = true
  )

  override lazy val applicationLifecycle: ApplicationLifecycle = defaultApplicationLifecycle
  override lazy val executionContext: ExecutionContext         = actorSystem.dispatcher

  /**
   * Override this method if your [[LagomClientFactory]] implementation needs to free any resource.
   *
   * For example, when implementing your own [[LagomClientFactory]], you may choose to reuse an existing [[akka.actor.ActorSystem]],
   * but use a internal [[akka.stream.Materializer]]. In which case, you can use this method to only shutdown the [[akka.stream.Materializer]].
   *
   * If you override this method, make sure you also release the internally managed resources
   * by calling [[#releaseInternalResources()]] method.
   *
   */
  def stop(): Unit = {
    Await.result(
      releaseInternalResources(),
      Duration(5, TimeUnit.SECONDS)
    )
  }

  /**
   * Releases the internal resources manages by this LagomClientFactory.
   * @return
   */
  protected final def releaseInternalResources(): Future[Done] =
    applicationLifecycle.stop().map(_ => Done)(executionContext)
}
