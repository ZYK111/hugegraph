/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.traversal.algorithm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.collections.CollectionUtils;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

import com.baidu.hugegraph.HugeException;
import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.query.Aggregate;
import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.query.QueryResults;
import com.baidu.hugegraph.backend.tx.GraphTransaction;
import com.baidu.hugegraph.iterator.ExtendableIterator;
import com.baidu.hugegraph.iterator.MapperIterator;
import com.baidu.hugegraph.schema.SchemaLabel;
import com.baidu.hugegraph.structure.HugeEdge;
import com.baidu.hugegraph.traversal.optimize.TraversalUtil;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.type.define.Directions;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.util.CollectionUtil;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.InsertionOrderUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class HugeTraverser {

    private HugeGraph graph;

    public static final List<Id> PATH_NONE = ImmutableList.of();

    public static final String DEFAULT_CAPACITY = "10000000";
    public static final String DEFAULT_ELEMENTS_LIMIT = "10000000";
    public static final String DEFAULT_PATHS_LIMIT = "10";
    public static final String DEFAULT_LIMIT = "100";
    public static final String DEFAULT_DEGREE = "10000";
    public static final String DEFAULT_SKIP_DEGREE = "100000";
    public static final String DEFAULT_SAMPLE = "100";
    public static final String DEFAULT_MAX_DEPTH = "50";
    public static final String DEFAULT_WEIGHT = "0";

    // Empirical value of scan limit, with which results can be returned in 3s
    public static final String DEFAULT_PAGE_LIMIT = "100000";

    public static final long NO_LIMIT = -1L;

    public HugeTraverser(HugeGraph graph) {
        this.graph = graph;
    }

    public HugeGraph graph() {
        return this.graph;
    }

    public Set<Id> kout(Id sourceV, Directions dir, String label,
                        int depth, boolean nearest,
                        long degree, long capacity, long limit) {
        E.checkNotNull(sourceV, "source vertex id");
        E.checkNotNull(dir, "direction");
        checkPositive(depth, "k-out max_depth");
        checkDegree(degree);
        checkCapacity(capacity);
        checkLimit(limit);
        if (capacity != NO_LIMIT) {
            // Capacity must > limit because sourceV is counted in capacity
            E.checkArgument(capacity >= limit && limit != NO_LIMIT,
                            "Capacity can't be less than limit, " +
                            "but got capacity '%s' and limit '%s'",
                            capacity, limit);
        }

        Id labelId = this.getEdgeLabelId(label);

        Set<Id> latest = newSet();
        latest.add(sourceV);

        Set<Id> all = newSet();
        all.add(sourceV);

        long remaining = capacity == NO_LIMIT ?
                         NO_LIMIT : capacity - latest.size();
        while (depth-- > 0) {
            // Just get limit nodes in last layer if limit < remaining capacity
            if (depth == 0 && limit != NO_LIMIT &&
                (limit < remaining || remaining == NO_LIMIT)) {
                remaining = limit;
            }
            if (nearest) {
                latest = this.adjacentVertices(latest, dir, labelId, all,
                                               degree, remaining);
                all.addAll(latest);
            } else {
                latest = this.adjacentVertices(latest, dir, labelId, null,
                                               degree, remaining);
            }
            if (capacity != NO_LIMIT) {
                // Update 'remaining' value to record remaining capacity
                remaining -= latest.size();

                if (remaining <= 0 && depth > 0) {
                    throw new HugeException(
                              "Reach capacity '%s' while remaining depth '%s'",
                              capacity, depth);
                }
            }
        }

        return latest;
    }

    public Set<Id> kneighbor(Id sourceV, Directions dir,
                             String label, int depth,
                             long degree, long limit) {
        E.checkNotNull(sourceV, "source vertex id");
        E.checkNotNull(dir, "direction");
        checkPositive(depth, "k-neighbor max_depth");
        checkDegree(degree);
        checkLimit(limit);

        Id labelId = this.getEdgeLabelId(label);

        Set<Id> latest = newSet();
        latest.add(sourceV);

        Set<Id> all = newSet();
        all.add(sourceV);

        while (depth-- > 0) {
            long remaining = limit == NO_LIMIT ? NO_LIMIT : limit - all.size();
            latest = this.adjacentVertices(latest, dir, labelId, all,
                                           degree, remaining);
            all.addAll(latest);
            if (limit != NO_LIMIT && all.size() >= limit) {
                break;
            }
        }

        return all;
    }

    public Set<Id> sameNeighbors(Id vertex, Id other, Directions direction,
                                 String label, long degree, long limit) {
        E.checkNotNull(vertex, "vertex id");
        E.checkNotNull(other, "the other vertex id");
        E.checkNotNull(direction, "direction");
        checkDegree(degree);
        checkLimit(limit);

        Id labelId = this.getEdgeLabelId(label);

        Set<Id> sourceNeighbors = IteratorUtils.set(this.adjacentVertices(
                                  vertex, direction, labelId, degree));
        Set<Id> targetNeighbors = IteratorUtils.set(this.adjacentVertices(
                                  other, direction, labelId, degree));
        Set<Id> sameNeighbors = (Set<Id>) CollectionUtil.intersect(
                                sourceNeighbors, targetNeighbors);
        if (limit != NO_LIMIT) {
            int end = Math.min(sameNeighbors.size(), (int) limit);
            sameNeighbors = CollectionUtil.subSet(sameNeighbors, 0, end);
        }
        return sameNeighbors;
    }

    public double jaccardSimilarity(Id vertex, Id other, Directions dir,
                                    String label, long degree) {
        E.checkNotNull(vertex, "vertex id");
        E.checkNotNull(other, "the other vertex id");
        E.checkNotNull(dir, "direction");
        checkDegree(degree);

        Id labelId = this.getEdgeLabelId(label);

        Set<Id> sourceNeighbors = IteratorUtils.set(this.adjacentVertices(
                                  vertex, dir, labelId, degree));
        Set<Id> targetNeighbors = IteratorUtils.set(this.adjacentVertices(
                                  other, dir, labelId, degree));
        int interNum = CollectionUtil.intersect(sourceNeighbors,
                                                targetNeighbors).size();
        int unionNum = CollectionUtil.union(sourceNeighbors,
                                            targetNeighbors).size();
        return (double) interNum / unionNum;
    }

    private Set<Id> adjacentVertices(Set<Id> vertices, Directions dir,
                                     Id label, Set<Id> excluded,
                                     long degree, long limit) {
        if (limit == 0) {
            return ImmutableSet.of();
        }

        Set<Id> neighbors = newSet();
        for (Id source : vertices) {
            Iterator<Edge> edges = this.edgesOfVertex(source, dir,
                                                      label, degree);
            while (edges.hasNext()) {
                HugeEdge e = (HugeEdge) edges.next();
                Id target = e.id().otherVertexId();
                if (excluded != null && excluded.contains(target)) {
                    continue;
                }
                neighbors.add(target);
                if (limit != NO_LIMIT && neighbors.size() >= limit) {
                    return neighbors;
                }
            }
        }
        return neighbors;
    }

    protected Iterator<Id> adjacentVertices(Id source, Directions dir,
                                            Id label, long limit) {
        Iterator<Edge> edges = this.edgesOfVertex(source, dir, label, limit);
        return new MapperIterator<>(edges, e -> {
            HugeEdge edge = (HugeEdge) e;
            return edge.id().otherVertexId();
        });
    }

    protected Iterator<Edge> edgesOfVertex(Id source, Directions dir,
                                           Id label, long limit) {
        Id[] labels = {};
        if (label != null) {
            labels = new Id[]{label};
        }

        Query query = GraphTransaction.constructEdgesQuery(source, dir, labels);
        if (limit != NO_LIMIT) {
            query.limit(limit);
        }
        return this.graph.edges(query);
    }

    protected Iterator<Edge> edgesOfVertex(Id source, Directions dir,
                                           Map<Id, String> labels, long limit) {
        if (labels == null || labels.isEmpty()) {
            return this.edgesOfVertex(source, dir, (Id) null, limit);
        }
        ExtendableIterator<Edge> results = new ExtendableIterator<>();
        for (Id label : labels.keySet()) {
            E.checkNotNull(label, "edge label");
            // TODO: limit should be applied to all labels
            results.extend(this.edgesOfVertex(source, dir, label, limit));
        }
        return results;
    }

    protected Iterator<Edge> edgesOfVertex(Id source, EdgeStep edgeStep) {
        if (edgeStep.properties == null || edgeStep.properties.isEmpty()) {
            Iterator<Edge> edges = this.edgesOfVertex(source,
                                                      edgeStep.direction,
                                                      edgeStep.labels,
                                                      edgeStep.limit());
            return edgeStep.skipSuperNodeIfNeeded(edges);
        }
        return this.edgesOfVertex(source, edgeStep, false);
    }

    protected Iterator<Edge> edgesOfVertexWithSK(Id source, EdgeStep edgeStep) {
        assert edgeStep.properties != null && !edgeStep.properties.isEmpty();
        return this.edgesOfVertex(source, edgeStep, true);
    }

    private Iterator<Edge> edgesOfVertex(Id source, EdgeStep edgeStep,
                                         boolean mustAllSK) {
        Id[] edgeLabels = edgeStep.edgeLabels();
        Query query = GraphTransaction.constructEdgesQuery(source,
                                                           edgeStep.direction,
                                                           edgeLabels);
        if (mustAllSK) {
            this.fillFilterBySortKeys(query, edgeLabels, edgeStep.properties);
        } else {
            this.fillFilterByProperties(query, edgeStep.properties);
        }
        query.capacity(Query.NO_CAPACITY);
        if (edgeStep.limit() != NO_LIMIT) {
            query.limit(edgeStep.limit());
        }
        Iterator<Edge> edges = this.graph().edges(query);
        return edgeStep.skipSuperNodeIfNeeded(edges);
    }

    private void fillFilterBySortKeys(Query query, Id[] edgeLabels,
                                      Map<Id, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return;
        }

        E.checkArgument(edgeLabels.length == 1,
                        "The properties filter condition can be set " +
                        "only if just set one edge label");

        this.fillFilterByProperties(query, properties);

        ConditionQuery condQuery = (ConditionQuery) query;
        if (!GraphTransaction.matchFullEdgeSortKeys(condQuery, this.graph())) {
            Id label = condQuery.condition(HugeKeys.LABEL);
            E.checkArgument(false, "The properties %s does not match " +
                            "sort keys of edge label '%s'",
                            this.graph().mapPkId2Name(properties.keySet()),
                            this.graph().edgeLabel(label).name());
        }
    }

    private void fillFilterByProperties(Query query,
                                        Map<Id, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return;
        }

        ConditionQuery condQuery = (ConditionQuery) query;
        TraversalUtil.fillConditionQuery(condQuery, properties, this.graph);
    }

    protected long edgesCount(Id source, EdgeStep edgeStep) {
        Id[] edgeLabels = edgeStep.edgeLabels();
        Query query = GraphTransaction.constructEdgesQuery(source,
                                                           edgeStep.direction,
                                                           edgeLabels);
        this.fillFilterBySortKeys(query, edgeLabels, edgeStep.properties);
        query.aggregate(Aggregate.AggregateFunc.COUNT, null);
        query.capacity(Query.NO_CAPACITY);
        query.limit(Query.NO_LIMIT);
        long count = graph().queryNumber(query).longValue();
        if (edgeStep.degree == NO_LIMIT || count < edgeStep.degree) {
            return count;
        } else if (edgeStep.skipDegree != 0L && count >= edgeStep.skipDegree) {
            return 0L;
        } else {
            return edgeStep.degree;
        }
    }

    protected Object getVertexLabelId(Object label) {
        if (label == null) {
            return null;
        }
        return SchemaLabel.getLabelId(this.graph, HugeType.VERTEX, label);
    }

    protected Id getEdgeLabelId(Object label) {
        if (label == null) {
            return null;
        }
        return SchemaLabel.getLabelId(this.graph, HugeType.EDGE, label);
    }

    public static void checkDegree(long degree) {
        checkPositiveOrNoLimit(degree, "max degree");
    }

    public static void checkCapacity(long capacity) {
        checkPositiveOrNoLimit(capacity, "capacity");
    }

    public static void checkLimit(long limit) {
        checkPositiveOrNoLimit(limit, "limit");
    }

    public static void checkPositive(long value, String name) {
        E.checkArgument(value > 0,
                        "The %s parameter must be > 0, but got %s",
                        name, value);
    }

    public static void checkPositiveOrNoLimit(long value, String name) {
        E.checkArgument(value > 0L || value == NO_LIMIT,
                        "The %s parameter must be > 0 or == %s, but got: %s",
                        name, NO_LIMIT, value);
    }

    public static void checkNonNegative(long value, String name) {
        E.checkArgument(value >= 0L,
                        "The %s parameter must be >= 0, but got: %s",
                        name, value);
    }

    public static void checkNonNegativeOrNoLimit(long value, String name) {
        E.checkArgument(value >= 0L || value == NO_LIMIT,
                        "The %s parameter must be >= 0 or == %s, but got: %s",
                        name, NO_LIMIT, value);
    }

    public static void checkCapacity(long capacity, long access,
                                     String traverse) {
        if (capacity != NO_LIMIT && access > capacity) {
            throw new HugeException("Exceed capacity '%s' while finding %s",
                                    capacity, traverse);
        }
    }

    public static void checkSkipDegree(long skipDegree, long degree,
                                       long capacity) {
        E.checkArgument(skipDegree >= 0L,
                        "The skipped degree must be >= 0, but got '%s'",
                        skipDegree);
        if (capacity != NO_LIMIT) {
            E.checkArgument(degree != NO_LIMIT && degree < capacity,
                            "The degree must be < capacity");
            E.checkArgument(skipDegree < capacity,
                            "The skipped degree must be < capacity");
        }
        if (skipDegree > 0L) {
            E.checkArgument(degree != NO_LIMIT && skipDegree >= degree,
                            "The skipped degree must be >= degree, " +
                            "but got skipped degree '%s' and degree '%s'",
                            skipDegree, degree);
        }
    }

    public static <K, V extends Comparable<? super V>> Map<K, V> topN(
                                                                 Map<K, V> map,
                                                                 boolean sorted,
                                                                 long limit) {
        if (sorted) {
            map = CollectionUtil.sortByValue(map, false);
        }
        if (limit == NO_LIMIT || map.size() <= limit) {
            return map;
        }
        Map<K, V> results = InsertionOrderUtil.newMap();
        long count = 0L;
        for (Map.Entry<K, V> entry : map.entrySet()) {
            results.put(entry.getKey(), entry.getValue());
            if (++count >= limit) {
                break;
            }
        }
        return results;
    }

    public static Iterator<Edge> skipSuperNodeIfNeeded(Iterator<Edge> edges,
                                                       long degree,
                                                       long skipDegree) {
        if (skipDegree <= 0L) {
            return edges;
        }
        List<Edge> edgeList = new ArrayList<>();
        for (int i = 1; edges.hasNext(); i++) {
            Edge edge = edges.next();
            if (i <= degree) {
                edgeList.add(edge);
            }
            if (i >= skipDegree) {
                return QueryResults.emptyIterator();
            }
        }
        return edgeList.iterator();
    }

    protected static <V> Set<V> newSet() {
        return new HashSet<>();
    }

    protected static <K, V> Map<K, V> newMap() {
        return new HashMap<>();
    }

    protected static <K, V> MultivaluedMap<K, V> newMultivalueMap() {
        return new MultivaluedHashMap<>();
    }

    public static class Node {

        private Id id;
        private Node parent;

        public Node(Id id) {
            this(id, null);
        }

        public Node(Id id, Node parent) {
            E.checkArgumentNotNull(id, "Id of Node can't be null");
            this.id = id;
            this.parent = parent;
        }

        public Id id() {
            return this.id;
        }

        public Node parent() {
            return this.parent;
        }

        public List<Id> path() {
            List<Id> ids = new ArrayList<>();
            Node current = this;
            do {
                ids.add(current.id);
                current = current.parent;
            } while (current != null);
            Collections.reverse(ids);
            return ids;
        }

        public List<Id> joinPath(Node back) {
            // Get self path
            List<Id> path = this.path();

            // Get reversed other path
            List<Id> backPath = back.path();
            Collections.reverse(backPath);

            // Avoid loop in path
            if (CollectionUtils.containsAny(path, backPath)) {
                return ImmutableList.of();
            }

            // Append other path behind self path
            path.addAll(backPath);
            return path;
        }

        public boolean contains(Id id) {
            Node node = this;
            do {
                if (node.id.equals(id)) {
                    return true;
                }
                node = node.parent;
            } while (node != null);
            return false;
        }

        @Override
        public int hashCode() {
            return this.id.hashCode();
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Node)) {
                return false;
            }
            Node other = (Node) object;
            return Objects.equals(this.id, other.id) &&
                   Objects.equals(this.parent, other.parent);
        }
    }

    public static class Path {

        public static final Path EMPTY_PATH = new Path(ImmutableList.of());

        private Id crosspoint;
        private List<Id> vertices;

        public Path(List<Id> vertices) {
            this(null, vertices);
        }

        public Path(Id crosspoint, List<Id> vertices) {
            this.crosspoint = crosspoint;
            this.vertices = vertices;
        }

        public Id crosspoint() {
            return this.crosspoint;
        }

        public List<Id> vertices() {
            return this.vertices;
        }

        public void reverse() {
            Collections.reverse(this.vertices);
        }

        public Map<String, Object> toMap(boolean withCrossPoint) {
            if (withCrossPoint) {
                return ImmutableMap.of("crosspoint", this.crosspoint,
                                       "objects", this.vertices);
            } else {
                return ImmutableMap.of("objects", this.vertices);
            }
        }

        public boolean ownedBy(Id source) {
            E.checkNotNull(source, "source");
            Id min = null;
            for (Id id : this.vertices) {
                if (min == null || id.compareTo(min) < 0) {
                    min = id;
                }
            }
            return source.equals(min);
        }

        @Override
        public int hashCode() {
            return this.vertices.hashCode();
        }

        /**
         * Compares the specified object with this path for equality.
         * Returns <tt>true</tt> if and only if both have same vertices list
         * without regard of crosspoint.
         * @param other the object to be compared for equality with this path
         * @return <tt>true</tt> if the specified object is equal to this path
         */
        @Override
        public boolean equals(Object other) {
            if (other == null || !(other instanceof Path)) {
                return false;
            }
            return this.vertices.equals(((Path) other).vertices);
        }
    }

    public static class PathSet extends HashSet<Path> {

        private static final long serialVersionUID = -8237531948776524872L;

        public Set<Id> vertices() {
            Set<Id> vertices = new HashSet<>();
            for (Path path : this) {
                vertices.addAll(path.vertices());
            }
            return vertices;
        }
    }
}
