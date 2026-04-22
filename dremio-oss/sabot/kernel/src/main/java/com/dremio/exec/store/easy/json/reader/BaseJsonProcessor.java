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
package com.dremio.exec.store.easy.json.reader;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.store.easy.json.JsonProcessor;
import com.dremio.exec.util.RowSizeUtil;
import com.dremio.sabot.exec.context.OperatorContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TreeTraversingParser;
import java.io.IOException;
import java.io.InputStream;
import org.apache.arrow.vector.complex.writer.BaseWriter;
import org.apache.arrow.vector.types.Types.MinorType;
import org.apache.calcite.util.Pair;

public abstract class BaseJsonProcessor implements JsonProcessor {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
          .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

  protected JsonParser parser;
  protected final int rowSizeLimit;
  protected boolean rowSizeLimitEnabled;
  protected int currentRowSize;
  protected int fixedDataLenPerRow;

  public BaseJsonProcessor(OperatorContext context) {
    if (context != null) {
      this.rowSizeLimit =
          Math.toIntExact(context.getOptions().getOption(ExecConstants.LIMIT_ROW_SIZE_BYTES));
    } else {
      this.rowSizeLimit =
          Math.toIntExact(ExecConstants.LIMIT_ROW_SIZE_BYTES.getDefault().getNumVal());
    }
  }

  @Override
  public void setSource(InputStream is) throws IOException {
    parser = MAPPER.getFactory().createParser(is);
  }

  public void setSource(byte[] bytes) throws IOException {
    parser = MAPPER.getFactory().createParser(bytes);
  }

  @Override
  public void setSource(JsonNode node) {
    this.parser = new TreeTraversingParser(node);
  }

  @Override
  public UserException.Builder getExceptionWithContext(
      UserException.Builder exceptionBuilder, String field) {
    if (field != null) {
      exceptionBuilder.pushContext("Field ", field);
    }
    exceptionBuilder
        .pushContext("Column ", parser.getCurrentLocation().getColumnNr() + 1)
        .pushContext("Line ", parser.getCurrentLocation().getLineNr());
    return exceptionBuilder;
  }

  @Override
  public UserException.Builder getExceptionWithContext(Throwable e, String field) {
    UserException.Builder exceptionBuilder = UserException.dataReadError(e);
    return getExceptionWithContext(exceptionBuilder, field);
  }

  @Override
  public void resetDataSizeCounter() {
    // no-op
  }

  @Override
  public long getDataSizeCounter() {
    return 0;
  }

  @Override
  public Pair<String, Long> getScrollAndTotalSizeThenSeekToHits() throws IOException {
    return new Pair<>("default", 0L);
  }

  @Override
  public int writeSuccessfulParseEvent(BaseWriter.ComplexWriter writer) throws IOException {
    // Do nothing
    return 0;
  }

  protected void incrementCurrentRowSize(MinorType type, byte[] bytes) {
    if (!rowSizeLimitEnabled) {
      return;
    }
    if (type == MinorType.VARCHAR || type == MinorType.VARBINARY) {
      currentRowSize += RowSizeUtil.getFieldSizeForVariableWidthType(bytes);
    }
  }

  protected void resetRowSize() {
    currentRowSize = 0;
  }
}
