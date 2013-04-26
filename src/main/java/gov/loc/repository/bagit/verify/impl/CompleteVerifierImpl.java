
package gov.loc.repository.bagit.verify.impl;

import static com.google.common.base.Throwables.propagate;
import static gov.loc.repository.bagit.filesystem.FileSystemFactory.getDirNodeForBag;
import static java.text.MessageFormat.format;
import gov.loc.repository.bagit.Bag;
import gov.loc.repository.bagit.BagFactory.Version;
import gov.loc.repository.bagit.BagFile;
import gov.loc.repository.bagit.Manifest;
import gov.loc.repository.bagit.filesystem.DirNode;
import gov.loc.repository.bagit.filesystem.FileNode;
import gov.loc.repository.bagit.filesystem.FileSystemFactory.UnsupportedFormatException;
import gov.loc.repository.bagit.filesystem.FileSystemNode;
import gov.loc.repository.bagit.filesystem.filter.AndFileSystemNodeFilter;
import gov.loc.repository.bagit.filesystem.filter.DirNodeFileSystemNodeFilter;
import gov.loc.repository.bagit.filesystem.filter.FileNodeFileSystemNodeFilter;
import gov.loc.repository.bagit.filesystem.filter.IgnoringFileSystemNodeFilter;
import gov.loc.repository.bagit.utilities.FilenameHelper;
import gov.loc.repository.bagit.utilities.FormatHelper.UnknownFormatException;
import gov.loc.repository.bagit.utilities.LongRunningOperationBase;
import gov.loc.repository.bagit.utilities.SimpleResult;
import gov.loc.repository.bagit.utilities.SimpleResultHelper;
import gov.loc.repository.bagit.verify.CompleteVerifier;
import gov.loc.repository.bagit.verify.FailModeSupporting;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompleteVerifierImpl extends LongRunningOperationBase implements
        CompleteVerifier, FailModeSupporting {

    private static final Logger log = LoggerFactory
            .getLogger(CompleteVerifierImpl.class);

    private boolean missingBagItTolerant = false;

    private boolean additionalDirectoriesInBagDirTolerant = false;

    private List<String> ignoreAdditionalDirectories = new ArrayList<String>();

    private boolean ignoreSymlinks = false;

    private FailMode failMode = FailMode.FAIL_STAGE;

    public void setIgnoreSymlinks(final boolean ignore) {
        this.ignoreSymlinks = ignore;
    }

    public void setMissingBagItTolerant(final boolean missingBagItTolerant) {
        this.missingBagItTolerant = missingBagItTolerant;
    }

    public void setAdditionalDirectoriesInBagDirTolerant(
            final boolean additionalDirectoriesInBagDirTolerant) {
        this.additionalDirectoriesInBagDirTolerant =
                additionalDirectoriesInBagDirTolerant;

    }

    public void setIgnoreAdditionalDirectories(final List<String> dirs) {
        this.ignoreAdditionalDirectories = dirs;
    }

    @Override
    public void setFailMode(final FailMode failMode) {
        this.failMode = failMode;
    }

    @Override
    public FailMode getFailMode() {
        return this.failMode;
    }

    @Override
    public SimpleResult verify(final Bag bag) {
        boolean allowTagDirectories = true;
        if (!additionalDirectoriesInBagDirTolerant &&
                (Version.V0_93 == bag.getVersion() ||
                        Version.V0_94 == bag.getVersion() ||
                        Version.V0_95 == bag.getVersion() || Version.V0_96 == bag
                        .getVersion())) {
            allowTagDirectories = false;
        }

        final SimpleResult result = new SimpleResult(true);
        //Is at least one payload manifest
        log.debug("Checking that at least one payload manifest");
        if (bag.getPayloadManifests().isEmpty()) {
            result.setSuccess(false);
            result.addMessage(CODE_NO_PAYLOAD_MANIFEST,
                    "Bag does not have any payload manifests.");
            if (FailMode.FAIL_FAST == failMode) {
                return result;
            }

        }
        //Has bagit file
        log.debug("Checking that has BagIt.txt");
        if (!this.missingBagItTolerant && bag.getBagItTxt() == null) {
            result.setSuccess(false);
            result.addMessage(CODE_NO_BAGITTXT, MessageFormat.format(
                    "Bag does not have {0}.", bag.getBagConstants()
                            .getBagItTxt()));
            if (FailMode.FAIL_FAST == failMode) {
                return result;
            }
        }

        //Bagit is right version
        log.debug("Checking that BagIt.txt is right version");
        if (!this.missingBagItTolerant &&
                bag.getBagItTxt() != null &&
                !bag.getBagConstants().getVersion().versionString.equals(bag
                        .getBagItTxt().getVersion())) {
            result.setSuccess(false);
            result.addMessage(CODE_WRONG_VERSION, MessageFormat.format(
                    "Version is not {0}.", bag.getBagConstants().getVersion()));
            if (FailMode.FAIL_FAST == failMode) {
                return result;
            }
        }

        if (this.isCancelled()) {
            return null;
        }
        if (FailMode.FAIL_STEP == failMode && !result.isSuccess()) {
            return result;
        }

        //All payload files are in data directory
        log.debug("Checking that all payload files in data directory");
        int total = bag.getPayload().size();
        int count = 0;
        for (final BagFile bagFile : bag.getPayload()) {
            if (this.isCancelled()) {
                return null;
            }
            final String filepath = bagFile.getFilepath();
            count++;
            this.progress("verifying payload file in data directory", filepath,
                    count, total);
            log.trace(MessageFormat.format(
                    "Verifying payload {0} in data directory", filepath));
            if (!filepath
                    .startsWith(bag.getBagConstants().getDataDirectory() + '/')) {
                result.setSuccess(false);
                result.addMessage(CODE_PAYLOAD_NOT_IN_PAYLOAD_DIRECTORY,
                        MessageFormat.format(
                                "Payload file {0} not in the {1} directory.",
                                filepath, bag.getBagConstants()
                                        .getDataDirectory()), filepath);
                log.warn(MessageFormat.format(
                        "Payload file {0} not in data directory", filepath));
                if (FailMode.FAIL_FAST == failMode) {
                    return result;
                }
            }
        }
        if (FailMode.FAIL_STEP == failMode && !result.isSuccess()) {
            return result;
        }

        // Ensure no tag files are listed in the payload manifest.
        log.debug("Checking that no tag files are listed in payload manifests.");
        final String payloadDirName = bag.getBagConstants().getDataDirectory();

        for (final Manifest manifest : bag.getPayloadManifests()) {
            if (this.isCancelled()) {
                return null;
            }

            this.progress("checking payload manifest for tag files", manifest
                    .getFilepath());

            for (final String path : manifest.keySet()) {
                final String normalizedPath =
                        FilenameHelper.normalizePath(path);
                log.trace(format("Normalized path: {0} -> {1}", path,
                        normalizedPath));

                if (!normalizedPath.startsWith(payloadDirName)) {
                    result.setSuccess(false);
                    result.addMessage(CODE_TAG_IN_PAYLOAD_MANIFEST,
                            "Tag file is listed in payload manifest {0}: {1}",
                            manifest.getFilepath(), path);
                    if (FailMode.FAIL_FAST == failMode) {
                        return result;
                    }
                }
            }
        }
        if (FailMode.FAIL_STEP == failMode && !result.isSuccess()) {
            return result;
        }

        //Every payload BagFile in at least one manifest
        log.debug("Checking that every payload file in at least one manifest");
        total = bag.getPayload().size();
        log.trace(MessageFormat.format("{0} payload files to check", total));
        count = 0;
        for (final BagFile bagFile : bag.getPayload()) {
            final String filepath = bagFile.getFilepath();
            count++;
            this.progress("verifying payload file in at least one manifest",
                    filepath, count, total);
            log.trace(MessageFormat.format(
                    "Verifying payload file {0} in at least one manifest",
                    filepath));
            boolean inManifest = false;
            for (final Manifest manifest : bag.getPayloadManifests()) {
                if (this.isCancelled()) {
                    return null;
                }
                if (manifest.containsKey(filepath)) {
                    inManifest = true;
                    break;
                }
            }
            if (!inManifest) {
                result.setSuccess(false);
                result.addMessage(CODE_PAYLOAD_FILE_NOT_IN_PAYLOAD_MANIFEST,
                        "Payload file {0} not found in any payload manifest.",
                        filepath);
                log.warn(MessageFormat.format(
                        "Payload file {0} not found in any payload manifest.",
                        filepath));
                if (FailMode.FAIL_FAST == failMode) {
                    return result;
                }
            }
        }
        if (FailMode.FAIL_STEP == failMode && !result.isSuccess()) {
            return result;
        }

        //Every payload file exists
        log.debug("Checking that every payload file exists");
        total = bag.getPayloadManifests().size();
        log.trace(MessageFormat.format("{0} payload manifests to check", total));
        count = 0;
        for (final Manifest manifest : bag.getPayloadManifests()) {
            count++;
            this.progress("verifying payload files in manifest exist", manifest
                    .getFilepath(), count, total);
            this.checkManifest(manifest, bag, result);
            if (this.isCancelled()) {
                return null;
            }
            if (FailMode.FAIL_FAST == failMode && !result.isSuccess()) {
                return result;
            }
        }
        if (FailMode.FAIL_STEP == failMode && !result.isSuccess()) {
            return result;
        }

        //Every tag file exists
        log.debug("Checking that every tag file exists");
        total = bag.getTagManifests().size();
        log.trace(MessageFormat.format("{0} tag manifests to check", total));
        count = 0;
        for (final Manifest manifest : bag.getTagManifests()) {
            count++;
            this.progress("verifying tag files in manifest exist", manifest
                    .getFilepath(), count, total);
            this.checkManifest(manifest, bag, result);
            if (this.isCancelled()) {
                return null;
            }
            if (FailMode.FAIL_FAST == failMode && !result.isSuccess()) {
                return result;
            }
        }
        if (FailMode.FAIL_STEP == failMode && !result.isSuccess()) {
            return result;
        }

        //Additional checks if an existing Bag
        if (bag.getFile() != null) {
            DirNode bagDirNode = null;
            try {
                bagDirNode = getDirNodeForBag(bag.getFile());
            } catch (final UnknownFormatException | UnsupportedFormatException
                    | IOException e) {
                propagate(e);
            }
            try {
                //FileObject bagFileObject = VFSHelper.getFileObjectForBag(bag.getFile());
                //Only directory is a data directory
                log.debug("Checking that only directory is data directory");
                if (!allowTagDirectories) {
                    final Collection<FileSystemNode> dirNodes =
                            bagDirNode
                                    .listChildren(new AndFileSystemNodeFilter(
                                            new DirNodeFileSystemNodeFilter(),
                                            new IgnoringFileSystemNodeFilter(
                                                    ignoreAdditionalDirectories,
                                                    false)));
                    for (final FileSystemNode dirNode : dirNodes) {
                        if (!bag.getBagConstants().getDataDirectory().equals(
                                dirNode.getName())) {
                            result.setSuccess(false);
                            result.addMessage(
                                    CODE_DIRECTORY_NOT_ALLOWED_IN_BAG_DIR,
                                    "Directory {0} not allowed in bag_dir.",
                                    dirNode.getName());
                            if (FailMode.FAIL_FAST == failMode) {
                                return result;
                            }
                        }
                    }
                }
                if (FailMode.FAIL_STEP == failMode && !result.isSuccess()) {
                    return result;
                }

                log.debug("Checking that all payload files on disk included in bag");
                final DirNode dataDirNode =
                        bagDirNode.childDir(bag.getBagConstants()
                                .getDataDirectory());
                if (dataDirNode != null) {
                    final Collection<FileSystemNode> nodes =
                            dataDirNode.listDescendants(
                                    new FileNodeFileSystemNodeFilter(),
                                    new IgnoringFileSystemNodeFilter(
                                            ignoreAdditionalDirectories,
                                            ignoreSymlinks));
                    total = nodes.size();
                    count = 0;
                    for (final FileSystemNode node : nodes) {
                        if (this.isCancelled()) {
                            return null;
                        }
                        final FileNode fileNode = (FileNode) node;
                        final String filepath =
                                FilenameHelper.removeBasePath(bagDirNode
                                        .getFilepath(), fileNode.getFilepath());
                        count++;
                        this.progress(
                                "verifying payload files on disk are in bag",
                                filepath, count, total);
                        log.trace(MessageFormat.format(
                                "Checking that payload file {0} is in bag",
                                filepath));
                        if (bag.getBagFile(filepath) == null) {
                            result.setSuccess(false);
                            result.addMessage(
                                    CODE_PAYLOAD_FILE_NOT_IN_PAYLOAD_MANIFEST,
                                    "Payload file {0} not found in any payload manifest.",
                                    filepath);
                            final String msg =
                                    MessageFormat
                                            .format("Bag has file {0} not found in manifest file.",
                                                    filepath);
                            log.warn(msg);
                            if (FailMode.FAIL_FAST == failMode &&
                                    !result.isSuccess()) {
                                return result;
                            }
                        }

                    }
                    if (FailMode.FAIL_STEP == failMode && !result.isSuccess()) {
                        return result;
                    }

                }
            } finally {
                bagDirNode.getFileSystem().closeQuietly();
            }

        } else {
            log.debug("Not an existing bag");
        }

        //Check payload-oxum
        log.info("Completed verification that bag is complete.");
        log.info("Note that this a verification of completeness, not validity. A bag may be complete without being valid, though a valid bag must be complete.");
        log.info("Result of verification that complete: " + result.toString());
        return result;

    }

    protected void checkManifest(final Manifest manifest, final Bag bag,
            final SimpleResult result) {
        log.trace("Checking manifest " + manifest.getFilepath());
        final int manifestTotal = manifest.keySet().size();
        int manifestCount = 0;
        for (final String filepath : manifest.keySet()) {
            if (this.isCancelled()) {
                return;
            }
            manifestCount++;
            this.progress("verifying files in manifest exist", filepath,
                    manifestCount, manifestTotal);
            log.trace(MessageFormat.format(
                    "Checking that file {0} in manifest {1} exists", filepath,
                    manifest.getFilepath()));
            final BagFile bagFile = bag.getBagFile(filepath);
            if (bagFile == null || !bagFile.exists()) {
                if (manifest.isPayloadManifest()) {
                    SimpleResultHelper.missingPayloadFile(result, manifest
                            .getFilepath(), filepath);
                } else {
                    SimpleResultHelper.missingTagFile(result, manifest
                            .getFilepath(), filepath);
                }
                final String message =
                        MessageFormat.format(
                                "File {0} in manifest {1} missing from bag.",
                                filepath, manifest.getFilepath());
                log.warn(message);
                if (FailMode.FAIL_FAST == failMode) {
                    return;
                }
            }
        }

    }

}
