package org.apache.spark.ml.odkl.hyperopt

import java.util.Random
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import breeze.linalg.DenseVector
import org.apache.spark.ml.odkl._
import org.apache.spark.ml.param.shared.{HasMaxIter, HasSeed, HasTol}
import org.apache.spark.ml.param._
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.repro.ReproContext
import org.apache.spark.sql.{DataFrame, Dataset, Row, SQLContext}

import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

/**
  * Searches for optimal parameters using Bayesian approach. Important difference of this searcher compared to
  * other forked estimators is the need to get previous evaluation to know where to sample next params.
  */
class StochasticHyperopt[ModelIn <: ModelWithSummary[ModelIn]]
(
  nested: SummarizableEstimator[ModelIn],
  override val uid: String) extends ForkedEstimator[ModelIn, ConfigNumber, ModelIn](nested, uid) with HyperparametersOptimizer[ModelIn]
  with HasMaxIter with HasTol with HasSeed {

  def this(nested: SummarizableEstimator[ModelIn]) = this(nested, Identifiable.randomUID("stochasticHyperopt"))

  val paramDomains = new Param[Array[ParamDomainPair[_]]](this, "paramDomains",
  "Domains of the parameters to optimize. Used to map actual values to the [0,1] and back to interact with sampler.") {
    override def jsonEncode(value: Array[ParamDomainPair[_]]): String = value.map(x => s"${x.param.name} -> ${x.domain}").mkString(",")
  }

  val nanReplacement = new DoubleParam(this, "nanReplacement",
  "Value to use as evaluation result in case if model evaluation failed. Should be 'bad enougth' to force" +
    " sampler to avoid this region.")

  val searchMode = new Param[BayesianParamOptimizerFactory](this, "searchMode",
    "How to search for parameters. See BayesianParamOptimizer for supported modes. Default is random.") {
    override def jsonEncode(value: BayesianParamOptimizerFactory): String = value.toString

    override def jsonDecode(json: String): BayesianParamOptimizerFactory = super.jsonDecode(json)
  }

  val maxNoImproveIters = new IntParam(this, "maxNoImproveIters",
    "After having this many iterations without improvement search is considered converged.", (i: Int) => i > 0)

  val topKForTolerance = new IntParam(this, "topKForTolerance",
    "If difference between best and worse evaluations among top K models is less then tolerance algorithm" +
      " is considered converged. The K is set by this param.", (i: Int) => i > 0)

  val epsilonGreedy = new DoubleParam(this, "epsilonGreedy",
    "Probability to sample uniform vector instead of guided search. Used to avoid stucking in a local optimum",
    (v : Double) => v >= 0 && v <= 1)

  val priorsPath = new Param[String](this, "priorsPath",
    "Path to configurations to use for priors initialization. Typically this is a path " +
      "to the configurations summary block of the previously trained similar model.")

  val priorsContextFilter = new Param[String](this, "priorsContextFilter",
    "In case if models are trained in the scope of complex training DAG you might need " +
      "to specify filter for the relevant config, eg. \"type = '%1$s' AND class = '%2$s'\" when " +
      "trained in the type/class tree.")

  val priorsToSample = new IntParam(this, "priorsPercentage", "Using all the " +
    "available priors might significantly degrade the performance if landscape changed, thus it is recommended " +
    "to sample only limited amount of historical config as priors.", (i: Int) => i > 0)

  setDefault(
    searchMode -> BayesianParamOptimizer.RANDOM,
    seed -> System.nanoTime(),
    priorsToSample -> 20
  )

  def setParamDomains(pairs: ParamDomainPair[_]*) : this.type = set(paramDomains, pairs.toArray)

  def setMaxIter(iters: Int) : this.type = set(maxIter, iters)

  def setNanReplacement(value: Double) : this.type = set(nanReplacement, value)

  def setSearchMode(value: BayesianParamOptimizerFactory) : this.type = set(searchMode, value)

  def setTol(value: Double) : this.type = set(tol, value)
  def setMaxNoImproveIters(value: Int) : this.type = set(maxNoImproveIters, value)
  def setTopKForTolerance(value: Int) : this.type = set(topKForTolerance, value)

  def setEpsilonGreedy(value: Double) : this.type = set(epsilonGreedy, value)

  def setSeed(value: Long) : this.type = set(seed, value)

  def setPriorsPath(value: String) : this.type = set(priorsPath, value)

  def setPriorsContextFilter(value: String) : this.type = set(priorsContextFilter, value)

  def setPriorsToSample(value: Int) : this.type = set(priorsToSample, value)

  override def fit(dataset: Dataset[_]): ModelIn = {
    if (isDefined(pathForTempModels) && !isSet(seed)) {
      logWarning(s"Persisting models without seed set could lead to inefficient behavior during restoration process at $uid")
    }
    try {
      super.fit(dataset)
    } catch {
      case NonFatal(e) => logError(s"Exception while fitting at $uid: ${e.toString}")
        throw e
    }
  }

  override protected def failFast(key: ConfigNumber, triedIn: Try[ModelIn]): Try[ModelIn] = {
    if (triedIn.isFailure) {
      logError(s"Fitting at $uid failed for $key due to ${triedIn.failed.get}")
    }
    // Grid search can tollerate some invalid configs
    triedIn
  }


  override protected def getForkTags(partialData: (ConfigNumber, DataFrame)): Seq[(String, String)]
  = Seq("configuration" -> partialData._1.number.toString)

  override protected def diveToReproContext(partialData: (ConfigNumber, DataFrame), estimator: SummarizableEstimator[ModelIn]): Unit = {
    ReproContext.dive(getForkTags(partialData))
    ReproContext.logParamPairs(partialData._1.config.toSeq, Seq())
  }

  def createConfigDF(config: ParamMap): DataFrame = ???

  override def fitFork(estimator: SummarizableEstimator[ModelIn], wholeData: Dataset[_], partialData: (ConfigNumber, DataFrame)): (ConfigNumber, Try[ModelIn]) =
    try {
      // Copy the nested estimator
      logInfo(s"Trying configuration ${partialData._1.config}")
      val (config, model) = super.fitFork(estimator.copy(partialData._1.config), wholeData, partialData)

      if (isDefined(pathForTempModels)) {
        // In order to support restoration of the config tested we need to add configurations block to the model summary
        (config, model.map(x => {
          val rankedModels = Seq(extractParamsAndQuality(config.config, x))
          extractBestModel(wholeData.sqlContext, Seq(config.config -> model), rankedModels)
        }))
      } else {
        (config, model)
      }
    } catch {
      // Make sure errors in estimator copying are reported as model training failure.
      case NonFatal(e) => (partialData._1, failFast(partialData._1, Failure(e)))
    }

  override def copy(extra: ParamMap): StochasticHyperopt[ModelIn] = copyValues(
    new StochasticHyperopt[ModelIn](nested.copy(extra)), extra)

  /**
    * Loads and samples priors for the model.
    */
  private def loadPriors(sqlContext: SQLContext)(path: String) : Seq[(Double, ParamMap)] = {
    val priorConfigs = sqlContext.read.parquet(path)

    val priors = get(priorsContextFilter)
      .map(x => priorConfigs.where({
        val filter = x.format(getCurrentContext: _*)
        logInfo(s"Applying filter to priors $filter")
        filter
      }))
      .getOrElse(priorConfigs)
      .collect()
      .map(extractConfig)

    val result: Seq[(Double, ParamMap)] = scala.util.Random.shuffle(priors
      .iterator)
      .take($(priorsToSample))
      .toSeq

    logInfo(s"Loaded priors from path $path: ${result.mkString(", ")}")

    result
  }

  override protected def createForkSource(dataset: Dataset[_]): ForkSource[ModelIn, ConfigNumber, ModelIn]
  = {
    // Priors is available
    val priorConfigs : Option[Seq[(Double,ParamMap)]] = get(priorsPath).map(loadPriors(dataset.sqlContext))

    new ForkSource[ModelIn, ConfigNumber, ModelIn] {
      // The Bayesian optimizer to use
      val guide: BayesianParamOptimizer = $(searchMode).create(
        $(paramDomains).map(_.domain),
        $(seed),
        priorConfigs.map(_.map(x => x._1 -> paramsToVector(x._2))))

      // Generates unique numbers for samples
      val sequenceGenerator = new AtomicInteger()

      // Used to indicate that one of threads detected convergence. If one of the threads detected convergence,
      // others also exit even if their result changes the condition. This is important not to have part of the
      // threads working while others exited.
      val convergenceFlag = new AtomicBoolean(false)

      // Used to control epsilon-greedy policy
      val random = new Random($(seed))

      // Accumulates results of the evaluations.
      private val results = mutable.ArrayBuffer[(ConfigNumber, Try[ModelIn], Double)]()

      def vectorToParams(vector: DenseVector[Double]): ParamMap = {
        ParamMap($(paramDomains).zipWithIndex.map(x => x._1.toParamPair(vector(x._2))): _*)
      }

      def paramsToVector(params: ParamMap): DenseVector[Double] = {
        DenseVector($(paramDomains).map(x => x.toDouble(params)).toArray)
      }

      override def nextFork(): Option[(ConfigNumber, DataFrame)] = {
        val index = sequenceGenerator.getAndIncrement()
        if (!isConverged(index)) {
          guide.synchronized(
            Some(ConfigNumber(index, vectorToParams(guide.sampleInitialParams())) -> dataset.toDF))
        } else {
          None
        }
      }

      override def consumeFork(key: ConfigNumber, modelTry: Try[ModelIn]): Option[(ConfigNumber, DataFrame)] = {
        // Try to restore the actual configuration and evaluation from the model. If fail recovery is enabled
        // via pathForTempModels we can not rely on the parameters in the key and must check the summary info
        val tuple = modelTry.map(model => {
          if (isDefined(pathForTempModels)) {
            val row = model.summary(configurations).collect().head

            val (evaluation: Double, restoredParams: ParamMap) = extractConfig(row)

            (ConfigNumber(key.number, restoredParams), Try(model), evaluation)
          } else {
            val (params, extractedModel, evaluation) = extractParamsAndQuality(key.config, model)
            (ConfigNumber(key.number, params), Try(extractedModel), evaluation)
          }
        }).getOrElse((key, modelTry, Double.NaN))

        logInfo(s"At $uid got evaluation ${tuple._3} for confg ${tuple._1.config}")

        results.synchronized(results += tuple)

        val index = sequenceGenerator.getAndIncrement()
        if (!isConverged(index)) {
          // If search is not converged, sample next params from the guide
          guide.synchronized(
            Some({
              val sample = guide.sampleNextParams(paramsToVector(tuple._1.config), if (tuple._3.isNaN) $(nanReplacement) else tuple._3)
              ConfigNumber(index, vectorToParams(
                // Use sampled params if epsilon greedy not enabled otherwise challenge with random.
                if (!isDefined(epsilonGreedy) || random.nextDouble() > $(epsilonGreedy)) sample else guide.sampleInitialParams())) -> dataset.toDF
            }))
        } else {
          convergenceFlag.set(true)
          None
        }
      }

      override def createResult(): ModelIn = {
        val accumulated = results.result()

        extractBestModel(dataset.sqlContext,
          accumulated.filter(_._2.isFailure).map(x => x._1.config -> x._2),
          accumulated.filter(_._2.isSuccess).map(x => (x._1.config, x._2.get, x._3)).sortBy(-_._3))
      }

      private def isConverged(index: Int): Boolean = {
        if (convergenceFlag.get()) {
          logDebug("Convergence detected by another thread")
          return true
        }

        if (index > $(maxIter)) {
          logInfo(s"Search converged at $uid due to max iterations limit ${$(maxIter)}")
          return true
        }

        // Check for quality related convergence criterias if configured.
        if (isDefined(maxNoImproveIters) || isDefined(topKForTolerance)) {
          val evaluations = results.synchronized(results.view.map(x => x._1.number -> x._3).toArray).sortBy(x => -x._2)
          if (evaluations.nonEmpty) {
            val bestConfig = evaluations.head._1

            if (isDefined(maxNoImproveIters) && evaluations.view.map(_._1).max > bestConfig + $(maxNoImproveIters)) {
              logInfo(s"Search converged at $uid due to max iterations without improvement limit ${$(maxNoImproveIters)}, best config found at index $bestConfig")
              return true
            }

            if (isDefined(topKForTolerance) && evaluations.size >= $(topKForTolerance)) {
              val bestModels = evaluations.view.map(_._2).take($(topKForTolerance))
              if (bestModels.head - bestModels.last < $(tol)) {
                logInfo(s"Search converged at $uid due to too small improvement among top ${$(topKForTolerance)} models ${bestModels.head - bestModels.last}")
                return true
              }
            }
          }
        }

        false
      }
    }
  }

  /**
    * Extract model configuration from the configurations data frame row.
    */
  override protected def extractConfig(row: Row): (Double, ParamMap) = {
    val evaluation = row.getAs[Number]($(resultingMetricColumn)).doubleValue()

    val restoredParams = ParamMap($(paramDomains).map(x => {
      val columnName: String = get(paramNames).flatMap(_.get(x.param))
        .getOrElse({
          logWarning(s"Failed to find column name for param ${x.param}, restoration might not work properly")
          row.schema.fieldNames.find(_.endsWith(x.param.name)).get
        })

      x.toPairFromRow(row, columnName)
    }): _*)
    (evaluation, restoredParams)
  }

  /**
    * Not used due to custom forks source
    */
  override protected def createForks(dataset: Dataset[_]): Seq[(ConfigNumber, DataFrame)] = ???

  /**
    * Not used due to custom forks source
    */
  override protected def mergeModels(sqlContext: SQLContext, models: Seq[(ConfigNumber, Try[ModelIn])]): ModelIn = ???
}

case class ConfigNumber(number: Int, config: ParamMap) {
  override def toString: String = s"config_$number"
}