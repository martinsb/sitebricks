package com.google.sitebricks.client;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Realm;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import net.jcip.annotations.ThreadSafe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * @author Jeanfrancois Arcand (jfarcand@apache.org)
 * @author Jason van Zyl
 */
@ThreadSafe
class AHCWebClient<T> implements WebClient<T> {
  private final String url;
  private final Map<String, String> headers;
  private final TypeLiteral<T> typeToTransform;
  private final AsyncHttpClient httpClient;
  private final Transport transport;
  private final Injector injector;

  public AHCWebClient(Injector injector, Transport transport, Web.Auth authType, String username, String password, boolean usePreemptiveAuth, String url, Map<String, String> headers, TypeLiteral<T> typeToTransform) {
    this.injector = injector;
    this.url = url;
    this.headers = (null == headers) ? null : ImmutableMap.copyOf(headers);
    this.typeToTransform = typeToTransform;
    this.transport = transport;

    // configure auth
    AsyncHttpClientConfig.Builder c = new AsyncHttpClientConfig.Builder();
    if (null != authType) {
      Realm.RealmBuilder b = new Realm.RealmBuilder();
      // TODO: Add support for Kerberos and SPNEGO
      Realm.AuthScheme scheme = authType.equals(Web.Auth.BASIC) ? Realm.AuthScheme.BASIC : Realm.AuthScheme.DIGEST;
      b.setPrincipal(username).setPassword(password).setScheme(scheme).setUsePreemptiveAuth(usePreemptiveAuth);
      c.setRealm(b.build());
    }

    this.httpClient = new AsyncHttpClient(c.build());
  }

  private WebResponse simpleRequest(RequestBuilder requestBuilder) {
    requestBuilder = addHeadersToRequestBuilder(requestBuilder);

    try {
      Response r = httpClient.executeRequest(requestBuilder.build()).get();
      return new WebResponseImpl(injector, r);
    } catch (IOException e) {
      throw new TransportException(e);
    } catch (InterruptedException e) {
      throw new TransportException(e);
    } catch (ExecutionException e) {
      throw new TransportException(e);
    }
  }

  private ListenableFuture<WebResponse> simpleAsyncRequest(RequestBuilder requestBuilder, Executor executor) {
    requestBuilder = addHeadersToRequestBuilder(requestBuilder);

    try {
      final SettableFuture<WebResponse> future = SettableFuture.create();
      final com.ning.http.client.ListenableFuture<Response> responseFuture = httpClient.executeRequest(
          requestBuilder.build());
      responseFuture.addListener(new Runnable() {
        @Override
        public void run() {
          try {
            future.set(new WebResponseImpl(injector, responseFuture.get()));
          } catch (InterruptedException e) {
            throw new TransportException(e);
          } catch (ExecutionException e) {
            throw new TransportException(e);
          }
        }
      }, executor);

      return future;
    } catch (IOException e) {
      throw new TransportException(e);
    }
  }

  private WebResponse request(RequestBuilder requestBuilder, T t) {
    requestBuilder = addHeadersToRequestBuilder(requestBuilder);

    try {
      //
      // Read the entity from the transport plugin.
      //
      final ByteArrayOutputStream stream = new ByteArrayOutputStream();
      transport.out(stream, typeToTransform.getRawType(), t);

      // TODO worry about endian issues? Or will Content-Encoding be sufficient?
      // OOM if the stream is too bug
      final byte[] outBuffer = stream.toByteArray();

      //
      // Set request body
      //
      requestBuilder.setBody(outBuffer);
      Response r = httpClient.executeRequest(requestBuilder.build()).get();
      return new WebResponseImpl(injector, r);
    } catch (IOException e) {
      throw new TransportException(e);
    } catch (InterruptedException e) {
      throw new TransportException(e);
    } catch (ExecutionException e) {
      throw new TransportException(e);
    }
  }

  @SuppressWarnings("deprecation")
  private ListenableFuture<WebResponse> requestAsync(RequestBuilder requestBuilder, T t,
                                                     Executor executor) {
    requestBuilder = addHeadersToRequestBuilder(requestBuilder);

    try {
      final SettableFuture<WebResponse> future = SettableFuture.create();


      //
      // Read the entity from the transport plugin.
      //
      InputStream in;
      if (t instanceof InputStream)
        in = (InputStream) t;
      else {
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        transport.out(stream, typeToTransform.getRawType(), t);

        in = new ByteArrayInputStream(stream.toByteArray());
      }

      //
      // Set request body
      //
      requestBuilder.setBody(in);

      final com.ning.http.client.ListenableFuture<Response> responseFuture = httpClient.executeRequest(
          requestBuilder.build());
      responseFuture.addListener(new Runnable() {
        @Override
        public void run() {
          try {
            future.set(new WebResponseImpl(injector, responseFuture.get()));
          } catch (InterruptedException e) {
            throw new TransportException(e);
          } catch (ExecutionException e) {
            throw new TransportException(e);
          }
        }
      }, executor);
      return future;
    } catch (IOException e) {
      throw new TransportException(e);
    }
  }

  private RequestBuilder addHeadersToRequestBuilder(RequestBuilder requestBuilder) {
    //
    // The user may wish to override the Content-Type header for whatever reason. If they do so we just honour that header and make
    // sure we don't trample that header with the default Content-Type header as provided by the Transport.
    //
    boolean contentTypeOverriddenInHeaders = false;

    if (null != headers) {
      for (Map.Entry<String, String> header : headers.entrySet()) {
        if (header.getKey().toLowerCase().equals("content-type")) {
          contentTypeOverriddenInHeaders = true;
        }
        requestBuilder.addHeader(header.getKey(), header.getValue());
      }
    }

    if (!contentTypeOverriddenInHeaders) {
      //
      // Set the Content-Type as specified by the Transport. For example if we're using the Json transport the Content-Type header
      // will be set to application/json.
      //
      requestBuilder.addHeader("Content-Type", transport.contentType());
    }

    return requestBuilder;
  }

  public WebResponse get() {
    return simpleRequest(new RequestBuilder("GET").setUrl(url));
  }

  public WebResponse post(T t) {
    return request(new RequestBuilder("POST").setUrl(url), t);
  }

  public WebResponse put(T t) {
    return request(new RequestBuilder("PUT").setUrl(url), t);
  }

  public WebResponse patch(T t) {
    return request(new RequestBuilder("PATCH").setUrl(url), t);
  }

  public WebResponse delete() {
    return simpleRequest(new RequestBuilder("DELETE").setUrl(url));
  }

  @Override
  public ListenableFuture<WebResponse> get(Executor executor) {
    return simpleAsyncRequest(new RequestBuilder("GET").setUrl(url), executor);
  }

  @Override
  public ListenableFuture<WebResponse> post(T t, Executor executor) {
    return requestAsync(new RequestBuilder("POST").setUrl(url), t, executor);
  }

  @Override
  public ListenableFuture<WebResponse> put(T t, Executor executor) {
    return requestAsync(new RequestBuilder("PUT").setUrl(url), t, executor);
  }

  @Override
  public ListenableFuture<WebResponse> patch(T t, Executor executor) {
    return requestAsync(new RequestBuilder("PATCH").setUrl(url), t, executor);
  }

  @Override
  public ListenableFuture<WebResponse> delete(Executor executor) {
    return simpleAsyncRequest(new RequestBuilder("DELETE").setUrl(url), executor);
  }

  @Override
  public void close() {
    httpClient.close();
  }
}
