/*
 * Copyright (c) 2010, 2025 BSI Business Systems Integration AG
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.scout.rt.shared.servicetunnel.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import jakarta.ws.rs.core.Response;

import org.eclipse.scout.rt.platform.BEANS;
import org.eclipse.scout.rt.platform.context.CorrelationId;
import org.eclipse.scout.rt.platform.context.RunContext;
import org.eclipse.scout.rt.platform.context.RunMonitor;
import org.eclipse.scout.rt.platform.job.IFuture;
import org.eclipse.scout.rt.platform.job.Jobs;
import org.eclipse.scout.rt.platform.util.UriUtility;
import org.eclipse.scout.rt.platform.util.concurrent.FutureCancelledError;
import org.eclipse.scout.rt.platform.util.concurrent.ICancellable;
import org.eclipse.scout.rt.platform.util.concurrent.ThreadInterruptedError;
import org.eclipse.scout.rt.shared.SharedConfigProperties.ServiceTunnelTargetUrlProperty;
import org.eclipse.scout.rt.shared.http.IHttpTransportManager;
import org.eclipse.scout.rt.shared.opentelemetry.HttpServiceTunnelInstrumenterFactory;
import org.eclipse.scout.rt.shared.servicetunnel.AbstractServiceTunnel;
import org.eclipse.scout.rt.shared.servicetunnel.BinaryServiceTunnelContentHandler;
import org.eclipse.scout.rt.shared.servicetunnel.IServiceTunnelContentHandler;
import org.eclipse.scout.rt.shared.servicetunnel.ServiceTunnelOptions;
import org.eclipse.scout.rt.shared.servicetunnel.ServiceTunnelRequest;
import org.eclipse.scout.rt.shared.servicetunnel.ServiceTunnelResponse;
import org.eclipse.scout.rt.shared.servicetunnel.rest.ProcessResourceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;

/**
 * Abstract tunnel used to invoke a service through HTTP.
 */
public class HttpServiceTunnel extends AbstractServiceTunnel {
  private static final Logger LOG = LoggerFactory.getLogger(HttpServiceTunnel.class);

  public static final String TOKEN_AUTH_HTTP_HEADER = "X-ScoutAccessToken";
  public static final String ID_SIGNATURE_HTTP_HEADER = "X-ScoutIdSignature";

  /**
   * Marker header for session-less requests.
   */
  public static final String WITHOUT_SESSION_HEADER = "X-WithoutSession";

  private IServiceTunnelContentHandler m_contentHandler;
  private final URL m_serverUrl;
  private final GenericUrl m_genericUrl;
  private final boolean m_active;

  private final Instrumenter<ServiceTunnelRequest, Void> m_instrumenter;

  public HttpServiceTunnel() {
    this(getConfiguredServerUrl());
  }

  public HttpServiceTunnel(URL url) {
    m_serverUrl = url;
    m_genericUrl = url != null ? new GenericUrl(url) : null;
    m_active = url != null;
    m_instrumenter = BEANS.get(HttpServiceTunnelInstrumenterFactory.class).createInstrumenter();
  }

  protected static URL getConfiguredServerUrl() {
    String url = BEANS.get(ServiceTunnelTargetUrlProperty.class).getValue();
    try {
      return UriUtility.toUrl(url);
    }
    catch (RuntimeException e) {
      throw new IllegalArgumentException("targetUrl: " + url, e);
    }
  }

  @Override
  public boolean isActive() {
    return m_active;
  }

  public GenericUrl getGenericUrl() {
    return m_genericUrl;
  }

  public URL getServerUrl() {
    return m_serverUrl;
  }

  /**
   * Execute a {@link ServiceTunnelRequest}, returns the plain {@link HttpResponse} - (executed and) ready to be
   * processed to create a {@link ServiceTunnelResponse}.
   *
   * @param call
   *     the original call
   * @param callData
   *     the data created by the {@link IServiceTunnelContentHandler} used by this tunnel Create url connection and
   *     write post data (if required)
   * @throws IOException
   *     override this method to customize the creation of the {@link HttpResponse} see
   *     {@link #addCustomHeaders(Map, ServiceTunnelRequest, byte[])}
   */
  protected Response executeRequestInternal(ServiceTunnelRequest call, byte[] callData) throws IOException {
    // fast check of wrong URL's for this tunnel
    if (!"http".equalsIgnoreCase(getServerUrl().getProtocol()) && !"https".equalsIgnoreCase(getServerUrl().getProtocol())) {
      throw new IOException("URL '" + getServerUrl().toString() + "' is not supported by this tunnel ('" + getClass().getName() + "').");
    }
    if (!isActive()) {
      String key = BEANS.get(ServiceTunnelTargetUrlProperty.class).getKey();
      throw new IllegalArgumentException("No target URL configured. Please specify a target URL in the config.properties using property '" + key + "'.");
    }

    Map<String, String> headers = new HashMap<>();
    headers.put("Cache-Control", "no-cache"); // HTTP 1.1
    headers.put("Pragma", "no-cache"); // HTTP 1.0
    addCustomHeaders(headers, call, callData);

    return BEANS.get(ProcessResourceClient.class).call(headers, new ByteArrayInputStream(callData));
  }

  protected Response executeRequest(ServiceTunnelRequest call, byte[] callData) throws IOException {
    Context parentContext = Context.current();

    if (!m_instrumenter.shouldStart(parentContext, call)) {
      return executeRequestInternal(call, callData);
    }

    Context context = m_instrumenter.start(parentContext, call);
    Response response;
    try (Scope ignored = context.makeCurrent()) {
      response = executeRequestInternal(call, callData);
    }
    catch (Throwable t) {
      m_instrumenter.end(context, call, null, t);
      throw t;
    }
    m_instrumenter.end(context, call, null, null);
    return response;
  }

  /**
   * @return the {@link IHttpTransportManager}
   */
  protected IHttpTransportManager getHttpTransportManager() {
    return BEANS.get(HttpServiceTunnelTransportManager.class);
  }

  /**
   * @param headers
   *     headers
   * @param call
   *     request information
   * @param callData
   *     data as byte array
   * @throws IOException
   *     exception
   * @since 6.0
   */
  protected void addCustomHeaders(Map<String, String> headers, ServiceTunnelRequest call, byte[] callData) throws IOException {
    addSignatureHeader(headers, callData);
    addCorrelationId(headers);
    addOpenTelemetryContextHeader(headers);
    addIdSignatureHeader(headers);
  }

  protected void addSignatureHeader(Map<String, String> headers, byte[] callData) throws IOException {
    try {
      DefaultAuthToken token = BEANS.get(DefaultAuthTokenSigner.class).createDefaultSignedToken(DefaultAuthToken.class);
      if (token != null) {
        headers.put(TOKEN_AUTH_HTTP_HEADER, token.toString());
      }
    }
    catch (RuntimeException e) {
      throw new IOException(e);
    }
  }

  /**
   * Method invoked to add the <em>correlation ID</em> as HTTP header to the request.
   */
  protected void addCorrelationId(Map<String, String> headers) {
    final String cid = CorrelationId.CURRENT.get();
    if (cid != null) {
      headers.put(CorrelationId.HTTP_HEADER_NAME, cid);
    }
  }

  protected void addOpenTelemetryContextHeader(Map<String, String> headers) {
    TextMapSetter<Map<String, String>> setter = (carrier, key, value) -> {
      if (carrier != null) {
        headers.put(key, value);
      }
    };
    GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
        .inject(Context.current(), headers, setter);
  }

  /**
   * Adds the {@link HttpServiceTunnel#ID_SIGNATURE_HTTP_HEADER} if the current run context has the property {@link ServiceTunnelOptions#ID_SIGNATURE_PROP} set.
   */
  protected void addIdSignatureHeader(Map<String, String> headers) {
    if (Optional.ofNullable(RunContext.CURRENT.get())
        .map(rc -> rc.getPropertyOrDefault(ServiceTunnelOptions.ID_SIGNATURE_PROP, false))
        .orElse(false)) {
      headers.put(ID_SIGNATURE_HTTP_HEADER, Boolean.TRUE.toString());
    }
  }

  /**
   * @return msgEncoder used to encode and decode a request / response to and from the binary stream. Default is the
   * {@link BinaryServiceTunnelContentHandler} which handles binary messages
   */
  public IServiceTunnelContentHandler getContentHandler() {
    return m_contentHandler;
  }

  /**
   * @param e
   *     content handler that can encode and decode a request / response to and from the binary stream. Default is
   *     the {@link BinaryServiceTunnelContentHandler} which handles binary messages
   */
  public void setContentHandler(IServiceTunnelContentHandler e) {
    m_contentHandler = e;
  }

  @Override
  public Object invokeService(Class serviceInterfaceClass, Method operation, Object[] callerArgs) {
    if (m_contentHandler == null) {
      m_contentHandler = BEANS.get(IServiceTunnelContentHandler.class);
      m_contentHandler.initialize();
    }
    return super.invokeService(serviceInterfaceClass, operation, callerArgs);
  }

  /**
   * Creates the {@link Callable} to invoke the remote service operation described by 'serviceRequest'.
   * <p>
   * To enable cancellation, the callable returned must also implement {@link ICancellable}, so that the remote
   * operation can be cancelled once the current {@link RunMonitor} gets cancelled.
   */
  protected RemoteServiceInvocationCallable createRemoteServiceInvocationCallable(ServiceTunnelRequest serviceRequest) {
    return new RemoteServiceInvocationCallable(this, serviceRequest);
  }

  @Override
  protected ServiceTunnelResponse tunnel(final ServiceTunnelRequest serviceRequest) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("requestSequence {} {}.{}", serviceRequest.getRequestSequence(), serviceRequest.getServiceInterfaceClassName(), serviceRequest.getOperation());
    }
    final long requestSequence = serviceRequest.getRequestSequence();

    // Create the Callable to be given to the job manager for execution.
    final RemoteServiceInvocationCallable remoteInvocationCallable = createRemoteServiceInvocationCallable(serviceRequest);

    // Register the execution monitor as child monitor of the current monitor so that the service request is cancelled once the current monitor gets cancelled.
    // Invoke the service operation asynchronously (to enable cancellation) and wait until completed or cancelled.
    final IFuture<ServiceTunnelResponse> future = Jobs
        .schedule(remoteInvocationCallable,
            Jobs.newInput().withRunContext(RunContext.CURRENT.get().copy())
                .withName(createServiceRequestName(requestSequence))
                .withExceptionHandling(null, false)) // do not handle uncaught exceptions because typically invoked from within a model job (might cause a deadlock, because ClientExceptionHandler schedules and waits for a model job to visualize the exception).
        .whenDone(event -> {
          if (event.isCancelled()) {
            remoteInvocationCallable.cancel();
          }
        }, RunContext.CURRENT.get().copy()
            .withRunMonitor(BEANS.get(RunMonitor.class))); // separate monitor to not cancel this cancellation action.

    try {
      return future.awaitDoneAndGet();
    }
    catch (ThreadInterruptedError e) { // NOSONAR
      future.cancel(true); // Ensure the monitor to be cancelled once this thread is interrupted to cancel the remote call.
      return new ServiceTunnelResponse(new ThreadInterruptedError("UserInterrupted")); // Interruption has precedence over computation result or computation error.
    }
    catch (FutureCancelledError e) { // NOSONAR
      return new ServiceTunnelResponse(new FutureCancelledError("UserInterrupted")); // Cancellation has precedence over computation result or computation error.
    }
  }

  /**
   * This method is called just after the HTTP response is received, but before being processed, and might be used to
   * read and interpret custom HTTP headers.
   */
  protected void interceptHttpResponse(HttpResponse httpResponse, ServiceTunnelRequest call) {
    // subclasses may intercept HTTP response
  }

  /**
   * Returns the name to decorate the thread's name while executing the service request.
   */
  protected String createServiceRequestName(final long requestSequence) {
    final IFuture<?> currentFuture = IFuture.CURRENT.get();
    final String submitter = (currentFuture != null ? currentFuture.getJobInput().getName() : Thread.currentThread().getName());
    return String.format("Tunneling service request [seq=%s, submitter=%s]", requestSequence, submitter);
  }
}
