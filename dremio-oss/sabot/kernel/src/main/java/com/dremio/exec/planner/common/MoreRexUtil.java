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
package com.dremio.exec.planner.common;

import static com.dremio.exec.planner.logical.RexToExpr.isLiteralNull;

import com.dremio.exec.vector.complex.fn.ExtendedJsonOutput;
import com.dremio.exec.vector.complex.fn.JsonOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.util.NlsString;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDateTime;
import org.joda.time.Period;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class on RexNodes */
public final class MoreRexUtil {
  private static final Logger LOGGER = LoggerFactory.getLogger(MoreRexUtil.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long MILLIS_IN_DAY = 1000 * 60 * 60 * 24;

  private MoreRexUtil() {}

  public static boolean hasFunction(RexNode node, Function<SqlOperator, Boolean> predicate) {
    FunctionFinder finder = new FunctionFinder(predicate);
    node.accept(finder);
    return finder.found;
  }

  private static class FunctionFinder extends RexShuttle {
    private final Function<SqlOperator, Boolean> predicate;
    private boolean found;

    public FunctionFinder(Function<SqlOperator, Boolean> predicate) {
      this.predicate = predicate;
      this.found = false;
    }

    @Override
    public RexNode visitCall(RexCall call) {
      if (!predicate.apply(call.getOperator())) {
        return super.visitCall(call);
      }

      found = true;
      return call; // Short circuit
    }
  }

  public static JsonNode convertToJsonNode(
      RelDataType rowType, List<? extends List<RexLiteral>> tuples, boolean isValueCastEnabled) {
    try {
      TokenBuffer out = new TokenBuffer(MAPPER.getFactory().getCodec(), false);
      JsonOutput json = new ExtendedJsonOutput(out);
      json.writeStartArray();
      String[] fields = rowType.getFieldNames().toArray(new String[rowType.getFieldCount()]);

      for (List<RexLiteral> row : tuples) {
        json.writeStartObject();
        int i = 0;
        for (RexLiteral field : row) {
          json.writeFieldName(fields[i]);
          writeLiteral(field, json, isValueCastEnabled);
          i++;
        }
        json.writeEndObject();
      }
      json.writeEndArray();
      json.flush();
      return out.asParser().readValueAsTree();
    } catch (IOException ex) {
      throw new RuntimeException("Failure while attempting to encode ValuesRel in JSON.", ex);
    }
  }

  private static void writeLiteral(RexLiteral literal, JsonOutput out, boolean isValueCastEnabled)
      throws IOException {

    switch (literal.getType().getSqlTypeName()) {
      case BIGINT:
        if (isLiteralNull(literal)) {
          out.writeBigIntNull();
        } else {
          out.writeBigInt(
              (((BigDecimal) literal.getValue()).setScale(0, RoundingMode.HALF_UP)).longValue());
        }
        return;

      case BINARY:
      case VARBINARY:
        if (isLiteralNull(literal)) {
          out.writeVarcharNull();
        } else {
          throw new UnsupportedEncodingException("Binary");
        }
        return;
      case BOOLEAN:
        if (isLiteralNull(literal)) {
          out.writeBooleanNull();
        } else {
          out.writeBoolean((Boolean) literal.getValue());
        }
        return;

      case CHAR:
        if (isLiteralNull(literal)) {
          out.writeVarcharNull();
        } else {
          out.writeVarChar(((NlsString) literal.getValue()).getValue());
        }
        return;

      case DOUBLE:
        if (isLiteralNull(literal)) {
          out.writeDoubleNull();
        } else {
          out.writeDouble(((BigDecimal) literal.getValue()).doubleValue());
        }
        return;

      case FLOAT:
        if (isValueCastEnabled) {
          if (isLiteralNull(literal)) {
            out.writeDoubleNull();
          } else {
            out.writeDouble(((BigDecimal) literal.getValue()).doubleValue());
          }
          return;
        }
        if (isLiteralNull(literal)) {
          out.writeFloatNull();
        } else {
          out.writeFloat(((BigDecimal) literal.getValue()).floatValue());
        }
        return;

      case INTEGER:
        if (isValueCastEnabled) {
          if (isLiteralNull(literal)) {
            out.writeBigIntNull();
          } else {
            out.writeBigInt(
                (((BigDecimal) literal.getValue()).setScale(0, RoundingMode.HALF_UP)).longValue());
          }
          return;
        }
        if (isLiteralNull(literal)) {
          out.writeIntNull();
        } else {
          out.writeInt(
              (((BigDecimal) literal.getValue()).setScale(0, RoundingMode.HALF_UP)).intValue());
        }
        return;

      case DECIMAL:
        if (isValueCastEnabled) {
          if (isLiteralNull(literal)) {
            out.writeDoubleNull();
          } else {
            out.writeDouble(((BigDecimal) literal.getValue()).doubleValue());
          }
          LOGGER.warn(
              "Converting exact decimal into approximate decimal.  Should be fixed once decimal is implemented.");
          return;
        }
        if (isLiteralNull(literal)) {
          out.writeDecimalNull();
        } else {
          out.writeDecimal(((BigDecimal) literal.getValue()));
        }
        return;

      case VARCHAR:
        if (isLiteralNull(literal)) {
          out.writeVarcharNull();
        } else {
          out.writeVarChar(((NlsString) literal.getValue()).getValue());
        }
        return;

      case SYMBOL:
        if (isLiteralNull(literal)) {
          out.writeVarcharNull();
        } else {
          out.writeVarChar(literal.getValue().toString());
        }
        return;

      case DATE:
        if (isLiteralNull(literal)) {
          out.writeDateNull();
        } else {
          out.writeDate(new LocalDateTime(literal.getValue(), DateTimeZone.UTC));
        }
        return;

      case TIME:
        if (isLiteralNull(literal)) {
          out.writeTimeNull();
        } else {
          out.writeTime(new LocalDateTime(literal.getValue(), DateTimeZone.UTC));
        }
        return;

      case TIMESTAMP:
        if (isLiteralNull(literal)) {
          out.writeTimestampNull();
        } else {
          out.writeTimestamp(new LocalDateTime(literal.getValue(), DateTimeZone.UTC));
        }
        return;
      case INTERVAL_YEAR:
      case INTERVAL_YEAR_MONTH:
      case INTERVAL_MONTH:
        if (isLiteralNull(literal)) {
          out.writeIntervalNull();
        } else {
          int months = ((BigDecimal) (literal.getValue())).intValue();
          out.writeInterval(new Period().plusMonths(months));
        }
        return;

      case INTERVAL_DAY:
      case INTERVAL_DAY_HOUR:
      case INTERVAL_DAY_MINUTE:
      case INTERVAL_DAY_SECOND:
      case INTERVAL_HOUR:
      case INTERVAL_HOUR_MINUTE:
      case INTERVAL_HOUR_SECOND:
      case INTERVAL_MINUTE:
      case INTERVAL_MINUTE_SECOND:
      case INTERVAL_SECOND:
        if (isLiteralNull(literal)) {
          out.writeIntervalNull();
        } else {
          long millis = ((BigDecimal) (literal.getValue())).longValue();
          int days = (int) (millis / MILLIS_IN_DAY);
          millis = millis - (days * MILLIS_IN_DAY);
          out.writeInterval(new Period().plusDays(days).plusMillis((int) millis));
        }
        return;

      case NULL:
        out.writeUntypedNull();
        return;

      case ANY:
      default:
        throw new UnsupportedOperationException(
            String.format(
                "Unable to convert the value of %s and type %s to a Dremio constant expression.",
                literal, literal.getType().getSqlTypeName()));
    }
  }
}
