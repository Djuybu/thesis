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
package com.dremio.exec.store.easy.text.compliant;

import static com.dremio.common.types.TypeProtos.MinorType.VARBINARY;
import static com.dremio.exec.planner.ExceptionUtils.collapseExceptionMessages;
import static java.util.Objects.requireNonNullElse;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.CastExpressionWithOverflow;
import com.dremio.common.expression.CompleteType;
import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.FunctionCallFactory;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.expression.SupportedEngines;
import com.dremio.common.expression.TypedNullConstant;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.types.TypeProtos;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.common.types.Types;
import com.dremio.common.util.MajorTypeHelper;
import com.dremio.exec.expr.ExpressionEvaluationOptions;
import com.dremio.exec.physical.config.CopyIntoExtendedProperties;
import com.dremio.exec.physical.config.CopyIntoExtendedProperties.PropertyKey;
import com.dremio.exec.physical.config.ExtendedFormatOptions;
import com.dremio.exec.physical.config.ExtendedProperty;
import com.dremio.exec.physical.config.SimpleQueryContext;
import com.dremio.exec.physical.config.copyinto.CopyIntoFileLoadInfo.Builder;
import com.dremio.exec.physical.config.copyinto.CopyIntoFileLoadInfo.CopyIntoFileState;
import com.dremio.exec.physical.config.copyinto.CopyIntoHistoryExtendedProperties;
import com.dremio.exec.physical.config.copyinto.CopyIntoQueryProperties;
import com.dremio.exec.physical.config.copyinto.CopyIntoTransformationProperties;
import com.dremio.exec.physical.config.copyinto.IngestionProperties;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.AbstractRecordReader;
import com.dremio.exec.store.SampleMutator;
import com.dremio.exec.store.copyinto.TransformationContainerHelper;
import com.dremio.exec.store.dfs.FileLoadInfo;
import com.dremio.exec.store.dfs.copyinto.CopyIntoExceptionUtils;
import com.dremio.exec.store.dfs.easy.ExtendedEasyReaderProperties;
import com.dremio.exec.store.easy.EasyFormatUtils;
import com.dremio.exec.store.easy.text.compliant.CompliantTextRecordReader.SetupOption;
import com.dremio.exec.tablefunctions.copyerrors.ValidationErrorRowWriter;
import com.dremio.exec.util.ColumnUtils;
import com.dremio.io.CompressionCodecFactory;
import com.dremio.io.file.FileSystem;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.project.ProjectErrorUtils;
import com.dremio.sabot.op.project.ProjectErrorUtils.ProjectionError;
import com.dremio.sabot.op.project.SimpleProjector;
import com.dremio.sabot.op.scan.OutputMutator;
import com.dremio.service.namespace.file.proto.FileType;
import io.protostuff.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.hadoop.mapred.FileSplit;

/**
 * A CSV record reader supporting schema imposed execution. It means that the target schema may
 * contain non-VARCHAR columns, so the values might be converted. It also implements the scan part
 * of the COPY INTO command including its ON_ERROR option and potential SELECT transformations.
 *
 * <p>For the actual text (CSV) parsing the {@link CompliantTextRecordReader} will be used, then a
 * projector will take care of the required transformations.
 */
public class SchemaImposedTextRecordReader extends AbstractRecordReader {

  /** A runtime exception used to stop the processing and handle the embedded cause exception. */
  private static class StopProcessingException extends RuntimeException {

    StopProcessingException(Throwable cause) {
      super(cause);
    }
  }

  /**
   * Helper class with some base implementations to support overriding behaviours according to the
   * current execution phase.
   */
  private abstract class PhaseHelper implements TextRecordReaderEventHandler {
    private final String name;

    PhaseHelper(String name) {
      this.name = Objects.requireNonNull(name);
    }

    @Override
    public String toString() {
      return name;
    }

    public void setup() throws ExecutionSetupException {
      try {
        VectorContainer projectionOutput = projectionOutput();

        textParser.setup(textOutputMutator);
        textOutputMutator.getContainer().buildSchema();

        ExpressionEvaluationOptions projectorOptions = projectorOptions();
        projector =
            new SimpleProjector(
                context,
                projectorInput(),
                createExpressions(
                    extendedFormatOptions,
                    textOutputMutator.getContainer().getSchema(),
                    projectionOutput.getSchema()),
                projectionOutput,
                projectorOptions);
        projector.setup();
      } catch (Exception e) {
        handleSetupFailure(e);
      }
    }

    VectorContainer projectionOutput() {
      VectorContainer projectionOutput = targetMutator.getContainer();
      projectionErrorVector =
          projectionOutput.addOrGet(
              ColumnUtils.COPY_HISTORY_COLUMN_NAME,
              Types.optional(MinorType.VARCHAR),
              VarCharVector.class);
      return projectionOutput;
    }

    VectorContainer projectorInput() {
      if (transformationContainerHelper != null) {
        return transformationContainerHelper.prepareProjectorInput();
      } else {
        return textOutputMutator.getContainer();
      }
    }

    ExpressionEvaluationOptions projectorOptions() {
      ExpressionEvaluationOptions projectorOptions =
          SimpleProjector.defaultProjectorOptions(context.getOptions());
      // We would like to know the record position, where an error was seen;
      // this is only possible with Java expression evaluation
      projectorOptions.setCodeGenOption(SupportedEngines.CodeGenOption.Java.name());
      projectorOptions.setTrackRecordLevelErrors(true);
      return projectorOptions;
    }

    void handleSetupFailure(Exception exception) throws ExecutionSetupException {
      setupFailedException = new StopProcessingException(exception);
    }

    @Override
    public void handleTextReadingError(
        int fieldIndex, int recordIndexInBatch, long linePosition, Exception error)
        throws IOException {
      ++rejectedRecordsInBatch;
      updateFirstErrorIfRequired(error);
    }

    /**
     * Retrieves the next batch from textParser and invokes the projection on them, then return the
     * number of records that were actually written into the target vectors.
     */
    int parseAndProjectBatch() {
      // rejectedRecordsInBatch is not reset for each parsing+projection cycles for "validation"
      int rejectedRecordsBeforeParsing = rejectedRecordsInBatch;
      int outputRecordsInBatch = textParser.next();
      int rejectedRecordsBeforeProjection = rejectedRecordsInBatch;
      if (transformationContainerHelper != null) {
        transformationContainerHelper.transferData(outputRecordsInBatch);
      }
      projector.eval(outputRecordsInBatch);
      outputRecordsInBatch = processProjectionErrors(outputRecordsInBatch);

      // Increment by the number of projected records (does not contain the errors during parsing)
      // minus the number of rejected records during the projection
      loadedRecords +=
          outputRecordsInBatch - (rejectedRecordsInBatch - rejectedRecordsBeforeProjection);
      // Increment by the number rejected records during this parsing+projection cycle
      rejectedRecords += rejectedRecordsInBatch - rejectedRecordsBeforeParsing;

      return outputRecordsInBatch;
    }

    int processProjectionErrors(int outputRecordsInBatch) {
      if (outputRecordsInBatch == 0) {
        return 0;
      }

      assert outputRecordsInBatch == projectionErrorVector.getValueCount();

      int rejectedRecordsInProjection = outputRecordsInBatch - projectionErrorVector.getNullCount();
      if (rejectedRecordsInProjection > 0) {
        ProjectErrorUtils.parseErrors(projectionErrorVector, fieldNames::get)
            .forEachRemaining(
                projectionError -> {
                  ++rejectedRecordsInBatch;
                  processProjectionError(projectionError);
                });
      }
      return outputRecordsInBatch;
    }

    abstract void processProjectionError(ProjectionError projectionError);

    abstract int writeEventAfterFinalBatch(int recordIndex);

    abstract int handleProcessingFailure(Throwable error);
  }

  /**
   * Helper used for the ON_ERROR 'abort' option: any error will throw an exception so the process
   * will fail.
   */
  private final PhaseHelper abortHelper =
      new PhaseHelper("abort") {

        @Override
        public void handleSetupFailure(Exception exception) throws ExecutionSetupException {
          throw ExecutionSetupException.fromThrowable(
              String.format(
                  "Failure while setting up the reader for file %s: %s",
                  split.getPath(), exception.getMessage()),
              exception);
        }

        @Override
        VectorContainer projectionOutput() {
          // We do not need error vector
          projectionErrorVector = null;
          return targetMutator.getContainer();
        }

        @Override
        ExpressionEvaluationOptions projectorOptions() {
          // We are fine with default options (Gandiva), because we do not need record positions
          return SimpleProjector.defaultProjectorOptions(context.getOptions());
        }

        @Override
        public void handleTextReadingError(
            int fieldIndex, int recordIndexInBatch, long linePosition, Exception error)
            throws IOException {
          TextRecordReaderEventHandler.raiseIO(error);
        }

        @Override
        int processProjectionErrors(int outputRecordsInBatch) {
          assert projectionErrorVector == null;
          // Any error would have already been thrown => no errors in the batch
          return outputRecordsInBatch;
        }

        @Override
        void processProjectionError(ProjectionError projectionError) {
          throw new IllegalStateException("Shall never be invoked");
        }

        @Override
        int writeEventAfterFinalBatch(int recordIndex) {
          return writeEvent(CopyIntoFileState.FULLY_LOADED, recordIndex);
        }

        @Override
        int handleProcessingFailure(Throwable error) {
          throw UserException.dataReadError(error)
              .addContext("File Path", split.getPath().toString())
              .buildSilently();
        }
      };

  /**
   * Helper used for the ON_ERROR 'continue' option: errors will be registered and the process
   * continues. The registered errors are to be returned with the properly processed records.
   */
  private final PhaseHelper continueHelper =
      new PhaseHelper("continue") {

        @Override
        int parseAndProjectBatch() {
          // This loop is required to handle the case of parsing n records but all n records gets
          // rejected during the projection.
          int loadedRecordsInBatch;
          do {
            loadedRecordsInBatch = super.parseAndProjectBatch();
          } while (textParser.hasNext() && loadedRecordsInBatch == 0);
          return loadedRecordsInBatch;
        }

        @Override
        void processProjectionError(ProjectionError projectionError) {
          updateFirstErrorIfRequired(projectionError);
        }

        @Override
        int processProjectionErrors(int outputRecordsInBatch) {
          int outRecords = super.processProjectionErrors(outputRecordsInBatch);
          // Overwrite values with "" to avoid parsing issues from json to CopyIntoFileLoadInfo;
          // meanwhile having non-null values in the error vector makes skipping the related records
          for (int i = 0; i < outRecords; ++i) {
            if (!projectionErrorVector.isNull(i)) {
              projectionErrorVector.setValueLengthSafe(i, 0);
            }
          }
          return outRecords;
        }

        @Override
        int writeEventAfterFinalBatch(int recordIndex) {
          if (rejectedRecords > 0) {
            if (loadedRecords > 0) {
              return writeEvent(CopyIntoFileState.PARTIALLY_LOADED, recordIndex);
            } else {
              return writeEvent(CopyIntoFileState.SKIPPED, recordIndex);
            }
          } else {
            return writeEvent(CopyIntoFileState.FULLY_LOADED, recordIndex);
          }
        }

        @Override
        int handleProcessingFailure(Throwable error) {
          updateFirstErrorIfRequired(error);
          return writeEvent(CopyIntoFileState.SKIPPED, 0);
        }
      };

  /**
   * Helper used for the "dry-run" phase of the ON_ERROR 'skip_file' option: the first error will be
   * registered and the process will be stopped. In case of error only the registered error will be
   * returned. Otherwise, the second phase will be started that is equivalent to the ON_ERROR
   * 'abort' option.
   *
   * @see #abortHelper
   */
  private final PhaseHelper dryRunHelper =
      new PhaseHelper("dry-run") {

        @Override
        public void handleTextReadingError(
            int fieldIndex, int recordIndexInBatch, long linePosition, Exception error) {
          ++rejectedRecords;
          throw new StopProcessingException(error);
        }

        @Override
        int parseAndProjectBatch() {
          int validRecordsInBatch;
          do {
            validRecordsInBatch = super.parseAndProjectBatch();
          } while (validRecordsInBatch > 0);
          phaseHelper = abortHelper;
          loadedRecords = 0;
          rejectedRecords = 0;
          try {
            textOutputMutator.getContainer().setAllCount(0);
            phaseHelper.setup();
            targetMutator.getContainer().setAllCount(0);
          } catch (ExecutionSetupException e) {
            throw new StopProcessingException(e);
          }
          return phaseHelper.parseAndProjectBatch();
        }

        @Override
        void processProjectionError(ProjectionError projectionError) {
          ++rejectedRecords;
          throw new StopProcessingException(projectionError);
        }

        @Override
        int writeEventAfterFinalBatch(int recordIndex) {
          return writeEvent(CopyIntoFileState.FULLY_LOADED, recordIndex);
        }

        @Override
        int handleProcessingFailure(Throwable error) {
          updateFirstErrorIfRequired(error);
          return writeEvent(CopyIntoFileState.SKIPPED, 0);
        }
      };

  /**
   * Helper used for the "validation mode" that is used for the COPY_ERROR table function. Only the
   * occurring errors will be registered and returned.
   */
  private final PhaseHelper validationHelper =
      new PhaseHelper("validation") {

        @Override
        public void setup() throws ExecutionSetupException {
          BatchSchema validationResultSchema = targetMutator.getContainer().getSchema();
          ValueVector[] validationResult =
              new ValueVector[validationResultSchema.getTotalFieldCount()];
          int fieldIx = 0;
          for (Field f : validationResultSchema) {
            validationResult[fieldIx++] = targetMutator.getVector(f.getName());
          }
          validationErrorRowWriter =
              ValidationErrorRowWriter.newVectorWriter(
                  validationResult,
                  filePathForError(),
                  originalJobId,
                  () -> rejectedRecordsInBatch - 1);
          initIndexDiffs();
          super.setup();
        }

        @Override
        VectorContainer projectionOutput() {
          VectorContainer projectionOutput =
              closeLater(new VectorContainer(context.getAllocator()));
          projectionOutput.addSchema(validatedTableSchema);
          projectionOutput.buildSchema();
          projectionErrorVector =
              projectionOutput.addOrGet(
                  ColumnUtils.COPY_HISTORY_COLUMN_NAME,
                  Types.optional(MinorType.VARCHAR),
                  VarCharVector.class);
          return projectionOutput;
        }

        private void initIndexDiffs() {
          if (rejectedRecordIndexDiffs == null) {
            assert lineNumberDiffs == null;
            rejectedRecordIndexDiffs = new TreeMap<>();
            lineNumberDiffs = new TreeMap<>();
          } else {
            // Clear to save heap
            rejectedRecordIndexDiffs.clear();
            lineNumberDiffs.clear();
          }
          rejectedRecordIndexDiffs.put(0, 0);
          lineNumberDiffs.put(0, 0L);
        }

        @Override
        public void handleTextReadingError(
            int fieldIndex, int recordIndexInBatch, long linePosition, Exception error)
            throws IOException {
          long recordIndex =
              recordIndexInBatch + rejectedRecordsInBatch + loadedRecords + rejectedRecords;
          super.handleTextReadingError(fieldIndex, recordIndexInBatch, linePosition, error);
          validationErrorRowWriter.write(
              fieldNames.get(fieldIndex),
              recordIndex + 1, // 0-based -> 1-based
              linePosition + 1, // 0-based -> 1-based
              collapseExceptionMessages(CopyIntoExceptionUtils.redactException(error)));
          int lastDiff = rejectedRecordIndexDiffs.lastEntry().getValue();
          rejectedRecordIndexDiffs.put(recordIndexInBatch, lastDiff + 1);
        }

        @Override
        public void handleStartingNewRecord(int recordIndexInBatch, long linePosition) {
          long diff = linePosition - recordIndexInBatch;
          if (lineNumberDiffs.lastEntry().getValue() != diff) {
            lineNumberDiffs.put(recordIndexInBatch, diff);
          }
        }

        @Override
        int parseAndProjectBatch() {
          int validRecordsInBatch;
          do {
            initIndexDiffs();
            validRecordsInBatch = super.parseAndProjectBatch();
          } while (rejectedRecordsInBatch == 0 && validRecordsInBatch > 0 && textParser.hasNext());
          return rejectedRecordsInBatch;
        }

        @Override
        void processProjectionError(ProjectionError projectionError) {
          int recordIndexInProjection = projectionError.getIdxInBatch();
          long recordNumber =
              recordIndexInProjection
                  + rejectedRecordIndexDiffs.floorEntry(recordIndexInProjection).getValue()
                  + loadedRecords
                  + rejectedRecords;
          long linePosition =
              recordIndexInProjection
                  + lineNumberDiffs.floorEntry(recordIndexInProjection).getValue();
          validationErrorRowWriter.write(
              String.join(", ", projectionError.getFieldNames()),
              recordNumber + 1, // 0-based -> 1-based
              linePosition + 1, // 0-based -> 1-based
              projectionError.getMessage());
        }

        @Override
        int writeEventAfterFinalBatch(int recordIndex) {
          return 0;
        }

        @Override
        int handleProcessingFailure(Throwable error) {
          rejectedRecordsInBatch = 1; // Needed for proper record indexing
          validationErrorRowWriter.write(
              null, 0L, 1L, CopyIntoExceptionUtils.redactException(error).getMessage());
          return 1;
        }
      };

  // The input file split for this record reader
  private final FileSplit split;
  // Settings for the CSV parsing
  private final TextParsingSettings textParsingSettings;
  // The record reader for reading/parsing the CSV file into VARCHAR vectors
  private final CompliantTextRecordReader textParser;
  // The COPY INTO format options to be translated into expressions for the projector
  private final ExtendedFormatOptions extendedFormatOptions;
  // The COPY INTO properties containing the on error option; also used for copy history
  private final CopyIntoQueryProperties copyIntoQueryProperties;
  // Properties used to describe transformations, specified in the SELECT clause of COPY INTO query
  private final CopyIntoTransformationProperties transformationProperties;
  // The context of the current query
  private final SimpleQueryContext queryContext;
  // The ingestion properties for copy history
  private final IngestionProperties ingestionProperties;
  // The target schema of the original COPY INTO command used in the validation phase
  private final BatchSchema validatedTableSchema;
  // The original job id for copy history
  private final String originalJobId;
  // The output of the text reader / input of the projector
  private final SampleMutator textOutputMutator;
  // Transformation container swapper tool tailored for this reader
  private final TransformationContainerHelper transformationContainerHelper;
  // Contains all references to be closed at #close()
  private final List<AutoCloseable> closeables = new ArrayList<>();
  // The projector to convert the input VARCHAR columns to the related target types
  private SimpleProjector projector;
  // The error vector to be used by the projector to store the potential errors
  private VarCharVector projectionErrorVector;
  // The indexed field names to be used for translating the field ids to names
  private List<String> fieldNames;
  // The helper implementations for the current phase
  private PhaseHelper phaseHelper;
  // The target output mutator (containing the actual output of this record reader)
  private OutputMutator targetMutator;
  // Number of rejected records in the current batch (incremented record-by-record)
  private int rejectedRecordsInBatch;
  // Number of overall rejected records (incremented per text parse+projection cycle)
  private long rejectedRecords;
  // Number of overall loaded records (incremented per text parse+projection cycle)
  private long loadedRecords;
  // Contains the record counts skipped by the text parsing till the related batch index positions
  private NavigableMap<Integer, Integer> rejectedRecordIndexDiffs;
  // Contains the differences between the batch record index and the line numbers
  private NavigableMap<Integer, Long> lineNumberDiffs;
  // The row writer for used by the validation phase
  private ValidationErrorRowWriter validationErrorRowWriter;
  // The starting time of the whole record reading process for copy history
  private long processingStartTime;
  // The first error message for copy history
  private String firstErrorMessage;
  // Initialized in case of an exception happened during the setup
  private StopProcessingException setupFailedException;

  public SchemaImposedTextRecordReader(
      FileSplit split,
      CompressionCodecFactory codecFactory,
      FileSystem dfs,
      OperatorContext context,
      TextParsingSettings settings,
      List<SchemaPath> columns,
      ExtendedEasyReaderProperties properties,
      ByteString extendedProperties) {
    super(context, columns);
    this.split = split;
    this.textParsingSettings = settings;
    this.extendedFormatOptions = properties.getExtendedFormatOptions();

    // We use the trimming functionality of the parser because we only want to remove whitespaces
    // outside the quotes. We cannot reproduce this with the projector.
    boolean trimSpace = requireNonNullElse(extendedFormatOptions.getTrimSpace(), false);
    textParsingSettings.setIgnoreLeadingWhitespaces(trimSpace);
    textParsingSettings.setIgnoreTrailingWhitespaces(trimSpace);

    CopyIntoExtendedProperties copyIntoExtendedProperties =
        CopyIntoExtendedProperties.Util.getProperties(extendedProperties)
            .orElse(new CopyIntoExtendedProperties());
    this.copyIntoQueryProperties =
        copyIntoExtendedProperties.getProperty(
            CopyIntoExtendedProperties.PropertyKey.COPY_INTO_QUERY_PROPERTIES,
            CopyIntoQueryProperties.class);
    this.transformationProperties =
        copyIntoExtendedProperties.getProperty(
            CopyIntoExtendedProperties.PropertyKey.COPY_INTO_TRANSFORMATION_PROPERTIES,
            CopyIntoTransformationProperties.class);
    this.queryContext =
        copyIntoExtendedProperties.getProperty(
            CopyIntoExtendedProperties.PropertyKey.QUERY_CONTEXT, SimpleQueryContext.class);

    CopyIntoHistoryExtendedProperties copyIntoHistoryExtendedProperties =
        copyIntoExtendedProperties.getProperty(
            PropertyKey.COPY_INTO_HISTORY_PROPERTIES, CopyIntoHistoryExtendedProperties.class);
    this.ingestionProperties =
        copyIntoExtendedProperties.getProperty(
            CopyIntoExtendedProperties.PropertyKey.INGESTION_PROPERTIES, IngestionProperties.class);

    phaseHelper = getPhaseHelper(copyIntoQueryProperties, copyIntoHistoryExtendedProperties);

    if (copyIntoHistoryExtendedProperties != null) {
      this.validatedTableSchema = copyIntoHistoryExtendedProperties.getValidatedTableSchema();
      this.originalJobId = copyIntoHistoryExtendedProperties.getOriginalJobId();
    } else {
      this.validatedTableSchema = null;
      this.originalJobId = null;
    }

    List<SchemaPath> selectedColumns;
    if (transformationProperties != null) {
      if (!settings.isHeaderExtractionEnabled()) {
        // expecting $1, $2, $3... as column identifiers
        settings.setAutoGenerateColumnNames(true);
        settings.setColumnNameGenerationLogic(
            TextParsingSettings.ColumnNameGenerationType.COLUMN_NUM);
      }
      selectedColumns =
          transformationProperties.getProperties().stream()
              .map(p -> p.getSourceColNames())
              .flatMap(List::stream)
              .distinct()
              .map(SchemaPath::getSimplePath)
              .collect(Collectors.toList());
    } else {
      if (validatedTableSchema != null) {
        selectedColumns =
            calculateSelection(
                validatedTableSchema.getFields().stream().map(Field::getName),
                settings.isHeaderExtractionEnabled());
      } else {
        selectedColumns =
            calculateSelection(
                columns.stream().map(SchemaImposedTextRecordReader::asSimplePath),
                settings.isHeaderExtractionEnabled());
      }
    }
    textParser =
        closeLater(
            new CompliantTextRecordReader(
                split,
                codecFactory,
                dfs,
                context,
                settings,
                selectedColumns,
                phaseHelper,
                EnumSet.of(
                    SetupOption.ENSURE_NO_DUPLICATE_COLUMNS_IN_SELECTION,
                    SetupOption.ENSURE_NO_EMPTY_SELECTION)));
    textOutputMutator = closeLater(new SampleMutator(context.getAllocator()));
    transformationContainerHelper =
        transformationProperties != null
            ? closeLater(new TransformationContainerHelper(context, textOutputMutator))
            : null;
  }

  List<SchemaPath> calculateSelection(Stream<String> columns, boolean headerExtraction) {
    Stream<String> filtered =
        columns.filter(path -> !ColumnUtils.COPY_HISTORY_COLUMN_NAME.equals(path));
    if (headerExtraction) {
      return filtered.map(SchemaPath::getSimplePath).collect(Collectors.toList());
    } else {
      int columnCount = Math.toIntExact(filtered.count());
      return IntStream.range(0, columnCount)
          .mapToObj(TextColumnNameGenerator::columnNameForIndex)
          .map(SchemaPath::getSimplePath)
          .collect(Collectors.toList());
    }
  }

  private static String asSimplePath(SchemaPath schemaPath) {
    if (!schemaPath.isSimplePath()) {
      throw new IllegalArgumentException("Selection column is not simple: " + schemaPath);
    }
    return schemaPath.getRootSegment().getPath();
  }

  private PhaseHelper getPhaseHelper(
      CopyIntoQueryProperties copyIntoQueryProperties,
      CopyIntoHistoryExtendedProperties copyIntoHistoryExtendedProperties) {
    if (copyIntoHistoryExtendedProperties != null) {
      return validationHelper;
    }
    if (copyIntoQueryProperties == null) {
      return abortHelper;
    }
    switch (copyIntoQueryProperties.getOnErrorOption()) {
      case ABORT:
        return abortHelper;
      case CONTINUE:
        return continueHelper;
      case SKIP_FILE:
        return dryRunHelper;
      default:
        throw new IllegalArgumentException(
            "Unknown ON_ERROR option: " + copyIntoQueryProperties.getOnErrorOption());
    }
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {
    processingStartTime = System.currentTimeMillis();
    targetMutator = output;
    phaseHelper.setup();
  }

  private List<NamedExpression> createExpressions(
      ExtendedFormatOptions extendedFormatOptions,
      BatchSchema sourceSchema,
      BatchSchema targetSchema) {
    List<NamedExpression> expressions = new ArrayList<>();
    fieldNames = new ArrayList<>();
    if (transformationProperties != null) {
      createExpressionsForTransformation(sourceSchema, targetSchema, expressions);
    } else {
      createExpressionsForSimple(extendedFormatOptions, sourceSchema, targetSchema, expressions);
    }
    ensureErrorVector(expressions);
    return expressions;
  }

  private void createExpressionsForSimple(
      ExtendedFormatOptions extendedFormatOptions,
      BatchSchema sourceSchema,
      BatchSchema targetSchema,
      List<NamedExpression> expressions) {

    if (textParsingSettings.isHeaderExtractionEnabled()) {
      // Column name based matching
      Map<String, Field> targetFields =
          targetSchema.getFields().stream()
              .collect(
                  Collectors.toMap(field -> field.getName().toLowerCase(), Function.identity()));
      for (Field sourceField : sourceSchema.getFields()) {
        Field targetField = targetFields.get(sourceField.getName().toLowerCase());
        if (targetField != null) {
          expressions.add(
              EasyFormatUtils.createExpression(
                  SchemaPath.getSimplePath(sourceField.getName()),
                  targetField,
                  extendedFormatOptions));
          fieldNames.add(targetField.getName());
        }
      }
    } else {
      // Column index based matching
      for (int i = 0, n = Math.min(sourceSchema.getFieldCount(), targetSchema.getFieldCount());
          i < n;
          ++i) {
        Field targetField = targetSchema.getColumn(i);
        expressions.add(
            EasyFormatUtils.createExpression(
                SchemaPath.getSimplePath(sourceSchema.getColumn(i).getName()),
                targetField,
                extendedFormatOptions));
        fieldNames.add(targetField.getName());
      }
    }
  }

  private void createExpressionsForTransformation(
      BatchSchema sourceSchema, BatchSchema targetSchema, List<NamedExpression> expressions) {
    for (CopyIntoTransformationProperties.Property transformationProperty :
        transformationProperties.getProperties()) {
      ensureSourceColumnsPresentForTransformation(sourceSchema, transformationProperty);
      String targetColName = transformationProperty.getTargetColName();
      LogicalExpression transformationExpression =
          transformationProperty.getTransformationExpression();
      for (Field field : targetSchema.getFields()) {
        TypeProtos.MajorType fieldType = MajorTypeHelper.getMajorTypeForField(field);
        String name = field.getName();
        if (name.equalsIgnoreCase(targetColName)) {
          FieldReference outputRef = FieldReference.getWithQuotedRef(name);
          CompleteType targetType = CompleteType.fromField(field);
          if (targetType.isUnion() || targetType.isComplex()) {
            throw new UnsupportedOperationException(
                "Union and complex types and supported for CSV COPY INTO");
          }
          LogicalExpression cast;
          if (outputRef.getCompleteType().isText()
              && (fieldType.getMinorType().equals(MinorType.VARCHAR)
                  || fieldType.getMinorType().equals(VARBINARY))) {
            cast = transformationExpression;
          } else if (fieldType.getMinorType().equals(MinorType.DECIMAL)) {
            cast = new CastExpressionWithOverflow(transformationExpression, fieldType);
          } else {
            cast = FunctionCallFactory.createCast(fieldType, transformationExpression);
          }
          expressions.add(new NamedExpression(cast, outputRef));
          fieldNames.add(field.getName());
          break;
        }
      }
    }
  }

  private void ensureErrorVector(List<NamedExpression> expressions) {
    if (projectionErrorVector != null) {
      // We need to add an expression for the history column, otherwise it won't be created in the
      // projector
      expressions.add(
          new NamedExpression(
              new TypedNullConstant(CompleteType.VARCHAR),
              FieldReference.getWithQuotedRef(ColumnUtils.COPY_HISTORY_COLUMN_NAME)));
    }
  }

  private void ensureSourceColumnsPresentForTransformation(
      BatchSchema sourceSchema, CopyIntoTransformationProperties.Property transformation) {
    List<String> expectedSourceColumns = transformation.getSourceColNames();
    if (!sourceSchema.getFields().stream()
        .map(f -> f.getName().toLowerCase())
        .collect(Collectors.toList())
        .containsAll(expectedSourceColumns)) {
      throw UserException.validationError()
          .message(
              "Input file schema %s does not contain all of the columns %s that are required to "
                  + "evaluate expressions for target table column %s.",
              sourceSchema.toString(), expectedSourceColumns, transformation.getTargetColName())
          .buildSilently();
    }
  }

  @Override
  public int next() {
    // No more records
    if (phaseHelper == null) {
      return 0;
    }
    int recordsInBatch;
    try {
      rejectedRecordsInBatch = 0;
      if (setupFailedException != null) {
        throw setupFailedException;
      }
      recordsInBatch = phaseHelper.parseAndProjectBatch();
      if (!textParser.hasNext()) {
        int eventCount = phaseHelper.writeEventAfterFinalBatch(recordsInBatch);
        phaseHelper = null;
        recordsInBatch += eventCount;
      }
    } catch (StopProcessingException e) {
      recordsInBatch = phaseHelper.handleProcessingFailure(e.getCause());
      phaseHelper = null;
    } catch (Exception e) {
      throw UserException.dataReadError(e)
          .addContext("File Path", split.getPath().toString())
          .buildSilently();
    }
    targetMutator.getContainer().setAllCount(recordsInBatch);
    return recordsInBatch;
  }

  private int writeEvent(CopyIntoFileState fileState, int recordIndex) {
    if (copyIntoQueryProperties != null && copyIntoQueryProperties.shouldRecord(fileState)) {
      VarCharVector copyHistoryColumn = null;
      for (ValueVector column : targetMutator.getVectors()) {
        if (ColumnUtils.COPY_HISTORY_COLUMN_NAME.equals(column.getName())) {
          copyHistoryColumn = column instanceof VarCharVector ? (VarCharVector) column : null;
        }
      }
      if (copyHistoryColumn == null) {
        throw new IllegalStateException("Unable to find proper column history vector");
      }
      byte[] fileLoadInfo = getFileLoadInfoJson(fileState).getBytes(StandardCharsets.UTF_8);
      copyHistoryColumn.setSafe(recordIndex, fileLoadInfo, 0, fileLoadInfo.length);
      return 1;
    } else {
      return 0;
    }
  }

  private void updateFirstErrorIfRequired(Throwable ex) {
    if (firstErrorMessage == null) {
      firstErrorMessage = collapseExceptionMessages(CopyIntoExceptionUtils.redactException(ex));
    }
  }

  private String getFileLoadInfoJson(CopyIntoFileState fileState) {
    Builder builder =
        new Builder(
                queryContext.getQueryId(),
                queryContext.getUserName(),
                queryContext.getTableNamespace(),
                copyIntoQueryProperties.getStorageLocation(),
                filePathForError(),
                extendedFormatOptions,
                FileType.CSV.name(),
                fileState)
            .setRecordsLoadedCount(loadedRecords)
            .setRecordsRejectedCount(rejectedRecords)
            .setRecordDelimiter(new String(textParsingSettings.getNewLineDelimiter()))
            .setFieldDelimiter(new String(textParsingSettings.getDelimiter()))
            .setQuoteChar(new String(textParsingSettings.getQuote()))
            .setEscapeChar(new String(textParsingSettings.getQuoteEscape()))
            .setExtractHeader(textParsingSettings.isHeaderExtractionEnabled())
            .setSkipLines(textParsingSettings.getSkipLines())
            .setBranch(copyIntoQueryProperties.getBranch())
            .setProcessingStartTime(processingStartTime)
            .setFileSize(split.getLength())
            .setFirstErrorMessage(firstErrorMessage);

    if (ingestionProperties != null) {
      builder
          .setPipeName(ingestionProperties.getPipeName())
          .setPipeId(ingestionProperties.getPipeId())
          .setFileNotificationTimestamp(ingestionProperties.getNotificationTimestamp())
          .setIngestionSourceType(ingestionProperties.getIngestionSourceType())
          .setRequestId(ingestionProperties.getRequestId());
    }

    if (transformationProperties != null) {
      builder.setTransformationProperties(
          ExtendedProperty.Util.serialize(transformationProperties));
    }

    return FileLoadInfo.Util.getJson(builder.build());
  }

  String filePathForError() {
    // We don't care about the URI schema but the actual path only in the errors
    return split.getPath().toUri().getPath();
  }

  private <C extends AutoCloseable> C closeLater(C closeable) {
    closeables.add(closeable);
    return closeable;
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(closeables);
  }
}
