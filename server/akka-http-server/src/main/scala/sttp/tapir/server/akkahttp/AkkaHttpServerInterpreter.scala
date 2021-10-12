package sttp.tapir.server.akkahttp

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{
  complete,
  extractExecutionContext,
  extractMaterializer,
  extractRequestContext,
  handleWebSocketMessages,
  onSuccess,
  reject,
  respondWithHeaders
}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import sttp.capabilities.WebSockets
import sttp.capabilities.akka.AkkaStreams
import sttp.model.{HeaderNames, Method}
import sttp.monad.FutureMonad
import sttp.tapir.Endpoint
import sttp.tapir.model.ServerResponse
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.akkahttp.AkkaModel.parseHeadersOrThrowWithoutContentHeaders
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.interpreter.{BodyListener, ServerInterpreter}

import scala.concurrent.Future
import scala.reflect.ClassTag

trait AkkaHttpServerInterpreter {

  def akkaHttpServerOptions: AkkaHttpServerOptions = AkkaHttpServerOptions.default

  def toRoute[I, E, O](e: Endpoint[I, E, O, AkkaStreams with WebSockets])(logic: I => Future[Either[E, O]]): Route =
    toRoute(e.serverLogic(logic))

  def toRouteRecoverErrors[I, E, O](
      e: Endpoint[I, E, O, AkkaStreams with WebSockets]
  )(logic: I => Future[O])(implicit eIsThrowable: E <:< Throwable, eClassTag: ClassTag[E]): Route =
    toRoute(e.serverLogicRecoverErrors(logic))

  def toRoute[I, E, O](se: ServerEndpoint[I, E, O, AkkaStreams with WebSockets, Future]): Route = toRoute(List(se))

  def toRoute(ses: List[ServerEndpoint[_, _, _, AkkaStreams with WebSockets, Future]]): Route = {
    extractRequestContext { ctx =>
      extractExecutionContext { implicit ec =>
        extractMaterializer { implicit mat =>
          implicit val monad: FutureMonad = new FutureMonad()
          implicit val bodyListener: BodyListener[Future, AkkaResponseBody] = new AkkaBodyListener
          val serverRequest = new AkkaServerRequest(ctx)
          val interpreter = new ServerInterpreter(
            new AkkaRequestBody(ctx, serverRequest, akkaHttpServerOptions),
            new AkkaToResponseBody,
            akkaHttpServerOptions.interceptors,
            akkaHttpServerOptions.deleteFile
          )

          onSuccess(interpreter(serverRequest, ses)) {
            case RequestResult.Failure(_)         => reject
            case RequestResult.Response(response) => serverResponseToAkka(response, serverRequest.method)
          }
        }
      }
    }
  }

  private def serverResponseToAkka(response: ServerResponse[AkkaResponseBody], requestMethod: Method): Route = {
    val statusCode = StatusCodes.getForKey(response.code.code).getOrElse(StatusCodes.custom(response.code.code, ""))
    val akkaHeaders = parseHeadersOrThrowWithoutContentHeaders(response)

    response.body match {
      case Some(Left(flow)) =>
        respondWithHeaders(akkaHeaders) {
          handleWebSocketMessages(flow)
        }
      case Some(Right(entity)) =>
        complete(HttpResponse(entity = entity, status = statusCode, headers = akkaHeaders))
      case None =>
        if (requestMethod.is(Method.HEAD)) {
          val contentLength: Long = response.contentLength.getOrElse(0)
          val contentType: ContentType = response.contentType match {
            case Some(t) => ContentType.parse(t).getOrElse(ContentTypes.NoContentType)
            case None    => ContentTypes.NoContentType
          }
          complete(
            HttpResponse(
              status = statusCode,
              headers = akkaHeaders,
              entity = HttpEntity.Default(contentType, contentLength, Source.empty)
            )
          )
        } else complete(HttpResponse(statusCode, headers = akkaHeaders))
    }
  }
}

object AkkaHttpServerInterpreter {

  def apply(serverOptions: AkkaHttpServerOptions = AkkaHttpServerOptions.default): AkkaHttpServerInterpreter = {
    new AkkaHttpServerInterpreter {
      override def akkaHttpServerOptions: AkkaHttpServerOptions = serverOptions
    }
  }
}
