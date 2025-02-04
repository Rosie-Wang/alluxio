/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.fuse.file;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.client.file.FileOutStream;
import alluxio.client.file.FileSystem;
import alluxio.client.file.URIStatus;
import alluxio.concurrent.LockMode;
import alluxio.exception.PreconditionMessage;
import alluxio.exception.runtime.AlluxioRuntimeException;
import alluxio.exception.runtime.AlreadyExistsRuntimeException;
import alluxio.exception.runtime.FailedPreconditionRuntimeException;
import alluxio.exception.runtime.UnimplementedRuntimeException;
import alluxio.fuse.AlluxioFuseOpenUtils;
import alluxio.fuse.AlluxioFuseUtils;
import alluxio.fuse.auth.AuthPolicy;
import alluxio.fuse.lock.FuseReadWriteLockManager;
import alluxio.resource.CloseableResource;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import javax.annotation.concurrent.ThreadSafe;

/**
 * An implementation for {@link FuseFileStream} for sequential write only operations
 * against an Alluxio uri.
 */
@ThreadSafe
public class FuseFileOutStream implements FuseFileStream {
  private static final Logger LOG = LoggerFactory.getLogger(FuseFileOutStream.class);
  private static final int DEFAULT_BUFFER_SIZE = Constants.MB * 4;
  private final AuthPolicy mAuthPolicy;
  private final FileSystem mFileSystem;
  private final CloseableResource<Lock> mLockResource;
  private final AlluxioURI mURI;
  private final CreateFileStatus mFileStatus;

  private volatile boolean mClosed = false;
  private Optional<FileOutStream> mOutStream;

  /**
   * Creates a {@link FuseFileInOrOutStream}.
   *
   * @param fileSystem the Alluxio file system
   * @param authPolicy the Authentication policy
   * @param lockManager the lock manager
   * @param uri the alluxio uri
   * @param flags the fuse create/open flags
   * @param mode the filesystem mode, -1 if not set
   * @return a {@link FuseFileInOrOutStream}
   */
  public static FuseFileOutStream create(FileSystem fileSystem, AuthPolicy authPolicy,
      FuseReadWriteLockManager lockManager, AlluxioURI uri, int flags, long mode) {
    Preconditions.checkNotNull(fileSystem);
    Preconditions.checkNotNull(authPolicy);
    Preconditions.checkNotNull(lockManager);
    Preconditions.checkNotNull(uri);
    // Make sure file is not being read/written by current FUSE
    CloseableResource<Lock> lockResource = lockManager.tryLock(uri.toString(), LockMode.WRITE);

    try {
      // Make sure file is not being written by other clients outside current FUSE
      Optional<URIStatus> status = AlluxioFuseUtils.getPathStatus(fileSystem, uri);
      if (status.isPresent() && !status.get().isCompleted()) {
        status = AlluxioFuseUtils.waitForFileCompleted(fileSystem, uri);
        if (!status.isPresent()) {
          throw new UnimplementedRuntimeException(String.format(
              "Failed to create fuse file out stream for %s: cannot concurrently write same file",
              uri));
        }
      }
      if (mode == AlluxioFuseUtils.MODE_NOT_SET_VALUE && status.isPresent()) {
        mode = status.get().getMode();
      }
      long fileLen = status.map(URIStatus::getLength).orElse(0L);
      CreateFileStatus createFileStatus = CreateFileStatus.create(authPolicy, mode, fileLen);
      if (status.isPresent()) {
        if (AlluxioFuseOpenUtils.containsTruncate(flags) || fileLen == 0) {
          // support OPEN(O_WRONLY | O_RDONLY) existing file + O_TRUNC to write
          // support create empty file then open for write/read_write workload
          AlluxioFuseUtils.deletePath(fileSystem, uri);
          createFileStatus.setFileLength(0L);
          if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Open path %s with flag 0x%x for overwriting. "
                + "Alluxio deleted the old file and created a new file for writing", uri, flags));
          }
        } else {
          // Support open(O_WRONLY | O_RDWR flag) - truncate(0) - write() workflow
          return new FuseFileOutStream(fileSystem, authPolicy, uri, createFileStatus, lockResource,
              Optional.empty());
        }
      }
      return new FuseFileOutStream(fileSystem, authPolicy, uri,
          createFileStatus, lockResource,
          Optional.of(AlluxioFuseUtils.createFile(fileSystem, authPolicy, uri, createFileStatus)));
    } catch (Throwable t) {
      lockResource.close();
      throw t;
    }
  }

  private FuseFileOutStream(FileSystem fileSystem, AuthPolicy authPolicy,
      AlluxioURI uri, CreateFileStatus fileStatus, CloseableResource<Lock> lockResource,
      Optional<FileOutStream> outStream) {
    mFileSystem = Preconditions.checkNotNull(fileSystem);
    mAuthPolicy = Preconditions.checkNotNull(authPolicy);
    mFileStatus = Preconditions.checkNotNull(fileStatus);
    mURI = Preconditions.checkNotNull(uri);
    mLockResource = Preconditions.checkNotNull(lockResource);
    mOutStream = Preconditions.checkNotNull(outStream);
  }

  @Override
  public int read(ByteBuffer buf, long size, long offset) {
    throw new FailedPreconditionRuntimeException("Cannot read from write only stream");
  }

  @Override
  public synchronized void write(ByteBuffer buf, long size, long offset) {
    Preconditions.checkArgument(size >= 0 && offset >= 0 && size <= buf.capacity(),
        PreconditionMessage.ERR_BUFFER_STATE.toString(), buf.capacity(), offset, size);
    if (!mOutStream.isPresent()) {
      throw new AlreadyExistsRuntimeException(
          "Cannot overwrite/extending existing file without O_TRUNC flag or truncate(0) operation");
    }
    if (size == 0) {
      return;
    }
    int sz = (int) size;
    long bytesWritten = mOutStream.get().getBytesWritten();
    if (offset != bytesWritten && offset + sz > bytesWritten) {
      throw new UnimplementedRuntimeException(String.format("Only sequential write is supported. "
          + "Cannot write bytes of size %s to offset %s when %s bytes have written to path %s",
          size, offset, bytesWritten, mURI));
    }
    if (offset + sz <= bytesWritten) {
      LOG.warn("Skip writing to file {} offset={} size={} when {} bytes has written to file",
          mURI, offset, sz, bytesWritten);
      // To fulfill vim :wq
    }
    final byte[] dest = new byte[sz];
    buf.get(dest, 0, sz);
    try {
      mOutStream.get().write(dest);
    } catch (IOException e) {
      throw AlluxioRuntimeException.from(e);
    }
  }

  @Override
  public synchronized FileStatus getFileStatus() {
    if (mOutStream.isPresent()) {
      if (mOutStream.get().getBytesWritten() > mFileStatus.getFileLength()) {
        mFileStatus.setFileLength(mOutStream.get().getBytesWritten());
      }
    }
    return mFileStatus;
  }

  @Override
  public synchronized void flush() {
    if (!mOutStream.isPresent()) {
      return;
    }
    try {
      mOutStream.get().flush();
    } catch (IOException e) {
      throw AlluxioRuntimeException.from(e);
    }
  }

  @Override
  public synchronized void truncate(long size) {
    long currentSize = getFileStatus().getFileLength();
    if (size == currentSize) {
      return;
    }
    if (size == 0) {
      closeStreams();
      AlluxioFuseUtils.deletePath(mFileSystem, mURI);
      mOutStream = Optional.of(AlluxioFuseUtils
          .createFile(mFileSystem, mAuthPolicy, mURI, mFileStatus));
      mFileStatus.setFileLength(0);
      return;
    }
    if (mOutStream.isPresent() && size >= mOutStream.get().getBytesWritten()) {
      // support setting file length to a value bigger than current file length
      // but do not support opening an existing file and append on top.
      // e.g. support "create() -> sequential write
      // -> truncate(to larger value) -> sequential write"
      // do not support "file exist -> open(W or RW) -> truncate(to a larger value)"
      mFileStatus.setFileLength(size);
      return;
    }
    throw new UnimplementedRuntimeException(
        String.format("Cannot truncate file %s from size %s to size %s", mURI, currentSize, size));
  }

  @Override
  public synchronized void close() {
    if (mClosed) {
      return;
    }
    mClosed = true;
    try {
      closeStreams();
    } finally {
      mLockResource.close();
    }
  }

  @Override
  public boolean isClosed() {
    return mClosed;
  }

  private void closeStreams() {
    try {
      writeToFileLengthIfNeeded();
      if (mOutStream.isPresent()) {
        mOutStream.get().close();
      }
    } catch (IOException e) {
      throw AlluxioRuntimeException.from(e);
    }
  }

  /**
   * Fills zero bytes to file if file length is set to a value larger
   * than bytes written by truncate() operation.
   */
  private void writeToFileLengthIfNeeded() throws IOException {
    if (!mOutStream.isPresent()) {
      return;
    }
    long bytesWritten = mOutStream.get().getBytesWritten();
    if (bytesWritten >= mFileStatus.getFileLength()) {
      return;
    }
    long bytesGap = mFileStatus.getFileLength() - bytesWritten;
    final long originalBytesGap = bytesGap;
    int bufferSize = bytesGap >= DEFAULT_BUFFER_SIZE
        ? DEFAULT_BUFFER_SIZE : (int) bytesGap;
    byte[] buffer = new byte[bufferSize];
    Arrays.fill(buffer, (byte) 0);
    while (bytesGap > 0) {
      int bytesToWrite = bytesGap >= DEFAULT_BUFFER_SIZE
          ? DEFAULT_BUFFER_SIZE : (int) bytesGap;
      mOutStream.get().write(buffer, 0, bytesToWrite);
      bytesGap -= DEFAULT_BUFFER_SIZE;
    }
    LOG.debug("Filled {} zero bytes to file {} to fulfill the extended file length of {}",
        originalBytesGap, mURI, mFileStatus.getFileLength());
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }
}
