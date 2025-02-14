/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.protobuf

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.DynamicMessage

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.{InternalRow, NoopFilters}
import org.apache.spark.sql.catalyst.expressions.Cast.toSQLType
import org.apache.spark.sql.protobuf.utils.ProtobufUtils
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{IntegerType, StructType}

/**
 * Tests for [[ProtobufSerializer]] and [[ProtobufDeserializer]] with a more specific focus on
 * those classes.
 */
class ProtobufSerdeSuite extends SharedSparkSession with ProtobufTestBase {

  import ProtoSerdeSuite._
  import ProtoSerdeSuite.MatchType._

  private val testFileDescFile = testFile("serde_suite.desc", "protobuf/serde_suite.desc")
  private val testFileDesc = ProtobufUtils.readDescriptorFileContent(testFileDescFile)

  private val javaClassNamePrefix = "org.apache.spark.sql.protobuf.protos.SerdeSuiteProtos$"

  private val proto2DescFile = testFile("proto2_messages.desc", "protobuf/proto2_messages.desc")
  private val proto2Desc = ProtobufUtils.readDescriptorFileContent(proto2DescFile)

  test("Test basic conversion") {
    withFieldMatchType { fieldMatch =>
      val (top, nest) = fieldMatch match {
        case BY_NAME => ("foo", "bar")
      }
      val protoFile = ProtobufUtils.buildDescriptor(testFileDesc, "BasicMessage")

      val dynamicMessageFoo = DynamicMessage
        .newBuilder(protoFile.getFile.findMessageTypeByName("Foo"))
        .setField(protoFile.getFile.findMessageTypeByName("Foo").findFieldByName("bar"), 10902)
        .build()

      val dynamicMessage = DynamicMessage
        .newBuilder(protoFile)
        .setField(protoFile.findFieldByName("foo"), dynamicMessageFoo)
        .build()

      val serializer = Serializer.create(CATALYST_STRUCT, protoFile, fieldMatch)
      val deserializer = Deserializer.create(CATALYST_STRUCT, protoFile, fieldMatch)

      assert(
        serializer.serialize(deserializer.deserialize(dynamicMessage).get) === dynamicMessage)
    }
  }

  test("Optional fields can be dropped from input SQL schema for the serializer") {
    // This test verifies that optional fields can be missing from input Catalyst schema
    // while serializing rows to protobuf.

    val desc = ProtobufUtils.buildDescriptor(proto2Desc, "FoobarWithRequiredFieldBar")

    // Confirm desc contains optional field 'foo' and required field bar.
    assert(desc.getFields.size() == 2)
    assert(desc.findFieldByName("foo").isOptional)

    // Use catalyst type without optional "foo".
    val sqlType = structFromDDL("struct<bar int>")
    val serializer = new ProtobufSerializer(sqlType, desc, nullable = false) // Should work fine.

    // Should be able to deserialize a row.
    val protoMessage = serializer.serialize(InternalRow(22)).asInstanceOf[DynamicMessage]

    // Verify the descriptor and the value.
    assert(protoMessage.getDescriptorForType == desc)
    assert(protoMessage.getField(desc.findFieldByName("bar")).asInstanceOf[Int] == 22)
  }

  test("Fail to convert with field type mismatch") {
    val protoFile = ProtobufUtils.buildDescriptor(testFileDesc, "MissMatchTypeInRoot")
    withFieldMatchType { fieldMatch =>
      assertFailedConversionMessage(
        protoFile,
        Deserializer,
        fieldMatch,
        errorClass = "CANNOT_CONVERT_PROTOBUF_MESSAGE_TYPE_TO_SQL_TYPE",
        params = Map(
          "protobufType" -> "MissMatchTypeInRoot",
          "toType" -> toSQLType(CATALYST_STRUCT)))

      assertFailedConversionMessage(
        protoFile,
        Serializer,
        fieldMatch,
        errorClass = "UNABLE_TO_CONVERT_TO_PROTOBUF_MESSAGE_TYPE",
        params = Map(
          "protobufType" -> "MissMatchTypeInRoot",
          "toType" -> toSQLType(CATALYST_STRUCT)))
    }
  }

  test("Fail to convert with missing nested Protobuf fields for serializer") {
    val protoFile = ProtobufUtils.buildDescriptor(testFileDesc, "FieldMissingInProto")

    val nonnullCatalyst = new StructType()
      .add("foo", new StructType().add("bar", IntegerType, nullable = false))

    // serialize fails whether or not 'bar' is nullable
    assertFailedConversionMessage(
      protoFile,
      Serializer,
      BY_NAME,
      errorClass = "UNABLE_TO_CONVERT_TO_PROTOBUF_MESSAGE_TYPE",
      params = Map(
        "protobufType" -> "FieldMissingInProto",
        "toType" -> toSQLType(CATALYST_STRUCT)))

    assertFailedConversionMessage(protoFile,
      Serializer,
      BY_NAME,
      errorClass = "UNABLE_TO_CONVERT_TO_PROTOBUF_MESSAGE_TYPE",
      params = Map(
        "protobufType" -> "FieldMissingInProto",
        "toType" -> toSQLType(nonnullCatalyst)))
  }

  test("Fail to convert with deeply nested field type mismatch") {
    val protoFile = ProtobufUtils.buildDescriptorFromJavaClass(
      s"${javaClassNamePrefix}MissMatchTypeInDeepNested"
    )
    val catalyst = new StructType().add("top", CATALYST_STRUCT)

    withFieldMatchType { fieldMatch =>
      assertFailedConversionMessage(
        protoFile,
        Deserializer,
        fieldMatch,
        catalyst,
        errorClass = "CANNOT_CONVERT_PROTOBUF_MESSAGE_TYPE_TO_SQL_TYPE",
        params = Map(
          "protobufType" -> "MissMatchTypeInDeepNested",
          "toType" -> toSQLType(catalyst)))

      assertFailedConversionMessage(
        protoFile,
        Serializer,
        fieldMatch,
        catalyst,
        errorClass = "UNABLE_TO_CONVERT_TO_PROTOBUF_MESSAGE_TYPE",
        params = Map(
          "protobufType" -> "MissMatchTypeInDeepNested",
          "toType" -> toSQLType(catalyst)))
    }
  }

  test("Fail to convert with missing Catalyst fields") {
    val protoFile = ProtobufUtils.buildDescriptor(testFileDesc, "FieldMissingInSQLRoot")

    val foobarSQLType = structFromDDL("struct<foo string>") // "bar" is missing.

    assertFailedConversionMessage(
      ProtobufUtils.buildDescriptor(proto2Desc, "FoobarWithRequiredFieldBar"),
      Serializer,
      BY_NAME,
      catalystSchema = foobarSQLType,
      errorClass = "UNABLE_TO_CONVERT_TO_PROTOBUF_MESSAGE_TYPE",
      params = Map(
        "protobufType" -> "FoobarWithRequiredFieldBar",
        "toType" -> toSQLType(foobarSQLType)))

    /* deserializing should work regardless of whether the extra field is missing
     in SQL Schema or not */
    withFieldMatchType(Deserializer.create(CATALYST_STRUCT, protoFile, _))
    withFieldMatchType(Deserializer.create(CATALYST_STRUCT, protoFile, _))

    val protoNestedFile = ProtobufUtils
      .buildDescriptor(proto2Desc, "NestedFoobarWithRequiredFieldBar")

    val nestedFoobarSQLType = structFromDDL(
      "struct<nested_foobar: struct<foo string>>" // "bar" field is missing.
    )
    // serializing with extra fails if required field is missing in inner struct
    assertFailedConversionMessage(
      ProtobufUtils.buildDescriptor(proto2Desc, "NestedFoobarWithRequiredFieldBar"),
      Serializer,
      BY_NAME,
      catalystSchema = nestedFoobarSQLType,
      errorClass = "UNABLE_TO_CONVERT_TO_PROTOBUF_MESSAGE_TYPE",
      params = Map(
        "protobufType" -> "NestedFoobarWithRequiredFieldBar",
        "toType" -> toSQLType(nestedFoobarSQLType)))

    /* deserializing should work regardless of whether the extra field is missing
      in SQL Schema or not */
    withFieldMatchType(Deserializer.create(nestedFoobarSQLType, protoNestedFile, _))
  }

  test("raise cannot parse and construct protobuf descriptor error") {
    // passing serde_suite.proto instead serde_suite.desc
    var fileDescFile = testFile("serde_suite.proto", "protobuf/serde_suite.proto")

    val e1 = intercept[AnalysisException] {
      ProtobufUtils.buildDescriptor(
        ProtobufUtils.readDescriptorFileContent(fileDescFile),
        "SerdeBasicMessage"
      )
    }

    checkError(
      exception = e1,
      errorClass = "CANNOT_PARSE_PROTOBUF_DESCRIPTOR")

    fileDescFile =
      testFile("basicmessage_noimports.desc", "protobuf/basicmessage_noimports.desc")

    val e2 = intercept[AnalysisException] {
      ProtobufUtils.buildDescriptor(
        ProtobufUtils.readDescriptorFileContent(fileDescFile),
        "SerdeBasicMessage")
    }

    checkError(
      exception = e2,
      errorClass = "PROTOBUF_DEPENDENCY_NOT_FOUND",
      parameters = Map("dependencyName" -> "nestedenum.proto"))
  }

  /**
   * Attempt to convert `catalystSchema` to `protoSchema` (or vice-versa if `deserialize` is
   * true), assert that it fails, and assert that the _cause_ of the thrown exception has a
   * message matching `expectedCauseMessage`.
   */
  private def assertFailedConversionMessage(
      protoSchema: Descriptor,
      serdeFactory: SerdeFactory[_],
      fieldMatchType: MatchType,
      catalystSchema: StructType = CATALYST_STRUCT,
      errorClass: String,
      params: Map[String, String]): Unit = {

    val e = intercept[AnalysisException] {
      serdeFactory.create(catalystSchema, protoSchema, fieldMatchType)
    }

    val expectMsg = serdeFactory match {
      case Deserializer =>
        s"[CANNOT_CONVERT_PROTOBUF_MESSAGE_TYPE_TO_SQL_TYPE] Unable to convert" +
          s" ${protoSchema.getName} of Protobuf to SQL type ${toSQLType(catalystSchema)}."
      case Serializer =>
        s"[UNABLE_TO_CONVERT_TO_PROTOBUF_MESSAGE_TYPE] Unable to convert SQL type" +
          s" ${toSQLType(catalystSchema)} to Protobuf type ${protoSchema.getName}."
    }

    assert(e.getMessage === expectMsg)
    checkError(
      exception = e,
      errorClass = errorClass,
      parameters = params)
  }

  def withFieldMatchType(f: MatchType => Unit): Unit = {
    MatchType.values.foreach { fieldMatchType =>
      withClue(s"fieldMatchType == $fieldMatchType") {
        f(fieldMatchType)
      }
    }
  }
}

object ProtoSerdeSuite {

  val CATALYST_STRUCT =
    new StructType().add("foo", new StructType().add("bar", IntegerType))

  /**
   * Specifier for type of field matching to be used for easy creation of tests that do by-name
   * field matching.
   */
  object MatchType extends Enumeration {
    type MatchType = Value
    val BY_NAME = Value
  }

  import MatchType._

  /**
   * Specifier for type of serde to be used for easy creation of tests that do both serialization
   * and deserialization.
   */
  sealed trait SerdeFactory[T] {
    def create(sqlSchema: StructType, descriptor: Descriptor, fieldMatchType: MatchType): T
  }

  object Serializer extends SerdeFactory[ProtobufSerializer] {
    override def create(
        sql: StructType,
        descriptor: Descriptor,
        matchType: MatchType): ProtobufSerializer = new ProtobufSerializer(sql, descriptor, false)
  }

  object Deserializer extends SerdeFactory[ProtobufDeserializer] {
    override def create(
        sql: StructType,
        descriptor: Descriptor,
        matchType: MatchType): ProtobufDeserializer =
      new ProtobufDeserializer(descriptor, sql, new NoopFilters)
  }
}
