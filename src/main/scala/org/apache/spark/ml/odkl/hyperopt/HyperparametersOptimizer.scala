package org.apache.spark.ml.odkl.hyperopt

import org.apache.spark.ml.odkl.{HasNumThreads, ModelWithSummary, SummarizableEstimator}
import org.apache.spark.ml.odkl.ModelWithSummary.Block
import org.apache.spark.ml.param._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SQLContext, functions}

import scala.util.Try

/**
  * Common trait to all the hyper-parameter optimizers.
  */
trait HyperparametersOptimizer[M <: ModelWithSummary[M]] extends SummarizableEstimator[M] with HasConfigurations with HasNumThreads {

  val paramNames: Param[Map[Param[_], String]] = new Param[Map[Param[_], String]](
    this, "paramsFriendlyNames", "Names of the parameters to use in column names to store configs"
  ) {
    override def jsonEncode(value: Map[Param[_], String]): String = ""
  }
  val metricsBlock = new Param[String](this, "metricsBlock", "Name of the block with metrics to get results from.")
  val metricsExpression = new Param[String](this, "metricsExpression",
    "Expression used to extract single metric value from the metrics table. __THIS__ shoud be used as a table alias.")


  setDefault(metricsBlock -> "metrics")

  def setParamNames(value: (Param[_], String)*): this.type = set(paramNames, value.toMap)

  def resolveParamName(param: Param[_]) : String =
    get(paramNames).flatMap(_.get(param)).getOrElse(param.name)

  def getMetricsBlock: String = $(metricsBlock)

  def setMetricsBlock(value: String): this.type = set(metricsBlock, value)

  def getMetricsExpression: String = $(metricsExpression)

  def setMetricsExpression(value: String): this.type = set(metricsExpression, value)

  /**
    * Extracts information of the resulting metrics from the trained model.
    */
  protected def extractParamsAndQuality(params: ParamMap, model: M): (ParamMap, M, Double) = {
    val metrics = model.summary.blocks(Block($(metricsBlock)))

    val tableName = model.uid + "_metrics"
    val query = $(metricsExpression).replaceAll("__THIS__", tableName)

    metrics.createOrReplaceTempView(tableName)

    val quality = metrics.sqlContext.sql(query).rdd.map(_.getAs[Number](0)).collect().map(_.doubleValue()).sum

    (params, model, quality)
  }

  /**
    * Given all the history of the optimization create the resulting model with the configurations
    * summary block.
    */
  protected def extractBestModel(sqlContext: SQLContext, failedModels: Seq[(ParamMap, Try[M])], rankedModels: Seq[(ParamMap, M, Double)]): M = {
    val configurationBlock: DataFrame = createConfigurationsBlock(sqlContext, failedModels, rankedModels)

    // Now get the best model and enrich its summary
    val bestModel = rankedModels.head._2

    val nestedBlocks: Map[Block, DataFrame] = bestModel.summary.blocks.keys.map(
      block => block -> rankedModels.zipWithIndex.map(
        x => x._1._2.summary(block).withColumn($(configurationIndexColumn), functions.lit(x._2))
      ).reduce(_ union _)).toMap ++ Map(configurations -> configurationBlock)


    bestModel.copy(nestedBlocks).setParent(this)
  }

  /**
    * Create summary block with investigated configurations.
    */
  protected def createConfigurationsBlock(sqlContext: SQLContext, failedModels: Seq[(ParamMap, Try[M])], rankedModels: Seq[(ParamMap, M, Double)]): DataFrame = {
    // Extract parameters to build config for
    val keys: Seq[Param[_]] = rankedModels.head._1.toSeq.map(_.param.asInstanceOf[Param[Any]]).sortBy(_.name)

    // Infer dataset schema
    val schema = StructType(
      Seq(
        StructField($(configurationIndexColumn), IntegerType),
        StructField($(resultingMetricColumn), DoubleType),
        StructField($(errorColumn), StringType)) ++
        keys.map(x => {
          val dataType = x match {
            case _: IntParam => IntegerType
            case _: DoubleParam => DoubleType
            case _: LongParam => LongType
            case _: BooleanParam => BooleanType
            case _: FloatParam => FloatType
            case _: StringArrayParam => ArrayType(StringType, true)
            case _: DoubleArrayParam => ArrayType(DoubleType, true)
            case _: IntArrayParam => ArrayType(IntegerType, true)
            case _ => StringType
          }

          StructField(get(paramNames).map(_.getOrElse(x, x.toString())).getOrElse(x.toString()), dataType, true)
        }))

    def extractParams(params: ParamMap) = {
      keys.map(key => params.get(key).map(value => key match {
        case _: IntParam | _: DoubleParam | _: LongParam | _: BooleanParam | _: FloatParam => value
        case _: StringArrayParam | _: DoubleArrayParam | _: IntArrayParam => value
        case _ => key.asInstanceOf[Param[Any]].jsonEncode(value)
      }).get)
    }

    // Construct resulting block with variable part of configuration
    val rows = rankedModels.zipWithIndex.map(x => {
      val index: Int = x._2
      val params: ParamMap = x._1._1
      val metric: Double = x._1._3

      Row.fromSeq(Seq[Any](index, metric, "") ++ extractParams(params))
    }) ++ failedModels.filter(_._2.isFailure).map(x => {
      val params = x._1
      val error = x._2.failed.get.toString

      Row.fromSeq(Seq[Any](Int.MaxValue, Double.NaN, error) ++ extractParams(params))
    })

    val configurationBlock = sqlContext.createDataFrame(
      sqlContext.sparkContext.parallelize(rows, 1),
      schema)
    configurationBlock
  }


  def extractConfig(model : M) : (Double, ParamMap) = {
    val row = model.summary(configurations).collect().head

    extractConfig(row)
  }

  /**
    * In order to support correct restoration from the temporary model storage and grouped optimization
    * we need a way to restore model configuration from its summary row of configurations block.
    */
  protected def extractConfig(row: Row): (Double, ParamMap)

  override def copy(extra: ParamMap): HyperparametersOptimizer[M]
}
