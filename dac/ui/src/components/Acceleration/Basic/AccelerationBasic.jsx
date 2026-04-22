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
import { Component } from "react";
import { connect } from "react-redux";
import PropTypes from "prop-types";
import Immutable from "immutable";
import { modalFormProps } from "components/Forms";
import { Toggle } from "components/Fields";
import Message from "components/Message";

import "#oss/uiTheme/less/commonModifiers.less";
import "#oss/uiTheme/less/Acceleration/Acceleration.less";
import LayoutInfo from "../LayoutInfo";
import AccelerationAggregate from "./AccelerationAggregate";
import { SpinnerOverlay, Button } from "dremio-ui-lib/components";
import ApiUtils from "utils/apiUtils/apiUtils";

export class AccelerationBasic extends Component {
  static getFields() {
    return AccelerationAggregate.getFields();
  }
  static validate(values) {
    return AccelerationAggregate.validate(values);
  }

  state = {
    isGenerating: false,
  };

  generateAutonomousReflection = async () => {
    const { dataset, fields } = this.props;
    const { rawReflections, aggregationReflections } = fields;

    const datasetId = dataset.get("id");
    const cpath = encodeURIComponent(datasetId);

    try {
      this.setState({ isGenerating: true });
      const response = await ApiUtils.fetch(
        `dataset/${cpath}/reflection/recommendations`,
        { method: "POST" },
        2,
      );
      const data = await response.json();

      const rawReflection = rawReflections[0];
      const aggReflection = aggregationReflections[0];

      const dims = data.dimensions || [];
      const meas = data.measures || [];

      const rawDisplayFields = [...dims, ...meas.map((m) => m.name)]
        .filter((d) => !d.includes("$"))
        .map((d) => ({ name: d }));

      import("react-dom").then((ReactDOM) => {
        ReactDOM.unstable_batchedUpdates(() => {
          if (rawReflection && rawReflection.enabled) {
            rawReflection.enabled.onChange(rawDisplayFields.length > 0);
          }

          // Enable agg and set fields
          const dimensionFields = dims
            .filter((d) => !d.includes("$"))
            .map((d) => ({ name: d }));
          const measureFields = meas
            .filter((d) => !d.name.includes("$"))
            .map((d) => ({
              name: d.name,
              measureTypeList: d.aggregations || ["SUM", "COUNT"],
            }));

          if (aggReflection) {
            const hasAgg =
              dimensionFields.length > 0 || measureFields.length > 0;
            if (aggReflection.enabled) {
              aggReflection.enabled.onChange(hasAgg);
            }

            if (hasAgg) {
              for (let i = fields.columnsDimensions.length - 1; i >= 0; i--) {
                fields.columnsDimensions.removeField(i);
              }
              for (let i = fields.columnsMeasures.length - 1; i >= 0; i--) {
                fields.columnsMeasures.removeField(i);
              }

              dimensionFields.forEach((f) =>
                fields.columnsDimensions.addField({ column: f.name }),
              );
              measureFields.forEach((f) =>
                fields.columnsMeasures.addField({
                  column: f.name,
                  measureTypeList: f.measureTypeList,
                }),
              );
            }
          }
        });
      });
    } catch (e) {
      console.error("Failed to generate Autonomous Reflection", e);
    } finally {
      this.setState({ isGenerating: false });
    }
  };

  static propTypes = {
    dataset: PropTypes.instanceOf(Immutable.Map).isRequired,
    reflections: PropTypes.instanceOf(Immutable.Map).isRequired,
    location: PropTypes.object.isRequired,
    fields: PropTypes.object,
    handleSubmit: PropTypes.func,
    submit: PropTypes.func,
    onCancel: PropTypes.func,
    loadingRecommendations: PropTypes.bool,
    loadingRawRecommendations: PropTypes.bool,
    loadingAggRecommendations: PropTypes.bool,
    skipRecommendations: PropTypes.func,
    canAlter: PropTypes.any,
    fetchRecommendations: PropTypes.func,
    formIsDirty: PropTypes.bool,
    isTriggerRecommendation: PropTypes.bool,
  };

  static contextTypes = {
    reflectionSaveErrors: PropTypes.instanceOf(Immutable.Map).isRequired,
  };

  getHighlightedSection() {
    const { layoutId } = this.props.location.state || {};
    if (!layoutId) return null;

    return this.props.reflections.getIn([layoutId, "type"]);
  }

  render() {
    const {
      fields,
      location,
      reflections,
      dataset,
      loadingRecommendations,
      loadingAggRecommendations,
      loadingRawRecommendations,
      skipRecommendations,
      canAlter,
      fetchRecommendations,
      formIsDirty,
      isTriggerRecommendation,
    } = this.props;

    if (!fields.rawReflections.length || !fields.aggregationReflections.length)
      return null; // Form still initializing

    const { enabled } = fields.rawReflections[0];
    const toggleLabel = (
      <h3 className={"AccelerationBasic__toggleLabel"}>
        <dremio-icon
          style={{
            blockSize: 24,
            inlineSize: 24,
          }}
          class="mx-1"
          name="interface/reflection-raw-mode"
        ></dremio-icon>
        {laDeprecated("Raw Reflections")}
      </h3>
    );

    const firstRawLayout = reflections.find((r) => r.get("type") === "RAW");
    const firstAggLayout = reflections.find(
      (r) => r.get("type") === "AGGREGATION",
    );
    const highlightedSection = this.getHighlightedSection();

    // if this error is encountered, no error message should be rendered
    const SUPPORT_ERROR =
      "Permission denied. A support user cannot create a reflection";

    const rawError = this.context.reflectionSaveErrors.get(
      fields.rawReflections[0].id.value,
    );
    const rawErrorInfo = rawError?.get("message")?.get("errorMessage");
    const rawErrorMessage = rawError && rawErrorInfo !== SUPPORT_ERROR && (
      <Message
        messageType="error"
        inFlow={false}
        message={rawError.get("message")}
        messageId={rawError.get("id")}
        className={"AccelerationBasic__message"}
      />
    );

    const aggError = this.context.reflectionSaveErrors.get(
      fields.aggregationReflections[0].id.value,
    );

    let errorMessageInfo;
    const aggErrorMessage = aggError?.get("message");

    if (aggErrorMessage) {
      if (typeof aggErrorMessage === "string") {
        errorMessageInfo = aggErrorMessage;
      } else {
        errorMessageInfo = aggError.get("message").get("errorMessage");
      }
    }

    const errorMessageComponent = aggError &&
      errorMessageInfo !== SUPPORT_ERROR && (
        <Message
          messageType="error"
          inFlow={false}
          message={aggError.get("message")}
          messageId={aggError.get("id")}
          className={"AccelerationBasic__message"}
        />
      );

    return (
      <div className={"AccelerationBasic"} data-qa="raw-basic">
        <div
          style={{
            display: "flex",
            justifyContent: "flex-end",
            marginBottom: 10,
          }}
        >
          <Button
            variant="primary"
            disabled={this.state.isGenerating}
            onClick={this.generateAutonomousReflection}
          >
            {this.state.isGenerating
              ? "Generating..."
              : "Generate Autonomous Reflection"}
          </Button>
        </div>
        <div className={"AccelerationBasic__header"}>
          <div
            className={`AccelerationBasic__toggleLayout ${
              highlightedSection === "RAW" ? "--bgColor-highlight" : null
            }`}
            data-qa="raw-queries-toggle"
          >
            <Toggle
              {...enabled}
              label={toggleLabel}
              className={"AccelerationBasic__toggle"}
              {...(isTriggerRecommendation && {
                onChange: (e) => {
                  enabled?.onChange?.(e);
                  if (e.target.checked) fetchRecommendations("raw");
                },
              })}
            />
            <LayoutInfo layout={firstRawLayout} />
            {loadingRawRecommendations && isTriggerRecommendation && (
              <SpinnerOverlay
                in
                className="AccelerationAggregate__AggregateForm__loading"
              />
            )}
          </div>

          <div className={"position-relative"}>{rawErrorMessage}</div>
        </div>
        <AccelerationAggregate
          {...modalFormProps(this.props)}
          canAlter={canAlter}
          dataset={dataset}
          reflection={firstAggLayout}
          fields={fields}
          className={"AccelerationBasic__AccelerationAggregate"}
          location={location}
          shouldHighlight={highlightedSection === "AGGREGATION"}
          errorMessage={errorMessageComponent}
          loadingRecommendations={
            isTriggerRecommendation
              ? loadingAggRecommendations
              : loadingRecommendations
          }
          skipRecommendations={skipRecommendations}
          fetchRecommendations={fetchRecommendations}
          formIsDirty={formIsDirty}
          isTriggerRecommendation={isTriggerRecommendation}
        />
      </div>
    );
  }
}

const mapStateToProps = (state) => {
  const location = state.routing.locationBeforeTransitions;
  return {
    location,
  };
};

export default connect(mapStateToProps)(AccelerationBasic);
