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
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** Unit tests for SQL column usage extraction in {@link AutonomousReflectionIngestTask}. */
public class TestAutonomousReflectionIngestTask {

  @Test
  public void testCountColumnsOnlyIncreaseAggregate() {
    String sql =
        "SELECT\r\n"
            + "  COUNT(PRCP) AS prcp_cnt,\r\n"
            + "  COUNT(SNOW) AS snow_cnt\r\n"
            + "FROM \"SF weather 2018-2019.csv\"";

    Map<String, int[]> colMap = new HashMap<>();
    AutonomousReflectionIngestTask.aggregateColumnsFromSql(sql, colMap);

    assertEquals(1, colMap.get("PRCP")[3]);
    assertEquals(0, colMap.get("PRCP")[0]);
    assertEquals(1, colMap.get("SNOW")[3]);
    assertEquals(0, colMap.get("SNOW")[0]);
    assertNull(colMap.get("COUNT"));
    assertNull(colMap.get("prcp_cnt"));
    assertNull(colMap.get("snow_cnt"));
  }

  @Test
  public void testGroupByIncreasesDimensionSignal() {
    String sql =
        "SELECT STATION, \"DATE\", COUNT(*) AS cnt\r\n"
            + "FROM \"SF weather 2018-2019.csv\"\r\n"
            + "GROUP BY STATION, \"DATE\"";

    Map<String, int[]> colMap = new HashMap<>();
    AutonomousReflectionIngestTask.aggregateColumnsFromSql(sql, colMap);

    assertEquals(1, colMap.get("STATION")[0]);
    assertEquals(1, colMap.get("STATION")[2]);
    assertEquals(1, colMap.get("DATE")[0]);
    assertEquals(1, colMap.get("DATE")[2]);
    assertNull(colMap.get("COUNT"));
    assertNull(colMap.get("cnt"));
  }
}
