
package org.fcrepo.federation.bagit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import org.modeshape.jcr.value.Name;
import org.modeshape.jcr.value.Property;


public class BagItExtraPropertiesStoreTest {

    BagItExtraPropertiesStore store;

    @Before
    public void setUp() {
        BagItConnector connector = mock(BagItConnector.class);
        store = new BagItExtraPropertiesStore(connector);
    }

/*
    @Test
    public void testRead() {
        // TODO read a properties file from disk
    }

    @Test
    public void testWrite() {
        // TODO write a properties file to disk then read it back
    }

    @Test
    public void testUpdate() {
        // TODO update a properties file and make sure new props saved, delete dprops removed
    }

    @Test
    public void testRemove() {
        // TODO remove a properties file
    }
*/
}