/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.client.file.options;

import tachyon.Constants;
import tachyon.annotation.PublicApi;
import tachyon.client.ClientContext;
import tachyon.client.TachyonStorageType;
import tachyon.client.UnderStorageType;
import tachyon.client.WriteType;
import tachyon.client.file.policy.FileWriteLocationPolicy;
import tachyon.client.file.policy.FileWriteLocationPolicyOptions;
import tachyon.conf.TachyonConf;

@PublicApi
public final class OutStreamOptions {
  public static class Builder implements OptionsBuilder<OutStreamOptions> {
    private long mBlockSizeBytes;
    private String mHostname;
    private TachyonStorageType mTachyonStorageType;
    private long mTTL;
    private UnderStorageType mUnderStorageType;
    private FileWriteLocationPolicyOptions mLocationPolicyOptions;
    private Class<? extends FileWriteLocationPolicy> mLocationPolicyClass;

    /**
     * Creates a new builder for {@link OutStreamOptions}.
     */
    public Builder() {
      this(ClientContext.getConf());
    }

    /**
     * Creates a new builder for {@link OutStreamOptions}.
     *
     * @param conf a Tachyon configuration
     */
    public Builder(TachyonConf conf) {
      mBlockSizeBytes = conf.getBytes(Constants.USER_BLOCK_SIZE_BYTES_DEFAULT);
      mHostname = null;
      WriteType defaultWriteType =
          conf.getEnum(Constants.USER_FILE_WRITE_TYPE_DEFAULT, WriteType.class);
      mTachyonStorageType = defaultWriteType.getTachyonStorageType();
      mUnderStorageType = defaultWriteType.getUnderStorageType();
      mTTL = Constants.NO_TTL;
      mLocationPolicyClass = null;
      mLocationPolicyOptions = null;
    }

    /**
     * @param blockSizeBytes the block size to use
     * @return the builder
     */
    public Builder setBlockSizeBytes(long blockSizeBytes) {
      mBlockSizeBytes = blockSizeBytes;
      return this;
    }

    /**
     * @param hostname the hostname to use
     * @return the builder
     */
    public Builder setHostname(String hostname) {
      mHostname = hostname;
      return this;
    }

    /**
     * This is an advanced API, use {@link Builder#setWriteType(WriteType)} when possible.
     *
     * @param tachyonStorageType the Tachyon storage type to use
     * @return the builder
     */
    public Builder setTachyonStorageType(TachyonStorageType tachyonStorageType) {
      mTachyonStorageType = tachyonStorageType;
      return this;
    }

    /**
     * This is an advanced API, use {@link Builder#setWriteType(WriteType)} when possible.
     *
     * @param underStorageType the under storage type to use
     * @return the builder
     */
    public Builder setUnderStorageType(UnderStorageType underStorageType) {
      mUnderStorageType = underStorageType;
      return this;
    }

    /**
     * @param ttl the TTL (time to live) value to use; it identifies duration (in milliseconds) the
     *        created file should be kept around before it is automatically deleted, no matter
     *        whether the file is pinned
     * @return the builder
     */
    public Builder setTTL(long ttl) {
      mTTL = ttl;
      return this;
    }

    /**
     * @param writeType the {@link tachyon.client.WriteType} to use for this operation. This will
     *                  override both the TachyonStorageType and UnderStorageType.
     * @return the builder
     */
    public Builder setWriteType(WriteType writeType) {
      mTachyonStorageType = writeType.getTachyonStorageType();
      mUnderStorageType = writeType.getUnderStorageType();
      return this;
    }

    /**
     * @param locationPolicyClass the {@link FileWriteLocationPolicy} to use for this operation.
     * @return the builder
     */
    public Builder setLocationPolicyClass(
        Class<? extends FileWriteLocationPolicy> locationPolicyClass) {
      mLocationPolicyClass = locationPolicyClass;
      return this;
    }

    /**
     * @param locationPolicyOptions the {@link FileWriteLocationPolicyOptions} to use for this
     *        operation. This will configure the {@link FileWriteLocationPolicy}
     * @return the builder
     */
    public Builder setLocationPolicyOptions(FileWriteLocationPolicyOptions locationPolicyOptions) {
      mLocationPolicyOptions = locationPolicyOptions;
      return this;
    }

    /**
     * Builds a new instance of {@link OutStreamOptions}.
     *
     * @return a {@link OutStreamOptions} instance
     */
    @Override
    public OutStreamOptions build() {
      return new OutStreamOptions(this);
    }
  }

  private final long mBlockSizeBytes;
  private final String mHostname;
  private final TachyonStorageType mTachyonStorageType;
  private final UnderStorageType mUnderStorageType;
  private final long mTTL;
  private FileWriteLocationPolicyOptions mLocationPolicyOptions;
  private Class<? extends FileWriteLocationPolicy> mLocationPolicyClass;

  /**
   * @return the default {@link OutStreamOptions}
   */
  public static OutStreamOptions defaults() {
    return new Builder().build();
  }

  private OutStreamOptions(OutStreamOptions.Builder builder) {
    mBlockSizeBytes = builder.mBlockSizeBytes;
    mHostname = builder.mHostname;
    mTachyonStorageType = builder.mTachyonStorageType;
    mTTL = builder.mTTL;
    mUnderStorageType = builder.mUnderStorageType;
    mLocationPolicyClass = builder.mLocationPolicyClass;
    mLocationPolicyOptions = builder.mLocationPolicyOptions;
  }

  /**
   * @return the block size
   */
  public long getBlockSizeBytes() {
    return mBlockSizeBytes;
  }

  /**
   * @return the hostname
   */
  public String getHostname() {
    return mHostname;
  }

  /**
   * @return the Tachyon storage type
   */
  public TachyonStorageType getTachyonStorageType() {
    return mTachyonStorageType;
  }

  /**
   * @return the TTL (time to live) value; it identifies duration (in milliseconds) the created file
   *         should be kept around before it is automatically deleted
   */
  public long getTTL() {
    return mTTL;
  }

  /**
   * @return the under storage type
   */
  public UnderStorageType getUnderStorageType() {
    return mUnderStorageType;
  }

  /**
   * @return the class of the location policy
   */
  public Class<? extends FileWriteLocationPolicy> getLocationPolicyClass() {
    return mLocationPolicyClass;
  }

  /**
   * @return the options for configuring the location policy
   */
  public FileWriteLocationPolicyOptions getLocationPolicyOptions() {
    return mLocationPolicyOptions;
  }

  /**
   * @return the name : value pairs for all the fields
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("OutStreamOptions(");
    sb.append(super.toString()).append(", BlockSizeBytes: ").append(mBlockSizeBytes);
    sb.append(", Hostname: ").append(mHostname);
    sb.append(", TachyonStorageType: ").append(mTachyonStorageType.toString());
    sb.append(", UnderStorageType: ").append(mUnderStorageType.toString());
    sb.append(", TTL: ").append(mTTL);
    sb.append(", LocationPolicyClass").append(mLocationPolicyClass.toString());
    sb.append(", LocationPolicyOptions").append(mLocationPolicyOptions.toString());
    sb.append(")");
    return sb.toString();
  }
}
