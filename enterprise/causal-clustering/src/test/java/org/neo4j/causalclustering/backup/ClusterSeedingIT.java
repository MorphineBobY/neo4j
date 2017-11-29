/*
 * Copyright (c) 2002-2017 "Neo Technology,"
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
package org.neo4j.causalclustering.backup;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import org.neo4j.backup.OnlineBackupSettings;
import org.neo4j.causalclustering.catchup.tx.FileCopyMonitor;
import org.neo4j.causalclustering.catchup.tx.PullRequestMonitor;
import org.neo4j.causalclustering.core.CoreGraphDatabase;
import org.neo4j.causalclustering.discovery.Cluster;
import org.neo4j.causalclustering.discovery.CoreClusterMember;
import org.neo4j.causalclustering.discovery.SharedDiscoveryService;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.kernel.impl.store.format.standard.StandardV3_0;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.test.DbRepresentation;
import org.neo4j.test.rule.TestDirectory;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.neo4j.backup.OnlineBackupCommandIT.runBackupToolFromOtherJvmToGetExitCode;
import static org.neo4j.causalclustering.backup.BackupCoreIT.backupAddress;
import static org.neo4j.causalclustering.discovery.Cluster.dataMatchesEventually;
import static org.neo4j.causalclustering.helpers.DataCreator.createNodes;

public class ClusterSeedingIT
{
    private Cluster backupCluster;
    private Cluster cluster;
    private DefaultFileSystemAbstraction fsa = new DefaultFileSystemAbstraction();
    private DetectFileCopyMonitor detectFileCopyMonitor;
    private PullRequestMonitor pullRequestMonitor;

    @Rule
    public TestDirectory testDir = TestDirectory.testDirectory();
    private File baseBackupDir;

    @Before
    public void setup() throws Exception
    {
        Monitors monitors = new Monitors();
        addMonitorListeners( monitors );
        backupCluster = new Cluster( testDir.directory( "cluster-for-backup" ), 3, 0,
                new SharedDiscoveryService(), emptyMap(), backupParams(), emptyMap(), emptyMap(), StandardV3_0.NAME,
                new Monitors() );

        cluster = new Cluster( testDir.directory( "cluster-b" ), 3, 0,
                new SharedDiscoveryService(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), StandardV3_0.NAME,
                monitors );

        baseBackupDir = testDir.directory( "backups" );
    }

    private void addMonitorListeners( Monitors monitors )
    {
        this.detectFileCopyMonitor = new DetectFileCopyMonitor();
        this.pullRequestMonitor = new DetectPullRequestMonitor();
        monitors.addMonitorListener( detectFileCopyMonitor );
        monitors.addMonitorListener( pullRequestMonitor );
    }

    private Map<String,IntFunction<String>> backupParams()
    {
        return singletonMap(
                OnlineBackupSettings.online_backup_server.name(),
                serverId -> (":" + (8000 + serverId)) );
    }

    @After
    public void after() throws Exception
    {
        if ( backupCluster != null )
        {
            backupCluster.shutdown();
        }
        if ( cluster != null )
        {
            cluster.shutdown();
        }
    }

    private File createBackupUsingAnotherCluster() throws Exception
    {
        backupCluster.start();
        CoreGraphDatabase db = BackupCoreIT.createSomeData( backupCluster );

        File backup = createBackup( db, "some-backup" );
        backupCluster.shutdown();

        return backup;
    }

    private File createBackup( CoreGraphDatabase db, String backupName ) throws Exception
    {
        String[] args = BackupCoreIT.backupArguments( backupAddress( db ), baseBackupDir, backupName );
        assertEquals( 0, runBackupToolFromOtherJvmToGetExitCode( args ) );
        return new File( baseBackupDir, backupName );
    }

    @Test
    public void shouldRestoreBySeedingAllMembers() throws Throwable
    {
        // given
        File backupDir = createBackupUsingAnotherCluster();
        DbRepresentation before = DbRepresentation.of( backupDir );

        // when
        fsa.copyRecursively( backupDir, cluster.getCoreMemberById( 0 ).storeDir() );
        fsa.copyRecursively( backupDir, cluster.getCoreMemberById( 1 ).storeDir() );
        fsa.copyRecursively( backupDir, cluster.getCoreMemberById( 2 ).storeDir() );

        Map<File,Object> creation1 = fileKeys( cluster.getCoreMemberById( 0 ).storeDir() );
        Map<File,Object> creation2 = fileKeys( cluster.getCoreMemberById( 1 ).storeDir() );
        Map<File,Object> creation3 = fileKeys( cluster.getCoreMemberById( 2 ).storeDir() );

        cluster.start();

        // then
        dataMatchesEventually( before, cluster.coreMembers() );
        assertFalse( detectFileCopyMonitor.fileCopyDetected.get() );
        assertEquals( 4, pullRequestMonitor.numberOfRequests() );

        assertFileKeysAreTheSame( creation1 );
        assertFileKeysAreTheSame( creation2 );
        assertFileKeysAreTheSame( creation3 );
    }

    @Test
    public void shouldSeedNewMemberFromEmptyIdleCluster() throws Throwable
    {
        // given
        Monitors monitors = new Monitors();
        cluster = new Cluster( testDir.directory( "cluster-b" ), 3, 0,
                new SharedDiscoveryService(), emptyMap(), backupParams(), emptyMap(), emptyMap(), StandardV3_0.NAME,
                monitors );
        cluster.start();

        // when: creating a backup
        File backupDir = createBackup( cluster.getCoreMemberById( 0 ).database(), "the-backup" );
        // we are only interested in monitoring the new instance
        addMonitorListeners( monitors );

        // and: seeding new member with said backup
        CoreClusterMember newMember = cluster.addCoreMemberWithId( 3 );
        fsa.copyRecursively( backupDir, newMember.storeDir() );
        Map<File,Object> creationTimes = fileKeys( cluster.getCoreMemberById( 3 ).storeDir() );
        newMember.start();

        // then
        dataMatchesEventually( DbRepresentation.of( newMember.database() ), cluster.coreMembers() );
        assertFalse( detectFileCopyMonitor.fileCopyDetected.get() );
        assertEquals( 1, pullRequestMonitor.numberOfRequests() );
        assertEquals( 1, pullRequestMonitor.lastRequestedTxId() );
        assertFileKeysAreTheSame( creationTimes );
    }

    @Test
    public void shouldSeedNewMemberFromNonEmptyIdleCluster() throws Throwable
    {
        // given
        Monitors monitors = new Monitors();
        cluster = new Cluster( testDir.directory( "cluster-b" ), 3, 0,
                new SharedDiscoveryService(), emptyMap(), backupParams(), emptyMap(), emptyMap(), StandardV3_0.NAME,
                monitors );
        cluster.start();
        createNodes( cluster, 100 );

        // when: creating a backup
        File backupDir = createBackup( cluster.getCoreMemberById( 0 ).database(), "the-backup" );
        // we are only interested in monitoring the new instance
        addMonitorListeners( monitors );

        // and: seeding new member with said backup
        CoreClusterMember newMember = cluster.addCoreMemberWithId( 3 );
        fsa.copyRecursively( backupDir, newMember.storeDir() );
        Map<File,Object> creationTimes = fileKeys( cluster.getCoreMemberById( 3 ).storeDir() );
        newMember.start();

        // then
        dataMatchesEventually( DbRepresentation.of( newMember.database() ), cluster.coreMembers() );
        assertFalse( detectFileCopyMonitor.fileCopyDetected.get() );
        assertEquals( 1, pullRequestMonitor.numberOfRequests() );
        assertFileKeysAreTheSame( creationTimes );
    }

    @Test
    @Ignore( "need to seed all members for now" )
    public void shouldRestoreBySeedingSingleMember() throws Throwable
    {
        // given
        File backupDir = createBackupUsingAnotherCluster();
        DbRepresentation before = DbRepresentation.of( backupDir );

        // when
        fsa.copyRecursively( backupDir, cluster.getCoreMemberById( 0 ).storeDir() );
        cluster.getCoreMemberById( 0 ).start();
        Thread.sleep( 2_000 );
        cluster.getCoreMemberById( 1 ).start();
        cluster.getCoreMemberById( 2 ).start();

        // then
        dataMatchesEventually( before, cluster.coreMembers() );
    }

    private void assertFileKeysAreTheSame( Map<File,Object> fileKeys ) throws IOException
    {
        for ( Map.Entry<File,Object> e : fileKeys.entrySet() )
        {
            File file = e.getKey();
            Object oldKey = e.getValue();

            BasicFileAttributes attr = Files.readAttributes( file.toPath(), BasicFileAttributes.class );
            assertEquals( "File key for file: " + file, oldKey, attr.fileKey() );
        }
    }

    private Map<File,Object> fileKeys( File dir ) throws IOException
    {
        Map<File,Object> map = new HashMap<>();
        File[] files = dir.listFiles();
        assert files != null;

        for ( File file : files )
        {
            BasicFileAttributes attr = Files.readAttributes( file.toPath(), BasicFileAttributes.class );
            map.put( file, attr.fileKey() );
        }

        return map;
    }

    private class DetectPullRequestMonitor implements PullRequestMonitor
    {

        private final AtomicLong lastPullRequest = new AtomicLong();
        private final AtomicInteger numberOfRequest = new AtomicInteger();

        @Override
        public void txPullRequest( long txId )
        {
            lastPullRequest.set( txId );
            numberOfRequest.incrementAndGet();
        }

        @Override
        public void txPullResponse( long txId )
        {
            throw new UnsupportedOperationException( "not implemented" );
        }

        @Override
        public long lastRequestedTxId()
        {
            return lastPullRequest.get();
        }

        @Override
        public long lastReceivedTxId()
        {
            throw new UnsupportedOperationException( "not implemented" );
        }

        @Override
        public long numberOfRequests()
        {
            return numberOfRequest.get();
        }
    }

    private class DetectFileCopyMonitor implements FileCopyMonitor
    {
        private final AtomicBoolean fileCopyDetected = new AtomicBoolean( false );

        @Override
        public void copyFile( File file )
        {
            fileCopyDetected.compareAndSet( false, true );
        }
    }
}
