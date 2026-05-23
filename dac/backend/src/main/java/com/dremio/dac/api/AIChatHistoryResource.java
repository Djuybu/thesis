/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.dac.api;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import com.dremio.dac.annotations.APIResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.datastore.api.LegacyKVStoreCreationFunction;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.datastore.api.LegacyStoreBuildingFactory;
import com.dremio.datastore.format.Format;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.SecurityContext;

/** Stores AI chatbot sessions in Dremio's internal KV store, scoped by authenticated user. */
@APIResource
@Secured
@RolesAllowed({"admin", "user"})
@Path("/aichat-history")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class AIChatHistoryResource {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String EMPTY_SESSIONS = "[]";

  private final Provider<LegacyKVStoreProvider> kvStoreProvider;
  private final SecurityContext securityContext;

  @Inject
  public AIChatHistoryResource(
      Provider<LegacyKVStoreProvider> kvStoreProvider, SecurityContext securityContext) {
    this.kvStoreProvider = kvStoreProvider;
    this.securityContext = securityContext;
  }

  @GET
  @Path("/sessions")
  public String getSessions() {
    final String sessions = sessionsStore().get(currentUserName());
    return sessions == null ? EMPTY_SESSIONS : sessions;
  }

  @PUT
  @Path("/sessions")
  public String saveSessions(String sessionsJson) {
    validateSessionsJson(sessionsJson);
    sessionsStore().put(currentUserName(), sessionsJson);
    return sessionsJson;
  }

  private LegacyKVStore<String, String> sessionsStore() {
    return kvStoreProvider.get().getStore(ChatbotSessionsStoreCreator.class);
  }

  private String currentUserName() {
    return securityContext.getUserPrincipal().getName();
  }

  private static void validateSessionsJson(String sessionsJson) {
    if (sessionsJson == null || sessionsJson.trim().isEmpty()) {
      throw new BadRequestException("Session payload must be a JSON array.");
    }

    try {
      final JsonNode root = OBJECT_MAPPER.readTree(sessionsJson);
      if (!root.isArray()) {
        throw new BadRequestException("Session payload must be a JSON array.");
      }
    } catch (IOException e) {
      throw new BadRequestException("Session payload must be valid JSON.", e);
    }
  }

  /** KV store for one serialized ChatSession[] payload per Dremio user. */
  public static final class ChatbotSessionsStoreCreator
      implements LegacyKVStoreCreationFunction<String, String> {
    public static final String TABLE_NAME = "aichatbotSessions";

    @Override
    public LegacyKVStore<String, String> build(LegacyStoreBuildingFactory factory) {
      return factory
          .<String, String>newStore()
          .name(TABLE_NAME)
          .keyFormat(Format.ofString())
          .valueFormat(Format.ofString())
          .build();
    }
  }
}
