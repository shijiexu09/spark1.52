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

package org.apache.spark.sql.sources

import java.io.File

import com.google.common.io.Files
import org.apache.hadoop.fs.Path

import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.{execution, AnalysisException, SaveMode}
import org.apache.spark.sql.types._


class ParquetHadoopFsRelationSuite extends HadoopFsRelationTest {
  override val dataSourceName: String = "parquet"

  import sqlContext._
  import sqlContext.implicits._

  // Parquet does not play well with NullType.
  //Parquet与NullType不兼容。
  override protected def supportsDataType(dataType: DataType): Boolean = dataType match {
    case _: NullType => false
    case _: CalendarIntervalType => false
    case _ => true
  }
  //save（）/ load（） - 分区表 - 简单查询 - 数据中的分区列
  test("save()/load() - partitioned table - simple queries - partition columns in data") {
    withTempDir { file =>
      val basePath = new Path(file.getCanonicalPath)
      val fs = basePath.getFileSystem(SparkHadoopUtil.get.conf)
      val qualifiedBasePath = fs.makeQualified(basePath)

      for (p1 <- 1 to 2; p2 <- Seq("foo", "bar")) {
        val partitionDir = new Path(qualifiedBasePath, s"p1=$p1/p2=$p2")
        sparkContext
          .parallelize(for (i <- 1 to 3) yield (i, s"val_$i", p1))
          .toDF("a", "b", "p1")
          .write.parquet(partitionDir.toString)
      }

      val dataSchemaWithPartition =
        StructType(dataSchema.fields :+ StructField("p1", IntegerType, nullable = true))

      checkQueries(
        read.format(dataSourceName)
          .option("dataSchema", dataSchemaWithPartition.json)
          .load(file.getCanonicalPath))
    }
  }
  //临时目录应该被忽略
  test("SPARK-7868: _temporary directories should be ignored") {
    withTempPath { dir =>
      val df = Seq("a", "b", "c").zipWithIndex.toDF()

      df.write
        .format("parquet")
        .save(dir.getCanonicalPath)

      df.write
        .format("parquet")
        .save(s"${dir.getCanonicalPath}/_temporary")

      checkAnswer(read.format("parquet").load(dir.getCanonicalPath), df.collect())
    }
  }
  //当SaveMode不是SaveMode.Append时，避免扫描输出目录
  test("SPARK-8014: Avoid scanning output directory when SaveMode isn't SaveMode.Append") {
    withTempDir { dir =>
      val path = dir.getCanonicalPath
      val df = Seq(1 -> "a").toDF()

      // Creates an arbitrary file.  If this directory gets scanned, ParquetRelation2 will throw
      // since it's not a valid Parquet file.
      //创建一个任意文件。 如果该目录被扫描，ParquetRelation2将抛出
      //因为它不是一个有效的Parquet文件。
      val emptyFile = new File(path, "empty")
      Files.createParentDirs(emptyFile)
      Files.touch(emptyFile)

      // This shouldn't throw anything.
      //这不应该扔任何东西。
      df.write.format("parquet").mode(SaveMode.Ignore).save(path)

      // This should only complain that the destination directory already exists, rather than file
      // "empty" is not a Parquet file.
      //这应该只是抱怨目的地目录已经存在，而不是文件“empty”不是Parquet文件
      assert {
        intercept[AnalysisException] {
          df.write.format("parquet").mode(SaveMode.ErrorIfExists).save(path)
        }.getMessage.contains("already exists")
      }

      // This shouldn't throw anything. 这不应该扔任何东西。
      df.write.format("parquet").mode(SaveMode.Overwrite).save(path)
      checkAnswer(read.format("parquet").load(path), df)
    }
  }
  //避免从BaseWriterContainer.abortJob抛出NPE
  test("SPARK-8079: Avoid NPE thrown from BaseWriterContainer.abortJob") {
    withTempPath { dir =>
      intercept[AnalysisException] {
        // Parquet doesn't allow field names with spaces.  Here we are intentionally making an
        // exception thrown from the `ParquetRelation2.prepareForWriteJob()` method to trigger
        // the bug.  Please refer to spark-8079 for more details.
        // Parquet不允许带空格的字段名称。 在这里我们有意制作从“ParquetRelation2.prepareForWriteJob（）”方法抛出的异常触发
        // 错误。 更多详情请参考spark-8079。
        range(1, 10)
          .withColumnRenamed("id", "a b")
          .write
          .format("parquet")
          .save(dir.getCanonicalPath)
      }
    }
  }
  //Parquet数据源应该在附加的时候写入汇总文件
  test("SPARK-8604: Parquet data source should write summary file while doing appending") {
    withTempPath { dir =>
      val path = dir.getCanonicalPath
      val df = sqlContext.range(0, 5)
      df.write.mode(SaveMode.Overwrite).parquet(path)

      val summaryPath = new Path(path, "_metadata")
      val commonSummaryPath = new Path(path, "_common_metadata")

      val fs = summaryPath.getFileSystem(configuration)
      fs.delete(summaryPath, true)
      fs.delete(commonSummaryPath, true)

      df.write.mode(SaveMode.Append).parquet(path)
      checkAnswer(sqlContext.read.parquet(path), df.unionAll(df))

      assert(fs.exists(summaryPath))
      assert(fs.exists(commonSummaryPath))
    }
  }
  //投影和过滤器应保持实物计划
  test("SPARK-10334 Projections and filters should be kept in physical plan") {
    withTempPath { dir =>
      val path = dir.getCanonicalPath

      sqlContext.range(2).select('id as 'a, 'id as 'b).write.partitionBy("b").parquet(path)
      val df = sqlContext.read.parquet(path).filter('a === 0).select('b)
      val physicalPlan = df.queryExecution.executedPlan

      assert(physicalPlan.collect { case p: execution.Project => p }.length === 1)
      assert(physicalPlan.collect { case p: execution.Filter => p }.length === 1)
    }
  }
}
