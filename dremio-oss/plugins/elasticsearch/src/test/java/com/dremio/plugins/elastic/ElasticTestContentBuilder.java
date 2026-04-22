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
package com.dremio.plugins.elastic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.joda.time.DateTime;

/*
Replaces XContentBuilder in Elasticsearch using Google Gson
*/
public class ElasticTestContentBuilder {
  private StringBuilder sb = new StringBuilder();
  private boolean commaNeeded = false;

  // The tests appear to use LocalDate, LocalDateTime and JodaTime
  Gson gson =
      new GsonBuilder()
          .registerTypeAdapter(LocalDate.class, new LocalDateTypeAdapter())
          .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeTypeAdapter())
          .registerTypeAdapter(DateTime.class, new JodaTimeAdapter())
          .create();

  public ElasticTestContentBuilder field(String name, Object value) {
    String start = commaNeeded ? "," : "";
    commaNeeded = true;
    sb.append(start).append(gson.toJson(name)).append(":");
    sb.append(gson.toJson(value));
    return this;
  }

  public ElasticTestContentBuilder startObject(String name) {
    String start = commaNeeded ? "," : "";
    commaNeeded = false;
    sb.append(start).append(gson.toJson(name)).append(":{");
    return this;
  }

  public ElasticTestContentBuilder startObject() {
    String start = commaNeeded ? "," : "";
    commaNeeded = false;
    sb.append(start).append("{");
    return this;
  }

  public ElasticTestContentBuilder endObject() {
    commaNeeded = true;
    sb.append("}");
    return this;
  }

  public ElasticTestContentBuilder startArray(String name) {
    String start = commaNeeded ? "," : "";
    commaNeeded = false;
    sb.append(start).append(gson.toJson(name)).append(":[");
    return this;
  }

  public ElasticTestContentBuilder endArray() {
    commaNeeded = true;
    sb.append("]");
    return this;
  }

  public String build() {
    return sb.toString();
  }

  @Override
  public String toString() {
    return build();
  }

  private static final class LocalDateTypeAdapter
      implements JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public JsonElement serialize(
        final LocalDate date, final Type typeOfSrc, final JsonSerializationContext context) {
      return new JsonPrimitive(date.format(formatter));
    }

    @Override
    public LocalDate deserialize(
        final JsonElement json, final Type typeOfT, final JsonDeserializationContext context)
        throws JsonParseException {
      return LocalDate.parse(json.getAsString(), formatter);
    }
  }

  private static final class LocalDateTimeTypeAdapter
      implements JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {

    private final DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    @Override
    public JsonElement serialize(
        final LocalDateTime date, final Type typeOfSrc, final JsonSerializationContext context) {
      return new JsonPrimitive(date.format(formatter));
    }

    @Override
    public LocalDateTime deserialize(
        final JsonElement json, final Type typeOfT, final JsonDeserializationContext context)
        throws JsonParseException {
      return LocalDateTime.parse(json.getAsString(), formatter);
    }
  }

  private static final class JodaTimeAdapter
      implements JsonSerializer<DateTime>, JsonDeserializer<DateTime> {
    @Override
    public JsonElement serialize(
        final DateTime date, final Type typeOfSrc, final JsonSerializationContext context) {
      return new JsonPrimitive(date.toString());
    }

    @Override
    public DateTime deserialize(
        final JsonElement json, final Type typeOfT, final JsonDeserializationContext context)
        throws JsonParseException {
      return DateTime.parse(json.getAsString());
    }
  }
}
