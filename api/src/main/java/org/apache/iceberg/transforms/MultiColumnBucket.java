/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing,
 *  * software distributed under the License is distributed on an
 *  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  * KIND, either express or implied.  See the License for the
 *  * specific language governing permissions and limitations
 *  * under the License.
 *
 */

package org.apache.iceberg.transforms;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.function.Function;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.BoundPredicate;
import org.apache.iceberg.expressions.BoundTransform;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.UnboundPredicate;
import org.apache.iceberg.relocated.com.google.common.base.Objects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.BucketUtil;
import org.apache.iceberg.util.SerializableFunction;

public class MultiColumnBucket<T> implements Transform<T, Integer>, Serializable {

    private final int numBuckets;

    static <T> MultiColumnBucket<T> get(int numBuckets) {
        Preconditions.checkArgument(
                numBuckets > 0, "Invalid number of buckets: %s (must be > 0)", numBuckets);
        return new MultiColumnBucket<>(numBuckets);
    }


    public MultiColumnBucket(int numBuckets) {
        Preconditions.checkArgument(
                numBuckets > 0, "Invalid number of buckets: %s (must be > 0)", numBuckets);
        this.numBuckets = numBuckets;
    }

    public Integer numBuckets() {
        return numBuckets;
    }

    @Override
    public SerializableFunction<T, Integer> bind(Type type) {
        Preconditions.checkArgument(canTransform(type), "Cannot bucket by type: %s", type);
        return get(type, numBuckets);
    }


    @SuppressWarnings("unchecked")
    static <T, B extends MultiColumnBucket<T> & SerializableFunction<T, Integer>> B get(
            Type type, int numBuckets) {
        Preconditions.checkArgument(
                numBuckets > 0, "Invalid number of buckets: %s (must be > 0)", numBuckets);

        switch (type.typeId()) {

            case STRUCT:
                return (B) new MultiColumnBucket.MultiColumnBucketStruct(numBuckets);
            default:
                throw new IllegalArgumentException("Cannot bucket on multiple column by type: " + type);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof MultiColumnBucket)) {
            return false;
        }

        MultiColumnBucket<?> bucket = (MultiColumnBucket<?>) o;
        return numBuckets == bucket.numBuckets;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(numBuckets);
    }

    @Override
    public String toString() {
        return "bucket[" + numBuckets + "]";
    }

    @Override
    public Integer apply(T value) {
        if (value == null) {
            return null;
        }
        return (hash(value) & Integer.MAX_VALUE) % numBuckets;
    }

    @Override
    public boolean canTransform(Type type) {
        if (type.isStructType()) {
            return true;
        }
        return false;
    }

    protected int hash(T value) {
        throw new UnsupportedOperationException(
                "hash(value) is not supported on the base Bucket class");
    }

    @Override
    public Type getResultType(Type sourceType) {
        return Types.IntegerType.get();
    }

    @Override
    public UnboundPredicate<Integer> project(String name, BoundPredicate<T> predicate) {
        Function<T, Integer> function = this.bind(predicate.term().type());
        if (predicate.term() instanceof BoundTransform) {
            return ProjectionUtil.projectTransformPredicate(this, name, predicate);
        }

        if (predicate.isUnaryPredicate()) {
            return Expressions.predicate(predicate.op(), name);
        } else if (predicate.isLiteralPredicate() && predicate.op() == Expression.Operation.EQ) {
            return Expressions.predicate(
                    predicate.op(), name, function.apply(predicate.asLiteralPredicate().literal().value()));
        } else if (predicate.isSetPredicate()
                && predicate.op() == Expression.Operation.IN) { // notIn can't be projected
            return ProjectionUtil.transformSet(name, predicate.asSetPredicate(), function);
        }

        // comparison predicates can't be projected, notEq can't be projected
        // TODO: small ranges can be projected.
        // for example, (x > 0) and (x < 3) can be turned into in({1, 2}) and projected.
        return null;
    }

    @Override
    public UnboundPredicate<Integer> projectStrict(String name, BoundPredicate<T> predicate) {
        Function<T, Integer> function = this.bind(predicate.term().type());
        if (predicate.term() instanceof BoundTransform) {
            return ProjectionUtil.projectTransformPredicate(this, name, predicate);
        }

        if (predicate.isUnaryPredicate()) {
            return Expressions.predicate(predicate.op(), name);
        } else if (predicate.isLiteralPredicate() && predicate.op() == Expression.Operation.NOT_EQ) {
            // TODO: need to translate not(eq(...)) into notEq in expressions
            return Expressions.predicate(
                    predicate.op(), name, function.apply(predicate.asLiteralPredicate().literal().value()));
        } else if (predicate.isSetPredicate() && predicate.op() == Expression.Operation.NOT_IN) {
            return ProjectionUtil.transformSet(name, predicate.asSetPredicate(), function);
        }

        // no strict projection for comparison or equality
        return null;
    }

    private static class MultiColumnBucketStruct extends MultiColumnBucket<Record>
            implements SerializableFunction<Record, Integer> {

        public MultiColumnBucketStruct(int numBuckets) {
            super(numBuckets);
        }

        @Override
        protected int hash(Record value) {
            return BucketUtil.hash(value);
        }

    }
}
