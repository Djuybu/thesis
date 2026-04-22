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
package com.dremio.exec.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.exec.planner.sql.parser.SqlVacuum;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.TableProperties;
import org.junit.Test;

/** Tests for {@link VacuumOptions} */
public class TestVacuumOptions {

  @Test
  public void testCreateInstanceWithDirectValues() {
    long olderThan = 1000L;
    int retainLast = 5;
    String location = "/test/location";
    long gracePeriod = 2000L;

    VacuumOptions options =
        VacuumOptions.createInstance(true, true, olderThan, retainLast, location, gracePeriod);

    assertTrue(options.isExpireSnapshots());
    assertTrue(options.isRemoveOrphans());
    assertEquals(olderThan, (long) options.getOlderThanInMillis());
    assertEquals(retainLast, (int) options.getRetainLast());
    assertEquals(location, options.getLocation());
    assertEquals(gracePeriod, (long) options.getGracePeriodInMillis());
  }

  @Test
  public void testCreateInstanceFromNessieGCPolicy() {
    NessieGCPolicy policy = mock(NessieGCPolicy.class);
    when(policy.getOlderThanInMillis()).thenReturn(1000L);
    when(policy.getGracePeriodInMillis()).thenReturn(2000L);

    VacuumOptions options = VacuumOptions.createInstance(policy);

    assertTrue(options.isExpireSnapshots());
    assertTrue(options.isRemoveOrphans());
    assertEquals(1000L, (long) options.getOlderThanInMillis());
    assertEquals(1, (int) options.getRetainLast());
    assertNull(options.getLocation());
    assertEquals(2000L, (long) options.getGracePeriodInMillis());
  }

  @Test
  public void testCreateInstanceFromSqlVacuum() {
    SqlVacuum sqlVacuum = mock(SqlVacuum.class);
    when(sqlVacuum.isExpireSnapshots()).thenReturn(true);
    when(sqlVacuum.isRemoveOrphans()).thenReturn(false);
    when(sqlVacuum.getOlderThanInMillis()).thenReturn(Optional.of(1000L));
    when(sqlVacuum.getRetainLast()).thenReturn(Optional.of(5));
    when(sqlVacuum.getLocation()).thenReturn("/test/location");

    VacuumOptions options = VacuumOptions.createInstance(sqlVacuum);

    assertTrue(options.isExpireSnapshots());
    assertFalse(options.isRemoveOrphans());
    assertEquals(1000L, (long) options.getOlderThanInMillis());
    assertEquals(5, (int) options.getRetainLast());
    assertEquals("/test/location", options.getLocation());
    assertNull(options.getGracePeriodInMillis());
  }

  @Test
  public void testCreateInstanceFromTablePropertiesAndSqlVacuum() {
    Map<String, String> tableProperties = new HashMap<>();
    tableProperties.put(TableProperties.MAX_SNAPSHOT_AGE_MS, "2000");
    tableProperties.put(TableProperties.MIN_SNAPSHOTS_TO_KEEP, "3");

    SqlVacuum sqlVacuum = mock(SqlVacuum.class);
    when(sqlVacuum.isExpireSnapshots()).thenReturn(true);
    when(sqlVacuum.isRemoveOrphans()).thenReturn(false);
    when(sqlVacuum.getOlderThanInMillis()).thenReturn(Optional.empty());
    when(sqlVacuum.getRetainLast()).thenReturn(Optional.empty());
    when(sqlVacuum.getLocation()).thenReturn("/test/location");

    VacuumOptions options = VacuumOptions.createInstance(tableProperties, sqlVacuum);

    assertTrue(options.isExpireSnapshots());
    assertFalse(options.isRemoveOrphans());
    assertTrue(options.getOlderThanInMillis() > 0); // Should be current time - 2000
    assertEquals(3, (int) options.getRetainLast());
    assertEquals("/test/location", options.getLocation());
  }

  @Test
  public void testCreateInstanceFromTablePropertiesAndVacuumOptions() {
    Map<String, String> tableProperties = new HashMap<>();
    tableProperties.put(TableProperties.MAX_SNAPSHOT_AGE_MS, "2000");
    tableProperties.put(TableProperties.MIN_SNAPSHOTS_TO_KEEP, "3");

    VacuumOptions existingOptions =
        VacuumOptions.createInstance(true, true, 1000L, 2, "/test/location", 500L);

    VacuumOptions options = VacuumOptions.createInstance(tableProperties, existingOptions);

    assertTrue(options.isExpireSnapshots());
    assertTrue(options.isRemoveOrphans());
    assertEquals(1000L, (long) options.getOlderThanInMillis()); // Should take minimum
    assertEquals(3, (int) options.getRetainLast()); // Should take maximum
    assertEquals("/test/location", options.getLocation());
    assertEquals(500L, (long) options.getGracePeriodInMillis());
  }

  @Test
  public void testInvalidTableProperties() {
    Map<String, String> tableProperties = new HashMap<>();
    tableProperties.put(TableProperties.MAX_SNAPSHOT_AGE_MS, "invalid");
    tableProperties.put(TableProperties.MIN_SNAPSHOTS_TO_KEEP, "invalid");

    SqlVacuum sqlVacuum = mock(SqlVacuum.class);
    when(sqlVacuum.isExpireSnapshots()).thenReturn(true);
    when(sqlVacuum.isRemoveOrphans()).thenReturn(false);
    when(sqlVacuum.getOlderThanInMillis()).thenReturn(Optional.of(1000L));
    when(sqlVacuum.getRetainLast()).thenReturn(Optional.of(5));

    VacuumOptions options = VacuumOptions.createInstance(tableProperties, sqlVacuum);

    assertEquals(1000L, (long) options.getOlderThanInMillis());
    assertEquals(5, (int) options.getRetainLast());
  }

  @Test
  public void testDefaultValues() {
    VacuumOptions options = VacuumOptions.createInstance(true, false, null, null, null, null);

    assertTrue(options.isExpireSnapshots());
    assertFalse(options.isRemoveOrphans());
    assertTrue(options.getOlderThanInMillis() > 0); // Should be current time - 5 days
    assertEquals(1, (int) options.getRetainLast());
    assertNull(options.getLocation());
    assertNull(options.getGracePeriodInMillis());
  }

  @Test
  public void testToString() {
    VacuumOptions options =
        VacuumOptions.createInstance(true, false, 1000L, 5, "/test/location", 2000L);

    String expected =
        "VacuumOptions{"
            + "olderThanInMillis=1000"
            + ", retainLast=5"
            + ", expireSnapshots=true"
            + ", removeOrphans=false"
            + ", location=/test/location"
            + ", gracePeriodInMillis=2000"
            + '}';

    assertEquals(expected, options.toString());
  }
}
