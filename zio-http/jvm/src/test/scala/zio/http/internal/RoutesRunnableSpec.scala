/*
 * Copyright 2021 - 2023 Sporta Technologies PVT LTD & the ZIO HTTP contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.http.internal

import zio.{EnvironmentTag, Scope, ZIO}

import zio.http.URL.Location
import zio.http._

/**
 * Should be used only when e2e tests needs to be written. Typically, we would
 * want to do that when we want to test the logic that is part of the netty
 * based backend. For most of the other use cases directly running the Routes
 * should suffice. RoutesRunnableSpec spins of an actual Http server and makes
 * requests.
 */
abstract class RoutesRunnableSpec extends ZIOHttpSpec { self =>
  implicit class RunnableHttpClientAppSyntax[R](route: Routes[R, Response]) {

    def routes: Routes[R, Response] = route

    /**
     * Deploys the http application on the test server and returns a
     * [[Handler]]. This allows us to assert using all the powerful operators
     * that are available on [[Handler]] while writing tests. It also allows us
     * to simply pass a request in the end, to execute, and resolve it with a
     * response. Just like with [[Routes]]
     */
    def deploy: Handler[DynamicServer with R with Client, Throwable, Request, Response] =
      for {
        port     <- Handler.fromZIO(DynamicServer.port)
        id       <- Handler.fromZIO(DynamicServer.deploy[R](routes))
        client   <- Handler.fromZIO(ZIO.service[Client])
        response <- Handler.fromFunctionZIO[Request] { params =>
          client.batched(
            params
              .addHeader(DynamicServer.APP_ID, id)
              .copy(url = URL(params.url.path, Location.Absolute(Scheme.HTTP, "localhost", Some(port)))),
          )
        }
      } yield response

    def deployAndRequest[R0, Output](
      call: Client => ZIO[R0, Throwable, Output],
    ): Handler[Client with DynamicServer with R with R0, Throwable, Any, Output] =
      for {
        port     <- Handler.fromZIO(DynamicServer.port)
        id       <- Handler.fromZIO(DynamicServer.deploy[R](routes))
        client   <- Handler.fromZIO(ZIO.service[Client])
        response <- Handler.fromZIO(
          call(
            client
              .addHeader(DynamicServer.APP_ID, id)
              .url(URL.decode(s"http://localhost:$port").toOption.get),
          ),
        )
      } yield response

    def deployChunked =
      for {
        port     <- Handler.fromZIO(DynamicServer.port)
        id       <- Handler.fromZIO(DynamicServer.deploy(routes))
        client   <- Handler.fromZIO(ZIO.service[Client])
        response <- Handler.fromFunctionZIO[Request] { params =>
          client(
            params
              .addHeader(DynamicServer.APP_ID, id)
              .copy(url = URL(params.url.path, Location.Absolute(Scheme.HTTP, "localhost", Some(port)))),
          )
        }
      } yield response

    def deployWS
      : Handler[R with Client with DynamicServer with Scope, Throwable, WebSocketApp[Client with Scope], Response] =
      for {
        id       <- Handler.fromZIO(DynamicServer.deploy[R](routes))
        rawUrl   <- Handler.fromZIO(DynamicServer.wsURL)
        url      <- Handler.fromEither(URL.decode(rawUrl)).orDie
        client   <- Handler.fromZIO(ZIO.service[Client])
        response <- Handler.fromFunctionZIO[WebSocketApp[Client with Scope]] { app =>
          ZIO.scoped[Client with Scope](
            client
              .url(url)
              .addHeaders(Headers(DynamicServer.APP_ID, id))
              .socket(app),
          )
        }
      } yield response
  }

  def serve: ZIO[DynamicServer with Server, Nothing, Int] =
    for {
      server <- ZIO.service[Server]
      ds     <- ZIO.service[DynamicServer]
      handler = DynamicServer.handler(ds)
      port <- Server.installRoutes(handler.toRoutes)
      _    <- DynamicServer.setStart(server)
    } yield port

  def serve[R: EnvironmentTag](routes: Routes[R, Response]): ZIO[R with DynamicServer with Server, Nothing, Int] =
    for {
      server <- ZIO.service[Server]
      port   <- Server.installRoutes(routes)
      _      <- DynamicServer.setStart(server)
    } yield port

  def status(
    method: Method = Method.GET,
    path: Path,
  ): ZIO[Client with DynamicServer, Throwable, Status] = {
    for {
      port   <- DynamicServer.port
      client <- ZIO.service[Client]
      url = URL.decode("http://localhost:%d/%s".format(port, path)).toOption.get
      status <- client.batched(Request(method = method, url = url)).map(_.status)
    } yield status
  }

  def headers(
    method: Method = Method.GET,
    path: Path,
    headers: Headers = Headers.empty,
  ): ZIO[Client with DynamicServer, Throwable, Headers] = {
    for {
      port <- DynamicServer.port
      url = URL.decode("http://localhost:%d/%s".format(port, path)).toOption.get
      headers <- ZClient.batched(Request(method = method, headers = headers, url = url)).map(_.headers)
    } yield headers
  }
}
