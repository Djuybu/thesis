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
package com.dremio.exec.planner.sql.handlers;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogOptions;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.planner.StarColumnHelper;
import com.dremio.exec.planner.common.MoreRelOptUtil;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.planner.sql.SqlExceptionHelper;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.parser.DremioSqlColumnDeclaration;
import com.dremio.exec.planner.sql.parser.DremioSqlRowTypeSpec;
import com.dremio.exec.planner.sql.parser.DremioSqlValidator;
import com.dremio.exec.planner.sql.parser.SqlArrayTypeSpec;
import com.dremio.exec.planner.sql.parser.SqlColumnPolicyPair;
import com.dremio.exec.planner.sql.parser.SqlComplexDataTypeSpec;
import com.dremio.exec.planner.sql.parser.SqlMapTypeSpec;
import com.dremio.exec.planner.types.JavaTypeFactoryImpl;
import com.dremio.exec.planner.types.RelDataTypeSystemImpl;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.SchemaBuilder;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.text.SimpleDateFormat;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlRowTypeNameSpec;
import org.apache.calcite.sql.SqlTimestampLiteral;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.tools.ValidationException;
import org.apache.calcite.util.TimestampString;
import org.apache.commons.lang3.StringUtils;

public class SqlHandlerUtil {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(SqlHandlerUtil.class);

  private static final String UNKNOWN_SOURCE_TYPE = "Unknown";
  public static final String PLANNER_SOURCE_TARGET_SOURCE_TYPE_SPAN_ATTRIBUTE_NAME =
      "dremio.planner.source.target.source_type";

  /**
   * Resolve final RelNode of the new table (or view) for given table field list and new table
   * definition.
   *
   * @param isNewTableView Is the new table created a view? This doesn't affect the functionality,
   *     but it helps format better error messages.
   * @param tableFieldNames List of fields specified in new table/view field list. These are the
   *     fields given just after new table name. Ex. CREATE TABLE newTblName(col1, medianOfCol2,
   *     avgOfCol3) AS SELECT col1, median(col2), avg(col3) FROM sourcetbl GROUP BY col1;
   * @throws ValidationException If table's fields list and field list specified in table definition
   *     are not valid.
   */
  public static RelNode resolveNewTableRel(
      boolean isNewTableView,
      List<String> tableFieldNames,
      RelDataType validatedRowtype,
      RelNode queryRelNode)
      throws ValidationException {
    return resolveNewTableRel(
        isNewTableView,
        tableFieldNames,
        validatedRowtype,
        queryRelNode,
        false /* disallow duplicates cols in select query */);
  }

  public static RelNode resolveNewTableRel(
      boolean isNewTableView,
      List<String> tableFieldNames,
      RelDataType validatedRowtype,
      RelNode queryRelNode,
      boolean allowDuplicatesInSelect)
      throws ValidationException {

    validateRowType(isNewTableView, tableFieldNames, validatedRowtype);
    if (tableFieldNames.size() > 0) {
      return MoreRelOptUtil.createRename(queryRelNode, tableFieldNames);
    }

    if (!allowDuplicatesInSelect) {
      ensureNoDuplicateColumnNames(validatedRowtype.getFieldNames());
    }

    return queryRelNode;
  }

  public static void validateRowType(
      boolean isNewTableView, List<String> tableFieldNames, RelDataType validatedRowtype)
      throws ValidationException {

    // Get the row type of view definition query.
    // Reason for getting the row type from validated SqlNode than RelNode is because SqlNode ->
    // RelNode involves
    // renaming duplicate fields which is not desired when creating a view or table.
    // For ex: SELECT region_id, region_id FROM cp."region.json" LIMIT 1 returns
    //  +------------+------------+
    //  | region_id  | region_id0 |
    //  +------------+------------+
    //  | 0          | 0          |
    //  +------------+------------+
    // which is not desired when creating new views or tables.
    //    final RelDataType queryRowType = validatedRowtype;

    if (tableFieldNames.size() > 0) {
      // Field count should match unless there is an order by clause using a row
      // that is not in the select list.
      if (tableFieldNames.size() != validatedRowtype.getFieldCount()) {
        final String tblType = isNewTableView ? "view" : "table";
        throw UserException.validationError()
            .message(
                "%s's field list and the %s's query field list have different counts.",
                tblType, tblType)
            .build(logger);
      }
      // CTAS's query field list shouldn't have "*" when table's field list is specified.
      for (String field : validatedRowtype.getFieldNames()) {
        if ("*".equals(field)) {
          final String tblType = isNewTableView ? "view" : "table";
          throw UserException.validationError()
              .message(
                  "%s's query field list has a '*', which is invalid when %s's field list is specified.",
                  tblType, tblType)
              .build(logger);
        }
      }
    }

    // validate the given field names to make sure there are no duplicates
    ensureNoDuplicateColumnNames(tableFieldNames);
  }

  private static void ensureNoDuplicateColumnNames(List<String> fieldNames)
      throws ValidationException {
    final Set<String> fieldHashSet = Sets.newHashSetWithExpectedSize(fieldNames.size());
    for (String field : fieldNames) {
      if (fieldHashSet.contains(field.toLowerCase())) {
        throw new ValidationException(String.format("Duplicate column name [%s]", field));
      }
      fieldHashSet.add(field.toLowerCase());
    }
  }

  /**
   * Resolve the partition columns specified in "PARTITION BY" clause of CTAS statement. Throw
   * validation error if a partition column is not resolved correctly. A partition column is
   * resolved, either (1) the same column appear in the select list of CTAS or (2) CTAS has a * in
   * select list.
   *
   * <p>In the second case, a PROJECT with ITEM expression would be created and returned. Throw
   * validation error if a partition column is not resolved correctly.
   *
   * @param input : the RelNode represents the select statement in CTAS.
   * @param partitionColumns : the list of partition columns.
   * @return : 1) the original RelNode input, if all partition columns are in select list of CTAS 2)
   *     a New Project, if a partition column is resolved to * column in select list 3) validation
   *     error, if partition column is not resolved.
   */
  public static RelNode qualifyPartitionCol(RelNode input, List<String> partitionColumns) {

    final RelDataType inputRowType = input.getRowType();

    final List<RexNode> colRefStarExprs = Lists.newArrayList();
    final List<String> colRefStarNames = Lists.newArrayList();
    final RexBuilder builder = input.getCluster().getRexBuilder();
    final int originalFieldSize = inputRowType.getFieldCount();

    for (final String col : partitionColumns) {
      final RelDataTypeField field = inputRowType.getField(col, false, false);

      if (field == null) {
        throw UserException.validationError()
            .message("Partition column %s is not in the SELECT list of CTAS!", col)
            .build(logger);
      } else {
        if (field.getName().startsWith(StarColumnHelper.STAR_COLUMN)) {
          colRefStarNames.add(col);

          final List<RexNode> operands = Lists.newArrayList();
          operands.add(new RexInputRef(field.getIndex(), field.getType()));
          operands.add(builder.makeLiteral(col));
          final RexNode item = builder.makeCall(SqlStdOperatorTable.ITEM, operands);
          colRefStarExprs.add(item);
        }
      }
    }

    if (colRefStarExprs.isEmpty()) {
      return input;
    } else {
      final List<String> names =
          new AbstractList<String>() {
            @Override
            public String get(int index) {
              if (index < originalFieldSize) {
                return inputRowType.getFieldNames().get(index);
              } else {
                return colRefStarNames.get(index - originalFieldSize);
              }
            }

            @Override
            public int size() {
              return originalFieldSize + colRefStarExprs.size();
            }
          };

      final List<RexNode> refs =
          new AbstractList<RexNode>() {
            @Override
            public int size() {
              return originalFieldSize + colRefStarExprs.size();
            }

            @Override
            public RexNode get(int index) {
              if (index < originalFieldSize) {
                return RexInputRef.of(index, inputRowType.getFieldList());
              } else {
                return colRefStarExprs.get(index - originalFieldSize);
              }
            }
          };

      return RelOptUtil.createProject(input, refs, names, false);
    }
  }

  public static void unparseSqlNodeList(
      SqlWriter writer, int leftPrec, int rightPrec, SqlNodeList fieldList) {
    writer.keyword("(");
    fieldList.get(0).unparse(writer, leftPrec, rightPrec);
    for (int i = 1; i < fieldList.size(); i++) {
      writer.keyword(",");
      fieldList.get(i).unparse(writer, leftPrec, rightPrec);
    }
    writer.keyword(")");
  }

  /**
   * Checks if new columns list has duplicates or has columns from existing schema
   *
   * @param newColumsDeclaration
   * @param existingSchema
   */
  public static void checkForDuplicateColumns(
      List<DremioSqlColumnDeclaration> newColumsDeclaration,
      BatchSchema existingSchema,
      String sql) {
    Set<String> existingColumns =
        existingSchema.getFields().stream()
            .map(Field::getName)
            .map(String::toUpperCase)
            .collect(Collectors.toSet());
    Set<String> newColumns = new HashSet<>();
    String column, columnUpper;
    for (DremioSqlColumnDeclaration columnDecl : newColumsDeclaration) {
      column = columnDecl.getName().getSimple();
      columnUpper = column.toUpperCase();
      SqlIdentifier type = columnDecl.getDataType().getTypeName();
      if (existingColumns.contains(columnUpper)) {
        throw SqlExceptionHelper.parseError(
                String.format("Column [%s] already in the table.", column),
                sql,
                columnDecl.getParserPosition())
            .buildSilently();
      }
      checkIfSpecifiedMultipleTimesAndAddColumn(
          sql, newColumns, column, columnUpper, type, columnDecl.getParserPosition());
    }
  }

  private static void checkIfSpecifiedMultipleTimesAndAddColumn(
      String sql,
      Set<String> newColumns,
      String column,
      String columnUpper,
      SqlIdentifier type,
      SqlParserPos parserPosition) {
    if (newColumns.contains(columnUpper)) {
      throw SqlExceptionHelper.parseError(
              String.format("Column [%s] specified multiple times.", column), sql, parserPosition)
          .buildSilently();
    }
    checkNestedFieldsForDuplicateNameDeclarations(sql, type);
    newColumns.add(columnUpper);
  }

  public static void checkNestedFieldsForDuplicateNameDeclarations(String sql, SqlIdentifier type) {
    if (type instanceof DremioSqlRowTypeSpec) {
      checkForDuplicateColumnsInStruct((DremioSqlRowTypeSpec) type, sql);
    } else if (type instanceof SqlArrayTypeSpec) {
      checkNestedFieldsForDuplicateNameDeclarations(
          sql, ((SqlArrayTypeSpec) type).getSpec().getTypeName());
    } else if (type instanceof SqlMapTypeSpec) {
      checkNestedFieldsForDuplicateNameDeclarations(
          sql, ((SqlMapTypeSpec) type).getSpecValue().getTypeName());
    }
  }

  private static void checkForDuplicateColumnsInStruct(
      DremioSqlRowTypeSpec rowTypeSpec, String sql) {
    List<SqlComplexDataTypeSpec> fieldTypes = rowTypeSpec.getFieldTypes();
    List<SqlIdentifier> fieldNames = rowTypeSpec.getFieldNames();
    Set<String> newColumns = new HashSet<>();
    for (int i = 0; i < fieldNames.size(); i++) {
      String column = fieldNames.get(i).getSimple();
      String columnUpper = column.toUpperCase();
      SqlIdentifier type = fieldTypes.get(i).getTypeName();
      checkIfSpecifiedMultipleTimesAndAddColumn(
          sql, newColumns, column, columnUpper, type, rowTypeSpec.getParserPosition());
    }
  }

  private static void checkForDuplicateColumnsInStruct(SqlRowTypeNameSpec rowTypeSpec, String sql) {
    List<SqlDataTypeSpec> fieldTypes = rowTypeSpec.getFieldTypes();
    List<SqlIdentifier> fieldNames = rowTypeSpec.getFieldNames();
    Set<String> newColumns = new HashSet<>();
    for (int i = 0; i < fieldNames.size(); i++) {
      String column = fieldNames.get(i).getSimple();
      String columnUpper = column.toUpperCase();
      SqlIdentifier type = fieldTypes.get(i).getTypeName();
      checkIfSpecifiedMultipleTimesAndAddColumn(
          sql, newColumns, column, columnUpper, type, rowTypeSpec.getParserPos());
    }
  }

  /**
   * Create arrow field from sql column declaration
   *
   * @param config
   * @param column
   * @return
   */
  public static Field fieldFromSqlColDeclaration(DremioSqlColumnDeclaration column, String sql) {
    checkInvalidType(column, sql);

    return CalciteArrowHelper.fieldFromCalciteRowType(
            column.getName().getSimple(),
            column.getDataType().deriveType(new DremioSqlValidator(JavaTypeFactoryImpl.INSTANCE)))
        .orElseThrow(
            () ->
                SqlExceptionHelper.parseError(
                        String.format(
                            "Invalid type [%s] specified for column [%s].",
                            column.getDataType(), column.getName().getSimple()),
                        sql,
                        column.getParserPosition())
                    .buildSilently());
  }

  /**
   * Create arrow field from sql column declaration
   *
   * @param relDataTypeFactory
   * @param column
   * @return
   */
  public static Field fieldFromSqlColDeclaration(
      RelDataTypeFactory relDataTypeFactory, DremioSqlColumnDeclaration column, String sql) {
    checkInvalidType(column, sql);

    return CalciteArrowHelper.fieldFromCalciteRowType(
            column.getName().getSimple(),
            column.getDataType().deriveType(new DremioSqlValidator(relDataTypeFactory)))
        .orElseThrow(
            () ->
                SqlExceptionHelper.parseError(
                        String.format(
                            "Invalid type [%s] specified for column [%s].",
                            column.getDataType(), column.getName().getSimple()),
                        sql,
                        column.getParserPosition())
                    .buildSilently());
  }

  public static void checkInvalidType(DremioSqlColumnDeclaration column, String sql) {
    if (SqlTypeName.get(column.getDataType().getTypeName().getSimple()) == null) {
      throw SqlExceptionHelper.parseError(
              String.format(
                  "Invalid column type [%s] specified for column [%s].",
                  column.getDataType(), column.getName().getSimple()),
              sql,
              column.getParserPosition())
          .buildSilently();
    }

    if (column.getDataType() instanceof SqlComplexDataTypeSpec) {
      SqlDataTypeSpec invalidTypeSpec =
          ((SqlComplexDataTypeSpec) column.getDataType()).validateType();
      if (invalidTypeSpec != null) {
        throw SqlExceptionHelper.parseError(
                String.format(
                    "Invalid type [%s] specified for nested column [%s].",
                    invalidTypeSpec, column.getName().getSimple()),
                sql,
                column.getParserPosition())
            .buildSilently();
      }
    }

    if (SqlTypeName.get(column.getDataType().getTypeName().getSimple()) == SqlTypeName.DECIMAL
        && ((SqlBasicTypeNameSpec) column.getDataType().getTypeNameSpec()).getPrecision()
            > RelDataTypeSystemImpl.MAX_NUMERIC_PRECISION) {
      throw SqlExceptionHelper.parseError(
              String.format(
                  "Precision larger than %s is not supported.",
                  RelDataTypeSystemImpl.MAX_NUMERIC_PRECISION),
              sql,
              column.getParserPosition())
          .buildSilently();
    }

    if (SqlTypeName.get(column.getDataType().getTypeName().getSimple()) == SqlTypeName.DECIMAL
        && ((SqlBasicTypeNameSpec) column.getDataType().getTypeNameSpec()).getScale()
            > RelDataTypeSystemImpl.MAX_NUMERIC_SCALE) {
      throw SqlExceptionHelper.parseError(
              String.format(
                  "Scale larger than %s is not supported.",
                  RelDataTypeSystemImpl.MAX_NUMERIC_SCALE),
              sql,
              column.getParserPosition())
          .buildSilently();
    }
  }

  /**
   * create BatchSchema from table schema specified in sql as list of columns
   *
   * @param config
   * @param newColumsDeclaration
   * @return
   */
  public static BatchSchema batchSchemaFromSqlSchemaSpec(
      List<DremioSqlColumnDeclaration> newColumsDeclaration, String sql) {
    SchemaBuilder schemaBuilder = BatchSchema.newBuilder();
    for (DremioSqlColumnDeclaration column : newColumsDeclaration) {
      schemaBuilder.addField(fieldFromSqlColDeclaration(column, sql));
    }
    return schemaBuilder.build();
  }

  /** create sql column declarations from SqlNodeList */
  public static List<DremioSqlColumnDeclaration> columnDeclarationsFromSqlNodes(
      SqlNodeList columnList, String sql) {
    List<DremioSqlColumnDeclaration> columnDeclarations = new ArrayList<>();
    for (SqlNode node : columnList.getList()) {
      if (node instanceof DremioSqlColumnDeclaration) {
        columnDeclarations.add((DremioSqlColumnDeclaration) node);
      } else {
        throw SqlExceptionHelper.parseError(
                "Column type not specified", sql, node.getParserPosition())
            .buildSilently();
      }
    }
    return columnDeclarations;
  }

  public static List<SqlColumnPolicyPair> columnPolicyPairsFromSqlNodes(
      SqlNodeList columnList, String sql) {
    List<SqlColumnPolicyPair> columnPolicyPairs = new ArrayList<>();
    for (SqlNode node : columnList.getList()) {
      if (node instanceof DremioSqlColumnDeclaration) {
        DremioSqlColumnDeclaration columnDeclaration = (DremioSqlColumnDeclaration) node;
        columnPolicyPairs.add(
            new SqlColumnPolicyPair(
                node.getParserPosition(),
                columnDeclaration.getName(),
                columnDeclaration.getPolicy()));
      } else if (node instanceof SqlColumnPolicyPair) {
        columnPolicyPairs.add((SqlColumnPolicyPair) node);
      } else {
        throw SqlExceptionHelper.parseError(
                "Column type not specified", sql, node.getParserPosition())
            .buildSilently();
      }
    }
    return columnPolicyPairs;
  }

  public static SimpleCommandResult validateSupportForDDLOperations(
      Catalog catalog, SqlHandlerConfig config, NamespaceKey path, DremioTable table) {
    Optional<SimpleCommandResult> validate = Optional.empty();

    if (table == null) {
      throw UserException.validationError().message("Table [%s] not found", path).buildSilently();
    }

    if (table.getJdbcTableType() != org.apache.calcite.schema.Schema.TableType.TABLE) {
      throw UserException.validationError()
          .message("[%s] is a %s", path, table.getJdbcTableType())
          .buildSilently();
    }

    if (table.getDatasetConfig() == null) {
      throw UserException.validationError().message("Table [%s] not found", path).buildSilently();
    }

    // Only for FS sources internal Iceberg or Json tables or Mongo source
    if (CatalogUtil.isFSInternalIcebergTableOrJsonTableOrMongo(
        catalog, path, table.getDatasetConfig())) {
      if (!(config.getContext().getOptions().getOption(ExecConstants.ENABLE_INTERNAL_SCHEMA))) {
        throw UserException.unsupportedError()
            .message(
                "Please contact customer support for steps to enable "
                    + "user managed schema feature.")
            .buildSilently();
      }
    } else {
      // For NativeIceberg tables in different catalogs supported
      validate = IcebergUtils.checkTableExistenceAndMutability(catalog, config, path, null, true);
    }
    return validate.isPresent() ? validate.get() : new SimpleCommandResult(true, "");
  }

  public static String getTimestampFromMillis(long timestampInMillis) {
    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS000000");
    return simpleDateFormat.format(new Date(timestampInMillis));
  }

  public static Long convertToTimeInMillis(String timestamp, SqlParserPos pos) {
    timestamp = removeEndingZeros(timestamp);
    try {
      TimestampString timestampStr = new TimestampString(timestamp);
      SqlTimestampLiteral timestampLiteral = SqlLiteral.createTimestamp(timestampStr, 3, pos);
      return timestampLiteral.getValueAs(Calendar.class).getTimeInMillis();
    } catch (Exception e) {
      throw UserException.parseError(e)
          .message("Literal '%s' cannot be casted to TIMESTAMP", timestamp)
          .buildSilently();
    }
  }

  public static String removeEndingZeros(String timestamp) {
    // Remove ending '0' after '.' in the timestamp string, because TimestampString does not accept
    // timestamp string
    // with ending of '0'. For instance, convert "2022-10-23 18:05:30.252000" to be "2022-10-23
    // 18:05:30.252".
    timestamp = timestamp.trim();
    if (timestamp.indexOf('.') < 0) {
      return timestamp;
    }
    int index = timestamp.length();
    while (index > 0 && timestamp.charAt(index - 1) == '0') {
      index--;
    }

    if (timestamp.charAt(index - 1) == '.') {
      index--;
    }

    return timestamp.substring(0, index);
  }

  public static void validateSupportForVersionedReflections(
      String source, Catalog catalog, OptionManager optionManager) {
    if (CatalogUtil.requestedPluginSupportsVersionedTables(source, catalog)) {
      if (!optionManager.getOption(CatalogOptions.REFLECTION_VERSIONED_SOURCE_ENABLED)) {
        throw UserException.unsupportedError()
            .message("Versioned source does not support reflection.")
            .build(logger);
      }
    }
  }

  public static String getSourceType(Catalog catalog, String sourceName) {
    if (!StringUtils.isEmpty(sourceName)) {
      try {
        return catalog.getSource(sourceName).getClass().getSimpleName();
      } catch (UserException e) {
        logger.debug(
            "Unable to get source {} from the catalog: {}", sourceName, e.getOriginalMessage());
      }
    }

    return UNKNOWN_SOURCE_TYPE;
  }

  /**
   * Checks if the given sql node contains any Dynamic parameters, i.e, select * from tableA where
   * tableA.a = ?. => returns true.
   *
   * @param sqlNode
   * @return true if there is a parameter in the given sql node.
   */
  public static boolean containsParameters(SqlNode sqlNode) {
    ParametersSqlShuttle parametersSqlShuttle = new ParametersSqlShuttle();
    sqlNode.accept(parametersSqlShuttle);
    return parametersSqlShuttle.containsParameter();
  }

  /**
   * Validates if a column exists
   *
   * @param schema table schema
   * @param columnName the name of the column to be validated
   * @return if the column exists it returns the arrow field
   * @throws UserException when the column does not exist
   */
  public static Field validateColumnExists(BatchSchema schema, String columnName)
      throws UserException {
    Optional<Field> field = schema.findFieldIgnoreCase(columnName);
    if (field.isEmpty()) {
      throw UserException.validationError()
          .message("Column '%s' does not exist in the table.", columnName)
          .buildSilently();
    }
    return field.get();
  }

  public enum Operations {
    PARTITION,
    LOCALSORT
    // Incomplete, add more in case you need them
  }

  /**
   * Validates if a column is a primitive type
   *
   * @param field the arrow field (column)
   * @param operation operation name for Exception message
   * @throws UserException when the column is not a primitive type
   */
  public static void validateColumnIsPrimitiveType(Field field, Operations operation)
      throws UserException {
    if (field.getType().isComplex()) {
      throw UserException.validationError()
          .message(
              "Cannot perform %s operation on a complex column '%s'. Only primitive types are allowed.",
              operation.name(), field.getName())
          .buildSilently();
    }
  }

  /**
   * Validates if a column exists, and if it has a primitive type
   *
   * @param schema table schema
   * @param columnName the name of the column to be validated
   * @param operation operation name for Exception message
   * @throws UserException when the column does not exist or when the column is not a primitive type
   */
  public static void validateColumnExistsAndIsPrimitiveType(
      BatchSchema schema, String columnName, Operations operation) throws UserException {
    Field field = SqlHandlerUtil.validateColumnExists(schema, columnName);
    SqlHandlerUtil.validateColumnIsPrimitiveType(field, operation);
  }

  /*
  Sql shuttle to find parameters inside a Sql node.
   */
  private static class ParametersSqlShuttle extends SqlShuttle {
    private boolean containsParameter;

    @Override
    public SqlNode visit(SqlDynamicParam param) {
      containsParameter = true;
      return super.visit(param);
    }

    public boolean containsParameter() {
      return this.containsParameter;
    }
  }
}
