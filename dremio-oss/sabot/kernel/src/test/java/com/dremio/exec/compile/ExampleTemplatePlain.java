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
package com.dremio.exec.compile;

import com.dremio.exec.compile.sig.RuntimeOverridden;

public abstract class ExampleTemplatePlain implements ExampleInner {
  @Override
  public abstract void doOutside();

  @Override
  public abstract void doInsideOutside();

  public class TheInnerClass {

    @RuntimeOverridden
    public void doInside() {}
    ;

    public void doDouble() {
      ExampleTemplatePlain.TheInnerClass.DoubleInner di =
          new ExampleTemplatePlain.TheInnerClass.DoubleInner();
      di.doDouble();
    }

    public class DoubleInner {
      @RuntimeOverridden
      public void doDouble() {}
      ;
    }
  }
}
