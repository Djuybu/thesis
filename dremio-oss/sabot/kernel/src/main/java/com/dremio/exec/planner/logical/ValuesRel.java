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
package com.dremio.exec.planner.logical;

import static com.dremio.exec.planner.common.MoreRexUtil.convertToJsonNode;

import com.dremio.exec.planner.physical.PrelUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.Pair;

/** Values implemented in Dremio. */
public class ValuesRel extends Values implements Rel {
  // This causes a bunch of tests to fail, since not everyone honors it.
  // Once they are fixed we can enable this.
  private static final boolean USE_STRICT_VERIFICATION = false;

  protected ValuesRel(
      RelOptCluster cluster,
      RelDataType rowType,
      ImmutableList<ImmutableList<RexLiteral>> tuples,
      RelTraitSet traits) {
    super(cluster, rowType, tuples, traits);
    assert getConvention() == LOGICAL;

    // Remove the ANY type and derive the literal type.
    this.rowType = adjustRowType(cluster.getTypeFactory(), rowType, tuples);

    verifyRowType(tuples, this.rowType);

    if (USE_STRICT_VERIFICATION) {
      verifyRowTypeStrict(tuples, this.rowType);
    }
  }

  /**
   * Adjust the row type to remove ANY types - derive type from the literals
   *
   * @param typeFactory RelDataTypeFactory used to create the RelDataType
   * @param rowType Row type
   * @param tuples RexLiterals for the Values Rel
   * @return the derived RelDataType from literal.
   */
  private static RelDataType adjustRowType(
      final RelDataTypeFactory typeFactory,
      final RelDataType rowType,
      final ImmutableList<ImmutableList<RexLiteral>> tuples) {
    final int inFieldCount = rowType.getFieldCount();
    List<RelDataType> fieldTypes = Lists.newArrayListWithExpectedSize(inFieldCount);
    List<String> fieldNames = Lists.newArrayListWithExpectedSize(inFieldCount);

    boolean changed = false;
    int i = 0;
    for (final RelDataTypeField field : rowType.getFieldList()) {
      final SqlTypeName sqlTypeName = field.getValue().getSqlTypeName();
      if (sqlTypeName == SqlTypeName.ANY) {
        fieldTypes.add(getFieldTypeFromInput(typeFactory, i, tuples));
        changed = true;
      } else {
        fieldTypes.add(field.getType());
      }
      fieldNames.add(field.getName());
    }

    if (!changed) {
      return rowType;
    }

    return typeFactory.createStructType(fieldTypes, fieldNames);
  }

  /**
   * Helper method that gets the type from tuples for given fieldIndex. An IN list is represented by
   * a single iteration through the tuples at fieldIndex.
   *
   * @param fieldIndex Field index used to retrieve the relevant RexLiteral.
   * @param tuples RexLiterals for the Values Rel
   * @return the derived RelDataType from literal.
   */
  private static RelDataType getFieldTypeFromInput(
      RelDataTypeFactory typeFactory,
      final int fieldIndex,
      final ImmutableList<ImmutableList<RexLiteral>> tuples) {
    // Search for a non-NULL, non-ANY type.
    List<RelDataType> literalTypes = Lists.newArrayListWithExpectedSize(tuples.size());

    for (ImmutableList<RexLiteral> literals : tuples) {
      final RexLiteral literal = literals.get(fieldIndex);
      if (literal != null
          && literal.getType().getSqlTypeName() != SqlTypeName.NULL
          && literal.getType().getSqlTypeName() != SqlTypeName.ANY) {
        literalTypes.add(literal.getType());
      }
    }

    // Return the least restrictive type unless it is null, in which case return the first non-null,
    // non-ANY type.
    RelDataType leastRestrictiveType = typeFactory.leastRestrictive(literalTypes);
    return (leastRestrictiveType != null) ? leastRestrictiveType : literalTypes.get(0);
  }

  private static void verifyRowType(
      final ImmutableList<ImmutableList<RexLiteral>> tuples, RelDataType rowType) {
    for (List<RexLiteral> tuple : tuples) {
      assert (tuple.size() == rowType.getFieldCount());

      for (Pair<RexLiteral, RelDataTypeField> pair : Pair.zip(tuple, rowType.getFieldList())) {
        RexLiteral literal = pair.left;
        RelDataType fieldType = pair.right.getType();

        if ((!(RexLiteral.isNullLiteral(literal)))
            && (!(SqlTypeUtil.canAssignFrom(fieldType, literal.getType())))) {
          throw new AssertionError("to " + fieldType + " from " + literal);
        }
      }
    }
  }

  private static void verifyRowTypeStrict(
      final ImmutableList<ImmutableList<RexLiteral>> tuples, RelDataType rowType) {
    for (List<RexLiteral> tuple : tuples) {
      assert (tuple.size() == rowType.getFieldCount());
      for (Pair<RexLiteral, RelDataTypeField> pair : Pair.zip(tuple, rowType.getFieldList())) {
        RexLiteral literal = pair.left;
        RelDataType fieldType = pair.right.getType();

        if (!RexLiteral.isNullLiteral(literal) && literal.getType() != fieldType) {
          throw new AssertionError("to " + fieldType + " from " + literal);
        }
      }
    }
  }

  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery relMetadataQuery) {
    return planner.getCostFactory().makeCost(tuples.size(), 1.0d, 0.0d);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    assert inputs.isEmpty();
    return new ValuesRel(getCluster(), rowType, tuples, traitSet);
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    return tuples.size();
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    pw =
        super.explainTerms(pw)
            .itemIf("type", this.rowType, pw.getDetailLevel() == SqlExplainLevel.DIGEST_ATTRIBUTES)
            .itemIf("type", this.rowType.getFieldList(), pw.nest())
            .itemIf(
                "tuplesCount",
                (double) tuples.size(),
                pw.getDetailLevel() != SqlExplainLevel.ALL_ATTRIBUTES);
    if (pw.getDetailLevel() == SqlExplainLevel.DIGEST_ATTRIBUTES) {
      boolean isValueCastEnabled = PrelUtil.getSettings(getCluster()).isValueCastEnabled();
      pw = pw.item("tuples", convertToJsonNode(rowType, tuples, isValueCastEnabled));
    }
    return pw;
  }

  public static ValuesRel from(LogicalValues values) {
    return new ValuesRel(
        values.getCluster(),
        values.getRowType(),
        values.getTuples(),
        values.getTraitSet().plus(Rel.LOGICAL));
  }
}
