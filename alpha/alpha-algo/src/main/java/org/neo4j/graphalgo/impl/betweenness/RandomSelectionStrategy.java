/*
 * Copyright (c) 2017-2020 "Neo4j,"
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
package org.neo4j.graphalgo.impl.betweenness;

import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.PagedSimpleBitSet;

import java.security.SecureRandom;

/**
 * Filters nodes randomly based on a given probability
 */
public class RandomSelectionStrategy implements RABrandesBetweennessCentrality.SelectionStrategy {

    private final PagedSimpleBitSet bitSet;
    private final long size;

    public RandomSelectionStrategy(Graph graph, double probability, AllocationTracker tracker) {
        this.bitSet = PagedSimpleBitSet.newBitSet(graph.nodeCount(), tracker);
        final SecureRandom random = new SecureRandom();
        for (int i = 0; i < graph.nodeCount(); i++) {
            if (random.nextDouble() < probability) {
                this.bitSet.put(i);
            }
        }
        this.size = this.bitSet.size();
    }

    @Override
    public boolean select(long nodeId) {
        return bitSet.contains(nodeId);
    }

    @Override
    public long size() {
        return size;
    }

}
