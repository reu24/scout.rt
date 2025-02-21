/*
 * Copyright (c) 2010, 2025 BSI Business Systems Integration AG
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.scout.rt.shared.servicetunnel.rest;

import java.util.List;

import jakarta.ws.rs.client.ClientRequestFilter;

import org.eclipse.scout.rt.platform.ApplicationScoped;
import org.eclipse.scout.rt.platform.BEANS;
import org.eclipse.scout.rt.platform.config.CONFIG;
import org.eclipse.scout.rt.rest.client.AbstractRestClientHelper;
import org.eclipse.scout.rt.shared.SharedConfigProperties;

@ApplicationScoped
public class ProcessRestClientHelper extends AbstractRestClientHelper {

  @Override
  protected String getBaseUri() {
    return CONFIG.getPropertyValue(SharedConfigProperties.BackendUrlProperty.class) + "/api";
  }

  @Override
  protected List<ClientRequestFilter> getRequestFiltersToRegister() {
    List<ClientRequestFilter> filters = super.getRequestFiltersToRegister();
    filters.add(BEANS.get(AuthenticationTokenClientRequestFilter.class));
    //filters.add(new RestRequestCancellationClientRequestFilter(this::cancelRequest)); FIXME
    //filters.add(BEANS.get(IdSignatureClientRequestFilter.class)); -> do not register this filter; process resource will determine on its own if header should be added
    return filters;
  }
}
