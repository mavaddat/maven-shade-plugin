package org.apache.maven.plugins.shade.mojo;

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

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.plugins.shade.ShadeRequest;
import org.apache.maven.plugins.shade.Shader;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ComponentsXmlResourceTransformer;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.PlexusConstants;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

/**
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
public class ShadeMojoTest
        extends AbstractMojoTestCase
{
    @Override
    protected void customizeContainerConfiguration(final ContainerConfiguration configuration) {
        configuration.setClassPathScanning(PlexusConstants.SCANNING_INDEX);
    }

    public void testManifestTransformerSelection() throws Exception
    {
        final ShadeMojo mojo = new ShadeMojo();
        final Method m = ShadeMojo.class.getDeclaredMethod("toResourceTransformers", String.class, List.class);
        m.setAccessible(true);

        final ManifestResourceTransformer defaultTfr = new ManifestResourceTransformer()
        {
            @Override
            public String toString() // when test fails junit does a toString so easier to read errors this way
            {
                return "default";
            }
        };
        final ManifestResourceTransformer testsTfr1 = new ManifestResourceTransformer()
        {
            @Override
            public String toString()
            {
                return "t1";
            }
        };
        testsTfr1.setForShade("tests");
        final ManifestResourceTransformer testsTfr2 = new ManifestResourceTransformer()
        {
            @Override
            public String toString()
            {
                return "t2";
            }
        };
        testsTfr2.setForShade("tests");

        assertEquals(
                singletonList( defaultTfr ),
                m.invoke( mojo, "jar", asList( defaultTfr, testsTfr1, testsTfr2 ) ));
        assertEquals(
                asList( testsTfr1, testsTfr2 ),
                m.invoke( mojo, "tests", asList( defaultTfr, testsTfr1, testsTfr2 ) ));
        assertEquals(
                asList( testsTfr1, testsTfr2 ),
                m.invoke( mojo, "tests", asList( testsTfr1, defaultTfr, testsTfr2 ) ));
        assertEquals(
                asList( testsTfr1, testsTfr2 ),
                m.invoke( mojo, "tests", asList( testsTfr1, testsTfr2, defaultTfr ) ));
    }

    public void testShaderWithDefaultShadedPattern()
        throws Exception
    {
        shaderWithPattern( null, new File( "target/foo-default.jar" ) );
    }

    public void testShaderWithCustomShadedPattern()
        throws Exception
    {
        shaderWithPattern( "org/shaded/plexus/util", new File( "target/foo-custom.jar" ) );
    }

    public void testShaderWithExclusions()
        throws Exception
    {
        File jarFile = new File( getBasedir(), "target/unit/foo-bar.jar" );

        Shader s = lookup( Shader.class );

        Set<File> set = new LinkedHashSet<>();
        set.add( new File( getBasedir(), "src/test/jars/test-artifact-1.0-SNAPSHOT.jar" ) );

        List<Relocator> relocators = new ArrayList<>();
        relocators.add( new SimpleRelocator( "org.codehaus.plexus.util", "hidden", null, Arrays.asList(
                "org.codehaus.plexus.util.xml.Xpp3Dom", "org.codehaus.plexus.util.xml.pull.*") ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        List<Filter> filters = new ArrayList<>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( jarFile );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );

        s.shade( shadeRequest );

        try ( URLClassLoader cl = new URLClassLoader( new URL[]{ jarFile.toURI().toURL() } ) ) {
            Class<?> c = cl.loadClass( "org.apache.maven.plugins.shade.Lib" );
    
            Field field = c.getDeclaredField( "CLASS_REALM_PACKAGE_IMPORT" );
            assertEquals( "org.codehaus.plexus.util.xml.pull", field.get( null ) );
    
            Method method = c.getDeclaredMethod( "getClassRealmPackageImport" );
            assertEquals( "org.codehaus.plexus.util.xml.pull", method.invoke( null ) );
        }
    }

    /**
     * Tests if a Filter is installed correctly, also if createSourcesJar is set to true.
     *
     * @throws Exception
     */
    public void testShadeWithFilter()
        throws Exception
    {
        // create and configure MavenProject
        MavenProject project = new MavenProject();
        ArtifactHandler artifactHandler = lookup( ArtifactHandler.class );
        Artifact artifact = new DefaultArtifact( "org.apache.myfaces.core", "myfaces-impl",
                VersionRange.createFromVersion( "2.0.1-SNAPSHOT" ), "compile", "jar",
                null, artifactHandler );
        artifact.setFile( new File("myfaces-impl-2.0.1-SNAPSHOT.jar") );
        project.setArtifact( artifact );

        ShadeMojo mojo = (ShadeMojo) lookupConfiguredMojo( project, "shade" );

        DefaultRepositorySystemSession repositorySystemSession = MavenRepositorySystemUtils.newSession();
        repositorySystemSession.setLocalRepositoryManager( new SimpleLocalRepositoryManagerFactory()
                .newInstance( repositorySystemSession, new LocalRepository( new File( "target/local-repo/" ) ) ) );
        MavenSession mavenSession = new MavenSession( getContainer(), repositorySystemSession, mock(
                MavenExecutionRequest.class), mock( MavenExecutionResult.class) );

        setVariableValueToObject( mojo, "session", mavenSession );

        // set createSourcesJar = true
        setVariableValueToObject( mojo, "createSourcesJar", true );

        RepositorySystem repositorySystem = mock( RepositorySystem.class );
        setVariableValueToObject( mojo, "repositorySystem", repositorySystem );

        ArtifactResult artifactResult = new ArtifactResult( new ArtifactRequest( mock(
                org.eclipse.aether.artifact.Artifact.class ), Collections.emptyList(), "" ) );
        org.eclipse.aether.artifact.Artifact result = new org.eclipse.aether.artifact.DefaultArtifact(
                "org.apache.myfaces.core:myfaces-impl:jar:sources:2.0.1-SNAPSHOT" )
                .setFile( new File("myfaces-impl-2.0.1-SNAPSHOT-sources.jar") );
        artifactResult.setArtifact( result );
        when( repositorySystem.resolveArtifact( eq(mavenSession.getRepositorySession()), any( ArtifactRequest.class) ) )
                .thenReturn( artifactResult );

        // create and configure the ArchiveFilter
        ArchiveFilter archiveFilter = new ArchiveFilter();
        Field archiveFilterArtifact = ArchiveFilter.class.getDeclaredField( "artifact" );
        archiveFilterArtifact.setAccessible( true );
        archiveFilterArtifact.set( archiveFilter, "org.apache.myfaces.core:myfaces-impl" );

        // add ArchiveFilter to mojo
        Field filtersField = ShadeMojo.class.getDeclaredField( "filters" );
        filtersField.setAccessible( true );
        filtersField.set( mojo, new ArchiveFilter[]{ archiveFilter } );

        // invoke getFilters()
        Method getFilters = ShadeMojo.class.getDeclaredMethod( "getFilters" );
        getFilters.setAccessible( true );
        List<Filter> filters = (List<Filter>) getFilters.invoke( mojo);

        // assertions - there must be one filter
        assertEquals( 1, filters.size() );

        // the filter must be able to filter the binary and the sources jar
        Filter filter = filters.get( 0 );
        assertTrue( filter.canFilter( new File( "myfaces-impl-2.0.1-SNAPSHOT.jar" ) ) ); // binary jar
        assertTrue( filter.canFilter( new File( "myfaces-impl-2.0.1-SNAPSHOT-sources.jar" ) ) ); // sources jar
    }

    public void shaderWithPattern( String shadedPattern, File jar )
        throws Exception
    {
        Shader s = lookup( Shader.class );

        Set<File> set = new LinkedHashSet<>();

        set.add( new File( getBasedir(), "src/test/jars/test-project-1.0-SNAPSHOT.jar" ) );

        set.add( new File( getBasedir(), "src/test/jars/plexus-utils-1.4.1.jar" ) );

        List<Relocator> relocators = new ArrayList<>();

        relocators.add( new SimpleRelocator( "org/codehaus/plexus/util", shadedPattern, null, Arrays.asList(
                "org/codehaus/plexus/util/xml/Xpp3Dom", "org/codehaus/plexus/util/xml/pull.*") ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        resourceTransformers.add( new ComponentsXmlResourceTransformer() );

        List<Filter> filters = new ArrayList<>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( jar );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );

        s.shade( shadeRequest );
    }

}
