package org.apache.spark.ml.odkl.texts

import org.apache.lucene.analysis.util.StopwordAnalyzerBase
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.ml.Transformer
import org.apache.spark.ml.param.shared.HasOutputCol
import org.apache.spark.ml.param.{Param, ParamMap, Params}
import org.apache.spark.ml.util.{DefaultParamsReadable, DefaultParamsWritable, Identifiable, SchemaUtils}
import org.apache.spark.sql.{DataFrame, Dataset}
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types.{ArrayType, StringType, StructType}

/**
  * Created by eugeny.malyutin on 05.05.16.
  */

class LanguageAwareAnalyzer(override val uid: String) extends Transformer
  with HasOutputCol
  with Params
  with DefaultParamsWritable{

  @transient lazy val languageAnalyzerMap = {
    LanguageAwareStemmerUtil.languageAnalyzersMap.mapValues(analyzer => {
      new ThreadLocal[StopwordAnalyzerBase]() {
        override def initialValue() = analyzer()
      }
    }).asInstanceOf[Object]
  }

  @transient lazy val tokenizer = {
    languageAnalyzerMap.asInstanceOf[Map[String, ThreadLocal[StopwordAnalyzerBase]]]($(defaultLanguage))

  }
  val inputColLang = new Param[String](this, "inputColLang",
    "language code from langdetect")

  setDefault(inputColLang -> "lang")

  val inputColText = new Param[String](this, "inputColText",
    "column with text")

  setDefault(inputColText -> "text")

  val defaultLanguage = new Param[String](this, "defaultLanguage",
    "language to use as default if actual unknown")

  setDefault(defaultLanguage -> "ru")

  val stemmTextUDF = udf((lang: String, text: String) => {
    val analyzer = languageAnalyzerMap.asInstanceOf[Map[String, ThreadLocal[StopwordAnalyzerBase]]].getOrElse(lang, tokenizer).get()
    LanguageAwareStemmerUtil.stemmString(text, analyzer)
  })

  /** @group getParam */
  def getInputColHash: String = $(inputColLang)

  /** @group getParam */
  def getInputColText: String = $(inputColText)

  /** @group setParam */
  def setInputColLang(value: String): this.type = set(inputColLang, value)

  /** @group setParam */
  def setInputColText(value: String): this.type = set(inputColText, value)

  /** @group setParam */
  def setDefaultLanguage(value: String): this.type = set(defaultLanguage, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  override def copy(extra: ParamMap): Transformer = {
    defaultCopy(extra)
  }

  def this() = this(Identifiable.randomUID("languageAnalyzer"))

  override def transform(dataset: Dataset[_]): DataFrame = {
    dataset.withColumn($(outputCol), stemmTextUDF(dataset.col($(inputColLang)), dataset.col($(inputColText)))).toDF
  }

  @DeveloperApi
  override def transformSchema(schema: StructType): StructType = {
    if ($(inputColText) equals $(outputCol)) {
      val schemaWithoutInput = new StructType(schema.fields.filterNot(_.name equals $(inputColText)))
      SchemaUtils.appendColumn(schemaWithoutInput, $(outputCol), ArrayType(StringType, true))
    } else {
      SchemaUtils.appendColumn(schema, $(outputCol), ArrayType(StringType, true))
    }
  }

}

object LanguageAwareAnalyzer extends DefaultParamsReadable[LanguageAwareAnalyzer] {
  override def load(path: String): LanguageAwareAnalyzer = super.load(path)
}


