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
package com.dremio.exec.store.parquet.copyinto;

import com.dremio.common.AutoCloseables;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.physical.config.copyinto.CopyIntoTransformationProperties;
import com.dremio.exec.physical.config.copyinto.CopyIntoTransformationProperties.Property;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.TypeCoercion;
import com.dremio.exec.store.copyinto.CopyIntoTransformationCompositeReader;
import com.dremio.exec.store.copyinto.TransformationContainerHelper;
import com.dremio.exec.store.parquet.ParquetCoercionReader;
import com.dremio.exec.store.parquet.ParquetFilters;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.scan.OutputMutator;
import com.google.common.base.Stopwatch;
import java.util.List;
import java.util.Objects;

public class CopyIntoTransformationParquetCoercionReader extends ParquetCoercionReader {

  protected final CopyIntoTransformationProperties copyIntoTransformationProperties;
  private final TransformationContainerHelper transformationContainerHelper;
  protected final boolean isCopyIntoTransformations;
  protected final BatchSchema targetSchema;

  protected CopyIntoTransformationParquetCoercionReader(
      OperatorContext context,
      List<SchemaPath> columns,
      RecordReader inner,
      BatchSchema originalSchema,
      TypeCoercion typeCoercion,
      ParquetFilters filters,
      CopyIntoTransformationProperties copyIntoTransformationProperties,
      BatchSchema targetSchema) {
    super(context, columns, inner, originalSchema, typeCoercion, filters);
    this.copyIntoTransformationProperties = copyIntoTransformationProperties;
    this.isCopyIntoTransformations = copyIntoTransformationProperties != null;
    this.targetSchema = targetSchema;
    if (isCopyIntoTransformations) {
      this.transformationContainerHelper = new TransformationContainerHelper(context, mutator);
      this.compositeReader =
          new CopyIntoTransformationCompositeReader(
              transformationContainerHelper.getProjectorMutator(),
              context,
              typeCoercion,
              Stopwatch.createUnstarted(),
              Stopwatch.createUnstarted(),
              originalSchema,
              copyIntoTransformationProperties.getProperties(),
              targetSchema,
              transformationContainerHelper.getRenamedFields(),
              0);
    } else {
      transformationContainerHelper = null;
    }
  }

  public static RecordReader newInstance(
      OperatorContext context,
      List<SchemaPath> columns,
      RecordReader inner,
      BatchSchema originalSchema,
      TypeCoercion typeCoercion,
      ParquetFilters filters,
      CopyIntoTransformationProperties copyIntoTransformationProperties,
      BatchSchema targetSchema) {
    if (copyIntoTransformationProperties != null) {
      return new CopyIntoTransformationParquetCoercionReader(
          context,
          columns,
          inner,
          originalSchema,
          typeCoercion,
          filters,
          copyIntoTransformationProperties,
          targetSchema);
    }

    return ParquetCoercionReader.newInstance(
        context, columns, inner, originalSchema, typeCoercion, filters);
  }

  @Override
  protected void setupProjector(OutputMutator outgoing, VectorContainer projectorOutput) {
    if (isCopyIntoTransformations) {
      transformationContainerHelper.prepareProjectorInput();
      compositeReader.setupProjector(
          outgoing,
          transformationContainerHelper.getProjectorMutator().getContainer(),
          projectorOptions,
          projectorOutput);
      outgoing.getAndResetSchemaChanged();
    } else {
      super.setupProjector(outgoing, projectorOutput);
    }
  }

  @Override
  protected void runProjector(int recordCount) {
    if (isCopyIntoTransformations) {
      transformationContainerHelper.transferData(mutator.getContainer().getRecordCount());
      compositeReader.runProjector(
          recordCount, transformationContainerHelper.getProjectorMutator().getContainer());
    } else {
      super.runProjector(recordCount);
    }
  }

  @Override
  protected void prepareOutgoing() {
    if (isCopyIntoTransformations) {
      copyIntoTransformationProperties.getProperties().stream()
          .map(Property::getTargetColName)
          .map(outputMutator::getVector)
          .filter(Objects::nonNull)
          .forEach(outgoing::add);
      outgoing.buildSchema(SelectionVectorMode.NONE);
    } else {
      super.prepareOutgoing();
    }
  }

  @Override
  public void close() throws Exception {
    super.close();
    AutoCloseables.close(transformationContainerHelper, mutator);
  }
}
