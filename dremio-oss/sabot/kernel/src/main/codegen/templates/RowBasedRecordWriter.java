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
<@pp.dropOutputFile />
<@pp.changeOutputFile name="/com/dremio/exec/store/RowBasedRecordWriter.java" />
<#include "/@includes/license.ftl" />

package com.dremio.exec.store;

import org.apache.arrow.vector.complex.reader.FieldReader;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper.RowSizeLimitExceptionType;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.store.EventBasedRecordWriter.FieldConverter;
import com.dremio.exec.store.RecordWriter.OutputEntryListener;
import org.apache.arrow.vector.complex.reader.FieldReader;

import java.io.IOException;
import java.lang.UnsupportedOperationException;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Extension of RecordWriter for row based writer output. If the writer output format requires row based
 * accessing of records, then it should implement this interface.
 */
public abstract class RowBasedRecordWriter implements RecordWriter {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(RowBasedRecordWriter.class);
  protected VectorAccessible incoming;
  protected OutputEntryListener listener;
  protected WriteStatsListener writeStatsListener;
  protected EventBasedRecordWriter eventBasedRecordWriter;

  private final int rowSizeLimit;
  private final int averageBatchRowSizeLimit;
  private final boolean rowSizeLimitFlagEnabled;

  private int currentRowSize;
  private boolean rowSizeLimitCheckEnabled;

  public RowBasedRecordWriter(OperatorContext context) {
    if (context != null) {
      this.rowSizeLimit =
          Math.toIntExact(context.getOptions().getOption(ExecConstants.LIMIT_ROW_SIZE_BYTES));
      this.rowSizeLimitFlagEnabled =
          context.getOptions().getOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT);
      this.averageBatchRowSizeLimit =
          Math.toIntExact(context.getOptions().getOption(ExecConstants.LIMIT_BATCH_ROW_SIZE_BYTES));
    } else {
      this.rowSizeLimit = Math.toIntExact(ExecConstants.LIMIT_ROW_SIZE_BYTES.getDefault().getNumVal());
      this.rowSizeLimitFlagEnabled = ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT.getDefault().getBoolVal();
      this.averageBatchRowSizeLimit = Math.toIntExact(ExecConstants.LIMIT_BATCH_ROW_SIZE_BYTES.getDefault().getNumVal());
    }
  }

  public final void setup(final VectorAccessible incoming, OutputEntryListener listener,
                          WriteStatsListener statsListener) throws IOException {
    this.incoming = incoming;
    this.listener = listener;
    this.writeStatsListener = statsListener;
    setup();
  }

  public abstract void setup() throws IOException;

  @Override
  public int writeBatch(int offset, int length) throws IOException {
    if (rowSizeLimitFlagEnabled) {
      int incomingBatchAvgRowSize = com.dremio.sabot.op.join.vhash.spill.slicer.Sizer.getAverageRowSize(incoming, length);
      //if the average row size of the incoming batch is greater than the averageBatchRowSizeLimit, then enable row size limit check
      rowSizeLimitCheckEnabled = incomingBatchAvgRowSize <= averageBatchRowSizeLimit ? false : true;
    }

    if (this.eventBasedRecordWriter == null) {
      this.eventBasedRecordWriter = new EventBasedRecordWriter(incoming, this);
    }
    return eventBasedRecordWriter.write(offset, length);
  }

  public boolean isRowSizeLimitCheckEnabled() {
    return rowSizeLimitCheckEnabled;
  }

  /**
   * Called before starting writing fields in a record.
   * @throws IOException
   */
  public abstract void startRecord() throws IOException;

  /** Add the field value given in <code>valueHolder</code> at the given column number <code>fieldId</code>. */
  public abstract FieldConverter getNewMapConverter(int fieldId, String fieldName, FieldReader reader);
  public abstract FieldConverter getNewListConverter(int fieldId, String fieldName, FieldReader reader);
  public abstract FieldConverter getNewUnionConverter(int fieldId, String fieldName, FieldReader reader);
  public abstract FieldConverter getNewNullConverter(int fieldId, String fieldName, FieldReader reader);
  public abstract FieldConverter getNewStructConverter(int fieldId, String fieldName, FieldReader reader);

<#list vv.types as type>
  <#list type.minor as minor>
  <#assign typeMapping = TypeMappings[minor.class]!{}>
  <#assign supported = typeMapping.supported!true>
  <#if supported>
  /** Add the field value given in <code>valueHolder</code> at the given column number <code>fieldId</code>. */
  public abstract FieldConverter getNewNullable${minor.class}Converter(int fieldId, String fieldName, FieldReader reader);
  </#if>
  </#list>
</#list>

  /**
   * Called after adding all fields in a particular record are added using add{TypeHolder}(fieldId, TypeHolder) methods.
   * @throws IOException
   */
  public abstract void endRecord() throws IOException;

  public void checkRowSizeLimit() {
    if (!isRowSizeLimitCheckEnabled()) {
      return;
    }
    RowSizeLimitExceptionHelper.checkSizeLimit(currentRowSize, rowSizeLimit, RowSizeLimitExceptionType.WRITE, logger);
    resetRowSize();
  }

  public void incrementRowSize(int rowSize) {
    currentRowSize += rowSize;
  }

  private void resetRowSize() {
    currentRowSize = 0;
  }

}
