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
package com.dremio.exec.exception.friendly;

import java.util.function.Function;
import java.util.function.Predicate;

public class ExceptionHandler<E extends Exception> {
  private final String name;
  private final Predicate<Throwable> matcher;
  private final Function<Throwable, E> mapperToFriendly;

  public ExceptionHandler(
      final String name,
      final Predicate<Throwable> matcher,
      final Function<Throwable, E> mapperToFriendly) {
    this.name = name;
    this.matcher = matcher;
    this.mapperToFriendly = mapperToFriendly;
  }

  public boolean isMatch(Throwable th) {
    return this.matcher.test(th);
  }

  public E makeFriendly(Throwable th) {
    return this.mapperToFriendly.apply(th);
  }

  @Override
  public String toString() {
    return String.format("ExceptionHandler{name='%s'}", name);
  }
}
