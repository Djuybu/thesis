/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.plugins.icebergcatalog.store;

import com.dremio.io.FSInputStream;
import com.dremio.io.FSOutputStream;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileBlockLocation;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.FilterFileSystem;
import com.dremio.io.file.Path;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.PosixFilePermission;
import java.security.AccessControlException;
import java.util.Set;
import java.util.function.Predicate;

// TODO: DX-99828 - Investigate Alternative
/**
 * A {@link FileSystem} wrapper that converts full paths (with scheme) to ContainerFileSystem
 * supported paths (/container/path/to/thing).
 */
public class IcebergCatalogFileSystem extends FilterFileSystem {
  private final FileSystem fs;

  public IcebergCatalogFileSystem(FileSystem fs) {
    super(fs);

    this.fs = fs;
  }

  @Override
  public FSInputStream open(Path path) throws FileNotFoundException, IOException {
    path = convertToContainerPath(path);
    return fs.open(path);
  }

  @Override
  public FSOutputStream create(Path path) throws FileNotFoundException, IOException {
    path = convertToContainerPath(path);
    return fs.create(path);
  }

  @Override
  public FSOutputStream create(Path path, boolean overwrite)
      throws FileAlreadyExistsException, IOException {
    path = convertToContainerPath(path);
    return fs.create(path, overwrite);
  }

  @Override
  public FileAttributes getFileAttributes(Path path) throws FileNotFoundException, IOException {
    path = convertToContainerPath(path);
    return fs.getFileAttributes(path);
  }

  @Override
  public void setPermission(Path path, Set<PosixFilePermission> permissions)
      throws FileNotFoundException, IOException {
    path = convertToContainerPath(path);
    fs.setPermission(path, permissions);
  }

  @Override
  public boolean mkdirs(Path path, Set<PosixFilePermission> permissions) throws IOException {
    path = convertToContainerPath(path);
    return fs.mkdirs(path, permissions);
  }

  @Override
  public boolean mkdirs(Path path) throws IOException {
    path = convertToContainerPath(path);
    return fs.mkdirs(path);
  }

  @Override
  public DirectoryStream<FileAttributes> list(Path path) throws FileNotFoundException, IOException {
    path = convertToContainerPath(path);
    return fs.list(path);
  }

  @Override
  public DirectoryStream<FileAttributes> list(Path path, Predicate<Path> filter)
      throws FileNotFoundException, IOException {
    path = convertToContainerPath(path);
    return fs.list(path, filter);
  }

  @Override
  public DirectoryStream<FileAttributes> listFiles(Path path, boolean recursive)
      throws FileNotFoundException, IOException {
    path = convertToContainerPath(path);
    return fs.listFiles(path, recursive);
  }

  @Override
  public DirectoryStream<FileAttributes> glob(Path pattern, Predicate<Path> filter)
      throws FileNotFoundException, IOException {
    pattern = convertToContainerPath(pattern);
    return fs.glob(pattern, filter);
  }

  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    src = convertToContainerPath(src);
    dst = convertToContainerPath(dst);
    return fs.rename(src, dst);
  }

  @Override
  public boolean delete(Path path, boolean recursive) throws IOException {
    path = convertToContainerPath(path);
    return fs.delete(path, recursive);
  }

  @Override
  public boolean exists(Path path) throws IOException {
    path = convertToContainerPath(path);
    return fs.exists(path);
  }

  @Override
  public boolean isDirectory(Path path) throws IOException {
    path = convertToContainerPath(path);
    return fs.isDirectory(path);
  }

  @Override
  public boolean isFile(Path path) throws IOException {
    path = convertToContainerPath(path);
    return fs.isFile(path);
  }

  @Override
  public Path makeQualified(Path path) {
    path = convertToContainerPath(path);
    return fs.makeQualified(path);
  }

  @Override
  public Iterable<FileBlockLocation> getFileBlockLocations(Path path, long start, long len)
      throws IOException {
    path = convertToContainerPath(path);
    return fs.getFileBlockLocations(path, start, len);
  }

  @Override
  public void access(Path path, Set<AccessMode> mode)
      throws AccessControlException, FileNotFoundException, IOException {
    path = convertToContainerPath(path);
    fs.access(path, mode);
  }

  @Override
  public boolean supportsPath(Path path) {
    path = convertToContainerPath(path);
    return fs.supportsPath(path);
  }

  @Override
  public long getDefaultBlockSize(Path path) {
    path = convertToContainerPath(path);
    return fs.getDefaultBlockSize(path);
  }

  private Path convertToContainerPath(Path path) {
    if (!fs.supportsPathsWithScheme()) {
      path = Path.of(Path.getContainerSpecificRelativePath(path));
    }

    return path;
  }
}
