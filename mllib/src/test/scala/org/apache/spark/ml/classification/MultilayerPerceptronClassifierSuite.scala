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

package org.apache.spark.ml.classification

import org.apache.spark.SparkFunSuite
import org.apache.spark.mllib.classification.LogisticRegressionSuite._
import org.apache.spark.mllib.classification.LogisticRegressionWithLBFGS
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.util.TestingUtils._
import org.apache.spark.sql.Row
/**
 * 这是一个基于 前馈神经网络 的分类器，它是一种在输入层与输出层之间含有一层或多层隐含结点的具有正向传播机制的神经网络模型，
 * 中间的节点使用sigmoid (logistic)函数，输出层的节点使用softmax函数
 */
class MultilayerPerceptronClassifierSuite extends SparkFunSuite with MLlibTestSparkContext {

  test("XOR function learning as binary classification problem with two outputs.") {
    val dataFrame = sqlContext.createDataFrame(Seq(
        (Vectors.dense(0.0, 0.0), 0.0),
        (Vectors.dense(0.0, 1.0), 1.0),
        (Vectors.dense(1.0, 0.0), 1.0),
        (Vectors.dense(1.0, 1.0), 0.0))
    ).toDF("features", "label")
    val layers = Array[Int](2, 5, 2)
    val trainer = new MultilayerPerceptronClassifier()
      .setLayers(layers)
      .setBlockSize(1)
      .setSeed(11L)
      .setMaxIter(100)
    val model = trainer.fit(dataFrame)
    val result = model.transform(dataFrame)
    val predictionAndLabels = result.select("prediction", "label").collect()
    predictionAndLabels.foreach { case Row(p: Double, l: Double) =>
      assert(p == l)
    }
  }

  // TODO: implement a more rigorous test
  test("3 class classification with 2 hidden layers") {
    val nPoints = 1000

    // The following weights are taken from OneVsRestSuite.scala
    // they represent 3-class iris dataset
    val weights = Array(
      -0.57997, 0.912083, -0.371077, -0.819866, 2.688191,
      -0.16624, -0.84355, -0.048509, -0.301789, 4.170682)

    val xMean = Array(5.843, 3.057, 3.758, 1.199)
    val xVariance = Array(0.6856, 0.1899, 3.116, 0.581)
    val rdd = sc.parallelize(generateMultinomialLogisticInput(
      weights, xMean, xVariance, true, nPoints, 42), 2)
    val dataFrame = sqlContext.createDataFrame(rdd).toDF("label", "features")
    val numClasses = 3
    val numIterations = 100
    val layers = Array[Int](4, 5, 4, numClasses)
    val trainer = new MultilayerPerceptronClassifier()
      .setLayers(layers)
      .setBlockSize(1)
      .setSeed(11L)
      .setMaxIter(numIterations)
    val model = trainer.fit(dataFrame)
    val mlpPredictionAndLabels = model.transform(dataFrame).select("prediction", "label")
      .map { case Row(p: Double, l: Double) => (p, l) }
    // train multinomial logistic regression
    val lr = new LogisticRegressionWithLBFGS()
      .setIntercept(true)
      .setNumClasses(numClasses)
    lr.optimizer.setRegParam(0.0)
      .setNumIterations(numIterations)
    val lrModel = lr.run(rdd)
    val lrPredictionAndLabels = lrModel.predict(rdd.map(_.features)).zip(rdd.map(_.label))
    // MLP's predictions should not differ a lot from LR's.
     //评估指标-多分类
    val lrMetrics = new MulticlassMetrics(lrPredictionAndLabels)
    val mlpMetrics = new MulticlassMetrics(mlpPredictionAndLabels)
    assert(mlpMetrics.confusionMatrix ~== lrMetrics.confusionMatrix absTol 100)
  }
}
