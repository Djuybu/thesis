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
import isPlainObject from "lodash/isPlainObject";

export const RSAA = "@@redux-api-middleware/RSAA";
const defaultHeaders = { "Content-Type": "application/json" };

export class InternalError extends Error {
  constructor(message) {
    super(message);
    this.name = "InternalError";
  }
}

export class RequestError extends Error {
  constructor(message) {
    super(message);
    this.name = "RequestError";
  }
}

export class ApiError extends Error {
  constructor(status, statusText, response) {
    super(`${status} ${statusText}`);
    this.name = "ApiError";
    this.status = status;
    this.statusText = statusText;
    this.response = response;
  }
}

const isValidTypeDescriptor = (descriptor) =>
  typeof descriptor === "string" ||
  (isPlainObject(descriptor) && typeof descriptor.type === "string");

export const normalizeTypeDescriptors = (types) =>
  types.map((type) => (typeof type === "string" ? { type } : type));

export const isRSAA = (action) =>
  isPlainObject(action) && Object.prototype.hasOwnProperty.call(action, RSAA);

export const validateRSAA = (action) => {
  const errors = [];
  if (!isRSAA(action)) {
    errors.push("Action must be a plain object with [RSAA] property.");
    return errors;
  }
  const keys = Object.keys(action);
  if (keys.length !== 1 || keys[0] !== RSAA) {
    errors.push("Invalid root keys for RSAA action.");
  }
  const callAPI = action[RSAA];
  if (!isPlainObject(callAPI)) {
    errors.push("[RSAA] property must be a plain object.");
    return errors;
  }
  if (
    typeof callAPI.endpoint !== "string" &&
    typeof callAPI.endpoint !== "function"
  ) {
    errors.push("[RSAA].endpoint must be a string or function.");
  }
  if (!Array.isArray(callAPI.types) || callAPI.types.length !== 3) {
    errors.push("[RSAA].types must be an array of length 3.");
  } else if (!callAPI.types.every(isValidTypeDescriptor)) {
    errors.push("[RSAA].types entries must be strings or FSA descriptors.");
  }
  return errors;
};

export const actionWith = (descriptor, args) =>
  Promise.resolve()
    .then(() => {
      if (typeof descriptor.payload === "function") {
        return descriptor.payload.apply(descriptor, args);
      }
      return descriptor.payload;
    })
    .then((payload) =>
      Promise.resolve()
        .then(() => {
          if (typeof descriptor.meta === "function") {
            return descriptor.meta.apply(descriptor, args);
          }
          return descriptor.meta;
        })
        .then((meta) => ({
          ...descriptor,
          ...(typeof payload !== "undefined" ? { payload } : {}),
          ...(typeof meta !== "undefined" ? { meta } : {}),
        })),
    )
    .catch((error) => ({
      ...descriptor,
      payload: new InternalError(error?.message || String(error)),
      error: true,
    }));

const getJSON = (res) => {
  const contentType = res.headers?.get?.("Content-Type") || "";
  if (![204, 205].includes(res.status) && contentType.includes("json")) {
    return res.json();
  }
  return Promise.resolve();
};

const withJsonBody = (res, init = {}) =>
  getJSON(res)
    .then((json) => ({ ...init, payload: json }))
    .catch(() => init);

export const apiMiddleware =
  ({ dispatch, getState }) =>
  (next) =>
  (action) => {
    if (!isRSAA(action)) {
      return next(action);
    }

    const validationErrors = validateRSAA(action);
    if (validationErrors.length) {
      return next({
        type: "@@redux-api-middleware/INVALID_RSAA",
        payload: new InternalError(validationErrors.join("; ")),
        error: true,
      });
    }

    const callAPI = action[RSAA];
    let { endpoint, headers } = callAPI;
    const {
      bailout,
      method = "GET",
      body,
      credentials,
      options = {},
    } = callAPI;
    const [requestType, successType, failureType] = normalizeTypeDescriptors(
      callAPI.types,
    );

    try {
      const shouldBailout =
        (typeof bailout === "boolean" && bailout) ||
        (typeof bailout === "function" && bailout(getState()));
      if (shouldBailout) {
        return;
      }
    } catch (_e) {
      return next({
        ...requestType,
        payload: new RequestError("[RSAA].bailout function failed"),
        error: true,
      });
    }

    try {
      if (typeof endpoint === "function") endpoint = endpoint(getState());
      if (typeof headers === "function") headers = headers(getState());
    } catch (_e) {
      return next({
        ...requestType,
        payload: new RequestError(
          "Failed to evaluate [RSAA].endpoint or [RSAA].headers",
        ),
        error: true,
      });
    }

    dispatch(requestType);

    return fetch(endpoint, {
      method,
      body,
      credentials,
      headers: { ...defaultHeaders, ...(headers || {}) },
      ...options,
    })
      .then((res) => {
        if (res.ok) {
          if (successType?.type === "ADD_NEW_SOURCE_SUCCESS") {
            // #region agent log
            fetch(
              "http://127.0.0.1:7396/ingest/a0ff0746-9042-4bf7-8a34-3ceb88d836a4",
              {
                method: "POST",
                headers: {
                  "Content-Type": "application/json",
                  "X-Debug-Session-Id": "7c4be8",
                },
                body: JSON.stringify({
                  sessionId: "7c4be8",
                  runId: "pre-fix",
                  hypothesisId: "H1",
                  location: "reduxApiMiddlewareSafe.js:194",
                  message:
                    "Success descriptor payload type before withJsonBody",
                  data: {
                    actionType: successType.type,
                    payloadType: typeof successType.payload,
                  },
                  timestamp: Date.now(),
                }),
              },
            ).catch(() => {});
            // #endregion
          }
          return withJsonBody(res, successType).then((successTypeWithPayload) =>
            actionWith(successTypeWithPayload, [action, getState(), res]).then(
              (fsa) => {
                if (successType?.type === "ADD_NEW_SOURCE_SUCCESS") {
                  // #region agent log
                  fetch(
                    "http://127.0.0.1:7396/ingest/a0ff0746-9042-4bf7-8a34-3ceb88d836a4",
                    {
                      method: "POST",
                      headers: {
                        "Content-Type": "application/json",
                        "X-Debug-Session-Id": "7c4be8",
                      },
                      body: JSON.stringify({
                        sessionId: "7c4be8",
                        runId: "pre-fix",
                        hypothesisId: "H2",
                        location: "reduxApiMiddlewareSafe.js:219",
                        message: "Dispatched FSA payload shape",
                        data: {
                          actionType: fsa?.type,
                          payloadType: typeof fsa?.payload,
                          hasGet: typeof fsa?.payload?.get === "function",
                        },
                        timestamp: Date.now(),
                      }),
                    },
                  ).catch(() => {});
                  // #endregion
                }
                return next(fsa);
              },
            ),
          );
        }
        return withJsonBody(
          res,
          new ApiError(res.status, res.statusText, undefined),
        ).then((apiError) =>
          actionWith({ ...failureType, payload: apiError, error: true }, [
            action,
            getState(),
            res,
          ]).then((fsa) => next(fsa)),
        );
      })
      .catch((error) =>
        actionWith(
          {
            ...failureType,
            payload: new RequestError(error.message),
            error: true,
          },
          [action, getState(), undefined],
        ).then((fsa) => next(fsa)),
      );
  };

export default {
  RSAA,
  ApiError,
  InternalError,
  RequestError,
  actionWith,
  apiMiddleware,
  isRSAA,
  normalizeTypeDescriptors,
  validateRSAA,
};
