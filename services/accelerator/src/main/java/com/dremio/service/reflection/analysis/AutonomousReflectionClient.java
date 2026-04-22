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
package com.dremio.service.reflection.analysis;

import com.dremio.exec.util.ViewFieldsHelper;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.ViewFieldType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AutonomousReflectionClient {
  private static final Logger logger = LoggerFactory.getLogger(AutonomousReflectionClient.class);
  private static final String AI_SERVICE_URL = "http://localhost:8000/predict/schema";
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              false);

  // Khắc phục lỗi HideUtilityClassConstructor
  private AutonomousReflectionClient() {}

  public static class MeasureItem {
    private String name;
    private List<String> aggregations;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public List<String> getAggregations() {
      return aggregations;
    }

    public void setAggregations(List<String> aggregations) {
      this.aggregations = aggregations;
    }
  }

  public static class AIResponse {
    // Khắc phục lỗi VisibilityModifier: Chuyển sang private
    private List<String> dimensions;
    private List<MeasureItem> measures;

    // Thêm Getter/Setter để Jackson có thể map dữ liệu
    public List<String> getDimensions() {
      return dimensions;
    }

    public void setDimensions(List<String> dimensions) {
      this.dimensions = dimensions;
    }

    public List<MeasureItem> getMeasures() {
      return measures;
    }

    public void setMeasures(List<MeasureItem> measures) {
      this.measures = measures;
    }
  }

  public static AIResponse getRecommendations(DatasetConfig datasetConfig) {
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put("datasetPath", datasetConfig.getFullPathList());

      List<Map<String, String>> columns = new ArrayList<>();
      List<ViewFieldType> viewFields = ViewFieldsHelper.getViewFields(datasetConfig);
      if (viewFields != null) {
        for (ViewFieldType field : viewFields) {
          Map<String, String> col = new HashMap<>();
          col.put("name", field.getName());
          col.put("type", field.getType());
          columns.add(col);
        }
      }
      payload.put("columns", columns);

      String jsonPayload = MAPPER.writeValueAsString(payload);
      logger.info("Requesting autonomous reflection for dataset, payload: {}", jsonPayload);

      URL url = new URL(AI_SERVICE_URL);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json; utf-8");
      conn.setRequestProperty("Accept", "application/json");
      conn.setConnectTimeout(3000);
      conn.setReadTimeout(5000);
      conn.setDoOutput(true);

      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
      }

      int responseCode = conn.getResponseCode();
      if (responseCode == 200) {
        try (BufferedReader br =
            new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
          StringBuilder response = new StringBuilder();
          String responseLine = null;
          while ((responseLine = br.readLine()) != null) {
            response.append(responseLine.trim());
          }
          // Sử dụng hằng số MAPPER đã đổi tên
          AIResponse aiResponse = MAPPER.readValue(response.toString(), AIResponse.class);
          if (aiResponse.getMeasures() != null) {
            for (MeasureItem item : aiResponse.getMeasures()) {
              if (item.getAggregations() != null) {
                List<String> newAggs = new ArrayList<>();
                for (String agg : item.getAggregations()) {
                  if ("AVG".equalsIgnoreCase(agg)) {
                    if (!newAggs.contains("SUM")) {
                      newAggs.add("SUM");
                    }
                    if (!newAggs.contains("COUNT")) {
                      newAggs.add("COUNT");
                    }
                  } else {
                    if (!newAggs.contains(agg.toUpperCase())) {
                      newAggs.add(agg.toUpperCase());
                    }
                  }
                }
                item.setAggregations(newAggs);
              }
            }
          }
          logger.info("Received AI reflection recommendations successfully.");
          return aiResponse;
        }
      } else {
        logger.warn("AI Service returned code: {}", responseCode);
      }
    } catch (Exception e) {
      logger.warn(
          "Failed to query autonomous reflection service (fallback to heuristic): {}",
          e.getMessage());
    }
    return null;
  }
}
