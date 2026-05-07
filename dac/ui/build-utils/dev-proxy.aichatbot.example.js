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

/**
 * Example webpack-dev-server proxy so the UI (port 3005) can call {@code /aichat/*} while the
 * standalone aichatbot-plugin runs on port 9191.
 *
 * Usage from {@code dac/ui}:
 *
 * <pre>
 *   export DEV_PROXY_CONFIG_PATH=./build-utils/dev-proxy.aichatbot.example.js
 *   npm run start
 * </pre>
 *
 * Then start the plugin jar with {@code DREMIO_BASE_URL} pointing at your coordinator (e.g.
 * {@code http://localhost:9047}).
 */
module.exports = {
  proxy: {
    "/aichat": {
      target: "http://127.0.0.1:9191",
      changeOrigin: false,
      secure: false,
    },
  },
};
