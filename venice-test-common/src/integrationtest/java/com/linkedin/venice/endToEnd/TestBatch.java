package com.linkedin.venice.endToEnd;

import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.JobStatusQueryResponse;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.hadoop.KafkaPushJob;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.schema.vson.VsonAvroSchemaAdapter;
import com.linkedin.venice.utils.Pair;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import java.io.File;
import java.io.IOException;
import java.util.*;

import java.util.Optional;
import java.util.function.Consumer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.util.Utf8;
import org.apache.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.*;

import static com.linkedin.venice.hadoop.KafkaPushJob.*;
import static com.linkedin.venice.utils.TestPushUtils.*; // TODO: remove this static import.

//TODO: write a H2VWrapper that can handle the whole flow

@Test(singleThreaded = true)
public class TestBatch {
  private static final Logger LOGGER = Logger.getLogger(TestBatch.class);
  private static final int TEST_TIMEOUT = 60 * Time.MS_PER_SECOND;
  private static final String STRING_SCHEMA = "\"string\"";

  private VeniceClusterWrapper veniceCluster;

  @BeforeClass
  public void setup() {
    veniceCluster = ServiceFactory.getVeniceCluster();
  }

  @AfterClass
  public void cleanup() {
    if (veniceCluster != null) {
      veniceCluster.close();
    }
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testVsonStoreWithSimpleRecords() throws Exception {
    testBatchStore(inputDir -> writeSimpleVsonFile(inputDir), props -> {
          props.setProperty(KEY_FIELD_PROP, "");
          props.setProperty(VALUE_FIELD_PROP, "");
        }, (avroClient, vsonClient) -> {
          for (int i = 0; i < 100; i++) {
            //we need to explicitly call toString() because avro actually returns Utf8
            Assert.assertEquals(avroClient.get(i).get().toString(), String.valueOf(i + 100));
            Assert.assertEquals(vsonClient.get(i).get(), String.valueOf(i + 100));
          }
        });
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testVsonStoreWithComplexRecords() throws Exception {
    testBatchStore(inputDir -> writeComplexVsonFile(inputDir), props -> {
      props.setProperty(KEY_FIELD_PROP, "");
      props.setProperty(VALUE_FIELD_PROP, "");
    }, (avroClient, vsonClient) -> {
      for (int i = 0; i < 100; i ++) {
        GenericData.Record avroObject = (GenericData.Record) avroClient.get(i).get();
        Map vsonObject = (Map) vsonClient.get(i).get();

        Assert.assertEquals(avroObject.get("member_id"), i + 100);
        Assert.assertEquals(vsonObject.get("member_id"), i + 100);

        //we are expecting the receive null field if i % 10 == 0
        Assert.assertEquals(avroObject.get("score"), i % 10 != 0 ? (float) i : null);
        Assert.assertEquals(vsonObject.get("score"), i % 10 != 0 ? (float) i : null);
      }
    });
  }

  /**
   * single byte (int8) and short (int16) are represented as Fixed in Avro.
   * This test case make sure Venice can write and read them properly.
   */
  @Test(timeOut = TEST_TIMEOUT)
  public void testVsonStoreCanProcessByteAndShort() throws Exception {
    testBatchStore(inputDir -> writeVsonByteAndShort(inputDir), props -> {
      props.setProperty(KEY_FIELD_PROP, "");
      props.setProperty(VALUE_FIELD_PROP, "");
    }, (avroClient, vsonClient) -> {
      for (int i = 0; i < 100; i ++) {
        Assert.assertEquals(vsonClient.get((byte) i).get(), (short) (i - 50));
      }
    });
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testVsonStoreWithSelectedField() throws Exception {
    testBatchStore(inputDir -> {
      Pair<Schema, Schema> schemas = writeComplexVsonFile(inputDir);
      //strip the value schema since this is selected filed
      Schema selectedValueSchema = VsonAvroSchemaAdapter.stripFromUnion(schemas.getSecond()).getField("score").schema();
      return new Pair<>(schemas.getFirst(), selectedValueSchema);
    }, props -> {
      props.setProperty(KEY_FIELD_PROP, "");
      props.setProperty(VALUE_FIELD_PROP, "score");
    }, (avroClient, vsonClient) -> {
      for (int i = 0; i < 100; i ++) {
        Assert.assertEquals(avroClient.get(i).get(), i % 10 != 0 ? (float) i : null);
        Assert.assertEquals(vsonClient.get(i).get(), i % 10 != 0 ? (float) i : null);
      }
    });
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testDuplicateKey() throws Exception {
    try {
      testStoreWithDuplicateKeys(false);
      Assert.fail();
    } catch (VeniceException e) {
      //push is expected to fail because of duplicate key
    }

    testStoreWithDuplicateKeys(true);
  }

  private void testStoreWithDuplicateKeys(boolean isDuplicateKeyAllowed) throws Exception {
    testBatchStore(inputDir -> {
      Schema recordSchema = writeSimpleAvroFileWithDuplicateKey(inputDir);
      return new Pair<>(recordSchema.getField("id").schema(),
                        recordSchema.getField("name").schema());
        }, props -> {
          if (isDuplicateKeyAllowed) {
            props.setProperty(ALLOW_DUPLICATE_KEY, "true");
          }
        },
        (avroClient, vsonClient) -> {});
  }

  @Test(timeOut =  TEST_TIMEOUT)
  public void testCompressingRecord() throws Exception {
    testBatchStore(inputDir -> {
      Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir);
      return new Pair<>(recordSchema.getField("id").schema(),
                        recordSchema.getField("name").schema());
    }, properties -> {}, (avroClient, vsonClient) -> {
      //test single get
      for (int i = 1; i <= 100; i ++) {
        Assert.assertEquals(avroClient.get(Integer.toString(i)).get().toString(), "test_name_" + i);
      }

      //test batch get
      for (int i = 0; i < 10; i ++) {
        Set<String> keys = new HashSet<>();
        for (int j = 1; j <= 10; j ++) {
          keys.add(Integer.toString(i * 10 + j));
        }

        Map<CharSequence, CharSequence> values = (Map<CharSequence, CharSequence>) avroClient.batchGet(keys).get();
        Assert.assertEquals(values.size(), 10);

        for (int j = 1; j <= 10; j ++) {
          Assert.assertEquals(values.get(Integer.toString(i * 10 + j)).toString(), "test_name_" + ((i * 10) + j));
        }
      }
    }, true);
  }

  private void testBatchStore(InputFileWriter inputFileWriter, Consumer<Properties> extraProps, H2VValidator dataValidator) throws Exception {
    testBatchStore(inputFileWriter, extraProps, dataValidator, false);
  }

  private void testBatchStore(InputFileWriter inputFileWriter, Consumer<Properties> extraProps, H2VValidator dataValidator, boolean isCompressed) throws Exception {
    testBatchStore(inputFileWriter, extraProps, dataValidator, isCompressed, Optional.empty());
  }

  private void testBatchStore(InputFileWriter inputFileWriter, Consumer<Properties> extraProps, H2VValidator dataValidator, boolean isCompressed, Optional<Boolean> chunkingEnabled) throws Exception {
    File inputDir = getTempDataDirectory();
    Pair<Schema, Schema> schemas = inputFileWriter.write(inputDir);
    String storeName = TestUtils.getUniqueString("store");

    String inputDirPath = "file://" + inputDir.getAbsolutePath();
    Properties props = defaultH2VProps(veniceCluster, inputDirPath, storeName);
    extraProps.accept(props);

    createStoreForJob(veniceCluster, schemas.getFirst().toString(), schemas.getSecond().toString(), props, isCompressed, chunkingEnabled);

    KafkaPushJob job = new KafkaPushJob("Test Batch push job", props);
    job.run();

    AvroGenericStoreClient avroClient = ClientFactory.getAndStartGenericAvroClient(ClientConfig.defaultGenericClientConfig(storeName)
        .setVeniceURL(veniceCluster.getRandomRouterURL()));
    AvroGenericStoreClient vsonClient = ClientFactory.getAndStartGenericAvroClient(ClientConfig.defaultVsonGenericClientConfig(storeName)
        .setVeniceURL(veniceCluster.getRandomRouterURL()));

    dataValidator.validate(avroClient, vsonClient);
  }

  private interface InputFileWriter {
    Pair<Schema, Schema> write(File inputDir) throws IOException;
  }

  private interface H2VValidator {
    void validate(AvroGenericStoreClient avroClient, AvroGenericStoreClient vsonClient) throws Exception;
  }

  @Test //(timeOut = TEST_TIMEOUT)
  public void testLargeValues() throws Exception {
    try {
      testStoreWithLargeValues(false);
      Assert.fail("Pushing large values with chunking disabled should fail.");
    } catch (VeniceException e) {
      //push is expected to fail because of large values
    }

    // TODO: Enable test once large value read path is coded!
    testStoreWithLargeValues(true);
  }

  private void testStoreWithLargeValues(boolean isChunkingAllowed) throws Exception {
    int maxValueSize = 3 * 1024 * 1024; // 3 MB apiece
    int numberOfRecords = 10;
    testBatchStore(inputDir -> {
          Schema recordSchema = writeSimpleAvroFileWithLargeValues(inputDir, numberOfRecords, maxValueSize);
          return new Pair<>(recordSchema.getField("id").schema(),
                            recordSchema.getField("name").schema());
        }, props -> {},
        (avroClient, vsonClient) -> {
          Set<String> keys = new HashSet(10);

          // Single gets
          for (int i = 0; i < numberOfRecords; i++) {
            int expectedSize = maxValueSize / numberOfRecords * (i + 1);
            String key = new Integer(i).toString();
            keys.add(key);
            char[] chars = new char[expectedSize];
            Arrays.fill(chars, Integer.toString(i).charAt(0));
            String expectedString = new String(chars);
            Utf8 expectedUtf8 = new Utf8(expectedString);

            LOGGER.info("About to query key: " + i);
            Utf8 returnedUtf8Value = (Utf8) avroClient.get(key).get();
            Assert.assertNotNull(returnedUtf8Value, "Avro client returned null value for key: " + key + ".");
            LOGGER.info("Received value of size: " + returnedUtf8Value.length() + " for key: " + key);
            Assert.assertEquals(returnedUtf8Value.toString().substring(0, 1), key, "Avro value does not begin with the expected prefix.");
            Assert.assertEquals(returnedUtf8Value.length(), expectedSize, "Avro value does not have the expected size.");
            Assert.assertEquals(returnedUtf8Value, expectedUtf8, "The entire large value should be filled with the same char: " + key);

            String jsonValue = (String) vsonClient.get(key).get();
            Assert.assertNotNull(jsonValue, "VSON client returned null value for key: " + key + ".");
            Assert.assertEquals(jsonValue.substring(0, 1), key, "VSON value does not begin with the expected prefix.");
            Assert.assertEquals(jsonValue.length(), expectedSize, "VSON value does not have the expected size.");
            Assert.assertEquals(jsonValue, expectedString, "The entire large value should be filled with the same char: " + key);
          }

          // Batch-get
          Map<String, Utf8> utf8Results = (Map<String, Utf8>) avroClient.batchGet(keys).get();
          Map<String, String> jsonResults = (Map<String, String>) vsonClient.batchGet(keys).get();
          for (String key: keys) {
            int i = Integer.parseInt(key);
            int expectedSize = maxValueSize / numberOfRecords * (i + 1);
            char[] chars = new char[expectedSize];
            Arrays.fill(chars, key.charAt(0));
            String expectedString = new String(chars);
            Utf8 expectedUtf8 = new Utf8(expectedString);

            Utf8 returnedUtf8Value = utf8Results.get(key);
            Assert.assertNotNull(returnedUtf8Value, "Avro client returned null value for key: " + key + ".");
            LOGGER.info("Received value of size: " + returnedUtf8Value.length() + " for key: " + key);
            Assert.assertEquals(returnedUtf8Value.toString().substring(0, 1), key, "Avro value does not begin with the expected prefix.");
            Assert.assertEquals(returnedUtf8Value.length(), expectedSize, "Avro value does not have the expected size.");
            Assert.assertEquals(returnedUtf8Value, expectedUtf8, "The entire large value should be filled with the same char: " + key);

            String jsonValue = jsonResults.get(key);
            Assert.assertNotNull(jsonValue, "VSON client returned null value for key: " + key + ".");
            Assert.assertEquals(jsonValue.substring(0, 1), key, "VSON value does not begin with the expected prefix.");
            Assert.assertEquals(jsonValue.length(), expectedSize, "VSON value does not have the expected size.");
            Assert.assertEquals(jsonValue, expectedString, "The entire large value should be filled with the same char: " + key);
          }
        },
        false,
        Optional.of(isChunkingAllowed));
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testRunMRJobAndPBNJ() throws Exception {
    testRunPushJobAndPBNJ(false);
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testRunMapOnlyJobAndPBNJ() throws Exception {
    testRunPushJobAndPBNJ(true);
  }

  private void testRunPushJobAndPBNJ(boolean mapOnly) throws Exception {
    Utils.thisIsLocalhost();

    File inputDir = getTempDataDirectory();
    Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir);
    String inputDirPath = "file:" + inputDir.getAbsolutePath();
    String storeName = TestUtils.getUniqueString("store");
    Properties props = defaultH2VProps(veniceCluster, inputDirPath, storeName);
    if (mapOnly) {
      props.setProperty(KafkaPushJob.VENICE_MAP_ONLY, "true");
    }
    props.setProperty(KafkaPushJob.PBNJ_ENABLE, "true");
    props.setProperty(KafkaPushJob.PBNJ_ROUTER_URL_PROP, veniceCluster.getRandomRouterURL());
    createStoreForJob(veniceCluster, recordSchema, props);

    KafkaPushJob job = new KafkaPushJob("Test push job", props);
    job.run();

    // Verify job properties
    Assert.assertEquals(job.getKafkaTopic(), Version.composeKafkaTopic(storeName, 1));
    Assert.assertEquals(job.getInputDirectory(), inputDirPath);
    String schema = "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"example.avro\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"age\",\"type\":\"int\"}]}";
    Assert.assertEquals(job.getFileSchemaString(), schema);
    Assert.assertEquals(job.getKeySchemaString(), STRING_SCHEMA);
    Assert.assertEquals(job.getValueSchemaString(), STRING_SCHEMA);
    Assert.assertEquals(job.getInputFileDataSize(), 3872);

    // Verify the data in Venice Store
    String routerUrl = veniceCluster.getRandomRouterURL();
    try(AvroGenericStoreClient<String, Object> client =
        ClientFactory.getAndStartGenericAvroClient(ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(routerUrl))) {
      for (int i = 1; i <= 100; ++i) {
        String expected = "test_name_" + i;
        String actual = client.get(Integer.toString(i)).get().toString(); /* client.get().get() returns a Utf8 object */
        Assert.assertEquals(actual, expected);
      }

      JobStatusQueryResponse jobStatus = ControllerClient.queryJobStatus(routerUrl, veniceCluster.getClusterName(), job.getKafkaTopic());
      Assert.assertEquals(jobStatus.getStatus(), ExecutionStatus.COMPLETED.toString(),
          "After job is complete, status should reflect that");
      // In this test we are allowing the progress to not reach the full capacity, but we still want to make sure
      // that most of the progress has completed
      Assert.assertTrue(jobStatus.getMessagesConsumed()*1.5 > jobStatus.getMessagesAvailable(),
          "Complete job should have progress");
    }
  }
}
