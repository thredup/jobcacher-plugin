/*
 * The MIT License
 *
 * Copyright 2016 Peter Hayes.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.plugins.itemstorage.s3;

import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import hudson.util.DirScanner;
import jenkins.plugins.itemstorage.StorageFormat;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Copies all objects from the path in S3 to the target base path
 *
 * @author Peter Hayes
 */
public class S3DownloadAllCallable extends S3Callable<Integer> {
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = Logger.getLogger(S3DownloadAllCallable.class.getName());

    private final String bucketName;
    private final String pathPrefix;
    private final StorageFormat storageFormat;
    private final DirScanner.Glob scanner;


    public S3DownloadAllCallable(ClientHelper helper,
                                 String fileMask,
                                 String excludes,
                                 String bucketName,
                                 String pathPrefix,
                                 StorageFormat storageFormat) {
        super(helper);
        this.bucketName = bucketName;
        this.pathPrefix = pathPrefix;
        this.storageFormat = storageFormat;

        scanner = new DirScanner.Glob(fileMask, excludes);
    }

    /**
     * Download to executor
     */
    @Override
    public Integer invoke(TransferManager transferManager, File base, VirtualChannel channel) throws IOException, InterruptedException {
        if(!base.exists()) {
            if (!base.mkdirs()) {
                throw new IOException("Failed to create directory : " + base);
            }
        }

        switch (storageFormat) {
            case DIRECTORY:
                return downloadDirectory(transferManager, base);
            case ZIP: case TAR:
                return downloadArchive(transferManager, base, storageFormat);
        }

        throw new IllegalStateException("Unsupported storageFormat: " + storageFormat);
    }

    private int downloadDirectory(TransferManager transferManager, File base) throws IOException, InterruptedException {
        int totalCount;
        Downloads downloads = new Downloads();
        ObjectListing objectListing = null;

        do {
            objectListing = transferManager.getAmazonS3Client().listObjects(new ListObjectsRequest()
                    .withBucketName(bucketName)
                    .withPrefix(pathPrefix)
                    .withMarker(objectListing != null ? objectListing.getNextMarker() : null));

            for (S3ObjectSummary summary : objectListing.getObjectSummaries()) {
                downloads.startDownload(transferManager, base, pathPrefix, summary);
            }

        } while (objectListing.getNextMarker() != null);

        // Grab # of files copied
        totalCount = downloads.count();

        // Finish the asynchronous downloading process
        downloads.finishDownloading();

        return totalCount;
    }

    private int downloadArchive(TransferManager transferManager, File base, StorageFormat storageFormat) throws IOException, InterruptedException {
        File archive = File.createTempFile("upload", "archive");

        String s3key = pathPrefix + "/archive.zip";
        transferManager.download(bucketName, s3key, archive).waitForCompletion();

        FilePath archiveFilePath = new FilePath(archive);
        FilePath baseFilePath = new FilePath(base);
        switch (storageFormat) {
            case ZIP: archiveFilePath.unzip(baseFilePath); break;
            case TAR: archiveFilePath.untar(baseFilePath, FilePath.TarCompression.NONE); break;
        }

        if (!archive.delete()) {
            LOG.warning("Unable to delete temporary file " + archive);
        }

        // TODO: count files in the archive
        return 0;
    }
}