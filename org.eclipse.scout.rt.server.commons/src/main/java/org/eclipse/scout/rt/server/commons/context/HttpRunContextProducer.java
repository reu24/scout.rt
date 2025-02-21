/*
 * Copyright (c) 2010, 2025 BSI Business Systems Integration AG
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.scout.rt.server.commons.context;

import java.security.AccessController;

import javax.security.auth.Subject;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.scout.rt.platform.ApplicationScoped;
import org.eclipse.scout.rt.platform.BEANS;
import org.eclipse.scout.rt.platform.context.CorrelationId;
import org.eclipse.scout.rt.platform.context.RunContext;
import org.eclipse.scout.rt.platform.context.RunContexts;
import org.eclipse.scout.rt.platform.transaction.TransactionScope;
import org.eclipse.scout.rt.platform.util.StringUtility;
import org.eclipse.scout.rt.server.commons.servlet.IHttpServletRoundtrip;
import org.eclipse.scout.rt.server.commons.servlet.logging.ServletDiagnosticsProviderFactory;

/**
 * Creates a {@link RunContext} based on a {@link HttpServletRequest} and the current JAAS context.
 *
 * @since 9.0
 */
@ApplicationScoped
public class HttpRunContextProducer {

  private final ServletDiagnosticsProviderFactory m_servletDiagProviderFactory;
  private final CorrelationId m_correlationIdProvider;

  public HttpRunContextProducer() {
    m_servletDiagProviderFactory = createServletDiagnosticsProviderFactory();
    m_correlationIdProvider = createCorrelationId();
  }

  /**
   * Creates a new {@link RunContext} based on the {@link HttpServletRequest} specified.
   */
  public RunContext produce(HttpServletRequest req, HttpServletResponse resp) {
    return produce(req, resp, null);
  }

  /**
   * Fills the {@link RunContext} specified based on the values of the given {@link HttpServletRequest}.
   *
   * @param existing
   *     This is the context that should be extended with the attributes from the {@link HttpServletRequest}. May
   *     be {@code null}. In that case a new one is created.
   */
  public RunContext produce(HttpServletRequest req, HttpServletResponse resp, RunContext existing) {
    RunContext contextToFill = existing;
    if (contextToFill == null) {
      contextToFill = RunContexts.copyCurrent(true);
    }

    RunContext runContext = contextToFill
        .withSubject(Subject.getSubject(AccessController.getContext()))
        .withTransactionScope(TransactionScope.REQUIRES_NEW);
    if (req != null) { // FIXME
      runContext = runContext
          .withLocale(req.getLocale())
          .withCorrelationId(currentCorrelationId(req))
          .withThreadLocal(IHttpServletRoundtrip.CURRENT_HTTP_SERVLET_REQUEST, req);
    }
    if (resp != null) { // FIXME
      runContext = runContext.withThreadLocal(IHttpServletRoundtrip.CURRENT_HTTP_SERVLET_RESPONSE, resp);
    }
    if (req != null && resp != null) {  // FIXME
      runContext = runContext.withDiagnostics(getServletDiagnosticsProviderFactory().getProviders(req, resp));
    }

    return runContext;
  }

  protected String currentCorrelationId(HttpServletRequest req) {
    String cid = req.getHeader(CorrelationId.HTTP_HEADER_NAME);
    if (StringUtility.hasText(cid)) {
      return cid;
    }
    return getCorrelationIdProvider().newCorrelationId();
  }

  protected ServletDiagnosticsProviderFactory createServletDiagnosticsProviderFactory() {
    return BEANS.get(ServletDiagnosticsProviderFactory.class);
  }

  protected CorrelationId createCorrelationId() {
    return BEANS.get(CorrelationId.class);
  }

  protected CorrelationId getCorrelationIdProvider() {
    return m_correlationIdProvider;
  }

  protected ServletDiagnosticsProviderFactory getServletDiagnosticsProviderFactory() {
    return m_servletDiagProviderFactory;
  }
}
