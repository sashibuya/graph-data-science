/*
 * Copyright (c) 2017-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo;

import org.HdrHistogram.Histogram;
import org.neo4j.graphalgo.PropertyMapping;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.HugeWeightMapping;
import org.neo4j.graphalgo.core.GraphLoader;
import org.neo4j.graphalgo.core.ProcedureConfiguration;
import org.neo4j.graphalgo.core.utils.*;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.write.Exporter;
import org.neo4j.graphalgo.impl.louvain.Louvain;
import org.neo4j.graphalgo.impl.results.AbstractCommunityResultBuilder;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * modularity based community detection algorithm
 *
 * @author mknblch
 */
public class LouvainProc {

    public static final String INTERMEDIATE_COMMUNITIES_WRITE_PROPERTY = "intermediateCommunitiesWriteProperty";
    public static final String DEFAULT_CLUSTER_PROPERTY = "communityProperty";
    public static final String INCLUDE_INTERMEDIATE_COMMUNITIES = "includeIntermediateCommunities";

    private static final String CLUSTERING_IDENTIFIER = "clustering";
    public static final String INNER_ITERATIONS = "innerIterations";
    public static final String COMMUNITY_SELECTION = "communitySelection";

    @Context
    public GraphDatabaseAPI api;

    @Context
    public Log log;

    @Context
    public KernelTransaction transaction;

    @Procedure(value = "algo.louvain", mode = Mode.WRITE)
    @Description("CALL algo.louvain(label:String, relationship:String, " +
            "{weightProperty:'weight', defaultValue:1.0, write: true, writeProperty:'community', concurrency:4, communityProperty:'propertyOfPredefinedCommunity', innerIterations:10, communitySelection:'classic'}) " +
            "YIELD nodes, communityCount, iterations, loadMillis, computeMillis, writeMillis")
    public Stream<LouvainResult> louvain(
            @Name(value = "label", defaultValue = "") String label,
            @Name(value = "relationship", defaultValue = "") String relationship,
            @Name(value = "config", defaultValue = "{}") Map<String, Object> config) {

        ProcedureConfiguration configuration = ProcedureConfiguration.create(config)
                .overrideNodeLabelOrQuery(label)
                .overrideRelationshipTypeOrQuery(relationship);

        final Builder builder = new Builder();

        AllocationTracker tracker = AllocationTracker.create();
        final Graph graph;
        try (ProgressTimer timer = builder.timeLoad()) {
            graph = graph(label, relationship, configuration, tracker);
        }

        if (graph.nodeCount() == 0) {
            graph.release();
            return Stream.of(LouvainResult.EMPTY);
        }

        Louvain louvain = eval(builder, configuration, graph, tracker, 1);

        if (configuration.isWriteFlag()) {
            builder.timeWrite(() -> {
                String writeProperty = configuration.getWriteProperty("community");
                boolean includeIntermediateCommunities = configuration.get(INCLUDE_INTERMEDIATE_COMMUNITIES, false);
                String intermediateCommunitiesWriteProperty = configuration.get(
                        INTERMEDIATE_COMMUNITIES_WRITE_PROPERTY,
                        "communities");

                builder.withWrite(true);
                builder.withWriteProperty(writeProperty);
                builder.withIntermediateCommunities(includeIntermediateCommunities);
                builder.withIntermediateCommunitiesWriteProperty(intermediateCommunitiesWriteProperty);

                log.debug("Writing results");
                Exporter exporter = exporter(graph, Pools.DEFAULT, configuration.getWriteConcurrency());
                louvain.export(
                        exporter,
                        writeProperty,
                        includeIntermediateCommunities,
                        intermediateCommunitiesWriteProperty);
            });
        }

        builder.withIterations(louvain.getLevel());
        builder.withModularities(louvain.getModularities());
        builder.withFinalModularity(louvain.getFinalModularity());
        return Stream.of(builder.build(louvain.communityCount(), tracker, graph.nodeCount(), louvain::communityIdOf));
    }

    @Procedure(value = "algo.louvain.stream")
    @Description("CALL algo.louvain.stream(label:String, relationship:String, " +
            "{weightProperty:'propertyName', defaultValue:1.0, concurrency:4, communityProperty:'propertyOfPredefinedCommunity', innerIterations:10, communitySelection:'classic') " +
            "YIELD nodeId, community - yields a setId to each node id")
    public Stream<Louvain.StreamingResult> louvainStream(
            @Name(value = "label", defaultValue = "") String label,
            @Name(value = "relationship", defaultValue = "") String relationship,
            @Name(value = "config", defaultValue = "{}") Map<String, Object> config) {

        final ProcedureConfiguration configuration = ProcedureConfiguration.create(config)
                .overrideNodeLabelOrQuery(label)
                .overrideRelationshipTypeOrQuery(relationship);

        AllocationTracker tracker = AllocationTracker.create();
        final Graph graph = graph(label, relationship, configuration, tracker);

        if (graph.nodeCount() == 0) {
            graph.release();
            return Stream.empty();
        }

        // evaluation
        Louvain louvain = eval(configuration, graph, tracker, configuration.getConcurrency());
        return louvain.dendrogramStream(configuration.get(INCLUDE_INTERMEDIATE_COMMUNITIES, false));
    }

    public Graph graph(
            String label,
            String relationship,
            ProcedureConfiguration config,
            AllocationTracker tracker) {

        GraphLoader graphLoader = new GraphLoader(api, Pools.DEFAULT)
                .init(log, label, relationship, config)
                .withAllocationTracker(tracker)
                .withNodeStatement(config.getNodeLabelOrQuery())
                .withRelationshipStatement(config.getRelationshipOrQuery())
                .withOptionalRelationshipWeightsFromProperty(
                        config.getWeightProperty(),
                        config.getWeightPropertyDefaultValue(1.0));

        config.getString(DEFAULT_CLUSTER_PROPERTY).ifPresent(propertyIdentifier -> {
            // configure predefined clustering if set
            graphLoader.withOptionalNodeProperties(PropertyMapping.of(CLUSTERING_IDENTIFIER, propertyIdentifier, -1));
        });

        return graphLoader
                .asUndirected(true)
                .load(config.getGraphImpl());
    }

    private Louvain eval(
            Builder builder,
            ProcedureConfiguration configuration,
            Graph graph,
            AllocationTracker tracker,
            int concurrency) {
        try (ProgressTimer ignored = builder.timeEval()) {
            return eval(configuration, graph, tracker, concurrency);
        }
    }

    private Louvain eval(
            ProcedureConfiguration configuration,
            Graph graph,
            AllocationTracker tracker,
            int concurrency) {
        final int iterations = configuration.getIterations(10);
        final int maxIterations = configuration.getNumber(INNER_ITERATIONS, 10L).intValue();
        final boolean randomNeighbor = configuration.get(COMMUNITY_SELECTION, "classic").equalsIgnoreCase("random");
        Optional<String> clusterProperty = configuration.getString(DEFAULT_CLUSTER_PROPERTY);

        if (clusterProperty.isPresent()) {
            // use predefined clustering
            HugeWeightMapping communityMap = graph.nodeProperties(CLUSTERING_IDENTIFIER);
            Louvain louvain = new Louvain(graph, Pools.DEFAULT, concurrency, tracker)
                    .withProgressLogger(ProgressLogger.wrap(log, "Louvain"))
                    .withTerminationFlag(TerminationFlag.wrap(transaction));
            louvain.compute(communityMap, iterations, maxIterations, randomNeighbor);
            return louvain;
        } else {
            Louvain louvain = new Louvain(graph, Pools.DEFAULT, concurrency, tracker)
                    .withProgressLogger(ProgressLogger.wrap(log, "Louvain"))
                    .withTerminationFlag(TerminationFlag.wrap(transaction));
            louvain.compute(iterations, maxIterations, randomNeighbor);
            return louvain;
        }
    }

    private Exporter exporter(
            Graph graph,
            ExecutorService pool,
            int concurrency) {
        Exporter.Builder builder = Exporter.of(api, graph);
        if (log != null) {
            builder.withLog(log);
        }
        if (ParallelUtil.canRunInParallel(pool)) {
            builder.parallel(pool, concurrency, TerminationFlag.wrap(transaction));
        }
        return builder.build();
    }

    public static class LouvainResult {

        public static final LouvainResult EMPTY = new LouvainResult(
                0,
                0,
                0,
                0,
                0,
                0,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                0,
                new double[]{},
                -1,
                false,
                null,
                false,
                null,
                false);

        public final long loadMillis;
        public final long computeMillis;
        public final long writeMillis;
        public final long postProcessingMillis;
        public final long nodes;
        public final long communityCount;
        public final long iterations;
        public final List<Double> modularities;
        public final double modularity;
        public final long p1;
        public final long p5;
        public final long p10;
        public final long p25;
        public final long p50;
        public final long p75;
        public final long p90;
        public final long p95;
        public final long p99;
        public final long p100;
        public final boolean write;
        public final String writeProperty;
        public final boolean includeIntermediateCommunities;
        public final String intermediateCommunitiesWriteProperty;
        public final boolean randomNeighbor;

        public LouvainResult(
                long loadMillis,
                long computeMillis,
                long postProcessingMillis,
                long writeMillis,
                long nodes,
                long communityCount,
                long p100,
                long p99,
                long p95,
                long p90,
                long p75,
                long p50,
                long p25,
                long p10,
                long p5,
                long p1,
                long iterations,
                double[] modularities,
                double finalModularity,
                boolean write,
                String writeProperty,
                boolean includeIntermediateCommunities,
                String intermediateCommunitiesWriteProperty,
                boolean randomNeighbor) {
            this.loadMillis = loadMillis;
            this.computeMillis = computeMillis;
            this.postProcessingMillis = postProcessingMillis;
            this.writeMillis = writeMillis;
            this.nodes = nodes;
            this.communityCount = communityCount;
            this.p100 = p100;
            this.p99 = p99;
            this.p95 = p95;
            this.p90 = p90;
            this.p75 = p75;
            this.p50 = p50;
            this.p25 = p25;
            this.p10 = p10;
            this.p5 = p5;
            this.p1 = p1;
            this.iterations = iterations;
            this.modularities = new ArrayList<>(modularities.length);
            this.write = write;
            this.includeIntermediateCommunities = includeIntermediateCommunities;
            for (double mod : modularities) this.modularities.add(mod);
            this.modularity = finalModularity;
            this.writeProperty = writeProperty;
            this.intermediateCommunitiesWriteProperty = intermediateCommunitiesWriteProperty;
            this.randomNeighbor = randomNeighbor;
        }
    }

    public static class Builder extends AbstractCommunityResultBuilder<LouvainResult> {

        private long iterations = -1;
        private double[] modularities = new double[]{};
        private double finalModularity = -1;
        private String writeProperty;
        private String intermediateCommunitiesWriteProperty;
        private boolean includeIntermediateCommunities;
        private boolean randomNeighbor = false;

        public Builder withWriteProperty(String writeProperty) {
            this.writeProperty = writeProperty;
            return this;
        }

        public Builder withIterations(long iterations) {
            this.iterations = iterations;
            return this;
        }

        public Builder randomNeighbor(boolean randomNeighbor) {
            this.randomNeighbor = randomNeighbor;
            return this;
        }

        @Override
        protected LouvainResult build(
                long loadMillis,
                long computeMillis,
                long writeMillis,
                long postProcessingMillis,
                long nodeCount,
                long communityCount,
                Histogram communityHistogram,
                boolean write) {
            return new LouvainResult(
                    loadMillis,
                    computeMillis,
                    postProcessingMillis,
                    writeMillis,
                    nodeCount,
                    communityCount,
                    communityHistogram.getValueAtPercentile(100),
                    communityHistogram.getValueAtPercentile(99),
                    communityHistogram.getValueAtPercentile(95),
                    communityHistogram.getValueAtPercentile(90),
                    communityHistogram.getValueAtPercentile(75),
                    communityHistogram.getValueAtPercentile(50),
                    communityHistogram.getValueAtPercentile(25),
                    communityHistogram.getValueAtPercentile(10),
                    communityHistogram.getValueAtPercentile(5),
                    communityHistogram.getValueAtPercentile(1),
                    iterations,
                    modularities,
                    finalModularity,
                    write,
                    writeProperty,
                    includeIntermediateCommunities,
                    intermediateCommunitiesWriteProperty,
                    randomNeighbor);
        }

        public Builder withModularities(double[] modularities) {
            this.modularities = modularities;
            return this;
        }

        public Builder withFinalModularity(double finalModularity) {
            this.finalModularity = finalModularity;
            return null;
        }

        public Builder withIntermediateCommunitiesWriteProperty(String intermediateCommunitiesWriteProperty) {
            this.intermediateCommunitiesWriteProperty = intermediateCommunitiesWriteProperty;
            return null;
        }

        public Builder withIntermediateCommunities(boolean includeIntermediateCommunities) {
            this.includeIntermediateCommunities = includeIntermediateCommunities;
            return this;
        }
    }


}
