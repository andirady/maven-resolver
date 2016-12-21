package org.eclipse.aether.internal.impl;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactProperties;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.collection.DependencyManagement;
import org.eclipse.aether.collection.DependencyManager;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyCycle;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.ArtifactDescriptorReader;
import org.eclipse.aether.internal.test.util.DependencyGraphParser;
import org.eclipse.aether.internal.test.util.TestLoggerFactory;
import org.eclipse.aether.internal.test.util.TestUtils;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;
import org.eclipse.aether.util.graph.manager.ClassicDependencyManager;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.manager.TransitiveDependencyManager;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector;
import org.eclipse.aether.util.graph.version.HighestVersionFilter;
import org.junit.Before;
import org.junit.Test;

/**
 */
public class DefaultDependencyCollectorTest
{

    private DefaultDependencyCollector collector;

    private DefaultRepositorySystemSession session;

    private DependencyGraphParser parser;

    private RemoteRepository repository;

    private IniArtifactDescriptorReader newReader( String prefix )
    {
        return new IniArtifactDescriptorReader( "artifact-descriptions/" + prefix );
    }

    private Dependency newDep( String coords )
    {
        return newDep( coords, "" );
    }

    private Dependency newDep( String coords, String scope )
    {
        return new Dependency( new DefaultArtifact( coords ), scope );
    }

    @Before
    public void setup()
        throws IOException
    {
        session = TestUtils.newSession();

        collector = new DefaultDependencyCollector();
        collector.setArtifactDescriptorReader( newReader( "" ) );
        collector.setVersionRangeResolver( new StubVersionRangeResolver() );
        collector.setRemoteRepositoryManager( new StubRemoteRepositoryManager() );
        collector.setLoggerFactory( new TestLoggerFactory() );

        parser = new DependencyGraphParser( "artifact-descriptions/" );

        repository = new RemoteRepository.Builder( "id", "default", "file:///" ).build();
    }

    private static void assertEqualSubtree( DependencyNode expected, DependencyNode actual )
    {
        assertEqualSubtree( expected, actual, new LinkedList<DependencyNode>() );
    }

    private static void assertEqualSubtree( DependencyNode expected, DependencyNode actual,
                                            LinkedList<DependencyNode> parents )
    {
        assertEquals( "path: " + parents, expected.getDependency(), actual.getDependency() );

        if ( actual.getDependency() != null )
        {
            Artifact artifact = actual.getDependency().getArtifact();
            for ( DependencyNode parent : parents )
            {
                if ( parent.getDependency() != null && artifact.equals( parent.getDependency().getArtifact() ) )
                {
                    return;
                }
            }
        }

        parents.addLast( expected );

        assertEquals( "path: " + parents + ", expected: " + expected.getChildren() + ", actual: "
                          + actual.getChildren(), expected.getChildren().size(), actual.getChildren().size() );

        Iterator<DependencyNode> iterator1 = expected.getChildren().iterator();
        Iterator<DependencyNode> iterator2 = actual.getChildren().iterator();

        while ( iterator1.hasNext() )
        {
            assertEqualSubtree( iterator1.next(), iterator2.next(), parents );
        }

        parents.removeLast();
    }

    private Dependency dep( DependencyNode root, int... coords )
    {
        return path( root, coords ).getDependency();
    }

    private DependencyNode path( DependencyNode root, int... coords )
    {
        try
        {
            DependencyNode node = root;
            for ( int coord : coords )
            {
                node = node.getChildren().get( coord );
            }

            return node;
        }
        catch ( IndexOutOfBoundsException e )
        {
            throw new IllegalArgumentException( "illegal coordinates for child", e );
        }
        catch ( NullPointerException e )
        {
            throw new IllegalArgumentException( "illegal coordinates for child", e );
        }
    }

    @Test
    public void testSimpleCollection()
        throws IOException, DependencyCollectionException
    {
        Dependency dependency = newDep( "gid:aid:ext:ver", "compile" );
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );

        DependencyNode root = result.getRoot();
        Dependency newDependency = root.getDependency();

        assertEquals( dependency, newDependency );
        assertEquals( dependency.getArtifact(), newDependency.getArtifact() );

        assertEquals( 1, root.getChildren().size() );

        Dependency expect = newDep( "gid:aid2:ext:ver", "compile" );
        assertEquals( expect, root.getChildren().get( 0 ).getDependency() );
    }

    @Test
    public void testMissingDependencyDescription()
        throws IOException
    {
        CollectRequest request =
            new CollectRequest( newDep( "missing:description:ext:ver" ), Arrays.asList( repository ) );
        try
        {
            collector.collectDependencies( session, request );
            fail( "expected exception" );
        }
        catch ( DependencyCollectionException e )
        {
            CollectResult result = e.getResult();
            assertSame( request, result.getRequest() );
            assertNotNull( result.getExceptions() );
            assertEquals( 1, result.getExceptions().size() );

            assertTrue( result.getExceptions().get( 0 ) instanceof ArtifactDescriptorException );

            assertEquals( request.getRoot(), result.getRoot().getDependency() );
        }
    }

    @Test
    public void testDuplicates()
        throws IOException, DependencyCollectionException
    {
        Dependency dependency = newDep( "duplicate:transitive:ext:dependency" );
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );

        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );

        DependencyNode root = result.getRoot();
        Dependency newDependency = root.getDependency();

        assertEquals( dependency, newDependency );
        assertEquals( dependency.getArtifact(), newDependency.getArtifact() );

        assertEquals( 2, root.getChildren().size() );

        Dependency dep = newDep( "gid:aid:ext:ver", "compile" );
        assertEquals( dep, dep( root, 0 ) );

        dep = newDep( "gid:aid2:ext:ver", "compile" );
        assertEquals( dep, dep( root, 1 ) );
        assertEquals( dep, dep( root, 0, 0 ) );
        assertEquals( dep( root, 1 ), dep( root, 0, 0 ) );
    }

    @Test
    public void testEqualSubtree()
        throws IOException, DependencyCollectionException
    {
        DependencyNode root = parser.parseResource( "expectedSubtreeComparisonResult.txt" );
        Dependency dependency = root.getDependency();
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );

        CollectResult result = collector.collectDependencies( session, request );
        assertEqualSubtree( root, result.getRoot() );
    }

    @Test
    public void testCyclicDependencies()
        throws Exception
    {
        DependencyNode root = parser.parseResource( "cycle.txt" );
        CollectRequest request = new CollectRequest( root.getDependency(), Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );
        assertEqualSubtree( root, result.getRoot() );
    }

    @Test
    public void testCyclicDependenciesBig()
        throws Exception
    {
        CollectRequest request = new CollectRequest( newDep( "1:2:pom:5.50-SNAPSHOT" ), Arrays.asList( repository ) );
        collector.setArtifactDescriptorReader( newReader( "cycle-big/" ) );
        CollectResult result = collector.collectDependencies( session, request );
        assertNotNull( result.getRoot() );
        // we only care about the performance here, this test must not hang or run out of mem
    }

    @Test
    public void testCyclicProjects()
        throws Exception
    {
        CollectRequest request = new CollectRequest( newDep( "test:a:2" ), Arrays.asList( repository ) );
        collector.setArtifactDescriptorReader( newReader( "versionless-cycle/" ) );
        CollectResult result = collector.collectDependencies( session, request );
        DependencyNode root = result.getRoot();
        DependencyNode a1 = path( root, 0, 0 );
        assertEquals( "a", a1.getArtifact().getArtifactId() );
        assertEquals( "1", a1.getArtifact().getVersion() );
        for ( DependencyNode child : a1.getChildren() )
        {
            assertFalse( "1".equals( child.getArtifact().getVersion() ) );
        }

        assertEquals( 1, result.getCycles().size() );
        DependencyCycle cycle = result.getCycles().get( 0 );
        assertEquals( Arrays.asList(), cycle.getPrecedingDependencies() );
        assertEquals( Arrays.asList( root.getDependency(), path( root, 0 ).getDependency(), a1.getDependency() ),
                      cycle.getCyclicDependencies() );
    }

    @Test
    public void testCyclicProjects_ConsiderLabelOfRootlessGraph()
        throws Exception
    {
        Dependency dep = newDep( "gid:aid:ver", "compile" );
        CollectRequest request =
            new CollectRequest().addDependency( dep ).addRepository( repository ).setRootArtifact( dep.getArtifact() );
        CollectResult result = collector.collectDependencies( session, request );
        DependencyNode root = result.getRoot();
        DependencyNode a1 = root.getChildren().get( 0 );
        assertEquals( "aid", a1.getArtifact().getArtifactId() );
        assertEquals( "ver", a1.getArtifact().getVersion() );
        DependencyNode a2 = a1.getChildren().get( 0 );
        assertEquals( "aid2", a2.getArtifact().getArtifactId() );
        assertEquals( "ver", a2.getArtifact().getVersion() );

        assertEquals( 1, result.getCycles().size() );
        DependencyCycle cycle = result.getCycles().get( 0 );
        assertEquals( Arrays.asList(), cycle.getPrecedingDependencies() );
        assertEquals( Arrays.asList( new Dependency( dep.getArtifact(), null ), a1.getDependency() ),
                      cycle.getCyclicDependencies() );
    }

    @Test
    public void testPartialResultOnError()
        throws IOException
    {
        DependencyNode root = parser.parseResource( "expectedPartialSubtreeOnError.txt" );

        Dependency dependency = root.getDependency();
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );

        CollectResult result;
        try
        {
            result = collector.collectDependencies( session, request );
            fail( "expected exception " );
        }
        catch ( DependencyCollectionException e )
        {
            result = e.getResult();

            assertSame( request, result.getRequest() );
            assertNotNull( result.getExceptions() );
            assertEquals( 1, result.getExceptions().size() );

            assertTrue( result.getExceptions().get( 0 ) instanceof ArtifactDescriptorException );

            assertEqualSubtree( root, result.getRoot() );
        }
    }

    @Test
    public void testCollectMultipleDependencies()
        throws IOException, DependencyCollectionException
    {
        Dependency root1 = newDep( "gid:aid:ext:ver", "compile" );
        Dependency root2 = newDep( "gid:aid2:ext:ver", "compile" );
        List<Dependency> dependencies = Arrays.asList( root1, root2 );
        CollectRequest request = new CollectRequest( dependencies, null, Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );
        assertEquals( 2, result.getRoot().getChildren().size() );
        assertEquals( root1, dep( result.getRoot(), 0 ) );

        assertEquals( 1, path( result.getRoot(), 0 ).getChildren().size() );
        assertEquals( root2, dep( result.getRoot(), 0, 0 ) );

        assertEquals( 0, path( result.getRoot(), 1 ).getChildren().size() );
        assertEquals( root2, dep( result.getRoot(), 1 ) );
    }

    @Test
    public void testArtifactDescriptorResolutionNotRestrictedToRepoHostingSelectedVersion()
        throws Exception
    {
        RemoteRepository repo2 = new RemoteRepository.Builder( "test", "default", "file:///" ).build();

        final List<RemoteRepository> repos = new ArrayList<RemoteRepository>();

        collector.setArtifactDescriptorReader( new ArtifactDescriptorReader()
        {

            public ArtifactDescriptorResult readArtifactDescriptor( RepositorySystemSession session,
                                                                    ArtifactDescriptorRequest request )
                throws ArtifactDescriptorException
            {
                repos.addAll( request.getRepositories() );
                return new ArtifactDescriptorResult( request );
            }

        } );

        List<Dependency> dependencies = Arrays.asList( newDep( "verrange:parent:jar:1[1,)", "compile" ) );
        CollectRequest request = new CollectRequest( dependencies, null, Arrays.asList( repository, repo2 ) );
        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );
        assertEquals( 2, repos.size() );
        assertEquals( "id", repos.get( 0 ).getId() );
        assertEquals( "test", repos.get( 1 ).getId() );
    }

    @Test
    public void testManagedVersionScope()
        throws IOException, DependencyCollectionException
    {
        Dependency dependency = newDep( "managed:aid:ext:ver" );
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );

        session.setDependencyManager( new ClassicDependencyManager() );

        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );

        DependencyNode root = result.getRoot();

        assertEquals( dependency, dep( root ) );
        assertEquals( dependency.getArtifact(), dep( root ).getArtifact() );

        assertEquals( 1, root.getChildren().size() );
        Dependency expect = newDep( "gid:aid:ext:ver", "compile" );
        assertEquals( expect, dep( root, 0 ) );

        assertEquals( 1, path( root, 0 ).getChildren().size() );
        expect = newDep( "gid:aid2:ext:managedVersion", "managedScope" );
        assertEquals( expect, dep( root, 0, 0 ) );
    }

    @Test
    public void testDependencyManagement()
        throws IOException, DependencyCollectionException
    {
        collector.setArtifactDescriptorReader( newReader( "managed/" ) );

        DependencyNode root = parser.parseResource( "expectedSubtreeComparisonResult.txt" );
        TestDependencyManager depMgmt = new TestDependencyManager();
        depMgmt.add( dep( root, 0 ), "managed", null, null );
        depMgmt.add( dep( root, 0, 1 ), "managed", "managed", null );
        depMgmt.add( dep( root, 1 ), null, null, "managed" );
        session.setDependencyManager( depMgmt );

        // collect result will differ from expectedSubtreeComparisonResult.txt
        // set localPath -> no dependency traversal
        CollectRequest request = new CollectRequest( dep( root ), Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );

        DependencyNode node = result.getRoot();
        assertEquals( "managed", dep( node, 0, 1 ).getArtifact().getVersion() );
        assertEquals( "managed", dep( node, 0, 1 ).getScope() );

        assertEquals( "managed", dep( node, 1 ).getArtifact().getProperty( ArtifactProperties.LOCAL_PATH, null ) );
        assertEquals( "managed", dep( node, 0, 0 ).getArtifact().getProperty( ArtifactProperties.LOCAL_PATH, null ) );
    }

    @Test
    public void testDependencyManagement_VerboseMode()
        throws Exception
    {
        String depId = "gid:aid2:ext";
        TestDependencyManager depMgmt = new TestDependencyManager();
        depMgmt.version( depId, "managedVersion" );
        depMgmt.scope( depId, "managedScope" );
        depMgmt.optional( depId, Boolean.TRUE );
        depMgmt.path( depId, "managedPath" );
        depMgmt.exclusions( depId, new Exclusion( "gid", "aid", "*", "*" ) );
        session.setDependencyManager( depMgmt );
        session.setConfigProperty( DependencyManagerUtils.CONFIG_PROP_VERBOSE, Boolean.TRUE );

        CollectRequest request = new CollectRequest().setRoot( newDep( "gid:aid:ver" ) );
        CollectResult result = collector.collectDependencies( session, request );
        DependencyNode node = result.getRoot().getChildren().get( 0 );
        assertEquals( DependencyNode.MANAGED_VERSION | DependencyNode.MANAGED_SCOPE | DependencyNode.MANAGED_OPTIONAL
            | DependencyNode.MANAGED_PROPERTIES | DependencyNode.MANAGED_EXCLUSIONS, node.getManagedBits() );
        assertEquals( "ver", DependencyManagerUtils.getPremanagedVersion( node ) );
        assertEquals( "compile", DependencyManagerUtils.getPremanagedScope( node ) );
        assertEquals( Boolean.FALSE, DependencyManagerUtils.getPremanagedOptional( node ) );
    }

    @Test
    public void testDependencyManagement_TransitiveDependencyManager()
        throws DependencyCollectionException, IOException
    {
        collector.setArtifactDescriptorReader( newReader( "managed/" ) );
        parser = new DependencyGraphParser( "artifact-descriptions/managed/" );
        session.setDependencyManager( new TransitiveDependencyManager() );
        final Dependency root = newDep( "gid:root:ext:ver", "compile" );
        CollectRequest request = new CollectRequest( root, Arrays.asList( repository ) );
        request.addManagedDependency( newDep( "gid:root:ext:must-retain-core-management" ) );
        CollectResult result = collector.collectDependencies( session, request );

        final DependencyNode expectedTree = parser.parseResource( "management-tree.txt" );
        assertEqualSubtree( expectedTree, result.getRoot() );

        // Same test for root artifact (POM) request.
        final CollectRequest rootArtifactRequest = new CollectRequest();
        rootArtifactRequest.setRepositories( Arrays.asList( repository ) );
        rootArtifactRequest.setRootArtifact( new DefaultArtifact( "gid:root:ext:ver" ) );
        rootArtifactRequest.addDependency( newDep( "gid:direct:ext:ver", "compile" ) );
        rootArtifactRequest.addManagedDependency( newDep( "gid:root:ext:must-retain-core-management" ) );
        rootArtifactRequest.addManagedDependency( newDep( "git:direct:ext:must-retain-core-management" ) );
        rootArtifactRequest.addManagedDependency( newDep( "gid:transitive-1:ext:managed-by-root" ) );
        result = collector.collectDependencies( session, rootArtifactRequest );
        assertEqualSubtree( expectedTree, this.toDependencyResult( result.getRoot(), "compile", null ) );
    }

    @Test
    public void testDependencyManagement_DependencySelectorProcessesManagedState()
        throws DependencyCollectionException, IOException
    {
        collector.setArtifactDescriptorReader( newReader( "selection/managed/" ) );
        parser = new DependencyGraphParser( "artifact-descriptions/selection/managed/" );

        final Dependency root = newDep( "gid:root:ext:ver", "root-scope" );
        CollectRequest request = new CollectRequest( root, Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );

        DependencyNode expected = parser.parseResource( "all-nodes.txt" );
        assertEqualSubtree( expected, result.getRoot() );

        this.session.setDependencySelector( new DependencySelector()
        {

            public boolean selectDependency( final Dependency dependency )
            {
                return dependency != null
                           && !( "managed".equals( dependency.getScope() )
                                 || "managed".equals( dependency.getArtifact().getVersion() )
                                 || dependency.isOptional() );

            }

            public DependencySelector deriveChildSelector( final DependencyCollectionContext context )
            {
                return this;
            }

        } );

        // Tests managed scope is processed by selector.
        TestDependencyManager depMgmt = new TestDependencyManager();
        depMgmt.scope( "gid:transitive-of-transitive-of-root:ext", "managed" );
        session.setDependencyManager( depMgmt );

        expected = parser.parseResource( "transitive-of-transitive-of-root.txt" );

        request = new CollectRequest( root, Arrays.asList( repository ) );
        result = collector.collectDependencies( session, request );

        assertEqualSubtree( expected, result.getRoot() );

        depMgmt = new TestDependencyManager();
        depMgmt.scope( "gid:transitive-of-root:ext", "managed" );
        session.setDependencyManager( depMgmt );

        expected = parser.parseResource( "transitive-of-root.txt" );

        request = new CollectRequest( root, Arrays.asList( repository ) );
        result = collector.collectDependencies( session, request );

        assertEqualSubtree( expected, result.getRoot() );

        depMgmt = new TestDependencyManager();
        depMgmt.scope( "gid:direct-of-root:ext", "managed" );
        session.setDependencyManager( depMgmt );

        expected = parser.parseResource( "direct-of-root.txt" );

        request = new CollectRequest( root, Arrays.asList( repository ) );
        result = collector.collectDependencies( session, request );

        assertEqualSubtree( expected, result.getRoot() );

        // Tests managed optionality is processed by selector.
        depMgmt = new TestDependencyManager();
        depMgmt.optional( "gid:transitive-of-transitive-of-root:ext", Boolean.TRUE );
        session.setDependencyManager( depMgmt );

        expected = parser.parseResource( "transitive-of-transitive-of-root.txt" );

        request = new CollectRequest( root, Arrays.asList( repository ) );
        result = collector.collectDependencies( session, request );

        assertEqualSubtree( expected, result.getRoot() );

        depMgmt = new TestDependencyManager();
        depMgmt.optional( "gid:transitive-of-root:ext", Boolean.TRUE );
        session.setDependencyManager( depMgmt );

        expected = parser.parseResource( "transitive-of-root.txt" );

        request = new CollectRequest( root, Arrays.asList( repository ) );
        result = collector.collectDependencies( session, request );

        assertEqualSubtree( expected, result.getRoot() );

        depMgmt = new TestDependencyManager();
        depMgmt.optional( "gid:direct-of-root:ext", Boolean.TRUE );
        session.setDependencyManager( depMgmt );

        expected = parser.parseResource( "direct-of-root.txt" );

        request = new CollectRequest( root, Arrays.asList( repository ) );
        result = collector.collectDependencies( session, request );

        assertEqualSubtree( expected, result.getRoot() );

        // Tests managed version is processed by selector.
        depMgmt = new TestDependencyManager();
        depMgmt.version( "gid:transitive-of-transitive-of-root:ext", "managed" );
        session.setDependencyManager( depMgmt );

        expected = parser.parseResource( "transitive-of-transitive-of-root.txt" );

        request = new CollectRequest( root, Arrays.asList( repository ) );
        result = collector.collectDependencies( session, request );

        assertEqualSubtree( expected, result.getRoot() );

        depMgmt = new TestDependencyManager();
        depMgmt.version( "gid:transitive-of-root:ext", "managed" );
        session.setDependencyManager( depMgmt );

        expected = parser.parseResource( "transitive-of-root.txt" );

        request = new CollectRequest( root, Arrays.asList( repository ) );
        result = collector.collectDependencies( session, request );

        assertEqualSubtree( expected, result.getRoot() );

        depMgmt = new TestDependencyManager();
        depMgmt.version( "gid:direct-of-root:ext", "managed" );
        session.setDependencyManager( depMgmt );

        expected = parser.parseResource( "direct-of-root.txt" );

        request = new CollectRequest( root, Arrays.asList( repository ) );
        result = collector.collectDependencies( session, request );

        assertEqualSubtree( expected, result.getRoot() );
    }

    @Test
    public void testVersionFilter()
        throws Exception
    {
        session.setVersionFilter( new HighestVersionFilter() );
        CollectRequest request = new CollectRequest().setRoot( newDep( "gid:aid:1" ) );
        CollectResult result = collector.collectDependencies( session, request );
        assertEquals( 1, result.getRoot().getChildren().size() );
    }

    @Test
    public void testSelectionWithScopeDependencySelector()
        throws DependencyCollectionException, IOException
    {
        collector.setArtifactDescriptorReader( newReader( "selection/scope/" ) );
        parser = new DependencyGraphParser( "artifact-descriptions/selection/scope/" );
        session.setDependencySelector( null );

        final DependencyNode allNodes = parser.parseResource( "all-nodes.txt" );
        final DependencyNode transitive1ExclusionTree = parser.parseResource( "transitive-1-exclusion-tree.txt" );
        final DependencyNode transitive2ExclusionTree = parser.parseResource( "transitive-2-exclusion-tree.txt" );
        final Dependency root = newDep( "gid:root:ext:ver", "root-scope" );
        final CollectRequest request = new CollectRequest( root, Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );
        assertEqualSubtree( allNodes, result.getRoot() );

        /*
         A dependency selector that filters transitive dependencies based on their scope. Direct dependencies are always
         included regardless of their scope.
         */
        // Include all.
        this.session.setDependencySelector( new ScopeDependencySelector() );
        result = collector.collectDependencies( session, request );
        assertEqualSubtree( allNodes, result.getRoot() );

        // Exclude root scope equals include all as the root is always included.
        this.session.setDependencySelector( new ScopeDependencySelector( "root-scope" ) );
        result = collector.collectDependencies( session, request );
        assertEqualSubtree( allNodes, result.getRoot() );

        // Exclude direct scope equals include all as direct dependencies are always included.
        this.session.setDependencySelector( new ScopeDependencySelector( "direct-scope" ) );
        result = collector.collectDependencies( session, request );
        assertEqualSubtree( allNodes, result.getRoot() );

        // Exclude scope of transitive of direct.
        this.session.setDependencySelector( new ScopeDependencySelector( "transitive-1-scope" ) );
        result = collector.collectDependencies( session, request );
        assertEqualSubtree( transitive1ExclusionTree, result.getRoot() );

        // Exclude scope of transitive of transitive of direct dependency.
        this.session.setDependencySelector( new ScopeDependencySelector( "transitive-2-scope" ) );
        result = collector.collectDependencies( session, request );
        assertEqualSubtree( transitive2ExclusionTree, result.getRoot() );

        // Same test as above but with root artifact instead of root dependency.
        this.session.setDependencySelector( null );
        final CollectRequest rootArtifactRequest = new CollectRequest();
        rootArtifactRequest.setRootArtifact( new DefaultArtifact( "gid:root:ext:ver" ) );
        rootArtifactRequest.addDependency( newDep( "gid:direct:ext:ver", "direct-scope" ) );
        rootArtifactRequest.setRepositories( Arrays.asList( repository ) );

        result = collector.collectDependencies( session, rootArtifactRequest );
        assertNull( result.getRoot().getDependency() );
        assertEqualSubtree( allNodes, this.toDependencyResult( result.getRoot(), "root-scope", null ) );

        /*
         A dependency selector that filters transitive dependencies based on their scope. Direct dependencies are always
         included regardless of their scope.
         */
        // Include all.
        this.session.setDependencySelector( new ScopeDependencySelector() );
        result = collector.collectDependencies( session, rootArtifactRequest );
        assertNull( result.getRoot().getDependency() );
        assertEqualSubtree( allNodes, this.toDependencyResult( result.getRoot(), "root-scope", null ) );

        // Exclude direct scope equals include all as direct dependencies are always included.
        this.session.setDependencySelector( new ScopeDependencySelector( "direct-scope" ) );
        result = collector.collectDependencies( session, rootArtifactRequest );
        assertNull( result.getRoot().getDependency() );
        assertEqualSubtree( allNodes, this.toDependencyResult( result.getRoot(), "root-scope", null ) );

        // Exclude scope of transitive of direct.
        this.session.setDependencySelector( new ScopeDependencySelector( "transitive-1-scope" ) );
        result = collector.collectDependencies( session, rootArtifactRequest );
        assertNull( result.getRoot().getDependency() );
        assertEqualSubtree( transitive1ExclusionTree, this.toDependencyResult( result.getRoot(), "root-scope", null ) );

        // Exclude scope of transitive of transitive of direct dependency.
        this.session.setDependencySelector( new ScopeDependencySelector( "transitive-2-scope" ) );
        result = collector.collectDependencies( session, rootArtifactRequest );
        assertNull( result.getRoot().getDependency() );
        assertEqualSubtree( transitive2ExclusionTree, this.toDependencyResult( result.getRoot(), "root-scope", null ) );
    }

    @Test
    public void testSelectionWithOptionalDependencySelector()
        throws DependencyCollectionException, IOException
    {
        collector.setArtifactDescriptorReader( newReader( "selection/optional/" ) );
        parser = new DependencyGraphParser( "artifact-descriptions/selection/optional/" );
        session.setDependencySelector( null );

        final DependencyNode allNodes = parser.parseResource( "all-nodes.txt" );
        final DependencyNode optionalTransitiveExclusionTree =
            parser.parseResource( "optional-transitive-exclusion-tree.txt" );

        final Dependency root = newDep( "gid:root:ext:ver", "root-scope" ).setOptional( true );
        // No selector. Include all.
        final CollectRequest request = new CollectRequest( root, Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );
        assertEqualSubtree( allNodes, result.getRoot() );

        // A dependency selector that excludes transitive optional dependencies.
        this.session.setDependencySelector( new OptionalDependencySelector() );
        result = collector.collectDependencies( session, request );
        assertEqualSubtree( optionalTransitiveExclusionTree, result.getRoot() );

        // Same test as above but with root artifact instead of root dependency.
        this.session.setDependencySelector( null );
        final Artifact rootArtifact = new DefaultArtifact( "gid:root:ext:ver" );
        final CollectRequest rootArtifactRequest = new CollectRequest();
        rootArtifactRequest.setRootArtifact( rootArtifact );
        rootArtifactRequest.addDependency( newDep( "gid:direct:ext:ver", "direct-scope" ).setOptional( true ) );
        result = collector.collectDependencies( session, rootArtifactRequest );
        assertNull( result.getRoot().getDependency() );
        assertEqualSubtree( allNodes, this.toDependencyResult( result.getRoot(), "root-scope", true ) );

        // A dependency selector that excludes transitive optional dependencies.
        this.session.setDependencySelector( new OptionalDependencySelector() );
        result = collector.collectDependencies( session, rootArtifactRequest );
        assertNull( result.getRoot().getDependency() );
        assertEqualSubtree( optionalTransitiveExclusionTree, this.toDependencyResult( result.getRoot(), "root-scope",
                                                                                      true ) );

    }

    private DependencyNode toDependencyResult( final DependencyNode root, final String rootScope,
                                               final Boolean optional )
    {
        // Make the root artifact resultion result a dependency resolution result for the subtree check.
        assertNull( "Expected root artifact resolution result.", root.getDependency() );
        final DefaultDependencyNode defaultNode =
            new DefaultDependencyNode( new Dependency( root.getArtifact(), rootScope ) );

        defaultNode.setChildren( root.getChildren() );

        if ( optional != null )
        {
            defaultNode.setOptional( optional );
        }

        return defaultNode;
    }

    static class TestDependencyManager
        implements DependencyManager
    {

        private Map<String, String> versions = new HashMap<String, String>();

        private Map<String, String> scopes = new HashMap<String, String>();

        private Map<String, Boolean> optionals = new HashMap<String, Boolean>();

        private Map<String, String> paths = new HashMap<String, String>();

        private Map<String, Collection<Exclusion>> exclusions = new HashMap<String, Collection<Exclusion>>();

        public void add( Dependency d, String version, String scope, String localPath )
        {
            String id = toKey( d );
            version( id, version );
            scope( id, scope );
            path( id, localPath );
        }

        public void version( String id, String version )
        {
            versions.put( id, version );
        }

        public void scope( String id, String scope )
        {
            scopes.put( id, scope );
        }

        public void optional( String id, Boolean optional )
        {
            optionals.put( id, optional );
        }

        public void path( String id, String path )
        {
            paths.put( id, path );
        }

        public void exclusions( String id, Exclusion... exclusions )
        {
            this.exclusions.put( id, exclusions != null ? Arrays.asList( exclusions ) : null );
        }

        public DependencyManagement manageDependency( Dependency d )
        {
            String id = toKey( d );
            DependencyManagement mgmt = new DependencyManagement();
            mgmt.setVersion( versions.get( id ) );
            mgmt.setScope( scopes.get( id ) );
            mgmt.setOptional( optionals.get( id ) );
            String path = paths.get( id );
            if ( path != null )
            {
                mgmt.setProperties( Collections.singletonMap( ArtifactProperties.LOCAL_PATH, path ) );
            }
            mgmt.setExclusions( exclusions.get( id ) );
            return mgmt;
        }

        private String toKey( Dependency dependency )
        {
            return ArtifactIdUtils.toVersionlessId( dependency.getArtifact() );
        }

        public DependencyManager deriveChildManager( DependencyCollectionContext context )
        {
            return this;
        }

    }

}
