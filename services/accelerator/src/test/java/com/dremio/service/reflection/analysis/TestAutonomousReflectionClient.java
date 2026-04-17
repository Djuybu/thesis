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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.reflection.analysis.ReflectionAnalyzer.TableStats;
import com.dremio.service.reflection.analysis.ReflectionSuggester.ReflectionSuggestionType;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Kiểm thử tính năng Autonomous Reflection mới được tích hợp vào Dremio OSS. Trọng tâm là kiểm thử
 * Parse Payload JSON và kịch bản Fallback khi AI Server (Python) bị down.
 */
public class TestAutonomousReflectionClient {

  @Test
  public void testAIResponseDeserialization() throws Exception {
    String mockJsonResponse =
        "{\n"
            + "  \"dimensions\": [\"order_date\", \"customer_id\"],\n"
            + "  \"measures\": [\n"
            + "    {\"name\": \"total_amount\", \"aggregations\": [\"SUM\", \"AVG\"]},\n"
            + "    {\"name\": \"quantity\", \"aggregations\": [\"SUM\", \"MAX\"]}\n"
            + "  ]\n"
            + "}";

    ObjectMapper mapper = new ObjectMapper();
    AutonomousReflectionClient.AIResponse response =
        mapper.readValue(mockJsonResponse, AutonomousReflectionClient.AIResponse.class);

    assertNotNull("Response should be deserialized", response);
    assertEquals("Should contain exactly 2 dimensions", 2, response.getDimensions().size());
    assertEquals("order_date", response.getDimensions().get(0));
    assertEquals("customer_id", response.getDimensions().get(1));

    assertEquals("Should contain exactly 2 measures", 2, response.getMeasures().size());
    assertEquals("total_amount", response.getMeasures().get(0).getName());
    assertEquals("AVG", response.getMeasures().get(0).getAggregations().get(1));
    assertEquals("quantity", response.getMeasures().get(1).getName());
  }

  @Test
  public void testSuggesterFallbackWhenAIServiceDown() {
    DatasetConfig config = new DatasetConfig();
    config.setFullPathList(Collections.singletonList("test_table"));

    ReflectionSuggester suggester = new ReflectionSuggester(config);

    TableStats mockTableStats = mock(TableStats.class);
    when(mockTableStats.getColumns()).thenReturn(Collections.emptyList());
    when(mockTableStats.getCount()).thenReturn(100L);

    // Khi gọi suggester.getReflectionGoals, nó sẽ chạy vào getAggReflections.
    // Trong đó sẽ gọi AutonomousReflectionClient (với http://localhost:8000).
    // Nếu Python Server không bắt HTTP Request -> catch Exception -> Fallback sang Original Dremio
    // Heuristic.
    // Chúng ta kì vọng hàm không throw Exception và trả về bình thường (ở đây emptyList vì mock
    // data trống).
    List<ReflectionGoal> goals =
        suggester.getReflectionGoals(mockTableStats, ReflectionSuggestionType.AGG);

    assertNotNull("Reflection goals should not be null, heuristic fallback must work", goals);
    assertTrue("Should be empty for empty table columns", goals.isEmpty());
  }
}
