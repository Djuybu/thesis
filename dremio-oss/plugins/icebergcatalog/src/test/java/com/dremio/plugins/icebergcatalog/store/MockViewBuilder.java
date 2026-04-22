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
package com.dremio.plugins.icebergcatalog.store;

import static org.mockito.Mockito.mock;

import java.util.Map;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.view.View;
import org.apache.iceberg.view.ViewBuilder;

public class MockViewBuilder implements ViewBuilder {

  public MockViewBuilder() {}

  @Override
  public ViewBuilder withProperties(Map<String, String> map) {
    return this;
  }

  @Override
  public ViewBuilder withProperty(String s, String s1) {
    return this;
  }

  @Override
  public View create() {
    return mock(View.class);
  }

  @Override
  public View replace() {
    return null;
  }

  @Override
  public View createOrReplace() {
    return null;
  }

  @Override
  public ViewBuilder withSchema(Schema schema) {
    return this;
  }

  @Override
  public ViewBuilder withQuery(String s, String s1) {
    return this;
  }

  @Override
  public ViewBuilder withDefaultCatalog(String s) {
    return this;
  }

  @Override
  public ViewBuilder withLocation(String location) {
    return this;
  }

  @Override
  public ViewBuilder withDefaultNamespace(Namespace namespace) {
    return this;
  }
}
