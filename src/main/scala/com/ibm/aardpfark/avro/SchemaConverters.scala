package com.ibm.aardpfark.avro

import java.nio.ByteBuffer
import java.util.HashMap

import scala.collection.JavaConversions._

import org.apache.avro.Schema.Type._
import org.apache.avro.SchemaBuilder._
import org.apache.avro.generic.GenericData.Fixed
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.avro.{Schema, SchemaBuilder}

import org.apache.spark.sql.catalyst.expressions.GenericRow
import org.apache.spark.sql.types._
import org.apache.spark.sparktypes.SparkVectorUDT


/**
 * This object contains method that are used to convert sparkSQL schemas to avro schemas and vice
 * versa.
 *
 * ADAPTED FROM spark-avro: https://github.com/databricks/spark-avro, while adding support for
 * VectorUDT
 */
object SchemaConverters {

  class IncompatibleSchemaException(msg: String, ex: Throwable = null) extends Exception(msg, ex)

  case class SchemaType(dataType: DataType, nullable: Boolean)

  /**
   * This function takes an avro schema and returns a sql schema.
   */
  def toSqlType(avroSchema: Schema): SchemaType = {
    avroSchema.getType match {
      case INT => SchemaType(IntegerType, nullable = false)
      case STRING => SchemaType(StringType, nullable = false)
      case BOOLEAN => SchemaType(BooleanType, nullable = false)
      case BYTES => SchemaType(BinaryType, nullable = false)
      case DOUBLE => SchemaType(DoubleType, nullable = false)
      case FLOAT => SchemaType(FloatType, nullable = false)
      case LONG => SchemaType(LongType, nullable = false)
      case FIXED => SchemaType(BinaryType, nullable = false)
      case ENUM => SchemaType(StringType, nullable = false)

      case RECORD =>
        val fields = avroSchema.getFields.map { f =>
          val schemaType = toSqlType(f.schema())
          StructField(f.name, schemaType.dataType, schemaType.nullable)
        }

        SchemaType(StructType(fields), nullable = false)

      case ARRAY =>
        val schemaType = toSqlType(avroSchema.getElementType)
        SchemaType(
          ArrayType(schemaType.dataType, containsNull = schemaType.nullable),
          nullable = false)

      case MAP =>
        val schemaType = toSqlType(avroSchema.getValueType)
        SchemaType(
          MapType(StringType, schemaType.dataType, valueContainsNull = schemaType.nullable),
          nullable = false)

      case UNION =>
        if (avroSchema.getTypes.exists(_.getType == NULL)) {
          // In case of a union with null, eliminate it and make a recursive call
          val remainingUnionTypes = avroSchema.getTypes.filterNot(_.getType == NULL)
          if (remainingUnionTypes.size == 1) {
            toSqlType(remainingUnionTypes.get(0)).copy(nullable = true)
          } else {
            toSqlType(Schema.createUnion(remainingUnionTypes)).copy(nullable = true)
          }
        } else avroSchema.getTypes.map(_.getType) match {
          case Seq(t1) =>
            toSqlType(avroSchema.getTypes.get(0))
          case Seq(t1, t2) if Set(t1, t2) == Set(INT, LONG) =>
            SchemaType(LongType, nullable = false)
          case Seq(t1, t2) if Set(t1, t2) == Set(FLOAT, DOUBLE) =>
            SchemaType(DoubleType, nullable = false)
          case other => throw new IncompatibleSchemaException(
            s"This mix of union types is not supported (see README): $other")
        }

      case other => throw new IncompatibleSchemaException(s"Unsupported type $other")
    }
  }

  /**
   * This function converts sparkSQL StructType into avro schema. This method uses two other
   * converter methods in order to do the conversion.
   */
  def convertStructToAvro[T](
    structType: StructType,
    schemaBuilder: RecordBuilder[T],
    recordNamespace: String): T = {
    val fieldsAssembler: FieldAssembler[T] = schemaBuilder.fields()
    structType.fields.foreach { field =>
      val newField = fieldsAssembler.name(field.name).`type`()

      if (field.nullable) {
        convertFieldTypeToAvro(field.dataType, newField.nullable(), field.name, recordNamespace)
          .noDefault
      } else {
        convertFieldTypeToAvro(field.dataType, newField, field.name, recordNamespace)
          .noDefault
      }
    }
    fieldsAssembler.endRecord()
  }

  /**
   * Returns a converter function to convert row in avro format to GenericRow of catalyst.
   *
   * @param sourceAvroSchema Source schema before conversion inferred from avro file by passed in
   *                       by user.
   * @param targetSqlType Target catalyst sql type after the conversion.
   * @return returns a converter function to convert row in avro format to GenericRow of catalyst.
   */
  def createConverterToSQL(sourceAvroSchema: Schema, targetSqlType: DataType): AnyRef => AnyRef = {

    def createConverter(avroSchema: Schema,
      sqlType: DataType, path: List[String]): AnyRef => AnyRef = {
      val avroType = avroSchema.getType
      (sqlType, avroType) match {
        // Avro strings are in Utf8, so we have to call toString on them
        case (StringType, STRING) | (StringType, ENUM) =>
          (item: AnyRef) => if (item == null) null else item.toString
        // Byte arrays are reused by avro, so we have to make a copy of them.
        case (IntegerType, INT) | (BooleanType, BOOLEAN) | (DoubleType, DOUBLE) |
             (FloatType, FLOAT) | (LongType, LONG) =>
          identity
        case (BinaryType, FIXED) =>
          (item: AnyRef) =>
            if (item == null) {
              null
            } else {
              item.asInstanceOf[Fixed].bytes().clone()
            }
        case (BinaryType, BYTES) =>
          (item: AnyRef) =>
            if (item == null) {
              null
            } else {
              val byteBuffer = item.asInstanceOf[ByteBuffer]
              val bytes = new Array[Byte](byteBuffer.remaining)
              byteBuffer.get(bytes)
              bytes
            }

        case (struct: StructType, RECORD) =>
          val length = struct.fields.length
          val converters = new Array[AnyRef => AnyRef](length)
          val avroFieldIndexes = new Array[Int](length)
          var i = 0
          while (i < length) {
            val sqlField = struct.fields(i)
            val avroField = avroSchema.getField(sqlField.name)
            if (avroField != null) {
              val converter = createConverter(avroField.schema(), sqlField.dataType,
                path :+ sqlField.name)
              converters(i) = converter
              avroFieldIndexes(i) = avroField.pos()
            } else if (!sqlField.nullable) {
              throw new IncompatibleSchemaException(
                s"Cannot find non-nullable field ${sqlField.name} at path ${path.mkString(".")} " +
                  "in Avro schema\n" +
                  s"Source Avro schema: $sourceAvroSchema.\n" +
                  s"Target Catalyst type: $targetSqlType")
            }
            i += 1
          }

          (item: AnyRef) => {
            if (item == null) {
              null
            } else {
              val record = item.asInstanceOf[GenericRecord]

              val result = new Array[Any](length)
              var i = 0
              while (i < converters.length) {
                if (converters(i) != null) {
                  val converter = converters(i)
                  result(i) = converter(record.get(avroFieldIndexes(i)))
                }
                i += 1
              }
              new GenericRow(result)
            }
          }
        case (arrayType: ArrayType, ARRAY) =>
          val elementConverter = createConverter(avroSchema.getElementType, arrayType.elementType,
            path)
          val allowsNull = arrayType.containsNull
          (item: AnyRef) => {
            if (item == null) {
              null
            } else {
              val array = item.asInstanceOf[GenericData.Array[AnyRef]]

              array.map { element =>
                if (element == null && !allowsNull) {
                  throw new RuntimeException(s"Array value at path ${path.mkString(".")} is not " +
                    "allowed to be null")
                } else {
                  elementConverter(element)
                }
              }
            }
          }
        case (mapType: MapType, MAP) if mapType.keyType == StringType =>
          val valueConverter = createConverter(avroSchema.getValueType, mapType.valueType, path)
          val allowsNull = mapType.valueContainsNull
          (item: AnyRef) => {
            if (item == null) {
              null
            } else {
              item.asInstanceOf[HashMap[AnyRef, AnyRef]].map { x =>
                if (x._2 == null && !allowsNull) {
                  throw new RuntimeException(s"Map value at path ${path.mkString(".")} is not " +
                    "allowed to be null")
                } else {
                  (x._1.toString, valueConverter(x._2))
                }
              }.toMap
            }
          }
        case (sqlType, UNION) =>
          if (avroSchema.getTypes.exists(_.getType == NULL)) {
            val remainingUnionTypes = avroSchema.getTypes.filterNot(_.getType == NULL)
            if (remainingUnionTypes.size == 1) {
              createConverter(remainingUnionTypes.get(0), sqlType, path)
            } else {
              createConverter(Schema.createUnion(remainingUnionTypes), sqlType, path)
            }
          } else avroSchema.getTypes.map(_.getType) match {
            case Seq(t1) => createConverter(avroSchema.getTypes.get(0), sqlType, path)
            case Seq(a, b) if Set(a, b) == Set(INT, LONG) && sqlType == LongType =>
              (item: AnyRef) => {
                item match {
                  case null => null
                  case l: java.lang.Long => l
                  case i: java.lang.Integer => new java.lang.Long(i.longValue())
                }
              }
            case Seq(a, b) if Set(a, b) == Set(FLOAT, DOUBLE) && sqlType == DoubleType =>
              (item: AnyRef) => {
                item match {
                  case null => null
                  case d: java.lang.Double => d
                  case f: java.lang.Float => new java.lang.Double(f.doubleValue())
                }
              }
            case other => throw new IncompatibleSchemaException(
              s"Cannot convert Avro schema to catalyst type because schema at path " +
                s"${path.mkString(".")} is not compatible (avroType = $other, sqlType = $sqlType)" +
                s" or this mix of union types is not supported (see README):\n" +
                s"Source Avro schema: $sourceAvroSchema.\n" +
                s"Target Catalyst type: $targetSqlType")
          }
        case (left, right) =>
          throw new IncompatibleSchemaException(
            s"Cannot convert Avro schema to catalyst type because schema at path " +
              s"${path.mkString(".")} is not compatible (avroType = $left, sqlType = $right). \n" +
              s"Source Avro schema: $sourceAvroSchema.\n" +
              s"Target Catalyst type: $targetSqlType")
      }
    }
    createConverter(sourceAvroSchema, targetSqlType, List.empty[String])
  }

  /**
   * This function is used to convert some sparkSQL type to avro type. Note that this function won't
   * be used to construct fields of avro record (convertFieldTypeToAvro is used for that).
   */
  private def convertTypeToAvro[T](
    dataType: DataType,
    schemaBuilder: BaseTypeBuilder[T],
    structName: String,
    recordNamespace: String): T = {
    dataType match {
      case ByteType => schemaBuilder.intType()
      case ShortType => schemaBuilder.intType()
      case IntegerType => schemaBuilder.intType()
      case LongType => schemaBuilder.longType()
      case FloatType => schemaBuilder.floatType()
      case DoubleType => schemaBuilder.doubleType()
      case _: DecimalType => schemaBuilder.stringType()
      case StringType => schemaBuilder.stringType()
      case BinaryType => schemaBuilder.bytesType()
      case BooleanType => schemaBuilder.booleanType()
      case TimestampType => schemaBuilder.longType()

      case ArrayType(elementType, _) =>
        val builder = getSchemaBuilder(dataType.asInstanceOf[ArrayType].containsNull)
        val elementSchema = convertTypeToAvro(elementType, builder, structName, recordNamespace)
        schemaBuilder.array().items(elementSchema)

      case MapType(StringType, valueType, _) =>
        val builder = getSchemaBuilder(dataType.asInstanceOf[MapType].valueContainsNull)
        val valueSchema = convertTypeToAvro(valueType, builder, structName, recordNamespace)
        schemaBuilder.map().values(valueSchema)

      case structType: StructType =>
        convertStructToAvro(
          structType,
          schemaBuilder.record(structName).namespace(recordNamespace),
          recordNamespace)

      case other => throw new IncompatibleSchemaException(s"Unexpected type $dataType.")
    }
  }

  /**
   * This function is used to construct fields of the avro record, where schema of the field is
   * specified by avro representation of dataType. Since builders for record fields are different
   * from those for everything else, we have to use a separate method.
   */
  private def convertFieldTypeToAvro[T](
    dataType: DataType,
    newFieldBuilder: BaseFieldTypeBuilder[T],
    structName: String,
    recordNamespace: String): FieldDefault[T, _] = {
    dataType match {
      case ByteType => newFieldBuilder.intType()
      case ShortType => newFieldBuilder.intType()
      case IntegerType => newFieldBuilder.intType()
      case LongType => newFieldBuilder.longType()
      case FloatType => newFieldBuilder.floatType()
      case DoubleType => newFieldBuilder.doubleType()
      case _: DecimalType => newFieldBuilder.stringType()
      case StringType => newFieldBuilder.stringType()
      case BinaryType => newFieldBuilder.bytesType()
      case BooleanType => newFieldBuilder.booleanType()
      case TimestampType => newFieldBuilder.longType()

      case ArrayType(elementType, _) =>
        val builder = getSchemaBuilder(dataType.asInstanceOf[ArrayType].containsNull)
        val elementSchema = convertTypeToAvro(elementType, builder, structName, recordNamespace)
        newFieldBuilder.array().items(elementSchema)

      case MapType(StringType, valueType, _) =>
        val builder = getSchemaBuilder(dataType.asInstanceOf[MapType].valueContainsNull)
        val valueSchema = convertTypeToAvro(valueType, builder, structName, recordNamespace)
        newFieldBuilder.map().values(valueSchema)

      case structType: StructType =>
        convertStructToAvro(
          structType,
          newFieldBuilder.record(structName).namespace(recordNamespace),
          recordNamespace)

      case v: SparkVectorUDT =>
        val builder = getSchemaBuilder(false)
        val elementSchema = convertTypeToAvro(DoubleType, builder, structName, recordNamespace)
        newFieldBuilder.array().items(elementSchema)

      case other => throw new IncompatibleSchemaException(s"Unexpected type $dataType.")
    }
  }

  private def getSchemaBuilder(isNullable: Boolean): BaseTypeBuilder[Schema] = {
    if (isNullable) {
      SchemaBuilder.builder().nullable()
    } else {
      SchemaBuilder.builder()
    }
  }
}