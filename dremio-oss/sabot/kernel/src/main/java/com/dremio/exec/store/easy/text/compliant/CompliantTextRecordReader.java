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

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper.RowSizeLimitExceptionType;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.exception.SchemaChangeException;
import com.dremio.exec.expr.TypeHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.AbstractRecordReader;
import com.dremio.io.CompressionCodecFactory;
import com.dremio.io.FSInputStream;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.FileSystemUtils;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.scan.OutputMutator;
import com.google.common.collect.Maps;
import com.univocity.parsers.common.TextParsingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.util.CallBack;
import org.apache.hadoop.mapred.FileSplit;

// New text reader, complies with the RFC 4180 standard for text/csv files
public class CompliantTextRecordReader extends AbstractRecordReader {

  // Some option flags for special requirements
  enum SetupOption {
    /** Exception will be thrown if duplicate column names found in the file which are selected. */
    ENSURE_NO_DUPLICATE_COLUMNS_IN_SELECTION,
    /** Exception will be thrown if no matching columns found for the selection. */
    ENSURE_NO_EMPTY_SELECTION,
    /**
     * Adds the columns from the selection to the target schema which have no matching in the actual
     * column names. (Not sure why it is required but this is the default behavior which is not
     * correct for COPY INTO.)
     */
    EXTEND_TARGET_SCHEMA_WITH_UNMATCHED_COLUMNS
  }

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(CompliantTextRecordReader.class);

  private static final int READ_BUFFER = 1024 * 1024;
  private static final int WHITE_SPACE_BUFFER = 64 * 1024;

  // settings to be used while parsing
  private final TextParsingSettings settings;
  // Chunk of the file to be read by this reader
  private final FileSplit split;
  // text reader implementation
  private TextReader reader;
  private final CompressionCodecFactory codecFactory;
  private final FileSystem dfs;

  private final String filePathForError;
  private final List<AutoCloseable> closeables = new ArrayList<>();
  private final TextRecordReaderEventHandler eventHandler;
  private boolean finished;
  private final Set<SetupOption> setupOptions;

  CompliantTextRecordReader(
      FileSplit split,
      CompressionCodecFactory codecFactory,
      FileSystem dfs,
      OperatorContext context,
      TextParsingSettings settings,
      List<SchemaPath> columns,
      TextRecordReaderEventHandler eventHandler,
      Set<SetupOption> setupOptions) {
    super(context, columns);
    this.split = split;
    this.settings = settings;
    this.codecFactory = codecFactory;
    this.dfs = dfs;

    // We don't care about the URI schema but the actual path only in the errors
    this.filePathForError = split.getPath().toUri().getPath();

    this.eventHandler = eventHandler;
    this.setupOptions = Objects.requireNonNull(setupOptions);
  }

  public CompliantTextRecordReader(
      FileSplit split,
      CompressionCodecFactory codecFactory,
      FileSystem dfs,
      OperatorContext context,
      TextParsingSettings settings,
      List<SchemaPath> columns) {
    this(
        split,
        codecFactory,
        dfs,
        context,
        settings,
        columns,
        null,
        EnumSet.of(SetupOption.EXTEND_TARGET_SCHEMA_WITH_UNMATCHED_COLUMNS));
  }

  // checks to see if we are querying all columns(star) or individual columns
  @Override
  public boolean isStarQuery() {
    if (settings.isUseRepeatedVarChar()) {
      return super.isStarQuery()
          || getColumns().stream().anyMatch(path -> path.equals(RepeatedVarCharOutput.COLUMNS));
    }
    return super.isStarQuery();
  }

  /**
   * Performs the initial setup required for the record reader. Initializes the input stream,
   * handling of the output record batch and the actual reader to be used.
   *
   * @param outputMutator Used to create the schema in the output record batch
   * @throws ExecutionSetupException
   */
  @Override
  public void setup(OutputMutator outputMutator) throws ExecutionSetupException {
    // setup Output, Input, and Reader
    TextInput input;
    final TextOutput output;
    final int sizeLimit =
        Math.toIntExact(this.context.getOptions().getOption(ExecConstants.LIMIT_FIELD_SIZE_BYTES));
    finished = false;
    try {
      // setup Input using InputStream
      input = createInput();

      if (isSkipQuery()) {
        if (settings.isHeaderExtractionEnabled()) {
          extractHeader();
        }
        // When no columns are projected try to make the parser do less work by turning off
        // options that have extra cost
        settings.setIgnoreLeadingWhitespaces(false);
        settings.setIgnoreTrailingWhitespaces(false);
        output = new TextCountOutput();
      } else {
        // setup Output using OutputMutator
        if (settings.isHeaderExtractionEnabled()) {
          // extract header and use that to setup a set of VarCharVectors
          String[] fieldNames = extractHeader();
          output =
              new FieldVarCharOutput(
                  outputMutator, fieldNames, getColumns(), isStarQuery(), sizeLimit, setupOptions);
          output.init();
        } else if (settings.isAutoGenerateColumnNames()) {
          String[] fieldNames = generateColumnNames();
          output =
              new FieldVarCharOutput(
                  outputMutator, fieldNames, getColumns(), isStarQuery(), sizeLimit, setupOptions);
          output.init();
        } else {
          // simply use RepeatedVarCharVector
          output = new RepeatedVarCharOutput(outputMutator, getColumns(), isStarQuery(), sizeLimit);
          output.init();
        }
        if (setupOptions.contains(SetupOption.ENSURE_NO_EMPTY_SELECTION)
            && !output.hasSelectedColumns()) {
          throw new SchemaMismatchException(
              String.format(
                  "No column name matches target schema %s in file %s",
                  getColumns(), split.getPath().toUri().getPath()));
        }
      }

      reader = createReader(input, output);
      reader.start();
    } catch (StreamFinishedPseudoException e) {
      finished = true;
    } catch (IOException e) {
      String bestEffortMessage = bestEffortMessageForUnknownException(e.getCause());
      if (bestEffortMessage != null) {
        throw new ExecutionSetupException(bestEffortMessage);
      }
      throw new ExecutionSetupException(
          String.format("Failure while setting up text reader for file %s", split.getPath()), e);
    } catch (SchemaChangeException e) {
      throw new ExecutionSetupException(
          String.format("Failure while setting up text reader for file %s", split.getPath()), e);
    } catch (IllegalArgumentException e) {
      throw UserException.dataReadError(e)
          .addContext("File Path", split.getPath().toString())
          .buildSilently();
    }
  }

  private TextInput createInput() throws IOException {
    ArrowBuf readBuffer = closeLater(this.context.getAllocator().buffer(READ_BUFFER));
    FSInputStream stream =
        FileSystemUtils.openPossiblyCompressedStream(
            codecFactory, dfs, Path.of(split.getPath().toUri()));
    return new TextInput(
        settings, stream, readBuffer, split.getStart(), split.getStart() + split.getLength());
  }

  private TextReader createReader(TextInput input, TextOutput output) {
    ArrowBuf whitespaceBuffer = closeLater(this.context.getAllocator().buffer(WHITE_SPACE_BUFFER));
    return closeLater(
        new TextReader(settings, input, output, whitespaceBuffer, filePathForError, eventHandler));
  }

  private String[] readFirstLineForColumnNames()
      throws ExecutionSetupException, SchemaChangeException, IOException {
    // setup Output using OutputMutator
    // we should use a separate output mutator to avoid reshaping query output with header data
    try (HeaderOutputMutator hOutputMutator = new HeaderOutputMutator();
        ArrowBuf readBufferInReader = this.context.getAllocator().buffer(READ_BUFFER);
        ArrowBuf whitespaceBufferInReader =
            this.context.getAllocator().buffer(WHITE_SPACE_BUFFER)) {
      final int sizeLimit =
          Math.toIntExact(
              this.context.getOptions().getOption(ExecConstants.LIMIT_FIELD_SIZE_BYTES));
      final RepeatedVarCharOutput hOutput =
          new RepeatedVarCharOutput(hOutputMutator, getColumns(), true, sizeLimit);
      hOutput.init();
      this.allocate(hOutputMutator.fieldVectorMap);

      // setup Input using InputStream
      // we should read file header irrespective of split given to this reader
      FSInputStream hStream =
          FileSystemUtils.openPossiblyCompressedStream(
              codecFactory, dfs, Path.of(split.getPath().toUri()));
      TextInput hInput =
          new TextInput(
              settings, hStream, readBufferInReader, 0, Math.min(READ_BUFFER, split.getLength()));
      // setup Reader using Input and Output
      TextReader reader =
          new TextReader(
              settings, hInput, hOutput, whitespaceBufferInReader, filePathForError, null);

      String[] fieldNames;
      try {
        reader.start();

        // extract first non-empty row
        do {
          TextReader.RecordReaderStatus status = reader.parseNext();
          if (TextReader.RecordReaderStatus.SUCCESS != status) {
            // end of file most likely
            throw StreamFinishedPseudoException.INSTANCE;
          }

          // grab the field names from output
          fieldNames = hOutput.getTextOutput();
        } while (fieldNames == null);

        if (settings.isTrimHeader()) {
          for (int i = 0; i < fieldNames.length; i++) {
            fieldNames[i] = fieldNames[i].trim();
          }
        }
        return fieldNames;
      } finally {
        hOutput.close();
        // cleanup and set to skip the first line next time we read input
        reader.close();
      }
    }
  }

  /**
   * This method is responsible to implement logic for extracting header from text file Currently it
   * is assumed to be first line if headerExtractionEnabled is set to true TODO: enhance to support
   * more common header patterns
   *
   * @return field name strings
   */
  private String[] extractHeader()
      throws SchemaChangeException, IOException, ExecutionSetupException {
    assert (settings.isHeaderExtractionEnabled());

    // don't skip header in case skipFirstLine is set true
    settings.setSkipFirstLine(false);
    final String[] fieldNames = readFirstLineForColumnNames();
    settings.setSkipFirstLine(true);
    if (setupOptions.contains(SetupOption.ENSURE_NO_DUPLICATE_COLUMNS_IN_SELECTION)) {
      // The real check will be done at FieldVarCharOutput
      return fieldNames;
    } else {
      return validateColumnNames(fieldNames);
    }
  }

  public static String[] validateColumnNames(String[] fieldNames) {
    final Map<String, Integer> uniqueFieldNames = Maps.newHashMap();
    if (fieldNames != null) {
      for (int i = 0; i < fieldNames.length; ++i) {
        if (fieldNames[i].isEmpty()) {
          fieldNames[i] = TextColumnNameGenerator.columnNameForIndex(i);
        }
        // If we have seen this column name before, add a suffix
        final Integer count = uniqueFieldNames.get(fieldNames[i]);
        if (count != null) {
          uniqueFieldNames.put(fieldNames[i], count + 1);
          fieldNames[i] = fieldNames[i] + count;
        } else {
          uniqueFieldNames.put(fieldNames[i], 0);
        }
      }
      return fieldNames;
    }
    return null;
  }

  /**
   * Generate fields names per column in text file. Read first line and count columns and return
   * fields names either like excel sheet A, B, C and so on or $1, $2, $3 ... $n
   *
   * @return field name strings, null if no records found in text file.
   */
  private String[] generateColumnNames()
      throws SchemaChangeException, IOException, ExecutionSetupException {
    assert (settings.isAutoGenerateColumnNames());

    final boolean shouldSkipFirstLine = settings.isSkipFirstLine();
    settings.setSkipFirstLine(false);
    final String[] columns = readFirstLineForColumnNames();
    settings.setSkipFirstLine(shouldSkipFirstLine);
    if (columns != null && columns.length > 0) {
      String[] fieldNames = new String[columns.length];
      for (int i = 0; i < columns.length; ++i) {
        switch (settings.getColumnNameGenerationLogic()) {
          case COLUMN_NUM:
            fieldNames[i] = "$" + (i + 1);
            break;
          case EXCEL:
          default:
            fieldNames[i] = TextColumnNameGenerator.columnNameForIndex(i);
            break;
        }
      }
      return fieldNames;
    } else {
      return null;
    }
  }

  /**
   * Generates the next record batch
   *
   * @return number of records in the batch
   */
  @Override
  public int next() {
    if (!hasNext()) {
      return 0;
    }
    try {
      reader.resetForNextBatch();
      int recordCount = 0;

      // We return a maximum of configured batch size worth of records.
      // (In case of an errorHandler is used, the erroneous records might be skipped, so they are
      // not counted in the number of records written in the batch.)
      loop:
      while (recordCount < numRowsPerBatch) {
        TextReader.RecordReaderStatus status = reader.parseNext();
        if (rowSizeLimitEnabled) {
          RowSizeLimitExceptionHelper.checkSizeLimit(
              reader.getAndResetRowSize(), rowSizeLimit, RowSizeLimitExceptionType.READ, logger);
        }
        switch (status) {
          case SUCCESS:
            recordCount++;
            break;
          case END:
            finished = true;
            break loop;
          case SKIP:
            continue;
          default:
            throw new IllegalStateException("Unknown record reader status: " + status);
        }
      }
      reader.finishBatch();
      return recordCount;
    } catch (IOException | TextParsingException e) {
      throw UserException.dataReadError(e)
          .addContext(
              "Failure while reading file %s. Happened at or shortly before byte position %d.",
              split.getPath(), reader.getPos())
          .buildSilently();
    }
  }

  boolean hasNext() {
    return !finished;
  }

  /**
   * Cleanup state once we are finished processing all the records. This would internally close the
   * input stream we are reading from.
   */
  @Override
  public void close() throws Exception {
    try {
      AutoCloseables.close(closeables);
    } finally {
      reader = null;
    }
  }

  @Override
  public void allocate(Map<String, ValueVector> vectorMap) throws OutOfMemoryException {
    int estimatedRecordCount;
    if ((reader != null) && (reader.getInput() != null) && (vectorMap.size() > 0)) {
      final OptionManager options = context.getOptions();
      final int listSizeEstimate = (int) options.getOption(ExecConstants.BATCH_LIST_SIZE_ESTIMATE);
      final int mapSizeEstimate = (int) options.getOption(ExecConstants.BATCH_MAP_SIZE_ESTIMATE);
      final int varFieldSizeEstimate =
          (int) options.getOption(ExecConstants.BATCH_VARIABLE_FIELD_SIZE_ESTIMATE);
      final int estimatedRecordSize =
          BatchSchema.estimateRecordSize(
              vectorMap, listSizeEstimate, mapSizeEstimate, varFieldSizeEstimate);
      if (estimatedRecordSize > 0) {
        estimatedRecordCount =
            Math.min(reader.getInput().length / estimatedRecordSize, numRowsPerBatch);
      } else {
        estimatedRecordCount = numRowsPerBatch;
      }
    } else {
      estimatedRecordCount = numRowsPerBatch;
    }
    for (final ValueVector v : vectorMap.values()) {
      v.setInitialCapacity(estimatedRecordCount);
      v.allocateNew();
    }
  }

  private <T extends AutoCloseable> T closeLater(T closeable) {
    closeables.add(closeable);
    return closeable;
  }

  /**
   * TextRecordReader during its first phase read to extract header should pass its own
   * OutputMutator to avoid reshaping query output. This class provides OutputMutator for header
   * extraction.
   */
  private class HeaderOutputMutator implements OutputMutator, AutoCloseable {

    private final Map<String, ValueVector> fieldVectorMap = Maps.newHashMap();

    @Override
    public <T extends ValueVector> T addField(Field field, Class<T> clazz)
        throws SchemaChangeException {
      ValueVector v = fieldVectorMap.get(field.getName().toLowerCase());
      if (v == null || v.getClass() != clazz) {
        // Field does not exist add it to the map
        v = TypeHelper.getNewVector(field, context.getAllocator());
        if (!clazz.isAssignableFrom(v.getClass())) {
          throw new SchemaChangeException(
              String.format(
                  "Class %s was provided, expected %s.",
                  clazz.getSimpleName(), v.getClass().getSimpleName()));
        }
        v.allocateNew();
        fieldVectorMap.put(field.getName().toLowerCase(), v);
      }
      return clazz.cast(v);
    }

    @Override
    public ValueVector getVector(String name) {
      return fieldVectorMap.get((name != null) ? name.toLowerCase() : name);
    }

    @Override
    public Collection<ValueVector> getVectors() {
      return fieldVectorMap.values();
    }

    @Override
    public void allocate(int recordCount) {
      // do nothing for now
    }

    @Override
    public ArrowBuf getManagedBuffer() {
      return context.getManagedBuffer();
    }

    @Override
    public CallBack getCallBack() {
      return null;
    }

    @Override
    public boolean getAndResetSchemaChanged() {
      return false;
    }

    @Override
    public boolean getSchemaChanged() {
      return false;
    }

    /**
     * Since this OutputMutator is passed by TextRecordReader to get the header out the mutator
     * might not get cleaned up elsewhere. TextRecordReader will call this method to clear any
     * allocations
     */
    @Override
    public void close() {
      for (final ValueVector v : fieldVectorMap.values()) {
        v.clear();
      }
      fieldVectorMap.clear();
    }
  }

  @Override
  public boolean supportsSkipAllQuery() {
    return true;
  }
}
