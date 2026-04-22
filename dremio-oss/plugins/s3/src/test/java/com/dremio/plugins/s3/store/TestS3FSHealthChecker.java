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
package com.dremio.plugins.s3.store;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.util.FSHealthChecker;
import com.dremio.io.file.Path;
import com.dremio.plugins.util.CloseableRef;
import com.dremio.plugins.util.CloseableResource;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;
import org.apache.hadoop.conf.Configuration;
import org.junit.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

/** Test the S3FSHealthChecker class. */
public class TestS3FSHealthChecker {
  private final String enableS3V2ClientFeatureFlagName =
      ExecConstants.ENABLE_S3_V2_CLIENT.getOptionName();

  @Test
  public void testGoodHealthCheckV1() throws Exception {
    Configuration config = new Configuration();
    config.set(enableS3V2ClientFeatureFlagName, "false");
    TestExtendedS3FSHealthChecker fs = new TestExtendedS3FSHealthChecker(config);
    AmazonS3 mockedS3Client = mock(AmazonS3.class);
    ListObjectsV2Result result = new ListObjectsV2Result();
    result.setKeyCount(1);
    when(mockedS3Client.listObjectsV2(
            any(com.amazonaws.services.s3.model.ListObjectsV2Request.class)))
        .thenReturn(result);
    fs.setCustomV1Client(mockedS3Client);

    Path p = Path.of("/bucket/prefix");
    fs.healthCheck(p, ImmutableSet.of());
  }

  @Test
  public void testGoodHealthCheck() throws Exception {
    TestExtendedS3FSHealthChecker fs = new TestExtendedS3FSHealthChecker(new Configuration());
    S3Client mockedS3Client = mock(S3Client.class);
    ListObjectsV2Response response = ListObjectsV2Response.builder().keyCount(1).build();
    when(mockedS3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);
    fs.setCustomV2Client(mockedS3Client);

    Path p = Path.of("/bucket/prefix");
    fs.healthCheck(p, ImmutableSet.of());
  }

  @Test(expected = IOException.class)
  public void testBadHealthCheckV1() throws IOException {
    Configuration config = new Configuration();
    config.set(enableS3V2ClientFeatureFlagName, "false");
    TestExtendedS3FSHealthChecker fs = new TestExtendedS3FSHealthChecker(config);
    AmazonS3 mockedS3Client = mock(AmazonS3.class);
    ListObjectsV2Result result = new ListObjectsV2Result();
    result.setKeyCount(0);
    when(mockedS3Client.listObjectsV2(
            any(com.amazonaws.services.s3.model.ListObjectsV2Request.class)))
        .thenReturn(result);
    fs.setCustomV1Client(mockedS3Client);

    Path p = Path.of("/bucket/prefix");
    fs.healthCheck(p, ImmutableSet.of());
  }

  @Test(expected = IOException.class)
  public void testBadHealthCheck() throws IOException {
    TestExtendedS3FSHealthChecker fs = new TestExtendedS3FSHealthChecker(new Configuration());
    S3Client mockedS3Client = mock(S3Client.class);
    ListObjectsV2Response response = ListObjectsV2Response.builder().keyCount(0).build();
    when(mockedS3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);
    fs.setCustomV2Client(mockedS3Client);

    Path p = Path.of("/bucket/prefix");
    fs.healthCheck(p, ImmutableSet.of());
  }

  @Test
  public void testS3FSHealthCheckerClassInstance() {
    String scheme = new String("dremioS3:///");
    Optional<FSHealthChecker> healthCheckerClass =
        FSHealthChecker.getInstance(Path.of("/bucket/test"), scheme, new Configuration());
    assertTrue(healthCheckerClass.isPresent());
    assertTrue(healthCheckerClass.get() instanceof S3FSHealthChecker);
  }

  private class TestExtendedS3FSHealthChecker extends S3FSHealthChecker {
    private AmazonS3 s3V1;
    private S3Client s3V2;

    public TestExtendedS3FSHealthChecker(Configuration fsConf) {
      super(fsConf);
    }

    void setCustomV1Client(AmazonS3 s3V1) {
      this.s3V1 = s3V1;
    }

    void setCustomV2Client(S3Client s3V2) {
      this.s3V2 = s3V2;
    }

    @Override
    protected CloseableResource<AmazonS3> getS3V1Client() throws IOException {
      return new CloseableResource(s3V1, s3V1 -> {});
    }

    @Override
    protected CloseableRef<S3Client> getS3V2Client() throws IOException {
      return new CloseableRef(s3V2);
    }
  }
}
