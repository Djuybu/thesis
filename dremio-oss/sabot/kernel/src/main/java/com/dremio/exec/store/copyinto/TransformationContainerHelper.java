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
package com.dremio.exec.store.copyinto;

import com.dremio.common.AutoCloseables;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.SchemaBuilder;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.SampleMutator;
import com.dremio.exec.util.ColumnUtils;
import com.dremio.exec.util.VectorUtil;
import com.dremio.sabot.exec.context.OperatorContext;
import java.util.HashMap;
import java.util.Map;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.commons.lang3.tuple.Pair;

public class TransformationContainerHelper implements AutoCloseable {

  // OperatorContext
  private final OperatorContext context;

  // Tracks virtual columns <> real column name mapping used for transformations
  private final Map<String, String> renamedFields = new HashMap<>();

  // Output mutator of upstream reader e.g. of TextReader | ParquetReader
  private final SampleMutator upstreamMutator;

  // The incoming mutator for the projector when transformations are used
  private final SampleMutator projectorMutator;

  // The incoming vector container for the projector when transformations are used
  private VectorContainer projectorIncoming;

  public TransformationContainerHelper(OperatorContext context, SampleMutator upstreamMutator) {
    this.upstreamMutator = upstreamMutator;
    this.context = context;
    this.projectorMutator = new SampleMutator(context.getAllocator());
  }

  /**
   * Prepares the input for the projector by creating and configuring a transformation container
   * with renamed fields. The method updates the {@code projectorIncoming} with the transformed
   * schema.
   *
   * @return
   */
  public VectorContainer prepareProjectorInput() {
    try (VectorContainer transformationContainer = new VectorContainer(context.getAllocator())) {
      BatchSchema incomingSchema = upstreamMutator.getContainer().getSchema();
      // If the incoming schema is empty, initialize the projector with an empty schema and return.
      if (incomingSchema.getFields().isEmpty()) {
        projectorIncoming = projectorMutator.getContainer();
        projectorIncoming.buildSchema();
        projectorIncoming.setRecordCount(0);
        return null;
      }
      // Build a new schema with renamed fields.
      SchemaBuilder schemaBuilder = BatchSchema.newBuilder();
      for (Field field : incomingSchema.getFields()) {
        Field renamedField =
            new Field(
                (ColumnUtils.VIRTUAL_COLUMN_PREFIX + field.getName()).toLowerCase(),
                field.getFieldType(),
                field.getChildren());

        renamedFields.put(field.getName(), renamedField.getName());
        schemaBuilder.addField(renamedField);
      }
      BatchSchema transformationSchema = schemaBuilder.build();
      // Add the transformed schema to the container and build it.
      transformationContainer.addSchema(transformationSchema);
      transformationContainer.buildSchema();

      // Add fields to the projector's mutator and transfer data from the original vectors.
      transformationSchema.getFields().stream()
          .map(
              f ->
                  Pair.of(
                      f, VectorUtil.getVectorFromSchemaPath(transformationContainer, f.getName())))
          .forEach(p -> projectorMutator.addField(p.getLeft(), p.getRight().getClass()));
    }

    // Set up the projector's incoming container
    projectorIncoming = projectorMutator.getContainer();
    projectorIncoming.buildSchema();
    return projectorIncoming;
  }

  public void transferData(int outputRecordsInBatch) {
    renamedFields.entrySet().stream()
        .map(
            e ->
                Pair.of(
                    upstreamMutator.getVector(e.getKey()),
                    projectorMutator.getVector(e.getValue())))
        .forEach(p -> p.getLeft().makeTransferPair(p.getRight()).transfer());

    projectorIncoming.setRecordCount(outputRecordsInBatch);
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(projectorMutator);
  }

  public SampleMutator getProjectorMutator() {
    return projectorMutator;
  }

  public Map<String, String> getRenamedFields() {
    return renamedFields;
  }
}
