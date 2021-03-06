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
package org.neo4j.coreedge.raft.roles;

import java.io.IOException;

import org.neo4j.coreedge.catchup.storecopy.LocalDatabase;
import org.neo4j.coreedge.raft.RaftMessageHandler;
import org.neo4j.coreedge.raft.RaftMessages;
import org.neo4j.coreedge.raft.RaftMessages.AppendEntries;
import org.neo4j.coreedge.raft.RaftMessages.Heartbeat;
import org.neo4j.coreedge.raft.outcome.Outcome;
import org.neo4j.coreedge.raft.state.RaftState;
import org.neo4j.coreedge.raft.state.ReadableRaftState;
import org.neo4j.kernel.impl.store.MismatchingStoreIdException;
import org.neo4j.kernel.impl.store.StoreId;
import org.neo4j.logging.Log;

import static java.lang.Long.min;

import static org.neo4j.coreedge.raft.roles.Role.CANDIDATE;
import static org.neo4j.coreedge.raft.roles.Role.FOLLOWER;

public class Follower implements RaftMessageHandler
{
    public static <MEMBER> boolean logHistoryMatches( ReadableRaftState<MEMBER> ctx, long prevLogIndex,
                                                      long prevLogTerm )
            throws IOException
    {
        // NOTE: A prevLogIndex before or at our log's prevIndex means that we
        //       already have all history (in a compacted form), so we report that history matches

        // NOTE: The entry term for a non existing log index is defined as -1,
        //       so the history for a non existing log entry never matches.

        return prevLogIndex <= ctx.entryLog().prevIndex() ||
                ctx.entryLog().readEntryTerm( prevLogIndex ) == prevLogTerm;
    }

    public static <MEMBER> void commitToLogOnUpdate( ReadableRaftState<MEMBER> ctx, long indexOfLastNewEntry,
                                                     long leaderCommit, Outcome<MEMBER> outcome )
    {
        long newCommitIndex = min( leaderCommit, indexOfLastNewEntry );

        if ( newCommitIndex > ctx.commitIndex() )
        {
            outcome.setCommitIndex( newCommitIndex );
        }
    }

    public static <MEMBER> void handleLeaderLogCompaction( ReadableRaftState<MEMBER> ctx, Outcome<MEMBER> outcome,
                                                           RaftMessages.LogCompactionInfo<MEMBER> compactionInfo )
    {
        if ( compactionInfo.leaderTerm() < ctx.term() )
        {
            return;
        }

        if ( compactionInfo.prevIndex() > ctx.entryLog().appendIndex() )
        {
            outcome.markNeedForFreshSnapshot();
        }
    }

    @Override
    public <MEMBER> Outcome<MEMBER> handle( RaftMessages.RaftMessage<MEMBER> message, ReadableRaftState<MEMBER> ctx,
                                            Log log, LocalDatabase localDatabase ) throws IOException
    {
        Outcome<MEMBER> outcome = new Outcome<>( FOLLOWER, ctx );

        switch ( message.type() )
        {
            case HEARTBEAT:
            {
                Heart.beat( ctx, outcome, (Heartbeat<MEMBER>) message );
                break;
            }

            case APPEND_ENTRIES_REQUEST:
            {
                Appending.handleAppendEntriesRequest( ctx, outcome, (AppendEntries.Request<MEMBER>) message,
                        localDatabase.storeId() );
                break;
            }

            case VOTE_REQUEST:
            {
                Voting.handleVoteRequest( ctx, outcome, (RaftMessages.Vote.Request<MEMBER>) message,
                        localDatabase.storeId() );
                break;
            }

            case LOG_COMPACTION_INFO:
            {
                handleLeaderLogCompaction( ctx, outcome, (RaftMessages.LogCompactionInfo<MEMBER>) message );
                break;
            }

            case ELECTION_TIMEOUT:
            {
                if ( Election.start( ctx, outcome, log, localDatabase.storeId() ) )
                {
                    outcome.setNextRole( CANDIDATE );
                    log.info( "Moving to CANDIDATE state after successfully starting election %n" );
                }
                break;
            }
        }

        return outcome;
    }

    @Override
    public <MEMBER> Outcome<MEMBER> validate( RaftMessages.RaftMessage<MEMBER> message, RaftState<MEMBER> ctx,
                                              Log log, LocalDatabase localDatabase )
    {
        localDatabase.assertHealthy( IllegalStateException.class );
        Outcome<MEMBER> outcome = new Outcome<>( FOLLOWER, ctx );

        StoreId storeId = localDatabase.storeId();
        if ( outcome.getLeader() != null &&
                message.type() != RaftMessages.Type.HEARTBEAT_TIMEOUT &&
                !storeId.theRealEquals( message.storeId() ) )
        {
            if ( localDatabase.isEmpty() )
            {
                outcome.markNeedForFreshSnapshot();
                outcome.markUnprocessable();
            }
            else if ( message.type() != RaftMessages.Type.VOTE_REQUEST )
            {
                throw new MismatchingStoreIdException( message.storeId(), storeId );
            }
            else
            {
                outcome.markUnprocessable();
            }
        }

        return outcome;
    }

}
