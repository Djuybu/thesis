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
package com.dremio.dac.server;

import com.dremio.config.DremioConfig;
import com.google.common.base.Strings;

/** Resolves the Dremio SQL Agent gateway base URL (e.g. {@code http://127.0.0.1:9292}). */
public final class AiChatbotPluginBaseUrlResolver {
  private static final String ENV_VAR = "DREMIO_AICHATBOT_PLUGIN_BASE_URL";
  private static final String SYS_PROP = "dremio.aichatbot.plugin.base_url";

  private AiChatbotPluginBaseUrlResolver() {}

  /**
   * Precedence: environment variable, JVM system property, then {@code dremio.conf} / {@code
   * dremio-reference.conf}.
   */
  public static String resolve(DACConfig dacConfig) {
    final String fromEnv = System.getenv(ENV_VAR);
    if (!Strings.isNullOrEmpty(fromEnv)) {
      return stripTrailingSlash(fromEnv.trim());
    }
    final String fromProp = System.getProperty(SYS_PROP);
    if (!Strings.isNullOrEmpty(fromProp)) {
      return stripTrailingSlash(fromProp.trim());
    }
    final DremioConfig cfg = dacConfig.getConfig();
    if (cfg.hasPath(DremioConfig.WEB_AICHATBOT_PLUGIN_BASE_URL)) {
      final String fromFile = cfg.getString(DremioConfig.WEB_AICHATBOT_PLUGIN_BASE_URL);
      if (!Strings.isNullOrEmpty(fromFile)) {
        return stripTrailingSlash(fromFile.trim());
      }
    }
    return "";
  }

  static String stripTrailingSlash(String s) {
    if (s == null || s.isEmpty()) {
      return "";
    }
    String out = s;
    while (out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }
}
