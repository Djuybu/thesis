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
//@ts-nocheck
import path from "path";
import { bundleAnalyzer } from "./build-utils/plugins/bundleAnalyzer";
import { optimization } from "./build-utils/optimization";
import { devServer as baseDevServer } from "./build-utils/devServer";
import { output } from "./build-utils/output";
import { stats } from "./build-utils/stats";
import { getRules } from "./build-utils/rules";
import { getResolve } from "./build-utils/resolve";
import { sentryPlugin } from "./build-utils/plugins/sentry";
import { mode } from "./build-utils/mode";
import { devtool } from "./build-utils/devtool";
import { htmlPlugin } from "./build-utils/plugins/htmlPlugin";
import { bannerPlugin } from "./build-utils/plugins/bannerPlugin";
import { define } from "./build-utils/plugins/define";
import { css } from "./build-utils/plugins/css";
import { copy } from "./build-utils/plugins/copy";
import { tags } from "./build-utils/plugins/tags";
import webpack from "webpack";
import { dynLoadPath } from "./build-utils/dyn-load";
import MonacoWebpackPlugin from "monaco-editor-webpack-plugin";

const dcsPath = process.env.DREMIO_DCS_LOADER_PATH
  ? path.resolve(__dirname, process.env.DREMIO_DCS_LOADER_PATH)
  : null;

const config = {
  ignoreWarnings: [
    (warning) => {
      if (warning.message.indexOf("was not found in '") !== -1) {
        return true;
      }
    },
  ],
  devtool,
  optimization,

  // 1. CẤU HÌNH DEVSERVER: Tắt việc ghi file ra đĩa để tránh lỗi Race Condition chữ hoa/thường trên WSL
  devServer: {
    ...baseDevServer,
    devMiddleware: {
      ...baseDevServer.devMiddleware,
      writeToDisk: false,
    },
  },

  // 2. CẤU HÌNH LOGGING
  infrastructureLogging: {
    level: 'error',
  },

  output,
  stats,
  mode,
  entry: {
    app: [
      path.resolve(
        process.env.DREMIO_DCS_LOADER_PATH ||
        process.env.DREMIO_INJECTION_PATH ||
        process.env.DREMIO_DYN_LOADER_PATH ||
        path.join(__dirname, "src"),
        "index.tsx",
      ),
    ],
  },
  performance: {
    maxEntrypointSize: 10000000,
    maxAssetSize: 10000000,
  },
  module: {
    // 3. THÊM LOADER CHO CÁC THƯ MỤC NGOÀI: Sửa lỗi "Unexpected token"
    rules: getRules({
      additionalIncludes: [
        ...(dcsPath ? [dcsPath] : []),
        path.resolve(__dirname, "../ui-common/src"),
        path.resolve(__dirname, "../ui-lib/src"),
        path.resolve(__dirname, "../../ui/icons/src"),
        path.resolve(__dirname, "../../ui/design-system") // Cần thiết cho các file .ts/.tsx trong design-system
      ]
    },
      {
        test: /\.svg$/,
        type: 'asset/resource',
        generator: {
          filename: 'static/icons/[name][ext]'
        }
      }),

  },

  plugins: [
    bundleAnalyzer,
    css,
    bannerPlugin,
    htmlPlugin,
    tags,
    define,
    copy,
    sentryPlugin,
    new webpack.ProgressPlugin(),
    new MonacoWebpackPlugin({
      languages: [],
    }),
  ].filter(Boolean),

  resolve: {
    ...getResolve({
      additionalAliases: {
        ...(dcsPath ? { "#dc": dcsPath } : {}),
        "#ee": dynLoadPath,

        // Explicit mapping to compiled lang JSON files
        "dremio-ui-common/lang": path.resolve(__dirname, "../ui-common/dist-lang"),
        "dremio-ui-common/appTheme": path.resolve(__dirname, "../ui-common/src/appTheme/appTheme.ts"),
        "dremio-ui-common": path.resolve(__dirname, "../ui-common/src"),

        // Explicit mappings for ui-lib so that subpaths don't hit the blanket /src mapping below
        "dremio-ui-lib/components": path.resolve(__dirname, "../ui-lib/components"),
        "dremio-ui-lib/icons": path.resolve(__dirname, "../ui-lib/icons"),
        "dremio-ui-lib/images": path.resolve(__dirname, "../ui-lib/images"),
        "dremio-ui-lib/dist-themes": path.resolve(__dirname, "../ui-lib/dist-themes"),
        "dremio-ui-lib/dist-icons": path.resolve(__dirname, "../ui-lib/dist-icons"),
        "dremio-ui-lib/dist": path.resolve(__dirname, "../ui-lib/dist"),
        "dremio-ui-lib": path.resolve(__dirname, "../ui-lib/src"),

        "~dremio-ui-lib/icons": path.resolve(__dirname, "../ui-lib/icons"),
        "~dremio-ui-lib/styles": path.resolve(__dirname, "../ui-lib/src/styles"),
        "~dremio-ui-lib": path.resolve(__dirname, "../ui-lib/src"),
        "~dremio-ui-common": path.resolve(__dirname, "../ui-common/src"),


      },
    }),

    // 4. EXTENSION ALIAS: Sửa lỗi "Can't resolve .js" khi file thực tế là .ts/.tsx
    extensionAlias: {
      ".js": [".ts", ".tsx", ".js"],
      ".jsx": [".tsx", ".jsx"]
    }
  },
};

export default config;