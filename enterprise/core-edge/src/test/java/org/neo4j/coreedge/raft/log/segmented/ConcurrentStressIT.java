/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.log.segmented;

import java.io.File;

import org.neo4j.coreedge.raft.log.DummyRaftableContentSerializer;
import org.neo4j.coreedge.raft.log.RaftLog;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.logging.NullLogProvider;

import static org.neo4j.coreedge.server.CoreEdgeClusterSettings.raft_log_pruning_strategy;

public class ConcurrentStressIT extends org.neo4j.coreedge.raft.log.ConcurrentStressIT<SegmentedRaftLog>
{
    @Override
    public SegmentedRaftLog createRaftLog( FileSystemAbstraction fsa, File dir ) throws Throwable
    {
        SegmentedRaftLog raftLog = new SegmentedRaftLog( fsa, dir, 8 * 1024 * 1024, new DummyRaftableContentSerializer(), NullLogProvider.getInstance(), 8,
                raft_log_pruning_strategy.getDefaultValue());
        raftLog.start();
        return raftLog;
    }
}
