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

import com.dremio.service.namespace.NamespaceIdentity;
import com.dremio.service.namespace.NamespaceUser;
import com.dremio.service.users.SystemUser;
import com.dremio.service.users.User;
import com.dremio.service.users.UserNotFoundException;
import com.dremio.service.users.UserService;
import javax.annotation.Nullable;

public class CatalogIdentityResolverImpl implements CatalogIdentityResolver {
  private final UserService userService;

  public CatalogIdentityResolverImpl(UserService userService) {
    this.userService = userService;
  }

  @Override
  public @Nullable NamespaceIdentity toNamespaceIdentity(CatalogIdentity identity) {
    if (!(identity instanceof CatalogUser)) {
      return null;
    }
    if (identity.getName().equals(SystemUser.SYSTEM_USERNAME)) {
      return new NamespaceUser(() -> SystemUser.SYSTEM_USER);
    }

    try {
      final User user = userService.getUser(identity.getName());
      return new NamespaceUser(() -> user);
    } catch (UserNotFoundException ignored) {
      return null;
    }
  }
}
