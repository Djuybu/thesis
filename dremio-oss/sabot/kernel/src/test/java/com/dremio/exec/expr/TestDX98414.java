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
package com.dremio.exec.expr;

import static com.dremio.exec.ExecConstants.SPLIT_OR_TO_IF_BRANCH_SIZE_OPTION;

import com.dremio.BaseTestQuery;
import org.junit.Test;

public class TestDX98414 extends BaseTestQuery {

  String query =
      "select \"col_date\"\n"
          + "    from (SELECT to_number(col_id,'#####') AS col_id,  col_date FROM cp.\"json/dx_98414/dx98414_a.json\" a left join (SELECT col_age,col_age_group1 FROM cp.\"json/dx_98414/dx98414_b.json\" GROUP BY col_age,col_age_group1) b on a.col_age=b.col_age and a.col_months=b.col_age_group1)\n"
          + "    where (\"col_date\" in (1001, 1002, 1003, 1004, 1005, 1051, 1052, 1053, 1054, 1055, 1056, 1057, 1058, 1076, 1077, 1078, 1079, 1080, 1081, 1082, 1083, 1084, 1085, 1086, 1087, 1088, 1089, 1090, 1091, 1092)) and (((\"col_id\" in (990001, 5001, 990004, 5002, 5003, 5004, 5005, 5006, "
          + "5007, 5008, 5009, 5010, 5011, 5012, 5013, 5014, 5015, 5016, 5017, 5018, 5019, 5020, 5021, 5022, 5023, 5024, 5025, 5026, 5027, 5028, 5029, 5030, 5031, 5032, 5033, 5034, 5035, 5036, 5037, 5038)) or (\"col_id\" in ( 990002, 5039, 5040, 5041, 5042, 5043, 5044, 5045, 5046, 5047, 5048, 5049, 5050,"
          + " 5051, 5052, 5053, 5054, 5055, 5056, 5057, 5058, 5059, 5060, 5061, 5062, 5063, 5064, 5065, 5066, 5067, 5068, 5069, 5070, 5071, 5072, 5073, 5074, 5075, 5076, 5077, 5078, 5079, 5080, 5081, 5082, 5083, 5084, 5085, 5086, 5087, 5088, 5089, 5090, 5091, 5092, 5093, 5094))) or "
          + "(\"col_id\" in (990003, 5095, 5096, 5097, 5098, 5099, 5100, 5101, 5102, 5103, 5104, 5105, 5106, 5107, 5108, 5109, 5110, 5111, 5112, 5113, 5114, 5115, 5116, 5117, 5118, 5119, 5120, 5121, 5122, 5123, 5124, 5125, 5126, 5127, 5128, 5129, 5130, 5131, 5132, 5133, 5134, 5135, 5136, 5137, 5138, 5139, "
          + "5140, 5141, 5142, 5143, 5144, 5145, 5146, 5147, 5148, 5149, 5150, 5151, 5152, 5153, 5154, 5155, 5156, 5157, 5158, 990005))) order by \"col_date\"";

  @Test
  public void testOrExprIfTreeOptimization() throws Exception {
    try (AutoCloseable with1 = withSystemOption(SPLIT_OR_TO_IF_BRANCH_SIZE_OPTION, 10)) {
      test();
    }
  }

  @Test
  public void testOrExprIfTreeNoOptimization() throws Exception {
    try (AutoCloseable with1 = withSystemOption(SPLIT_OR_TO_IF_BRANCH_SIZE_OPTION, 10000)) {
      test();
    }
  }

  private void test() throws Exception {
    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("col_date")
        .baselineValues(1001L)
        .baselineValues(1002L)
        .baselineValues(1003L)
        .baselineValues(1004L)
        .baselineValues(1005L)
        .go();
  }
}
