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
package org.apache.iceberg.util;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.hash.HashFunction;
import org.apache.iceberg.relocated.com.google.common.hash.Hashing;
import org.apache.iceberg.types.Types;

/**
 * Contains the logic for hashing various types for use with the {@code bucket} partition
 * transformations
 */
public class BucketUtil {

  private static final HashFunction MURMUR3 = Hashing.murmur3_32_fixed();

  private BucketUtil() {}

  public static int hash(int value) {
    return MURMUR3.hashLong((long) value).asInt();
  }

  public static int hash(long value) {
    return MURMUR3.hashLong(value).asInt();
  }

  private static long doubleToLongBits(double value) {
    long bits = Double.doubleToLongBits(value);

    // Change negative zero (-0.0) to positive zero (0.0). As IEEE 754
    // mandates 0.0 == -0.0, both should also produce the same hash value.
    if (bits == Long.MIN_VALUE) {
      bits = 0L;
    }

    return bits;
  }

  public static int hash(float value) {
    return MURMUR3.hashLong(doubleToLongBits((double) value)).asInt();
  }

  public static int hash(double value) {
    return MURMUR3.hashLong(doubleToLongBits(value)).asInt();
  }

  public static int hash(CharSequence value) {
    return MURMUR3.hashString(value, StandardCharsets.UTF_8).asInt();
  }

  public static int hash(ByteBuffer value) {
    if (value.hasArray()) {
      return MURMUR3
          .hashBytes(value.array(), value.arrayOffset() + value.position(), value.remaining())
          .asInt();
    } else {
      int position = value.position();
      byte[] copy = new byte[value.remaining()];
      try {
        value.get(copy);
      } finally {
        // make sure the buffer position is unchanged
        value.position(position);
      }
      return MURMUR3.hashBytes(copy).asInt();
    }
  }

  public static int hash(UUID value) {
    return MURMUR3
        .newHasher(16)
        .putLong(Long.reverseBytes(value.getMostSignificantBits()))
        .putLong(Long.reverseBytes(value.getLeastSignificantBits()))
        .hash()
        .asInt();
  }

  public static int hash(Record record) {

    String concatenatedValue = "";
    List<Types.NestedField> fields = new ArrayList<>(record.copy().struct().fields());
    fields.sort(Comparator.comparing(Types.NestedField::fieldId));
    for (Types.NestedField field : fields) {
      Preconditions.checkArgument(!field.type().isNestedType(), "when bucketing on multiple columns, bucketing columns cannot be of nested field type");
      if (record.getField(field.name()) != null) {
        concatenatedValue += field.name() + 0x1F + record.getField(field.name()).toString() + 0x1F;
      } else {
        concatenatedValue += field.name() + 0x1F + 0x1F;
      }
    }
    System.out.println("concat value " + concatenatedValue);
    return MURMUR3
            .hashString(concatenatedValue, StandardCharsets.UTF_8).asInt();
  }


  public static int hash(BigDecimal value) {
    return MURMUR3.hashBytes(value.unscaledValue().toByteArray()).asInt();
  }
}
