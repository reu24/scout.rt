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

import java.io.InputStream;
import java.util.Map;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation.Builder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.scout.rt.platform.BEANS;
import org.eclipse.scout.rt.rest.client.IRestClientHelper;
import org.eclipse.scout.rt.rest.client.IRestResourceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessResourceClient implements IRestResourceClient {

  protected static final String RESOURCE_PATH = "process";

  private static final Logger LOG = LoggerFactory.getLogger(ProcessResourceClient.class);

  public Response call(Map<String, String> headers, InputStream inputStream) {
    WebTarget target = helper().target(RESOURCE_PATH);

    Builder builder = target
        .request()
        .accept(MediaType.APPLICATION_OCTET_STREAM);

    // add headers
    headers.forEach((k, v) -> builder.header(k, v));

    return builder
        .post(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM));
  }

  protected IRestClientHelper helper() {
    return BEANS.get(ProcessRestClientHelper.class);
  }
}
