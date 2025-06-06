/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment;

import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.DoubleDimensionSchema;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.selector.settable.SettableColumnValueSelector;
import org.apache.druid.segment.selector.settable.SettableDoubleColumnValueSelector;
import org.apache.druid.segment.writeout.SegmentWriteOutMedium;

import java.io.File;
import java.util.Comparator;

public class DoubleDimensionHandler implements DimensionHandler<Double, Double, Double>
{
  private static Comparator<ColumnValueSelector> DOUBLE_COLUMN_COMPARATOR = (s1, s2) -> {
    if (s1.isNull()) {
      return s2.isNull() ? 0 : -1;
    } else if (s2.isNull()) {
      return 1;
    } else {
      return Double.compare(s1.getDouble(), s2.getDouble());
    }
  };

  private final String dimensionName;

  public DoubleDimensionHandler(String dimensionName)
  {
    this.dimensionName = dimensionName;
  }

  @Override
  public String getDimensionName()
  {
    return dimensionName;
  }

  @Override
  public DimensionSpec getDimensionSpec()
  {
    return new DefaultDimensionSpec(dimensionName, dimensionName, ColumnType.DOUBLE);
  }

  @Override
  public DimensionSchema getDimensionSchema(ColumnCapabilities capabilities)
  {
    return new DoubleDimensionSchema(dimensionName);
  }

  @Override
  public DimensionIndexer<Double, Double, Double> makeIndexer()
  {
    return new DoubleDimensionIndexer(dimensionName);
  }

  @Override
  public DimensionMergerV9 makeMerger(
      String outputName,
      IndexSpec indexSpec,
      SegmentWriteOutMedium segmentWriteOutMedium,
      ColumnCapabilities capabilities,
      ProgressIndicator progress,
      File segmentBaseDir,
      Closer closer
  )
  {
    return new DoubleDimensionMergerV9(
        outputName,
        indexSpec,
        segmentWriteOutMedium
    );
  }

  @Override
  public int getLengthOfEncodedKeyComponent(Double dimVals)
  {
    return 1;
  }

  @Override
  public Comparator<ColumnValueSelector> getEncodedValueSelectorComparator()
  {
    return DOUBLE_COLUMN_COMPARATOR;
  }

  @Override
  public SettableColumnValueSelector makeNewSettableEncodedValueSelector()
  {
    return new SettableDoubleColumnValueSelector();
  }
}
