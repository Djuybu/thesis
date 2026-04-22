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
package com.dremio.sabot.op.fromjson;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.FieldSizeLimitExceptionHelper;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper.RowSizeLimitExceptionType;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.Describer;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.CatalogOptions;
import com.dremio.exec.exception.JsonFieldChangeExceptionContext;
import com.dremio.exec.exception.SetupException;
import com.dremio.exec.physical.base.GroupScan;
import com.dremio.exec.physical.config.ExtendedFormatOptions;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.TypedFieldId;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorAccessibleComplexWriter;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.record.VectorWrapper;
import com.dremio.exec.store.easy.json.JsonProcessor.ReadState;
import com.dremio.exec.vector.complex.fn.JsonReader;
import com.dremio.exec.vector.complex.fn.JsonReaderIOException;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.fromjson.ConvertFromJsonPOP.ConversionColumn;
import com.dremio.sabot.op.join.vhash.spill.slicer.CombinedSizer;
import com.dremio.sabot.op.spi.SingleInputOperator;
import com.google.common.base.Preconditions;
import java.io.EOFException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorContainerHelper;
import org.apache.arrow.vector.complex.writer.BaseWriter.ComplexWriter;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.util.TransferPair;

public class ConvertFromJsonOperator implements SingleInputOperator {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ConvertFromJsonOperator.class);
  private static final long INT_SIZE = 4;

  private final ConvertFromJsonPOP config;
  private final OperatorContext context;
  private int rowSizeLimit;
  private boolean rowSizeLimitEnabled;

  private State state = State.NEEDS_SETUP;
  private VectorAccessible incoming;
  private VectorContainer outgoing;
  private List<TransferPair> transfers = new ArrayList<>();
  private List<JsonConverter<?>> converters = new ArrayList<>();
  private ArrowBuf rowSizeAccumulator;
  private int fixedDataLenPerRow;
  private CombinedSizer variableVectorSizer;
  private boolean rowSizeLimitEnabledForThisOperator;

  public ConvertFromJsonOperator(OperatorContext context, ConvertFromJsonPOP config) {
    this.config = config;
    this.context = context;
  }

  @Override
  public VectorAccessible setup(VectorAccessible accessible) throws Exception {
    state.is(State.NEEDS_SETUP);
    this.incoming = accessible;
    this.outgoing = context.createOutputVectorContainer();
    outgoing.setInitialCapacity(context.getTargetBatchSize());

    final Map<String, ConversionColumn> cMap = new HashMap<>();
    for (ConversionColumn c : config.getColumns()) {
      cMap.put(c.getInputField().toLowerCase(), c);

      if (c.getErrorMode() == ConvertFromErrorMode.NULL_ON_ERROR) {
        if (c.hasDummySchema()) {
          throw new UnsupportedOperationException(
              "Schema must be provided when using NULL_ON_ERROR mode.");
        }
      }
    }

    final int sizeLimit =
        Math.toIntExact(this.context.getOptions().getOption(ExecConstants.LIMIT_FIELD_SIZE_BYTES));
    final int maxLeafLimit =
        Math.toIntExact(
            this.context.getOptions().getOption(CatalogOptions.METADATA_LEAF_COLUMN_MAX));

    this.rowSizeLimit =
        Math.toIntExact(this.context.getOptions().getOption(ExecConstants.LIMIT_ROW_SIZE_BYTES));
    this.rowSizeLimitEnabled =
        this.context.getOptions().getOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT);
    this.rowSizeLimitEnabledForThisOperator = rowSizeLimitEnabled;

    for (VectorWrapper<?> w : accessible) {
      final Field f = w.getField();
      final ValueVector incomingVector = w.getValueVector();
      ConversionColumn conversion = cMap.get(f.getName().toLowerCase());
      if (conversion != null) {
        Field updatedField = conversion.asField(f.getName());
        ValueVector outgoingVector = outgoing.addOrGet(updatedField);
        Preconditions.checkArgument(
            incomingVector instanceof VarBinaryVector || incomingVector instanceof VarCharVector,
            "Incoming field [%s] should have been either a varchar or varbinary.",
            Describer.describe(f));
        if (incomingVector instanceof VarBinaryVector) {
          converters.add(
              new BinaryConverter(
                  conversion,
                  sizeLimit,
                  maxLeafLimit,
                  (VarBinaryVector) incomingVector,
                  outgoingVector));
        } else {
          converters.add(
              new CharConverter(
                  conversion,
                  sizeLimit,
                  maxLeafLimit,
                  (VarCharVector) incomingVector,
                  outgoingVector));
        }

      } else {
        TransferPair pair =
            incomingVector.getTransferPair(incomingVector.getField(), context.getAllocator());
        transfers.add(pair);
        outgoing.add(pair.getTo());
      }
    }

    if (converters.size() != config.getColumns().size()) {
      throw new SetupException(
          String.format(
              "Expected %d input column(s) but only found %d",
              config.getColumns().size(), converters.size()));
    }
    outgoing.buildSchema();
    state = State.CAN_CONSUME;
    if (rowSizeLimitEnabled) {
      fixedDataLenPerRow = VectorContainerHelper.getFixedDataLenPerRow(outgoing);
      if (!VectorContainerHelper.isVarLenColumnPresent(outgoing)) {
        rowSizeLimitEnabledForThisOperator = false;
        if (fixedDataLenPerRow > rowSizeLimit) {
          throw RowSizeLimitExceptionHelper.createRowSizeLimitException(
              rowSizeLimit, RowSizeLimitExceptionType.PROCESSING, logger);
        }
      } else {
        createNewRowLengthAccumulatorIfRequired(context.getTargetBatchSize());
        this.variableVectorSizer = VectorContainerHelper.createSizer(outgoing, false);
      }
    }

    return outgoing;
  }

  @Override
  public void consumeData(int records) throws Exception {
    state.is(State.CAN_CONSUME);
    state = State.CAN_PRODUCE;
  }

  @Override
  public int outputData() throws Exception {
    state.is(State.CAN_PRODUCE);

    final int records = incoming.getRecordCount();
    for (JsonConverter<?> converter : converters) {
      converter.convert(records);
    }

    for (TransferPair transfer : transfers) {
      transfer.transfer();
      transfer.getTo().setValueCount(records);
    }

    outgoing.setRecordCount(records);
    state = State.CAN_CONSUME;
    checkForRowSizeOverLimit(records);

    return records;
  }

  private void createNewRowLengthAccumulatorIfRequired(int batchSize) {
    if (rowSizeAccumulator != null) {
      if (rowSizeAccumulator.capacity() < (long) batchSize * INT_SIZE) {
        rowSizeAccumulator.close();
        rowSizeAccumulator = null;
      } else {
        return;
      }
    }
    rowSizeAccumulator = context.getAllocator().buffer((long) batchSize * INT_SIZE);
  }

  private void checkForRowSizeOverLimit(int recordCount) {
    if (!rowSizeLimitEnabledForThisOperator) {
      return;
    }
    createNewRowLengthAccumulatorIfRequired(recordCount);
    VectorContainerHelper.checkForRowSizeOverLimit(
        outgoing,
        recordCount,
        rowSizeLimit - fixedDataLenPerRow,
        rowSizeLimit,
        rowSizeAccumulator,
        variableVectorSizer,
        RowSizeLimitExceptionType.PROCESSING,
        logger);
  }

  @Override
  public <OUT, IN, EXCEP extends Throwable> OUT accept(
      OperatorVisitor<OUT, IN, EXCEP> visitor, IN value) throws EXCEP {
    return visitor.visitSingleInput(this, value);
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(outgoing);
    if (rowSizeAccumulator != null) {
      rowSizeAccumulator.close();
      rowSizeAccumulator = null;
    }
  }

  @Override
  public void noMoreToConsume() throws Exception {
    state.is(State.CAN_CONSUME);
    state = State.DONE;
  }

  @Override
  public State getState() {
    return state;
  }

  private class BinaryConverter extends JsonConverter<VarBinaryVector> {

    BinaryConverter(
        ConversionColumn column,
        int sizeLimit,
        int maxLeafLimit,
        VarBinaryVector vector,
        ValueVector outgoingVector) {
      super(column, sizeLimit, maxLeafLimit, vector, outgoingVector);
    }

    @Override
    byte[] getBytes(int inputIndex) {
      if (vector.isNull(inputIndex)) {
        return null;
      }
      final byte[] data = vector.get(inputIndex);
      FieldSizeLimitExceptionHelper.checkSizeLimit(data.length, sizeLimit, inputIndex, logger);
      return data;
    }
  }

  private class CharConverter extends JsonConverter<VarCharVector> {

    CharConverter(
        ConversionColumn column,
        int sizeLimit,
        int maxLeafLimit,
        VarCharVector vector,
        ValueVector outgoingVector) {
      super(column, sizeLimit, maxLeafLimit, vector, outgoingVector);
    }

    @Override
    byte[] getBytes(int inputIndex) {
      if (vector.isNull(inputIndex)) {
        return null;
      }

      final byte[] data = vector.get(inputIndex);
      FieldSizeLimitExceptionHelper.checkSizeLimit(data.length, sizeLimit, inputIndex, logger);
      return data;
    }
  }

  private abstract class JsonConverter<T extends ValueVector> {
    private final ConversionColumn column;
    private final ComplexWriter writer;
    private final JsonReader reader;
    protected final T vector;
    private final ValueVector outgoingVector;
    protected final int sizeLimit;

    public JsonConverter(
        ConversionColumn column,
        int sizeLimit,
        int maxLeafLimit,
        T vector,
        ValueVector outgoingVector) {
      this.column = column;
      this.vector = vector;
      this.writer = VectorAccessibleComplexWriter.getWriter(column.getInputField(), outgoing);
      boolean schemaImposedMode = false;
      BatchSchema targetSchema = null;
      if (column.getErrorMode() == ConvertFromErrorMode.NULL_ON_ERROR) {
        schemaImposedMode = true;
        targetSchema = BatchSchema.of(column.asField(column.getInputField()));

        // if we're running in schema imposed mode, preset the ComplexWriter to the desired write
        // mode - STRUCT or LIST - so that invalid data can't put it into the wrong mode
        if (column.getType().isStruct()) {
          writer.rootAsStruct();
        } else if (column.getType().isList()) {
          writer.rootAsList();
        }
      }

      this.reader =
          JsonReader.builder()
              .setManagedBuf(context.getManagedBuffer())
              .setMaxFieldSize(sizeLimit)
              .setMaxLeafLimit(maxLeafLimit)
              .setAllTextMode(
                  context.getOptions().getOption(ExecConstants.JSON_READER_ALL_TEXT_MODE_VALIDATOR))
              .setEnforceValidJsonDateFormat(
                  context
                      .getOptions()
                      .getOption(PlannerSettings.ENFORCE_VALID_JSON_DATE_FORMAT_ENABLED))
              .setSchemaImposedMode(schemaImposedMode)
              .setAllowRootList(true)
              .setTargetSchema(targetSchema)
              .setColumns(GroupScan.ALL_COLUMNS)
              .setExtendedFormatOptions(new ExtendedFormatOptions())
              // we don't want to limit the row size here because convert from json could contain
              // several call in one row, but in reader,
              // limit w ill be checked for each call separately. And we don't want to
              // double-checking
              // the row size limit,
              // so we will check the row size limit in the operator itself
              .setRowSizeLimitEnabled(false)
              .build();
      this.outgoingVector = outgoingVector;
      this.sizeLimit = sizeLimit;
    }

    abstract byte[] getBytes(int inputIndex);

    public void convert(final int records) {
      try {
        outgoingVector.allocateNew();
        for (int i = 0; i < records; i++) {
          writer.setPosition(i);
          byte[] bytes = getBytes(i);
          if (bytes == null || bytes.length == 0) {
            continue;
          }
          reader.setSource(bytes);

          try {
            final ReadState state = reader.write(writer);
            if (state == ReadState.END_OF_STREAM) {
              throw new EOFException("Unexpected end of stream while parsing JSON data.");
            }
          } catch (JsonReaderIOException ex) {
            if (column.getErrorMode() == ConvertFromErrorMode.NULL_ON_ERROR) {
              // reset the position as it will have been advanced by the reader
              writer.setPosition(i);
              if (column.getType().isStruct()) {
                writer.rootAsStruct().writeNull();
              } else if (column.getType().isList()) {
                writer.rootAsList().writeNull();
              } else {
                throw new UnsupportedOperationException(
                    "Unsupported output vector type " + column.getType().toString());
              }
              logger.trace("NULL_ON_ERROR mode - returning NULL due to error", ex);
            } else {
              throw ex;
            }
          }

          writer.setValueCount(records);
        }

      } catch (Exception ex) {
        throw UserException.dataReadError(ex)
            .message("Failure converting field %s from JSON.", column.getInputField())
            .build(logger);
      }

      if (outgoing.isNewSchema()) {
        // build the schema so we can get the "updated" schema
        outgoing.buildSchema();

        // retrieve the schema of the input field
        final SchemaPath path = SchemaPath.getSimplePath(column.getInputField());
        final TypedFieldId typedFieldId = outgoing.getSchema().getFieldId(path);

        // throw a fieldChangeError back to coordinator with necessary context to update schema.
        throw UserException.jsonFieldChangeError()
            .message(
                "New field in the schema found.  Please reattempt the query.  Multiple attempts may be necessary to fully learn the schema.")
            .setAdditionalExceptionContext(
                new JsonFieldChangeExceptionContext(
                    column.getOriginTable(), column.getOriginField(), typedFieldId.getFinalType()))
            .build(logger);
      }
    }
  }

  @SuppressWarnings("unused")
  public static class ConvertCreator implements Creator<ConvertFromJsonPOP> {

    @Override
    public SingleInputOperator create(OperatorContext context, ConvertFromJsonPOP operator)
        throws ExecutionSetupException {
      return new ConvertFromJsonOperator(context, operator);
    }
  }
}
