package org.apache.spark.ml.odkl

import org.apache.spark.annotation.Since
import org.apache.spark.internal.Logging
import org.apache.spark.ml._
import org.apache.spark.ml.attribute.NominalAttribute
import org.apache.spark.ml.feature.Bucketizer
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.ml.util._
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.types.StructType
import org.json4s.JValue
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

// Parvda-ML Patch: Keep columns with few splits

/**
  * Params for [[QuantileDiscretizer]].
  */
trait QuantileDiscretizerBase extends Params
  with HasHandleInvalid with HasInputCol with HasOutputCol {

  /**
    * Number of buckets (quantiles, or categories) into which data points are grouped. Must
    * be greater than or equal to 2.
    *
    * See also [[handleInvalid]], which can optionally create an additional bucket for NaN values.
    *
    * default: 2
    * @group param
    */
  val numBuckets = new IntParam(this, "numBuckets", "Number of buckets (quantiles, or " +
    "categories) into which data points are grouped. Must be >= 2.",
    ParamValidators.gtEq(2))
  setDefault(numBuckets -> 2)

  /** @group getParam */
  def getNumBuckets: Int = getOrDefault(numBuckets)

  /**
    * Array of number of buckets (quantiles, or categories) into which data points are grouped.
    * Each value must be greater than or equal to 2
    *
    * See also [[handleInvalid]], which can optionally create an additional bucket for NaN values.
    *
    * @group param
    */
  val numBucketsArray = new IntArrayParam(this, "numBucketsArray", "Array of number of buckets " +
    "(quantiles, or categories) into which data points are grouped. This is for multiple " +
    "columns input. If transforming multiple columns and numBucketsArray is not set, but " +
    "numBuckets is set, then numBuckets will be applied across all columns.",
    (arrayOfNumBuckets: Array[Int]) => arrayOfNumBuckets.forall(ParamValidators.gtEq(2)))

  /** @group getParam */
  def getNumBucketsArray: Array[Int] = $(numBucketsArray)

  /**
    * Relative error (see documentation for
    * `org.apache.spark.sql.DataFrameStatFunctions.approxQuantile` for description)
    * Must be in the range [0, 1].
    * Note that in multiple columns case, relative error is applied to all columns.
    * default: 0.001
    * @group param
    */
  val relativeError = new DoubleParam(this, "relativeError", "The relative target precision " +
    "for the approximate quantile algorithm used to generate buckets. " +
    "Must be in the range [0, 1].", ParamValidators.inRange(0.0, 1.0))
  setDefault(relativeError -> 0.001)

  /** @group getParam */
  def getRelativeError: Double = getOrDefault(relativeError)

  /**
    * Param for how to handle invalid entries. Options are 'skip' (filter out rows with
    * invalid values), 'error' (throw an error), or 'keep' (keep invalid values in a special
    * additional bucket). Note that in the multiple columns case, the invalid handling is applied
    * to all columns. That said for 'error' it will throw an error if any invalids are found in
    * any column, for 'skip' it will skip rows with any invalids in any columns, etc.
    * Default: "error"
    * @group param
    */
  @Since("2.1.0")
  override val handleInvalid: Param[String] = new Param[String](this, "handleInvalid",
    "how to handle invalid entries. Options are skip (filter out rows with invalid values), " +
      "error (throw an error), or keep (keep invalid values in a special additional bucket).",
    ParamValidators.inArray(Array("skip", "error", "keep")))
  setDefault(handleInvalid, "error")

}

/**
  * `QuantileDiscretizer` takes a column with continuous features and outputs a column with binned
  * categorical features. The number of bins can be set using the `numBuckets` parameter. It is
  * possible that the number of buckets used will be smaller than this value, for example, if there
  * are too few distinct values of the input to create enough distinct quantiles.
  * Since 2.3.0, `QuantileDiscretizer` can map multiple columns at once by setting the `inputCols`
  * parameter. If both of the `inputCol` and `inputCols` parameters are set, an Exception will be
  * thrown. To specify the number of buckets for each column, the `numBucketsArray` parameter can
  * be set, or if the number of buckets should be the same across columns, `numBuckets` can be
  * set as a convenience.
  *
  * NaN handling:
  * null and NaN values will be ignored from the column during `QuantileDiscretizer` fitting. This
  * will produce a `Bucketizer` model for making predictions. During the transformation,
  * `Bucketizer` will raise an error when it finds NaN values in the dataset, but the user can
  * also choose to either keep or remove NaN values within the dataset by setting `handleInvalid`.
  * If the user chooses to keep NaN values, they will be handled specially and placed into their own
  * bucket, for example, if 4 buckets are used, then non-NaN data will be put into buckets[0-3],
  * but NaNs will be counted in a special bucket[4].
  *
  * Algorithm: The bin ranges are chosen using an approximate algorithm (see the documentation for
  * `org.apache.spark.sql.DataFrameStatFunctions.approxQuantile`
  * for a detailed description). The precision of the approximation can be controlled with the
  * `relativeError` parameter. The lower and upper bin bounds will be `-Infinity` and `+Infinity`,
  * covering all real values.
  */
@Since("1.6.0")
final class QuantileDiscretizer @Since("1.6.0") (@Since("1.6.0") override val uid: String)
  extends Estimator[Bucketizer] with QuantileDiscretizerBase with DefaultParamsWritable
    with HasInputCols with HasOutputCols {

  @Since("1.6.0")
  def this() = this(Identifiable.randomUID("quantileDiscretizer"))

  /** @group setParam */
  @Since("2.0.0")
  def setRelativeError(value: Double): this.type = set(relativeError, value)

  /** @group setParam */
  @Since("1.6.0")
  def setNumBuckets(value: Int): this.type = set(numBuckets, value)

  /** @group setParam */
  @Since("1.6.0")
  def setInputCol(value: String): this.type = set(inputCol, value)

  /** @group setParam */
  @Since("1.6.0")
  def setOutputCol(value: String): this.type = set(outputCol, value)

  /** @group setParam */
  @Since("2.1.0")
  def setHandleInvalid(value: String): this.type = set(handleInvalid, value)

  /** @group setParam */
  @Since("2.3.0")
  def setNumBucketsArray(value: Array[Int]): this.type = set(numBucketsArray, value)

  /** @group setParam */
  @Since("2.3.0")
  def setInputCols(value: Array[String]): this.type = set(inputCols, value)

  /** @group setParam */
  @Since("2.3.0")
  def setOutputCols(value: Array[String]): this.type = set(outputCols, value)

  def getInOutCols: (Array[String], Array[String]) = {
    require((isSet(inputCol) && isSet(outputCol) && !isSet(inputCols) && !isSet(outputCols)) ||
      (!isSet(inputCol) && !isSet(outputCol) && isSet(inputCols) && isSet(outputCols)),
      "QuantileDiscretizer only supports setting either inputCol/outputCol or" +
        "inputCols/outputCols."
    )

    if (isSet(inputCol)) {
      (Array($(inputCol)), Array($(outputCol)))
    } else {
      require($(inputCols).length == $(outputCols).length,
        "inputCols number do not match outputCols")
      ($(inputCols), $(outputCols))
    }
  }

  @Since("1.6.0")
  override def transformSchema(schema: StructType): StructType = {
    val (inputColNames, outputColNames) = getInOutCols
    val existingFields = schema.fields
    var outputFields = existingFields
    inputColNames.zip(outputColNames).foreach { case (inputColName, outputColName) =>
      SchemaUtils.checkNumericType(schema, inputColName)
      require(existingFields.forall(_.name != outputColName),
        s"Output column ${outputColName} already exists.")
      val attr = NominalAttribute.defaultAttr.withName(outputColName)
      outputFields :+= attr.toStructField()
    }
    StructType(outputFields)
  }

  @Since("2.0.0")
  override def fit(dataset: Dataset[_]): Bucketizer = {
    transformSchema(dataset.schema, logging = true)
    val bucketizer = new Bucketizer(uid).setHandleInvalid($(handleInvalid))
    if (isSet(inputCols)) {
      val splitsArray = if (isSet(numBucketsArray)) {
        val probArrayPerCol = $(numBucketsArray).map { numOfBuckets =>
          (0.0 to 1.0 by 1.0 / numOfBuckets).toArray
        }

        val probabilityArray = probArrayPerCol.flatten.sorted.distinct
        val splitsArrayRaw = dataset.stat.approxQuantile($(inputCols),
          probabilityArray, $(relativeError))

        splitsArrayRaw.zip(probArrayPerCol).map { case (splits, probs) =>
          val probSet = probs.toSet
          val idxSet = probabilityArray.zipWithIndex.collect {
            case (p, idx) if probSet(p) =>
              idx
          }.toSet
          splits.zipWithIndex.collect {
            case (s, idx) if idxSet(idx) =>
              s
          }
        }
      } else {
        dataset.stat.approxQuantile($(inputCols),
          (0.0 to 1.0 by 1.0 / $(numBuckets)).toArray, $(relativeError))
      }
      bucketizer.setSplitsArray(splitsArray.map(getDistinctSplits))
    } else {
      val splits = dataset.stat.approxQuantile($(inputCol),
        (0.0 to 1.0 by 1.0 / $(numBuckets)).toArray, $(relativeError))
      bucketizer.setSplits(getDistinctSplits(splits))
    }
    copyValues(bucketizer.setParent(this))
  }

  private def getDistinctSplits(splits: Array[Double]): Array[Double] = {

    if (splits.length < 3) {
      // Parvda-ML Patch: Keep columns with few splits
      if (splits.isEmpty) {
        logWarning(s"Column with no valid splits found, extending with split around 0")
        Array(Double.NegativeInfinity, 0.0, Double.PositiveInfinity)
      } else {
        logWarning(s"Column with few splits $splits found, extending with infinity edges")
        Array(Double.NegativeInfinity) ++ splits ++ Array(Double.PositiveInfinity)
      }
    } else {
      splits(0) = Double.NegativeInfinity
      splits(splits.length - 1) = Double.PositiveInfinity
      val distinctSplits = splits.distinct
      if (splits.length != distinctSplits.length) {
        log.warn(s"Some quantiles were identical. Bucketing to ${distinctSplits.length - 1}" +
          s" buckets as a result.")
      }
      distinctSplits.sorted
    }
  }

  @Since("1.6.0")
  override def copy(extra: ParamMap): QuantileDiscretizer = defaultCopy(extra)

  override def write: MLWriter = new QuantileDiscretizer.QuantileDiscretizerWriter(this)
}

@Since("1.6.0")
object QuantileDiscretizer extends DefaultParamsReadable[QuantileDiscretizer] with Logging {

  private[QuantileDiscretizer]
  class QuantileDiscretizerWriter(instance: QuantileDiscretizer) extends MLWriter {

    override protected def saveImpl(path: String): Unit = {
      // SPARK-23377: The default params will be saved and loaded as user-supplied params.
      // Once `inputCols` is set, the default value of `outputCol` param causes the error
      // when checking exclusive params. As a temporary to fix it, we skip the default value
      // of `outputCol` if `inputCols` is set when saving the metadata.
      // TODO: If we modify the persistence mechanism later to better handle default params,
      // we can get rid of this.
      var paramWithoutOutputCol: Option[JValue] = None
      if (instance.isSet(instance.inputCols)) {
        val params = instance.extractParamMap().toSeq
        val jsonParams = params.filter(_.param != instance.outputCol).map { case ParamPair(p, v) =>
          p.name -> parse(p.jsonEncode(v))
        }.toList
        paramWithoutOutputCol = Some(render(jsonParams))
      }
      DefaultParamsWriter.saveMetadata(instance, path, sc, paramMap = paramWithoutOutputCol)
    }
  }

  @Since("1.6.0")
  override def load(path: String): QuantileDiscretizer = super.load(path)
}

