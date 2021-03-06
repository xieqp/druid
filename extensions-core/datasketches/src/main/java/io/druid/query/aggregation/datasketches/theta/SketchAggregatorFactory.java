/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.query.aggregation.datasketches.theta;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import com.yahoo.sketches.Family;
import com.yahoo.sketches.Util;
import com.yahoo.sketches.theta.SetOperation;
import com.yahoo.sketches.theta.Union;
import io.druid.java.util.common.StringUtils;
import io.druid.query.aggregation.AggregateCombiner;
import io.druid.query.aggregation.Aggregator;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.BufferAggregator;
import io.druid.query.aggregation.ObjectAggregateCombiner;
import io.druid.segment.ColumnSelectorFactory;
import io.druid.segment.ColumnValueSelector;
import io.druid.segment.ObjectColumnSelector;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public abstract class SketchAggregatorFactory extends AggregatorFactory
{
  public static final int DEFAULT_MAX_SKETCH_SIZE = 16384;

  protected final String name;
  protected final String fieldName;
  protected final int size;
  private final byte cacheId;

  public SketchAggregatorFactory(String name, String fieldName, Integer size, byte cacheId)
  {
    this.name = Preconditions.checkNotNull(name, "Must have a valid, non-null aggregator name");
    this.fieldName = Preconditions.checkNotNull(fieldName, "Must have a valid, non-null fieldName");

    this.size = size == null ? DEFAULT_MAX_SKETCH_SIZE : size;
    Util.checkIfPowerOf2(this.size, "size");

    this.cacheId = cacheId;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Aggregator factorize(ColumnSelectorFactory metricFactory)
  {
    ObjectColumnSelector selector = metricFactory.makeObjectColumnSelector(fieldName);
    if (selector == null) {
      return new EmptySketchAggregator();
    } else {
      return new SketchAggregator(selector, size);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public BufferAggregator factorizeBuffered(ColumnSelectorFactory metricFactory)
  {
    ObjectColumnSelector selector = metricFactory.makeObjectColumnSelector(fieldName);
    if (selector == null) {
      return EmptySketchBufferAggregator.instance();
    } else {
      return new SketchBufferAggregator(selector, size, getMaxIntermediateSize());
    }
  }

  @Override
  public Object deserialize(Object object)
  {
    return SketchHolder.deserialize(object);
  }

  @Override
  public Comparator<Object> getComparator()
  {
    return SketchHolder.COMPARATOR;
  }

  @Override
  public Object combine(Object lhs, Object rhs)
  {
    return SketchHolder.combine(lhs, rhs, size);
  }

  @Override
  public AggregateCombiner makeAggregateCombiner()
  {
    return new ObjectAggregateCombiner<SketchHolder>()
    {
      private final Union union = (Union) SetOperation.builder().setNominalEntries(size).build(Family.UNION);
      private final SketchHolder combined = SketchHolder.of(union);

      @Override
      public void reset(ColumnValueSelector selector)
      {
        union.reset();
        fold(selector);
      }

      @Override
      public void fold(ColumnValueSelector selector)
      {
        @SuppressWarnings("unchecked")
        SketchHolder other = ((ObjectColumnSelector<SketchHolder>) selector).getObject();
        // SketchAggregatorFactory.combine() delegates to SketchHolder.combine() and it doesn't check for nulls, so we
        // neither.
        other.updateUnion(union);
        combined.invalidateCache();
      }

      @Override
      public Class<SketchHolder> classOfObject()
      {
        return SketchHolder.class;
      }

      @Nullable
      @Override
      public SketchHolder getObject()
      {
        return combined;
      }
    };
  }

  @Override
  @JsonProperty
  public String getName()
  {
    return name;
  }

  @JsonProperty
  public String getFieldName()
  {
    return fieldName;
  }

  @JsonProperty
  public int getSize()
  {
    return size;
  }

  @Override
  public int getMaxIntermediateSize()
  {
    return SetOperation.getMaxUnionBytes(size);
  }

  @Override
  public List<String> requiredFields()
  {
    return Collections.singletonList(fieldName);
  }

  @Override
  public byte[] getCacheKey()
  {
    byte[] fieldNameBytes = StringUtils.toUtf8(fieldName);
    return ByteBuffer.allocate(1 + Ints.BYTES + fieldNameBytes.length)
                     .put(cacheId)
                     .putInt(size)
                     .put(fieldNameBytes)
                     .array();
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() + "{"
           + "fieldName='" + fieldName + '\''
           + ", name='" + name + '\''
           + ", size=" + size
           + '}';
  }


  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    SketchAggregatorFactory that = (SketchAggregatorFactory) o;

    if (size != that.size) {
      return false;
    }
    if (cacheId != that.cacheId) {
      return false;
    }
    if (!name.equals(that.name)) {
      return false;
    }
    return fieldName.equals(that.fieldName);

  }

  @Override
  public int hashCode()
  {
    int result = name.hashCode();
    result = 31 * result + fieldName.hashCode();
    result = 31 * result + size;
    result = 31 * result + (int) cacheId;
    return result;
  }
}
