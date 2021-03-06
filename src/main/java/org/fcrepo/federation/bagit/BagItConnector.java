/**
 * Copyright 2013 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fcrepo.federation.bagit;

import static org.modeshape.jcr.api.JcrConstants.JCR_CONTENT;
import static org.modeshape.jcr.api.JcrConstants.JCR_DATA;
import static org.modeshape.jcr.api.JcrConstants.NT_FOLDER;
import static org.modeshape.jcr.api.JcrConstants.NT_RESOURCE;
import gov.loc.repository.bagit.impl.FileBagFile;
import gov.loc.repository.bagit.v0_97.impl.BagConstantsImpl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.NamespaceRegistry;
import javax.jcr.RepositoryException;

import org.apache.poi.util.TempFile;
import org.infinispan.schematic.document.Document;
import org.modeshape.connector.filesystem.FileSystemConnector;
import org.modeshape.jcr.JcrI18n;
import org.modeshape.jcr.JcrLexicon;
import org.modeshape.jcr.api.JcrConstants;
import org.modeshape.jcr.api.nodetype.NodeTypeManager;
import org.modeshape.jcr.api.value.DateTime;
import org.modeshape.jcr.cache.DocumentStoreException;
import org.modeshape.jcr.federation.spi.ConnectorChangeSet;
import org.modeshape.jcr.federation.spi.DocumentChanges;
import org.modeshape.jcr.federation.spi.DocumentReader;
import org.modeshape.jcr.federation.spi.DocumentWriter;
import org.modeshape.jcr.value.BinaryValue;
import org.modeshape.jcr.value.Property;
import org.modeshape.jcr.value.PropertyFactory;
import org.modeshape.jcr.value.PropertyType;
import org.modeshape.jcr.value.ValueFactories;
import org.modeshape.jcr.value.basic.BasicPropertyFactory;

public class BagItConnector extends FileSystemConnector {

    private static final String BAGIT_ARCHIVE_TYPE = "bagit:archive";

    // NOT THE File.pathSeparator;
    private static final char JCR_PATH_DELIMITER_CHAR = '/';

    // NOT THE File.pathSeparator;
    private static final String JCR_PATH_DELIMITER = "/";

    private static final String JCR_LAST_MODIFIED = "jcr:lastModified";

    private static final String JCR_CREATED = "jcr:created";

    private static final String JCR_CREATED_BY = "jcr:createdBy";

    private static final String JCR_LAST_MODIFIED_BY = "jcr:lastModified";

    private static final String MIX_MIME_TYPE = "mix:mimeType";

    private static final String JCR_MIME_TYPE = "jcr:mimeType";

    private static final String JCR_ENCODING = "jcr:encoding";

    private static final String JCR_CONTENT_SUFFIX = JCR_PATH_DELIMITER +
            JCR_CONTENT;

    private static final int JCR_CONTENT_SUFFIX_LENGTH = JCR_CONTENT_SUFFIX
            .length();

    /**
     * A boolean flag that specifies whether this connector should add the
     * 'mix:mimeType' mixin to the 'nt:resource' nodes to include the
     * 'jcr:mimeType' property. If set to <code>true</code>, the MIME type is
     * computed immediately when the 'nt:resource' node is accessed, which might
     * be expensive for larger files. This is <code>false</code> by default.
     */
    private final boolean addMimeTypeMixin = false;

    /**
     * The string path for a {@link File} object that represents the top-level
     * directory accessed by this connector. This is set via reflection and is
     * required for this connector.
     */
    private String directoryPath;

    // it appears to be the case that bootstrapping the federated nodes results
    // in a pre-init call to the connector
    // so this is a dummy file for that situation
    private File m_directory = TempFile.createTempFile("stub", "stub");

    private Path rootPath;

    /**
     * A string that is created in the
     * {@link #initialize(NamespaceRegistry, NodeTypeManager)} method that
     * represents the absolute path to the {@link #m_directory}. This path is
     * removed from an absolute path of a file to obtain the ID of the node.
     */
    private String directoryAbsolutePath;

    private int directoryAbsolutePathLength;

    private ExecutorService threadPool;

    DocumentWriterFactory m_writerFactory;

    public void setDirectoryPath(final String directoryPath) {
        this.directoryPath = directoryPath;
        m_directory = new File(directoryPath);
    }

    public void setDirectory(final File directory) {
        m_directory = directory;
        this.directoryPath = directory.getAbsolutePath();
    }

    @Override
    public void initialize(final NamespaceRegistry registry,
            final NodeTypeManager nodeTypeManager) throws RepositoryException,
        IOException {
        getLogger().trace("Initializing at " + this.directoryPath + " ...");
        // Initialize the directory path field that has been set via reflection
        // when this method is called...
        m_writerFactory = new DocumentWriterFactory(translator());
        checkFieldNotNull(directoryPath, "directoryPath");
        m_directory = new File(directoryPath);
        if (!m_directory.exists() || !m_directory.isDirectory()) {
            final String msg =
                    JcrI18n.fileConnectorTopLevelDirectoryMissingOrCannotBeRead
                            .text(getSourceName(), "directoryPath");
            throw new RepositoryException(msg);
        }
        if (!m_directory.canRead() && !m_directory.setReadable(true)) {
            final String msg =
                    JcrI18n.fileConnectorTopLevelDirectoryMissingOrCannotBeRead
                            .text(getSourceName(), "directoryPath");
            throw new RepositoryException(msg);
        }
        directoryAbsolutePath = m_directory.getAbsolutePath();
        getLogger().debug(
                "Using filesystem directory: " + directoryAbsolutePath);
        if (!directoryAbsolutePath.endsWith(File.separator)) {
            directoryAbsolutePath = directoryAbsolutePath + File.separator;
        }
        directoryAbsolutePathLength =
                directoryAbsolutePath.length() - File.separator.length(); // does
                                                                          // NOT
                                                                          // include
                                                                          // the
                                                                          // separator

        rootPath = Paths.get(directoryAbsolutePath);

        setExtraPropertiesStore(new BagItExtraPropertiesStore(this));
        getLogger().trace("Initialized. rootPath: {}", rootPath);
        final BlockingQueue<Runnable> workQueue =
                new ArrayBlockingQueue<Runnable>(1);
        threadPool =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, workQueue);
        getLogger().trace("Threadpool initialized.");
        threadPool.execute(new ManifestMonitor(this));
        getLogger().trace("Monitor thread queued.");
    }

    @Override
    public void shutdown() {
        threadPool.shutdown();
        getLogger().trace("Threadpool shutdown.");
    }

    @Override
    public Document getDocumentById(final String id) {
        getLogger().trace("Entering getDocumentById()...");
        getLogger().trace("Received request for document: " + id);
        final File file = fileFor(id);
        // getLogger().debug(
        // "Received request for document: " + id + ", resolved to " +
        // file);
        if (file == null || isExcluded(file) || !file.exists()) {
            return null;
        }
        final boolean isRoot = isRoot(id);
        final boolean isResource = isContentNode(id);
        final DocumentWriter writer = newDocument(id);
        File parentFile = file.getParentFile();
        if (isRoot) {
            getLogger().trace(
                    "Determined document: " + id +
                            " to be the projection root.");
            writer.setPrimaryType(NT_FOLDER);
            writer.addProperty(JCR_CREATED, factories().getDateFactory()
                    .create(file.lastModified()));
            writer.addProperty(JCR_CREATED_BY, null); // ignored
            for (final File child : file.listFiles()) {
                // Only include as a datastream if we can access and read the
                // file. Permissions might prevent us from
                // reading the file, and the file might not exist if it is a
                // broken symlink (see MODE-1768 for details).
                if (child.exists() && child.canRead() &&
                        (child.isFile() || child.isDirectory())) {
                    // We use identifiers that contain the file/directory name
                    // ...
                    final String childName = child.getName();
                    final String childId =
                            isRoot ? File.separator + childName : id +
                                    File.separator + childName;
                    writer.addChild(childId, childName);
                }
            }
        } else if (isResource) {
            getLogger().trace(
                    "Determined document: " + id + " to be a binary resource.");
            final BinaryValue binaryValue = binaryFor(file);
            writer.setPrimaryType(NT_RESOURCE);
            writer.addProperty(JCR_DATA, binaryValue);
            if (addMimeTypeMixin) {
                writer.addMixinType(MIX_MIME_TYPE);
                String mimeType = null;
                final String encoding = null; // We don't really know this
                try {
                    mimeType = binaryValue.getMimeType();
                } catch (final Throwable e) {
                    getLogger().error(e, JcrI18n.couldNotGetMimeType,
                            getSourceName(), id, e.getMessage());
                }
                writer.addProperty(JCR_ENCODING, encoding);
                writer.addProperty(JCR_MIME_TYPE, mimeType);
            }
            writer.addProperty(JCR_LAST_MODIFIED, factories().getDateFactory()
                    .create(file.lastModified()));
            writer.addProperty(JCR_LAST_MODIFIED_BY, null); // ignored

            // make these binary not queryable. If we really want to query them,
            // we need to switch to external binaries
            writer.setNotQueryable();
            parentFile = file;
        } else if (file.isFile()) {
            getLogger().trace(
                    "Determined document: " + id + " to be a datastream.");
            writer.setPrimaryType(JcrConstants.NT_FILE);
            writer.addProperty(JCR_CREATED, factories().getDateFactory()
                    .create(file.lastModified()));
            try {
                final String owner = Files.getOwner(file.toPath()).getName();
                writer.addProperty(JCR_CREATED_BY, owner);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
            final String childId =
                    isRoot ? JCR_CONTENT_SUFFIX : id + JCR_CONTENT_SUFFIX;
            writer.addChild(childId, JCR_CONTENT);
        } else {
            getLogger().trace(
                    "Determined document: " + id + " to be a Fedora object.");
            final File dataDir =
                    new File(new File(file.getAbsolutePath()), "data");
            getLogger()
                    .trace("searching data dir " + dataDir.getAbsolutePath());
            writer.setPrimaryType(NT_FOLDER);
            writer.addMixinType(BAGIT_ARCHIVE_TYPE);
            writer.addProperty(JCR_CREATED, factories().getDateFactory()
                    .create(file.lastModified()));
            try {
                final String owner = Files.getOwner(file.toPath()).getName();
                writer.addProperty(JCR_CREATED_BY, owner); // required
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
            // get datastreams as children
            for (final File child : dataDir.listFiles()) {
                // Only include as a datastream if we can access and read the
                // file. Permissions might prevent us from
                // reading the file, and the file might not exist if it is a
                // broken symlink (see MODE-1768 for details).
                if (child.exists() && child.canRead() &&
                        (child.isFile() || child.isDirectory())) {
                    // We use identifiers that contain the file/directory name
                    // ...
                    final String childName = child.getName();
                    final String childId =
                            isRoot ? File.separator + childName : id +
                                    File.separator + childName;
                    writer.addChild(childId, childName);
                }
            }
        }

        if (!isRoot) {
            // Set the reference to the parent ...
            final String parentId = idFor(parentFile);
            writer.setParent(parentId);
        }

        // Add the extra properties (if there are any), overwriting any
        // properties with the same names
        // (e.g., jcr:primaryType, jcr:mixinTypes, jcr:mimeType, etc.) ...
        writer.addProperties(new BagItExtraPropertiesStore(this)
                .getProperties(id));
        getLogger().trace("Leaving getDocumentById().");
        return writer.document();
    }

    @Override
    public DocumentWriter newDocument(final String id) {
        return m_writerFactory.getDocumentWriter(id);
    }

    @Override
    public void storeDocument(final Document document) {
        // TODO Auto-generated method stub
        getLogger().debug("storeDocument(" + document.toString() + ")");
    }

    @Override
    public void updateDocument(final DocumentChanges documentChanges) {
        // TODO Auto-generated method stub
        getLogger().debug("updateDocument(" + documentChanges.toString() + ")");
    }

    File getBagItDirectory() {
        return this.m_directory;
    }

    @Override
    protected File fileFor(String id) {
        assert id.startsWith(JCR_PATH_DELIMITER);
        if (id.endsWith(JCR_PATH_DELIMITER)) {
            id = id.substring(0, id.length() - JCR_PATH_DELIMITER.length());
        }
        if (isContentNode(id)) {
            id = id.substring(0, id.length() - JCR_CONTENT_SUFFIX_LENGTH);
        }
        if ("".equals(id)) {
            getLogger().trace(
                    "#fileFor returning root directory for \"" + id + "\"");
            return this.m_directory; // root node
        }

        if (isContentNode(id)) {
            id = id.substring(0, id.length() - JCR_CONTENT_SUFFIX_LENGTH);
        }
        // /{bagId}/{dsId}(/{jcr:content})?
        final Pattern p = Pattern.compile("^(\\/[^\\/]+)(\\/[^\\/]+)");
        final Matcher m = p.matcher(id);
        if (m.find()) {
            id =
                    id.replace(m.group(1), m.group(1) + JCR_PATH_DELIMITER +
                            "data"); // because we're going to swap the delims
                                     // out for the system seperator
        }

        final File result =
                new File(this.m_directory, id.replace(JCR_PATH_DELIMITER_CHAR,
                        File.separatorChar));
        getLogger().trace(result.getAbsolutePath());
        // return super.fileFor(id);
        return result;
    }

    protected File bagInfoFileFor(final String id) {
        final File dir = fileFor(id);
        final File result = new File(dir, "bag-info.txt");
        return (result.exists()) ? result : null;
    }

    @Override
    protected boolean isExcluded(final File file) {
        // TODO this should check the data manifest
        return file == null || !file.exists();
    }

    @Override
    /**
     * DIRECTLY COPIED UNTIL WE SORT OUT HOW TO EFFECTIVELY SUBCLASS
     * Utility method for determining the node identifier for the supplied file. Subclasses may override this method to change the
     * format of the identifiers, but in that case should also override the {@link #fileFor(String)},
     * {@link #isContentNode(String)}, and {@link #isRoot(String)} methods.
     *
     * @param file the file; may not be null
     * @return the node identifier; never null
     * @see #isRoot(String)
     * @see #isContentNode(String)
     * @see #fileFor(String)
     */
    protected String idFor(final File file) {
        final String path = file.getAbsolutePath();
        if (!path.startsWith(directoryAbsolutePath)) {
            if (m_directory.getAbsolutePath().equals(path)) {
                // This is the root
                return JCR_PATH_DELIMITER;
            }
            final String msg =
                    JcrI18n.fileConnectorNodeIdentifierIsNotWithinScopeOfConnector
                            .text(getSourceName(), directoryPath, path);
            throw new DocumentStoreException(path, msg);
        }
        String id = path.substring(directoryAbsolutePathLength);
        id =
                id.replace(File.separator + "data" + File.separator,
                        File.separator); // data dir should be removed from the
                                         // id of a DS node
        if (id.endsWith(File.separator + "data")) {
            id = id.substring(0, id.length() - 5); // might also be the parent
                                                   // file of a DS node
        }
        id = id.replace(File.separatorChar, JCR_PATH_DELIMITER_CHAR);
        if ("".equals(id)) {
            id = JCR_PATH_DELIMITER;
        }
        assert id.startsWith(JCR_PATH_DELIMITER);
        // System.out.println("idFor = " + id);
        return id;
    }

    protected ValueFactories getValueFactories() {
        return getContext().getValueFactories();
    }

    protected PropertyFactory getPropertyFactory() {
        return getContext().getPropertyFactory();
    }

    protected BagInfo getBagInfo(final String id) {
        final File bagInfoFile = bagInfoFileFor(id);
        if (bagInfoFile == null) {
            return null;
        }
        // really need to get the version from bagit.txt, but start with
        // hard-coding
        final ValueFactories vf = getValueFactories();
        final BagInfo result =
                new BagInfo(id, new FileBagFile(bagInfoFile.getAbsolutePath(),
                        bagInfoFile), getPropertyFactory(),
                        vf.getNameFactory(), new BagConstantsImpl());
        return result;
    }

    /**
     * Sends a change set with a new node event for the bag.
     * 
     * @param p the path to the bag folder
     */
    protected void fireNewBagEvent(Path path) {
        ConnectorChangeSet changes = newConnectorChangedSet();
        String key = idFor(path.toFile());
        Document doc = getDocumentById(key);
        DocumentReader reader = readDocument(doc);
        getLogger().debug(
                "firing new bag node event with\n\tkey {0}\n\tpathToNode {1}",
                key, key);
        changes.nodeCreated(key, "/", key, reader.getProperties());
        changes.publish(null);
    }

    /**
     * @param path the path of the bag folder
     */
    public void fireRemoveBagEvent(Path path) {
        ConnectorChangeSet changes = newConnectorChangedSet();
        String key = idFor(path.toFile());
        getLogger()
                .debug("firing remove bag node event with\n\tkey {0}\n\tpathToNode {1}",
                        key, key);
        changes.nodeRemoved(key, "/", key);
        changes.publish(null);
    }

    /**
     * Sends a change set with a new node event for the bag.
     * 
     * @param p the path to the bag folder
     */
    protected void fireModifiedBagEvent(Path path) {
        ConnectorChangeSet changes = newConnectorChangedSet();
        String key = idFor(path.toFile());
        Document doc = getDocumentById(key);
        DocumentReader reader = readDocument(doc);
        getLogger()
                .debug("firing modified bag node event with\n\tkey {0}\n\tpathToNode {1}",
                        key, key);
        DateTime dt =
                this.factories().getDateFactory().create(
                        System.currentTimeMillis() - 10000);
        Property dtprop =
                new BasicPropertyFactory(factories()).create(
                        JcrLexicon.CREATED, PropertyType.DATE, dt);
        changes.propertyChanged(key, key, reader.getProperty(JCR_CREATED),
                dtprop);
        changes.publish(null);
    }
}
